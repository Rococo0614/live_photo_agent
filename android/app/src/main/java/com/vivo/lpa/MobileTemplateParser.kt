package com.vivo.lpa

import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/** Converts the Python template_schema_v2 contract into layout_context. */
object MobileTemplateParser {
    private const val GRID_COLS = 120
    private const val GRID_ROWS = 160

    fun parse(template: JSONObject, assets: List<PreparedAsset>): JSONObject {
        val canvasWidth = template.optInt("canvas_width", 1080).coerceAtLeast(2)
        val canvasHeight = template.optInt("canvas_height", 1440).coerceAtLeast(2)
        val totalSeconds = template.optDouble("total_duration_s", 6.0).coerceIn(0.5, 60.0)
        val totalDuration = (totalSeconds * 1000.0).toLong()
        val slots = template.optJSONArray("slots")
            ?: template.optJSONArray("template_slots")
            ?: template.optJSONObject("template")?.optJSONArray("slots")
            ?: JSONArray()
        val items = JSONArray()
        for (index in 0 until min(slots.length(), assets.size)) {
            val slot = slots.optJSONObject(index) ?: JSONObject()
            val rect = rectFor(slot, canvasWidth, canvasHeight, index, slots.length())
            val start = (slot.optDouble("start_time_s", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
            val end = (slot.optDouble("end_time_s", totalDuration / 1000.0) * 1000.0).toLong()
                .coerceAtLeast(start + 1L)
            val grid = gridFor(slot)
            val foreground = slot.optBoolean("foreground", false) || slot.optBoolean("is_overlay", false)
            val item = JSONObject()
                .put("asset_id", assets[index].assetId)
                .put("slot_id", slot.optString("slot_id", "slot_$index"))
                .put("x", rect[0])
                .put("y", rect[1])
                .put("width", rect[2])
                .put("height", rect[3])
                .put("rotation", slot.optDouble("rotation", 0.0))
                .put("start_ms", start)
                .put("end_ms", end)
                .put("z_index", slot.optInt("z_index", index))
                .put("foreground", foreground)
                .put("fill_mode", slot.optString("fill_mode", "freeze"))
                .put("pin_to_top", slot.optBoolean("pin_to_top", false))
                .put("anchor", slot.optString("anchor", "center"))
                .put("scale", slot.optDouble("scale", 0.45))
                .put("x_offset", slot.optInt("x_offset", 0))
                .put("y_offset", slot.optInt("y_offset", 0))
                .put("label", slot.optString("label", ""))
                .put("image_prompt", slot.optString("image_prompt", ""))
            grid?.let {
                item.put("grid_x", it[0]).put("grid_y", it[1])
                    .put("grid_w", it[2]).put("grid_h", it[3])
            }
            slot.optJSONObject("enter_transition")?.let { item.put("enter_transition", it) }
            slot.optJSONObject("exit_transition")?.let { item.put("exit_transition", it) }
            items.put(item)
        }
        return JSONObject()
            .put("schema_version", 1)
            .put("template_id", template.optString("id", "imported"))
            .put("layout_type", template.optString("layout_type", "vertical"))
            .put("needs_segmentation", template.optBoolean("needs_segmentation", false))
            .put("canvas_width", canvasWidth)
            .put("canvas_height", canvasHeight)
            .put("duration_ms", if (totalDuration > 0) totalDuration else 1000L)
            .put("items", items)
    }

    private fun rectFor(slot: JSONObject, width: Int, height: Int, index: Int, count: Int): DoubleArray {
        if (slot.has("x") && slot.has("y") && slot.has("width") && slot.has("height")) {
            return clampRect(doubleArrayOf(
                slot.optDouble("x"), slot.optDouble("y"),
                slot.optDouble("width"), slot.optDouble("height")
            ), width, height)
        }
        if (slot.has("left_pct") || slot.has("top_pct")) {
            return clampRect(doubleArrayOf(
                slot.optDouble("left_pct", 0.0) * width / 100.0,
                slot.optDouble("top_pct", 0.0) * height / 100.0,
                slot.optDouble("width_pct", 100.0) * width / 100.0,
                slot.optDouble("height_pct", 100.0) * height / 100.0
            ), width, height)
        }
        val grid = gridFor(slot)
        if (grid != null) {
            return doubleArrayOf(
                grid[0] * width.toDouble() / GRID_COLS,
                grid[1] * height.toDouble() / GRID_ROWS,
                grid[2] * width.toDouble() / GRID_COLS,
                grid[3] * height.toDouble() / GRID_ROWS
            )
        }
        val region = slot.optString("bg_region", slot.optString("position_preference", ""))
        when (region) {
            "top_third", "upper" -> return doubleArrayOf(0.0, 0.0, width.toDouble(), height / 3.0)
            "middle_third", "middle" -> return doubleArrayOf(0.0, height / 3.0, width.toDouble(), height / 3.0)
            "bottom_third", "lower" -> return doubleArrayOf(0.0, height * 2.0 / 3.0, width.toDouble(), height / 3.0)
            "left_third", "left" -> return doubleArrayOf(0.0, 0.0, width / 3.0, height.toDouble())
            "right_third", "right" -> return doubleArrayOf(width * 2.0 / 3.0, 0.0, width / 3.0, height.toDouble())
            "full" -> {
                // Web templates use "full" for a background strip, not an
                // instruction to cover the entire canvas. Preserve the slot
                // order and divide the canvas when a template has several
                // non-foreground slots.
                if (count > 1) {
                    val cellHeight = height.toDouble() / count
                    return doubleArrayOf(0.0, index * cellHeight, width.toDouble(), cellHeight)
                }
                return doubleArrayOf(0.0, 0.0, width.toDouble(), height.toDouble())
            }
        }
        val horizontal = region.contains("left") || region.contains("right") || region.contains("horizontal")
        if (horizontal) {
            val cellWidth = width.toDouble() / max(1, count)
            val x = if (region.contains("right")) width - cellWidth else index * cellWidth
            return doubleArrayOf(x, 0.0, cellWidth, height.toDouble())
        }
        val cellHeight = height.toDouble() / max(1, count)
        val y = when {
            region.contains("top") || region.contains("upper") -> 0.0
            region.contains("bottom") || region.contains("lower") -> height - cellHeight
            else -> index * cellHeight
        }
        return doubleArrayOf(0.0, min(height.toDouble() - cellHeight, max(0.0, y)), width.toDouble(), cellHeight)
    }

    private fun gridFor(slot: JSONObject): IntArray? {
        val x = slot.optInt("grid_x", -1)
        val y = slot.optInt("grid_y", -1)
        val w = slot.optInt("grid_w", -1)
        val h = slot.optInt("grid_h", -1)
        if (x < 0 || y < 0 || w <= 0 || h <= 0) return null
        return intArrayOf(
            x.coerceIn(0, GRID_COLS - 1),
            y.coerceIn(0, GRID_ROWS - 1),
            w.coerceIn(1, GRID_COLS - x.coerceIn(0, GRID_COLS - 1)),
            h.coerceIn(1, GRID_ROWS - y.coerceIn(0, GRID_ROWS - 1))
        )
    }

    private fun clampRect(rect: DoubleArray, width: Int, height: Int): DoubleArray {
        val x = rect[0].coerceIn(0.0, width - 1.0)
        val y = rect[1].coerceIn(0.0, height - 1.0)
        val w = rect[2].coerceIn(1.0, width - x)
        val h = rect[3].coerceIn(1.0, height - y)
        return doubleArrayOf(x, y, w, h)
    }
}
