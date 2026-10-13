package com.vivo.lpa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;

import com.vivo.videoeditorsdk.effect.ScaleTimelineEffect;
import com.vivo.videoeditorsdk.layer.Clip;
import com.vivo.videoeditorsdk.layer.CropMode;
import com.vivo.videoeditorsdk.layer.ImageOverlay;
import com.vivo.videoeditorsdk.layer.MediaClip;
import com.vivo.videoeditorsdk.layer.OverlayItem;
import com.vivo.videoeditorsdk.layer.OverlayParameters;
import com.vivo.videoeditorsdk.layer.Transition;
import com.vivo.videoeditorsdk.layer.VideoOverlay;
import com.vivo.videoeditorsdk.media.ExportParam;
import com.vivo.videoeditorsdk.stream.movie.FocusPoint;
import com.vivo.videoeditorsdk.theme.FadeTransition;
import com.vivo.videoeditorsdk.videoeditor.VideoEditorConfig;
import com.vivo.videoeditorsdk.videoeditor.VideoFactory;
import com.vivo.videoeditorsdk.videoeditor.VideoFactoryListener;
import com.vivo.videoeditorsdk.videoeditor.VideoProject;
import com.vivo.videoeditorsdk.videoeditor.ThemeLibrary;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class EditorVideoProcessor {
    private static final ConcurrentHashMap<String, VideoFactory> ACTIVE =
            new ConcurrentHashMap<String, VideoFactory>();

    private EditorVideoProcessor() {}

    static void cancel(String jobId) {
        VideoFactory factory = ACTIVE.get(jobId);
        if (factory != null) factory.stopSync();
    }

    static File process(Context context, String jobId, String operation,
                        String inputPath, String secondPath, String thirdPath, String effect,
                        int requestedWidth, int requestedHeight,
                        File outputDir) throws Exception {
        VideoEditorConfig.init(context.getApplicationContext(), null);
        MediaClip first = requireMediaClip(inputPath);
        int width = even(first.getWidth());
        int height = even(first.getHeight());
        if (width <= 0 || height <= 0) {
            int[] size = mediaSize(inputPath);
            width = size[0];
            height = size[1];
        }
        int rotation = ((first.getRotate() % 360) + 360) % 360;
        if (rotation == 90 || rotation == 270) {
            int temporary = width;
            width = height;
            height = temporary;
        }
        if ("video_fit_band".equals(operation) || "video_crop_band".equals(operation)) {
            width = even(requestedWidth > 0 ? requestedWidth : width);
            height = even(requestedHeight > 0 ? requestedHeight : height);
            if ("video_fit_band".equals(operation)) {
                first.setCropMode(CropMode.Fit);
                first.setBackgroundMode(Clip.BackgroundModeBlur, 100, Color.BLACK);
            } else {
                first.setCropMode(CropMode.Stretch);
            }
        } else if ("video_vertical_stack".equals(operation)) {
            width = even(requestedWidth > 0 ? requestedWidth : width);
            height = even(requestedHeight > 0 ? requestedHeight : height);
        }

        int verticalDuration = 0;
        if ("video_vertical_stack".equals(operation)) {
            MediaClip second = requireMediaClip(secondPath);
            MediaClip third = requireMediaClip(thirdPath);
            verticalDuration = Math.max(1, Math.min(first.getDuration(),
                    Math.min(second.getDuration(), third.getDuration())));
            first.setPlayTime(0, verticalDuration);
        }

        Clip baseClip = first;
        if ("video_vertical_stack".equals(operation)) {
            baseClip = createSolidBackgroundClip(
                    outputDir, width, height, verticalDuration);
        }

        VideoProject project = new VideoProject();
        project.setViewPortSize(width, height);
        if (project.addClip(baseClip) < 0) throw new IllegalStateException("add base clip failed");

        if ("video_fit_band".equals(operation)) {
            first.setCropMode(CropMode.Fit);
            first.setBackgroundMode(Clip.BackgroundModeBlur, 100, Color.BLACK);
        } else if ("video_crop_band".equals(operation)) {
            first.setCropMode(CropMode.Stretch);
        } else if ("video_transition".equals(operation)) {
            MediaClip second = requireMediaClip(secondPath);
            if (project.addClip(second) < 0) throw new IllegalStateException("add second clip failed");
            registerFadeTransition(first, 500);
        } else if ("video_timeline_effect".equals(operation)) {
            int duration = Math.max(1, first.getDuration());
            project.getTimelineEffectManager().addTimelineEffect(
                    0, duration, new ScaleTimelineEffect());
        } else if ("video_motion_effect".equals(operation)) {
            int duration = Math.max(1, first.getDuration());
            project.getTimelineEffectManager().addTimelineEffect(
                    0, duration, new MotionTimelineEffect(effect, width, height, rotation));
        } else if ("video_portrait_effect".equals(operation)) {
            throw new IllegalStateException("video_portrait_effect requires EdgeTAM/MediaPipe runtime");
        } else if ("video_dynamic_sticker".equals(operation)) {
            OverlayItem overlay = createOverlay(secondPath);
            configureOverlay(overlay, width, height, Math.max(1, first.getDuration()), false);
            project.addOverlayItem(overlay);
        } else if ("video_border_watermark".equals(operation)) {
            File borderFile = new File(outputDir, "reference-border.png");
            Bitmap border = createBorder(width, height);
            java.io.FileOutputStream borderStream = new java.io.FileOutputStream(borderFile);
            try {
                if (!border.compress(Bitmap.CompressFormat.PNG, 100, borderStream)) {
                    throw new IllegalStateException("border encoding failed");
                }
            } finally {
                borderStream.close();
                border.recycle();
            }
            ImageOverlay overlay = ImageOverlay.createOverlay(borderFile.getAbsolutePath());
            configureOverlay(overlay, width, height, Math.max(1, first.getDuration()), true);
            project.addOverlayItem(overlay);
        } else if ("video_vertical_stack".equals(operation)) {
            project.addOverlayItem(createVerticalOverlay(
                    inputPath, width, height, verticalDuration, 0));
            project.addOverlayItem(createVerticalOverlay(
                    secondPath, width, height, verticalDuration, 1));
            project.addOverlayItem(createVerticalOverlay(
                    thirdPath, width, height, verticalDuration, 2));
        } else if ("video_mirror".equals(operation)) {
            first.setVideoFlip(true, false);
        } else if ("video_blur".equals(operation)) {
            first.setBackgroundMode(Clip.BackgroundModeBlur, 100, Color.BLACK);
        } else if ("video_speed".equals(operation)) {
            first.setSpeed(Float.parseFloat(effect));
        } else {
            throw new IllegalArgumentException("unsupported editor operation: " + operation);
        }

        project.updateProject();
        File output = new File(outputDir, "effect-output.mp4");
        if (output.exists()) output.delete();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);
        final VideoFactory factory = new VideoFactory();
        factory.setEventHandler(new Listener(context, jobId, operation, factory, done, success));
        factory.setProject(project);
        factory.setVideoFrameRate(30);
        factory.updateProject();
        ACTIVE.put(jobId, factory);
        ExportParam export = new ExportParam(output.getAbsolutePath(), width, height,
                Math.max(2_000_000, Math.min(12_000_000, width * height * 6)),
                32L * 1024L * 1024L, 0, 44_100);
        factory.export(export);
        boolean callback = done.await(240, TimeUnit.SECONDS);
        ACTIVE.remove(jobId);
        if (!callback) {
            factory.stopSync();
            factory.release();
            throw new IllegalStateException("VideoEditor export timeout");
        }
        factory.release();
        if (!success.get() || !output.isFile() || output.length() == 0) {
            throw new IllegalStateException("VideoEditor export failed");
        }
        return output;
    }

    private static MediaClip requireMediaClip(String path) {
        Clip clip = Clip.getSupportedClip(path);
        if (!(clip instanceof MediaClip)) {
            throw new IllegalArgumentException("SDK does not support video: " + path);
        }
        return (MediaClip) clip;
    }

    private static OverlayItem createOverlay(String path) {
        if (path == null || !new File(path).isFile()) {
            throw new IllegalArgumentException("dynamic sticker requires a real image or video");
        }
        String lower = path.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".3gp")) {
            VideoOverlay overlay = VideoOverlay.createOverlay(path);
            if (overlay == null) throw new IllegalStateException("VideoOverlay creation failed");
            overlay.setVideoLoop(true);
            return overlay;
        }
        ImageOverlay overlay = ImageOverlay.createOverlay(path);
        if (overlay == null) throw new IllegalStateException("ImageOverlay creation failed");
        return overlay;
    }

    private static void configureOverlay(OverlayItem overlay, int width, int height,
                                         int duration, boolean fullFrame) {
        overlay.setPlayTime(0, duration);
        OverlayParameters parameters = new OverlayParameters();
        parameters.setViewSize(width, height);
        if (fullFrame) {
            parameters.setOverlaySize(2f, 2f);
            parameters.setOverlayPosition(0f, 0f);
        } else {
            parameters.setOverlaySize(0.68f, 0.68f);
            parameters.setOverlayPosition(0.55f, -0.55f);
        }
        overlay.setParam(parameters);
    }

    private static VideoOverlay createVerticalOverlay(String path, int width, int height,
                                                       int duration, int row) {
        VideoOverlay overlay = VideoOverlay.createOverlay(path);
        if (overlay == null) throw new IllegalStateException("vertical VideoOverlay creation failed");
        overlay.setVideoLoop(false);
        overlay.setPlayTime(0, duration);
        overlay.getVideoClip().setCropMode(CropMode.Fill);
        overlay.getVideoClip().setVolume(0f);
        OverlayParameters parameters = new OverlayParameters();
        parameters.setViewSize(width, height);
        parameters.setOverlaySize(2f, 2f / 3f);
        parameters.setOverlayPosition(0f, 2f / 3f - row * 2f / 3f);
        overlay.setParam(parameters);
        return overlay;
    }

    private static Clip createSolidBackgroundClip(File outputDir, int width, int height,
                                                   int duration) throws Exception {
        File file = new File(outputDir, "vertical-stack-background.png");
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        java.io.FileOutputStream stream = new java.io.FileOutputStream(file);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IllegalStateException("vertical background encoding failed");
            }
        } finally {
            stream.close();
            bitmap.recycle();
        }
        Clip clip = Clip.getSupportedClip(file.getAbsolutePath());
        if (clip == null) throw new IllegalStateException("vertical background clip failed");
        clip.setDuration(duration);
        clip.setCropMode(CropMode.Fill);
        return clip;
    }

    private static Bitmap createBorder(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Math.max(10f, Math.min(width, height) * 0.025f));
        frame.setColor(Color.rgb(255, 196, 48));
        canvas.drawRect(frame.getStrokeWidth() / 2f, frame.getStrokeWidth() / 2f,
                width - frame.getStrokeWidth() / 2f, height - frame.getStrokeWidth() / 2f, frame);
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.WHITE);
        label.setTextSize(Math.max(18f, Math.min(width, height) * 0.045f));
        label.setShadowLayer(4f, 1f, 1f, Color.BLACK);
        canvas.drawText("LIVE", frame.getStrokeWidth() * 1.2f,
                height - frame.getStrokeWidth() * 1.2f, label);
        return bitmap;
    }

    @SuppressWarnings("unchecked")
    private static void registerFadeTransition(Clip first, int duration) throws Exception {
        final String id = "live_agent_fade";
        Field mapField = ThemeLibrary.class.getDeclaredField("mTransitionMap");
        mapField.setAccessible(true);
        Map<String, Transition> transitions = (Map<String, Transition>) mapField.get(null);
        if (transitions == null) {
            transitions = new HashMap<String, Transition>();
            mapField.set(null, transitions);
        }
        transitions.put(id, new FadeTransition());
        first.setTransition(id, duration);
    }

    private static int[] mediaSize(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(path);
        int width = Integer.parseInt(retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int height = Integer.parseInt(retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        try {
            retriever.release();
        } catch (Exception ignored) {}
        return new int[] {even(width), even(height)};
    }

    private static int even(int value) {
        return Math.max(2, value & ~1);
    }

    private static final class Listener implements VideoFactoryListener {
        private final Context context;
        private final String jobId;
        private final String operation;
        private final VideoFactory factory;
        private final CountDownLatch done;
        private final AtomicBoolean success;
        private final AtomicInteger lastProgress = new AtomicInteger(0);

        Listener(Context context, String jobId, String operation, VideoFactory factory,
                 CountDownLatch done, AtomicBoolean success) {
            this.context = context;
            this.jobId = jobId;
            this.operation = operation;
            this.factory = factory;
            this.done = done;
            this.success = success;
        }

        public void onEncodingDone(boolean value, int code) {
            success.set(value || code == 0);
            done.countDown();
        }
        public void onEncodingProgress(int current, int total) {
            int reported = total > 0 ? current * 100 / total : current;
            int percent = Math.max(lastProgress.get(), Math.max(0, Math.min(99, reported)));
            lastProgress.set(percent);
        }
        public void onStateChange(int a, int b) {}
        public void onTimeChange(int a) {}
        public void onSetTimeDone(int a) {}
        public void onSetTimeFail(int a) {}
        public void onSetTimeIgnored() {}
        public void onPlayEnd() {}
        public void onPlayFail(int a, int b) {}
        public void onPlayStart() {}
        public void onFirstFrameShown() {}
        public void onClipInfoDone() {}
        public void onSeekStateChanged(boolean a) {}
        public void onCheckDirectExport(int a) {}
        public void onProgressThumbnailCaching(int a, int b) {}
        public void onFastPreviewStartDone(int a, int b, int c) {}
        public void onFastPreviewStopDone(int a) {}
        public void onFastPreviewTimeDone(int a) {}
        public void onPreviewPeakMeter(int a, int b) {}
        public void onContentChanged() {}
        public void onMovieFocusPointChanged(int a, LinkedList<FocusPoint> points) {}
    }
}
