package com.vivo.lpa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;

import com.vivo.imageprocess.FilterType;
import com.vivo.imageprocess.ImageProcessOffscreenRender;
import com.vivo.imageprocess.ImageProcessRenderEngine;
import com.vivo.videoeditorsdk.layer.Clip;
import com.vivo.videoeditorsdk.layer.ImageClip;
import com.vivo.videoeditorsdk.media.ExportParam;
import com.vivo.videoeditorsdk.stream.movie.FocusPoint;
import com.vivo.videoeditorsdk.videoeditor.VideoEditorConfig;
import com.vivo.videoeditorsdk.videoeditor.VideoFactory;
import com.vivo.videoeditorsdk.videoeditor.VideoFactoryListener;
import com.vivo.videoeditorsdk.videoeditor.VideoProject;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Image operations backed by the supplied vivo image/video SDKs. */
final class VivoImageSdkExecutor {
    private static final long TIMEOUT_SECONDS = 240L;
    private static final ConcurrentHashMap<String, VideoFactory> ACTIVE_EXPORTS =
            new ConcurrentHashMap<String, VideoFactory>();
    private static final ConcurrentHashMap<String, ImageProcessOffscreenRender> ACTIVE_IMAGES =
            new ConcurrentHashMap<String, ImageProcessOffscreenRender>();
    private static final ConcurrentHashMap<String, AtomicBoolean> CANCELLED =
            new ConcurrentHashMap<String, AtomicBoolean>();

    private VivoImageSdkExecutor() {
    }

    static VivoSdkExecutor.Result execute(Context context, String jobId, String operation,
                                          String inputPath, JSONObject params, File outputDir,
                                          VivoSdkExecutor.Progress progress) throws Exception {
        if (inputPath == null || !new File(inputPath).isFile()) {
            throw new IllegalArgumentException("image input is missing: " + inputPath);
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalStateException("cannot create output directory: " + outputDir);
        }
        CANCELLED.put(jobId, new AtomicBoolean(false));
        try {
            if ("denoise".equals(operation) || "brighten".equals(operation)
                    || "sharpen".equals(operation) || "color_tone".equals(operation)
                    || "portrait_beauty".equals(operation)) {
                return renderWithImageProcess(jobId, operation, inputPath, params, outputDir, progress);
            }
            return renderWithVideoEditor(context, jobId, operation, inputPath,
                    params, outputDir, progress);
        } finally {
            CANCELLED.remove(jobId);
        }
    }

    static void cancel(String jobId) {
        AtomicBoolean cancelled = CANCELLED.get(jobId);
        if (cancelled != null) cancelled.set(true);
        VideoFactory factory = ACTIVE_EXPORTS.get(jobId);
        if (factory != null) factory.stopSync();
        ImageProcessOffscreenRender renderer = ACTIVE_IMAGES.get(jobId);
        if (renderer != null) renderer.pause();
    }

