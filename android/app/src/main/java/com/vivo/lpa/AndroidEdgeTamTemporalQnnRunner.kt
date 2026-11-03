package com.vivo.lpa

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.floor

data class AndroidEdgeTamTemporalFrameResult(
    val sequenceIndex: Int,
    val maskValues: ByteArray,
    val rawMaskPixels: Int,
    val score: Float,
    val objectScoreLogit: Float,
    val modelObjectPresent: Boolean,
    val spatialMemoryCount: Int,
    val objectPointerCount: Int,
    val encoderMs: Long,
    val attentionMs: Long?,
    val decoderMs: Long,
    val memoryEncoderMs: Long?,
    val decoderMaskRawSha256: String,
    val evidenceFingerprint: String
)

/** Executes one EdgeTAM sequence step using the validated fixed-count QAIRT 2.45 graph bank. */
class AndroidEdgeTamTemporalQnnRunner(
    private val context: Context,
    private val modelRoot: File = AndroidLocalTrackedModelPack.root(context)
) {
    fun createState(): AndroidEdgeTamTemporalState {
        val noObject = File(modelRoot, "supplementary/no_obj_ptr.npy")
        require(noObject.isFile && AndroidQnnContextProcessRunner.sha256(noObject) == NO_OBJECT_NPY_SHA256) {
            "EdgeTAM no-object pointer is missing or has the wrong hash"
        }
        return AndroidEdgeTamTemporalState(
            AndroidEdgeTamTemporalState.loadNpyFloat32(
                noObject,
                AndroidEdgeTamTemporalState.OBJECT_POINTER_FLOAT_COUNT
            )
        )
    }

    fun processRawFrame(
        sequenceIndex: Int,
        imageInput: File,
        sourceWidth: Int,
        sourceHeight: Int,
        initialPromptCoordinates: FloatArray?,
        state: AndroidEdgeTamTemporalState,
        cancelled: AtomicBoolean,
        produceMemory: Boolean = true,
        progress: (String) -> Unit = {}
    ): AndroidEdgeTamTemporalFrameResult {
        require(BuildConfig.LOCAL_TRACKED_PREVIEW)
        require(sequenceIndex == state.processedFrameCount)
        require(imageInput.isFile && imageInput.length() == IMAGE_INPUT_BYTES)
        require(sourceWidth > 0 && sourceHeight > 0)
        if (sequenceIndex == 0) require(initialPromptCoordinates?.size == 4)
        else require(initialPromptCoordinates == null)
        requireModelPack()
        val work = File(
            context.cacheDir,
            "edgetam-temporal/step-${sequenceIndex}-${System.nanoTime()}"
        ).apply { mkdirs() }
        try {
            val coordinates = File(work, "unnorm_coords.raw")
            val labels = File(work, "labels.raw")
            if (sequenceIndex == 0) {
                AndroidOwlV2QnnLocator.writeFloats(
                    coordinates,
                    requireNotNull(initialPromptCoordinates)
                )
                AndroidOwlV2QnnLocator.writeFloats(labels, floatArrayOf(1f, 1f))
            } else {
                AndroidOwlV2QnnLocator.writeFloats(coordinates, FloatArray(4))
                AndroidOwlV2QnnLocator.writeFloats(labels, floatArrayOf(-1f, -1f))
            }
            val runner = AndroidQnnContextRunnerFactory.create(context, modelRoot)
            progress("EdgeTAM frame ${sequenceIndex + 1}: encoder")
            runner.execute(
                encoderContract(),
                mapOf(
                    "image" to imageInput,
                    "unnorm_coords" to coordinates,
                    "labels" to labels
                ),
                cancelled
            ).use { encoder ->
                var attentionMs: Long? = null
                var spatialMemoryCount = 0
                var objectPointerCount = 0
                val decoderEmbedding = if (sequenceIndex == 0) {
                    requireNotNull(encoder.outputs["pix_feat"])
                } else {
                    val temporalInputs = state.inputsForNextFrame()
                    spatialMemoryCount = temporalInputs.spatialMemoryCount
                    objectPointerCount = temporalInputs.objectPointerCount
                    val maskMemory = File(work, "maskmem_features.raw").apply {
                        writeBytes(temporalInputs.maskMemoryValues)
                    }
                    val pointers = File(work, "object_pointers.raw").apply {
                        writeBytes(temporalInputs.objectPointerValues)
                    }
                    progress(
                        "EdgeTAM frame ${sequenceIndex + 1}: attention " +
                            "$spatialMemoryCount/$objectPointerCount"
                    )
                    runner.execute(
                        attentionContract(temporalInputs),
                        mapOf(
                            "current_embeddings" to
                                requireNotNull(encoder.outputs["image_embeddings"]),
                            "maskmem_features" to maskMemory,
                            "object_pointers" to pointers
                        ),
                        cancelled
                    ).use { attention ->
                        attentionMs = attention.elapsedMs
                        File(work, "attention_pix_feat.raw").also { output ->
                            requireNotNull(attention.outputs["pix_feat"])
                                .copyTo(output, overwrite = true)
                        }
                    }
                }

                progress("EdgeTAM frame ${sequenceIndex + 1}: decoder")
                runner.execute(
                    decoderContract(),
                    mapOf(
                        "image_embeddings" to decoderEmbedding,
                        "high_res_features1" to requireNotNull(encoder.outputs["high_res_features1"]),
                        "high_res_features2" to requireNotNull(encoder.outputs["high_res_features2"]),
                        "sparse_embedding" to requireNotNull(encoder.outputs["sparse_embedding"])
                    ),
                    cancelled
                ).use { decoder ->
                    val score = readSingleFloat(requireNotNull(decoder.outputs["scores"]))
                    val objectLogit = readSingleFloat(
                        requireNotNull(decoder.outputs["object_score_logits"])
                    )
                    val modelObjectPresent = objectLogit > 0f
                    val lowResolution = AndroidOwlV2QnnLocator.readFloats(
                        requireNotNull(decoder.outputs["masks"]),
                        MASK_SIZE * MASK_SIZE
                    )
                    val decoderMaskRawSha256 = AndroidQnnContextProcessRunner.sha256(
                        requireNotNull(decoder.outputs["masks"])
                    )
                    if (!modelObjectPresent) lowResolution.fill(-1024f)
                    val mask = AndroidEdgeTamQnnSegmenter.resizeAndThreshold(
                        lowResolution,
                        MASK_SIZE,
                        sourceWidth,
                        sourceHeight
                    )
                    var memoryEncoderMs: Long? = null
                    if (produceMemory) {
                        val memoryImage = File(work, "memory_pix_feat.raw")
                        memoryImage.writeBytes(transposeNhwcToNchw(
                            requireNotNull(encoder.outputs["image_embeddings"]).readBytes(),
                            FEATURE_SIZE,
                            FEATURE_SIZE,
                            FEATURE_CHANNELS
                        ))
                        val highResolution = resizeFloatBilinear(
                            lowResolution,
                            MASK_SIZE,
                            MASK_SIZE,
                            AndroidEdgeTamQnnSegmenter.EDGE_SIZE,
                            AndroidEdgeTamQnnSegmenter.EDGE_SIZE
                        )
                        if (sequenceIndex == 0) {
                            highResolution.indices.forEach { index ->
                                highResolution[index] = if (highResolution[index] > 0f) 1f else 0f
                            }
                        } else {
                            highResolution.indices.forEach { index ->
                                highResolution[index] = sigmoid(highResolution[index])
                            }
                        }
                        val memoryMask = File(work, "mask_for_mem.raw")
                        AndroidOwlV2QnnLocator.writeFloats(memoryMask, highResolution)
                        progress("EdgeTAM frame ${sequenceIndex + 1}: memory encoder")
                        runner.execute(
                            memoryEncoderContract(),
                            mapOf(
                                "pix_feat" to memoryImage,
                                "mask_for_mem" to memoryMask
                            ),
                            cancelled
                        ).use { memory ->
                            memoryEncoderMs = memory.elapsedMs
                            state.append(
                                sequenceIndex,
                                requireNotNull(memory.outputs["maskmem_features"]).readBytes(),
                                requireNotNull(decoder.outputs["obj_ptr"]).readBytes(),
                                modelObjectPresent
                            )
                        }
                    }
                    return AndroidEdgeTamTemporalFrameResult(
                        sequenceIndex = sequenceIndex,
                        maskValues = mask,
                        rawMaskPixels = mask.count { (it.toInt() and 0xff) > 0 },
                        score = score,
                        objectScoreLogit = objectLogit,
                        modelObjectPresent = modelObjectPresent,
                        spatialMemoryCount = spatialMemoryCount,
                        objectPointerCount = objectPointerCount,
                        encoderMs = encoder.elapsedMs,
                        attentionMs = attentionMs,
                        decoderMs = decoder.elapsedMs,
                        memoryEncoderMs = memoryEncoderMs,
                        decoderMaskRawSha256 = decoderMaskRawSha256,
                        evidenceFingerprint = fingerprint(
                            sequenceIndex,
                            mask,
                            score,
                            objectLogit,
                            spatialMemoryCount,
                            objectPointerCount
                        )
                    )
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun requireModelPack() {
        val required = buildList {
            add("contexts/edgetam_encoder_qnn245.bin")
            add("contexts/edgetam_decoder_qnn245.bin")
            add("contexts/edgetam_memory_encoder_qnn245.bin")
            add("supplementary/no_obj_ptr.npy")
            ATTENTION_CONTEXT_SHA256.keys.forEach { add("contexts/$it") }
        }
        val missing = required.filterNot { File(modelRoot, it).isFile }
        require(missing.isEmpty()) { "EdgeTAM temporal model pack is incomplete: ${missing.joinToString()}" }
    }

    private fun encoderContract() = AndroidQnnContextContract(
        "edgetam_encoder",
        File(modelRoot, "contexts/edgetam_encoder_qnn245.bin"),
        AndroidEdgeTamQnnSegmenter.ENCODER_CONTEXT_SHA256,
        listOf(
            AndroidQnnTensorContract("image", IMAGE_INPUT_BYTES),
            AndroidQnnTensorContract("unnorm_coords", 16),
            AndroidQnnTensorContract("labels", 8)
        ),
        listOf(
            AndroidQnnTensorContract("high_res_features1", 8_388_608),
            AndroidQnnTensorContract("high_res_features2", 4_194_304),
            AndroidQnnTensorContract("sparse_embedding", 3_072),
            AndroidQnnTensorContract("pix_feat", 4_194_304),
            AndroidQnnTensorContract("image_embeddings", 4_194_304)
        )
    )

    private fun attentionContract(inputs: AndroidEdgeTamTemporalInputs) =
        AndroidQnnContextContract(
            "edgetam_attention_${inputs.spatialMemoryCount}_${inputs.objectPointerCount}",
            File(modelRoot, "contexts/${inputs.contextFileName}"),
            requireNotNull(ATTENTION_CONTEXT_SHA256[inputs.contextFileName]),
            listOf(
                AndroidQnnTensorContract("current_embeddings", 4_194_304),
                AndroidQnnTensorContract(
                    "maskmem_features",
                    inputs.spatialMemoryCount.toLong() * AndroidEdgeTamTemporalState.MASK_MEMORY_BYTES
                ),
                AndroidQnnTensorContract(
                    "object_pointers",
                    inputs.objectPointerCount.toLong() *
                        AndroidEdgeTamTemporalState.OBJECT_POINTER_BYTES
                )
            ),
            listOf(AndroidQnnTensorContract("pix_feat", 4_194_304))
        )

    private fun decoderContract() = AndroidQnnContextContract(
        "edgetam_decoder",
        File(modelRoot, "contexts/edgetam_decoder_qnn245.bin"),
        AndroidEdgeTamQnnSegmenter.DECODER_CONTEXT_SHA256,
        listOf(
            AndroidQnnTensorContract("image_embeddings", 4_194_304),
            AndroidQnnTensorContract("high_res_features1", 8_388_608),
            AndroidQnnTensorContract("high_res_features2", 4_194_304),
            AndroidQnnTensorContract("sparse_embedding", 3_072)
        ),
        listOf(
            AndroidQnnTensorContract("masks", 262_144),
            AndroidQnnTensorContract("scores", 4),
            AndroidQnnTensorContract("obj_ptr", 1_024),
            AndroidQnnTensorContract("object_score_logits", 4)
        )
    )

    private fun memoryEncoderContract() = AndroidQnnContextContract(
        "edgetam_memory_encoder",
        File(modelRoot, "contexts/edgetam_memory_encoder_qnn245.bin"),
        MEMORY_ENCODER_CONTEXT_SHA256,
        listOf(
            AndroidQnnTensorContract("pix_feat", 4_194_304),
            AndroidQnnTensorContract("mask_for_mem", 4_194_304)
        ),
        listOf(AndroidQnnTensorContract("maskmem_features", 131_072))
    )

    companion object {
        const val IMAGE_INPUT_BYTES = 12_582_912L
        const val MEMORY_ENCODER_CONTEXT_SHA256 =
            "bf13e61cbb873da162fbd9d28008b6587bc5a7fbcb6998f12ab6297feed9b3f1"
        const val NO_OBJECT_NPY_SHA256 =
            "1eaf62836fa7a7afb592bea5b2c043fee463cfd9a488c59440bded15982679fd"
        private const val FEATURE_SIZE = 64
        private const val FEATURE_CHANNELS = 256
        private const val MASK_SIZE = 256

        val ATTENTION_CONTEXT_SHA256 = mapOf(
            "memory_attention_1_o3.bin" to "676e569ed51a844e6c26b19eaa83c8f62b44fd4926b54f21b974b24931cd0fa9",
            "memory_attention_2_o3.bin" to "7a9667d81e2954ebfeba2f67e15c447f1a6eadf59122e5d03265578bcdb50593",
            "memory_attention_3_o3.bin" to "e7a124bc5dfdeb61f4b87040c8a803fcd2e34ab04c126e60bab6baf1472e7c04",
            "memory_attention_4_o3.bin" to "6da815487e58ca22ca7b95724eadb645458943cff22d2c2bef31f13eb43204f3",
            "memory_attention_5_o3.bin" to "683cecec5b0ed2fcadcf07b6a41685a380b14bfff2bef485606622e66b24cde7",
            "memory_attention_6_o3.bin" to "083a042982d1c95b91a978466c1c012eadd6dc74b6728fefff5a80780b60554f",
            "memory_attention_7_o3.bin" to "96a423580cd34b649ca6e8acb174f096fe3f4bd0b154ea70f05965092981f957",
            "memory_attention_7_8_o3.bin" to "597fdec836203fcf9acaec2a3d6b9ddbc5e7b29cb00b265da8216777e3b36079",
            "memory_attention_7_9_o3.bin" to "95595694134242e7cc20d5f7f3de2cadf6f6f90b20a75d8f1560c65c92237227",
            "memory_attention_7_10_o3.bin" to "31498f41d0257f68b93d6a438553192eff5914ab645249ab9a0c63337d3ab765",
            "memory_attention_7_11_o3.bin" to "e28268e2c2ef3f25cb6dffab8c532400520ad70ad05d7df035015686f0e9a574",
            "memory_attention_7_12_o3.bin" to "da3e14c676dd8f4339bd22a3162b5f3f57364678d53e99c34c305d1a389ee846",
            "memory_attention_7_13_o3.bin" to "794c97259188de76e5a7dbe79c2ab0cd6fd6374340db849deae02f9c786585e6",
            "memory_attention_7_14_o3.bin" to "d1d3af82b2ebbf5c010aa329ca621f834d3fe1ed76447759d1fa237505e16dca",
            "memory_attention_7_15_o3.bin" to "46d099d26fc7604a884241b9f9ef47e1ed156b2c2f1fe6d9888a7526ade6f6e0",
            "memory_attention_7_16_o3.bin" to "a78d2bb51c1ded313cfedf39fd718c9f079f19afa20fe17304f4b92234231f6f"
        )

        internal fun transposeNhwcToNchw(
            values: ByteArray,
            height: Int,
            width: Int,
            channels: Int
        ): ByteArray {
            require(values.size == height * width * channels * Float.SIZE_BYTES)
            val input = ByteBuffer.wrap(values).order(ByteOrder.LITTLE_ENDIAN)
            val outputBytes = ByteArray(values.size)
            val output = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    for (channel in 0 until channels) {
                        val sourceIndex = ((y * width + x) * channels + channel) * Float.SIZE_BYTES
                        val targetIndex = ((channel * height + y) * width + x) * Float.SIZE_BYTES
                        output.putInt(targetIndex, input.getInt(sourceIndex))
                    }
                }
            }
            return outputBytes
        }

        internal fun resizeFloatBilinear(
            input: FloatArray,
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int
        ): FloatArray {
            require(input.size == sourceWidth * sourceHeight)
            val output = FloatArray(targetWidth * targetHeight)
            val horizontal = bilinearAxis(sourceWidth, targetWidth)
            val vertical = bilinearAxis(sourceHeight, targetHeight)
            for (y in 0 until targetHeight) {
                val topOffset = vertical.lower[y] * sourceWidth
                val bottomOffset = vertical.upper[y] * sourceWidth
                val yWeight = vertical.weight[y]
                for (x in 0 until targetWidth) {
                    val left = horizontal.lower[x]
                    val right = horizontal.upper[x]
                    val xWeight = horizontal.weight[x]
                    val topValue = input[topOffset + left] * (1f - xWeight) +
                        input[topOffset + right] * xWeight
                    val bottomValue = input[bottomOffset + left] * (1f - xWeight) +
                        input[bottomOffset + right] * xWeight
                    output[y * targetWidth + x] =
                        topValue * (1f - yWeight) + bottomValue * yWeight
                }
            }
            return output
        }

        private data class BilinearAxis(
            val lower: IntArray,
            val upper: IntArray,
            val weight: FloatArray
        )

        private fun bilinearAxis(sourceSize: Int, targetSize: Int): BilinearAxis {
            val scale = sourceSize.toDouble() / targetSize
            val lower = IntArray(targetSize)
            val upper = IntArray(targetSize)
            val weight = FloatArray(targetSize)
            for (index in 0 until targetSize) {
                val source = (index + 0.5) * scale - 0.5
                val floorValue = floor(source)
                val rawLower = floorValue.toInt()
                lower[index] = rawLower.coerceIn(0, sourceSize - 1)
                upper[index] = (rawLower + 1).coerceIn(0, sourceSize - 1)
                weight[index] = (source - floorValue).toFloat().coerceIn(0f, 1f)
            }
            return BilinearAxis(lower, upper, weight)
        }

        private fun sigmoid(value: Float): Float = if (value >= 0f) {
            (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
        } else {
            val exponent = exp(value.toDouble())
            (exponent / (1.0 + exponent)).toFloat()
        }

        private fun readSingleFloat(file: File): Float = ByteBuffer.wrap(file.readBytes())
            .order(ByteOrder.LITTLE_ENDIAN)
            .float

        private fun fingerprint(
            sequenceIndex: Int,
            mask: ByteArray,
            score: Float,
            objectLogit: Float,
            memoryCount: Int,
            pointerCount: Int
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val facts = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
                .putInt(sequenceIndex)
                .putInt(score.toRawBits())
                .putInt(objectLogit.toRawBits())
                .putInt(memoryCount)
                .putInt(pointerCount)
                .array()
            digest.update(facts)
            digest.update(mask)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
