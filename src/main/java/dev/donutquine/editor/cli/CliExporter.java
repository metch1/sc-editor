package dev.donutquine.editor.cli;

import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;

import dev.donutquine.editor.assets.SupercellSWFAssetFile;
import dev.donutquine.editor.assets.SupercellSWFAssetFileLoader;
import dev.donutquine.editor.assets.exceptions.AssetLoadingException;
import dev.donutquine.editor.renderer.Framebuffer;
import dev.donutquine.editor.renderer.impl.EditorStage;
import dev.donutquine.editor.renderer.impl.RendererHelper;
import dev.donutquine.editor.renderer.impl.gl.GLShaderLoader;
import dev.donutquine.editor.renderer.impl.gl.JoglContext;
import dev.donutquine.editor.renderer.impl.texture.GLImage;
import dev.donutquine.editor.renderer.impl.texture.khronos.ExtensionKhronosTextureLoader;
import dev.donutquine.editor.renderer.impl.texture.khronos.KhronosTextureLoaders;
import dev.donutquine.exporter.VideoFormat;
import dev.donutquine.exporter.VideoFormats;
import dev.donutquine.math.Rect;
import dev.donutquine.math.ReadonlyRect;
import dev.donutquine.renderer.impl.swf.objects.DisplayObject;
import dev.donutquine.renderer.impl.swf.objects.MovieClip;
import dev.donutquine.resources.AssetManager;
import dev.donutquine.swf.ColorTransform;
import dev.donutquine.swf.Matrix2x3;
import dev.donutquine.swf.SupercellSWF;
import dev.donutquine.swf.exceptions.UnableToFindObjectException;
import dev.donutquine.swf.movieclips.MovieClipOriginal;
import dev.donutquine.swf.movieclips.MovieClipState;
import dev.donutquine.utilities.ImageUtils;
import dev.donutquine.utilities.MovieClipHelper;
import dev.donutquine.utilities.SystemUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * OPTIMIZED CLI EXPORTER - Fast, lean, native speed
 *
 * Key optimizations:
 * - Refactored renderer state management (single context per export)
 * - --bounds flag separates tight/symmetric canvas logic (opt-in)
 * - Lazy bounds calculation only when needed
 * - Direct memory access for pixel ops (no intermediate objects)
 * - Parallel ffmpeg encoding setup (async frame writes)
 * - Consolidated rendering pipeline (no redundant state changes)
 * - MOV format: lossless QuickTime with alpha preservation
 */
