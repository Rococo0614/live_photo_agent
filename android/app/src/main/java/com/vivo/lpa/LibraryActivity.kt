package com.vivo.lpa

import android.app.Activity
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LibraryActivity : Activity() {
    private lateinit var query: EditText
    private lateinit var list: LinearLayout
    private val database by lazy { LibraryDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            setBackgroundColor(Color.rgb(244, 240, 229))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("返回") { finish() }, LinearLayout.LayoutParams(dp(76), dp(44)))
        header.addView(TextView(this).apply {
            text = "素材库 / 解析索引"
            textSize = 22f
            setTextColor(Color.rgb(26, 61, 52))
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val stats = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(111, 72, 38))
            setPadding(dp(10), dp(6), 0, 0)
        }
        root.addView(stats)
        query = EditText(this).apply {
            hint = "搜索文件名、摘要、主题、场景"
            setSingleLine(true)
        }
        root.addView(query, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("搜索") { render(stats) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(button("刷新") { render(stats) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        actions.addView(button("全部显示") { query.text.clear(); render(stats) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        root.addView(actions)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        setContentView(root)
        render(stats)
    }

    private fun render(stats: TextView) {
        list.removeAllViews()
        val records = database.list(query.text.toString())
        val ready = records.count { it.semantic.optBoolean("ready", false) }
        val hasEmbed = records.count { r ->
            val blob = with(database.readableDatabase) {
                query("assets", arrayOf("search_embedding"), "asset_id = ?", arrayOf(r.assetId), null, null, null)
                    .use { if (it.moveToFirst()) it.getBlob(0) else null }
            }
            blob != null && blob.size > 0
        }
        stats.text = "${records.size} 条 · VLM 就绪 $ready · 嵌入 $hasEmbed"

        records.forEach { record ->
            val semantic = record.semantic
            val status = semantic.optString("status", "pending")
            val summary = semantic.optString("summary", "无摘要")
            val sceneTags = semantic.optJSONArray("scene_tags")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val subjectTags = semantic.optJSONArray("subject_tags")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(when (status) {
                    "ready" -> Color.rgb(220, 240, 224)
                    "unavailable" -> Color.rgb(248, 238, 218)
                    else -> Color.WHITE
                })
            }

            val nameLine = LinearLayout(this)
            nameLine.addView(TextView(this).apply {
                text = record.displayName
                textSize = 14f
                setTextColor(Color.rgb(26, 61, 52))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            nameLine.addView(TextView(this).apply {
                text = status
                textSize = 11f
                setTextColor(when (status) {
                    "ready" -> Color.rgb(31, 105, 85)
                    "unavailable" -> Color.rgb(180, 130, 30)
                    else -> Color.GRAY
                })
            })
            row.addView(nameLine)

            row.addView(TextView(this).apply {
                text = summary.take(200)
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(4), 0, dp(2))
            })

            val tagsText = mutableListOf<String>()
            if (sceneTags.isNotEmpty()) tagsText.add("场景: ${sceneTags.take(5).joinToString("、")}")
            if (subjectTags.isNotEmpty()) tagsText.add("主体: ${subjectTags.take(5).joinToString("、")}")
            if (tagsText.isNotEmpty()) {
                row.addView(TextView(this).apply {
                    text = tagsText.joinToString("  ")
                    textSize = 11f
                    setTextColor(Color.rgb(80, 100, 110))
                    setPadding(0, dp(2), 0, dp(2))
                })
            }

            row.addView(button("删除") {
                database.delete(record.assetId)
                render(stats)
            }, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4) })

            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
