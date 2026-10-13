package com.vivo.lpa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.os.Environment;

import com.vivo.videoeditorsdk.layer.Clip;
import com.vivo.videoeditorsdk.layer.CropMode;
import com.vivo.videoeditorsdk.layer.MediaClip;
import com.vivo.videoeditorsdk.layer.OverlayItem;
import com.vivo.videoeditorsdk.layer.OverlayParameters;
import com.vivo.videoeditorsdk.layer.VideoOverlay;
import com.vivo.videoeditorsdk.beauty.BeautyParameters;
import com.vivo.videoeditorsdk.effect.ScaleTimelineEffect;
import com.vivo.videoeditorsdk.media.ExportParam;
import com.vivo.videoeditorsdk.theme.FadeTransition;
import com.vivo.videoeditorsdk.videoeditor.ThemeLibrary;
import com.vivo.videoeditorsdk.videoeditor.VideoEditorConfig;
import com.vivo.videoeditorsdk.videoeditor.VideoFactory;
import com.vivo.videoeditorsdk.videoeditor.VideoFactoryListener;
import com.vivo.videoeditorsdk.videoeditor.VideoProject;
import com.vivo.videoeditorsdk.videoreverse.VideoReverser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native media execution adapter. Python/ffmpeg is deliberately not used here. */
public final class VivoSdkExecutor {
    public interface Progress {
        void onProgress(int percent, String message);
    }

    public static final class Result {
        public final File output;
        public final String operation;
        public final long durationMs;

        Result(File output, String operation, long durationMs) {
            this.output = output;
            this.operation = operation;
            this.durationMs = durationMs;
        }
    }

    private static final long TIMEOUT_SECONDS = 240L;
    private static final ConcurrentHashMap<String, VideoFactory> ACTIVE =
            new ConcurrentHashMap<String, VideoFactory>();

    private VivoSdkExecutor() {
    }

    public static Result execute(Context context, String jobId, String operation,
                                 List<String> inputs, long startMs, long endMs,
                                 String layout, int requestedWidth, int requestedHeight,
                                 File outputDir, JSONObject layoutContext,
                                 Progress progress) throws Exception {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("native SDK operation requires at least one video");
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalStateException("cannot create output directory: " + outputDir);
        }
        VideoEditorConfig.init(context.getApplicationContext(), null);
        ArrayList<String> sdkInputs = new ArrayList<String>();
        for (int index = 0; index < inputs.size(); index++) {
            sdkInputs.add(stageInput(context, jobId, inputs.get(index), index));
        }
        if ("video_reverse".equals(operation)) {
            File output = reverseVideo(sdkInputs.get(0), outputDir);
            return new Result(output, operation, mediaDuration(output));
        }
        if (supportsReferenceVideoTool(operation)) {
            String second = sdkInputs.size() > 1 ? sdkInputs.get(1) : null;
            String third = sdkInputs.size() > 2 ? sdkInputs.get(2) : null;
            String effect = "video_speed".equals(operation)
                    ? String.valueOf(layoutContext == null ? 2.0 : layoutContext.optDouble("speed", 2.0))
                    : layoutContext == null ? "motion_light"
                    : layoutContext.optString("effect", "motion_light");
            File output = EditorVideoProcessor.process(
                    context, jobId, operation, sdkInputs.get(0), second, third, effect,
                    requestedWidth, requestedHeight, outputDir);
            return new Result(output, operation, mediaDuration(output));
        }
        ArrayList<MediaClip> clips = new ArrayList<MediaClip>();
        for (String path : sdkInputs) clips.add(requireMediaClip(path));

