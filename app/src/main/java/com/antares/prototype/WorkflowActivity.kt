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
import android.widget.EditText
import android.widget.ImageView
import android.widget.CheckBox
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
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
    private lateinit var ageYears: EditText
    private lateinit var ageBody: CheckBox
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
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        val subjectTitle = TextView(this).apply {
            textSize = 16f
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
        }
        screen.addView(subjectTitle)
        scroll.addView(root)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(screen)
        label(workflow.title, 25f)
        val activeSubject = store.projects().firstOrNull { it.id == projectId }
        if (activeSubject == null) {
            label("Select a subject on the main screen before processing.", 17f)
            button("Return to subjects") { finish() }
            return
        }
        subjectTitle.text = "SUBJECT · ${activeSubject.name}"
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
            Workflow.COSMETIC -> listOf("Hair tint", "Makeup tint", "Complexion softening", "Age Transformation")
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
        if (workflow == Workflow.COSMETIC) ageControls(state)
        confirm = button(if (workflow == Workflow.COMPOSITE) "Confirm and add reference" else "Confirm subject and start") {
            confirmSelection()
        }
        if (workflow == Workflow.REPAIR || workflow == Workflow.COMPOSITE) {
            button("Isolate Subject") {
                val selected = preview.selection
                if (processing || selected == null || !isolatedRegion(selected, preview.candidates)) {
                    alert("Select one subject", "Choose or draw a region containing the intended subject before isolating.")
                } else {
                    val reference = SubjectReference(photoId, selected)
                    AlertDialog.Builder(this).setTitle("Confirm subject for isolation")
                        .setMessage("Inspect the region and confirm it contains the intended subject.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Isolate Subject") { _, _ -> openComposite(listOf(reference), true) }.show()
                }
            }
        }
        if (workflow == Workflow.COMPOSITE) {
            button("Use saved isolation or composite") { chooseSavedComposite(this, store, projectId, 21) }
            button("Review confirmed references") { reviewReferences() }
            button("Create composite") { finishComposite() }
        }
        button("Cancel workflow") { finish() }
        updateStatus()
        if (photoId != 0L && references.none { it.photoId == photoId }) loadPhoto(photoId)
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putLong("photo", photoId)
        if (!::preview.isInitialized) return
        state.putString("selected", preview.selection?.json()?.toString())
        state.putString("references", JSONArray(references.map { it.json() }).toString())
        state.putInt("mode", choices.checkedRadioButtonId)
        state.putInt("amount", amount.progress)
        if (::ageYears.isInitialized) {
            state.putString("ageYears", ageYears.text.toString())
            state.putBoolean("ageBody", ageBody.isChecked)
        }
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
                outlines = try {
                    val faces = detectSubjects(this, bitmap)
                    if (workflow == Workflow.COMPOSITE || workflow == Workflow.REPAIR) {
                        val bodies = detectPeople(this, bitmap)
                        bodies + faces.filter { face ->
                            bodies.none { body -> body.contains((face.left + face.right) / 2f, (face.top + face.bottom) / 2f) }
                        }
                    } else faces
                } catch (_: Exception) {
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
        if (references.isEmpty()) {
            alert("Choose a reference", "Confirm the intended subject separately in each photograph.")
            return
        }
        AlertDialog.Builder(this).setTitle("Confirm composite references")
            .setMessage(references.joinToString("\n") {
                store.task(it.photoId)?.label ?: "Photo ${it.photoId}"
            })
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Open composite editor") { _, _ -> openComposite(references.toList(), false) }.show()
    }

    private fun submit(refs: List<SubjectReference>) {
        try {
            val age = if (workflow == Workflow.COSMETIC && choices.checkedRadioButtonId == 4) currentAge() else null
            val request = WorkflowRequest(workflow, refs, (choices.checkedRadioButtonId - 1).coerceIn(0, 2),
                amount.progress, age = age).encode()
            store.queue(projectId, if (workflow == Workflow.SCAN) "scan" else "png", request, if (age == null) workflow.title else "Simulated Age Transformation", refs.first().photoId)
            ProcessingService.schedule(this)
            setResult(RESULT_OK, Intent().putExtra("photo", refs.first().photoId))
            finish()
        } catch (error: Exception) { alert("Unable to queue job", error.message.orEmpty()) }
    }

    private fun openComposite(refs: List<SubjectReference>, isolation: Boolean) {
        val settings = (choices.checkedRadioButtonId - 1).coerceIn(0, 1)
        val stored = getSharedPreferences("workspace", MODE_PRIVATE).getString("background:$projectId", null)
        val background = stored?.let { runCatching { BackgroundMode.valueOf(it) }.getOrNull() } ?: BackgroundMode.GREEN
        val layers = refs.indices.map { index ->
            val position = (index + 0.5f) / refs.size
            ForegroundStyle(if (settings == 0) position else 0.5f,
                if (settings == 1) position else 0.5f, scale = if (refs.size == 1) 0.85f else 0.72f / refs.size)
        }
        val request = WorkflowRequest(Workflow.COMPOSITE, refs, settings, 50,
            composite = CompositeDesign(mode = background, layers = layers))
        startActivityForResult(Intent(this, CompositeEditorActivity::class.java).putExtra("project", projectId)
            .putExtra("request", request.encode()).putExtra("isolate", isolation), 21)
    }

    @Deprecated("Composite editor result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 21 && result == RESULT_OK) {
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private fun currentAge(): AgeDesign {
        val years = ageYears.text.toString().trim().toIntOrNull()
            ?: error("Enter a signed year adjustment between -40 and +40.")
        require(years in -40..40) { "Enter a signed year adjustment between -40 and +40." }
        return AgeDesign(years, amount.progress, if (ageBody.isChecked) AgeScope.FACE_AND_VISIBLE_BODY else AgeScope.FACE)
    }

    private fun ageControls(state: Bundle?) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(panel)
        panel.addView(TextView(this).apply {
            text = "Simulated appearance · negative years for regression, positive years for progression."
        })
        ageYears = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "Years · −40 to +40"
            setText(state?.getString("ageYears") ?: "0")
        }
        panel.addView(ageYears)
        val bar = SeekBar(this).apply { max = 80; progress = ((ageYears.text.toString().toIntOrNull() ?: 0) + 40).coerceIn(0, 80) }
        bar.contentDescription = "Simulated age adjustment in years"
        panel.addView(bar)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) ageYears.setText((value - 40).toString())
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        ageYears.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                s?.toString()?.toIntOrNull()?.takeIf { it in -40..40 }?.let { bar.progress = it + 40 }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        ageBody = CheckBox(this).apply {
            text = "Apply to face and visible body within the selected region"
            isChecked = state?.getBoolean("ageBody") ?: false
        }
        panel.addView(ageBody)
        button("Compare before and after", panel) { previewAge() }
        panel.visibility = if (choices.checkedRadioButtonId == 4) View.VISIBLE else View.GONE
        choices.setOnCheckedChangeListener { _, checked -> panel.visibility = if (checked == 4) View.VISIBLE else View.GONE }
    }

    private fun previewAge() {
        val selected = preview.selection
        val photo = preview.copyPhoto()
        if (selected == null || photo == null || !isolatedRegion(selected, preview.candidates)) {
            photo?.recycle(); alert("Select one subject", "Choose and inspect the intended region first."); return
        }
        val age = try { currentAge() } catch (error: Exception) {
            photo.recycle(); alert("Age adjustment", error.message.orEmpty()); return
        }
        val reference = SubjectReference(photoId, selected)
        val request = WorkflowRequest(workflow, listOf(reference), 0, age.strength, age = age)
        decoder.execute {
            val adjusted = runCatching { applyAppearance(photo, ageRegion(this, photo, selected, age), request) }
            runOnUiThread {
                if (isDestroyed) {
                    photo.recycle(); adjusted.getOrNull()?.recycle(); return@runOnUiThread
                }
                val after = adjusted.getOrElse {
                    photo.recycle(); alert("Preview unavailable", it.message.orEmpty()); return@runOnUiThread
                }
                var showAfter = true
                val comparison = ImageView(this).apply { adjustViewBounds = true; setImageBitmap(after) }
                val dialog = AlertDialog.Builder(this).setTitle("After · simulated appearance").setView(comparison)
                    .setNegativeButton("Close", null).setPositiveButton("Before", null).create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        showAfter = !showAfter
                        comparison.setImageBitmap(if (showAfter) after else photo)
                        dialog.setTitle(if (showAfter) "After · simulated appearance" else "Before · source")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = if (showAfter) "Before" else "After"
                    }
                }
                dialog.setOnDismissListener { comparison.setImageDrawable(null); photo.recycle(); after.recycle() }
                dialog.show()
            }
        }
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

