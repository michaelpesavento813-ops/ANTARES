package com.antares.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

internal enum class Workflow(val title: String) {
    REPAIR("Restorative Repair"),
    COSMETIC("Make Me Pretty"),
    SCAN("Deep Scan/Mapping"),
    COMPOSITE("Build Composite Image")
}

internal data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(right - left >= 0.005f && bottom - top >= 0.005f)
    }

    fun json(): JSONArray = JSONArray().put(left.toDouble()).put(top.toDouble())
        .put(right.toDouble()).put(bottom.toDouble())

    fun pixels(width: Int, height: Int) = Rect(
        floor(left * width).toInt(), floor(top * height).toInt(),
        ceil(right * width).toInt(), ceil(bottom * height).toInt()
    )

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    fun intersects(other: Region) =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun overlapRatio(other: Region): Float {
        val width = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val height = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val overlap = width * height
        val area = (right - left) * (bottom - top)
        val otherArea = (other.right - other.left) * (other.bottom - other.top)
        return overlap / (area + otherArea - overlap)
    }

    companion object {
        fun read(value: JSONArray) = Region(
            value.getDouble(0).toFloat(), value.getDouble(1).toFloat(),
            value.getDouble(2).toFloat(), value.getDouble(3).toFloat()
        )
    }
}

internal data class SubjectReference(val photoId: Long, val region: Region) {
    fun json() = JSONObject().put("photoId", photoId).put("region", region.json())
}

internal data class WorkflowRequest(
    val flow: Workflow, val references: List<SubjectReference>, val settings: Int, val amount: Int,
    val composite: CompositeDesign? = null, val age: AgeDesign? = null
) {
    init {
        require(references.size in (if (flow == Workflow.COMPOSITE) (if (composite == null) 2..4 else 1..4) else 1..1))
        require(composite == null || flow == Workflow.COMPOSITE && composite.layers.size == references.size)
        require(age == null || flow == Workflow.COSMETIC)
        require(references.map { it.photoId }.distinct().size == references.size)
        require(settings in 0..2 && amount in 0..100)
    }

    fun encode(): String = JSONObject()
        .put("version", 1).put("workflow", flow.name).put("settings", settings).put("amount", amount)
        .put("references", JSONArray(references.map { it.json() }))
        .put("composite", composite?.json() ?: JSONObject.NULL)
        .put("age", age?.json() ?: JSONObject.NULL).toString()

    companion object {
        fun decode(text: String): WorkflowRequest {
            val doc = JSONObject(text)
            require(doc.getInt("version") == 1)
            val refs = doc.getJSONArray("references")
            return WorkflowRequest(
                Workflow.valueOf(doc.getString("workflow")),
                List(refs.length()) {
                    val row = refs.getJSONObject(it)
                    SubjectReference(row.getLong("photoId"), Region.read(row.getJSONArray("region")))
                },
                doc.getInt("settings"), doc.getInt("amount"),
                doc.optJSONObject("composite")?.let(CompositeDesign::read),
                doc.optJSONObject("age")?.let(AgeDesign::read)
            )
        }
    }
}

internal fun isolatedRegion(region: Region, detected: List<Region>) =
    detected.count { region.intersects(it) } <= 1

internal fun applyAppearance(source: Bitmap, region: Region, request: WorkflowRequest): Bitmap {
    require(request.flow == Workflow.COSMETIC || request.flow == Workflow.REPAIR)
    if (request.age?.years == 0 || request.age?.strength == 0) {
        return requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
    }
    val output = source.copy(Bitmap.Config.ARGB_8888, true) ?: error("Could not copy the image")
    val area = region.pixels(source.width, source.height)
    val width = area.width()
    val height = area.height()
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, area.left, area.top, width, height)
    val rendered = pixels.copyOf()
    val age = request.age
    val strength = if (age == null) request.amount / 100f
        else age.strength / 100f * kotlin.math.abs(age.years) / 40f
    val tint = when (request.settings) {
        0 -> Color.rgb(160, 108, 74)
        1 -> Color.rgb(185, 92, 120)
        else -> Color.rgb(221, 173, 146)
    }
    for (y in 0 until height) for (x in 0 until width) {
        val index = y * width + x
        val pixel = pixels[index]
        val edge = minOf(x, y, width - 1 - x, height - 1 - y)
        val fade = edge.coerceAtMost(8) / 8f * strength
        if (fade == 0f) continue
        val left = pixels[y * width + (x - 1).coerceAtLeast(0)]
        val right = pixels[y * width + (x + 1).coerceAtMost(width - 1)]
        val up = pixels[(y - 1).coerceAtLeast(0) * width + x]
        val down = pixels[(y + 1).coerceAtMost(height - 1) * width + x]
        fun channel(read: (Int) -> Int): Int {
            val original = read(pixel)
            val local = (read(left) + read(right) + read(up) + read(down) + original * 2) / 6f
            val adjusted = if (age != null) {
                if (age.years < 0) local * 0.96f + 11f else original + (original - local) * 1.9f - 6
            } else if (request.flow == Workflow.COSMETIC) {
                val base = if (request.settings == 2) local else original.toFloat()
                base * 0.78f + read(tint) * 0.22f
            } else when (request.settings) {
                0 -> (original - 128) * 1.14f + 134
                1 -> local
                else -> {
                    val a = read(left)
                    val b = read(right)
                    val c = read(up)
                    val d = read(down)
                    (a + b + c + d - minOf(a, b, c, d) - maxOf(a, b, c, d)) / 2f
                }
            }
            return (original + fade * (adjusted - original)).toInt().coerceIn(0, 255)
        }
        rendered[index] = Color.argb(Color.alpha(pixel), channel(Color::red), channel(Color::green), channel(Color::blue))
    }
    output.setPixels(rendered, 0, width, area.left, area.top, width, height)
    return output
}

internal fun composite(crops: List<Bitmap>, sideBySide: Boolean): Bitmap {
    val cell = 512
    val output = Bitmap.createBitmap(if (sideBySide) cell * crops.size else cell,
        if (sideBySide) cell else cell * crops.size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(Color.rgb(245, 243, 238))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    for ((index, bitmap) in crops.withIndex()) {
        val scale = 488f / maxOf(bitmap.width, bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (if (sideBySide) index * cell else 0) + (cell - width) / 2
        val top = (if (sideBySide) 0 else index * cell) + (cell - height) / 2
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), paint)
    }
    return output
}