        boolean collage = "template_collage".equals(operation)
                || "smart_collage".equals(operation)
                || "concat_clips".equals(operation);
        int width = even(clips.get(0).getWidth());
        int height = even(clips.get(0).getHeight());
        if (width <= 0 || height <= 0) {
            int[] size = mediaSize(sdkInputs.get(0));
            width = size[0];
            height = size[1];
        }
        int duration;
        VideoProject project = new VideoProject();
        if (collage) {
            boolean horizontal = "horizontal".equalsIgnoreCase(layout);
            int canvasWidth = even(layoutContext != null
                    ? layoutContext.optInt("canvas_width", requestedWidth > 0 ? requestedWidth : 1080)
                    : (requestedWidth > 0 ? requestedWidth : 1080));
            int canvasHeight = even(layoutContext != null
                     ? layoutContext.optInt("canvas_height", requestedHeight > 0 ? requestedHeight : 1440)
                     : (requestedHeight > 0 ? requestedHeight : 1440));
            if (horizontal) {
                canvasWidth = even(layoutContext != null
                        ? layoutContext.optInt("canvas_width", requestedWidth > 0 ? requestedWidth : 1920)
                        : (requestedWidth > 0 ? requestedWidth : 1920));
                canvasHeight = even(layoutContext != null
                        ? layoutContext.optInt("canvas_height", requestedHeight > 0 ? requestedHeight : 1080)
                        : (requestedHeight > 0 ? requestedHeight : 1080));
            }
            duration = layoutDuration(layoutContext, shortestDuration(clips));
            project.setViewPortSize(canvasWidth, canvasHeight);
            Clip background = createSolidBackgroundClip(outputDir, canvasWidth, canvasHeight, duration);
            if (project.addClip(background) < 0) throw new IllegalStateException("background clip failed");
            JSONArray layoutItems = layoutContext == null ? null : layoutContext.optJSONArray("items");
            int count = layoutItems == null || layoutItems.length() == 0
                    ? Math.min(10, clips.size())
                    : Math.min(clips.size(), layoutItems.length());
            for (int index = 0; index < count; index++) {
                project.addOverlayItem(createOverlay(sdkInputs.get(index), canvasWidth, canvasHeight,
                        duration, index, count, horizontal,
                        layoutItems == null ? null : layoutItems.optJSONObject(index)));
            }
            width = canvasWidth;
            height = canvasHeight;
        } else {
            MediaClip clip = clips.get(0);
            int originalDuration = Math.max(1, clip.getDuration());
            int start = (int) Math.max(0L, Math.min(startMs, originalDuration - 1L));
            int end = endMs > 0 ? (int) Math.min(endMs, originalDuration) : originalDuration;
            if (end <= start) throw new IllegalArgumentException("invalid trim range");
            clip.setPlayTime(start, end);
            duration = end - start;
            project.setViewPortSize(width, height);
            if ("video_transition".equals(operation)) {
                if (clips.size() < 2) throw new IllegalArgumentException("video_transition requires two videos");
                if (project.addClip(clip) < 0 || project.addClip(clips.get(1)) < 0) {
                    throw new IllegalStateException("transition clips failed");
                }
                registerFadeTransition(clip, 500);
            } else {
                if ("video_mirror".equals(operation)) clip.setVideoFlip(true, false);
                if ("video_blur".equals(operation)) {
                    clip.setBackgroundMode(Clip.BackgroundModeBlur, 100, Color.BLACK);
                }
                if ("video_timeline_effect".equals(operation)) {
                    project.getTimelineEffectManager().addTimelineEffect(
                            0, duration, new ScaleTimelineEffect());
                }
                if ("video_motion_effect".equals(operation)) {
                    project.getTimelineEffectManager().addTimelineEffect(
                            0, duration, new MotionTimelineEffect("motion_light", width, height, 0));
                }
                if ("video_filter".equals(operation)) {
                    File lutFile = applyColorFilterLut(context, clip, layoutContext, outputDir);
                    if (lutFile == null) {
                        throw new IllegalStateException("video_filter requires LUT filter parameters");
                    }
                }
                if (project.addClip(clip) < 0) throw new IllegalStateException("input clip failed");
            }
        }

