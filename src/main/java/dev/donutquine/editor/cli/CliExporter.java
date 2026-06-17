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

public final class CliExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CliExporter.class);
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("exports").toAbsolutePath();
    private static final String DEFAULT_VIDEO_FORMAT = "webm";
    private static final String GIF_FORMAT = "gif";

    // Offscreen surface just needs to exist to create a GL context.
    // Actual render targets are FBO-backed and sized per-asset.
    private static final int OFFSCREEN_W = 8;
    private static final int OFFSCREEN_H = 8;

    // The raw GL3 handle — kept so we can call glFinish() directly.
    private static GL3 gl3Global;

    private CliExporter() {
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

        // Load metadata (pure data, no GL) for the list command
        SupercellSWF swf;
        try {
            swf = SupercellSWFAssetFileLoader.loadInternal(scPath);
        } catch (AssetLoadingException e) {
            System.err.println("[cli] Failed to load SC file: " + e.getMessage());
            LOGGER.error("SC load failure", e);
            System.exit(2);
            return;
        }

        // List mode: no --name and no --all
        if (parsed.exportName == null && !parsed.exportAll) {
            listExportNames(swf);
            return;
        }

        // ── Boot real offscreen GL context ────────────────────────────────
        GLOffscreenAutoDrawable offscreen = bootOffscreenGL();
        try {
            offscreen.getContext().makeCurrent();
            gl3Global = offscreen.getGL().getGL3();
            initEditorStage(gl3Global);

            // Load asset file — this queues texture uploads via doInRenderThread.
            // We MUST flush that queue (via stage.update()) before rendering,
            // otherwise all textures stay blank on the GPU.
            SupercellSWFAssetFile assetFile;
            try {
                assetFile = (SupercellSWFAssetFile) new SupercellSWFAssetFileLoader(scPath).load();
            } catch (AssetLoadingException e) {
                System.err.println("[cli] Failed to load asset file: " + e.getMessage());
                LOGGER.error("Asset file load failure", e);
                System.exit(2);
                return;
            }

            // Flush all queued texture upload tasks.
            flushRenderTasks();

            if (parsed.exportAll) {
                exportAll(assetFile, swf, parsed);
            } else {
                exportNamed(assetFile, swf, parsed);
            }

        } finally {
            offscreen.getContext().release();
            offscreen.destroy();
        }
    }

    // ── Flush the EditorStage render-thread task queue ────────────────────────
    private static void flushRenderTasks() {
        EditorStage.getInstance().update();
        gl3Global.glFinish();
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

    // ── --all export mode ─────────────────────────────────────────────────────

    private static void exportAll(SupercellSWFAssetFile assetFile,
            SupercellSWF swf, CliArgs parsed) {
        List<String> names = collectExportNames(swf);
        if (names.isEmpty()) {
            System.out.println("[cli] No exportable definitions found.");
            return;
        }

        Path outDir;
        try {
            outDir = parsed.outputPath != null
                    ? Path.of(parsed.outputPath)
                    : DEFAULT_OUTPUT_DIR;
            Files.createDirectories(outDir);
        } catch (IOException e) {
            System.err.println("[cli] Cannot create output directory: " + e.getMessage());
            System.exit(6);
            return;
        }

        System.out.println("[cli] Exporting " + names.size() + " assets...");
        int ok = 0, skipped = 0;

        for (String name : names) {
            // Resolve name → id
            int targetId = -1;
            for (int id : swf.getMovieClipIds()) {
                try {
                    MovieClipOriginal mc = swf.getOriginalMovieClip(id & 0xFFFF, null);
                    if (name.equals(mc.getExportName())) {
                        targetId = id & 0xFFFF;
                        break;
                    }
                } catch (UnableToFindObjectException e) {
                    LOGGER.warn("Could not check MovieClip id={}", id, e);
                }
            }
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

            Path outputPath = outDir.resolve(name + ".png");
            EditorStage stage = EditorStage.getInstance();

            // --firstframe: always export frame 0 as PNG regardless of frame count
            exportFirstFrame(displayObject, outputPath, stage);
            System.out.println("[cli]   " + name + ".png");
            ok++;
        }

        System.out.println("[cli] Done. Exported: " + ok + "  skipped: " + skipped);
    }

    // ── Single named export mode ───────────────────────────────────────────────

    private static void exportNamed(SupercellSWFAssetFile assetFile,
            SupercellSWF swf, CliArgs parsed) {
        // Resolve export name → object ID
        int targetId = -1;
        for (int id : swf.getMovieClipIds()) {
            try {
                MovieClipOriginal mc = swf.getOriginalMovieClip(id & 0xFFFF, null);
                if (parsed.exportName.equals(mc.getExportName())) {
                    targetId = id & 0xFFFF;
                    break;
                }
            } catch (UnableToFindObjectException e) {
                LOGGER.warn("Could not check MovieClip id={}", id, e);
            }
        }

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

        // Check if exporting as video or GIF
        boolean isAnimated = displayObject.isMovieClip()
                && ((MovieClip) displayObject).getFrameCountRecursive() > 1;
        boolean exportAsVideo = parsed.formatName != null && !isGifFormat(parsed.formatName);
        boolean exportAsGif = GIF_FORMAT.equalsIgnoreCase(parsed.formatName);

        if ((exportAsVideo || exportAsGif) && !isAnimated) {
            System.err.println("[cli] Cannot export single-frame object as video/GIF. Use image export instead.");
            System.exit(8);
            return;
        }

        // Resolve output path
        Path outputPath;
        try {
            if (parsed.outputPath != null) {
                outputPath = Path.of(parsed.outputPath);
            } else {
                Files.createDirectories(DEFAULT_OUTPUT_DIR);
                String ext = exportAsVideo
                        ? resolveVideoFormat(parsed.formatName).name()
                        : exportAsGif
                                ? "gif"
                                : "png";
                outputPath = DEFAULT_OUTPUT_DIR.resolve(parsed.exportName + "." + ext);
            }
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
        } catch (IOException e) {
            System.err.println("[cli] Cannot create output directory: " + e.getMessage());
            System.exit(6);
            return;
        }

        EditorStage stage = EditorStage.getInstance();

        if (exportAsVideo) {
            exportVideo((MovieClip) displayObject, outputPath,
                    resolveVideoFormat(parsed.formatName), stage);
        } else if (exportAsGif) {
            exportGif((MovieClip) displayObject, outputPath, stage);
        } else {
            exportFirstFrame(displayObject, outputPath, stage);
        }
    }

    // ── First-frame image export ──────────────────────────────────────────────
    //
    // WHY TRANSPARENT PIXELS MATTER
    // ──────────────────────────────
    // SC assets live in a shared coordinate space. The world origin (0,0) is the
    // registration/anchor point every asset is positioned from at runtime.
    // If you export only the tight pixel bounding box of the visible geometry, you
    // lose that anchor: the consumer has no way to know where (0,0) sat inside the
    // image, so sprites composited together will misalign.
    //
    // The correct canvas is symmetric around (0,0):
    //
    // canvas_left = -max(|left|, |right|)
    // canvas_right = max(|left|, |right|)
    // canvas_top = -max(|top|, |bottom|)
    // canvas_bottom = max(|top|, |bottom|)
    //
    // This is exactly what DisplayObjectContextMenu.getRenderBounds() does in the
    // GUI.
    // The resulting canvas is always centred on (0,0), and world origin pixel
    // coords
    // are always (width/2, height/2) — predictable, no metadata needed.
    //
    // The extra space around the tight geometry is left as transparent (alpha=0),
    // which PNG handles fine.
    //
    // HOW THE CAMERA + FBO INTERACT
    // ──────────────────────────────
    // prepareStageForRendering(stage, canvasRect):
    // 1. camera.init(canvas.width, canvas.height)
    // → viewport = [-w/2, -h/2, w/2, h/2] (centred at origin)
    // 2. camera.moveToFit(canvasRect)
    // → offsetX = canvasRect.midX - 0 = 0 (symmetric canvas midX IS 0)
    // → offsetY = canvasRect.midY - 0 = 0
    // 3. updateClipArea() → clipArea = viewport (no pan needed)
    // 4. glOrthof(left, right, bottom, top, -1, 1) [Y-up clip space]
    //
    // Result: a vertex at world (0,0) maps to the exact centre of the FBO.
    // getPixelArray(true) flips Y (GL bottom-left → image top-left).
    // So in the saved PNG, world (0,0) = pixel (width/2, height/2). ✓
    //
    // BOUNDS FIX SUMMARY
    // ───────────────────
    // Old code problem 1: calculateBoundsForAllFrames called getDisplayObjectBounds
    // internally, which re-ran render(deltaTime=0). deltaTime=0 skips MovieClip
    // frame advancement, so child clips were frozen in whatever position the loop
    // left them — stale, wrong bounds per frame.
    // Old code problem 2: tight bounds were passed directly to
    // prepareStageForRendering,
    // discarding the transparent border around the world origin.
    // Old code problem 3: for single-frame objects, calculateBoundsForAllFrames
    // went
    // through the full multi-frame loop path unnecessarily.
    //
    // All three are fixed here.
    //
    private static void exportFirstFrame(DisplayObject displayObject,
            Path outputPath, EditorStage stage) {

        // ── Step 1: pin the clip at frame 0 ──────────────────────────────────
        // Do this BEFORE measuring bounds so the dry-run render and the real render
        // both see the identical frame. gotoAbsoluteTimeRecursive(0) recurses into
        // all nested child MovieClips too, so every level is at its t=0 state.
        if (displayObject.isMovieClip()) {
            MovieClip mc = (MovieClip) displayObject;
            mc.gotoAbsoluteTimeRecursive(0);
            mc.gotoAndStopFrameIndex(0);
        }

        // ── Step 2: measure tight geometry bounds in world space ──────────────
        // getDisplayObjectBounds() sets isCalculatingBounds=true and calls
        // displayObject.render(IDENTITY, ..., deltaTime=0). With deltaTime=0
        // MovieClip.render skips frame advancement and goes straight to super.render,
        // so the clip stays at frame 0 as we set above. Every Shape encountered
        // calls stage.startShape(rect, ...) which merges rect into this.bounds.
        Rect tightBounds = stage.getDisplayObjectBounds(displayObject);

        if (!areBoundsValid(tightBounds)) {
            LOGGER.warn("Empty bounds for {}, using 1x1 fallback", outputPath.getFileName());
            tightBounds = new Rect(0, 0, 1, 1);
        }

        // ── Step 3: expand to origin-symmetric canvas ─────────────────────────
        // This preserves the transparent space between the geometry and the world
        // origin (0,0). The canvas is always centred on (0,0), so any two exports
        // from the same SC file can be composited by simply overlaying the PNGs —
        // their origins are aligned by construction.
        //
        // Example: tight bounds = (-10, -5, 80, 60)
        // maxH = max(|-10|, |80|) = 80 → canvas X: -80..80 (160 px wide)
        // maxV = max(|-5|, |60|) = 60 → canvas Y: -60..60 (120 px tall)
        // world (0,0) is at pixel (80, 60) in the saved PNG.
        //
        float maxH = Math.max(Math.abs(tightBounds.getLeft()), Math.abs(tightBounds.getRight()));
        float maxV = Math.max(Math.abs(tightBounds.getTop()), Math.abs(tightBounds.getBottom()));
        // Guard: if geometry sits entirely on one side (e.g. left=5, right=80)
        // maxH=80 already covers the origin gap. No special case needed.

        // ── Step 4: round canvas outward to integer pixels ────────────────────
        // Using the symmetric canvas means width = 2*ceil(maxH), height = 2*ceil(maxV).
        // We round maxH/maxV up before doubling so the canvas stays symmetric.
        ReadonlyRect fboRect = roundSymmetric(maxH, maxV);

        // ── Step 5: configure camera + allocate FBO ───────────────────────────
        // Because the canvas is symmetric (midX=0, midY=0), moveToFit sets
        // camera offset to (0,0) — no pan. The viewport exactly covers world space
        // [-fboW/2 .. fboW/2] × [-fboH/2 .. fboH/2].
        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stage, fboRect);

        boolean parentSet = false;
        if (displayObject.getParent() == null) {
            displayObject.setParent(stage.getStageSprite());
            parentSet = true;
        }

        // ── Step 6: render (deltaTime=0 keeps clip at frame 0) ───────────────
        Matrix2x3 identity = new Matrix2x3();
        displayObject.render(identity, new ColorTransform(), 0, 0);

        // ── Step 7: flush geometry into FBO ──────────────────────────────────
        // renderToFramebuffer clears to (0,0,0,0), then endRendering flushes the
        // BatchedRenderer draw calls into the bound FBO.
        stage.renderToFramebuffer(framebuffer);

        // ── Step 8: GPU sync ──────────────────────────────────────────────────
        flushRenderTasks();

        if (parentSet)
            displayObject.setParent(null);

        // ── Step 9: read pixels, un-premultiply, save ─────────────────────────
        // getPixelArray(true) reads GL_RGBA bytes and flips Y.
        // Pixels are premultiplied (shader writes rgb*a), so we divide back.
        int[] pixels = framebuffer.getPixelArray(true);
        unPremultiplyAlpha(pixels);

        BufferedImage image = ImageUtils.createBufferedImageFromPixels(
                framebuffer.getWidth(), framebuffer.getHeight(), pixels, false);
        framebuffer.delete();

        ImageUtils.saveImage(outputPath, image);
        System.out.println("[cli] Image saved: " + outputPath.toAbsolutePath()
                + "  (" + framebuffer.getWidth() + "x" + framebuffer.getHeight()
                + ", origin at " + framebuffer.getWidth() / 2 + "," + framebuffer.getHeight() / 2 + ")");
    }

    // ── Un-premultiply alpha ───────────────────────────────────────────────────
    // The objects.fragment.glsl shader writes premultiplied RGB:
    // fragColor = vec4(color.rgb * colorMul.a, color.a)
    // PNG files expect straight (un-premultiplied) alpha.
    // Without this step, semi-transparent edges show as dark/black fringing.
    //
    // Pixel layout from glGetTexImage(GL_RGBA, GL_UNSIGNED_BYTE) on little-endian:
    // int bits 0-7 = R, 8-15 = G, 16-23 = B, 24-31 = A
    private static void unPremultiplyAlpha(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int a = (p >> 24) & 0xFF;
            if (a == 0) {
                pixels[i] = 0;
                continue;
            }
            if (a == 255)
                continue;
            int r = (p) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = (p >> 16) & 0xFF;
            r = Math.min(255, (r * 255) / a);
            g = Math.min(255, (g * 255) / a);
            b = Math.min(255, (b * 255) / a);
            pixels[i] = (a << 24) | (b << 16) | (g << 8) | r;
        }
    }

    // ── Video export ──────────────────────────────────────────────────────────

    private static void exportVideo(MovieClip movieClip, Path outputPath,
            VideoFormat format, EditorStage stage) {
        final float pixelSize = 1.0f;

        // Calculate bounds for all animation frames
        Rect bounds = stage.calculateBoundsForAllFrames(movieClip);
        bounds.scale(pixelSize);

        ReadonlyRect ceilBounds = roundBounds(bounds, format.requiresSizeDividableByTwo());

        Matrix2x3 matrix = new Matrix2x3();
        matrix.scaleMultiply(pixelSize, pixelSize);
        ColorTransform colorTransform = new ColorTransform();

        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int fps = movieClip.getFps();

        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stage, ceilBounds);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stage.getStageSprite());
            parentSet = true;
        }

        Path framesDir = outputPath.getParent().resolve(
                outputPath.getFileName().toString() + "_frames");
        framesDir.toFile().mkdirs();

        // Render all frames to temporary directory
        MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
            movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
            if (loopFrame != -1) {
                movieClip.setFrame(loopFrame);
            } else if (state == MovieClipState.STOPPED) {
                movieClip.setFrame(startFrame);
            }

            // Queue geometry
            movieClip.render(matrix, colorTransform, 0, 0);

            // Flush geometry into FBO
            stage.renderToFramebuffer(framebuffer);

            // Flush pending GL tasks + GPU sync
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

        // Encode frames to video using ffmpeg
        runFfmpegBlocking(framesDir, outputPath, format, fps);
        System.out.println("[cli] Video saved: " + outputPath.toAbsolutePath());
    }

    // ── GIF export ────────────────────────────────────────────────────────────
    // Renders all frames and encodes them to GIF using ffmpeg's built-in GIF
    // encoder.
    // Follows the EXACT SAME alignment and spacing logic as PNG export:
    // - Symmetric canvas centered on world origin (0,0)
    // - Transparent padding preserves sprite alignment metadata
    // - All frames aligned to same world coordinates
    //
    // This ensures GIFs can be perfectly overlaid with their PNG first-frame
    // equivalents.
    private static void exportGif(MovieClip movieClip, Path outputPath,
            EditorStage stage) {
        final float pixelSize = 1.0f;

        // ── Step 1: Calculate bounds for ALL frames ────────────────────────────
        // Unlike PNG (single frame), GIF needs the bounding box that encompasses
        // every frame of the animation. This ensures all frames fit within the same
        // canvas size.
        Rect tightBounds = stage.calculateBoundsForAllFrames(movieClip);
        tightBounds.scale(pixelSize);

        if (!areBoundsValid(tightBounds)) {
            LOGGER.warn("Empty bounds for {}, using 1x1 fallback", outputPath.getFileName());
            tightBounds = new Rect(0, 0, 1, 1);
        }

        // ── Step 2: Expand to origin-symmetric canvas ─────────────────────────
        // CRITICAL: Same logic as PNG export.
        // This creates a canvas centered at (0,0) with transparent padding around
        // the geometry. The world origin lands at pixel (width/2, height/2).
        //
        // Example: tight bounds = (-10, -5, 80, 60)
        // maxH = max(|-10|, |80|) = 80 → canvas X: -80..80 (160 px wide)
        // maxV = max(|-5|, |60|) = 60 → canvas Y: -60..60 (120 px tall)
        // All frames will render into this same symmetric canvas.
        float maxH = Math.max(Math.abs(tightBounds.getLeft()), Math.abs(tightBounds.getRight()));
        float maxV = Math.max(Math.abs(tightBounds.getTop()), Math.abs(tightBounds.getBottom()));

        // ── Step 3: Round to integer pixels (symmetric) ────────────────────────
        ReadonlyRect fboRect = roundSymmetric(maxH, maxV);

        // ── Step 4: Configure camera + allocate FBO ───────────────────────────
        // Same setup as PNG: symmetric canvas means viewport is centered at origin,
        // and world (0,0) maps to the exact centre of the framebuffer.
        Framebuffer framebuffer = RendererHelper.prepareStageForRendering(stage, fboRect);

        boolean parentSet = false;
        if (movieClip.getParent() == null) {
            movieClip.setParent(stage.getStageSprite());
            parentSet = true;
        }

        Matrix2x3 matrix = new Matrix2x3();
        matrix.scaleMultiply(pixelSize, pixelSize);
        ColorTransform colorTransform = new ColorTransform();

        MovieClipState state = movieClip.getState();
        int loopFrame = movieClip.getLoopFrame();
        int startFrame = movieClip.getCurrentFrame();
        int fps = movieClip.getFps();

        Path framesDir = outputPath.getParent().resolve(
                outputPath.getFileName().toString() + "_frames");
        framesDir.toFile().mkdirs();

        // ── Step 5: Render ALL frames to temporary directory ──────────────────
        // Each frame goes into the SAME FBO with the SAME camera setup, so all
        // frames are aligned to the same world coordinates.
        MovieClipHelper.doForAllFrames(movieClip, (frameIndex) -> {
            movieClip.gotoAbsoluteTimeRecursive(frameIndex * movieClip.getMsPerFrame());
            if (loopFrame != -1) {
                movieClip.setFrame(loopFrame);
            } else if (state == MovieClipState.STOPPED) {
                movieClip.setFrame(startFrame);
            }

            // Queue geometry for this frame
            movieClip.render(matrix, colorTransform, 0, 0);

            // Flush geometry into FBO
            stage.renderToFramebuffer(framebuffer);

            // Flush pending GL tasks + GPU sync
            flushRenderTasks();

            // ── Un-premultiply and save frame ────────────────────────────────
            int[] pixels = framebuffer.getPixelArray(true);
            unPremultiplyAlpha(pixels);

            BufferedImage image = ImageUtils.createBufferedImageFromPixels(
                    framebuffer.getWidth(), framebuffer.getHeight(), pixels, false);

            ImageUtils.saveImage(framesDir.resolve(frameIndex + ".png"), image);
        });

        if (parentSet)
            movieClip.setParent(null);
        framebuffer.delete();

        // ── Step 6: Encode frames to high-quality GIF using ffmpeg ────────────
        runFfmpegGifBlocking(framesDir, outputPath, fps, framebuffer.getWidth(), framebuffer.getHeight());
        System.out.println("[cli] GIF saved: " + outputPath.toAbsolutePath()
                + "  (" + framebuffer.getWidth() + "x" + framebuffer.getHeight()
                + ", origin at " + framebuffer.getWidth() / 2 + "," + framebuffer.getHeight() / 2 + ")");
    }

    // ── Blocking ffmpeg invocation for video ──────────────────────────────────

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

            LOGGER.info("Waiting for ffmpeg video encoding...");
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String err = new String(process.getErrorStream().readAllBytes());
                if (!err.isEmpty())
                    LOGGER.error("ffmpeg stderr: {}", err);
                System.err.println("[cli] ffmpeg exited with code " + exitCode);
            } else {
                // Clean up temporary frame files
                cleanupFrameDirectory(framesDir);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[cli] ffmpeg error: " + e.getMessage());
            LOGGER.error("ffmpeg invocation failed", e);
        }
    }

    // ── Blocking ffmpeg invocation for GIF ────────────────────────────────────
    // High-quality GIF encoding with optimized palette generation.
    // Two-pass encoding for maximum quality:
    // Pass 1: Generate optimized 256-color palette using palettegen filter
    // Pass 2: Encode GIF using palette with dithering and diffusion
    //
    // Quality settings:
    // - palettegen stats_mode=diff: analyzes color differences for better palette
    // - paletteuse dither=sierra2_4a: high-quality error diffusion dithering
    // - fps filter: smooth motion at original frame rate
    private static void runFfmpegGifBlocking(Path framesDir, Path outputPath, int fps, int width, int height) {
        try {
            Path paletteFile = framesDir.resolve("palette.png");

            // ── Pass 1: Generate optimized color palette ──────────────────────
            // stats_mode=diff uses color difference analysis for better palette selection
            // This is slower but produces much better results than default
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

            LOGGER.info("Generating GIF palette (pass 1/2, analyzing colors)...");
            int paletteExitCode = paletteProcess.waitFor();

            if (paletteExitCode != 0) {
                String err = new String(paletteProcess.getErrorStream().readAllBytes());
                if (!err.isEmpty())
                    LOGGER.error("ffmpeg palette generation stderr: {}", err);
                System.err.println("[cli] ffmpeg palette generation exited with code " + paletteExitCode);
                return;
            }

            // ── Pass 2: Encode GIF with optimized palette ────────────────────
            // sierra2_4a: High-quality error diffusion dithering (slow but beautiful)
            // bayer_scale=3: Controls bayer dithering intensity
            // diff_mode=rectangle: Optimizes palette remapping for rectangles of similar
            // color
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

            LOGGER.info("Encoding GIF with palette (pass 2/2, applying dithering)...");
            int gifExitCode = gifProcess.waitFor();

            if (gifExitCode != 0) {
                String err = new String(gifProcess.getErrorStream().readAllBytes());
                if (!err.isEmpty())
                    LOGGER.error("ffmpeg GIF encoding stderr: {}", err);
                System.err.println("[cli] ffmpeg GIF encoding exited with code " + gifExitCode);
            } else {
                // Clean up temporary frame files and palette
                cleanupFrameDirectory(framesDir);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[cli] ffmpeg error: " + e.getMessage());
            LOGGER.error("ffmpeg GIF invocation failed", e);
        }
    }

    // ── Clean up temporary frame directory ────────────────────────────────────
    private static void cleanupFrameDirectory(Path framesDir) {
        try (Stream<Path> files = Files.walk(framesDir)) {
            files.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            LOGGER.warn("Failed to clean up frame directory {}: {}", framesDir, e.getMessage());
        }
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    // Returns false if the Rect was never expanded from its infinite-sentinel
    // initial state, meaning no geometry was found (invisible / empty clip).
    private static boolean areBoundsValid(Rect bounds) {
        return bounds.getLeft() != Float.POSITIVE_INFINITY
                && bounds.getRight() != Float.NEGATIVE_INFINITY
                && bounds.getTop() != Float.POSITIVE_INFINITY
                && bounds.getBottom() != Float.NEGATIVE_INFINITY
                && bounds.getWidth() > 0
                && bounds.getHeight() > 0;
    }

    // Round a symmetric canvas: ceil each half-extent, then double.
    // Keeps the canvas perfectly symmetric so world (0,0) lands exactly at the
    // centre pixel. e.g. maxH=80.3f → halfW=81 → canvas -81..81 (162 px wide).
    private static ReadonlyRect roundSymmetric(float maxH, float maxV) {
        int halfW = (int) Math.ceil(maxH);
        int halfH = (int) Math.ceil(maxV);
        if (halfW < 1)
            halfW = 1;
        if (halfH < 1)
            halfH = 1;
        return new Rect(-halfW, -halfH, halfW, halfH);
    }

    // Round bounds outward to integer pixels for video export (requires divisible
    // by 2).
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
            System.err.println("[cli] Unknown format \"" + name
                    + "\", falling back to " + DEFAULT_VIDEO_FORMAT);
            return VideoFormats.getVideoFormatByName(DEFAULT_VIDEO_FORMAT);
        }
        return fmt;
    }

    private static boolean isGifFormat(String formatName) {
        return formatName != null && GIF_FORMAT.equalsIgnoreCase(formatName);
    }

    // ── GL bootstrap ──────────────────────────────────────────────────────────

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

    private static void initEditorStage(GL3 gl3) {
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
    }

    // ── CLI argument model ────────────────────────────────────────────────────

    static final class CliArgs {
        final String scFile;
        final String exportName; // null when listing or --all
        final boolean exportAll; // true when --all is present
        final boolean firstFrame; // true when --firstframe is present
        final String formatName; // video/gif format (webm, mp4, hevc, avi, gif) or null for first frame
        final String outputPath;

        private CliArgs(String scFile, String exportName,
                boolean exportAll, boolean firstFrame, String formatName, String outputPath) {
            this.scFile = scFile;
            this.exportName = exportName;
            this.exportAll = exportAll;
            this.firstFrame = firstFrame;
            this.formatName = formatName;
            this.outputPath = outputPath;
        }

        static CliArgs parse(String[] args) {
            String scFile = null;
            String exportName = null;
            String outputPath = null;
            String formatName = null;
            boolean exportAll = false;
            boolean firstFrame = false;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--export" -> {
                        if (i + 1 < args.length)
                            scFile = args[++i];
                    }
                    case "--name" -> {
                        if (i + 1 < args.length)
                            exportName = args[++i];
                    }
                    case "--out" -> {
                        if (i + 1 < args.length)
                            outputPath = args[++i];
                    }
                    case "--format" -> {
                        if (i + 1 < args.length)
                            formatName = args[++i];
                    }
                    case "--all" -> exportAll = true;
                    case "--firstframe" -> firstFrame = true;
                    default -> LOGGER.debug("Ignoring unknown flag: {}", args[i]);
                }
            }

            if (scFile == null) {
                System.err.println("[cli] --export <file.sc> is required");
                System.err.println("[cli] Usage:");
                System.err.println("  --export <file.sc>                                  list names");
                System.err.println("  --export <file.sc> --name <name>                    export first frame as PNG");
                System.err.println(
                        "  --export <file.sc> --name <name> --format webm      export as video (webm|mp4|hevc|avi)");
                System.err.println("  --export <file.sc> --name <name> --format gif       export as animated GIF");
                System.err.println("  --export <file.sc> --name <name> --out <path>       export to specific path");
                System.err.println(
                        "  --export <file.sc> --all --firstframe                export all as PNG (first frame)");
                System.err.println("  --export <file.sc> --all --firstframe --out <dir>    export all into dir");
                System.exit(1);
            }

            return new CliArgs(scFile, exportName, exportAll, firstFrame, formatName, outputPath);
        }
    }
}
