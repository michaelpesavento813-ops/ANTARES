package com.antares.prototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenterlegacy.InteractiveSegmenterLegacy
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal const val COMPOSITE_SOURCE_EDGE = 2048

internal enum class BackgroundMode(val label: String) {
    GREEN("Green Screen"), TRANSPARENT("Transparent Background"), ORIGINAL("Original Background"),
    SOLID("Solid Color"), CUSTOM("Custom Background Image")
}

internal data class CompositePoint(val x: Float, val y: Float) {
    init { require(x in 0f..1f && y in 0f..1f) }
    fun json() = JSONArray().put(x.toDouble()).put(y.toDouble())
    companion object {
        fun read(json: JSONArray) = CompositePoint(json.getDouble(0).toFloat(), json.getDouble(1).toFloat())
    }
}

internal data class MaskBrush(val add: Boolean, val radius: Float, val points: List<CompositePoint>) {
    init { require(radius in 0.005f..0.2f && points.size in 1..256) }
    fun json() = JSONObject().put("add", add).put("radius", radius.toDouble())
        .put("points", JSONArray(points.map { it.json() }))
    companion object {
        fun read(json: JSONObject): MaskBrush {
            val points = json.getJSONArray("points")
            require(points.length() in 1..256)
            return MaskBrush(json.getBoolean("add"), json.getDouble("radius").toFloat(),
                List(points.length()) { CompositePoint.read(points.getJSONArray(it)) })
        }
    }
}

internal data class ForegroundStyle(
    val x: Float, val y: Float, val scale: Float = 0.75f,
    val seed: CompositePoint = CompositePoint(0.5f, 0.5f), val edge: Int = 60,
    val light: Int = 50, val warmth: Int = 50, val outline: List<CompositePoint> = emptyList(),
    val brushes: List<MaskBrush> = emptyList(), val manualOnly: Boolean = false,
    val reusedMask: Long? = null, val reusedLayer: Int = 0
) {
    init {
        require(x in 0f..1f && y in 0f..1f && scale in 0.15f..2f)
        require(edge in 0..100 && light in 0..100 && warmth in 0..100)
        require(outline.isEmpty() || outline.size in 3..80)
        require(brushes.size <= 100 && reusedLayer in 0..3)
    }

    fun json(): JSONObject = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())
        .put("scale", scale.toDouble()).put("seed", seed.json())
        .put("edge", edge).put("light", light).put("warmth", warmth)
        .put("outline", JSONArray(outline.map { it.json() }))
        .put("brushes", JSONArray(brushes.map { it.json() })).put("manualOnly", manualOnly)
        .put("reusedMask", reusedMask ?: JSONObject.NULL).put("reusedLayer", reusedLayer)

    companion object {
        fun read(json: JSONObject): ForegroundStyle {
            val outline = json.getJSONArray("outline")
            val strokes = json.optJSONArray("brushes") ?: JSONArray()
            require(outline.length() <= 80 && strokes.length() <= 100)
            return ForegroundStyle(
                json.getDouble("x").toFloat(), json.getDouble("y").toFloat(), json.getDouble("scale").toFloat(),
                CompositePoint.read(json.getJSONArray("seed")), json.getInt("edge"), json.getInt("light"),
                json.getInt("warmth"), List(outline.length()) { CompositePoint.read(outline.getJSONArray(it)) },
                List(strokes.length()) { MaskBrush.read(strokes.getJSONObject(it)) }, json.optBoolean("manualOnly"),
                if (json.isNull("reusedMask")) null else json.getLong("reusedMask"), json.optInt("reusedLayer")
            )
        }
    }
}

