package com.antares.prototype

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.json.JSONArray
import java.util.concurrent.Executors

class WorkflowActivity : Activity() {
    private val store by lazy { ProjectStore(applicationContext) }
    private val decoder = Executors.newSingleThreadExecutor()
    private lateinit var workflow: Workflow
    private lateinit var root: LinearLayout
    private lateinit var progress: TextView
    private lateinit var preview: SubjectView
    private lateinit var confirm: Button
    private lateinit var choices: RadioGroup
    private lateinit var amount: SeekBar
    private var projectId = 0L
    private var photoId = 0L
    private var session = 0
    private var processing = false
    private var initialSelection: Region? = null
    private val references = mutableListOf<SubjectReference>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        workflow = Workflow.valueOf(intent.getStringExtra("workflow") ?: run { finish(); return })
        projectId = intent.getLongExtra("project", 0)
        photoId = state?.getLong("photo") ?: intent.getLongExtra("photo", 0)
        initialSelection = state?.getString("selected")?.let { Region.read(JSONArray(it)) }
        state?.getString("references")?.let { text ->
            val rows = JSONArray(text)
            repeat(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                references.add(SubjectReference(row.getLong("photoId"), Region.read(row.getJSONArray("region"))))
            }
        }
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        scroll.addView(root)
        setContentView(scroll)
        label(workflow.title, 25f)
        progress = label("Choose the subject before starting.", 16f)
        button("Choose photograph") { pickPhoto() }
        preview = SubjectView(this) { updateStatus() }
        preview.contentDescription = "Photo with numbered subject outlines. Use the buttons below for selection."
        root.addView(preview, LinearLayout.LayoutParams(-1, (365 * resources.displayMetrics.density).toInt()))
        val controls = LinearLayout(this)
        root.addView(controls)
        button("Choose outline", controls) { preview.manual = false; chooseCandidate() }
        button("Draw region", controls) {
            preview.manual = true
            preview.selection = null
            preview.invalidate()
            updateStatus()
        }
        button("Reset zoom") { preview.resetZoom() }
        choices = RadioGroup(this)
        root.addView(choices)
        val options = when (workflow) {
            Workflow.REPAIR -> listOf("Restore faded tones", "Reduce noise", "Soften fine scratches in the selected area")
            Workflow.COSMETIC -> listOf("Hair tint", "Makeup tint", "Complexion softening")
            Workflow.COMPOSITE -> listOf("Side-by-side layout", "Stacked layout")
            Workflow.SCAN -> emptyList()
        }
        options.forEachIndexed { index, title ->
            choices.addView(RadioButton(this).apply { id = index + 1; text = title })
        }
        if (options.isNotEmpty()) choices.check(state?.getInt("mode", 1) ?: 1)
        amount = SeekBar(this).apply {
            max = 100
            progress = state?.getInt("amount", 45) ?: 45
            contentDescription = "Adjustment strength"
        }
        if (workflow == Workflow.REPAIR || workflow == Workflow.COSMETIC) {
            label("Adjustment strength", 15f)
            root.addView(amount)
        }
        confirm = button(if (workflow == Workflow.COMPOSITE) "Confirm and add reference" else "Confirm subject and start") {
            confirmSelection()
        }
        if (workflow == Workflow.COMPOSITE) {
            button("Review confirmed references") { reviewReferences() }
            button("Create composite") { finishComposite() }
        }
        button("Cancel workflow") { finish() }
        updateStatus()
        if (photoId != 0L && references.none { it.photoId == photoId }) loadPhoto(photoId)
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putLong("photo", photoId)
        state.putString("selected", preview.selection?.json()?.toString())
        state.putString("references", JSONArray(references.map { it.json() }).toString())
        state.putInt("mode", choices.checkedRadioButtonId)
        state.putInt("amount", amount.progress)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        session++
        decoder.shutdownNow()
        if (::preview.isInitialized) preview.swap(null, emptyList())
        store.close()
        super.onDestroy()
    }

    private fun pickPhoto() {
        val photos = store.photos(projectId).filter { picture -> references.none { it.photoId == picture.id } }
        if (photos.isEmpty()) {
            alert("No photos available", "Add photos to this project from the main screen.")
            return
        }
        AlertDialog.Builder(this).setTitle("Choose photograph")
            .setItems(photos.map { it.label }.toTypedArray()) { _, index ->
                initialSelection = null
                loadPhoto(photos[index].id)
            }.show()
    }

    private fun loadPhoto(id: Long) {
        val photo = store.task(id)?.takeIf { it.kind == "import" && it.status == "done" && it.projectId == projectId } ?: return
        photoId = id
        processing = true
        preview.swap(null, emptyList())
        updateStatus()
        val attempt = ++session
        decoder.execute {
            var bitmap: Bitmap? = null
            var outlines = emptyList<Region>()
            var failure: String? = null
            try {
                bitmap = upright(originalFile(this, requireNotNull(photo.hash)), 1024)
                outlines = try { detectSubjects(this, bitmap) } catch (_: Exception) {
                    failure = "Automatic outlines unavailable. Draw a region."
                    emptyList()
                } catch (_: LinkageError) {
                    failure = "Automatic outlines unavailable. Draw a region."
                    emptyList()
                }
            } catch (_: Exception) { failure = "The imported photograph could not be opened." }
            runOnUiThread {
                if (attempt != session || isDestroyed) {
                    bitmap?.recycle()
                } else {
                    processing = false
                    preview.swap(bitmap, outlines)
                    preview.selection = initialSelection
                    initialSelection = null
                    preview.invalidate()
                    updateStatus(failure)
                }
            }
        }
    }

    private fun chooseCandidate() {
        val candidates = preview.candidates
        if (candidates.isEmpty()) {
            alert("Draw the subject", "Tap Draw region, then drag a box around only the intended area.")
            return
        }
        AlertDialog.Builder(this).setTitle("Choose outline")
            .setItems(candidates.indices.map { "Subject ${it + 1}" }.toTypedArray()) { _, index ->
                preview.selection = candidates[index]
                preview.invalidate()
                updateStatus()
            }.show()
    }

    private fun updateStatus(message: String? = null) {
        val selected = preview.selection
        val isolated = selected != null && isolatedRegion(selected, preview.candidates)
        confirm.isEnabled = !processing && isolated && (workflow != Workflow.COMPOSITE || references.size < 4)
        val detail = when {
            processing -> "Looking for visible faces…"
            message != null -> message
            selected != null && !isolated -> "Selection overlaps multiple outlines. Zoom and draw a smaller region."
            selected != null -> "Confirm that the outlined area contains only the intended subject."
            !preview.hasPhoto -> "Choose an imported photograph."
            preview.candidates.isEmpty() -> "No automatic outline. Draw a region around the intended subject."
            else -> "${preview.candidates.size} candidate outlines. Tap an outline or draw a region."
        }
        val suffix = if (workflow == Workflow.COMPOSITE) "\nConfirmed references: ${references.size} of 4 maximum." else ""
        progress.text = detail + suffix
    }

    private fun confirmSelection() {
        val selected = preview.selection ?: return
        if (!isolatedRegion(selected, preview.candidates)) return
        val region = SubjectReference(photoId, selected)
        if (workflow == Workflow.COMPOSITE) {
            if (references.size >= 4 || references.any { it.photoId == photoId }) return
            references.add(region)
            photoId = 0
            preview.swap(null, emptyList())
            updateStatus()
        } else {
            submit(listOf(region))
        }
    }

    private fun reviewReferences() {
        if (references.isEmpty()) {
            alert("Confirmed references", "No references have been confirmed.")
            return
        }
        val labels = references.map { store.task(it.photoId)?.label ?: "Photo ${it.photoId}" }
        AlertDialog.Builder(this).setTitle("Select a reference to change")
            .setItems(labels.toTypedArray()) { _, index ->
                val removed = references.removeAt(index)
                initialSelection = removed.region
                loadPhoto(removed.photoId)
            }.setNegativeButton("Close", null).show()
    }

    private fun finishComposite() {
        if (references.size < 2) {
            alert("Choose at least two references", "Confirm the intended subject separately in each photograph.")
            return
        }
        AlertDialog.Builder(this).setTitle("Confirm composite references")
            .setMessage(references.joinToString("\n") {
                store.task(it.photoId)?.label ?: "Photo ${it.photoId}"
            })
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Build composite") { _, _ -> submit(references.toList()) }.show()
    }

    private fun submit(refs: List<SubjectReference>) {
        try {
            val request = WorkflowRequest(workflow, refs, (choices.checkedRadioButtonId - 1).coerceAtLeast(0), amount.progress).encode()
            store.queue(projectId, if (workflow == Workflow.SCAN) "scan" else "png", request, workflow.title, refs.first().photoId)
            ProcessingService.schedule(this)
            setResult(RESULT_OK, Intent().putExtra("photo", refs.first().photoId))
            finish()
        } catch (error: Exception) { alert("Unable to queue job", error.message.orEmpty()) }
    }

    private fun alert(title: String, copy: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(copy).setPositiveButton("OK", null).show()
    }

    private fun label(copy: String, size: Float) = TextView(this).apply {
        text = copy
        textSize = size
        setPadding(0, 10, 0, 10)
        root.addView(this)
    }

    private fun button(copy: String, parent: LinearLayout = root, action: () -> Unit) =
        Button(this).apply {
            text = copy
            isAllCaps = false
            setOnClickListener { action() }
            parent.addView(this)
        }
}

