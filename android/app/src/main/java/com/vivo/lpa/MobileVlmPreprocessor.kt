package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Boundary for the image/video semantic preprocessing required by the Python
 * project. The current repository does not contain a QNN VLM graph, so this
 * class never pretends that technical metadata is VLM output.
 *
 * Runs the packaged Qualcomm Genie image pipeline on sampled 512x512 frames.
 */
class MobileVlmPreprocessor(private val context: Context) {
    data class Result(val payload: JSONObject, val ready: Boolean, val message: String)

    fun analyze(asset: PreparedAsset, frameFiles: List<File>): Result {
        val deployedRoot = ModelPack.vlmRoot(context)
        val manifest = File(deployedRoot, "manifest.json")
        val config = File(deployedRoot, "genie_config.json")
        val script = File(deployedRoot, "genie-app-script.txt")
        if (!manifest.isFile && !config.isFile) {
            return Result(
                payload = unavailablePayload(asset, "vlm_model_pack_missing"),
                ready = false,
                message = "VLM 模型包未部署：${config.absolutePath}"
            )
        }

        val modelRoot = ModelPack.executionRoot(context, deployedRoot)
        val runner = ModelPack.genieRunner(context, modelRoot)
        if (runner == null) {
            return Result(
                payload = unavailablePayload(asset, "vlm_manifest_incomplete"),
                ready = false,
                message = "VLM bundle 已存在，但缺少 genie-app runner"
            )
        }

        if (!script.isFile && !manifest.isFile) {
            return Result(
                payload = unavailablePayload(asset, "vlm_script_missing"),
                ready = false,
                message = "VLM bundle 缺少 genie-app-script.txt 或 manifest"
            )
        }
        if (frameFiles.isEmpty()) {
            return Result(
                payload = unavailablePayload(asset, "vlm_frames_missing"),
                ready = false,
                message = "VLM 没有可输入的关键帧"
            )
        }

        val work = File(context.cacheDir, "vlm-${System.nanoTime()}").apply { mkdirs() }
        return try {
            val summaries = frameFiles.take(3).mapIndexedNotNull { index, frame ->
                runFrame(modelRoot, deployedRoot, runner, frame, work, index)
            }
            if (summaries.isEmpty()) {
                Result(
                    payload = unavailablePayload(asset, "vlm_inference_failed"),
                    ready = false,
                    message = "VLM runner 已启动，但没有返回有效描述"
                )
            } else {
                val structured = summaries.mapNotNull(::extractSemanticJson)
                val summary = structured.mapNotNull { it.optString("summary").takeIf(String::isNotBlank) }
                    .ifEmpty { summaries }
                    .joinToString("\n")
                Result(
                    payload = JSONObject()
                        .put("producer", "qwen3-vl-4b")
                        .put("model", "Qwen3-VL-4B-Instruct-W4A16")
                        .put("source", "qnn_genie")
                        .put("status", "ready")
                        .put("summary", summary)
                        .put("theme", structured.firstOrNull()?.optString("theme", summary) ?: summary)
                        .put("scene_tags", mergeTags(structured, "scene_tags"))
                        .put("subject_tags", mergeTags(structured, "subject_tags"))
                        .put("motion_tags", mergeTags(structured, "motion_tags"))
                        .put("audio_tags", mergeTags(structured, "audio_tags"))
                        .put("search_keywords", mergeTags(structured, "search_keywords"))
                        .put("quality_score", structured.mapNotNull {
                            it.optDouble("quality_score", -1.0).takeIf { score -> score >= 0 }
                        }.let { scores -> if (scores.isEmpty()) JSONObject.NULL else scores.average() })
                        .put("frames_analyzed", summaries.size),
                    ready = true,
                    message = "VLM 已完成 ${summaries.size} 个关键帧分析"
                )
            }
        } catch (error: Exception) {
            Log.e("MobileVlm", "VLM inference failed", error)
            Result(
                payload = unavailablePayload(asset, "vlm_inference_failed"),
                ready = false,
                message = "VLM 执行失败：${error.message ?: error::class.java.simpleName}"
            )
        } finally {
            work.deleteRecursively()
        }
    }

