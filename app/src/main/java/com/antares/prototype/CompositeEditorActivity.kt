package com.antares.prototype

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.util.ArrayDeque
import java.util.concurrent.Executors

class CompositeEditorActivity : Activity() {
    private val store by lazy { ProjectStore(applicationContext) }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var request: WorkflowRequest
    private lateinit var design: CompositeDesign
    private lateinit var renderer: CompositeRenderer
    private lateinit var preview: ImageView
    private lateinit var backgroundPicker: Spinner
    private lateinit var status: TextView
    private lateinit var build: Button
    private lateinit var controls: LinearLayout
    private var bitmap: Bitmap? = null
    private var activeLayer = 0
    private var token = 0
    private var backgroundTask = 0L
    private var project = 0L
    private var syncing = false
    private val undo = ArrayDeque<ForegroundStyle>()
    private val redo = ArrayDeque<ForegroundStyle>()
    private val pending = Runnable { render() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        project = intent.getLongExtra("project", 0)
        val subject = store.projects().firstOrNull { it.id == project } ?: run { finish(); return }
        request = WorkflowRequest.decode(state?.getString("request") ?: intent.getStringExtra("request") ?: run { finish(); return })
        design = requireNotNull(request.composite)
        backgroundTask = state?.getLong("backgroundTask") ?: 0L
        renderer = CompositeRenderer(applicationContext, project, 640)
        val frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        frame.addView(TextView(this).apply { text = "SUBJECT · ${subject.name}"; textSize = 17f; setPadding(20, 10, 20, 10) })
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 32) }
        scroll.addView(body)
        frame.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(frame)
        label(body, if (intent.getBooleanExtra("isolate", false)) "Isolate Subject" else "Build Composite Image", 24f)
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(83, 87, 94))
            contentDescription = "Live composite preview"
        }
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(320)))
        status = label(body, "Preparing cutout…")
        backgroundPicker = picker(body, BackgroundMode.entries.map { it.label }, design.mode.ordinal) {
            if (!syncing) {
                design = design.copy(mode = BackgroundMode.entries[it])
                schedule()
            }
        }
        button(body, "Choose background color") { colorDialog() }
        button(body, "Import custom background image") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, PICK_BACKGROUND)
        }
        button(body, "Choose imported background") {
            val photos = store.photos(project)
            AlertDialog.Builder(this).setTitle("Background image")
                .setItems(photos.map { it.label }.toTypedArray()) { _, index ->
                    design = design.copy(mode = BackgroundMode.CUSTOM, backgroundPhoto = photos[index].id)
                    backgroundPicker.setSelection(design.mode.ordinal)
                    schedule()
                }.show()
        }
        picker(body, listOf("Square 1024 × 1024", "Landscape 1280 × 720", "Portrait 720 × 1280", "High resolution 2048 × 2048"),
            when { design.width == 1280 -> 1; design.height == 1280 -> 2; design.width == 2048 -> 3; else -> 0 }) {
            val sizes = listOf(1024 to 1024, 1280 to 720, 720 to 1280, 2048 to 2048)
            design = design.copy(width = sizes[it].first, height = sizes[it].second)
            schedule()
        }
        slider(body, "Background horizontal position", (design.backgroundX * 100).toInt()) { design = design.copy(backgroundX = it / 100f); schedule() }
        slider(body, "Background vertical position", (design.backgroundY * 100).toInt()) { design = design.copy(backgroundY = it / 100f); schedule() }
        slider(body, "Background zoom", ((design.backgroundScale - 1f) * 100).toInt()) { design = design.copy(backgroundScale = 1f + it / 100f); schedule() }
        if (request.references.size > 1) picker(body, request.references.mapIndexed { i, reference ->
            "Layer ${i + 1} · ${store.task(reference.photoId)?.label.orEmpty()}"
        }, activeLayer) {
            activeLayer = it
            undo.clear()
            redo.clear()
            fillControls()
        }
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(controls)
        fillControls()
        build = button(body, "Confirm cutouts and render PNG") {
            build.isEnabled = false
            try {
                val checked = request.copy(composite = design.copy(reviewed = true))
                store.queue(project, "png", checked.encode(), if (intent.getBooleanExtra("isolate", false)) "Isolate Subject" else "Build Composite Image",
                    request.references.first().photoId)
                getSharedPreferences("workspace", MODE_PRIVATE).edit().putString("background:$project", design.mode.name).apply()
                ProcessingService.schedule(this)
                setResult(RESULT_OK, Intent().putExtra("photo", request.references.first().photoId))
                finish()
            } catch (error: Exception) {
                status.text = error.message
                build.isEnabled = true
            }
        }
        button(body, "Cancel") { finish() }
        build.isEnabled = false
        if (backgroundTask > 0) waitForBackground()
        schedule()
    }

    private fun fillControls() {
        if (!::controls.isInitialized) return
        syncing = true
        controls.removeAllViews()
        val style = design.layers[activeLayer]
        slider(controls, "Subject horizontal position", (style.x * 100).toInt()) { change { s -> s.copy(x = it / 100f) } }
        slider(controls, "Subject vertical position", (style.y * 100).toInt()) { change { s -> s.copy(y = it / 100f) } }
        slider(controls, "Subject scale", ((style.scale - 0.15f) / 1.85f * 100).toInt()) { change { s -> s.copy(scale = 0.15f + it / 100f * 1.85f) } }
        slider(controls, "Edge feathering", style.edge) { change { s -> s.copy(edge = it, reusedMask = null) } }
        slider(controls, "Subject lighting", style.light) { change { s -> s.copy(light = it) } }
        slider(controls, "Subject warmth", style.warmth) { change { s -> s.copy(warmth = it) } }
        button(controls, "Retarget cutout") { maskDialog(0) }
        button(controls, "Add mask brush") { maskDialog(1) }
        button(controls, "Remove mask brush") { maskDialog(2) }
        button(controls, "Start a manual mask") { change(true) { it.copy(manualOnly = true, brushes = emptyList(), outline = emptyList(), reusedMask = null) }; maskDialog(1) }
        button(controls, "Undo mask change") {
            if (undo.isNotEmpty()) { redo.addLast(design.layers[activeLayer]); setLayer(undo.removeLast()) }
        }
        button(controls, "Redo mask change") {
            if (redo.isNotEmpty()) { undo.addLast(design.layers[activeLayer]); setLayer(redo.removeLast()) }
        }
        syncing = false
    }

    private fun change(record: Boolean = false, transform: (ForegroundStyle) -> ForegroundStyle) {
        val current = design.layers[activeLayer]
        if (record) { undo.addLast(current); redo.clear() }
        setLayer(transform(current))
    }

    private fun setLayer(style: ForegroundStyle) {
        design = design.copy(layers = design.layers.toMutableList().apply { set(activeLayer, style) })
        schedule()
    }

    private fun maskDialog(mode: Int) {
        val ref = request.references[activeLayer]
        val layer = activeLayer
        executor.execute {
            val crop = runCatching { renderer.crop(ref) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || crop == null || layer != activeLayer) return@runOnUiThread
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(crop)
                    adjustViewBounds = true
                }
                val points = mutableListOf<CompositePoint>()
                image.setOnTouchListener { view, event ->
                    if (event.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                        val map = android.graphics.Matrix()
                        if (image.imageMatrix.invert(map)) {
                            val xy = floatArrayOf(event.x, event.y)
                            map.mapPoints(xy)
                            if (xy[0] in 0f..crop.width.toFloat() && xy[1] in 0f..crop.height.toFloat() && points.size < 256) {
                                points.add(CompositePoint(xy[0] / crop.width, xy[1] / crop.height))
                            }
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    }
                    true
                }
                AlertDialog.Builder(this).setTitle(if (mode == 0) "Tap inside the intended subject" else "Paint the region to change")
                    .setView(image).setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                        if (points.isEmpty()) return@setPositiveButton
                        change(true) { style ->
                            if (mode == 0) style.copy(seed = points.last(), manualOnly = false, brushes = emptyList(), outline = emptyList(), reusedMask = null)
                            else style.copy(brushes = (style.brushes + MaskBrush(mode == 1, 0.04f, points.toList())).takeLast(100))
                        }
                    }.show()
            }
        }
    }

    private fun colorDialog() {
        val input = EditText(this).apply {
            isSingleLine = true
            setText(String.format("#%06X", (if (design.mode == BackgroundMode.GREEN) design.green else design.solid) and 0xFFFFFF))
        }
        AlertDialog.Builder(this).setTitle("Background color · #RRGGBB").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                try {
                    val color = Color.parseColor(input.text.toString().trim())
                    design = if (design.mode == BackgroundMode.GREEN) design.copy(green = color) else design.copy(mode = BackgroundMode.SOLID, solid = color)
                    backgroundPicker.setSelection(design.mode.ordinal)
                    schedule()
                } catch (_: IllegalArgumentException) { status.text = "Enter a six-digit color such as #00FF00." }
            }.show()
    }

    private fun schedule() {
        handler.removeCallbacks(pending)
        token++
        if (::build.isInitialized) build.isEnabled = false
        handler.postDelayed(pending, 90)
    }

    private fun render() {
        val version = token
        val snapshot = design
        status.text = "Updating preview…"
        executor.execute {
            val rendered = runCatching { renderer.render(request.references, snapshot, preview = true) }
            runOnUiThread {
                if (version != token || isDestroyed) { rendered.getOrNull()?.recycle(); return@runOnUiThread }
                rendered.fold(onSuccess = {
                    preview.setImageDrawable(null)
                    bitmap?.recycle()
                    bitmap = it
                    preview.setImageBitmap(it)
                    status.text = "Inspect all cutouts. Correct incomplete or wrong regions before confirming."
                    build.isEnabled = true
                }, onFailure = {
                    preview.setImageDrawable(null)
                    status.text = "${it.message ?: "Cutout failed"} Retarget or manually correct the mask."
                    build.isEnabled = false
                })
            }
        }
    }

    @Deprecated("Activity result callback for Android Files")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != PICK_BACKGROUND || result != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            backgroundTask = store.queue(project, "import", uri.toString(), documentName(this, uri))
            ProcessingService.schedule(this)
            waitForBackground()
        } catch (error: Exception) { status.text = error.message }
    }

    private fun waitForBackground() {
        if (isDestroyed || backgroundTask == 0L) return
        val job = store.task(backgroundTask) ?: return
        when (job.status) {
            "done" -> { design = design.copy(mode = BackgroundMode.CUSTOM, backgroundPhoto = job.id); backgroundTask = 0
                backgroundPicker.setSelection(design.mode.ordinal); schedule() }
            "failed" -> { status.text = job.detail; backgroundTask = 0 }
            else -> handler.postDelayed({ waitForBackground() }, 450)
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        if (::request.isInitialized) state.putString("request", request.copy(composite = design).encode())
        state.putLong("backgroundTask", backgroundTask)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        token++
        handler.removeCallbacksAndMessages(null)
        bitmap?.recycle()
        if (::renderer.isInitialized) executor.execute { renderer.close() }
        executor.shutdown()
        store.close()
        super.onDestroy()
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun label(parent: LinearLayout, copy: String, size: Float = 15f) = TextView(this).apply { text = copy; textSize = size; parent.addView(this) }
    private fun button(parent: LinearLayout, copy: String, click: () -> Unit) = Button(this).apply { text = copy; isAllCaps = false; setOnClickListener { click() }; parent.addView(this) }
    private fun slider(parent: LinearLayout, copy: String, value: Int, update: (Int) -> Unit) {
        label(parent, copy)
        parent.addView(SeekBar(this).apply {
            max = 100
            progress = value.coerceIn(0, 100)
            contentDescription = copy
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser && !syncing) update(progress) }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
    }

    private fun picker(parent: LinearLayout, options: List<String>, selected: Int, changed: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@CompositeEditorActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) { changed(position) }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
            }
            parent.addView(this)
        }

    private companion object { const val PICK_BACKGROUND = 31 }
}


internal fun chooseSavedComposite(activity: Activity, store: ProjectStore, project: Long, requestCode: Int) {
    val candidates = store.composites(project)
    if (candidates.isEmpty()) {
        AlertDialog.Builder(activity).setTitle("Saved isolations and composites").setMessage("No saved masks are available yet.")
            .setPositiveButton("OK", null).show()
        return
    }
    AlertDialog.Builder(activity).setTitle("Reopen isolation or composite")
        .setItems(candidates.map { "${it.label} · result ${it.id}" }.toTypedArray()) { _, index ->
            val selected = candidates[index]
            val request = WorkflowRequest.decode(selected.input)
            val design = requireNotNull(request.composite)
            val reusable = design.copy(layers = design.layers.mapIndexed { layer, style ->
                style.copy(reusedMask = selected.id, reusedLayer = layer, brushes = emptyList())
            }, reviewed = false)
            activity.startActivityForResult(Intent(activity, CompositeEditorActivity::class.java).putExtra("project", project)
                .putExtra("request", request.copy(composite = reusable).encode()), requestCode)
        }.show()
}
