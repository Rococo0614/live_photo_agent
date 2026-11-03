package com.vivo.lpa

import android.content.Context
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class AndroidQnnTensorContract(
    val name: String,
    val byteCount: Long
) {
    init {
        require(NAME.matches(name)) { "invalid QNN tensor name: $name" }
        require(byteCount > 0)
    }

    private companion object {
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_./-]{0,127}")
    }
}

data class AndroidQnnContextContract(
    val componentId: String,
    val contextFile: File,
    val contextSha256: String,
    val inputs: List<AndroidQnnTensorContract>,
    val outputs: List<AndroidQnnTensorContract>
) {
    init {
        require(Regex("[a-z0-9][a-z0-9_.-]{0,63}").matches(componentId))
        require(Regex("[0-9a-f]{64}").matches(contextSha256))
        require(inputs.isNotEmpty() && outputs.isNotEmpty())
        require(inputs.map { it.name }.distinct().size == inputs.size)
        require(outputs.map { it.name }.distinct().size == outputs.size)
    }
}

class AndroidQnnContextRunResult internal constructor(
    val componentId: String,
    val elapsedMs: Long,
    val stdout: String,
    val outputs: Map<String, File>,
    private val workDirectory: File
) : Closeable {
    override fun close() {
        check(workDirectory.absolutePath.contains("/qnn-context-runs/")) {
            "refusing to remove an unexpected QNN work directory"
        }
        workDirectory.deleteRecursively()
    }
}

/**
 * App-UID QAIRT 2.45 context runner used only by the opt-in tracked-preview backend.
 *
 * The executable is the official 2.45 `qnn-net-run`, packaged as an extracted native binary in
 * experimental builds. Contexts remain external model-pack artifacts and are pinned by SHA-256.
 * This closes Android process ownership, cancellation and output-shape gates while a lower-level
 * persistent JNI wrapper is evaluated; it is not registered by the production runtime.
 */
