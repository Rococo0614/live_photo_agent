package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * Duty-cycle control for the NPU, owned here because runGrid is the single choke point
     * where genie-app is spawned. A guard held by a caller instead would count assets rather
     * than inferences, and one asset can spend up to four inferences on seed retries.
     */
    private val thermalGuard = ThermalGuard()

    init {
        // Reported once, because a guard that discovers no sensor fails silently:
        // npuCelsius() returns null, every throttle check passes, and indexing runs
        // unguarded until the vendor daemon kills it. An untrusted_app domain may be
        // denied sysfs_thermal even where adb shell could read it, so confirm on device.
        Log.i("MobileVlm", "thermal guard armed: ${thermalGuard.sensorCount} sensor(s), " +
            "npu=${thermalGuard.npuCelsius()}C")
    }

    companion object {
        /**
         * Bump this whenever the frame sampling or prompt changes. LibraryDatabase.isFresh()
         * requires an exact match, so stale records are re-analyzed automatically instead of
         * being skipped forever.
         */
        const val ANALYSIS_VERSION = "grid-v5"

        /**
         * Cells of the 2x2 grid: top-left, top-right, bottom-left, bottom-right.
         *
         * Public because the frame sampler upstream must produce exactly this many frames.
         * A smaller count leaves a cell blank, which is what the filled grid exists to avoid.
         */
        const val MAX_GRID_FRAMES = 4

        /**
         * The filler shown in the schema example, and the literal the model sometimes
         * echoes back instead of filling the slot.
         *
         * Defined once on purpose: the prompt and the validity check must agree on what
         * counts as an unfilled placeholder. If they drift, leaked output slips through.
         */
        private const val SCHEMA_PLACEHOLDER = "..."

        /**
         * Floor for the combined retrieval text (summary + theme + every tag).
         *
         * Judged on the concatenation, not on summary length: a Chinese summary can be
         * semantically complete in three characters ("猫静止"), so any per-field character
         * threshold misreads CJK text and rejects good parses. What actually matters is
         * whether the row contributes anything to the BGE vector, and a bare "无" with no
         * tags contributes nothing.
         */
        private const val MIN_RETRIEVAL_TEXT_LENGTH = 4

        /**
         * Ceiling for summary. The prompt asks for one sentence; observed good output runs
         * 7-60 characters. A repetition loop produces thousands, so anything past this is a
         * runaway generation rather than a description.
         */
        private const val MAX_SUMMARY_LENGTH = 200

        /**
         * Below this a summary is too thin to accept on its own, but it is only treated as
         * degenerate when no tags came back either -- "猫静止" is three characters and a
         * perfectly good answer for a static scene.
         */
        private const val MIN_SUMMARY_LENGTH_FOR_RETRY = 4

        /**
         * NPU performance profile used for indexing.
         *
         * Measured on SM8850, one VLM inference from a 44C baseline:
         *   burst        8s, peak 66C (+22C)
         *   balanced    12s, peak 54C (+10C)
         *   power_saver 15s, peak 52C (+8C)
         *
         * Output was byte-identical across all three, which is expected: the profile sets
         * the clock, not the arithmetic. So this trades wall clock for heat and not for
         * quality. balanced halves the heat per inference for 50% more time; power_saver's
         * further 2C does not justify another 25% slowdown.
         *
         * This matters because the vendor thermal daemon force-stops the app once the
         * Hexagon sensors sustain ~99.7C, and with burst a full 102-asset pass cannot
         * finish before that happens.
         */
        private const val NPU_PERF_PROFILE = "balanced"

        /**
         * Generation cap, in tokens. The shipped model pack sets no limit at all.
         *
         * Without one, an image that lands the model in a repetition attractor keeps
         * emitting until the 240s process timeout, which is what made indexing stall for
         * four minutes on a single asset. Measured on real output: a 348-character JSON
         * answer needs 65-96 tokens, and the longest valid parse in the library is 453
         * characters, so 256 leaves roughly 2x headroom while capping a runaway at about
         * 25 seconds instead of 240.
         *
         * A capped runaway still fails validation and is recorded as vlm_unparseable, so
         * this bounds the cost of the failure without hiding it.
         */
        private const val MAX_GENERATED_TOKENS = 256

        /**
         * Alternate sampler seeds tried when the pinned seed lands in a repetition attractor.
         * Measured on a known-failing asset: 7 and 999 escaped, 2026 and 12345 did not, so
         * the list mixes values observed to work with fresh ones rather than assuming any
         * particular seed is universally safe.
         */
        private val RETRY_SEEDS = listOf(7, 999, 20260101)

        /**
         * Failure reasons that will not heal on their own.
         *
         * The sampler ships with a fixed seed, so the same frames plus the same prompt
         * reproduce the same output byte for byte. Retrying a placeholder echo therefore
         * cannot succeed -- it just burns another inference on every launch. Transient
         * reasons (DSP timeout, a model pack that was missing but has since been deployed)
         * are deliberately absent from this set so they keep being retried.
         */
        private val PERMANENT_FAILURES = setOf(
            "vlm_placeholder_echo",
            "vlm_unparseable",
            "vlm_runaway_output",
            "vlm_no_signal"
        )

        /** Must stay 512 so the 32x32 patch grid matches the shipped RoPE ids. */
        private const val GRID_CANVAS_SIZE = 512
    }

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
            val frames = frameFiles.take(MAX_GRID_FRAMES)
            val stitched = stitchFrames(frames)
            val raw = try {
                val frameDir = File(work, "grid").apply { mkdirs() }
                val pixelValues = File(frameDir, "pixel_values.raw")
                writePixelValues(stitched, pixelValues)
                runGridWithRetries(modelRoot, runner, pixelValues, frameDir, frames.size)
            } finally {
                stitched.recycle()
            }
            if (raw.isNullOrBlank()) {
                Result(
                    payload = unavailablePayload(asset, "vlm_inference_failed"),
                    ready = false,
                    message = "VLM runner 已启动，但没有返回有效描述"
                )
            } else {
                val structured = extractSemanticJson(raw)
                // No fallback to `raw`. When the JSON is unparseable the raw text is a
                // truncated fragment, and using it as the summary stores source code as if
                // it were a description -- which then becomes the BGE retrieval vector.
                val failure = when {
                    structured == null -> "vlm_unparseable"
                    !isFilledIn(structured.optString("summary")) -> "vlm_placeholder_echo"
                    structured.optString("summary").trim().length > MAX_SUMMARY_LENGTH ->
                        "vlm_runaway_output"
                    else -> null
                }
                if (failure != null) {
                    Result(
                        payload = unavailablePayload(asset, failure, raw),
                        ready = false,
                        message = "VLM 未产出有效描述（$failure，详见 raw_output_excerpt）"
                    )
                } else {
                    val summary = structured!!.optString("summary").trim()
                    val theme = structured.optString("theme")
                        .takeIf { isFilledIn(it) } ?: summary
                    val sceneTags = tagArray(structured, "scene_tags")
                    val subjectTags = tagArray(structured, "subject_tags")
                    val motionTags = tagArray(structured, "motion_tags")
                    val keywords = tagArray(structured, "search_keywords")

                    // Judge the row by what it actually contributes to retrieval, not by
                    // the length of any single field. A three-character Chinese summary
                    // like "猫静止" is complete; "无" with no tags carries nothing.
                    val retrievalText = buildString {
                        append(summary); append(' '); append(theme); append(' ')
                        listOf(sceneTags, subjectTags, motionTags, keywords).forEach { tags ->
                            for (index in 0 until tags.length()) {
                                append(tags.optString(index)); append(' ')
                            }
                        }
                    }.trim()

                    if (retrievalText.length < MIN_RETRIEVAL_TEXT_LENGTH ||
                        retrievalText.all { it == '无' || it.isWhitespace() }
                    ) {
                        Result(
                            payload = unavailablePayload(asset, "vlm_no_signal", raw),
                            ready = false,
                            message = "VLM 未从该素材中提取到可检索信息"
                        )
                    } else Result(
                        payload = JSONObject()
                            .put("producer", "qwen3-vl-4b")
                            .put("model", "Qwen3-VL-4B-Instruct-W4A16")
                            .put("source", "qnn_genie")
                            .put("status", "ready")
                            .put("summary", summary)
                            .put("theme", theme)
                            .put("scene_tags", sceneTags)
                            .put("subject_tags", subjectTags)
                            .put("motion_tags", motionTags)
                            .put("search_keywords", keywords)
                            .put("frames_analyzed", frames.size)
                            .put("temporal_grid", frames.size > 1)
                            .put("analysis_version", ANALYSIS_VERSION),
                        ready = true,
                        message = if (frames.size > 1) {
                            "VLM 已完成 ${frames.size} 帧时序拼图分析"
                        } else {
                            "VLM 已完成单帧分析"
                        }
                    )
                }
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

    /**
     * 2x2 temporal grid packed into a single 512x512 canvas.
     *
     * The deployed QNN vision graph has a fixed [1024, 1536] pixel_values input, i.e. a
     * 32x32 patch grid, and ships precomputed RoPE position ids plus attention masks for
     * exactly that grid. The canvas therefore has to stay 512x512 with 16px patches so the
     * positional encoding stays aligned. Reading order is top-left, top-right, bottom-left.
     */
    private fun stitchFrames(frames: List<File>): Bitmap {
        val canvasSize = GRID_CANVAS_SIZE
        val out = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        if (frames.size == 1) {
            val only = BitmapFactory.decodeFile(frames[0].absolutePath)
                ?: throw IllegalArgumentException("无法解码 VLM 帧：${frames[0].name}")
            val rect = android.graphics.RectF(0f, 0f, canvasSize.toFloat(), canvasSize.toFloat())
            canvas.drawBitmap(only, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            only.recycle()
            return out
        }
        val cell = canvasSize / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.forEachIndexed { index, frame ->
            val decoded = BitmapFactory.decodeFile(frame.absolutePath) ?: return@forEachIndexed
            val left = (index % 2) * cell
            val top = (index / 2) * cell
            canvas.drawBitmap(
                decoded,
                null,
                android.graphics.RectF(left.toFloat(), top.toFloat(), (left + cell).toFloat(), (top + cell).toFloat()),
                paint
            )
            decoded.recycle()
        }
        return out
    }

    /**
     * True when a field carries real content rather than the schema example.
     *
     * This checks schema compliance only -- "did the requested slot get filled" -- not
     * whether the description is good. A quantized 4B model sampling at temp 0.8
     * occasionally reproduces the example verbatim, and that output is syntactically
     * valid JSON, so nothing downstream would otherwise notice.
     */
    /**
     * True when a field carries real content rather than the schema example.
     *
     * Checks schema compliance only -- "did the requested slot get filled" -- never
     * whether the description is good or long enough. Length is a poor proxy for meaning
     * in CJK text; see MIN_RETRIEVAL_TEXT_LENGTH for the signal check.
     */
    private fun isFilledIn(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        return trimmed.isNotEmpty() && trimmed != SCHEMA_PLACEHOLDER
    }

    private fun extractSemanticJson(raw: String): JSONObject? = runCatching {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) null else JSONObject(raw.substring(start, end + 1))
    }.getOrNull()

    /**
     * Reads a tag field, accepting a bare string where an array was requested.
     *
     * The model sometimes emits "scene_tags": "猫静止" instead of ["猫静止"]. Reading it
     * with optJSONArray alone returns null and the value is silently discarded, which is
     * how a usable parse ends up looking empty.
     */
    private fun tagArray(source: JSONObject?, key: String): JSONArray {
        if (source == null) return JSONArray()
        val values = linkedSetOf<String>()
        fun add(candidate: String?) {
            candidate?.trim()
                ?.takeIf { it.isNotBlank() && it != SCHEMA_PLACEHOLDER }
                ?.let(values::add)
        }
        source.optJSONArray(key)?.let { array ->
            for (index in 0 until array.length()) add(array.optString(index))
        } ?: add(source.optString(key).takeIf { source.has(key) && it.isNotBlank() })
        return JSONArray(values.toList())
    }

    /**
     * Runs the grid inference, retrying with alternate sampler seeds on a degenerate parse.
     *
     * The shipped config pins `seed: 42`, which makes generation deterministic: the same
     * frames plus the same prompt reproduce the same output byte for byte. Some images land
     * in a repetition attractor at that seed and echo the schema placeholder instead of
     * describing the frame. Retrying the same seed is therefore pointless, but a different
     * seed takes a different sampling path and usually escapes.
     *
     * Measured on a known-failing asset (spring_376279, placeholder echo at seed 42):
     * seeds 7 and 999 both produced correct descriptions, while 2026 and 12345 failed --
     * roughly 40% success per alternate seed, so three attempts recover most failures.
     *
     * Only degenerate output triggers a retry. A parse that yields real content is accepted
     * even if terse, and genuine inference failures (timeout, DSP error) are not retried
     * here because they are environmental rather than sampling artefacts.
     */
    private fun runGridWithRetries(
        modelRoot: File,
        runner: File,
        pixelValues: File,
        frameDir: File,
        frameCount: Int
    ): String? {
        // First pass uses the model pack's own config, so assets that already parse are
        // unaffected by this change.
        var output = runGrid(modelRoot, runner, pixelValues, frameDir, frameCount, seedOverride = null)
        if (!isDegenerate(output)) return output
        for (seed in RETRY_SEEDS) {
            Log.i("MobileVlm", "degenerate output at previous seed, retrying with seed=$seed")
            output = runGrid(modelRoot, runner, pixelValues, frameDir, frameCount, seedOverride = seed)
            if (!isDegenerate(output)) return output
        }
        return output
    }

    /**
     * True when the model produced nothing usable: no output at all, or output that carries
     * no content beyond the schema example.
     */
    private fun isDegenerate(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val structured = extractSemanticJson(raw) ?: return true
        val summary = structured.optString("summary").trim()
        if (!isFilledIn(summary)) return true
        // A repetition loop can still emit well-formed JSON, so the runaway check has to
        // live here too. Without it such an output skips the retry and is written off as
        // vlm_runaway_output on the first attempt, even though another seed would escape.
        if (summary.length > MAX_SUMMARY_LENGTH) return true
        val tags = listOf("scene_tags", "subject_tags", "motion_tags", "search_keywords")
            .sumOf { tagArray(structured, it).length() }
        return summary.length < MIN_SUMMARY_LENGTH_FOR_RETRY && tags == 0
    }

    private fun runGrid(
        modelRoot: File,
        runner: File,
        pixelValues: File,
        frameDir: File,
        frameCount: Int,
        seedOverride: Int? = null
    ): String? {
        val sample = File(modelRoot, "sample_inputs")
        val prefix = File(sample, "prompt_prefix.txt")
        val suffix = File(frameDir, "prompt_suffix.txt")
        suffix.writeText(gridPrompt(frameCount))
        // Both node configs are materialized into frameDir so the NPU runs at the indexing
        // clock and, on retry, under an alternate sampler seed. The model pack itself is
        // never mutated.
        val configs = materializeConfigs(modelRoot, frameDir, seedOverride)
        val script = File(frameDir, "run.txt")
        script.writeText(
            "pipeline config create pipelineConfig\n" +
                "pipeline create GeniePipeline pipelineConfig\n" +
                "node config create imageEncoderConfig ${configs.imageEncoder.absolutePath}\n" +
                "node create imageEncoder imageEncoderConfig\n" +
                "node config create lutEncoderConfig ${File(modelRoot, "text-encoder.json").absolutePath}\n" +
                "node create lutEncoder lutEncoderConfig\n" +
                "node config create textGeneratorConfig ${configs.generator.absolutePath}\n" +
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
        // Checked between inferences, never during one: generation cannot be interrupted
        // mid-flight, so this is the last point where resting is still possible.
        if (thermalGuard.recordInference() || thermalGuard.shouldThrottle()) {
            thermalGuard.awaitCooldown { celsius, waitedSeconds ->
                Log.i("MobileVlm", "NPU ${celsius.toInt()}C, cooling for ${waitedSeconds}s")
            }
        }
        // CALIBRATION INSTRUMENTATION: sample the hottest NPU sensor throughout the
        // inference, not just before and after. The thermal kill fires on the peak reached
        // mid-generation, and a before/after pair would understate that peak whenever the
        // sensors cool again before the process exits. Remove once the pause threshold has
        // been derived from measured per-inference rise.
        val tempBefore = thermalGuard.npuCelsius()
        // Peak tracked in millidegrees as an Int. The sysfs reading is already in
        // millidegrees, so this keeps the comparison exact and sidesteps both nullable
        // generics and @Volatile, which cannot annotate a local variable.
        val sampling = AtomicBoolean(true)
        val peakMilli = AtomicInteger((tempBefore ?: 0f).times(1000).toInt())
        val sampler = Thread {
            while (sampling.get()) {
                val current = thermalGuard.npuCelsius()
                if (current != null) {
                    peakMilli.accumulateAndGet(current.times(1000).toInt()) { old, new ->
                        maxOf(old, new)
                    }
                }
                try {
                    Thread.sleep(500)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; name = "npu-peak-sampler"; start() }

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
            // Draining stdout has to tolerate the stream being closed underneath it. On a
            // timeout the main thread calls destroyForcibly(), which makes this blocked
            // readLine throw InterruptedIOException; uncaught, that takes down the whole
            // process rather than just abandoning one inference.
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) { output.append(line).append('\n') }
                    }
                }
            }.onFailure { error ->
                Log.w("MobileVlm", "genie-app output stream closed early: ${error.javaClass.simpleName}")
            }
        }.apply { start() }
        val startedAt = System.currentTimeMillis()
        val timedOut = !process.waitFor(240, TimeUnit.SECONDS)
        val elapsedMs = System.currentTimeMillis() - startedAt
        sampling.set(false)
        sampler.interrupt()
        sampler.join(2000)
        val tempPeak = peakMilli.get() / 1000f
        val tempAfter = thermalGuard.npuCelsius()
        Log.i(
            "MobileVlmThermal",
            "inference ${elapsedMs}ms timedOut=$timedOut " +
                "before=${tempBefore?.let { "${it.toInt()}C" } ?: "n/a"} " +
                "peak=${tempPeak.toInt()}C " +
                "after=${tempAfter?.let { "${it.toInt()}C" } ?: "n/a"} " +
                "rise=${if (tempBefore != null) "${(tempPeak - tempBefore).toInt()}C" else "n/a"}"
        )
        if (timedOut) {
            process.destroyForcibly()
            outputThread.join(5000)
            Log.e("MobileVlm", "genie-app timeout")
            return null
        }
        outputThread.join(5000)
        val text = output.toString()
        Log.i("MobileVlm", "genie-app exit=${process.exitValue()} frames=$frameCount output=${text.takeLast(4000)}")
        return Regex("(?s)\\[BEGIN\\]:\\s*(.*?)\\[END\\]")
            .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Cell labels for the 2x2 grid, in temporal reading order.
     *
     * The layout sentence is derived from the frames we actually stitched instead of being
     * written out by hand. A hardcoded description silently goes stale whenever the frame
     * count changes, and a prompt that contradicts the image makes the model describe the
     * layout itself ("左下角缺失") rather than the content.
     */
    /**
     * Materializes the node configs genie-app needs into [frameDir], so indexing can run
     * at a lower NPU clock and, on retry, under a different sampler seed.
     *
     * Both are written rather than edited in place: the model pack is deployed data, and
     * mutating it would be lost on the next push and invisible in the source.
     *
     * Every rewrite falls back to the original file on failure, so a malformed copy
     * degrades to "no throttling / no retry" instead of breaking inference outright.
     */
    private fun materializeConfigs(
        modelRoot: File,
        frameDir: File,
        seedOverride: Int?
    ): MaterializedConfigs {
        val htp = rewriteHtpProfile(modelRoot, frameDir)
        val imageEncoder = rewriteExtensions(
            source = File(modelRoot, "img-enc-htp.json"),
            target = File(frameDir, "img-enc-htp.json"),
            extensions = htp.absolutePath
        )
        val generatorSource = File(modelRoot, "text-generator.json")
        val generatorTarget = File(frameDir, "text-generator.json")
        val generator = runCatching {
            val config = JSONObject(generatorSource.readText())
            val node = config.optJSONObject("text-generator")
                ?: return@runCatching generatorSource
            if (seedOverride != null) {
                node.optJSONObject("sampler")
                    ?.put("seed", seedOverride)
                    ?: return@runCatching generatorSource
            }
            // Verified to take effect, unlike the sampler's token-penalty block which
            // parses but is never applied: with this set to 5 the output truncated at
            // exactly five tokens.
            node.put("max-num-tokens", MAX_GENERATED_TOKENS)
            if (!pointExtensionsAt(config, htp.absolutePath)) return@runCatching generatorSource
            generatorTarget.writeText(config.toString())
            generatorTarget
        }.getOrDefault(generatorSource)
        return MaterializedConfigs(imageEncoder, generator)
    }

    private data class MaterializedConfigs(val imageEncoder: File, val generator: File)

    /** Lowers the NPU clock in the HTP backend extension config. */
    private fun rewriteHtpProfile(modelRoot: File, frameDir: File): File {
        val source = File(modelRoot, "htp_backend_ext_config.json")
        val target = File(frameDir, "htp_backend_ext_config.json")
        return runCatching {
            val config = JSONObject(source.readText())
            val devices = config.optJSONArray("devices") ?: return source
            var touched = false
            for (deviceIndex in 0 until devices.length()) {
                val cores = devices.optJSONObject(deviceIndex)?.optJSONArray("cores") ?: continue
                for (coreIndex in 0 until cores.length()) {
                    val core = cores.optJSONObject(coreIndex) ?: continue
                    if (core.has("perf_profile")) {
                        core.put("perf_profile", NPU_PERF_PROFILE)
                        touched = true
                    }
                }
            }
            if (!touched) return source
            target.writeText(config.toString())
            target
        }.getOrDefault(source)
    }

    private fun rewriteExtensions(source: File, target: File, extensions: String): File =
        runCatching {
            val config = JSONObject(source.readText())
            if (!pointExtensionsAt(config, extensions)) return source
            target.writeText(config.toString())
            target
        }.getOrDefault(source)

    /**
     * Redirects a node config's HTP `extensions` entry.
     *
     * Walks every top-level node key rather than naming "image-encoder" or
     * "text-generator", because the two node configs differ only in that key.
     */
    private fun pointExtensionsAt(config: JSONObject, extensions: String): Boolean {
        var found = false
        val keys = config.keys()
        while (keys.hasNext()) {
            val backend = config.optJSONObject(keys.next())
                ?.optJSONObject("engine")
                ?.optJSONObject("backend")
                ?: continue
            if (backend.has("extensions")) {
                backend.put("extensions", extensions)
                found = true
            }
        }
        return found
    }

    private fun gridLayoutSentence(frameCount: Int): String {
        val cells = listOf("左上", "右上", "左下", "右下")
        val filled = cells.take(frameCount).joinToString("、")
        val blank = cells.drop(frameCount)
        return if (frameCount <= 1) {
            "输入是一段 Live Photo 的单帧画面，请描述画面内容与主体。"
        } else {
            "输入是同一段 Live Photo 短视频的 $frameCount 个关键帧拼图，" +
                "按时间先后排列在：$filled" +
                (if (blank.isEmpty()) "，四格均已填满。" else "；${blank.joinToString("、")}为空白，请忽略。") +
                "请对比各帧中主体的位置、姿态与肢体变化，推断动作和运动趋势" +
                "（如走动、奔跑、挥手、转身、跳跃、镜头推拉），不要只描述静态场景。"
        }
    }

    private fun gridPrompt(frameCount: Int): String {
        // The model echoes the grid description back into "summary", which then pollutes
        // the BGE retrieval text, so the output rules follow the layout note and forbid
        // layout metadata explicitly.
        return "<|vision_end|>${gridLayoutSentence(frameCount)} " +
            "严格只输出 JSON，不要输出任何解释文字。" +
            "summary 必须是描述画面实际内容的一句话（主体、场景、动作），" +
            "禁止提及拼图、网格、帧序号、左上右下等排版信息。" +
            "motion_tags 必须把动作绑定到具体主体，并成对给出中文与英文，" +
            "格式为 [\"猫跳跃\",\"cat jumping\",\"人挥手\",\"person waving\"]；" +
            "若主体基本没有运动，则输出主体加静止，如 [\"猫静止\",\"cat still\"]，" +
            "不要编造画面中不存在的动作。" +
            "其余标签用中文和英文混合。Schema: {\"summary\":\"$SCHEMA_PLACEHOLDER\"," +
            "\"theme\":\"$SCHEMA_PLACEHOLDER\",\"scene_tags\":[\"$SCHEMA_PLACEHOLDER\"]," +
            "\"subject_tags\":[\"$SCHEMA_PLACEHOLDER\"],\"motion_tags\":[\"$SCHEMA_PLACEHOLDER\"]," +
            "\"search_keywords\":[\"$SCHEMA_PLACEHOLDER\"]}.<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun writePixelValues(scaled: Bitmap, destination: File) {
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
    }

    /**
     * Stub payload for a parse that did not produce usable semantics.
     *
     * [rawExcerpt] is kept for diagnosis only. It never feeds the BGE retrieval text
     * (LibraryIndexer embeds `ready` rows only), so a failed run stays traceable after
     * logcat has rotated -- without it the only surviving evidence is the reason label,
     * which cannot distinguish "model echoed the placeholder" from "model answered 无"
     * or "model looped until the output was truncated".
     */
    private fun unavailablePayload(
        asset: PreparedAsset,
        reason: String,
        rawExcerpt: String? = null
    ): JSONObject = JSONObject()
        .put("producer", "none")
        .put("model", "none")
        .put("source", "technical_preprocess_only")
        .put("status", "unavailable")
        .put("reason", reason)
        .apply {
            if (!rawExcerpt.isNullOrBlank()) put("raw_output_excerpt", rawExcerpt.take(600))
        }
        // Recorded so a failure can be told apart from "never analysed", and so the
        // reason stays attributable to the prompt/model version that produced it.
        .put("settled", reason in PERMANENT_FAILURES)
        .put("analysis_version", ANALYSIS_VERSION)
        .put("summary", asset.sourceFile.name)
        .put("theme", "")
        .put("scene_tags", JSONArray())
        .put("subject_tags", JSONArray())
        .put("motion_tags", JSONArray())
        .put("search_keywords", JSONArray())
}
