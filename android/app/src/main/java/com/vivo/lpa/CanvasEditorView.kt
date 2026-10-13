package com.vivo.lpa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.atan2
import org.json.JSONArray
import org.json.JSONObject

/** Direct-manipulation canvas; its output is the planner's layout_context. */
class CanvasEditorView(context: Context) : View(context) {
    private data class Slot(
        val assetId: String,
        val bitmap: Bitmap,
        var x: Float,
        var y: Float,
        var width: Float,
        var height: Float,
        var rotation: Float,
        var startMs: Long,
        var endMs: Long,
        var zIndex: Int
    )

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        setShadowLayer(5f, 1f, 1f, Color.BLACK)
    }
    private val slots = mutableListOf<Slot>()
    private var selected = -1
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDistance = 0f
    private var pinchAngle = 0f
    private var pinchWidth = 0f
    private var pinchHeight = 0f
    private var pinchRotation = 0f
    private var canvasWidth = 1080
    private var canvasHeight = 1920
    private var durationMs = 1000L

    fun setAssets(assets: List<PreparedAsset>) {
        slots.clear()
        val usable = assets.mapNotNull { asset ->
            val file = asset.imageFile ?: return@mapNotNull null
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                ?: return@mapNotNull null
            asset to bitmap
        }
        val cellHeight = canvasHeight.toFloat() / max(1, usable.size)
        usable.forEachIndexed { index, pair ->
            val (asset, bitmap) = pair
            slots += Slot(
                assetId = asset.assetId,
                bitmap = bitmap,
                x = 0f,
                y = index * cellHeight,
                width = canvasWidth.toFloat(),
                height = cellHeight,
                rotation = 0f,
                startMs = 0L,
                endMs = durationMs,
                zIndex = index + 1
            )
        }
        selected = if (slots.isEmpty()) -1 else 0
        invalidate()
    }

    fun applyLayout(layout: JSONObject) {
        canvasWidth = layout.optInt("canvas_width", canvasWidth).coerceAtLeast(2)
        canvasHeight = layout.optInt("canvas_height", canvasHeight).coerceAtLeast(2)
        durationMs = layout.optLong("duration_ms", durationMs).coerceAtLeast(1L)
        val items = layout.optJSONArray("items") ?: return
        val byId = slots.associateBy { it.assetId }
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val slot = byId[item.optString("asset_id")] ?: slots.getOrNull(index) ?: continue
            slot.x = item.optDouble("x", slot.x.toDouble()).toFloat()
            slot.y = item.optDouble("y", slot.y.toDouble()).toFloat()
            slot.width = item.optDouble("width", slot.width.toDouble()).toFloat()
            slot.height = item.optDouble("height", slot.height.toDouble()).toFloat()
            slot.rotation = item.optDouble("rotation", slot.rotation.toDouble()).toFloat()
            slot.startMs = item.optLong("start_ms", slot.startMs)
            slot.endMs = item.optLong("end_ms", slot.endMs)
            slot.zIndex = item.optInt("z_index", slot.zIndex)
        }
        invalidate()
    }

    fun exportLayout(): JSONObject {
        val items = JSONArray()
        slots.sortedBy { it.zIndex }.forEach { slot ->
            items.put(JSONObject()
                .put("asset_id", slot.assetId)
                .put("x", slot.x.toDouble())
                .put("y", slot.y.toDouble())
                .put("width", slot.width.toDouble())
                .put("height", slot.height.toDouble())
                .put("rotation", slot.rotation.toDouble())
                .put("start_ms", slot.startMs)
                .put("end_ms", slot.endMs)
                .put("z_index", slot.zIndex))
        }
        return JSONObject()
            .put("schema_version", 1)
            .put("canvas_width", canvasWidth)
            .put("canvas_height", canvasHeight)
            .put("duration_ms", durationMs)
            .put("items", items)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(25, 28, 27))
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / canvasWidth, height.toFloat() / canvasHeight)
        val offsetX = (width - canvasWidth * scale) / 2f
        val offsetY = (height - canvasHeight * scale) / 2f
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawColor(Color.rgb(55, 58, 55))
        slots.sortedBy { it.zIndex }.forEachIndexed { index, slot ->
            val rect = RectF(slot.x, slot.y, slot.x + slot.width, slot.y + slot.height)
            canvas.save()
            canvas.rotate(slot.rotation, rect.centerX(), rect.centerY())
            canvas.drawBitmap(slot.bitmap, null, rect, null)
            if (index == selected || slots.indexOf(slot) == selected) {
                border.color = Color.rgb(242, 190, 76)
                canvas.drawRect(rect, border)
                canvas.drawText("${slots.indexOf(slot) + 1}", rect.left + 20f, rect.top + 45f, label)
            }
            canvas.restore()
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val point = toCanvasPoint(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selected = hitTest(point.first, point.second)
                lastX = point.first
                lastY = point.second
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (selected >= 0 && event.pointerCount >= 2) {
                pinchDistance = distance(event)
                pinchAngle = angle(event)
                pinchWidth = slots[selected].width
                pinchHeight = slots[selected].height
                pinchRotation = slots[selected].rotation
                return true
            }
            MotionEvent.ACTION_MOVE -> if (selected >= 0) {
                val slot = slots[selected]
                if (event.pointerCount >= 2 && pinchDistance > 0f) {
                    val ratio = (distance(event) / pinchDistance).coerceIn(0.5f, 2f)
                    slot.width = (pinchWidth * ratio).coerceIn(160f, canvasWidth.toFloat() * 2f)
                    slot.height = (pinchHeight * ratio).coerceIn(120f, canvasHeight.toFloat() * 2f)
                    slot.rotation = pinchRotation + angle(event) - pinchAngle
                } else {
                    slot.x = (slot.x + point.first - lastX).coerceIn(-slot.width * 0.5f, canvasWidth - slot.width * 0.5f)
                    slot.y = (slot.y + point.second - lastY).coerceIn(-slot.height * 0.5f, canvasHeight - slot.height * 0.5f)
                }
                lastX = point.first
                lastY = point.second
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinchDistance = 0f
                return true
            }
        }
        return true
    }

    private fun toCanvasPoint(viewX: Float, viewY: Float): Pair<Float, Float> {
        val scale = min(width.toFloat() / canvasWidth, height.toFloat() / canvasHeight)
        val offsetX = (width - canvasWidth * scale) / 2f
        val offsetY = (height - canvasHeight * scale) / 2f
        return ((viewX - offsetX) / scale) to ((viewY - offsetY) / scale)
    }

    private fun hitTest(x: Float, y: Float): Int {
        return slots.indices.reversed().firstOrNull { index ->
            val slot = slots[index]
            x >= slot.x && x <= slot.x + slot.width && y >= slot.y && y <= slot.y + slot.height
        } ?: -1
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun angle(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return Math.toDegrees(
            atan2(
                (event.getY(1) - event.getY(0)).toDouble(),
                (event.getX(1) - event.getX(0)).toDouble()
            )
        ).toFloat()
    }
}
