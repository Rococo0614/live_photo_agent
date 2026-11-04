package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

data class AndroidHttpGenerationConfig(
    val endpoint: String,
    val modelId: String,
    val bearerToken: String?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int
) {
    init {
        require(endpointAllowed(endpoint))
        require(modelId.isNotBlank() && modelId.length <= 160)
        require(connectTimeoutMs in 1_000..60_000)
        require(readTimeoutMs in 1_000..600_000)
    }

    companion object {
        fun fromFile(file: File): AndroidHttpGenerationConfig? = runCatching {
            if (!file.isFile) return null
            val value = JSONObject(file.readText())
            require(value.keys().asSequence().toSet().all {
                it in setOf(
                    "endpoint", "model_id", "bearer_token",
                    "connect_timeout_ms", "read_timeout_ms"
                )
            })
            AndroidHttpGenerationConfig(
                endpoint = value.getString("endpoint"),
                modelId = value.getString("model_id"),
                bearerToken = value.optString("bearer_token").takeIf(String::isNotBlank),
                connectTimeoutMs = value.optInt("connect_timeout_ms", 15_000),
                readTimeoutMs = value.optInt("read_timeout_ms", 300_000)
            )
        }.getOrNull()

        internal fun endpointAllowed(endpoint: String): Boolean {
            val url = runCatching { URL(endpoint) }.getOrNull() ?: return false
            if (url.protocol == "https" && url.host.isNotBlank()) return true
            return url.protocol == "http" && url.host in setOf("127.0.0.1", "localhost", "::1")
        }
    }
}

