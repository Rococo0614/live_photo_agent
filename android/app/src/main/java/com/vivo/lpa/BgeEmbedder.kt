package com.vivo.lpa

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/** CPU ONNX BGE encoder matching the web project's 512-dim search embeddings. */
class BgeEmbedder(context: Context) {
    private val root = File(context.getExternalFilesDir("models"), "bge")
    private val model = File(root, "bge_small_zh_v1_5.onnx")
    private val tokenizer = File(root, "vocab.txt")
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val session by lazy {
        environment.createSession(model.absolutePath, OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        })
    }
    private val encoder by lazy { BertTokenizer(tokenizer) }

    fun available(): Boolean = model.isFile && tokenizer.isFile

    fun embed(text: String): FloatArray {
        check(available()) { "BGE model is not deployed" }
        val tokens = encoder.encode(text)
        val ids = OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens.ids), longArrayOf(1, tokens.ids.size.toLong()))
        val mask = OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens.mask), longArrayOf(1, tokens.mask.size.toLong()))
        val types = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(tokens.ids.size)), longArrayOf(1, tokens.ids.size.toLong()))
        ids.use { inputIds -> mask.use { attention -> types.use { tokenTypes ->
            session.run(mapOf("input_ids" to inputIds, "attention_mask" to attention, "token_type_ids" to tokenTypes)).use { result ->
                val raw = result[0].value as Array<FloatArray>
                val vector = raw[0]
                var norm = 0.0
                vector.forEach { norm += it * it }
                val scale = sqrt(norm).toFloat().coerceAtLeast(1e-8f)
                return FloatArray(vector.size) { index -> vector[index] / scale }
            }
        } } }
    }
}