internal data class CompositeDesign(
    val mode: BackgroundMode = BackgroundMode.GREEN, val green: Int = Color.GREEN,
    val solid: Int = Color.WHITE, val backgroundPhoto: Long? = null, val width: Int = 1024, val height: Int = 1024,
    val backgroundX: Float = 0.5f, val backgroundY: Float = 0.5f, val backgroundScale: Float = 1f,
    val layers: List<ForegroundStyle>, val reviewed: Boolean = false
) {
    init {
        require(layers.size in 1..4 && width in 256..2048 && height in 256..2048)
        require(backgroundX in 0f..1f && backgroundY in 0f..1f && backgroundScale in 1f..2f)
    }

    fun json() = JSONObject().put("version", 1).put("background", mode.name)
        .put("green", green).put("solid", solid).put("backgroundPhoto", backgroundPhoto ?: JSONObject.NULL)
        .put("width", width).put("height", height).put("reviewed", reviewed)
        .put("backgroundX", backgroundX.toDouble()).put("backgroundY", backgroundY.toDouble())
        .put("backgroundScale", backgroundScale.toDouble())
        .put("layers", JSONArray(layers.map { it.json() }))

    companion object {
        fun read(json: JSONObject): CompositeDesign {
            require(json.getInt("version") == 1)
            val layers = json.getJSONArray("layers")
            return CompositeDesign(
                BackgroundMode.valueOf(json.getString("background")), json.getInt("green"), json.getInt("solid"),
                if (json.isNull("backgroundPhoto")) null else json.getLong("backgroundPhoto"),
                json.getInt("width"), json.getInt("height"), json.getDouble("backgroundX").toFloat(),
                json.getDouble("backgroundY").toFloat(), json.getDouble("backgroundScale").toFloat(),
                List(layers.length()) { ForegroundStyle.read(layers.getJSONObject(it)) }, json.optBoolean("reviewed")
            )
        }
    }
}

internal class CompositeRenderer(private val context: Context, private val project: Long, private val edgeLimit: Int) : AutoCloseable {
    private data class Mask(val width: Int, val height: Int, val values: FloatArray)

    private val sources = mutableMapOf<Long, Bitmap>()
    private val crops = mutableMapOf<SubjectReference, Bitmap>()
    private val masks = mutableMapOf<SubjectReference, Pair<CompositePoint, Mask>>()
    private var segmenter: InteractiveSegmenterLegacy? = null
    private val db = ProjectStore(context)

    fun source(id: Long): Bitmap = sources.getOrPut(id) {
        val row = db.task(id)?.takeIf { it.projectId == project && it.kind == "import" && it.status == "done" }
            ?: error("A composite source is unavailable in this SUBJECT")
        upright(originalFile(context, requireNotNull(row.hash)), edgeLimit)
    }

    fun crop(reference: SubjectReference): Bitmap = crops.getOrPut(reference) {
        val image = source(reference.photoId)
        val rect = reference.region.pixels(image.width, image.height)
        Bitmap.createBitmap(image, rect.left, rect.top, rect.width(), rect.height()).let {
            if (it === image) requireNotNull(it.copy(Bitmap.Config.ARGB_8888, false)) else it
        }
    }