internal fun detectSubjects(context: Context, bitmap: Bitmap): List<Region> {
    val options = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
        .setNumFaces(16).build()
    FaceLandmarker.createFromOptions(context, options).use { detector ->
        BitmapImageBuilder(bitmap).build().use { image ->
            return detector.detect(image).faceLandmarks().mapNotNull { face ->
                runCatching {
                    val left = face.minOf { it.x() }
                    val right = face.maxOf { it.x() }
                    val top = face.minOf { it.y() }
                    val bottom = face.maxOf { it.y() }
                    val marginX = (right - left) * 0.12f
                    val marginY = (bottom - top) * 0.18f
                    Region((left - marginX).coerceIn(0f, 1f), (top - marginY).coerceIn(0f, 1f),
                        (right + marginX).coerceIn(0f, 1f), (bottom + marginY).coerceIn(0f, 1f))
                }.getOrNull()
            }.sortedWith(compareBy({ it.left }, { it.top }))
        }
    }
}

private class SubjectView(context: Context, private val changed: () -> Unit) : View(context) {
    private var photo: Bitmap? = null
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var panX = 0f
    private var panY = 0f
    private var startX = 0f
    private var startY = 0f
    private var multipleTouches = false
    private var moving = false
    private var previousSelection: Region? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    var candidates = emptyList<Region>()
        private set
    var selection: Region? = null
    var manual = false
    val hasPhoto get() = photo != null

