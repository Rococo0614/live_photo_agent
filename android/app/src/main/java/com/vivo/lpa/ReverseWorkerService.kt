package com.vivo.lpa

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File

/**
 * Runs the vendor VideoReverser in a short-lived process.
 *
 * On this device VideoReverser can stop producing callbacks after several unrelated codec tools
 * have run in the same process. Resource finalization and bounded retry do not heal that state.
 * A dedicated process gives every reverse call a clean codec/native environment; it exits after
 * replying so the next call cannot inherit poisoned static/native state.
 */
class ReverseWorkerService : Service() {
    companion object {
        const val MSG_REVERSE = 1
        const val MSG_RESULT = 2
        const val KEY_INPUT = "input"
        const val KEY_OUTPUT_DIR = "output_dir"
        const val KEY_JOB_ID = "job_id"
        const val KEY_SUCCESS = "success"
        const val KEY_OUTPUT = "output"
        const val KEY_ERROR = "error"
    }

    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what != MSG_REVERSE) return@Handler false
            val request = Bundle(message.data)
            val reply = message.replyTo
            Thread({ executeRequest(request, reply) }, "reverse-worker").start()
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun executeRequest(request: Bundle, reply: Messenger?) {
        val response = Bundle()
        runCatching {
            val input = request.getString(KEY_INPUT)?.let(::File)
                ?.takeIf(File::isFile) ?: error("reverse input missing")
            val outputDir = request.getString(KEY_OUTPUT_DIR)?.let(::File)
                ?: error("reverse output directory missing")
            outputDir.mkdirs()
            val jobId = request.getString(KEY_JOB_ID).orEmpty().ifBlank {
                "reverse-${System.currentTimeMillis()}"
            }
            VivoSdkExecutor.execute(
                this,
                jobId,
                "video_reverse",
                listOf(input.absolutePath),
                0L,
                0L,
                "",
                0,
                0,
                outputDir,
                null,
                null
            ).output
        }.onSuccess { output ->
            response.putBoolean(KEY_SUCCESS, true)
            response.putString(KEY_OUTPUT, output.absolutePath)
        }.onFailure { error ->
            response.putBoolean(KEY_SUCCESS, false)
            response.putString(KEY_ERROR, "${error::class.java.simpleName}: ${error.message}")
        }

        runCatching {
            reply?.send(Message.obtain(null, MSG_RESULT).apply { data = response })
        }
        stopSelf()
        // Let the Binder reply reach the caller, then discard all vendor native/static state.
        Thread({
            Thread.sleep(750L)
            Process.killProcess(Process.myPid())
        }, "reverse-worker-exit").start()
    }
}