/** Multipart inpaint adapter. The phone re-composites the response so mask-exterior pixels are exact. */
class AndroidHttpMaskedGenerationBackend private constructor(
    private val config: AndroidHttpGenerationConfig
) : AndroidMaskedGenerationBackend {
    override val backendId = BACKEND_ID
    override val available = true

    override fun generate(
        source: Bitmap,
        maskValues: ByteArray,
        change: LocalGenerativeChange,
        certificateFingerprint: String,
        cancelled: AtomicBoolean
    ): AndroidMaskedGenerationResult {
        require(BuildConfig.DEBUG && BuildConfig.LOCAL_GENERATED_PREVIEW)
        require(!source.isRecycled)
        require(maskValues.size == source.width * source.height)
        require(Regex("[0-9a-f]{64}").matches(certificateFingerprint))
        check(!cancelled.get()) { "HTTP generation cancelled before request" }

        val sourceBytes = encodePng(source)
        val maskBytes = encodeMaskPng(source.width, source.height, maskValues)
        val requestSha = requestSha256(
            source.width,
            source.height,
            sourceBytes,
            maskValues,
            change,
            certificateFingerprint,
            config.modelId
        )
        val seed = requestSha.take(16).toULong(16).toLong() and Long.MAX_VALUE
        val payload = multipart(
            requestSha,
            sourceBytes,
            maskBytes,
            change.generationPrompt,
            config.modelId,
            seed,
            certificateFingerprint
        )
        val response = executeCancellable(payload, cancelled)
        require(response.getInt("schema_version") == 1)
        require(response.getString("request_sha256") == requestSha)
        require(response.getInt("width") == source.width)
        require(response.getInt("height") == source.height)
        val remoteBytes = Base64.decode(response.getString("image_base64"), Base64.DEFAULT)
        require(remoteBytes.size in 1..MAX_RESPONSE_IMAGE_BYTES)
        require(sha256(remoteBytes) == response.getString("output_sha256"))
        val remote = requireNotNull(BitmapFactory.decodeByteArray(remoteBytes, 0, remoteBytes.size)) {
            "generation response is not a decodable image"
        }
        try {
            require(remote.width == source.width && remote.height == source.height)
            val sourcePixels = IntArray(maskValues.size)
            val remotePixels = IntArray(maskValues.size)
            source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
            remote.getPixels(remotePixels, 0, remote.width, 0, 0, remote.width, remote.height)
            val outputPixels = compositeInsideMask(sourcePixels, remotePixels, maskValues)
            val output = Bitmap.createBitmap(
                outputPixels,
                source.width,
                source.height,
                Bitmap.Config.ARGB_8888
            )
            val remoteId = response.optString("backend_id", "remote").replace(
                Regex("[^A-Za-z0-9._-]"), "_"
            ).take(80)
            return AndroidMaskedGenerationResult(
                output,
                "$BACKEND_ID/$remoteId",
                responseEvidenceFingerprint(
                    requestSha,
                    response.getString("output_sha256"),
                    outputPixels,
                    certificateFingerprint
                )
            )
        } finally {
            remote.recycle()
        }
    }

    private fun executeCancellable(payload: MultipartPayload, cancelled: AtomicBoolean): JSONObject {
        val connection = AtomicReference<HttpURLConnection?>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "masked-generation-http").apply { isDaemon = true }
        }
        val future = executor.submit<JSONObject> {
            val http = URL(config.endpoint).openConnection() as HttpURLConnection
            connection.set(http)
            try {
                http.requestMethod = "POST"
                http.doOutput = true
                http.connectTimeout = config.connectTimeoutMs
                http.readTimeout = config.readTimeoutMs
                http.setRequestProperty("Content-Type", "multipart/form-data; boundary=${payload.boundary}")
                http.setRequestProperty("Accept", "application/json")
                config.bearerToken?.let { http.setRequestProperty("Authorization", "Bearer $it") }
                http.setFixedLengthStreamingMode(payload.bytes.size)
                http.outputStream.use { it.write(payload.bytes) }
                val status = http.responseCode
                val stream = if (status in 200..299) http.inputStream else http.errorStream
                val bytes = stream?.use { readBounded(it, MAX_RESPONSE_JSON_BYTES) } ?: ByteArray(0)
                require(status in 200..299) {
                    "generation server HTTP $status: ${bytes.toString(Charsets.UTF_8).take(500)}"
                }
                JSONObject(bytes.toString(Charsets.UTF_8))
            } finally {
                connection.set(null)
                http.disconnect()
            }
        }
        try {
            while (true) {
                if (cancelled.get()) {
                    connection.get()?.disconnect()
                    future.cancel(true)
                    throw InterruptedException("HTTP generation cancelled")
                }
                try {
                    return future.get(100, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Poll only the app-owned cancellation token while the socket is blocking.
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private data class MultipartPayload(val boundary: String, val bytes: ByteArray)

    private fun multipart(
        requestSha: String,
        sourcePng: ByteArray,
        maskPng: ByteArray,
        prompt: String,
        modelId: String,
        seed: Long,
        certificateFingerprint: String
    ): MultipartPayload {
        val boundary = "lpa-${requestSha.take(24)}"
        val output = ByteArrayOutputStream(sourcePng.size + maskPng.size + 2048)
        fun line(value: String) = output.write((value + "\r\n").toByteArray(StandardCharsets.UTF_8))
        fun field(name: String, value: String) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line("")
            line(value)
        }
        fun file(name: String, filename: String, bytes: ByteArray) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"")
            line("Content-Type: image/png")
            line("")
            output.write(bytes)
            line("")
        }
        field("schema_version", "1")
        field("request_sha256", requestSha)
        field("prompt", prompt)
        field("model_id", modelId)
        field("seed", seed.toString())
        field("certificate_fingerprint", certificateFingerprint)
        file("source", "source.png", sourcePng)
        file("mask", "mask.png", maskPng)
        line("--$boundary--")
        return MultipartPayload(boundary, output.toByteArray())
    }

    companion object {
        const val BACKEND_ID = "http_masked_generation_v1"
        private const val MAX_RESPONSE_JSON_BYTES = 32 * 1024 * 1024
        private const val MAX_RESPONSE_IMAGE_BYTES = 24 * 1024 * 1024

        fun fromPrivateConfig(context: Context): AndroidHttpMaskedGenerationBackend? {
            val file = File(context.filesDir, "models/local-generation-http/config.json")
            val config = AndroidHttpGenerationConfig.fromFile(file)
            Log.i(
                "LPA_GENERATION",
                "HTTP backend config present=${file.isFile} accepted=${config != null}"
            )
            return config?.let(::AndroidHttpMaskedGenerationBackend)
        }

        internal fun compositeInsideMask(
            source: IntArray,
            generated: IntArray,
            mask: ByteArray
        ): IntArray {
            require(source.size == generated.size && source.size == mask.size)
            return IntArray(source.size) { index ->
                val alpha = mask[index].toInt() and 0xff
                if (alpha == 0) return@IntArray source[index]
                val original = source[index]
                val replacement = generated[index]
                fun blend(shift: Int): Int {
                    val left = original ushr shift and 0xff
                    val right = replacement ushr shift and 0xff
                    return (left * (255 - alpha) + right * alpha + 127) / 255
                }
                (original and -0x1000000) or
                    (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
            }
        }

        private fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }

        private fun encodeMaskPng(width: Int, height: Int, mask: ByteArray): ByteArray {
            val pixels = IntArray(mask.size) { index ->
                val value = mask[index].toInt() and 0xff
                0xff000000.toInt() or (value shl 16) or (value shl 8) or value
            }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            return try {
                encodePng(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        private fun requestSha256(
            width: Int,
            height: Int,
            sourcePng: ByteArray,
            mask: ByteArray,
            change: LocalGenerativeChange,
            certificateFingerprint: String,
            modelId: String
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(width).putInt(height).array())
            digest.update(sourcePng)
            digest.update(mask)
            listOf(
                change.generationPrompt,
                change.promptSourceText,
                certificateFingerprint,
                modelId
            ).forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().hex()
        }

        private fun responseEvidenceFingerprint(
            requestSha: String,
            remoteOutputSha: String,
            outputPixels: IntArray,
            certificateFingerprint: String
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(requestSha, remoteOutputSha, certificateFingerprint).forEach {
                digest.update(it.toByteArray(StandardCharsets.US_ASCII))
            }
            val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            outputPixels.forEach { pixel ->
                buffer.clear()
                buffer.putInt(pixel)
                digest.update(buffer.array())
            }
            return digest.digest().hex()
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).hex()

        private fun readBounded(input: java.io.InputStream, maximumBytes: Int): ByteArray {
            val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximumBytes) { "generation response is too large" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
