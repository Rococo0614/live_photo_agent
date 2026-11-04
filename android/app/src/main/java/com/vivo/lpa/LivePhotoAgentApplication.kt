package com.vivo.lpa

import android.app.Application
import java.io.File

data class AndroidPreviewArtifactCleanupReport(
    val scannedRootCount: Int,
    val deletedRootCount: Int,
    val deletedByteCount: Long,
    val failedRootCount: Int
)

/** Deletes process-local preview artifacts that cannot survive a process death safely. */
internal object AndroidPreviewArtifactJanitor {
    @Volatile
    var lastReport = AndroidPreviewArtifactCleanupReport(0, 0, 0L, 0)
        private set

    fun clean(cacheDir: File): AndroidPreviewArtifactCleanupReport {
        val cache = cacheDir.canonicalFile
        val parent = File(cache, "full-timeline-previews").canonicalFile
        require(parent.toPath().startsWith(cache.toPath()))
        val roots = parent.listFiles().orEmpty().filter(File::isDirectory)
        var deleted = 0
        var bytes = 0L
        var failed = 0
        roots.forEach { root ->
            val canonical = root.canonicalFile
            if (!canonical.toPath().startsWith(parent.toPath())) {
                failed += 1
                return@forEach
            }
            val rootBytes = canonical.walkTopDown()
                .filter(File::isFile)
                .sumOf(File::length)
            if (canonical.deleteRecursively()) {
                deleted += 1
                bytes += rootBytes
            } else {
                failed += 1
            }
        }
        return AndroidPreviewArtifactCleanupReport(roots.size, deleted, bytes, failed).also {
            lastReport = it
        }
    }
}

class LivePhotoAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() == packageName) {
            AndroidPreviewArtifactJanitor.clean(cacheDir)
        }
    }
}
