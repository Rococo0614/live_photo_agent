package com.vivo.lpa

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Interactive canvas editor; returns only structured layout state to AgentActivity. */
class CanvasEditorActivity : Activity() {
    private val templateRequest = 703
    private val cancelled = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var canvas: CanvasEditorView
    private var assets: List<PreparedAsset> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(244, 240, 229))
        }
        status = TextView(this).apply {
            text = "正在准备画布素材..."
            textSize = 14f
            setTextColor(Color.rgb(26, 61, 52))
        }
        root.addView(status)
        canvas = CanvasEditorView(this)
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("导入模板") { importTemplate() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(button("选择模板") { chooseTemplate() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(button("使用布局") { finishWithLayout() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions)
        setContentView(root)
        prepareAssets()
    }

    private fun prepareAssets() {
        val uriStrings = intent.getStringArrayListExtra("uris").orEmpty()
        val uris = uriStrings.map(Uri::parse)
        Thread({
            runCatching {
                val prepared = LivePhotoPreprocessor(this).prepare(uris, { cancelled.get() })
                runOnUiThread {
                    assets = prepared
                    canvas.setAssets(prepared)
                    intent.getStringExtra("layout_context")?.let { savedLayout ->
                        runCatching { canvas.applyLayout(JSONObject(savedLayout)) }
                    }
                    status.text = "已准备 ${prepared.size} 个 MP4；可拖动、双指缩放、旋转素材"
                }
            }.onFailure { error ->
                runOnUiThread { status.text = "画布准备失败：${error.message}" }
            }
        }, "canvas-preprocess").start()
    }

    private fun importTemplate() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, templateRequest)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != templateRequest || resultCode != RESULT_OK || data?.data == null) return
        runCatching {
            val text = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalArgumentException("无法读取模板")
            canvas.applyLayout(MobileTemplateParser.parse(JSONObject(text), assets))
            status.text = "模板已解析，可继续拖动调整"
        }.onFailure { error -> status.text = "模板解析失败：${error.message}" }
    }

    private fun chooseTemplate() {
        if (assets.isEmpty()) return
        val templates = MobileTemplateCatalog(this).list()
        val labels = templates.map { template ->
            "${template.optString("id")}  ${template.optString("name", "")} · ${template.optString("description", "")}".trim()
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择拼接模板")
            .setItems(labels) { _, which ->
                val template = templates[which]
                canvas.applyLayout(MobileTemplateParser.parse(template, assets))
                status.text = "已应用 ${template.optString("name", "模板")}，可拖动、缩放、旋转后使用布局"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun finishWithLayout() {
        if (assets.isEmpty()) return
        setResult(RESULT_OK, Intent().putExtra("layout_context", canvas.exportLayout().toString()))
        finish()
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        cancelled.set(true)
        super.onDestroy()
    }
}