internal fun detectPeople(context: Context, bitmap: Bitmap): List<Region> {
    val options = ObjectDetector.ObjectDetectorOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
        .setCategoryAllowlist(listOf("person")).setMaxResults(24).setScoreThreshold(0.37f).build()
    return ObjectDetector.createFromOptions(context, options).use { detector ->
        val inferenceInput = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        BitmapImageBuilder(inferenceInput).build().use { image ->
            detector.detect(image).detections().mapNotNull {
                val box = it.boundingBox()
                runCatching {
                    Region((box.left / bitmap.width).coerceIn(0f, 1f), (box.top / bitmap.height).coerceIn(0f, 1f),
                        (box.right / bitmap.width).coerceIn(0f, 1f), (box.bottom / bitmap.height).coerceIn(0f, 1f))
                }.getOrNull()
            }.sortedWith(compareBy({ it.left }, { it.top }))
        }
    }
}

internal fun ageRegion(context: Context, photo: Bitmap, region: Region, age: AgeDesign): Region {
    if (age.scope == AgeScope.FACE_AND_VISIBLE_BODY || age.years == 0 || age.strength == 0) return region
    val area = region.pixels(photo.width, photo.height)
    val crop = Bitmap.createBitmap(photo, area.left, area.top, area.width(), area.height())
    val candidates = try { detectSubjects(context, crop) } finally { if (crop !== photo) crop.recycle() }
    require(candidates.size == 1) { "Face-only age simulation needs exactly one visible face in the selected region." }
    val face = candidates.single()
    fun x(value: Float) = ((area.left + value * area.width()) / photo.width).coerceIn(0f, 1f)
    fun y(value: Float) = ((area.top + value * area.height()) / photo.height).coerceIn(0f, 1f)
    return Region(x(face.left), y(face.top), x(face.right), y(face.bottom))
}