    private val zoom = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale
            scale = (scale * detector.scaleFactor).coerceIn(1f, 6f)
            val ratio = scale / old
            offsetX = (offsetX + width / 2f - detector.focusX) * ratio - width / 2f + detector.focusX
            offsetY = (offsetY + height / 2f - detector.focusY) * ratio - height / 2f + detector.focusY
            invalidate()
            return true
        }
    })

    fun swap(bitmap: Bitmap?, outlines: List<Region>) {
        photo?.recycle()
        photo = bitmap
        candidates = outlines
        selection = null
        manual = false
        resetZoom()
    }

    fun resetZoom() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(26, 30, 36))
        val bitmap = photo ?: return
        val fit = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * scale
        val w = bitmap.width * fit
        val h = bitmap.height * fit
        bounds.set((width - w) / 2 + offsetX, (height - h) / 2 + offsetY,
            (width + w) / 2 + offsetX, (height + h) / 2 + offsetY)
        canvas.drawBitmap(bitmap, null, bounds, paint)
        for ((i, region) in candidates.withIndex()) drawRegion(canvas, region, Color.CYAN, i + 1)
        selection?.let { drawRegion(canvas, it, Color.YELLOW, null) }
    }

    private fun drawRegion(canvas: Canvas, region: Region, color: Int, number: Int?) {
        val left = bounds.left + bounds.width() * region.left
        val top = bounds.top + bounds.height() * region.top
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (number == null) 5f else 3f
        canvas.drawRect(left, top, bounds.left + bounds.width() * region.right,
            bounds.top + bounds.height() * region.bottom, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 28f
        number?.let { canvas.drawText(it.toString(), left + 4f, top + 28f, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (photo == null || bounds.isEmpty) return false
        zoom.onTouchEvent(event)
        val x = ((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        val y = ((event.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                multipleTouches = false
                moving = false
                previousSelection = selection
                startX = x
                startY = y
                panX = event.x
                panY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> multipleTouches = true
            MotionEvent.ACTION_MOVE -> if (!multipleTouches) {
                moving = moving || kotlin.math.abs(event.x - panX) > 10 || kotlin.math.abs(event.y - panY) > 10
                if (manual) {
                    val draft = runCatching { Region(minOf(x, startX), minOf(y, startY), maxOf(x, startX), maxOf(y, startY)) }.getOrNull()
                    if (draft != null) selection = draft
                } else if (moving && scale > 1f) {
                    offsetX += event.x - panX
                    offsetY += event.y - panY
                    panX = event.x
                    panY = event.y
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!multipleTouches && !moving && !manual && bounds.contains(event.x, event.y)) {
                    val hit = candidates.filter { it.contains(x, y) }
                    if (hit.size == 1) selection = hit.first()
                    performClick()
                }
                changed()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                selection = previousSelection
                changed()
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
