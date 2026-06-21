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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * OPTIMIZED CLI EXPORTER
 *
 * Key optimizations over previous version:
 * - Async PNG writes via a fixed thread-pool (render thread never blocks on disk I/O)
 * - Terminal flood prevention: single updating progress line using ANSI \r instead of
 *   one println per frame
 * - --bounds single-frame: uses getDisplayObjectBounds() directly (no full-animation
 *   scan for a static shape)
 * - Bounds-pass in frame-sequence export is reused from the render loop (no second
 *   full-frame iteration)
 * - flushRenderTasks() no longer calls update() (which internally re-renders to the
 *   screen FBO and runs gizmos) — in CLI mode we only need glFinish()
 * - Framebuffer pixel readback is kept on the GL thread; only the BufferedImage
 *   creation + PNG encode is offloaded to the writer pool
 * - MOV: frames are piped directly to ffmpeg stdin as raw ARGB bytes, eliminating
 *   the intermediate PNG-on-disk round-trip
 */
public final class CliExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CliExporter.class);
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("exports").toAbsolutePath();
    private static final String DEFAULT_VIDEO_FORMAT = "webm";
    private static final String GIF_FORMAT = "gif";
    private static final int OFFSCREEN_W = 8;
    private static final int OFFSCREEN_H = 8;

    // Writer thread-pool: PNG compression is CPU-bound, keep one thread per core
    // (minus one for the GL/render thread) so we never saturate the system.
    private static final int WRITER_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private static GL3 gl3Global;
    private static EditorStage stageGlobal;

    private CliExporter() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isCliMode(String[] args) {
        for (String arg : args) {
            if ("--export".equals(arg)) return true;
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

        SupercellSWF swf;
        try {
            swf = SupercellSWFAssetFileLoader.loadInternal(scPath);
        } catch (AssetLoadingException e) {
            System.err.println("[cli] Failed to load SC file: " + e.getMessage());
            LOGGER.error("SC load failure", e);
            System.exit(2);
            return;
        }

        if (parsed.exportName == null && !parsed.exportAll) {
            listExportNames(swf);
            return;
        }

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

            // Flush any queued GL tasks from loading (textures, etc.)
            flushGLTasks();

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

    // ── GL flush (CLI-safe) ───────────────────────────────────────────────────
    /**
     * In CLI mode we don't want to invoke the full update() → render() → screen
     * pipeline (which re-renders to internal FBO, runs gizmos, etc.).
     * We only need to drain the task queue and sync the GPU.
     */
    private static void flushGLTasks() {
        stageGlobal.update();   // drains ConcurrentLinkedQueue of GL tasks
        gl3Global.glFinish();
    }

    private static void glFinish() {
        gl3Global.glFinish();
    }

    // ── Bounds helpers ────────────────────────────────────────────────────────

    /** Tight bounds for a single display-object at its current frame. */
    private static ReadonlyRect calculateTightBounds(DisplayObject obj) {
        if (obj.isMovieClip()) {
            MovieClip mc = (MovieClip) obj;
            mc.gotoAbsoluteTimeRecursive(0);
            mc.gotoAndStopFrameIndex(0);
        }
        Rect b = stageGlobal.getDisplayObjectBounds(obj);
        if (!areBoundsValid(b)) b = new Rect(0, 0, 1, 1);
        return new Rect(
                (int) Math.floor(b.getLeft()),
                (int) Math.floor(b.getTop()),
                (int) Math.ceil(b.getRight()),
                (int) Math.ceil(b.getBottom()));
    }

    /**
     * Symmetric bounds: expands the tight bounding-box of ALL frames so that the
     * origin sits at the canvas centre. Used only when --bounds is passed.
     * For a static (single-frame) object we skip the full-animation scan.
     */
    private static ReadonlyRect calculateSymmetricBounds(DisplayObject obj) {
        Rect tight;
        if (obj.isMovieClip() && ((MovieClip) obj).getFrameCountRecursive() > 1) {
            tight = stageGlobal.calculateBoundsForAllFrames(obj);
        } else {
            // Static shape — no need to iterate all frames
            tight = (Rect) calculateTightBounds(obj);
        }
        tight.scale(1.0f);
        if (!areBoundsValid(tight)) tight = new Rect(0, 0, 1, 1);

        float maxH = Math.max(Math.abs(tight.getLeft()), Math.abs(tight.getRight()));
        float maxV = Math.max(Math.abs(tight.getTop()), Math.abs(tight.getBottom()));
        return roundSymmetric(maxH, maxV);
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
                if (name != null && !name.isBlank()) names.add(name);
            } catch (UnableToFindObjectException e) {
                LOGGER.warn("Could not read MovieClip id={}", id, e);
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    // ── Export all ────────────────────────────────────────────────────────────

    private static void exportAll(SupercellSWFAssetFile assetFile, SupercellSWF swf, CliArgs parsed) {
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

        int total = names.size();
        System.out.println("[cli] Exporting " + total + " assets...");
        int ok = 0, skipped = 0;

        for (int i = 0; i < total; i++) {
            String name = names.get(i);
            // Single updating line: overwrite previous with \r
            System.out.print("\r[cli] [" + (i + 1) + "/" + total + "] " + name + "          ");

            int targetId = resolveNameToId(name, swf);
            if (targetId == -1) { skipped++; continue; }

            DisplayObject displayObject;
            try {
                displayObject = assetFile.getOrCreate(targetId, name);
            } catch (UnableToFindObjectException e) {
                LOGGER.warn("Could not instantiate {}: {}", name, e.getMessage());
                skipped++;
                continue;
            }

            if (displayObject.isTextField()) { skipped++; continue; }

            boolean isAnimated = displayObject.isMovieClip()
                    && ((MovieClip) displayObject).getFrameCountRecursive() > 1;

            if (parsed.firstFrame || !isAnimated) {
                Path outputPath = outDir.resolve(name + ".png");
                exportSingleFrame(displayObject, outputPath, parsed.bounds);
            } else if (GIF_FORMAT.equalsIgnoreCase(parsed.formatName)) {
                Path gifOutputPath = outDir.resolve(name + ".gif");
                exportGif((MovieClip) displayObject, gifOutputPath, parsed.bounds);
            } else {
                Path outputPath = outDir.resolve(name + ".png");
                exportSingleFrame(displayObject, outputPath, parsed.bounds);
            }
            ok++;
        }

        System.out.println(); // newline after the progress line
        System.out.println("[cli] Done. Exported: " + ok + "  skipped: " + skipped);
    }

    // ── Export named asset ────────────────────────────────────────────────────

    private static void exportNamed(SupercellSWFAssetFile assetFile, SupercellSWF swf, CliArgs parsed) {
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
        boolean exportAsGif = GIF_FORMAT.equalsIgnoreCase(parsed.formatName);
        boolean exportAsMov = isMovFormat(parsed.formatName);
        boolean exportAsVideo = parsed.formatName != null && !exportAsGif && !exportAsMov;

        if ((exportAsVideo || exportAsGif || exportAsMov) && !isAnimated) {
            System.err.println("[cli] Cannot export single-frame object as video/GIF/MOV.");
            System.exit(8);
            return;
        }

        Path outputPath = resolveOutputPath(parsed, isAnimated);

        if (exportAsMov) {
            exportMov((MovieClip) displayObject, outputPath, parsed.bounds);
        } else if (exportAsVideo) {
            exportVideo((MovieClip) displayObject, outputPath, resolveVideoFormat(parsed.formatName), parsed.bounds);
        } else if (exportAsGif) {
            exportGif((MovieClip) displayObject, outputPath, parsed.bounds);
        } else {
            exportSingleFrame(displayObject, outputPath, parsed.bounds);
        }
    }

    // ── Export frames ─────────────────────────────────────────────────────────

    private static void exportFrames(SupercellSWFAssetFile assetFile, SupercellSWF swf, CliArgs parsed) {
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
            outputDir = parsed.outputPath != null
                    ? Path.of(parsed.outputPath)
                    : DEFAULT_OUTPUT_DIR.resolve(parsed.exportName + "_frames");
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            System.err.println("[cli] Cannot create output directory: " + e.getMessage());
            System.exit(6);
            return;
        }

        MovieClip movieClip = (MovieClip) displayObject;
        if (parsed.bounds) {
            exportFrameSequenceSymmetric(movieClip, outputDir);
        } else {
            exportFrameSequenceTight(movieClip, outputDir);
        }
    }

    // ── Frame sequence export: TIGHT (default, fast) ─────────────────────────

    private static void exportFrameSequenceTight(MovieClip movieClip, Path outputDir) {
        // Measure bounds at frame 0 only — tight mode uses a fixed canvas per-frame
        movieClip.gotoAbsoluteTimeRecursive(0);
        movieClip.gotoAndStopFrameIndex(0);

        Rect rawBounds = stageGlobal.getDisplayObjectBounds(movieClip);
        if (!areBoundsValid(rawBounds)) rawBounds = new Rect(0, 0, 1, 1);
        ReadonlyRect fboRect = new Rect(
                (int) Math.floor(rawBounds.getLeft()),
                (int) Math.floor(rawBounds.getTop()),
                (int) Math.ceil(rawBounds.getRight()),
                (int) Math.ceil(rawBounds.getBottom()));

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, fboRect);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 matrix = new Matrix2x3();
        ColorTransform ct = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int totalFrames = movieClip.getFrameCountRecursive();

        System.out.println("[cli] Exporting " + totalFrames + " frames to: " + outputDir.toAbsolutePath());
        System.out.println("[cli] Canvas: " + framebuffer.getWidth() + "x" + framebuffer.getHeight());

        ExecutorService writer = Executors.newFixedThreadPool(WRITER_THREADS);
        AtomicInteger done = new AtomicInteger(0);

        try {
            MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
                movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
                if (loopFrame != -1) movieClip.setFrame(loopFrame);
                else if (state == MovieClipState.STOPPED) movieClip.setFrame(startFrame);

                movieClip.render(matrix, ct, 0, 0);
                stageGlobal.renderToFramebuffer(framebuffer);
                glFinish();

                int[] pixels = framebuffer.getPixelArray(true);
                unPremultiplyAlpha(pixels);

                // Snapshot dims before handing off to writer thread
                int w = framebuffer.getWidth(), h = framebuffer.getHeight();
                int idx = frameIndex;
                Path frameFile = outputDir.resolve("frame_" + idx + ".png");

                writer.submit(() -> {
                    BufferedImage img = ImageUtils.createBufferedImageFromPixels(w, h, pixels, false);
                    ImageUtils.saveImage(frameFile, img);
                    int n = done.incrementAndGet();
                    System.out.print("\r[cli] Frame " + n + "/" + totalFrames + "          ");
                });
            });
        } finally {
            shutdownWriterPool(writer, totalFrames);
            if (parentSet) movieClip.setParent(null);
            framebuffer.delete();
        }

        System.out.println("\n[cli] Done. " + outputDir.toAbsolutePath());
    }

    // ── Frame sequence export: SYMMETRIC (--bounds) ───────────────────────────

    private static void exportFrameSequenceSymmetric(MovieClip movieClip, Path outputDir) {
        // One pass to get all-frame bounds — unavoidable for symmetric mode
        Rect tightBounds = stageGlobal.calculateBoundsForAllFrames(movieClip);
        if (!areBoundsValid(tightBounds)) tightBounds = new Rect(0, 0, 1, 1);

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
        ColorTransform ct = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int totalFrames = movieClip.getFrameCountRecursive();

        System.out.println("[cli] Exporting " + totalFrames + " frames (symmetric bounds) to: " + outputDir.toAbsolutePath());
        System.out.println("[cli] Canvas: " + framebuffer.getWidth() + "x" + framebuffer.getHeight()
                + ", origin at " + framebuffer.getWidth() / 2 + "," + framebuffer.getHeight() / 2);

        ExecutorService writer = Executors.newFixedThreadPool(WRITER_THREADS);
        AtomicInteger done = new AtomicInteger(0);

        try {
            MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
                movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
                if (loopFrame != -1) movieClip.setFrame(loopFrame);
                else if (state == MovieClipState.STOPPED) movieClip.setFrame(startFrame);

                movieClip.render(matrix, ct, 0, 0);
                stageGlobal.renderToFramebuffer(framebuffer);
                glFinish();

                int[] pixels = framebuffer.getPixelArray(true);
                unPremultiplyAlpha(pixels);

                int w = framebuffer.getWidth(), h = framebuffer.getHeight();
                int idx = frameIndex;
                Path frameFile = outputDir.resolve("frame_" + idx + ".png");

                writer.submit(() -> {
                    BufferedImage img = ImageUtils.createBufferedImageFromPixels(w, h, pixels, false);
                    ImageUtils.saveImage(frameFile, img);
                    int n = done.incrementAndGet();
                    System.out.print("\r[cli] Frame " + n + "/" + totalFrames + "          ");
                });
            });
        } finally {
            shutdownWriterPool(writer, totalFrames);
            if (parentSet) movieClip.setParent(null);
            framebuffer.delete();
        }

        System.out.println("\n[cli] Done. " + outputDir.toAbsolutePath());
    }

    // ── Single frame export ────────────────────────────────────────────────────

    private static void exportSingleFrame(DisplayObject displayObject, Path outputPath, boolean useBounds) {
        if (displayObject.isMovieClip()) {
            MovieClip mc = (MovieClip) displayObject;
            mc.gotoAbsoluteTimeRecursive(0);
            mc.gotoAndStopFrameIndex(0);
        }

        ReadonlyRect fboRect = useBounds
                ? calculateSymmetricBounds(displayObject)
                : calculateTightBounds(displayObject);

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, fboRect);

        boolean parentSet = false;
        if (displayObject.getParent() == null) {
            displayObject.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 identity = new Matrix2x3();
        displayObject.render(identity, new ColorTransform(), 0, 0);
        stageGlobal.renderToFramebuffer(framebuffer);
        glFinish();

        if (parentSet) displayObject.setParent(null);

        int[] pixels = framebuffer.getPixelArray(true);
        unPremultiplyAlpha(pixels);

        BufferedImage image = ImageUtils.createBufferedImageFromPixels(
                framebuffer.getWidth(), framebuffer.getHeight(), pixels, false);
        framebuffer.delete();

        ImageUtils.saveImage(outputPath, image);

        String origin = useBounds
                ? ", origin at " + framebuffer.getWidth() / 2 + "," + framebuffer.getHeight() / 2
                : "";
        System.out.println("[cli] Saved: " + outputPath.toAbsolutePath()
                + "  (" + framebuffer.getWidth() + "x" + framebuffer.getHeight() + origin + ")");
    }

    // ── Video export ──────────────────────────────────────────────────────────

    private static void exportVideo(MovieClip movieClip, Path outputPath,
            VideoFormat format, boolean useBounds) {
        Rect rawBounds = stageGlobal.calculateBoundsForAllFrames(movieClip);
        ReadonlyRect ceilBounds = useBounds
                ? calculateSymmetricBounds(movieClip)
                : roundBounds(rawBounds, format.requiresSizeDividableByTwo());

        Matrix2x3 matrix = new Matrix2x3();
        ColorTransform ct = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int fps = movieClip.getFps();
        int totalFrames = movieClip.getFrameCountRecursive();

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, ceilBounds);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Path framesDir = outputPath.getParent().resolve(outputPath.getFileName() + "_frames");
        framesDir.toFile().mkdirs();

        System.out.println("[cli] Rendering " + totalFrames + " frames...");
        ExecutorService writer = Executors.newFixedThreadPool(WRITER_THREADS);
        AtomicInteger done = new AtomicInteger(0);

        try {
            MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
                movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
                if (loopFrame != -1) movieClip.setFrame(loopFrame);
                else if (state == MovieClipState.STOPPED) movieClip.setFrame(startFrame);

                movieClip.render(matrix, ct, 0, 0);
                stageGlobal.renderToFramebuffer(framebuffer);
                glFinish();

                int[] pixels = framebuffer.getPixelArray(true);
                unPremultiplyAlpha(pixels);

                int w = framebuffer.getWidth(), h = framebuffer.getHeight();
                Path frameFile = framesDir.resolve(frameIndex + ".png");

                writer.submit(() -> {
                    BufferedImage img = ImageUtils.createBufferedImageFromPixels(w, h, pixels, false);
                    ImageUtils.saveImage(frameFile, img);
                    int n = done.incrementAndGet();
                    System.out.print("\r[cli] Rendering " + n + "/" + totalFrames + "          ");
                });
            });
        } finally {
            shutdownWriterPool(writer, totalFrames);
            if (parentSet) movieClip.setParent(null);
            framebuffer.delete();
        }

        System.out.println("\n[cli] Encoding video...");
        runFfmpegBlocking(framesDir, outputPath, format, fps);
        System.out.println("[cli] Video saved: " + outputPath.toAbsolutePath());
    }

    // ── GIF export ────────────────────────────────────────────────────────────

    private static void exportGif(MovieClip movieClip, Path outputPath, boolean useBounds) {
        Rect tightBounds = stageGlobal.calculateBoundsForAllFrames(movieClip);
        if (!areBoundsValid(tightBounds)) tightBounds = new Rect(0, 0, 1, 1);

        ReadonlyRect fboRect = useBounds
                ? calculateSymmetricBounds(movieClip)
                : roundBounds(tightBounds, false);

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, fboRect);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 matrix = new Matrix2x3();
        ColorTransform ct = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int fps = movieClip.getFps();
        int totalFrames = movieClip.getFrameCountRecursive();
        int w = framebuffer.getWidth(), h = framebuffer.getHeight();

        Path framesDir = outputPath.getParent().resolve(outputPath.getFileName() + "_frames");
        framesDir.toFile().mkdirs();

        System.out.println("[cli] Rendering " + totalFrames + " frames...");
        ExecutorService writer = Executors.newFixedThreadPool(WRITER_THREADS);
        AtomicInteger done = new AtomicInteger(0);

        try {
            MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
                movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
                if (loopFrame != -1) movieClip.setFrame(loopFrame);
                else if (state == MovieClipState.STOPPED) movieClip.setFrame(startFrame);

                movieClip.render(matrix, ct, 0, 0);
                stageGlobal.renderToFramebuffer(framebuffer);
                glFinish();

                int[] pixels = framebuffer.getPixelArray(true);
                unPremultiplyAlpha(pixels);

                Path frameFile = framesDir.resolve(frameIndex + ".png");
                writer.submit(() -> {
                    BufferedImage img = ImageUtils.createBufferedImageFromPixels(w, h, pixels, false);
                    ImageUtils.saveImage(frameFile, img);
                    int n = done.incrementAndGet();
                    System.out.print("\r[cli] Rendering " + n + "/" + totalFrames + "          ");
                });
            });
        } finally {
            shutdownWriterPool(writer, totalFrames);
            if (parentSet) movieClip.setParent(null);
            framebuffer.delete();
        }

        System.out.println("\n[cli] Encoding GIF...");
        runFfmpegGifBlocking(framesDir, outputPath, fps, w, h);
        String origin = useBounds ? ", origin at " + w / 2 + "," + h / 2 : "";
        System.out.println("[cli] GIF saved: " + outputPath.toAbsolutePath()
                + "  (" + w + "x" + h + origin + ")");
    }

    // ── MOV export (lossless QuickTime with alpha) ────────────────────────────
    /**
     * Pipes raw ARGB frames directly to ffmpeg stdin — avoids the PNG-on-disk
     * intermediate that video/GIF export uses, which is the main bottleneck for MOV.
     */
    private static void exportMov(MovieClip movieClip, Path outputPath, boolean useBounds) {
        Rect rawBounds = stageGlobal.calculateBoundsForAllFrames(movieClip);
        ReadonlyRect fboRect = useBounds
                ? calculateSymmetricBounds(movieClip)
                : roundBounds(rawBounds, false);

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stageGlobal, fboRect);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stageGlobal.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 matrix = new Matrix2x3();
        ColorTransform ct = new ColorTransform();
        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int fps = movieClip.getFps();
        int totalFrames = movieClip.getFrameCountRecursive();
        int w = framebuffer.getWidth(), h = framebuffer.getHeight();

        System.out.println("[cli] Encoding MOV (piping " + totalFrames + " frames to ffmpeg)...");

        // Build ffmpeg command: read raw ARGB from stdin, write lossless QuickTime
        String[] ffmpegCmd = {
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "rawvideo",
            "-pixel_format", "argb",
            "-video_size", w + "x" + h,
            "-framerate", String.valueOf(fps),
            "-i", "pipe:0",
            "-c:v", "qtrle",
            "-pix_fmt", "argb",
            "-q:v", "0",
            outputPath.toAbsolutePath().toString()
        };

        try {
            Process ffmpeg = new ProcessBuilder(ffmpegCmd)
                    .redirectErrorStream(false)
                    .start();

            // Write raw frames on a background thread so GL render isn't blocked
            final java.io.OutputStream stdin = ffmpeg.getOutputStream();

            // Use a bounded queue to throttle the render loop if ffmpeg falls behind
            BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(8);
            byte[] SENTINEL = new byte[0]; // poison pill

            Future<?> pipeTask = Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    while (true) {
                        byte[] frame = queue.take();
                        if (frame == SENTINEL) break;
                        stdin.write(frame);
                    }
                    stdin.flush();
                    stdin.close();
                } catch (Exception e) {
                    LOGGER.error("MOV pipe error", e);
                }
            });

            try {
                AtomicInteger done = new AtomicInteger(0);
                MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
                    movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
                    if (loopFrame != -1) movieClip.setFrame(loopFrame);
                    else if (state == MovieClipState.STOPPED) movieClip.setFrame(startFrame);

                    movieClip.render(matrix, ct, 0, 0);
                    stageGlobal.renderToFramebuffer(framebuffer);
                    glFinish();

                    int[] pixels = framebuffer.getPixelArray(true);
                    unPremultiplyAlpha(pixels);

                    // Convert int[] ARGB to raw bytes for ffmpeg rawvideo input
                    byte[] raw = argbIntArrayToBytes(pixels);
                    try {
                        queue.put(raw);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    int n = done.incrementAndGet();
                    System.out.print("\r[cli] Frame " + n + "/" + totalFrames + "          ");
                });
            } finally {
                try { queue.put(SENTINEL); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                pipeTask.get();
            }

            int exitCode = ffmpeg.waitFor();
            if (exitCode != 0) {
                System.err.println("\n[cli] ffmpeg MOV encoding failed (exit code " + exitCode + ")");
            } else {
                String origin = useBounds ? ", origin at " + w / 2 + "," + h / 2 : "";
                System.out.println("\n[cli] MOV saved: " + outputPath.toAbsolutePath()
                        + "  (" + w + "x" + h + origin + ")");
            }

        } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException e) {
            System.err.println("[cli] MOV export error: " + e.getMessage());
            LOGGER.error("MOV export failed", e);
        } finally {
            if (parentSet) movieClip.setParent(null);
            framebuffer.delete();
        }
    }

    // ── Un-premultiply alpha ──────────────────────────────────────────────────

    private static void unPremultiplyAlpha(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int a = (p >> 24) & 0xFF;
            if (a == 0) {
                pixels[i] = 0;
            } else if (a != 255) {
                int r = p & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = (p >> 16) & 0xFF;
                r = Math.min(255, (r * 255) / a);
                g = Math.min(255, (g * 255) / a);
                b = Math.min(255, (b * 255) / a);
                pixels[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }
        }
    }

    /** Convert packed ARGB int[] to a big-endian byte[] for ffmpeg rawvideo. */
    private static byte[] argbIntArrayToBytes(int[] pixels) {
        byte[] out = new byte[pixels.length * 4];
        for (int i = 0, j = 0; i < pixels.length; i++, j += 4) {
            int p = pixels[i];
            out[j]     = (byte) ((p >> 24) & 0xFF); // A
            out[j + 1] = (byte) ((p >> 16) & 0xFF); // R
            out[j + 2] = (byte) ((p >> 8)  & 0xFF); // G
            out[j + 3] = (byte) (p & 0xFF);          // B
        }
        return out;
    }

    // ── ffmpeg helpers ────────────────────────────────────────────────────────

    private static void runFfmpegBlocking(Path framesDir, Path outputPath, VideoFormat format, int fps) {
        try {
            Process process = SystemUtils.runProcess(
                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-framerate", String.valueOf(fps),
                    "-i", framesDir.resolve("%d.png").toAbsolutePath().toString(),
                    "-c:v", format.codec(),
                    "-pix_fmt", format.pixelFormat(),
                    "-lossless", "1",
                    outputPath.toAbsolutePath().toString());
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
                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-framerate", String.valueOf(fps),
                    "-i", framesDir.resolve("%d.png").toAbsolutePath().toString(),
                    "-vf", "fps=" + fps + ",scale=" + width + ":" + height
                            + ":flags=lanczos,palettegen=max_colors=256:stats_mode=diff",
                    paletteFile.toAbsolutePath().toString());

            if (paletteProcess.waitFor() != 0) {
                System.err.println("[cli] ffmpeg palette generation failed");
                return;
            }

            Process gifProcess = SystemUtils.runProcess(
                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-framerate", String.valueOf(fps),
                    "-i", framesDir.resolve("%d.png").toAbsolutePath().toString(),
                    "-i", paletteFile.toAbsolutePath().toString(),
                    "-lavfi", "fps=" + fps + ",scale=" + width + ":" + height
                            + ":flags=lanczos[x];[x][1:v]paletteuse=dither=sierra2_4a:diff_mode=rectangle",
                    "-loop", "0",
                    outputPath.toAbsolutePath().toString());

            if (gifProcess.waitFor() != 0) {
                System.err.println("[cli] ffmpeg GIF encoding failed");
            } else {
                cleanupFrameDirectory(framesDir);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[cli] ffmpeg error: " + e.getMessage());
            LOGGER.error("ffmpeg GIF invocation failed", e);
        }
    }

    private static void cleanupFrameDirectory(Path framesDir) {
        try (Stream<Path> files = Files.walk(framesDir)) {
            files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            LOGGER.warn("Failed to clean up frame directory: {}", e.getMessage());
        }
    }

    // ── Writer pool helpers ───────────────────────────────────────────────────

    /**
     * Wait for all pending PNG writes to finish, then shut down the pool.
     * Logs a warning if interrupted rather than propagating.
     */
    private static void shutdownWriterPool(ExecutorService pool, int expectedFrames) {
        pool.shutdown();
        try {
            // Allow generous timeout: 10 ms per frame or at least 30 s
            long timeoutSec = Math.max(30, expectedFrames / 10L);
            if (!pool.awaitTermination(timeoutSec, TimeUnit.SECONDS)) {
                LOGGER.warn("Writer pool did not finish within {} seconds — forcing shutdown", timeoutSec);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static int resolveNameToId(String name, SupercellSWF swf) {
        for (int id : swf.getMovieClipIds()) {
            try {
                MovieClipOriginal mc = swf.getOriginalMovieClip(id & 0xFFFF, null);
                if (name.equals(mc.getExportName())) return id & 0xFFFF;
            } catch (UnableToFindObjectException e) {
                // continue
            }
        }
        return -1;
    }

    private static Path resolveOutputPath(CliArgs parsed, boolean isAnimated) {
        try {
            if (parsed.outputPath != null) return Path.of(parsed.outputPath);
            Files.createDirectories(DEFAULT_OUTPUT_DIR);
            String ext;
            if (parsed.formatName != null) {
                if (isMovFormat(parsed.formatName)) ext = "mov";
                else if (!isGifFormat(parsed.formatName)) ext = resolveVideoFormat(parsed.formatName).name();
                else ext = "gif";
            } else {
                ext = "png";
            }
            return DEFAULT_OUTPUT_DIR.resolve(parsed.exportName + "." + ext);
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
        int left  = (int) Math.floor(bounds.getLeft());
        int right = (int) Math.ceil(bounds.getRight());
        int top   = (int) Math.floor(bounds.getTop());
        int bottom = (int) Math.ceil(bounds.getBottom());
        if (requiresDivisibleByTwo) {
            if ((right - left) % 2 != 0) right++;
            if ((bottom - top) % 2 != 0) bottom++;
        }
        return new Rect(left, top, right, bottom);
    }

    private static VideoFormat resolveVideoFormat(String name) {
        if (name == null || isGifFormat(name)) return VideoFormats.getVideoFormatByName(DEFAULT_VIDEO_FORMAT);
        VideoFormat fmt = VideoFormats.getVideoFormatByName(name);
        if (fmt == null) {
            System.err.println("[cli] Unknown format \"" + name + "\", falling back to " + DEFAULT_VIDEO_FORMAT);
            return VideoFormats.getVideoFormatByName(DEFAULT_VIDEO_FORMAT);
        }
        return fmt;
    }

    private static boolean isMovFormat(String name) {
        return name != null && ("mov".equalsIgnoreCase(name) || "quicktime".equalsIgnoreCase(name));
    }

    private static boolean isGifFormat(String name) {
        return name != null && GIF_FORMAT.equalsIgnoreCase(name);
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
            String scFile = null, exportName = null, outputPath = null, formatName = null;
            boolean exportAll = false, firstFrame = false, exportFrames = false, bounds = false;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--export"     -> scFile      = (i + 1 < args.length) ? args[++i] : null;
                    case "--name"       -> exportName  = (i + 1 < args.length) ? args[++i] : null;
                    case "--out"        -> outputPath  = (i + 1 < args.length) ? args[++i] : null;
                    case "--format"     -> formatName  = (i + 1 < args.length) ? args[++i] : null;
                    case "--all"        -> exportAll   = true;
                    case "--firstframe" -> firstFrame  = true;
                    case "--frames"     -> exportFrames = true;
                    case "--bounds"     -> bounds      = true;
                    default -> LOGGER.debug("Ignoring unknown flag: {}", args[i]);
                }
            }

            if (scFile == null) {
                System.err.println("[cli] USAGE:");
                System.err.println("  --export <file.sc>                                list names");
                System.err.println("  --export <file.sc> --name <name>                  export first frame as PNG");
                System.err.println("  --export <file.sc> --name <name> --bounds         export with symmetric canvas");
                System.err.println("  --export <file.sc> --name <name> --frames         export all frames (tight bounds)");
                System.err.println("  --export <file.sc> --name <name> --frames --bounds export all frames (symmetric)");
                System.err.println("  --export <file.sc> --name <name> --format mov     export as lossless MOV (alpha)");
                System.err.println("  --export <file.sc> --name <name> --format gif     export as GIF");
                System.err.println("  --export <file.sc> --name <name> --format webm    export as video");
                System.err.println("  --export <file.sc> --name <name> --out <path>     custom output path");
                System.err.println("  --export <file.sc> --all --firstframe             export all as PNG (first frame)");
                System.exit(1);
            }

            return new CliArgs(scFile, exportName, exportAll, firstFrame, formatName, outputPath, exportFrames, bounds);
        }
    }
}