internal fun detectSubjects(context: Context, bitmap: Bitmap): List<Region> {
    val options = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
        .setNumFaces(16).build()
    val proposals = mutableListOf<Region>()
    FaceLandmarker.createFromOptions(context, options).use { detector ->
        fun scan(window: Region) {
            val area = window.pixels(bitmap.width, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, area.left, area.top, area.width(), area.height())
            try {
                BitmapImageBuilder(crop).build().use { image ->
                    for (face in detector.detect(image).faceLandmarks()) {
                        val left = face.minOf { it.x() }
                        val right = face.maxOf { it.x() }
                        val top = face.minOf { it.y() }
                        val bottom = face.maxOf { it.y() }
                        val marginX = (right - left) * 0.12f
                        val marginY = (bottom - top) * 0.18f
                        fun x(point: Float) = ((area.left + point * area.width()) / bitmap.width).coerceIn(0f, 1f)
                        fun y(point: Float) = ((area.top + point * area.height()) / bitmap.height).coerceIn(0f, 1f)
                        val box = runCatching {
                            Region(x(left - marginX), y(top - marginY), x(right + marginX), y(bottom + marginY))
                        }.getOrNull()
                        if (box != null && proposals.none { it.overlapRatio(box) >= 0.35f }) proposals.add(box)
                    }
                }
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
        }

        scan(Region(0f, 0f, 1f, 1f))
        if (maxOf(bitmap.width, bitmap.height) >= 384) {
            for (grid in listOf(2, 4)) {
                val overlap = 0.12f / grid
                for (row in 0 until grid) for (column in 0 until grid) {
                    scan(Region(
                        (column.toFloat() / grid - overlap).coerceAtLeast(0f),
                        (row.toFloat() / grid - overlap).coerceAtLeast(0f),
                        ((column + 1f) / grid + overlap).coerceAtMost(1f),
                        ((row + 1f) / grid + overlap).coerceAtMost(1f)
                    ))
                }
            }
        }
    }
    return proposals.sortedWith(compareBy({ it.left }, { it.top }))
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
    fun copyPhoto() = photo?.copy(Bitmap.Config.ARGB_8888, false)

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
