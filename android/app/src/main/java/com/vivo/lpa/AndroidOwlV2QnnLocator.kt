package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

data class AndroidOwlV2LocatorRun(
    val result: OwlV2GroundingResult,
    val preprocessingMs: Long,
    val visionExecutionMs: Long,
    val textExecutionMs: Long
)

object AndroidLocalTrackedModelPack {
    fun root(context: Context): File = File(ModelPack.root(context), "local-tracked-preview")

    fun missingOwlV2Artifacts(context: Context): List<String> =
        missingOwlV2Artifacts(root(context))

    fun missingOwlV2Artifacts(root: File): List<String> {
        return listOf(
            "contexts/owlv2_vision_qnn245.bin",
            "contexts/owlv2_text_qnn245.bin",
            "tokenizer/vocab.json",
            "tokenizer/merges.txt",
            "runtime/dsp/libQnnHtpV81Skel.so"
        ).filterNot { File(root, it).isFile }
    }
}

/** Language-only OWLv2 locator backed by the pinned app-UID QAIRT 2.45 context runner. */
class AndroidOwlV2QnnLocator(
    private val context: Context,
    private val modelRoot: File = AndroidLocalTrackedModelPack.root(context)
) {
    fun locate(
        canonicalQuery: String,
        sourceText: String?,
        stateFingerprint: String,
        bitmap: Bitmap,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit = {}
    ): AndroidOwlV2LocatorRun {
        require(BuildConfig.LOCAL_TRACKED_PREVIEW)
        require(!bitmap.isRecycled)
        val missing = AndroidLocalTrackedModelPack.missingOwlV2Artifacts(modelRoot)
        require(missing.isEmpty()) { "OWLv2 QAIRT 2.45 model pack is incomplete: ${missing.joinToString()}" }
        val visionContext = File(modelRoot, "contexts/owlv2_vision_qnn245.bin")
        val textContext = File(modelRoot, "contexts/owlv2_text_qnn245.bin")
        require(AndroidQnnContextProcessRunner.sha256(
            File(modelRoot, "tokenizer/vocab.json")
        ) == VOCAB_SHA256) { "OWLv2 vocabulary hash mismatch" }
        require(AndroidQnnContextProcessRunner.sha256(
            File(modelRoot, "tokenizer/merges.txt")
        ) == MERGES_SHA256) { "OWLv2 merges hash mismatch" }
        require(AndroidQnnContextProcessRunner.sha256(
            File(modelRoot, "runtime/dsp/libQnnHtpV81Skel.so")
        ) == DSP_SKEL_SHA256) { "QAIRT 2.45 DSP skel hash mismatch" }

        check(!cancelled.get()) { "OWLv2 locator cancelled before preprocessing" }
        progress("正在准备端侧语言定位输入…")
        val work = File(context.cacheDir, "owlv2-locator-${System.nanoTime()}").apply { mkdirs() }
        try {
            val preprocessStarted = System.currentTimeMillis()
            val tokenizer = OwlV2ClipTokenizer.fromFiles(
                File(modelRoot, "tokenizer/vocab.json"),
                File(modelRoot, "tokenizer/merges.txt")
            )
            val textInput = tokenizer.encode(canonicalQuery)
            val pixelValues = OwlV2ImagePreprocessor.preprocess(bitmap)
            check(!cancelled.get()) { "OWLv2 locator cancelled after preprocessing" }
            val pixelFile = File(work, "pixel_values.raw")
            writeFloats(pixelFile, pixelValues)
            val preprocessingMs = System.currentTimeMillis() - preprocessStarted

            val runner = AndroidQnnContextProcessRunner(context, modelRoot)
            progress("正在运行 OWLv2 视觉编码（QAIRT 2.45）…")
            val visionContract = AndroidQnnContextContract(
                componentId = "owlv2_vision",
                contextFile = visionContext,
                contextSha256 = VISION_CONTEXT_SHA256,
                inputs = listOf(AndroidQnnTensorContract("pixel_values", 11_059_200)),
                outputs = listOf(AndroidQnnTensorContract("image_embeds", 11_059_200))
            )
            runner.execute(
                visionContract,
                mapOf("pixel_values" to pixelFile),
                cancelled
            ).use { vision ->
                progress("正在匹配语言目标（QAIRT 2.45）…")
                val idsFile = File(work, "input_ids.raw")
                val attentionFile = File(work, "attention_mask.raw")
                writeInts(idsFile, textInput.inputIds)
                writeInts(attentionFile, textInput.attentionMask)
                val textContract = AndroidQnnContextContract(
                    componentId = "owlv2_text",
                    contextFile = textContext,
                    contextSha256 = TEXT_CONTEXT_SHA256,
                    inputs = listOf(
                        AndroidQnnTensorContract("input_ids", 64),
                        AndroidQnnTensorContract("attention_mask", 64),
                        AndroidQnnTensorContract("image_embeds", 11_059_200)
                    ),
                    outputs = listOf(
                        // qnn-net-run appends `_native` for this INT32 native output.
                        AndroidQnnTensorContract("labels_native", 14_400),
                        AndroidQnnTensorContract("scores", 14_400),
                        AndroidQnnTensorContract("boxes", 57_600)
                    )
                )
                runner.execute(
                    textContract,
                    mapOf(
                        "input_ids" to idsFile,
                        "attention_mask" to attentionFile,
                        "image_embeds" to requireNotNull(vision.outputs["image_embeds"])
                    ),
                    cancelled
                ).use { text ->
                    check(!cancelled.get()) { "OWLv2 locator cancelled before postprocessing" }
                    val labels = readInts(requireNotNull(text.outputs["labels_native"]), 3_600)
                    val scores = readFloats(requireNotNull(text.outputs["scores"]), 3_600)
                    val boxes = readFloats(requireNotNull(text.outputs["boxes"]), 14_400)
                    val result = OwlV2GroundingPostprocessor.process(
                        canonicalQuery = canonicalQuery,
                        sourceText = sourceText,
                        stateFingerprint = stateFingerprint,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        boxesXyxy = boxes,
                        scores = scores,
                        labels = labels
                    )
                    return AndroidOwlV2LocatorRun(
                        result,
                        preprocessingMs,
                        vision.elapsedMs,
                        text.elapsedMs
                    )
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    companion object {
        const val VISION_CONTEXT_SHA256 =
            "6bd5393673e57190fa95d9af770fe470ef7b0b038f2edf42da4c6dae0e4bb006"
        const val TEXT_CONTEXT_SHA256 =
            "831c23acd211f29189860e0a9dbb5fb5544aca068835abd5d002ffec3a00a28a"
        const val VOCAB_SHA256 =
            "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349"
        const val MERGES_SHA256 =
            "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"
        const val DSP_SKEL_SHA256 =
            "57330ed3f7846b94181b25b7a87bc3fe5dc71033766f2d5630520a1400477c97"

        internal fun writeFloats(file: File, values: FloatArray) {
            file.parentFile?.mkdirs()
            FileOutputStream(file).channel.use { channel ->
                val buffer = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { value ->
                    if (buffer.remaining() < Float.SIZE_BYTES) {
                        buffer.flip()
                        while (buffer.hasRemaining()) channel.write(buffer)
                        buffer.clear()
                    }
                    buffer.putFloat(value)
                }
                buffer.flip()
                while (buffer.hasRemaining()) channel.write(buffer)
            }
        }

        internal fun writeInts(file: File, values: IntArray) {
            val buffer = ByteBuffer.allocate(values.size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach(buffer::putInt)
            file.writeBytes(buffer.array())
        }

        internal fun readFloats(file: File, count: Int): FloatArray {
            require(file.length() == count.toLong() * Float.SIZE_BYTES)
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(count) { buffer.float }
        }

        internal fun readInts(file: File, count: Int): IntArray {
            require(file.length() == count.toLong() * Int.SIZE_BYTES)
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            return IntArray(count) { buffer.int }
        }
    }
}