    private fun modelMask(reference: SubjectReference, style: ForegroundStyle): Mask {
        val existing = masks[reference]
        if (existing?.first == style.seed) return existing.second
        val image = crop(reference)
        val detector = segmenter ?: InteractiveSegmenterLegacy.createFromOptions(context,
            InteractiveSegmenterLegacy.InteractiveSegmenterLegacyOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("magic_touch.tflite").build())
                .setOutputConfidenceMasks(true).setOutputCategoryMask(false).build()
        ).also { segmenter = it }
        val point = NormalizedKeypoint.create(style.seed.x, style.seed.y)
        // MPImage takes ownership of its bitmap. The crop is still needed for compositing.
        val inferenceInput = requireNotNull(image.copy(Bitmap.Config.ARGB_8888, false))
        val result = BitmapImageBuilder(inferenceInput).build().use {
            detector.segment(it, InteractiveSegmenterLegacy.RegionOfInterest.create(point))
        }
        val output = result.confidenceMasks().orElseThrow { IllegalStateException("No confidence mask was returned") }
        try {
            require(output.size in 1..2) { "Unexpected segmentation output" }
            val foreground = output.last()
            val data = ByteBufferExtractor.extract(foreground).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val count = foreground.width * foreground.height
            require(data.remaining() >= count)
            val values = FloatArray(count)
            data.get(values)
            val opaque = values.count { it.isFinite() && it > 0.58f }
            require(opaque > count * 0.01f && opaque < count * 0.98f) {
                "Cutout failed. Try a new subject point or draw an outline."
            }
            return Mask(foreground.width, foreground.height, values).also { masks[reference] = style.seed to it }
        } finally { output.forEach { it.close() } }
    }

    fun cutout(reference: SubjectReference, style: ForegroundStyle): Bitmap {
        val image = crop(reference)
        val reused = style.reusedMask?.let { id ->
            val task = db.task(id)?.takeIf { it.projectId == project && it.kind == "png" && it.status == "done" }
                ?: error("The reusable mask belongs to another SUBJECT or is unavailable")
            val request = WorkflowRequest.decode(task.input)
            require(request.references.getOrNull(style.reusedLayer) == reference) { "The mask belongs to a different source region" }
            val bitmap = android.graphics.BitmapFactory.decodeFile(outputFile(context, id, "mask-${style.reusedLayer}.png").path)
                ?: error("The saved isolation mask is missing")
            bitmap
        }
        val alphaLayer = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val alphaCanvas = Canvas(alphaLayer)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        try {
            if (reused != null) alphaCanvas.drawBitmap(reused, null, RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()), maskPaint)
            else if (style.outline.isNotEmpty()) {
                val path = Path()
                style.outline.forEachIndexed { index, pt ->
                    if (index == 0) path.moveTo(pt.x * image.width, pt.y * image.height)
                    else path.lineTo(pt.x * image.width, pt.y * image.height)
                }
                path.close()
                alphaCanvas.drawPath(path, maskPaint)
            } else if (!style.manualOnly) {
                val generated = segmentAlpha(reference, style, image)
                try { alphaCanvas.drawBitmap(generated, 0f, 0f, maskPaint) } finally { generated.recycle() }
            }
            for (brush in style.brushes) {
                maskPaint.xfermode = if (brush.add) null else android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                val radius = brush.radius * minOf(image.width, image.height)
                maskPaint.strokeWidth = radius * 2
                maskPaint.strokeCap = Paint.Cap.ROUND
                brush.points.zipWithNext().forEach { (left, right) ->
                    alphaCanvas.drawLine(left.x * image.width, left.y * image.height,
                        right.x * image.width, right.y * image.height, maskPaint)
                }
                brush.points.forEach { alphaCanvas.drawCircle(it.x * image.width, it.y * image.height, radius, maskPaint) }
            }
            require(hasForeground(alphaLayer)) { "Mask is empty. Paint the selected subject back in before rendering." }
            val result = requireNotNull(image.copy(Bitmap.Config.ARGB_8888, true))
            Canvas(result).drawBitmap(alphaLayer, 0f, 0f,
                Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) })
            return result
        } finally {
            alphaLayer.recycle()
            reused?.recycle()
        }
    }

    private fun hasForeground(mask: Bitmap): Boolean {
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (Color.alpha(mask.getPixel(x, y)) > 32) return true
        }
        return false
    }

    private fun segmentAlpha(reference: SubjectReference, style: ForegroundStyle, image: Bitmap): Bitmap {
        val mask = modelMask(reference, style)
        val values = IntArray(image.width * image.height)
        val soft = 0.06f + style.edge / 100f * 0.34f
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val offset = y * image.width + x
            val sx = (x + 0.5f) * mask.width / image.width - 0.5f
            val sy = (y + 0.5f) * mask.height / image.height - 0.5f
            val mx = sx.coerceIn(0f, mask.width - 1f)
            val my = sy.coerceIn(0f, mask.height - 1f)
            val left = mx.toInt()
            val top = my.toInt()
            val right = (left + 1).coerceAtMost(mask.width - 1)
            val bottom = (top + 1).coerceAtMost(mask.height - 1)
            val dx = mx - left
            val dy = my - top
            val a = mask.values[top * mask.width + left] * (1f - dx) + mask.values[top * mask.width + right] * dx
            val b = mask.values[bottom * mask.width + left] * (1f - dx) + mask.values[bottom * mask.width + right] * dx
            val alpha = (((a * (1f - dy) + b * dy) - (0.5f - soft)) / (soft * 2))
                .coerceIn(0f, 1f) * 255f
            values[offset] = Color.WHITE and ((alpha.roundToInt() shl 24) or 0x00FFFFFF)
        }
        return Bitmap.createBitmap(values, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    fun render(references: List<SubjectReference>, design: CompositeDesign, preview: Boolean = false): Bitmap {
        require(references.size == design.layers.size)
        val requiredSources = references.map { it.photoId }.toSet() + listOfNotNull(design.backgroundPhoto)
        val expired = sources.keys.filter { it !in requiredSources }
        expired.forEach { sources.remove(it)?.recycle() }
        val downscale = if (preview) 440f / maxOf(design.width, design.height) else 1f
        val width = (design.width * downscale).roundToInt()
        val height = (design.height * downscale).roundToInt()
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        try {
            when (design.mode) {
                BackgroundMode.GREEN -> canvas.drawColor(design.green or Color.BLACK)
                BackgroundMode.SOLID -> canvas.drawColor(design.solid or Color.BLACK)
                BackgroundMode.TRANSPARENT -> Unit
                BackgroundMode.ORIGINAL -> canvas.drawBitmap(source(references.first().photoId), null,
                    backgroundRect(source(references.first().photoId), width, height, design, preserveFullSource = true), paint)
                BackgroundMode.CUSTOM -> {
                    val id = design.backgroundPhoto ?: error("Choose a custom background image")
                    val background = source(id)
                    canvas.drawBitmap(background, null, backgroundRect(background, width, height, design, false), paint)
                }
            }
            for ((index, reference) in references.withIndex()) {
                val style = design.layers[index]
                val layer = cutout(reference, style)
                try {
                    val scale = minOf(width.toFloat() / layer.width, height.toFloat() / layer.height) * style.scale
                    val w = layer.width * scale
                    val h = layer.height * scale
                    val centerX = width * style.x
                    val centerY = height * style.y
                    val brightness = (style.light - 50) * 0.55f
                    val tone = (style.warmth - 50) * 0.32f
                    paint.colorFilter = ColorMatrixColorFilter(floatArrayOf(
                        1f, 0f, 0f, 0f, brightness + tone, 0f, 1f, 0f, 0f, brightness,
                        0f, 0f, 1f, 0f, brightness - tone, 0f, 0f, 0f, 1f, 0f
                    ))
                    canvas.drawBitmap(layer, null,
                        RectF(centerX - w / 2, centerY - h / 2, centerX + w / 2, centerY + h / 2), paint)
                } finally { layer.recycle() }
            }
            return output
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    }

    private fun backgroundRect(image: Bitmap, width: Int, height: Int, design: CompositeDesign, preserveFullSource: Boolean): RectF {
        val ratios = listOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val scale = if (preserveFullSource) ratios.min() else ratios.max() * design.backgroundScale
        val w = image.width * scale
        val h = image.height * scale
        val left = (width - w) * if (preserveFullSource) 0.5f else design.backgroundX
        val top = (height - h) * if (preserveFullSource) 0.5f else design.backgroundY
        return RectF(left, top, left + w, top + h)
    }

    override fun close() {
        segmenter?.close()
        crops.values.forEach { it.recycle() }
        sources.values.forEach { it.recycle() }
        db.close()
    }
}