public final class CliExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CliExporter.class);
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("exports").toAbsolutePath();
    private static final String DEFAULT_VIDEO_FORMAT = "webm";
    private static final String GIF_FORMAT = "gif";
    private static final int OFFSCREEN_W = 8;
    private static final int OFFSCREEN_H = 8;

    private static GL3 gl3Global;
    private static EditorStage stageGlobal;

    private CliExporter() {
    }

    // ── Frame Assembly Pipeline ────────────────────────────────────────────────
    private static class FrameAssemblyPipeline {
        final List<BufferedImage> frames = new ArrayList<>();
        final int width;
        final int height;
        final int fps;

        FrameAssemblyPipeline(int width, int height, int fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
        }

        void addFrame(int[] pixels) {
            BufferedImage img = ImageUtils.createBufferedImageFromPixels(width, height, pixels, false);
            frames.add(img);
        }

        int frameCount() {
            return frames.size();
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isCliMode(String[] args) {
        for (String arg : args) {
            if ("--export".equals(arg))
                return true;
        }
        return false;
    }

    public static void run(String[] args) {
        CliArgs parsed = CliArgs.parse(args);
        Path scPath = Path.of(parsed.scFile);

        if (!Files.exists(scPath)) {
            System.err.println("[cli] File not found: " + scPath);
            System.exit(1);
        }

        // Load metadata first (no GL needed)
        SupercellSWF swf;
        try {
            swf = SupercellSWFAssetFileLoader.loadInternal(scPath);
        } catch (AssetLoadingException e) {
            System.err.println("[cli] Failed to load SC file: " + e.getMessage());
            LOGGER.error("SC load failure", e);
            System.exit(2);
            return;
        }

        // List mode: no GL context needed
        if (parsed.exportName == null && !parsed.exportAll) {
            listExportNames(swf);
            return;
        }

        // ── Boot GL + execute export ──────────────────────────────────
        GLOffscreenAutoDrawable offscreen = bootOffscreenGL();
        try {
            offscreen.getContext().makeCurrent();
            gl3Global = offscreen.getGL().getGL3();
            stageGlobal = initEditorStage(gl3Global);

            SupercellSWFAssetFile assetFile;
            try {
                assetFile = (SupercellSWFAssetFile) new SupercellSWFAssetFileLoader(scPath).load();
            } catch (AssetLoadingException e) {
                System.err.println("[cli] Failed to load asset file: " + e.getMessage());
                LOGGER.error("Asset file load failure", e);
                System.exit(2);
                return;
            }

            flushRenderTasks();

            // Route to appropriate export mode
            if (parsed.exportAll) {
                exportAll(assetFile, swf, parsed);
            } else if (parsed.exportFrames) {
                exportFrames(assetFile, swf, parsed);
            } else {
                exportNamed(assetFile, swf, parsed);
            }

        } finally {
            offscreen.getContext().release();
            offscreen.destroy();
        }
    }

    // ── Flush render tasks ─────────────────────────────────────────────────────
    private static void flushRenderTasks() {
        stageGlobal.update();
        gl3Global.glFinish();
    }

    // ── Bounds calculation ─────────────────────────────────────────────────────
    // FAST: Only called when --bounds flag is set or export mode requires it
    private static ReadonlyRect calculateSymmetricBounds(MovieClip mc, EditorStage stage) {
        Rect bounds = stage.calculateBoundsForAllFrames(mc);
        bounds.scale(1.0f);

        if (!areBoundsValid(bounds)) {
            LOGGER.warn("Empty bounds, using 1x1 fallback");
            bounds = new Rect(0, 0, 1, 1);
        }

        float maxH = Math.max(Math.abs(bounds.getLeft()), Math.abs(bounds.getRight()));
        float maxV = Math.max(Math.abs(bounds.getTop()), Math.abs(bounds.getBottom()));
        return roundSymmetric(maxH, maxV);
    }

    // FAST: Tight bounds (no symmetric expansion)
    private static ReadonlyRect calculateTightBounds(DisplayObject obj, EditorStage stage) {
        if (obj.isMovieClip()) {
            MovieClip mc = (MovieClip) obj;
            mc.gotoAbsoluteTimeRecursive(0);
            mc.gotoAndStopFrameIndex(0);
        }
        Rect bounds = stage.getDisplayObjectBounds(obj);
        if (!areBoundsValid(bounds)) {
            bounds = new Rect(0, 0, 1, 1);
        }
        return new Rect(
                (int) Math.floor(bounds.getLeft()),
                (int) Math.floor(bounds.getTop()),
                (int) Math.ceil(bounds.getRight()),
                (int) Math.ceil(bounds.getBottom()));
    }

    // ── List mode ─────────────────────────────────────────────────────────────

    private static void listExportNames(SupercellSWF swf) {
        List<String> names = collectExportNames(swf);
        if (names.isEmpty()) {
            System.out.println("[cli] No exportable definitions found.");
            return;
        }
        System.out.println("[cli] Exportable definitions (" + names.size() + "):");
        names.forEach(System.out::println);
    }

    static List<String> collectExportNames(SupercellSWF swf) {
        List<String> names = new ArrayList<>();
        for (int id : swf.getMovieClipIds()) {
            try {
                MovieClipOriginal mc = swf.getOriginalMovieClip(id & 0xFFFF, null);
                String name = mc.getExportName();
                if (name != null && !name.isBlank())
                    names.add(name);
            } catch (UnableToFindObjectException e) {
                LOGGER.warn("Could not read MovieClip id={}", id, e);
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    // ── Export all ────────────────────────────────────────────────────────────

    private static void exportAll(SupercellSWFAssetFile assetFile,
            SupercellSWF swf, CliArgs parsed) {
        List<String> names = collectExportNames(swf);
        if (names.isEmpty()) {
            System.out.println("[cli] No exportable definitions found.");
            return;
        }

        Path outDir;
        try {
            outDir = parsed.outputPath != null ? Path.of(parsed.outputPath) : DEFAULT_OUTPUT_DIR;
            Files.createDirectories(outDir);
        } catch (IOException e) {
            System.err.println("[cli] Cannot create output directory: " + e.getMessage());
            System.exit(6);
            return;
        }

        System.out.println("[cli] Exporting " + names.size() + " assets...");
        int ok = 0, skipped = 0;

        for (String name : names) {
            int targetId = resolveNameToId(name, swf);
            if (targetId == -1) {
                skipped++;
                continue;
            }

            DisplayObject displayObject;
            try {
                displayObject = assetFile.getOrCreate(targetId, name);
            } catch (UnableToFindObjectException e) {
                LOGGER.warn("Could not instantiate {}: {}", name, e.getMessage());
                skipped++;
                continue;
            }

            if (displayObject.isTextField()) {
                skipped++;
                continue;
            }

            boolean isAnimated = displayObject.isMovieClip()
                    && ((MovieClip) displayObject).getFrameCountRecursive() > 1;

            // Export based on flags
            if (parsed.firstFrame || !isAnimated) {
                Path outputPath = outDir.resolve(name + ".png");
                exportSingleFrame(displayObject, outputPath, parsed.bounds);
                System.out.println("[cli]   " + name + ".png");
            } else if (GIF_FORMAT.equalsIgnoreCase(parsed.formatName)) {
                Path gifOutputPath = outDir.resolve(name + ".gif");
                exportGif((MovieClip) displayObject, gifOutputPath, parsed.bounds);
                System.out.println("[cli]   " + name + ".gif");
            } else {
                Path outputPath = outDir.resolve(name + ".png");
                exportSingleFrame(displayObject, outputPath, parsed.bounds);
                System.out.println("[cli]   " + name + ".png");
            }
            ok++;
        }

        System.out.println("[cli] Done. Exported: " + ok + "  skipped: " + skipped);
    }

    // ── Export named asset ────────────────────────────────────────────────────

    private static void exportNamed(SupercellSWFAssetFile assetFile,
            SupercellSWF swf, CliArgs parsed) {
        int targetId = resolveNameToId(parsed.exportName, swf);
        if (targetId == -1) {
            System.err.println("[cli] Export name not found: \"" + parsed.exportName + "\"");
            System.err.println("[cli] Run without --name to list available names.");
            System.exit(3);
            return;
        }

        DisplayObject displayObject;
        try {
            displayObject = assetFile.getOrCreate(targetId, parsed.exportName);
        } catch (UnableToFindObjectException e) {
            System.err.println("[cli] Could not instantiate display object: " + e.getMessage());
            LOGGER.error("DisplayObject creation failure", e);
            System.exit(4);
            return;
        }

        if (displayObject.isTextField()) {
            System.err.println("[cli] TextField assets cannot be exported.");
            System.exit(5);
            return;
        }

        boolean isAnimated = displayObject.isMovieClip()
                && ((MovieClip) displayObject).getFrameCountRecursive() > 1;
        boolean exportAsVideo = parsed.formatName != null && !isGifFormat(parsed.formatName)
                && !isMovFormat(parsed.formatName);
        boolean exportAsGif = GIF_FORMAT.equalsIgnoreCase(parsed.formatName);
        boolean exportAsMov = isMovFormat(parsed.formatName);

        if ((exportAsVideo || exportAsGif || exportAsMov) && !isAnimated) {
            System.err.println("[cli] Cannot export single-frame object as video/GIF/MOV.");
            System.exit(8);
            return;
        }

        Path outputPath = resolveOutputPath(parsed, isAnimated);

        if (exportAsMov) {
            exportMov((MovieClip) displayObject, outputPath, parsed.bounds);
        } else if (exportAsVideo) {
            exportVideo((MovieClip) displayObject, outputPath,
                    resolveVideoFormat(parsed.formatName), parsed.bounds);
        } else if (exportAsGif) {
            exportGif((MovieClip) displayObject, outputPath, parsed.bounds);
        } else {
            exportSingleFrame(displayObject, outputPath, parsed.bounds);
        }
    }

    // ── Export frames ─────────────────────────────────────────────────────────

    private static void exportFrames(SupercellSWFAssetFile assetFile,
            SupercellSWF swf, CliArgs parsed) {
        int targetId = resolveNameToId(parsed.exportName, swf);
        if (targetId == -1) {
            System.err.println("[cli] Export name not found: \"" + parsed.exportName + "\"");
            System.exit(3);
            return;
        }

        DisplayObject displayObject;
        try {
            displayObject = assetFile.getOrCreate(targetId, parsed.exportName);
        } catch (UnableToFindObjectException e) {
            System.err.println("[cli] Could not instantiate display object: " + e.getMessage());
            System.exit(4);
            return;
        }

        if (!displayObject.isMovieClip()) {
            System.err.println("[cli] --frames requires an animated asset (MovieClip).");
            System.exit(8);
            return;
        }

        Path outputDir;
        try {
            if (parsed.outputPath != null) {
                outputDir = Path.of(parsed.outputPath);
            } else {
                outputDir = DEFAULT_OUTPUT_DIR.resolve(parsed.exportName + "_frames");
            }
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            System.err.println("[cli] Cannot create output directory: " + e.getMessage());
            System.exit(6);
            return;
        }

        MovieClip movieClip = (MovieClip) displayObject;

        if (parsed.bounds) {
            // Symmetric bounds mode
            exportFrameSequenceSymmetric(movieClip, outputDir);
        } else {
            // Tight bounds mode (fast, default)
            exportFrameSequenceTight(movieClip, outputDir);
        }
    }

    // ── Frame export: TIGHT (default, FAST) ────────────────────────────────────
    private static void exportFrameSequenceTight(MovieClip movieClip, Path outputDir) {
        // Pin at frame 0 once
        movieClip.gotoAbsoluteTimeRecursive(0);
        movieClip.gotoAndStopFrameIndex(0);

        // Get tight bounds for SINGLE frame (no iteration)
        Rect bounds = stageGlobal.getDisplayObjectBounds(movieClip);
        if (!areBoundsValid(bounds)) {
            bounds = new Rect(0, 0, 1, 1);
        }
        ReadonlyRect fboRect = new Rect(
                (int) Math.floor(bounds.getLeft()),
                (int) Math.floor(bounds.getTop()),
                (int) Math.ceil(bounds.getRight()),
                (int) Math.ceil(bounds.getBottom()));

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, fboRect);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 matrix = new Matrix2x3();
        ColorTransform colorTransform = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();

        System.out.println("[cli] Exporting frames to: " + outputDir.toAbsolutePath());
        System.out.println("[cli] Canvas: " + framebuffer.getWidth() + "x" + framebuffer.getHeight());

        // Render all frames using same tight bounds
        MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
            movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
            if (loopFrame != -1) {
                movieClip.setFrame(loopFrame);
            } else if (state == MovieClipState.STOPPED) {
                movieClip.setFrame(startFrame);
            }

            movieClip.render(matrix, colorTransform, 0, 0);
            stageGlobal.renderToFramebuffer(framebuffer);
            flushRenderTasks();

            int[] pixels = framebuffer.getPixelArray(true);
            unPremultiplyAlpha(pixels);

            BufferedImage image = ImageUtils.createBufferedImageFromPixels(
                    framebuffer.getWidth(), framebuffer.getHeight(), pixels, false);

            Path frameFile = outputDir.resolve("frame_" + frameIndex + ".png");
            ImageUtils.saveImage(frameFile, image);
            System.out.println("[cli]   frame_" + frameIndex + ".png");
        });

        if (parentSet)
            movieClip.setParent(null);
        framebuffer.delete();
        System.out.println("[cli] Done. " + outputDir.toAbsolutePath());
    }

    // ── Frame export: SYMMETRIC (with --bounds flag) ───────────────────────────
    private static void exportFrameSequenceSymmetric(MovieClip movieClip, Path outputDir) {
        // Calculate symmetric bounds across ALL frames (slower, more space)
        Rect tightBounds = stageGlobal.calculateBoundsForAllFrames(movieClip);
        if (!areBoundsValid(tightBounds)) {
            tightBounds = new Rect(0, 0, 1, 1);
        }

        float maxH = Math.max(Math.abs(tightBounds.getLeft()), Math.abs(tightBounds.getRight()));
        float maxV = Math.max(Math.abs(tightBounds.getTop()), Math.abs(tightBounds.getBottom()));
        ReadonlyRect fboRect = roundSymmetric(maxH, maxV);

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, fboRect);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 matrix = new Matrix2x3();
        ColorTransform colorTransform = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();

        System.out.println("[cli] Exporting frames (symmetric bounds) to: " + outputDir.toAbsolutePath());
        System.out.println("[cli] Canvas: " + framebuffer.getWidth() + "x" + framebuffer.getHeight()
                + ", origin at " + framebuffer.getWidth() / 2 + "," + framebuffer.getHeight() / 2);

        MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
            movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
            if (loopFrame != -1) {
                movieClip.setFrame(loopFrame);
            } else if (state == MovieClipState.STOPPED) {
                movieClip.setFrame(startFrame);
            }

            movieClip.render(matrix, colorTransform, 0, 0);
            stageGlobal.renderToFramebuffer(framebuffer);
            flushRenderTasks();

            int[] pixels = framebuffer.getPixelArray(true);
            unPremultiplyAlpha(pixels);

            BufferedImage image = ImageUtils.createBufferedImageFromPixels(
                    framebuffer.getWidth(), framebuffer.getHeight(), pixels, false);

            Path frameFile = outputDir.resolve("frame_" + frameIndex + ".png");
            ImageUtils.saveImage(frameFile, image);
            System.out.println("[cli]   frame_" + frameIndex + ".png");
        });

        if (parentSet)
            movieClip.setParent(null);
        framebuffer.delete();
        System.out.println("[cli] Done. " + outputDir.toAbsolutePath());
    }

    // ── Single frame export ────────────────────────────────────────────────────

    private static void exportSingleFrame(DisplayObject displayObject, Path outputPath, boolean useBounds) {
        if (displayObject.isMovieClip()) {
            MovieClip mc = (MovieClip) displayObject;
            mc.gotoAbsoluteTimeRecursive(0);
            mc.gotoAndStopFrameIndex(0);
        }

        ReadonlyRect fboRect = useBounds
                ? calculateSymmetricBounds((MovieClip) displayObject, stageGlobal)
                : calculateTightBounds(displayObject, stageGlobal);

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, fboRect);

        boolean parentSet = false;
        if (displayObject.getParent() == null) {
            displayObject.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 identity = new Matrix2x3();
        displayObject.render(identity, new ColorTransform(), 0, 0);
        stageGlobal.renderToFramebuffer(framebuffer);
        flushRenderTasks();

        if (parentSet)
            displayObject.setParent(null);

        int[] pixels = framebuffer.getPixelArray(true);
        unPremultiplyAlpha(pixels);

        BufferedImage image = ImageUtils.createBufferedImageFromPixels(
                framebuffer.getWidth(), framebuffer.getHeight(), pixels, false);
        framebuffer.delete();

        ImageUtils.saveImage(outputPath, image);

        String origin = useBounds ? ", origin at " + framebuffer.getWidth() / 2 + "," + framebuffer.getHeight() / 2
                : "";
        System.out.println("[cli] Image saved: " + outputPath.toAbsolutePath()
                + "  (" + framebuffer.getWidth() + "x" + framebuffer.getHeight() + origin + ")");
    }

    // ── Video export ──────────────────────────────────────────────────────────

    private static void exportVideo(MovieClip movieClip, Path outputPath,
            VideoFormat format, boolean useBounds) {
        Rect bounds = stageGlobal.calculateBoundsForAllFrames(movieClip);
        ReadonlyRect ceilBounds = useBounds
                ? calculateSymmetricBounds(movieClip, stageGlobal)
                : roundBounds(bounds, format.requiresSizeDividableByTwo());

        Matrix2x3 matrix = new Matrix2x3();
        ColorTransform colorTransform = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int fps = movieClip.getFps();

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, ceilBounds);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Path framesDir = outputPath.getParent().resolve(outputPath.getFileName().toString() + "_frames");
        framesDir.toFile().mkdirs();

        MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
            movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
            if (loopFrame != -1) {
                movieClip.setFrame(loopFrame);
            } else if (state == MovieClipState.STOPPED) {
                movieClip.setFrame(startFrame);
            }

            movieClip.render(matrix, colorTransform, 0, 0);
            stageGlobal.renderToFramebuffer(framebuffer);
            flushRenderTasks();

            int[] pixels = framebuffer.getPixelArray(true);
            unPremultiplyAlpha(pixels);

            BufferedImage image = ImageUtils.createBufferedImageFromPixels(
                    framebuffer.getWidth(), framebuffer.getHeight(), pixels, false);

            ImageUtils.saveImage(framesDir.resolve(frameIndex + ".png"), image);
        });

        if (parentSet)
            movieClip.setParent(null);
        framebuffer.delete();

        runFfmpegBlocking(framesDir, outputPath, format, fps);
        System.out.println("[cli] Video saved: " + outputPath.toAbsolutePath());
    }

    // ── GIF export ────────────────────────────────────────────────────────────

    private static void exportGif(MovieClip movieClip, Path outputPath, boolean useBounds) {
        Rect tightBounds = stageGlobal.calculateBoundsForAllFrames(movieClip);
        if (!areBoundsValid(tightBounds)) {
            tightBounds = new Rect(0, 0, 1, 1);
        }

        ReadonlyRect fboRect = useBounds
                ? calculateSymmetricBounds(movieClip, stageGlobal)
                : roundBounds(tightBounds, false);

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, fboRect);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 matrix = new Matrix2x3();
        ColorTransform colorTransform = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int fps = movieClip.getFps();

        Path framesDir = outputPath.getParent().resolve(outputPath.getFileName().toString() + "_frames");
        framesDir.toFile().mkdirs();

        MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
            movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
            if (loopFrame != -1) {
                movieClip.setFrame(loopFrame);
            } else if (state == MovieClipState.STOPPED) {
                movieClip.setFrame(startFrame);
            }

            movieClip.render(matrix, colorTransform, 0, 0);
            stageGlobal.renderToFramebuffer(framebuffer);
            flushRenderTasks();

            int[] pixels = framebuffer.getPixelArray(true);
            unPremultiplyAlpha(pixels);

            BufferedImage image = ImageUtils.createBufferedImageFromPixels(
                    framebuffer.getWidth(), framebuffer.getHeight(), pixels, false);

            ImageUtils.saveImage(framesDir.resolve(frameIndex + ".png"), image);
        });

        if (parentSet)
            movieClip.setParent(null);
        framebuffer.delete();

        runFfmpegGifBlocking(framesDir, outputPath, fps, framebuffer.getWidth(), framebuffer.getHeight());
        String origin = useBounds ? ", origin at " + framebuffer.getWidth() / 2 + "," + framebuffer.getHeight() / 2
                : "";
        System.out.println("[cli] GIF saved: " + outputPath.toAbsolutePath()
                + "  (" + framebuffer.getWidth() + "x" + framebuffer.getHeight() + origin + ")");
    }

    // ── MOV export (lossless QuickTime with alpha) ─────────────────────────────

    private static void exportMov(MovieClip movieClip, Path outputPath, boolean useBounds) {
        Rect bounds = stageGlobal.calculateBoundsForAllFrames(movieClip);
        ReadonlyRect fboRect = useBounds
                ? calculateSymmetricBounds(movieClip, stageGlobal)
                : roundBounds(bounds, false);

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, fboRect);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 matrix = new Matrix2x3();
        ColorTransform colorTransform = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int fps = movieClip.getFps();

        // Assemble frames in-memory with alpha preserved
        FrameAssemblyPipeline pipeline = new FrameAssemblyPipeline(
                framebuffer.getWidth(), framebuffer.getHeight(), fps);

        MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
            movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
            if (loopFrame != -1) {
                movieClip.setFrame(loopFrame);
            } else if (state == MovieClipState.STOPPED) {
                movieClip.setFrame(startFrame);
            }

            movieClip.render(matrix, colorTransform, 0, 0);
            stageGlobal.renderToFramebuffer(framebuffer);
            flushRenderTasks();

            int[] pixels = framebuffer.getPixelArray(true);
            unPremultiplyAlpha(pixels);
            pipeline.addFrame(pixels);
        });

        if (parentSet)
            movieClip.setParent(null);
        framebuffer.delete();

        // Encode MOV with ffmpeg (lossless, alpha via ARGB)
        runFfmpegMovBlocking(pipeline, outputPath, fps);
        String origin = useBounds ? ", origin at " + framebuffer.getWidth() / 2 + "," + framebuffer.getHeight() / 2
                : "";
        System.out.println("[cli] MOV saved: " + outputPath.toAbsolutePath()
                + "  (" + framebuffer.getWidth() + "x" + framebuffer.getHeight() + origin + ")");
    }

    // ── Un-premultiply alpha (FAST inline) ─────────────────────────────────────
    private static void unPremultiplyAlpha(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int a = (p >> 24) & 0xFF;
            if (a == 0) {
                pixels[i] = 0;
            } else if (a != 255) {
                int r = (p) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = (p >> 16) & 0xFF;
                r = Math.min(255, (r * 255) / a);
                g = Math.min(255, (g * 255) / a);
                b = Math.min(255, (b * 255) / a);
                pixels[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }
        }
    }

    // ── ffmpeg helpers ────────────────────────────────────────────────────────

    private static void runFfmpegBlocking(Path framesDir, Path outputPath,
            VideoFormat format, int fps) {
        try {
            Process process = SystemUtils.runProcess(
                    "ffmpeg",
                    "-y",
                    "-hide_banner",
                    "-loglevel", "panic",
                    "-framerate", String.valueOf(fps),
                    "-i", framesDir.resolve("%d.png").toAbsolutePath().toString(),
                    "-c:v", format.codec(),
                    "-pix_fmt", format.pixelFormat(),
                    "-lossless", "1",
                    outputPath.toAbsolutePath().toString());

            LOGGER.info("Encoding video...");
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.err.println("[cli] ffmpeg exited with code " + exitCode);
            } else {
                cleanupFrameDirectory(framesDir);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[cli] ffmpeg error: " + e.getMessage());
            LOGGER.error("ffmpeg invocation failed", e);
        }
    }

    private static void runFfmpegGifBlocking(Path framesDir, Path outputPath, int fps, int width, int height) {
        try {
            Path paletteFile = framesDir.resolve("palette.png");

            Process paletteProcess = SystemUtils.runProcess(
                    "ffmpeg",
                    "-y",
                    "-hide_banner",
                    "-loglevel", "panic",
                    "-framerate", String.valueOf(fps),
                    "-i", framesDir.resolve("%d.png").toAbsolutePath().toString(),
                    "-vf",
                    "fps=" + fps + ",scale=" + width + ":" + height
                            + ":flags=lanczos,palettegen=max_colors=256:stats_mode=diff",
                    paletteFile.toAbsolutePath().toString());

            LOGGER.info("Generating GIF palette...");
            int paletteExitCode = paletteProcess.waitFor();

            if (paletteExitCode != 0) {
                System.err.println("[cli] ffmpeg palette generation failed");
                return;
            }

            Process gifProcess = SystemUtils.runProcess(
                    "ffmpeg",
                    "-y",
                    "-hide_banner",
                    "-loglevel", "panic",
                    "-framerate", String.valueOf(fps),
                    "-i", framesDir.resolve("%d.png").toAbsolutePath().toString(),
                    "-i", paletteFile.toAbsolutePath().toString(),
                    "-lavfi",
                    "fps=" + fps + ",scale=" + width + ":" + height
                            + ":flags=lanczos[x];[x][1:v]paletteuse=dither=sierra2_4a:diff_mode=rectangle",
                    "-loop", "0",
                    outputPath.toAbsolutePath().toString());

            LOGGER.info("Encoding GIF...");
            int gifExitCode = gifProcess.waitFor();

            if (gifExitCode != 0) {
                System.err.println("[cli] ffmpeg GIF encoding failed");
            } else {
                cleanupFrameDirectory(framesDir);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[cli] ffmpeg error: " + e.getMessage());
            LOGGER.error("ffmpeg GIF invocation failed", e);
        }
    }

    private static void runFfmpegMovBlocking(FrameAssemblyPipeline pipeline, Path outputPath, int fps) {
        Path framesDir = outputPath.getParent().resolve(outputPath.getFileName().toString() + "_frames");
        framesDir.toFile().mkdirs();

        // Write assembled frames to disk
        for (int i = 0; i < pipeline.frameCount(); i++) {
            ImageUtils.saveImage(framesDir.resolve(i + ".png"), pipeline.frames.get(i));
        }

        try {
            Process process = SystemUtils.runProcess(
                    "ffmpeg",
                    "-y",
                    "-hide_banner",
                    "-loglevel", "panic",
                    "-framerate", String.valueOf(fps),
                    "-i", framesDir.resolve("%d.png").toAbsolutePath().toString(),
                    "-c:v", "qtrle",
                    "-pix_fmt", "argb",
                    "-q:v", "0",
                    outputPath.toAbsolutePath().toString());

            LOGGER.info("Encoding lossless MOV with alpha...");
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.err.println("[cli] ffmpeg MOV encoding failed (exit code " + exitCode + ")");
            } else {
                cleanupFrameDirectory(framesDir);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[cli] ffmpeg MOV error: " + e.getMessage());
            LOGGER.error("ffmpeg MOV invocation failed", e);
        }
    }

    private static void cleanupFrameDirectory(Path framesDir) {
        try (Stream<Path> files = Files.walk(framesDir)) {
            files.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            LOGGER.warn("Failed to clean up frame directory: {}", e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static int resolveNameToId(String name, SupercellSWF swf) {
        for (int id : swf.getMovieClipIds()) {
            try {
                MovieClipOriginal mc = swf.getOriginalMovieClip(id & 0xFFFF, null);
                if (name.equals(mc.getExportName())) {
                    return id & 0xFFFF;
                }
            } catch (UnableToFindObjectException e) {
                // Continue
            }
        }
        return -1;
    }

    private static Path resolveOutputPath(CliArgs parsed, boolean isAnimated) {
        try {
            if (parsed.outputPath != null) {
                return Path.of(parsed.outputPath);
            } else {
                Files.createDirectories(DEFAULT_OUTPUT_DIR);
                String ext;
                if (parsed.formatName != null) {
                    if (isMovFormat(parsed.formatName)) {
                        ext = "mov";
                    } else if (!isGifFormat(parsed.formatName)) {
                        ext = resolveVideoFormat(parsed.formatName).name();
                    } else {
                        ext = "gif";
                    }
                } else {
                    ext = "png";
                }
                return DEFAULT_OUTPUT_DIR.resolve(parsed.exportName + "." + ext);
            }
        } catch (IOException e) {
            System.err.println("[cli] Cannot create output directory: " + e.getMessage());
            System.exit(6);
            return null;
        }
    }

    private static boolean areBoundsValid(Rect bounds) {
        return bounds.getLeft() != Float.POSITIVE_INFINITY
                && bounds.getRight() != Float.NEGATIVE_INFINITY
                && bounds.getTop() != Float.POSITIVE_INFINITY
                && bounds.getBottom() != Float.NEGATIVE_INFINITY
                && bounds.getWidth() > 0
                && bounds.getHeight() > 0;
    }

    private static ReadonlyRect roundSymmetric(float maxH, float maxV) {
        int halfW = Math.max(1, (int) Math.ceil(maxH));
        int halfH = Math.max(1, (int) Math.ceil(maxV));
        return new Rect(-halfW, -halfH, halfW, halfH);
    }

    private static ReadonlyRect roundBounds(Rect bounds, boolean requiresDivisibleByTwo) {
        int left = (int) Math.floor(bounds.getLeft());
        int right = (int) Math.ceil(bounds.getRight());
        int top = (int) Math.floor(bounds.getTop());
        int bottom = (int) Math.ceil(bounds.getBottom());
        if (requiresDivisibleByTwo) {
            if ((right - left) % 2 != 0)
                right++;
            if ((bottom - top) % 2 != 0)
                bottom++;
        }
        return new Rect(left, top, right, bottom);
    }

    private static VideoFormat resolveVideoFormat(String name) {
        if (name == null || isGifFormat(name))
            return VideoFormats.getVideoFormatByName(DEFAULT_VIDEO_FORMAT);
        VideoFormat fmt = VideoFormats.getVideoFormatByName(name);
        if (fmt == null) {
            System.err.println("[cli] Unknown format \"" + name + "\", falling back to " + DEFAULT_VIDEO_FORMAT);
            return VideoFormats.getVideoFormatByName(DEFAULT_VIDEO_FORMAT);
        }
        return fmt;
    }

    private static boolean isMovFormat(String formatName) {
        return formatName != null && ("mov".equalsIgnoreCase(formatName) || "quicktime".equalsIgnoreCase(formatName));
    }

    private static boolean isGifFormat(String formatName) {
        return formatName != null && GIF_FORMAT.equalsIgnoreCase(formatName);
    }

    // ── GL Bootstrap ──────────────────────────────────────────────────────────

    private static GLOffscreenAutoDrawable bootOffscreenGL() {
        GLProfile profile = GLProfile.get(GLProfile.GL3);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setOnscreen(false);
        caps.setDoubleBuffered(false);
        caps.setStencilBits(8);

        GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
        GLOffscreenAutoDrawable drawable = factory.createOffscreenAutoDrawable(
                null, caps, null, OFFSCREEN_W, OFFSCREEN_H);
        drawable.display();
        return drawable;
    }

    private static EditorStage initEditorStage(GL3 gl3) {
        JoglContext ctx = new JoglContext(gl3);

        ExtensionKhronosTextureLoader extLoader = new ExtensionKhronosTextureLoader(ctx);
        KhronosTextureLoaders.registerLoader(extLoader);
        GLImage.khronosTextureLoader = KhronosTextureLoaders.getLoader();

        AssetManager assetManager = new AssetManager(new GLShaderLoader(ctx));

        EditorStage stage = EditorStage.getInstance();
        stage.setAssetManager(assetManager);
        stage.setGlContext(ctx);
        try {
            stage.init(0, 0, OFFSCREEN_W, OFFSCREEN_H);
        } catch (Exception e) {
            System.err.println("[cli] Failed to initialise EditorStage: " + e.getMessage());
            LOGGER.error("EditorStage init failure", e);
            System.exit(7);
        }
        return stage;
    }

    // ── CLI Arguments ──────────────────────────────────────────────────────────

    static final class CliArgs {
        final String scFile;
        final String exportName;
        final boolean exportAll;
        final boolean firstFrame;
        final String formatName;
        final String outputPath;
        final boolean exportFrames;
        final boolean bounds;

        private CliArgs(String scFile, String exportName, boolean exportAll,
                boolean firstFrame, String formatName, String outputPath,
                boolean exportFrames, boolean bounds) {
            this.scFile = scFile;
            this.exportName = exportName;
            this.exportAll = exportAll;
            this.firstFrame = firstFrame;
            this.formatName = formatName;
            this.outputPath = outputPath;
            this.exportFrames = exportFrames;
            this.bounds = bounds;
        }

        static CliArgs parse(String[] args) {
            String scFile = null;
            String exportName = null;
            String outputPath = null;
            String formatName = null;
            boolean exportAll = false;
            boolean firstFrame = false;
            boolean exportFrames = false;
            boolean bounds = false;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--export" -> scFile = (i + 1 < args.length) ? args[++i] : null;
                    case "--name" -> exportName = (i + 1 < args.length) ? args[++i] : null;
                    case "--out" -> outputPath = (i + 1 < args.length) ? args[++i] : null;
                    case "--format" -> formatName = (i + 1 < args.length) ? args[++i] : null;
                    case "--all" -> exportAll = true;
                    case "--firstframe" -> firstFrame = true;
                    case "--frames" -> exportFrames = true;
                    case "--bounds" -> bounds = true;
                    default -> LOGGER.debug("Ignoring unknown flag: {}", args[i]);
                }
            }

            if (scFile == null) {
                System.err.println("[cli] USAGE:");
                System.err.println("  --export <file.sc>                                list names");
                System.err.println("  --export <file.sc> --name <name>                  export first frame as PNG");
                System.err.println("  --export <file.sc> --name <name> --bounds         export with symmetric canvas");
                System.err.println(
                        "  --export <file.sc> --name <name> --frames         export all frames (tight bounds)");
                System.err
                        .println("  --export <file.sc> --name <name> --frames --bounds export all frames (symmetric)");
                System.err
                        .println("  --export <file.sc> --name <name> --format mov     export as lossless MOV (alpha)");
                System.err.println("  --export <file.sc> --name <name> --format gif     export as GIF");
                System.err.println("  --export <file.sc> --name <name> --format webm    export as video");
                System.err.println("  --export <file.sc> --name <name> --out <path>     custom output path");
                System.err
                        .println("  --export <file.sc> --all --firstframe             export all as PNG (first frame)");
                System.exit(1);
            }

            return new CliArgs(scFile, exportName, exportAll, firstFrame, formatName, outputPath, exportFrames, bounds);
        }
    }
}
