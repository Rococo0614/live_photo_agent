package com.vivo.lpa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class MobileTemplateCatalog(private val context: Context) {
    private val templates: JSONArray by lazy {
        runCatching { JSONArray(context.assets.open("templates.json").bufferedReader().use { it.readText() }) }
            .getOrElse { JSONArray() }
    }

    fun list(): List<JSONObject> = (0 until templates.length()).mapNotNull { index ->
        templates.optJSONObject(index)
    }

    fun find(id: String): JSONObject? = list().firstOrNull { it.optString("id") == id }

    fun chooseForCount(query: String, count: Int, excludedId: String? = null): JSONObject {
        val lower = query.lowercase()
        val candidates = list().filter {
            it.optJSONArray("slots")?.length() == count && it.optString("id") != excludedId
        }
        if (count == 4 &&
            (lower.contains("四拼") || lower.contains("四宫格") || lower.contains("四格")) &&
            !lower.contains("竖排") && !lower.contains("上下")
        ) {
            find("live_four_grid")?.takeIf { it.optString("id") != excludedId }?.let { return it }
        }
        val preferred = candidates.firstOrNull { template ->
            val name = template.optString("name") + template.optString("description")
            (lower.contains("依次") && name.contains("依次")) ||
                (lower.contains("横") && template.optString("layout_type") == "horizontal") ||
                (lower.contains("竖") && template.optString("layout_type") == "vertical")
        }
        val nonSegmentation = candidates.firstOrNull { !it.optBoolean("needs_segmentation", false) }
        return preferred ?: nonSegmentation ?: candidates.firstOrNull() ?: list().firstOrNull() ?: JSONObject()
    }

    fun choose(query: String, fallbackLayout: String): JSONObject {
        val lower = query.lowercase()
        for (index in 0 until templates.length()) {
            val template = templates.optJSONObject(index) ?: continue
            val id = template.optString("id")
            if (lower.contains("依次") && id == "T07") return template
            if (lower.contains("溶解") && id == "T07") return template
            if (fallbackLayout == template.optString("layout_type") && id == "T02") return template
        }
        return templates.optJSONObject(0) ?: JSONObject()
    }
}