class AndroidQnnContextProcessRunner(
    private val context: Context,
    private val modelRoot: File
) {
    fun execute(
        contract: AndroidQnnContextContract,
        inputFiles: Map<String, File>,
        cancelled: AtomicBoolean,
        timeoutSeconds: Long = 120
    ): AndroidQnnContextRunResult {
        require(BuildConfig.LOCAL_TRACKED_PREVIEW) {
            "QNN context process runner is available only in local tracked-preview builds"
        }
        require(timeoutSeconds in 1..600)
        val runner = File(context.applicationInfo.nativeLibraryDir, RUNNER_NAME)
        require(runner.isFile && runner.canExecute()) { "packaged QAIRT 2.45 runner is missing" }
        require(sha256(runner) == RUNNER_SHA256) { "packaged QNN runner hash mismatch" }
        val nativeRoot = runner.parentFile ?: error("native library directory is missing")
        val backend = File(nativeRoot, "libQnnHtp.so")
        val extension = File(nativeRoot, "libQnnHtpNetRunExtensions.so")
        val system = File(nativeRoot, "libQnnSystem.so")
        require(backend.isFile && extension.isFile && system.isFile) {
            "QAIRT HTP runtime is incomplete"
        }
        require(sha256Cached(backend) == HTP_BACKEND_SHA256 &&
            sha256Cached(extension) == HTP_EXTENSION_SHA256 &&
            sha256Cached(system) == QNN_SYSTEM_SHA256
        ) { "packaged QAIRT runtime hash mismatch" }

        val canonicalModelRoot = modelRoot.canonicalFile
        val contextFile = contract.contextFile.canonicalFile
        require(contextFile.isFile && contextFile.toPath().startsWith(canonicalModelRoot.toPath())) {
            "QNN context must be inside the tracked-preview model pack"
        }
        require(contextFile.length() > 0 && sha256Cached(contextFile) == contract.contextSha256) {
            "QNN context hash mismatch for ${contract.componentId}"
        }
        require(inputFiles.keys == contract.inputs.map { it.name }.toSet()) {
            "QNN inputs do not match the contract for ${contract.componentId}"
        }
        contract.inputs.forEach { tensor ->
            val file = requireNotNull(inputFiles[tensor.name]).canonicalFile
            require(file.isFile && file.length() == tensor.byteCount) {
                "QNN input ${tensor.name} has ${file.length()} bytes; expected ${tensor.byteCount}"
            }
        }
        if (cancelled.get()) throw InterruptedException("QNN execution was cancelled before launch")

        val work = File(
            context.cacheDir,
            "qnn-context-runs/${contract.componentId}-${System.nanoTime()}"
        ).apply { mkdirs() }
        val outputDirectory = File(work, "output")
        val inputList = File(work, "input-list.txt")
        inputList.writeText(contract.inputs.joinToString(" ") { tensor ->
            "${tensor.name}:=${requireNotNull(inputFiles[tensor.name]).canonicalPath}"
        } + "\n")
        val htpConfig = File(work, "htp.json")
        htpConfig.writeText(HTP_CONFIG)
        val backendConfig = File(work, "backend.json")
        backendConfig.writeText(
            """{"backend_extensions":{"shared_library_path":"${jsonEscape(extension.absolutePath)}","config_file_path":"${jsonEscape(htpConfig.absolutePath)}"}}"""
        )

        val command = listOf(
            runner.absolutePath,
            "--backend", backend.absolutePath,
            "--retrieve_context", contextFile.absolutePath,
            "--input_list", inputList.absolutePath,
            "--output_dir", outputDirectory.absolutePath,
            "--config_file", backendConfig.absolutePath,
            "--use_native_input_files",
            "--use_native_output_files",
            "--log_level", "error"
        )
        val output = StringBuilder()
        var process: Process? = null
        var outputThread: Thread? = null
        val startedAt = System.currentTimeMillis()
        try {
            process = ProcessBuilder(command)
                .directory(work)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = listOf(
                        nativeRoot.absolutePath,
                        "/vendor/lib64",
                        "/vendor/lib64/hw"
                    ).joinToString(":")
                    val dspPack = File(canonicalModelRoot, "runtime/dsp")
                    environment()["ADSP_LIBRARY_PATH"] = listOf(
                        dspPack.absolutePath,
                        nativeRoot.absolutePath,
                        "/system/vendor/lib/rfsa/adsp",
                        "/vendor/lib/rfsa/adsp",
                        "/vendor/dsp/cdsp",
                        "/vendor/lib64/hw",
                        "/vendor/npu/lib"
                    ).joinToString(";")
                }
                .start()
            val activeProcess = process
            outputThread = Thread({
                runCatching {
                    activeProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) { output.append(line).append('\n') }
                        }
                    }
                }
            }, "qnn-output-${contract.componentId}").apply { start() }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (!activeProcess.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.get()) {
                    activeProcess.destroy()
                    if (!activeProcess.waitFor(2, TimeUnit.SECONDS)) activeProcess.destroyForcibly()
                    throw InterruptedException("QNN execution cancelled")
                }
                if (System.nanoTime() >= deadline) {
                    activeProcess.destroyForcibly()
                    error("QNN execution timed out after ${timeoutSeconds}s")
                }
            }
            outputThread.join(5_000)
            val stdout = synchronized(output) { output.toString() }
            require(activeProcess.exitValue() == 0) {
                "qnn-net-run failed (${activeProcess.exitValue()}): ${stdout.takeLast(2_000)}"
            }
            require(stdout.contains("qnn-net-run build version: v$QAIRT_EXACT")) {
                "QNN runtime is not pinned to QAIRT $QAIRT_EXACT"
            }
            require(stdout.contains("Finished Executing Graphs")) {
                "QNN runtime did not report graph completion"
            }
            val resultDirectory = File(outputDirectory, "Result_0")
            val outputs = contract.outputs.associate { tensor ->
                val file = File(resultDirectory, "${tensor.name}.raw")
                require(file.isFile && file.length() == tensor.byteCount) {
                    "QNN output ${tensor.name} has ${file.length()} bytes; expected ${tensor.byteCount}"
                }
                tensor.name to file
            }
            return AndroidQnnContextRunResult(
                contract.componentId,
                System.currentTimeMillis() - startedAt,
                stdout,
                outputs,
                work
            )
        } catch (failure: Throwable) {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            outputThread?.join(2_000)
            work.deleteRecursively()
            throw failure
        }
    }

    companion object {
        const val QAIRT_EXACT = "2.45.0.260326154327"
        const val RUNNER_NAME = "libqnn_net_run.so"
        const val RUNNER_SHA256 = "812a0f98f8bcd2ecc39c8a2462e39ad0f031dfaa565b3d095e4475b5484ecba3"
        const val HTP_BACKEND_SHA256 =
            "b5b36b2d5e0cc352b5e5f468f6f668b1585d515854fc1c5890b1365047a36be1"
        const val HTP_EXTENSION_SHA256 =
            "0d29d3dac7d82d8eb5bdc47a0649a7d6a5f5b4a2fbc52b2f975b6ae2c16b6213"
        const val QNN_SYSTEM_SHA256 =
            "f08e9ddc18a40719839cce8bbfb3afd3aef06f5ad486b21934a1c4568c9a6a48"

        private val verifiedHashes = mutableMapOf<String, String>()
        private val HTP_CONFIG = """
            {"devices":[{"device_id":0,"soc_id":87,"dsp_arch":"v81","cores":[{"core_id":0,"perf_profile":"burst","rpc_control_latency":100}]}]}
        """.trimIndent()

        @Synchronized
        private fun sha256Cached(file: File): String {
            val key = "${file.canonicalPath}:${file.length()}:${file.lastModified()}"
            return verifiedHashes.getOrPut(key) { sha256(file) }
        }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun jsonEscape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }
}
