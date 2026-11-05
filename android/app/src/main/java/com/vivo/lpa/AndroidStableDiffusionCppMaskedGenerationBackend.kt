package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import org.json.JSONObject

internal data class AndroidStableDiffusionCppGenerationConfig(
    val modelFile: File,
    val modelSha256: String,
    val modelId: String,
    val runtimeCommit: String,
    val steps: Int,
    val guidanceScale: Double,
    val inferenceSize: Int,
    val roiContextPaddingPx: Int,
    val timeoutMs: Long
) {
    init {
        require(modelFile.isFile)
        require(modelFile.length() == PINNED_MODEL_BYTES)
        require(modelSha256 == PINNED_MODEL_SHA256)
        require(modelId == PINNED_MODEL_ID)
        require(runtimeCommit == PINNED_RUNTIME_COMMIT)
        require(steps in 1..30)
        require(guidanceScale in 1.0..20.0)
        require(inferenceSize in SUPPORTED_INFERENCE_SIZES)
        require(roiContextPaddingPx in 32..256)
        require(timeoutMs in 60_000L..900_000L)
    }

    companion object {
        const val PINNED_MODEL_ID =
            "sd2-community/stable-diffusion-2-inpainting/sdcpp-q4_0"
        const val PINNED_MODEL_SHA256 =
            "7d154a1f52167da45bb86f34ded41feb3b3f4f2f0f3e729a7997c8bb394bf44b"
        const val PINNED_MODEL_BYTES = 1_733_629_824L
        const val PINNED_RUNTIME_COMMIT = "58bb3c1b1cd4749e94e39f537e9adfce295dcaee"
        val SUPPORTED_INFERENCE_SIZES = setOf(256, 320, 512)

        fun fromFile(root: File, configFile: File): AndroidStableDiffusionCppGenerationConfig? =
            runCatching {
                if (!configFile.isFile) return null
                val value = JSONObject(configFile.readText())
                require(value.keys().asSequence().toSet() == setOf(
                    "schema_version",
                    "model_file",
                    "model_sha256",
                    "model_id",
                    "runtime_commit",
                    "steps",
                    "guidance_scale",
                    "inference_size",
                    "roi_context_padding_px",
                    "timeout_ms"
                ))
                require(value.getInt("schema_version") == 1)
                val canonicalRoot = root.canonicalFile
                val fileName = value.getString("model_file")
                require(fileName.isNotBlank() && fileName == File(fileName).name)
                val model = File(canonicalRoot, fileName).canonicalFile
                require(model.parentFile == canonicalRoot)
                AndroidStableDiffusionCppGenerationConfig(
                    modelFile = model,
                    modelSha256 = value.getString("model_sha256"),
                    modelId = value.getString("model_id"),
                    runtimeCommit = value.getString("runtime_commit"),
                    steps = value.getInt("steps"),
                    guidanceScale = value.getDouble("guidance_scale"),
                    inferenceSize = value.getInt("inference_size"),
                    roiContextPaddingPx = value.getInt("roi_context_padding_px"),
                    timeoutMs = value.getLong("timeout_ms")
                )
            }.getOrNull()
}
}

internal data class AndroidGenerationSquareCrop(
    val left: Int,
    val top: Int,
    val size: Int
) {
    init {
        require(left >= 0 && top >= 0 && size > 0)
    }
}

