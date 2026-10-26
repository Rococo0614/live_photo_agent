package com.vivo.lpa

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synchronous client for [ReverseWorkerService], called from the Agent worker thread. */
object ReverseProcessExecutor {
    private const val BIND_TIMEOUT_SECONDS = 10L
    private const val EXECUTION_TIMEOUT_SECONDS = 150L

    fun execute(context: Context, jobId: String, input: File, outputDir: File): File {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "reverse process client must not block the main thread"
        }
        val app = context.applicationContext
        val connected = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val serviceRef = AtomicReference<Messenger?>()
        val outputRef = AtomicReference<File?>()
        val errorRef = AtomicReference<String?>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what != ReverseWorkerService.MSG_RESULT) return@Handler false
            if (message.data.getBoolean(ReverseWorkerService.KEY_SUCCESS)) {
                outputRef.set(message.data.getString(ReverseWorkerService.KEY_OUTPUT)?.let(::File))
            } else {
                errorRef.set(message.data.getString(ReverseWorkerService.KEY_ERROR))
            }
            completed.countDown()
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceRef.set(Messenger(binder))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (completed.count > 0) {
                    errorRef.compareAndSet(null, "reverse worker disconnected")
                    completed.countDown()
                }
            }
        }

        val bound = app.bindService(
            Intent(app, ReverseWorkerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        check(bound) { "cannot bind reverse worker" }
        try {
            check(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "reverse worker bind timeout"
            }
            val request = Message.obtain(null, ReverseWorkerService.MSG_REVERSE).apply {
                data = Bundle().apply {
                    putString(ReverseWorkerService.KEY_INPUT, input.absolutePath)
                    putString(ReverseWorkerService.KEY_OUTPUT_DIR, outputDir.absolutePath)
                    putString(ReverseWorkerService.KEY_JOB_ID, jobId)
                }
                replyTo = reply
            }
            serviceRef.get()?.send(request) ?: error("reverse worker messenger unavailable")
            check(completed.await(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "reverse worker execution timeout"
            }
            errorRef.get()?.let { error(it) }
            return outputRef.get()?.takeIf { it.isFile && it.length() > 0 }
                ?: error("reverse worker returned no output")
        } finally {
            runCatching { app.unbindService(connection) }
        }
    }
}