    private static VivoSdkExecutor.Result renderWithVideoEditor(
            Context context, String jobId, String operation, String inputPath,
            JSONObject params, File outputDir, VivoSdkExecutor.Progress progress) throws Exception {
        VideoEditorConfig.init(context.getApplicationContext(), null);
        Bitmap source = BitmapFactory.decodeFile(inputPath);
        if (source == null) throw new IllegalArgumentException("cannot decode image: " + inputPath);

        File video = new File(outputDir, "image-render.mp4");
        File output = new File(outputDir, "vivo-" + safeName(operation) + ".jpg");
        ImageClip clip = null;
        VideoProject project = null;
        VideoFactory factory = null;
        MediaMetadataRetriever retriever = null;
        try {
            clip = new ImageClip(source);
            clip.setDuration(800);
            if ("video_filter".equals(operation)) {
                if (VivoSdkExecutor.applyColorFilterLut(context, clip, params, outputDir) == null) {
                    throw new IllegalArgumentException("video_filter requires LUT filter parameters");
                }
            } else {
                VivoSdkExecutor.applyClipParameters(clip, params);
            }

            int width = even(source.getWidth());
            int height = even(source.getHeight());
            project = new VideoProject();
            project.setViewPortSize(width, height);
            if (project.addClip(clip) < 0) throw new IllegalStateException("ImageClip add failed");
            project.updateProject();

            final CountDownLatch done = new CountDownLatch(1);
            final AtomicBoolean success = new AtomicBoolean(false);
            factory = new VideoFactory();
            factory.setEventHandler(new ImageExportListener(done, success));
            factory.setProject(project);
            factory.setVideoFrameRate(30);
            factory.updateProject();
            ACTIVE_EXPORTS.put(jobId, factory);
            factory.export(new ExportParam(video.getAbsolutePath(), width, height,
                    Math.max(2_000_000, Math.min(12_000_000, width * height * 6)),
                    32L * 1024L * 1024L, 0, 44_100));
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                factory.stopSync();
                throw new IllegalStateException("image VideoEditorSDK export timeout");
            }
            if (isCancelled(jobId)) throw new InterruptedException("cancelled");
            if (!success.get() || !video.isFile() || video.length() == 0) {
                throw new IllegalStateException("image VideoEditorSDK export failed");
            }
            if (progress != null) progress.onProgress(80, "vivo 图像 SDK 渲染完成");

            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(video.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(400_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = retriever.getFrameAtTime();
            if (frame == null) throw new IllegalStateException("exported image has no frame");
            try {
                savePng(frame, output);
            } finally {
                frame.recycle();
            }
            if (!output.isFile() || output.length() == 0) {
                throw new IllegalStateException("image SDK produced an empty result");
            }
            if (progress != null) progress.onProgress(100, "vivo 图像 SDK 导出完成");
            return new VivoSdkExecutor.Result(output, operation, 800L);
        } finally {
            ACTIVE_EXPORTS.remove(jobId);
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
            if (factory != null) factory.release();
            if (project != null) project.releaseGLObjects();
            if (clip != null) clip.releaseGLObjects();
            source.recycle();
        }
    }

    private static VivoSdkExecutor.Result renderWithImageProcess(String jobId, String operation,
                                                                  String inputPath, JSONObject params,
                                                                  File outputDir,
                                                                  VivoSdkExecutor.Progress progress) throws Exception {
        Bitmap source = BitmapFactory.decodeFile(inputPath);
        if (source == null) throw new IllegalArgumentException("cannot decode image input");
        Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                Bitmap.Config.ARGB_8888);
        ImageProcessOffscreenRender renderer = new ImageProcessOffscreenRender();
        final AtomicReference<String> failure = new AtomicReference<String>();
        try {
            renderer.setImageProcessListener(new ImageProcessOffscreenRender.IImageProcessOffscreenListener() {
                @Override public void notifySaveEffectFinished() {
                }

                @Override public void notifyFirstRenderFrameFinished() {
                }

                @Override public void notifyRenderThreadLaunchFinished() {
                }

                @Override public void notifyNativeDecodeImageFailed() {
                    failure.set("ImageProcess native decode failed");
                }
            });
            if (renderer.createEngine() != 0L) {
                throw new IllegalStateException("ImageProcess createEngine failed");
            }
            ACTIVE_IMAGES.put(jobId, renderer);
            if (progress != null) progress.onProgress(20, "vivo ImageProcess 初始化完成");
            renderer.setRenderSourceFromOutside(source, source.getWidth(), source.getHeight());
            renderer.setOrgDecodeImageSourceFromOutside(source, source.getWidth(), source.getHeight());

            renderer.notifySetEffects();
            if ("denoise".equals(operation)) {
                float strength = clamp(params == null ? 0.6f : (float) params.optDouble("denoise", 0.6), 0f, 1f);
                ImageProcessRenderEngine.DenoiseParam denoise =
                        new ImageProcessRenderEngine.DenoiseParam();
                denoise.luminanceDenoise = strength * 100f;
                denoise.colorDenoise = strength * 100f;
                renderer.setEffectProp(FilterType.FILTER_TYPE_DENOISE, denoise);
                if (progress != null) progress.onProgress(50, "vivo ImageProcess 降噪中");
            } else if ("brighten".equals(operation)) {
                float brightness = clamp(params == null ? 0.2f : (float) params.optDouble("brightness", 0.2), -1f, 1f);
                ImageProcessRenderEngine.AdjustParam adjust =
                        new ImageProcessRenderEngine.AdjustParam();
                adjust.fInsBright = brightness * 100f;
                adjust.fInsExposure = brightness * 0.25f;
                renderer.setEffectProp(FilterType.FILTER_TYPE_ADJUST, adjust);
                if (progress != null) progress.onProgress(50, "vivo ImageProcess 提亮中");
            } else if ("sharpen".equals(operation)) {
                float sharpen = clamp(params == null ? 0.25f : (float) params.optDouble("sharpen", 0.25), 0f, 1f);
                ImageProcessRenderEngine.DetailsParam details =
                        new ImageProcessRenderEngine.DetailsParam();
                details.nSharpenStrength = Math.round(sharpen * 100f);
                details.nStructureStrength = Math.round(sharpen * 40f);
                renderer.setEffectProp(FilterType.FILTER_TYPE_DETAILS, details);
                if (progress != null) progress.onProgress(50, "vivo ImageProcess 锐化中");
            } else if ("color_tone".equals(operation)) {
                float temperature = clamp(params == null ? 0f : (float) params.optDouble("temperature", 0), -1f, 1f);
                float saturation = clamp(params == null ? 0f : (float) params.optDouble("saturation", 0), -1f, 1f);
                float contrast = clamp(params == null ? 0f : (float) params.optDouble("contrast", 0), -1f, 1f);
                ImageProcessRenderEngine.AdjustParam adjust =
                        new ImageProcessRenderEngine.AdjustParam();
                adjust.fInsTemperature = temperature * 0.5f;
                adjust.fInsSaturation = saturation < 0 ? 1f + saturation : 1f + saturation * 0.5f;
                adjust.fInsContrast = contrast;
                renderer.setEffectProp(FilterType.FILTER_TYPE_ADJUST, adjust);
                if (progress != null) progress.onProgress(50, "vivo ImageProcess 调色中");
            } else if ("portrait_beauty".equals(operation)) {
                // FILTER_TYPE_BEAUTY 需要外部人脸蒙版 setGrayMask(ALPHA_8)，SDK 不提供
                // 人脸检测；无蒙版时输出全黑。暂用 ADJUST 全图提亮+轻微饱和降级，
                // 待接入人脸检测后升级为 BEAUTY 磨皮/美白。
                float smooth = clamp(params == null ? 30f : (float) params.optDouble("smooth_skin", 30), 0f, 100f);
                float whiten = clamp(params == null ? 20f : (float) params.optDouble("whiten", 20), 0f, 100f);
                ImageProcessRenderEngine.AdjustParam adjust =
                        new ImageProcessRenderEngine.AdjustParam();
                adjust.fInsBright = (smooth + whiten) * 0.15f;
                adjust.fInsSaturation = 1f + whiten * 0.002f;
                adjust.fInsContrast = 0.05f;
                renderer.setEffectProp(FilterType.FILTER_TYPE_ADJUST, adjust);
                if (progress != null) progress.onProgress(50, "vivo ImageProcess 人像提亮中");
            } else {
                throw new IllegalArgumentException("unsupported ImageProcess operation: " + operation);
            }
            if (isCancelled(jobId)) throw new InterruptedException("cancelled");
            renderer.notifySaveImageToBitmap(output, null, 0f, 0f,
                    source.getWidth(), source.getHeight());
            if (failure.get() != null) throw new IllegalStateException(failure.get());

            File result = new File(outputDir, "vivo-" + safeName(operation) + ".jpg");
            savePng(output, result);
            if (!result.isFile() || result.length() == 0) {
                throw new IllegalStateException("ImageProcess produced an empty result");
            }
            if (progress != null) progress.onProgress(100, "vivo ImageProcess " + operation + " 完成");
            return new VivoSdkExecutor.Result(result, operation, 0L);
        } finally {
            ACTIVE_IMAGES.remove(jobId);
            renderer.release();
            output.recycle();
            source.recycle();
        }
    }