/** Pinned stable-diffusion.cpp Q4_0 inpaint running entirely on the phone's Vulkan GPU. */
class AndroidStableDiffusionCppMaskedGenerationBackend private constructor(
    private val config: AndroidStableDiffusionCppGenerationConfig,
    private val runtimeInfo: String
) : AndroidMaskedGenerationBackend {
    override val backendId =
        "$BACKEND_ID/${config.inferenceSize}x${config.inferenceSize}/${config.steps}step"
    override val available = true

    override fun generate(
        source: Bitmap,
        maskValues: ByteArray,
        change: LocalGenerativeChange,
        certificateFingerprint: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): AndroidMaskedGenerationResult {
        require(BuildConfig.DEBUG && BuildConfig.LOCAL_GENERATED_PREVIEW &&
            BuildConfig.ON_DEVICE_GENERATED_PREVIEW)
        require(!source.isRecycled)
        require(maskValues.size == source.width * source.height)
        require(Regex("[0-9a-f]{64}").matches(certificateFingerprint))
        check(!cancelled.get()) { "on-device generation cancelled before preprocessing" }

        val crop = computeSquareCrop(
            maskValues,
            source.width,
            source.height,
            config.roiContextPaddingPx
        )
        val sourcePixels = IntArray(maskValues.size)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        val requestSha = requestSha256(
            source.width,
            source.height,
            sourcePixels,
            maskValues,
            crop,
            change,
            certificateFingerprint,
            config
        )
        val seed = ByteBuffer.wrap(hexBytes(requestSha.take(16)))
            .order(ByteOrder.BIG_ENDIAN).long and Long.MAX_VALUE

        progress("正在准备端侧SD2局部生成区域…")
        val cropBitmap = Bitmap.createBitmap(source, crop.left, crop.top, crop.size, crop.size)
        val scaledSource = Bitmap.createScaledBitmap(
            cropBitmap,
            config.inferenceSize,
            config.inferenceSize,
            true
        )
        if (scaledSource !== cropBitmap && cropBitmap !== source) cropBitmap.recycle()
        val nativeSource = try {
            rgbBytes(scaledSource)
        } finally {
            if (scaledSource !== source) scaledSource.recycle()
        }
        val nativeMask = scaleMaskNearest(maskValues, source.width, crop, config.inferenceSize)
        val requestId = NEXT_REQUEST_ID.getAndIncrement()
        progress("正在端侧Vulkan加载固定SD2模型…")
        val generatedRgb = executeNative(
            requestId,
            nativeSource,
            nativeMask,
            change.generationPrompt,
            seed,
            cancelled,
            progress
        )
        validateGeneratedRgb(generatedRgb, config.inferenceSize)
        check(!cancelled.get()) { "on-device generation cancelled after inference" }

        val generatedPixels = rgbToArgb(generatedRgb, config.inferenceSize)
        val generated512 = Bitmap.createBitmap(
            generatedPixels,
            config.inferenceSize,
            config.inferenceSize,
            Bitmap.Config.ARGB_8888
        )
        val generatedCrop = Bitmap.createScaledBitmap(
            generated512,
            crop.size,
            crop.size,
            true
        )
        if (generatedCrop !== generated512) generated512.recycle()
        val cropPixels = IntArray(crop.size * crop.size)
        try {
            generatedCrop.getPixels(cropPixels, 0, crop.size, 0, 0, crop.size, crop.size)
        } finally {
            generatedCrop.recycle()
        }
        val generatedFull = sourcePixels.copyOf()
        for (y in 0 until crop.size) {
            val targetOffset = (crop.top + y) * source.width + crop.left
            cropPixels.copyInto(
                generatedFull,
                targetOffset,
                y * crop.size,
                (y + 1) * crop.size
            )
        }
        val featheredMask = AndroidGenerationRegionPolicy.featherInsideMask(
            maskValues,
            source.width,
            source.height,
            FEATHER_RADIUS_PX
        )
        val outputPixels = AndroidHttpMaskedGenerationBackend.compositeInsideMask(
            sourcePixels,
            generatedFull,
            featheredMask
        )
        val changedPixels = outputPixels.indices.count { outputPixels[it] != sourcePixels[it] }
        require(changedPixels >= MIN_CHANGED_PIXELS) {
            "on-device generation did not produce a reviewable masked change"
        }
        val output = Bitmap.createBitmap(
            outputPixels,
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888
        )
        return AndroidMaskedGenerationResult(
            output,
            backendId,
            evidenceFingerprint(
                requestSha,
                sha256(generatedRgb),
                outputPixels,
                certificateFingerprint,
                runtimeInfo
            )
        )
    }

    private fun executeNative(
        requestId: Long,
        sourceRgb: ByteArray,
        mask: ByteArray,
        prompt: String,
        seed: Long,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): ByteArray {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "masked-generation-sdcpp").apply { isDaemon = true }
        }
        val future = executor.submit<ByteArray> {
            AndroidStableDiffusionCppNativeBridge.generate(
                requestId,
                config.modelFile.absolutePath,
                sourceRgb,
                mask,
                config.inferenceSize,
                config.inferenceSize,
                prompt,
                NEGATIVE_PROMPT,
                config.steps,
                config.guidanceScale.toFloat(),
                seed
            )
        }
        val startedAt = SystemClock.elapsedRealtime()
        var cancellationIssued = false
        var timeoutIssued = false
        var lastProgress = -1L
        try {
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (cancelled.get() && !cancellationIssued) {
                    cancellationIssued = true
                    AndroidStableDiffusionCppNativeBridge.cancel(requestId)
                    progress("正在取消端侧生成…")
                }
                if (elapsed > config.timeoutMs && !timeoutIssued) {
                    timeoutIssued = true
                    AndroidStableDiffusionCppNativeBridge.cancel(requestId)
                    progress("端侧生成超时，正在安全释放模型…")
                }
                val packed = AndroidStableDiffusionCppNativeBridge.progress(requestId)
                if (packed >= 0 && packed != lastProgress) {
                    lastProgress = packed
                    val step = (packed and 0xffffffffL).toInt()
                    val total = (packed ushr 32).toInt()
                    // sd.cpp reuses this callback for tensor loading and VAE tiles. Only expose
                    // the denoising pass as user-facing generation progress.
                    if (total == config.steps && step in 0..total) {
                        progress("端侧SD2生成 $step/$total…")
                    }
                }
                val output = try {
                    future.get(250, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Poll only app-owned cancellation, timeout and native progress state.
                    null
                } catch (failure: ExecutionException) {
                    if (timeoutIssued) throw IllegalStateException("on-device generation timed out")
                    if (cancellationIssued || cancelled.get() ||
                        failure.cause is InterruptedException) {
                        throw InterruptedException("on-device generation cancelled")
                    }
                    throw (failure.cause ?: failure)
                } catch (_: InterruptedException) {
                    cancellationIssued = true
                    cancelled.set(true)
                    AndroidStableDiffusionCppNativeBridge.cancel(requestId)
                    null
                }
                if (output != null) {
                    if (timeoutIssued) throw IllegalStateException("on-device generation timed out")
                    if (cancellationIssued || cancelled.get()) {
                        throw InterruptedException("on-device generation cancelled")
                    }
                    return output
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        const val BACKEND_ID = "sdcpp_vulkan_masked_generation_v1"
        private const val TAG = "LPA_GENERATION"
        private const val MIN_ROI_SIDE = 256
        private const val MIN_CHANGED_PIXELS = 16
        private const val FEATHER_RADIUS_PX = 4
        private const val NEGATIVE_PROMPT =
            "blurry, distorted, duplicate object, text, watermark, logo, low quality, malformed"
        private val NEXT_REQUEST_ID = AtomicLong(1L)
        private val verifiedModels = mutableMapOf<String, Pair<Long, Long>>()

        fun fromPrivateConfig(context: Context): AndroidStableDiffusionCppMaskedGenerationBackend? {
            if (!BuildConfig.DEBUG || !BuildConfig.LOCAL_GENERATED_PREVIEW ||
                !BuildConfig.ON_DEVICE_GENERATED_PREVIEW) return null
            val root = File(context.filesDir, "models/local-generation-sdcpp")
            val configFile = File(root, "config.json")
            val config = AndroidStableDiffusionCppGenerationConfig.fromFile(root, configFile)
            if (config == null) {
                Log.e(TAG, "stable-diffusion.cpp config rejected at ${configFile.absolutePath}")
                return null
            }
            val verified = synchronized(verifiedModels) {
                val key = config.modelFile.canonicalPath
                val signature = config.modelFile.length() to config.modelFile.lastModified()
                if (verifiedModels[key] == signature) {
                    true
                } else {
                    val accepted = fileSha256(config.modelFile) == config.modelSha256
                    if (accepted) verifiedModels[key] = signature
                    accepted
                }
            }
            if (!verified) {
                Log.e(TAG, "stable-diffusion.cpp model SHA-256 rejected")
                return null
            }
            val info = runCatching {
                AndroidStableDiffusionCppNativeBridge.runtimeInfo()
            }.getOrElse {
                Log.e(TAG, "stable-diffusion.cpp JNI unavailable", it)
                return null
            }
            if (!info.contains(config.runtimeCommit) || !info.contains("backend=Vulkan0")) {
                Log.e(TAG, "stable-diffusion.cpp runtime identity rejected: $info")
                return null
            }
            Log.i(
                TAG,
                "stable-diffusion.cpp backend accepted model=${config.modelId} " +
                    "steps=${config.steps} sha256=${config.modelSha256}"
            )
            return AndroidStableDiffusionCppMaskedGenerationBackend(config, info)
        }

        internal fun computeSquareCrop(
            mask: ByteArray,
            width: Int,
            height: Int,
            padding: Int
        ): AndroidGenerationSquareCrop {
            require(width > 0 && height > 0 && mask.size == width * height)
            require(padding in 0..256)
            val bounds = requireNotNull(AndroidGenerationRegionPolicy.bounds(mask, width, height)) {
                "generation mask is empty"
            }
            val maximumSide = minOf(width, height)
            require(bounds.width <= maximumSide && bounds.height <= maximumSide) {
                "generation target cannot fit a square inpaint ROI"
            }
            val side = max(
                MIN_ROI_SIDE.coerceAtMost(maximumSide),
                max(bounds.width, bounds.height) + padding * 2
            ).coerceAtMost(maximumSide)
            val centerX = (bounds.left + bounds.rightExclusive) / 2
            val centerY = (bounds.top + bounds.bottomExclusive) / 2
            val left = (centerX - side / 2).coerceIn(0, width - side)
            val top = (centerY - side / 2).coerceIn(0, height - side)
            require(bounds.left >= left && bounds.rightExclusive <= left + side &&
                bounds.top >= top && bounds.bottomExclusive <= top + side)
            return AndroidGenerationSquareCrop(left, top, side)
        }

        internal fun scaleMaskNearest(
            mask: ByteArray,
            sourceWidth: Int,
            crop: AndroidGenerationSquareCrop,
            outputSize: Int
        ): ByteArray {
            require(sourceWidth > 0 && outputSize > 0)
            return ByteArray(outputSize * outputSize) { index ->
                val x = index % outputSize
                val y = index / outputSize
                val sourceX = crop.left + (x * crop.size / outputSize).coerceAtMost(crop.size - 1)
                val sourceY = crop.top + (y * crop.size / outputSize).coerceAtMost(crop.size - 1)
                mask[sourceY * sourceWidth + sourceX]
            }
        }

        internal fun validateGeneratedRgb(rgb: ByteArray, size: Int) {
            require(rgb.size == size * size * 3)
            var minimumLuma = 255
            var maximumLuma = 0
            var clippedWhite = 0
            var clippedBlack = 0
            var index = 0
            while (index < rgb.size) {
                val red = rgb[index].toInt() and 0xff
                val green = rgb[index + 1].toInt() and 0xff
                val blue = rgb[index + 2].toInt() and 0xff
                val luma = (red * 54 + green * 183 + blue * 19) ushr 8
                minimumLuma = minOf(minimumLuma, luma)
                maximumLuma = maxOf(maximumLuma, luma)
                if (red >= 254 && green >= 254 && blue >= 254) clippedWhite++
                if (red <= 1 && green <= 1 && blue <= 1) clippedBlack++
                index += 3
            }
            val pixels = size * size
            require(maximumLuma - minimumLuma >= 16) {
                "generated image has collapsed luminance"
            }
            require(clippedWhite < pixels * 99 / 100 && clippedBlack < pixels * 99 / 100) {
                "generated image is numerically clipped"
            }
        }

        private fun rgbBytes(bitmap: Bitmap): ByteArray {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return ByteArray(pixels.size * 3).also { output ->
                pixels.forEachIndexed { index, pixel ->
                    output[index * 3] = (pixel ushr 16).toByte()
                    output[index * 3 + 1] = (pixel ushr 8).toByte()
                    output[index * 3 + 2] = pixel.toByte()
                }
            }
        }

        private fun rgbToArgb(rgb: ByteArray, size: Int): IntArray = IntArray(size * size) { index ->
            val offset = index * 3
            0xff000000.toInt() or
                ((rgb[offset].toInt() and 0xff) shl 16) or
                ((rgb[offset + 1].toInt() and 0xff) shl 8) or
                (rgb[offset + 2].toInt() and 0xff)
        }

        private fun requestSha256(
            width: Int,
            height: Int,
            sourcePixels: IntArray,
            mask: ByteArray,
            crop: AndroidGenerationSquareCrop,
            change: LocalGenerativeChange,
            certificateFingerprint: String,
            config: AndroidStableDiffusionCppGenerationConfig
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val integers = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            fun integer(value: Int) {
                integers.clear()
                integers.putInt(value)
                digest.update(integers.array())
            }
            fun text(value: String) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                integer(bytes.size)
                digest.update(bytes)
            }
            listOf(width, height, crop.left, crop.top, crop.size, config.steps,
                config.inferenceSize, config.roiContextPaddingPx).forEach(::integer)
            sourcePixels.forEach(::integer)
            digest.update(mask)
            listOf(
                change.generationPrompt,
                change.promptSourceText,
                certificateFingerprint,
                config.modelId,
                config.modelSha256,
                config.runtimeCommit,
                config.guidanceScale.toString()
            ).forEach(::text)
            return digest.digest().hex()
        }

        private fun evidenceFingerprint(
            requestSha: String,
            nativeOutputSha: String,
            outputPixels: IntArray,
            certificateFingerprint: String,
            runtimeInfo: String
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(
                requestSha,
                nativeOutputSha,
                certificateFingerprint,
                runtimeInfo,
                "inside_feather_radius_px=$FEATHER_RADIUS_PX"
            ).forEach {
                digest.update(it.toByteArray(StandardCharsets.UTF_8))
            }
            val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            outputPixels.forEach { pixel ->
                buffer.clear()
                buffer.putInt(pixel)
                digest.update(buffer.array())
            }
            return digest.digest().hex()
        }

        private fun fileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8 * 1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().hex()
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).hex()

        private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

        private fun ByteArray.hex(): String =
            joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal object AndroidStableDiffusionCppNativeBridge {
    @Volatile private var loaded = false

    fun runtimeInfo(): String {
        ensureLoaded()
        return nativeRuntimeInfo()
    }

    fun generate(
        requestId: Long,
        modelPath: String,
        sourceRgb: ByteArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        negativePrompt: String,
        steps: Int,
        guidanceScale: Float,
        seed: Long
    ): ByteArray {
        ensureLoaded()
        return nativeGenerate(
            requestId,
            modelPath,
            sourceRgb,
            mask,
            width,
            height,
            prompt,
            negativePrompt,
            steps,
            guidanceScale,
            seed
        )
    }

    fun cancel(requestId: Long) {
        if (loaded) nativeCancel(requestId)
    }

    fun progress(requestId: Long): Long = if (loaded) nativeProgress(requestId) else -1L

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("lpa_sdcpp")
                loaded = true
            }
        }
    }

    @JvmStatic private external fun nativeRuntimeInfo(): String

    @JvmStatic private external fun nativeGenerate(
        requestId: Long,
        modelPath: String,
        sourceRgb: ByteArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        negativePrompt: String,
        steps: Int,
        guidanceScale: Float,
        seed: Long
    ): ByteArray

    @JvmStatic private external fun nativeCancel(requestId: Long)
    @JvmStatic private external fun nativeProgress(requestId: Long): Long
}
