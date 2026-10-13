package com.vivo.lpa

import android.app.Activity
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Simple CRUD surface for the persistent parsed-media catalog. */
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
        query = EditText(this).apply {
            hint = "搜索文件名或 VLM 摘要"
            setSingleLine(true)
        }
        root.addView(query, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("搜索") { render() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(button("刷新") { render() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        actions.addView(button("清空索引") {
            database.list().forEach { database.delete(it.assetId) }
            render()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        root.addView(actions)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val records = database.list(query.text.toString())
        list.addView(TextView(this).apply {
            text = "${records.size} 条解析记录"
            setTextColor(Color.rgb(111, 72, 38))
            setPadding(0, dp(4), 0, dp(8))
        })
        records.forEach { record ->
            val summary = record.semantic.optString("summary", "暂无语义摘要")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.WHITE)
            }
            row.addView(TextView(this).apply {
                text = "${record.displayName} · ${record.mediaFormat}"
                textSize = 15f
                setTextColor(Color.rgb(26, 61, 52))
            })
            row.addView(TextView(this).apply {
                text = summary.take(240)
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
            row.addView(button("删除记录") {
                database.delete(record.assetId)
                render()
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
            list.addView(row, LinearLayout.LayoutParams(-1, dp(142)).apply { bottomMargin = dp(8) })
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
