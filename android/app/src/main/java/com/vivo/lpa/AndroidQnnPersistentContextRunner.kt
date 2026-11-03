package com.vivo.lpa

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process QAIRT 2.45 runner used only by `-PpersistentQnn=true` builds.
 *
 * Native code owns a four-entry LRU of reflected context binaries. Execution remains serialized,
 * which matches the validated temporal pipeline, while a QNN signal can be triggered from a
 * cancellation watcher. Every context and runtime library is still content-pinned before JNI sees
 * a path. The process runner remains the default and rollback implementation.
 */
internal class AndroidQnnPersistentContextRunner(
    private val context: Context,
    private val modelRoot: File
) : AndroidQnnContextRunner {
    override fun execute(
        contract: AndroidQnnContextContract,
        inputFiles: Map<String, File>,
        cancelled: AtomicBoolean,
        timeoutSeconds: Long
    ): AndroidQnnContextRunResult {
        require(BuildConfig.LOCAL_TRACKED_PREVIEW && BuildConfig.PERSISTENT_QNN) {
            "persistent QNN requires the explicit tracked-preview JNI build"
        }
        require(timeoutSeconds in 1..600)
        val canonicalModelRoot = modelRoot.canonicalFile
        val contextFile = contract.contextFile.canonicalFile
        require(contextFile.isFile && contextFile.toPath().startsWith(canonicalModelRoot.toPath())) {
            "QNN context must be inside the tracked-preview model pack"
        }
        require(contextFile.length() > 0 &&
            AndroidQnnContextProcessRunner.sha256CachedForRunner(contextFile) == contract.contextSha256
        ) { "QNN context hash mismatch for ${contract.componentId}" }
        require(inputFiles.keys == contract.inputs.map { it.name }.toSet()) {
            "QNN inputs do not match the contract for ${contract.componentId}"
        }
        contract.inputs.forEach { tensor ->
            val file = requireNotNull(inputFiles[tensor.name]).canonicalFile
            require(file.isFile && file.length() == tensor.byteCount) {
                "QNN input ${tensor.name} has ${file.length()} bytes; expected ${tensor.byteCount}"
            }
        }
        if (cancelled.get()) throw InterruptedException("QNN execution was cancelled before JNI")

        val nativeRoot = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val backend = File(nativeRoot, "libQnnHtp.so")
        val system = File(nativeRoot, "libQnnSystem.so")
        val extension = File(nativeRoot, "libQnnHtpNetRunExtensions.so")
        require(backend.isFile && system.isFile && extension.isFile) {
            "QAIRT HTP runtime is incomplete"
        }
        require(
            AndroidQnnContextProcessRunner.sha256CachedForRunner(backend) ==
                AndroidQnnContextProcessRunner.HTP_BACKEND_SHA256 &&
                AndroidQnnContextProcessRunner.sha256CachedForRunner(system) ==
                AndroidQnnContextProcessRunner.QNN_SYSTEM_SHA256 &&
                AndroidQnnContextProcessRunner.sha256CachedForRunner(extension) ==
                AndroidQnnContextProcessRunner.HTP_EXTENSION_SHA256
        ) { "packaged QAIRT runtime hash mismatch" }
        val dspRoot = File(canonicalModelRoot, "runtime/dsp").canonicalFile
        val skel = File(dspRoot, "libQnnHtpV81Skel.so")
        require(skel.isFile &&
            AndroidQnnContextProcessRunner.sha256CachedForRunner(skel) ==
            AndroidOwlV2QnnLocator.DSP_SKEL_SHA256
        ) { "QAIRT 2.45 DSP skel hash mismatch" }

        val runtimeInfo = AndroidQnnPersistentNativeBridge.initialize(
            backend.absolutePath,
            system.absolutePath,
            dspRoot.absolutePath,
            nativeRoot.absolutePath
        )
        require(runtimeInfo.contains("qairt=${AndroidQnnContextProcessRunner.QAIRT_EXACT}")) {
            "persistent JNI runtime is not pinned to QAIRT ${AndroidQnnContextProcessRunner.QAIRT_EXACT}"
        }
        require(runtimeInfo.contains("core_api=2.34.0") &&
            runtimeInfo.contains("system_api=1.9.0") &&
            runtimeInfo.contains("signal=1") && runtimeInfo.contains("burst=1")
        ) {
            "persistent JNI runtime lacks the required QAIRT 2.45 API, signal or power profile"
        }

        val work = File(
            context.cacheDir,
            "qnn-context-runs/${contract.componentId}-jni-${System.nanoTime()}"
        ).apply { mkdirs() }
        val resultDirectory = File(work, "output/Result_0").apply { mkdirs() }
        val outputFiles = contract.outputs.associate { tensor ->
            tensor.name to File(resultDirectory, "${tensor.name}.raw")
        }
        val requestId = NEXT_REQUEST_ID.getAndIncrement()
        val done = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val watcher = Thread({
            try {
                while (!done.get()) {
                    val shouldCancel = cancelled.get()
                    val shouldTimeout = System.nanoTime() >= deadline
                    if (shouldCancel || shouldTimeout) {
                        timedOut.set(shouldTimeout && !shouldCancel)
                        AndroidQnnPersistentNativeBridge.cancel(requestId)
                        return@Thread
                    }
                    Thread.sleep(CANCELLATION_POLL_MS)
                }
            } catch (_: InterruptedException) {
                // Normal completion interrupts the watcher to avoid waiting for the next poll.
            }
        }, "qnn-jni-cancel-${contract.componentId}").apply { start() }

        try {
            val elapsedMs = AndroidQnnPersistentNativeBridge.execute(
                requestId = requestId,
                componentId = contract.componentId,
                contextPath = contextFile.absolutePath,
                contextSha256 = contract.contextSha256,
                inputNames = contract.inputs.map { it.name }.toTypedArray(),
                inputPaths = contract.inputs.map {
                    requireNotNull(inputFiles[it.name]).canonicalPath
                }.toTypedArray(),
                inputByteCounts = contract.inputs.map { it.byteCount }.toLongArray(),
                outputNames = contract.outputs.map { it.name }.toTypedArray(),
                outputPaths = contract.outputs.map {
                    requireNotNull(outputFiles[it.name]).absolutePath
                }.toTypedArray(),
                outputByteCounts = contract.outputs.map { it.byteCount }.toLongArray()
            )
            if (cancelled.get()) throw InterruptedException("QNN execution cancelled")
            check(!timedOut.get()) { "QNN execution timed out after ${timeoutSeconds}s" }
            contract.outputs.forEach { tensor ->
                val file = requireNotNull(outputFiles[tensor.name])
                require(file.isFile && file.length() == tensor.byteCount) {
                    "QNN output ${tensor.name} has ${file.length()} bytes; expected ${tensor.byteCount}"
                }
            }
            return AndroidQnnContextRunResult(
                componentId = contract.componentId,
                elapsedMs = elapsedMs,
                stdout = "$runtimeInfo\nFinished Executing Graphs (persistent JNI)\n",
                outputs = outputFiles,
                workDirectory = work
            )
        } catch (failure: Throwable) {
            work.deleteRecursively()
            if (cancelled.get()) throw InterruptedException("QNN execution cancelled").apply {
                initCause(failure)
            }
            if (timedOut.get()) error("QNN execution timed out after ${timeoutSeconds}s")
            throw failure
        } finally {
            done.set(true)
            watcher.interrupt()
            watcher.join(2_000)
        }
    }

    companion object {
        private const val CANCELLATION_POLL_MS = 20L
        private val NEXT_REQUEST_ID = AtomicLong(1L)

        fun releaseAll() {
            AndroidQnnPersistentNativeBridge.releaseAll()
        }
    }
}

