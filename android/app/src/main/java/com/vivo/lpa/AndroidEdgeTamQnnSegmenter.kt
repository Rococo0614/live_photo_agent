package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class AndroidEdgeTamInitialMask(
    val width: Int,
    val height: Int,
    val maskValues: ByteArray,
    val selectedPixels: Int,
    val coverage: Double,
    val outsideExpandedBoxFraction: Double,
    val score: Float,
    val objectScoreLogit: Float,
    val qualityAccepted: Boolean,
    val evidenceFingerprint: String,
    val preprocessingMs: Long,
    val encoderExecutionMs: Long,
    val decoderExecutionMs: Long
)

data class AndroidEdgeTamPrompt(
    val coordinates: FloatArray,
    val labels: FloatArray
) {
    init {
        require(coordinates.size == 4)
        require(labels.size == 2)
    }
}

/** Unique OWLv2 candidate -> EdgeTAM key-frame mask, pinned to QAIRT 2.45. */
class AndroidEdgeTamQnnSegmenter(
    private val context: Context,
    private val modelRoot: File = AndroidLocalTrackedModelPack.root(context)
) {
    fun segment(
        sourceBitmap: Bitmap,
        grounding: OwlV2GroundingResult,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit = {},
        diagnosticImageInputCopy: File? = null
    ): AndroidEdgeTamInitialMask {
        require(!sourceBitmap.isRecycled)
        require(grounding.disposition == OwlV2GroundingDisposition.CANDIDATE_SET_READY)
        val candidate = grounding.candidates.single()
        val missing = listOf(
            "contexts/edgetam_encoder_qnn245.bin",
            "contexts/edgetam_decoder_qnn245.bin",
            "runtime/dsp/libQnnHtpV81Skel.so"
        ).filterNot { File(modelRoot, it).isFile }
        require(missing.isEmpty()) { "EdgeTAM QAIRT 2.45 model pack is incomplete: ${missing.joinToString()}" }
        require(AndroidQnnContextProcessRunner.sha256(
            File(modelRoot, "runtime/dsp/libQnnHtpV81Skel.so")
        ) == AndroidOwlV2QnnLocator.DSP_SKEL_SHA256) {
            "QAIRT 2.45 DSP skel hash mismatch"
        }
        check(!cancelled.get()) { "EdgeTAM segmentation cancelled before preprocessing" }
        val work = File(context.cacheDir, "edgetam-segment-${System.nanoTime()}").apply { mkdirs() }
        try {
            progress("正在准备 EdgeTAM 交互分割输入…")
            val preprocessingStarted = System.currentTimeMillis()
            val imageFile = File(work, "image.raw")
            AndroidOwlV2QnnLocator.writeFloats(imageFile, preprocessImage(sourceBitmap))
            diagnosticImageInputCopy?.let { target ->
                require(BuildConfig.DEBUG) { "diagnostic tensor copies are debug-only" }
                target.parentFile?.mkdirs()
                imageFile.copyTo(target, overwrite = true)
            }
            val prompt = promptFor(grounding, sourceBitmap.width, sourceBitmap.height)
            val coordinateFile = File(work, "unnorm_coords.raw")
            val labelFile = File(work, "labels.raw")
            AndroidOwlV2QnnLocator.writeFloats(coordinateFile, prompt.coordinates)
            AndroidOwlV2QnnLocator.writeFloats(labelFile, prompt.labels)
            val preprocessingMs = System.currentTimeMillis() - preprocessingStarted
            check(!cancelled.get()) { "EdgeTAM segmentation cancelled after preprocessing" }

            val runner = AndroidQnnContextRunnerFactory.create(context, modelRoot)
            progress("正在运行 EdgeTAM 图像编码（QAIRT 2.45）…")
            val encoderContract = AndroidQnnContextContract(
                componentId = "edgetam_encoder",
                contextFile = File(modelRoot, "contexts/edgetam_encoder_qnn245.bin"),
                contextSha256 = ENCODER_CONTEXT_SHA256,
                inputs = listOf(
                    AndroidQnnTensorContract("image", 12_582_912),
                    AndroidQnnTensorContract("unnorm_coords", 16),
                    AndroidQnnTensorContract("labels", 8)
                ),
                outputs = listOf(
                    AndroidQnnTensorContract("high_res_features1", 8_388_608),
                    AndroidQnnTensorContract("high_res_features2", 4_194_304),
                    AndroidQnnTensorContract("sparse_embedding", 3_072),
                    AndroidQnnTensorContract("pix_feat", 4_194_304),
                    AndroidQnnTensorContract("image_embeddings", 4_194_304)
                )
            )
            runner.execute(
                encoderContract,
                mapOf(
                    "image" to imageFile,
                    "unnorm_coords" to coordinateFile,
                    "labels" to labelFile
                ),
                cancelled
            ).use { encoder ->
                progress("正在生成目标区域 mask（QAIRT 2.45）…")
                val decoderContract = AndroidQnnContextContract(
                    componentId = "edgetam_decoder",
                    contextFile = File(modelRoot, "contexts/edgetam_decoder_qnn245.bin"),
                    contextSha256 = DECODER_CONTEXT_SHA256,
                    inputs = listOf(
                        AndroidQnnTensorContract("image_embeddings", 4_194_304),
                        AndroidQnnTensorContract("high_res_features1", 8_388_608),
                        AndroidQnnTensorContract("high_res_features2", 4_194_304),
                        AndroidQnnTensorContract("sparse_embedding", 3_072)
                    ),
                    outputs = listOf(
                        AndroidQnnTensorContract("masks", 262_144),
                        AndroidQnnTensorContract("scores", 4),
                        AndroidQnnTensorContract("obj_ptr", 1_024),
                        AndroidQnnTensorContract("object_score_logits", 4)
                    )
                )
                runner.execute(
                    decoderContract,
                    mapOf(
                        "image_embeddings" to requireNotNull(encoder.outputs["pix_feat"]),
                        "high_res_features1" to requireNotNull(encoder.outputs["high_res_features1"]),
                        "high_res_features2" to requireNotNull(encoder.outputs["high_res_features2"]),
                        "sparse_embedding" to requireNotNull(encoder.outputs["sparse_embedding"])
                    ),
                    cancelled
                ).use { decoder ->
                    check(!cancelled.get()) { "EdgeTAM segmentation cancelled before validation" }
                    val logits = AndroidOwlV2QnnLocator.readFloats(
                        requireNotNull(decoder.outputs["masks"]),
                        256 * 256
                    )
                    val mask = resizeAndThreshold(
                        logits,
                        256,
                        sourceBitmap.width,
                        sourceBitmap.height
                    )
                    val selected = mask.count { (it.toInt() and 0xff) > 0 }
                    val coverage = selected.toDouble() / mask.size
                    val outsideFraction = outsideExpandedBoxFraction(
                        mask,
                        sourceBitmap.width,
                        sourceBitmap.height,
                        candidate.box
                    )
                    val score = readSingleFloat(requireNotNull(decoder.outputs["scores"]))
                    val objectLogit = readSingleFloat(
                        requireNotNull(decoder.outputs["object_score_logits"])
                    )
                    val accepted = objectLogit > 0f && coverage > 0.0 &&
                        coverage <= MAX_MASK_COVERAGE &&
                        outsideFraction <= MAX_OUTSIDE_BOX_FRACTION
                    return AndroidEdgeTamInitialMask(
                        width = sourceBitmap.width,
                        height = sourceBitmap.height,
                        maskValues = mask,
                        selectedPixels = selected,
                        coverage = coverage,
                        outsideExpandedBoxFraction = outsideFraction,
                        score = score,
                        objectScoreLogit = objectLogit,
                        qualityAccepted = accepted,
                        evidenceFingerprint = fingerprint(
                            grounding.evidenceFingerprint,
                            mask,
                            score,
                            objectLogit
                        ),
                        preprocessingMs = preprocessingMs,
                        encoderExecutionMs = encoder.elapsedMs,
                        decoderExecutionMs = decoder.elapsedMs
                    )
                }
            }
        } finally {
            work.deleteRecursively()
            AndroidQnnContextRunnerFactory.releasePersistentContexts()
        }
    }

    companion object {
        const val ENCODER_CONTEXT_SHA256 =
            "0edaed8a468ab003dff72fd57574b3ffc95bf2ac9fbb89fba94070b12811ddc0"
        const val DECODER_CONTEXT_SHA256 =
            "0fbc23c5fd70760bf9aeb369f88936b112e6a54cbc03155ff7a3ee4067fd08dc"
        const val EDGE_SIZE = 1024
        const val BOX_MARGIN_FRACTION = 0.15
        const val MAX_MASK_COVERAGE = 0.25
        const val MAX_OUTSIDE_BOX_FRACTION = 0.25
        private const val PILLOW_PRECISION_BITS = 22
        private const val PILLOW_ROUNDING = 1 shl (PILLOW_PRECISION_BITS - 1)

        internal fun promptPoints(
            box: OwlV2InternalBox,
            imageWidth: Int,
            imageHeight: Int
        ): FloatArray {
            val centerX = (box.left + box.right) / 2.0
            val height = box.bottom - box.top
            return floatArrayOf(
                (centerX / imageWidth).coerceIn(0.0, 1.0).toFloat(),
                ((box.top + height * 0.50) / imageHeight).coerceIn(0.0, 1.0).toFloat(),
                (centerX / imageWidth).coerceIn(0.0, 1.0).toFloat(),
                ((box.top + height * 0.65) / imageHeight).coerceIn(0.0, 1.0).toFloat()
            )
        }

        internal fun promptFor(
            grounding: OwlV2GroundingResult,
            imageWidth: Int,
            imageHeight: Int
        ): AndroidEdgeTamPrompt {
            val box = grounding.candidates.single().box
            return if (HUMAN_TARGET.containsMatchIn(grounding.canonicalQuery)) {
                val width = box.right - box.left
                val height = box.bottom - box.top
                val broadBox = height / width < 1.55
                val promptX = box.left + width * if (broadBox) 0.30 else 0.50
                val lowerY = if (broadBox) 0.65 else 0.75
                AndroidEdgeTamPrompt(
                    floatArrayOf(
                        (promptX / imageWidth).coerceIn(0.0, 1.0).toFloat(),
                        ((box.top + height * 0.35) / imageHeight).coerceIn(0.0, 1.0).toFloat(),
                        (promptX / imageWidth).coerceIn(0.0, 1.0).toFloat(),
                        ((box.top + height * lowerY) / imageHeight).coerceIn(0.0, 1.0).toFloat()
                    ),
                    floatArrayOf(1f, 1f)
                )
            } else {
                AndroidEdgeTamPrompt(
                    promptPoints(box, imageWidth, imageHeight),
                    floatArrayOf(1f, 1f)
                )
            }
        }

        internal fun preprocessImage(bitmap: Bitmap, targetSize: Int = EDGE_SIZE): FloatArray {
            require(!bitmap.isRecycled && targetSize > 0)
            val width = bitmap.width
            val height = bitmap.height
            val source = IntArray(width * height)
            bitmap.getPixels(source, 0, width, 0, 0, width, height)
            return preprocessArgb(width, height, source, targetSize)
        }

        private val HUMAN_TARGET = Regex(
            """(?i)\b(person|people|human|man|woman|girl|boy|skater|skateboarder)\b"""
        )

        /** Pillow's UINT8 `Image.resize(..., BILINEAR)` two-pass convolution. */
        internal fun preprocessArgb(
            width: Int,
            height: Int,
            source: IntArray,
            targetSize: Int
        ): FloatArray {
            require(width > 0 && height > 0 && source.size == width * height && targetSize > 0)
            val horizontalCoefficients = pillowBilinearCoefficients(width, targetSize)
            val horizontal = IntArray(targetSize * height)
            for (y in 0 until height) {
                for (x in 0 until targetSize) {
                    val coefficients = horizontalCoefficients[x]
                    var red = PILLOW_ROUNDING
                    var green = PILLOW_ROUNDING
                    var blue = PILLOW_ROUNDING
                    coefficients.weights.forEachIndexed { offset, weight ->
                        val pixel = source[y * width + coefficients.start + offset]
                        red += (pixel ushr 16 and 0xff) * weight
                        green += (pixel ushr 8 and 0xff) * weight
                        blue += (pixel and 0xff) * weight
                    }
                    horizontal[y * targetSize + x] =
                        (clipByte(red shr PILLOW_PRECISION_BITS) shl 16) or
                            (clipByte(green shr PILLOW_PRECISION_BITS) shl 8) or
                            clipByte(blue shr PILLOW_PRECISION_BITS)
                }
            }

            val verticalCoefficients = pillowBilinearCoefficients(height, targetSize)
            val output = FloatArray(targetSize * targetSize * 3)
            for (y in 0 until targetSize) {
                val coefficients = verticalCoefficients[y]
                for (x in 0 until targetSize) {
                    var red = PILLOW_ROUNDING
                    var green = PILLOW_ROUNDING
                    var blue = PILLOW_ROUNDING
                    coefficients.weights.forEachIndexed { offset, weight ->
                        val pixel = horizontal[(coefficients.start + offset) * targetSize + x]
                        red += (pixel ushr 16 and 0xff) * weight
                        green += (pixel ushr 8 and 0xff) * weight
                        blue += (pixel and 0xff) * weight
                    }
                    val outputOffset = (y * targetSize + x) * 3
                    output[outputOffset] =
                        clipByte(red shr PILLOW_PRECISION_BITS) / 255f
                    output[outputOffset + 1] =
                        clipByte(green shr PILLOW_PRECISION_BITS) / 255f
                    output[outputOffset + 2] =
                        clipByte(blue shr PILLOW_PRECISION_BITS) / 255f
                }
            }
            return output
        }

        private data class PillowCoefficients(val start: Int, val weights: IntArray)

        private fun pillowBilinearCoefficients(
            inputSize: Int,
            outputSize: Int
        ): Array<PillowCoefficients> {
            val scale = inputSize.toDouble() / outputSize
            val filterScale = max(scale, 1.0)
            val support = filterScale
            return Array(outputSize) { outputIndex ->
                val center = (outputIndex + 0.5) * scale
                var minimum = (center - support + 0.5).toInt().coerceAtLeast(0)
                val maximum = (center + support + 0.5).toInt().coerceAtMost(inputSize)
                if (minimum >= maximum) minimum = (maximum - 1).coerceAtLeast(0)
                val doubleWeights = DoubleArray(maximum - minimum)
                var total = 0.0
                for (offset in doubleWeights.indices) {
                    val distance = kotlin.math.abs(
                        (offset + minimum - center + 0.5) / filterScale
                    )
                    val weight = if (distance < 1.0) 1.0 - distance else 0.0
                    doubleWeights[offset] = weight
                    total += weight
                }
                PillowCoefficients(minimum, IntArray(doubleWeights.size) { offset ->
                    val normalized = if (total == 0.0) 0.0 else doubleWeights[offset] / total
                    (normalized * (1 shl PILLOW_PRECISION_BITS) + 0.5).toInt()
                })
            }
        }

        private fun clipByte(value: Int): Int = value.coerceIn(0, 255)

        internal fun resizeAndThreshold(
            logits: FloatArray,
            sourceSize: Int,
            width: Int,
            height: Int
        ): ByteArray {
            require(logits.size == sourceSize * sourceSize && width > 0 && height > 0)
            val output = ByteArray(width * height)
            val horizontal = bilinearAxis(sourceSize, width)
            val vertical = bilinearAxis(sourceSize, height)
            for (y in 0 until height) {
                val topOffset = vertical.lower[y] * sourceSize
                val bottomOffset = vertical.upper[y] * sourceSize
                val yWeight = vertical.weight[y]
                for (x in 0 until width) {
                    val left = horizontal.lower[x]
                    val right = horizontal.upper[x]
                    val xWeight = horizontal.weight[x]
                    val topValue = logits[topOffset + left] * (1f - xWeight) +
                        logits[topOffset + right] * xWeight
                    val bottomValue = logits[bottomOffset + left] * (1f - xWeight) +
                        logits[bottomOffset + right] * xWeight
                    if (topValue * (1f - yWeight) + bottomValue * yWeight > 0f) {
                        output[y * width + x] = 0xff.toByte()
                    }
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

        internal fun outsideExpandedBoxFraction(
            mask: ByteArray,
            width: Int,
            height: Int,
            box: OwlV2InternalBox
        ): Double {
            require(mask.size == width * height)
            val marginX = (box.right - box.left) * BOX_MARGIN_FRACTION
            val marginY = (box.bottom - box.top) * BOX_MARGIN_FRACTION
            val left = max(0, floor(box.left - marginX).toInt())
            val top = max(0, floor(box.top - marginY).toInt())
            val right = min(width, ceil(box.right + marginX).toInt())
            val bottom = min(height, ceil(box.bottom + marginY).toInt())
            var selected = 0
            var inside = 0
            mask.forEachIndexed { index, value ->
                if ((value.toInt() and 0xff) == 0) return@forEachIndexed
                selected += 1
                val x = index % width
                val y = index / width
                if (x in left until right && y in top until bottom) inside += 1
            }
            return if (selected == 0) 1.0 else (selected - inside).toDouble() / selected
        }

        private fun readSingleFloat(file: File): Float {
            require(file.length() == 4L)
            return ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).float
        }

        private fun fingerprint(
            groundingFingerprint: String,
            mask: ByteArray,
            score: Float,
            objectLogit: Float
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(groundingFingerprint.toByteArray(Charsets.UTF_8))
            digest.update(ENCODER_CONTEXT_SHA256.toByteArray(Charsets.UTF_8))
            digest.update(DECODER_CONTEXT_SHA256.toByteArray(Charsets.UTF_8))
            digest.update(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(score.toRawBits()).putInt(objectLogit.toRawBits()).array())
            digest.update(mask)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