    private static boolean isCancelled(String jobId) {
        AtomicBoolean value = CANCELLED.get(jobId);
        return value != null && value.get();
    }

    private static void savePng(Bitmap bitmap, File output) throws Exception {
        // LivePhotoSDK encode() rejects RGBA covers; flatten to RGB before saving.
        Bitmap flattened = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? toRgb(bitmap)
                : bitmap;
        FileOutputStream stream = new FileOutputStream(output);
        try {
            if (!flattened.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                throw new IllegalStateException("JPEG encoding failed");
            }
        } finally {
            stream.close();
            if (flattened != bitmap) flattened.recycle();
        }
    }

    private static Bitmap toRgb(Bitmap source) {
        Bitmap rgb = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rgb);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, 0f, 0f, null);
        return rgb;
    }

    private static String safeName(String value) {
        return value == null ? "image" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int even(int value) {
        return Math.max(2, value & ~1);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ImageExportListener implements VideoFactoryListener {
        private final CountDownLatch done;
        private final AtomicBoolean success;

        ImageExportListener(CountDownLatch done, AtomicBoolean success) {
            this.done = done;
            this.success = success;
        }

        @Override public void onEncodingDone(boolean isError, int code) {
            success.set(!isError && code == 0);
            done.countDown();
        }
        @Override public void onEncodingProgress(int current, int total) {
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
        @Override public void onMovieFocusPointChanged(int a, LinkedList<FocusPoint> points) {}
    }
}