        project.updateProject();
        File output = new File(outputDir, safeName(operation) + ".mp4");
        if (output.exists() && !output.delete()) throw new IllegalStateException("cannot replace output");
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);
        final AtomicInteger lastProgress = new AtomicInteger(0);
        final VideoFactory factory = new VideoFactory();
        factory.setProject(project);
        factory.setVideoFrameRate(30);
        factory.setEventHandler(new VideoFactoryListener() {
            @Override public void onEncodingDone(boolean value, int code) {
                success.set(value || code == 0);
                done.countDown();
            }
            @Override public void onEncodingProgress(int current, int total) {
                int percent = total > 0 ? current * 100 / total : current;
                percent = Math.max(lastProgress.get(), Math.min(99, Math.max(0, percent)));
                lastProgress.set(percent);
                if (progress != null) progress.onProgress(percent, "vivo VideoEditorSDK 导出中");
            }
            @Override public void onStateChange(int a, int b) {}
            @Override public void onTimeChange(int a) {}
            @Override public void onSetTimeDone(int a) {}
            @Override public void onSetTimeFail(int a) {}
            @Override public void onSetTimeIgnored() {}
            @Override public void onPlayEnd() {}
            @Override public void onPlayFail(int a, int b) {}
            @Override public void onPlayStart() {}
            @Override public void onFirstFrameShown() {}
            @Override public void onClipInfoDone() {}
            @Override public void onSeekStateChanged(boolean a) {}
            @Override public void onCheckDirectExport(int a) {}
            @Override public void onProgressThumbnailCaching(int a, int b) {}
            @Override public void onFastPreviewStartDone(int a, int b, int c) {}
            @Override public void onFastPreviewStopDone(int a) {}
            @Override public void onFastPreviewTimeDone(int a) {}
            @Override public void onPreviewPeakMeter(int a, int b) {}
            @Override public void onContentChanged() {}
        });
        ACTIVE.put(jobId, factory);
        try {
            factory.updateProject();
            factory.export(new ExportParam(output.getAbsolutePath(), width, height,
                    Math.max(2_000_000, Math.min(12_000_000, width * height * 6)),
                    32L * 1024L * 1024L, 0, 44_100));
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                factory.stopSync();
                throw new IllegalStateException("vivo VideoEditorSDK export timeout");
            }
            if (!success.get() || !output.isFile() || output.length() == 0) {
                throw new IllegalStateException("vivo VideoEditorSDK export failed");
            }
            if (progress != null) progress.onProgress(100, "原生 SDK 导出完成");
            return new Result(output, operation, duration);
        } finally {
            ACTIVE.remove(jobId);
            factory.release();
        }
    }

    public static void cancel(String jobId) {
        VideoFactory factory = ACTIVE.get(jobId);
        if (factory != null) factory.stopSync();
        VivoImageSdkExecutor.cancel(jobId);
    }

    static void applyClipParameters(Clip clip, JSONObject params) {
        if (params == null) return;
        boolean hasAdjust = hasAny(params, "brightness", "contrast", "saturation",
                "temperature", "tint", "highlight", "shadow", "exposure", "sharpen");
        boolean hasBeauty = hasAny(params, "smooth_skin", "whiten", "thin_face",
                "cut_face", "big_eye", "thin_nose");
        if (!hasAdjust && !hasBeauty) return;

        clip.setEnableBeauty(hasAdjust || hasBeauty);
        clip.setEdited(true);
        BeautyParameters beauty = clip.getBeautyParams();
        beauty.setAdjustParam(hasAdjust);
        beauty.setAdjustParamChange(hasAdjust);
        beauty.setInsBright((float) params.optDouble("brightness", 0.0));
        beauty.setInsContrast((float) params.optDouble("contrast", 0.0));
        beauty.setInsSaturation((float) params.optDouble("saturation", 0.0));
        beauty.setInsTemperature((float) params.optDouble("temperature", 0.0));
        beauty.setInsTint((float) params.optDouble("tint", 0.0));
        beauty.setInsHighlight((float) params.optDouble("highlight", 0.0));
        beauty.setInsShadow((float) params.optDouble("shadow", 0.0));
        beauty.setInsExposure((float) params.optDouble("exposure", 0.0));
        beauty.setInsSharpen((float) params.optDouble("sharpen", 0.0));
        beauty.setBeautyParam(hasBeauty);
        beauty.setBeautyParamChange(hasBeauty);
        beauty.setSmoothSkin(params.optInt("smooth_skin", 0));
        beauty.setWhiten(params.optInt("whiten", 0));
        beauty.setThinFace(params.optInt("thin_face", 0));
        beauty.setCutFace(params.optInt("cut_face", 0));
        beauty.setBigEye(params.optInt("big_eye", 0));
        beauty.setThinNose(params.optInt("thin_nose", 0));
    }

    private static final int LUT_GRID = 8;
    private static final int LUT_CELL = 64;
    private static final int LUT_SIZE = LUT_GRID * LUT_CELL;

    static File applyColorFilterLut(Context context, Clip clip, JSONObject params, File outputDir) throws Exception {
        float contrast = (float) params.optDouble("contrast", 0.0);
        float saturation = (float) params.optDouble("saturation", 0.0);
        float temperature = (float) params.optDouble("temperature", 0.0);
        float brightness = (float) params.optDouble("brightness", 0.0);
        float exposure = (float) params.optDouble("exposure", 0.0);
        if (Math.abs(contrast) + Math.abs(saturation) + Math.abs(temperature)
                + Math.abs(brightness) + Math.abs(exposure) < 1e-4f) {
            return null;
        }

        Bitmap lut = Bitmap.createBitmap(LUT_SIZE, LUT_SIZE, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[LUT_SIZE * LUT_SIZE];
        int[] rgb = new int[3];
        for (int row = 0; row < LUT_SIZE; row++) {
            for (int col = 0; col < LUT_SIZE; col++) {
                rgb[0] = (col % LUT_CELL) * 255 / (LUT_CELL - 1);
                rgb[1] = (row % LUT_CELL) * 255 / (LUT_CELL - 1);
                rgb[2] = ((col / LUT_CELL) + (row / LUT_CELL) * LUT_GRID) * 255 / (LUT_GRID * LUT_GRID - 1);
                applyLutTransform(rgb, contrast, saturation, temperature, brightness, exposure);
                pixels[row * LUT_SIZE + col] = 0xFF000000
                        | (clampByte(rgb[0]) << 16)
                        | (clampByte(rgb[1]) << 8)
                        | clampByte(rgb[2]);
            }
        }
        lut.setPixels(pixels, 0, LUT_SIZE, 0, 0, LUT_SIZE, LUT_SIZE);
        File lutFile = new File(outputDir, "video-filter-lut.png");
        FileOutputStream out = new FileOutputStream(lutFile);
        try {
            lut.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
        }
        lut.recycle();
        clip.setColorFilterLUTPath(lutFile.getAbsolutePath());
        clip.setColorFilterLevel(params.optDouble("filter_level", 1.0) > 0
                ? (float) params.optDouble("filter_level", 1.0) : 1.0f);
        return lutFile;
    }

    private static void applyLutTransform(int[] rgb, float contrast, float saturation,
                                          float temperature, float brightness, float exposure) {
        float r = rgb[0] / 255f;
        float g = rgb[1] / 255f;
        float b = rgb[2] / 255f;

        r = (r - 0.5f) * (1f + contrast) + 0.5f;
        g = (g - 0.5f) * (1f + contrast) + 0.5f;
        b = (b - 0.5f) * (1f + contrast) + 0.5f;

        float luminance = 0.299f * r + 0.587f * g + 0.114f * b;
        r = luminance + (r - luminance) * (1f + saturation);
        g = luminance + (g - luminance) * (1f + saturation);
        b = luminance + (b - luminance) * (1f + saturation);

        r += temperature * 0.3f;
        b -= temperature * 0.3f;

        r += brightness + exposure;
        g += brightness + exposure;
        b += brightness + exposure;

        rgb[0] = Math.round(r * 255f);
        rgb[1] = Math.round(g * 255f);
        rgb[2] = Math.round(b * 255f);
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static boolean hasAny(JSONObject params, String... keys) {
        for (String key : keys) if (params.has(key)) return true;
        return false;
    }

    private static MediaClip requireMediaClip(String path) {
        Clip clip = Clip.getSupportedClip(path);
        if (!(clip instanceof MediaClip)) {
            throw new IllegalArgumentException("SDK does not support video: " + path);
        }
        return (MediaClip) clip;
    }

    private static String stageInput(Context context, String jobId, String path, int index) throws Exception {
        File source = new File(path);
        if (!source.isFile()) throw new IllegalArgumentException("media input is missing: " + path);
        String publicRoot = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        if (source.getAbsolutePath().startsWith(publicRoot)) return source.getAbsolutePath();
        File root = context.getExternalFilesDir("sdk-inputs");
        if (root == null) throw new IllegalStateException("external SDK input directory unavailable");
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("cannot create SDK input directory: " + root);
        }
        String name = safeName(jobId) + "-" + index + extension(source.getName());
        File staged = new File(root, name);
        FileInputStream input = new FileInputStream(source);
        try {
            FileOutputStream output = new FileOutputStream(staged);
            try {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) output.write(buffer, 0, read);
                }
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
        return staged.getAbsolutePath();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : ".mp4";
    }

    private static int shortestDuration(List<MediaClip> clips) {
        int duration = Integer.MAX_VALUE;
        for (MediaClip clip : clips) duration = Math.min(duration, Math.max(1, clip.getDuration()));
        return duration == Integer.MAX_VALUE ? 1 : duration;
    }

    private static VideoOverlay createOverlay(String path, int width, int height,
                                              int duration, int index, int count,
                                              boolean horizontal, JSONObject slot) {
        VideoOverlay overlay = VideoOverlay.createOverlay(path);
        if (overlay == null) throw new IllegalStateException("cannot create SDK video overlay");
        overlay.setVideoLoop(slot != null && "loop".equalsIgnoreCase(slot.optString("fill_mode", "freeze")));
        long startMs = slot == null ? 0L : slot.optLong("start_ms", 0L);
        long endMs = slot == null ? duration : slot.optLong("end_ms", duration);
        int start = (int) Math.max(0L, Math.min(startMs, duration - 1L));
        int end = (int) Math.max(start + 1L, Math.min(endMs, duration));
        overlay.setPlayTime(start, end);
        overlay.getVideoClip().setCropMode(CropMode.Fill);
        overlay.getVideoClip().setVolume(0f);
        OverlayParameters params = new OverlayParameters();
        params.setViewSize(width, height);
        if (slot != null && slot.has("x") && slot.has("y") && slot.has("width") && slot.has("height")) {
            double x = slot.optDouble("x");
            double y = slot.optDouble("y");
            double overlayWidth = Math.max(1d, slot.optDouble("width"));
            double overlayHeight = Math.max(1d, slot.optDouble("height"));
            params.setOverlaySize((float) (overlayWidth * 2d / width),
                    (float) (overlayHeight * 2d / height));
            params.setOverlayPosition((float) ((x + overlayWidth / 2d) * 2d / width - 1d),
                    (float) (1d - (y + overlayHeight / 2d) * 2d / height));
            params.setOverlayRotation((float) slot.optDouble("rotation", 0d), 0f, 0f);
        } else if (horizontal) {
            params.setOverlaySize(2f / count, 2f);
            params.setOverlayPosition(-1f + (index + 0.5f) * 2f / count, 0f);
        } else {
            params.setOverlaySize(2f, 2f / count);
            params.setOverlayPosition(0f, 1f - (index + 0.5f) * 2f / count);
        }
        overlay.setParam(params);
        return overlay;
    }

    private static int layoutDuration(JSONObject layoutContext, int fallback) {
        if (layoutContext == null) return fallback;
        long duration = layoutContext.optLong("duration_ms", 0L);
        JSONArray items = layoutContext.optJSONArray("items");
        if (items != null) {
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item != null) duration = Math.max(duration, item.optLong("end_ms", 0L));
            }
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, duration > 0L ? duration : fallback));
    }

    private static boolean supportsReferenceVideoTool(String operation) {
        return "video_fit_band".equals(operation)
                || "video_crop_band".equals(operation)
                || "video_vertical_stack".equals(operation)
                || "video_transition".equals(operation)
                || "video_timeline_effect".equals(operation)
                || "video_motion_effect".equals(operation)
                || "video_dynamic_sticker".equals(operation)
                || "video_border_watermark".equals(operation)
                || "video_speed".equals(operation)
                || "video_mirror".equals(operation)
                || "video_blur".equals(operation);
    }

    private static File reverseVideo(String input, File outputDir) throws Exception {
        File output = new File(outputDir, "reversed-output.mp4");
        File temp = new File(outputDir, "reverse-temp");
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger error = new AtomicInteger(0);
        VideoReverser reverser = new VideoReverser();
        reverser.setDataSource(input);
        reverser.setTargetFile(output.getAbsolutePath());
        reverser.setTempPath(temp.getAbsolutePath());
        reverser.setReverseListener(new VideoReverser.ReverseListener() {
            @Override public void onStart() { }
            @Override public void onError(int code) { error.set(code == 0 ? -1 : code); done.countDown(); }
            @Override public void onProgressChange(int percent) { }
            @Override public void onComplete() { done.countDown(); }
        });
        reverser.start();
        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("VideoReverser timeout");
        reverser.release();
        if (error.get() != 0 || !output.isFile() || output.length() == 0) {
            throw new IllegalStateException("VideoReverser failed code=" + error.get());
        }
        return output;
    }

    private static long mediaDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return Long.parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerFadeTransition(MediaClip first, int duration) throws Exception {
        final String id = "live_agent_fade";
        Field mapField = ThemeLibrary.class.getDeclaredField("mTransitionMap");
        mapField.setAccessible(true);
        Map<String, com.vivo.videoeditorsdk.layer.Transition> transitions =
                (Map<String, com.vivo.videoeditorsdk.layer.Transition>) mapField.get(null);
        if (transitions == null) {
            transitions = new HashMap<String, com.vivo.videoeditorsdk.layer.Transition>();
            mapField.set(null, transitions);
        }
        transitions.put(id, new FadeTransition());
        first.setTransition(id, duration);
    }

    private static Clip createSolidBackgroundClip(File outputDir, int width,
                                                   int height, int duration) throws Exception {
        File file = new File(outputDir, "background.png");
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        canvas.drawRect(0, 0, width, height, paint);
        FileOutputStream stream = new FileOutputStream(file);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IllegalStateException("background encoding failed");
            }
        } finally {
            stream.close();
            bitmap.recycle();
        }
        Clip clip = Clip.getSupportedClip(file.getAbsolutePath());
        if (clip == null) throw new IllegalStateException("background clip failed");
        clip.setDuration(duration);
        clip.setCropMode(CropMode.Fill);
        return clip;
    }

    private static int[] mediaSize(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            int width = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            return new int[]{even(width), even(height)};
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static int parse(String value) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return 2; }
    }

    private static int even(int value) { return Math.max(2, value & ~1); }

    private static String safeName(String operation) {
        return operation.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + System.currentTimeMillis();
    }
}