internal object AndroidQnnPersistentNativeBridge {
    private val lock = Any()
    @Volatile private var initializedKey: String? = null
    @Volatile private var runtimeInfo: String? = null

    fun initialize(
        backendPath: String,
        systemPath: String,
        dspRoot: String,
        nativeRoot: String
    ): String {
        val key = listOf(backendPath, systemPath, dspRoot, nativeRoot).joinToString("\u0000")
        runtimeInfo?.takeIf { initializedKey == key }?.let { return it }
        synchronized(lock) {
            runtimeInfo?.takeIf { initializedKey == key }?.let { return it }
            check(initializedKey == null || initializedKey == key) {
                "persistent QNN runtime was already initialized with another model pack"
            }
            System.loadLibrary("lpa_qnn_persistent")
            return nativeInitialize(backendPath, systemPath, dspRoot, nativeRoot).also {
                initializedKey = key
                runtimeInfo = it
            }
        }
    }

    fun execute(
        requestId: Long,
        componentId: String,
        contextPath: String,
        contextSha256: String,
        inputNames: Array<String>,
        inputPaths: Array<String>,
        inputByteCounts: LongArray,
        outputNames: Array<String>,
        outputPaths: Array<String>,
        outputByteCounts: LongArray
    ): Long = nativeExecute(
        requestId,
        componentId,
        contextPath,
        contextSha256,
        inputNames,
        inputPaths,
        inputByteCounts,
        outputNames,
        outputPaths,
        outputByteCounts
    )

    fun cancel(requestId: Long) = nativeCancel(requestId)

    fun releaseAll() {
        if (runtimeInfo != null) nativeReleaseAll()
    }

    fun loadedContextCount(): Int = if (runtimeInfo == null) 0 else nativeLoadedContextCount()

    fun isGraphInFlight(): Boolean = runtimeInfo != null && nativeIsGraphInFlight()

    fun cancelGraphInFlight(): Boolean = runtimeInfo != null && nativeCancelGraphInFlight()

    fun isPowerVoteActive(): Boolean = runtimeInfo != null && nativeIsPowerVoteActive()

    @JvmStatic private external fun nativeInitialize(
        backendPath: String,
        systemPath: String,
        dspRoot: String,
        nativeRoot: String
    ): String

    @JvmStatic private external fun nativeExecute(
        requestId: Long,
        componentId: String,
        contextPath: String,
        contextSha256: String,
        inputNames: Array<String>,
        inputPaths: Array<String>,
        inputByteCounts: LongArray,
        outputNames: Array<String>,
        outputPaths: Array<String>,
        outputByteCounts: LongArray
    ): Long

    @JvmStatic private external fun nativeCancel(requestId: Long)
    @JvmStatic private external fun nativeReleaseAll()
    @JvmStatic private external fun nativeLoadedContextCount(): Int
    @JvmStatic private external fun nativeIsGraphInFlight(): Boolean
    @JvmStatic private external fun nativeCancelGraphInFlight(): Boolean
    @JvmStatic private external fun nativeIsPowerVoteActive(): Boolean
}
