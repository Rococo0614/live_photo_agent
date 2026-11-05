package com.vivo.lpa

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class AndroidMaskedGenerationResult(
    val bitmap: Bitmap,
    val backendId: String,
    val evidenceFingerprint: String
)

interface AndroidMaskedGenerationBackend {
    val backendId: String
    val available: Boolean

    fun generate(
        source: Bitmap,
        maskValues: ByteArray,
        change: LocalGenerativeChange,
        certificateFingerprint: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit = {}
    ): AndroidMaskedGenerationResult
}

object UnavailableAndroidMaskedGenerationBackend : AndroidMaskedGenerationBackend {
    override val backendId = "unavailable_masked_generation"
    override val available = false

    override fun generate(
        source: Bitmap,
        maskValues: ByteArray,
        change: LocalGenerativeChange,
        certificateFingerprint: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): AndroidMaskedGenerationResult = error("masked generation backend is unavailable")
}

/** Debug fixture proving backend wiring and mask confinement; it is not a generative model. */
class AndroidFixtureMaskedGenerationBackend : AndroidMaskedGenerationBackend {
    override val backendId = BACKEND_ID
    override val available = true

    override fun generate(
        source: Bitmap,
        maskValues: ByteArray,
        change: LocalGenerativeChange,
        certificateFingerprint: String,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): AndroidMaskedGenerationResult {
        require(BuildConfig.DEBUG && BuildConfig.LOCAL_GENERATED_PREVIEW)
        require(!source.isRecycled)
        require(maskValues.size == source.width * source.height)
        require(Regex("[0-9a-f]{64}").matches(certificateFingerprint))
        progress("正在生成局部fixture预览…")
        val sourcePixels = IntArray(maskValues.size)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        val outputPixels = renderArgb(
            source.width,
            source.height,
            sourcePixels,
            maskValues,
            change.generationPrompt,
            cancelled
        )
        val bitmap = Bitmap.createBitmap(
            outputPixels,
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888
        )
        return AndroidMaskedGenerationResult(
            bitmap,
            backendId,
            evidenceFingerprint(
                source.width,
                source.height,
                sourcePixels,
                maskValues,
                outputPixels,
                change,
                certificateFingerprint
            )
        )
    }

    companion object {
        const val BACKEND_ID = "fixture_masked_generation_v1"

        internal fun renderArgb(
            width: Int,
            height: Int,
            source: IntArray,
            mask: ByteArray,
            prompt: String,
            cancelled: AtomicBoolean = AtomicBoolean(false)
        ): IntArray {
            require(width > 0 && height > 0 && source.size == width * height)
            require(mask.size == source.size && prompt.isNotBlank())
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(prompt.toByteArray(StandardCharsets.UTF_8))
            val first = intArrayOf(hash[0].u(), hash[1].u(), hash[2].u())
            val second = intArrayOf(hash[3].u(), hash[4].u(), hash[5].u())
            val output = source.copyOf()
            for (y in 0 until height) {
                check(!cancelled.get()) { "fixture generation cancelled" }
                for (x in 0 until width) {
                    val index = y * width + x
                    val alpha = mask[index].u()
                    if (alpha == 0) continue
                    val generated = if (((x / 12) + (y / 12)) and 1 == 0) first else second
                    val original = source[index]
                    fun blend(channel: Int, generatedChannel: Int): Int {
                        val value = original ushr channel and 0xff
                        return (value * (255 - alpha) + generatedChannel * alpha + 127) / 255
                    }
                    output[index] = (original and -0x1000000) or
                        (blend(16, generated[0]) shl 16) or
                        (blend(8, generated[1]) shl 8) or
                        blend(0, generated[2])
                }
            }
            return output
        }

        private fun evidenceFingerprint(
            width: Int,
            height: Int,
            source: IntArray,
            mask: ByteArray,
            output: IntArray,
            change: LocalGenerativeChange,
            certificateFingerprint: String
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun integer(value: Int) = digest.update(ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN).putInt(value).array())
            fun text(value: String) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                integer(bytes.size)
                digest.update(bytes)
            }
            integer(width)
            integer(height)
            source.forEach(::integer)
            digest.update(mask)
            output.forEach(::integer)
            text(change.generationPrompt)
            text(change.promptSourceText)
            text(certificateFingerprint)
            return digest.digest().joinToString("") { "%02x".format(it.u()) }
        }

        private fun Byte.u(): Int = toInt() and 0xff
    }
}