    private fun extractSemanticJson(raw: String): JSONObject? = runCatching {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) null else JSONObject(raw.substring(start, end + 1))
    }.getOrNull()

    private fun mergeTags(items: List<JSONObject>, key: String): JSONArray {
        val values = linkedSetOf<String>()
        items.forEach { item ->
            val array = item.optJSONArray(key) ?: return@forEach
            for (index in 0 until array.length()) values += array.optString(index)
        }
        return JSONArray(values.toList())
    }

    private fun runFrame(
        modelRoot: File,
        deployedRoot: File,
        runner: File,
        frame: File,
        work: File,
        index: Int
    ): String? {
        val frameDir = File(work, "frame-$index").apply { mkdirs() }
        val pixelValues = File(frameDir, "pixel_values.raw")
        writePixelValues(frame, pixelValues)
        val sample = File(modelRoot, "sample_inputs")
        val prefix = File(sample, "prompt_prefix.txt")
        val suffix = File(frameDir, "prompt_suffix.txt")
        suffix.writeText(
            "<|vision_end|>Return JSON only. Use Chinese and English search tags. " +
                "Schema: {\"summary\":\"...\",\"theme\":\"...\",\"scene_tags\":[\"...\"]," +
                "\"subject_tags\":[\"...\"],\"motion_tags\":[\"...\"]," +
                "\"audio_tags\":[\"...\"],\"search_keywords\":[\"...\"]," +
                "\"quality_score\":0.0}.<|im_end|>\n<|im_start|>assistant\n"
        )
        val script = File(frameDir, "run.txt")
        script.writeText(
            "pipeline config create pipelineConfig\n" +
                "pipeline create GeniePipeline pipelineConfig\n" +
                "node config create imageEncoderConfig ${File(modelRoot, "img-enc-htp.json").absolutePath}\n" +
                "node create imageEncoder imageEncoderConfig\n" +
                "node config create lutEncoderConfig ${File(modelRoot, "text-encoder.json").absolutePath}\n" +
                "node create lutEncoder lutEncoderConfig\n" +
                "node config create textGeneratorConfig ${File(modelRoot, "text-generator.json").absolutePath}\n" +
                "node create textGenerator textGeneratorConfig\n" +
                "node set textCallback textGenerator GENIE_NODE_TEXT_GENERATOR_TEXT_OUTPUT\n" +
                "pipeline add GeniePipeline imageEncoder\n" +
                "pipeline add GeniePipeline lutEncoder\n" +
                "pipeline add GeniePipeline textGenerator\n" +
                 "pipeline connect GeniePipeline imageEncoder GENIE_NODE_IMAGE_ENCODER_EMBEDDING_OUTPUT textGenerator GENIE_NODE_TEXT_GENERATOR_EMBEDDING_INPUT\n" +
                 "pipeline connect GeniePipeline lutEncoder GENIE_NODE_TEXT_ENCODER_EMBEDDING_OUTPUT textGenerator GENIE_NODE_TEXT_GENERATOR_EMBEDDING_INPUT\n" +
                 "node set textFile lutEncoder GENIE_NODE_TEXT_ENCODER_TEXT_INPUT ${prefix.absolutePath}\n" +
                "node set image imageEncoder GENIE_NODE_IMAGE_ENCODER_IMAGE_INPUT ${pixelValues.absolutePath}\n" +
                "node set embedding imageEncoder GENIE_NODE_IMAGE_ENCODER_IMAGE_POS_COS ${File(sample, "position_ids_cos.raw").absolutePath}\n" +
                "node set embedding imageEncoder GENIE_NODE_IMAGE_ENCODER_IMAGE_POS_SIN ${File(sample, "position_ids_sin.raw").absolutePath}\n" +
                "node set embedding imageEncoder GENIE_NODE_IMAGE_ENCODER_IMAGE_WINDOW_ATTN_MASK ${File(sample, "window_attention_mask.raw").absolutePath}\n" +
                "node set embedding imageEncoder GENIE_NODE_IMAGE_ENCODER_IMAGE_FULL_ATTN_MASK ${File(sample, "full_attention_mask.raw").absolutePath}\n" +
                "node set textFile lutEncoder GENIE_NODE_TEXT_ENCODER_TEXT_INPUT ${suffix.absolutePath}\n" +
                "pipeline execute GeniePipeline\n" +
                "node free imageEncoder\nnode free lutEncoder\nnode free textGenerator\npipeline free GeniePipeline\n"
        )
        val process = ProcessBuilder(runner.absolutePath, "-s", script.absolutePath)
            .directory(modelRoot)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] =
                    ModelPack.runtimeLibRoot(context, modelRoot).absolutePath + ":/vendor/lib64:/vendor/lib64/hw"
                environment()["ADSP_LIBRARY_PATH"] =
                    File(modelRoot, "runtime/dsp").absolutePath + ";/vendor/dsp/cdsp"
            }
            .start()
        val output = StringBuilder()
        val outputThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) { output.append(line).append('\n') }
                }
            }
        }.apply { start() }
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outputThread.join(5000)
            Log.e("MobileVlm", "genie-app timeout")
            return null
        }
        outputThread.join(5000)
        val text = output.toString()
        Log.i("MobileVlm", "genie-app exit=${process.exitValue()} output=${text.takeLast(4000)}")
        return Regex("(?s)\\[BEGIN\\]:\\s*(.*?)\\[END\\]")
            .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun writePixelValues(source: File, destination: File) {
        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
            ?: throw IllegalArgumentException("无法解码 VLM 帧：${source.name}")
        val scaled = Bitmap.createScaledBitmap(bitmap, 512, 512, true)
        if (scaled !== bitmap) bitmap.recycle()
        val values = ByteBuffer.allocate(1024 * 1536 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (blockY in 0 until 16) {
            for (blockX in 0 until 16) {
                for (mergeY in 0 until 2) {
                    for (mergeX in 0 until 2) {
                        val patchX = (blockX * 2 + mergeX) * 16
                        val patchY = (blockY * 2 + mergeY) * 16
                        for (channel in 0 until 3) {
                            for (time in 0 until 2) {
                                for (y in 0 until 16) {
                                    for (x in 0 until 16) {
                                        val pixel = scaled.getPixel(patchX + x, patchY + y)
                                        val value = when (channel) {
                                            0 -> (pixel shr 16) and 0xff
                                            1 -> (pixel shr 8) and 0xff
                                            else -> pixel and 0xff
                                        }
                                        values.putFloat(value / 127.5f - 1.0f)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        FileOutputStream(destination).use { it.write(values.array()) }
        scaled.recycle()
    }

    private fun unavailablePayload(asset: PreparedAsset, reason: String): JSONObject = JSONObject()
        .put("producer", "none")
        .put("model", "none")
        .put("source", "technical_preprocess_only")
        .put("status", "unavailable")
        .put("reason", reason)
        .put("summary", asset.sourceFile.name)
        .put("theme", "")
        .put("scene_tags", JSONArray())
        .put("subject_tags", JSONArray())
        .put("motion_tags", JSONArray())
        .put("audio_tags", JSONArray())
        .put("search_keywords", JSONArray())
}
