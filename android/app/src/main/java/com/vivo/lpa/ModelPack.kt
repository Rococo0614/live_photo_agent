package com.vivo.lpa

import android.content.Context
import java.io.File

/** Locations used by the phone-side model pack installer. */
object ModelPack {
    fun root(context: Context): File = context.getExternalFilesDir("models")
        ?: File(context.filesDir, "models")

    fun qwenRoot(context: Context): File = File(root(context), "qwen")

    fun vlmRoot(context: Context): File = File(root(context), "vlm")

    fun executionRoot(context: Context, modelRoot: File): File {
        val cached = File(context.filesDir, "genie-models/${modelRoot.name}")
        if (modelRoot.absolutePath == cached.absolutePath) return modelRoot
        try {
            copyTree(modelRoot, cached)
        } catch (_: Exception) {
            return modelRoot
        }
        return cached
    }

    fun runtimeLibRoot(context: Context, modelRoot: File): File {
        val packaged = File(context.applicationInfo.nativeLibraryDir, "libGenie.so")
        if (packaged.isFile) return packaged.parentFile ?: packaged
        val source = File(modelRoot, "runtime/lib")
        if (!source.isDirectory) return source
        if (source.absolutePath.startsWith(context.filesDir.absolutePath + "/")) return source
        val cached = File(context.filesDir, "genie-runtimes/${modelRoot.name}/lib")
        return try {
            cached.mkdirs()
            source.listFiles { file -> file.isFile && file.extension == "so" }
                ?.forEach { file ->
                    val target = File(cached, file.name)
                    if (!target.isFile || target.length() != file.length()) {
                        file.inputStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            cached
        } catch (_: Exception) {
            source
        }
    }

    fun qwenReady(context: Context): Boolean = File(qwenRoot(context), "genie_config.json").isFile &&
        genieRunner(context, qwenRoot(context)) != null

    fun genieRunner(context: Context, modelRoot: File): File? {
        val packaged = File(context.applicationInfo.nativeLibraryDir, "libgenie_app.so")
        if (packaged.isFile && packaged.canExecute()) return packaged
        return listOf(
        File(modelRoot, "genie-app"),
        File(modelRoot, "bin/genie-app")
        ).firstOrNull { it.isFile && it.canRead() }?.let { source ->
        val cached = File(context.filesDir, "genie-runners/${modelRoot.name}/genie-app")
        try {
            if (!cached.isFile || cached.length() != source.length()) {
                cached.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    cached.outputStream().use { output -> input.copyTo(output) }
                }
                cached.setExecutable(true, false)
            }
            cached.takeIf { it.isFile && it.canExecute() }
        } catch (_: Exception) {
            null
        }
    }
    }

    private fun copyTree(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child -> copyTree(child, File(target, child.name)) }
        } else if (!target.isFile || target.length() != source.length()) {
            target.parentFile?.mkdirs()
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
            }
        }
    }

    /**
     * A VLM pack is intentionally explicit. The runner is not inferred from a
     * text-only Qwen pack because image/video inputs need a different QNN graph.
     */
    fun vlmManifest(context: Context): File = File(vlmRoot(context), "manifest.json")

    fun vlmConfig(context: Context): File = File(vlmRoot(context), "genie_config.json")

    fun vlmScript(context: Context): File = File(vlmRoot(context), "genie-app-script.txt")

    fun vlmReady(context: Context): Boolean =
        (vlmManifest(context).isFile || vlmConfig(context).isFile) &&
            (vlmScript(context).isFile || vlmManifest(context).isFile) &&
            genieRunner(context, vlmRoot(context)) != null
}
