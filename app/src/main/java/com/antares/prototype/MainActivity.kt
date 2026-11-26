package com.antares.prototype

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.view.View
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private val db by lazy { ProjectStore(applicationContext) }
    private val settings by lazy { getSharedPreferences("workspace", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var projectId = 1L
    private var photoId = 0L
    private var pendingResult = 0L
    private var lastUi: List<Any> = emptyList()

    private lateinit var projectLabel: TextView
    private lateinit var photoLabel: TextView
    private lateinit var jobsLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var saveJson: Button
    private lateinit var saveCsv: Button
    private lateinit var savePng: Button
    private lateinit var retry: Button

    private val refresh = object : Runnable {
        override fun run() {
            guarded { redraw() }
            handler.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectId = savedInstanceState?.getLong("project") ?: settings.getLong("project", 1L)
        photoId = savedInstanceState?.getLong("photo") ?: settings.getLong("photo", 0L)
        pendingResult = savedInstanceState?.getLong("pending") ?: settings.getLong("pending", 0L)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        screen.addView(TextView(this).apply { text = "ANTARES"; textSize = 24f; setPadding(20, 8, 20, 4) })
        Workflow.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            screen.addView(row)
            pair.forEach { workflow ->
                row.addView(Button(this).apply {
                    text = workflow.title
                    isAllCaps = false
                    setOnClickListener { guarded { launchWorkflow(workflow) } }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        scroll.addView(body)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(screen)
        projectLabel = text(body, "", 20f)
        action(body, "Choose project") { chooseProject() }
        action(body, "New project") { newProject() }
        action(body, "Add photos") { pickPhotos() }
        photoLabel = text(body, "", 17f)
        action(body, "Choose imported photo") { choosePhoto() }
        action(body, "Combine confirmed scans for export") { chooseScansForBatch() }
        saveJson = action(body, "Save As JSON") { save(JSON) }
        saveCsv = action(body, "Save As CSV") { save(CSV) }
        savePng = action(body, "Save As PNG") { save(PNG) }
        action(body, "Preferred export folder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), FOLDER)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = "Processing in progress"
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        jobsLabel = text(body, "", 15f)
        action(body, "Open saved file") { openExport() }
        retry = action(body, "Retry failed job") { retryJob() }
        action(body, "System health") { showHealth() }
    }

    override fun onStart() {
        super.onStart()
        guarded { if (db.next() != null) ProcessingService.schedule(this) }
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("project", projectId)
        outState.putLong("photo", photoId)
        outState.putLong("pending", pendingResult)
        super.onSaveInstanceState(outState)
    }

    private fun redraw() {
        val project = db.projects().firstOrNull { it.id == projectId } ?: db.projects().first().also {
            projectId = it.id
            photoId = 0L
        }
        val latest = db.recent(project.id)
        val chosen = db.task(photoId)?.takeIf {
            it.projectId == project.id && it.kind == "import" && it.status == "done"
        } ?: latest.firstOrNull { it.kind == "import" && it.status == "done" }
        if (chosen != null && photoId != chosen.id) {
            photoId = chosen.id
            rememberSelection()
        }
        val queued = db.queuedCount(project.id)
        val key = listOf(project, latest, photoId, queued)
        if (lastUi == key) return
        lastUi = key
        projectLabel.text = project.name
        photoLabel.text = chosen?.let { "${it.label}\n${it.imageAlias} · ${it.width} × ${it.height}" }
            ?: "Add photos to get started."
        val hasScan = result("scan") != null || db.latestBatch(projectId) != null
        saveJson.isEnabled = hasScan
        saveCsv.isEnabled = hasScan
        savePng.isEnabled = result("png") != null
        retry.isEnabled = latest.any { it.status == "failed" }
        progress.visibility = if (queued > 0) View.VISIBLE else View.GONE
        val heading = if (queued > 0) "Active and queued jobs · $queued" else "Recent work"
        jobsLabel.text = latest.take(8).joinToString("\n\n", prefix = "$heading\n\n") {
            "${it.label}\n${status(it)}"
        }.trimEnd()
    }

    private fun status(task: ProjectStore.Task): String = when (task.status) {
        "queued" -> "Queued or processing…"
        "failed" -> "Failed. ${task.detail}"
        else -> task.detail
    }

    private fun chooseProject() {
        val projects = db.projects()
        AlertDialog.Builder(this).setTitle("Choose project")
            .setItems(projects.map { it.name }.toTypedArray()) { _, index ->
                selectProject(projects[index].id)
            }.show()
    }

    private fun selectProject(id: Long) {
        projectId = id
        photoId = 0L
        rememberSelection()
        redraw()
    }

    private fun newProject() {
        val input = EditText(this).apply { hint = "Project name"; isSingleLine = true }
        val alert = AlertDialog.Builder(this).setTitle("New project").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        alert.setOnShowListener {
            alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    selectProject(db.addProject(input.text.toString()))
                    alert.dismiss()
                } catch (_: SQLiteConstraintException) {
                    input.error = "A project with this name already exists."
                } catch (_: IllegalArgumentException) {
                    input.error = "Enter a name with 1 to 80 characters."
                }
            }
        }
        alert.show()
    }

    private fun choosePhoto() {
        val photos = db.photos(projectId)
        if (photos.isEmpty()) {
            message("Add photos first.")
            return
        }
        AlertDialog.Builder(this).setTitle("Choose imported photo")
            .setItems(photos.map { "${it.imageAlias} · ${it.label}" }.toTypedArray()) { _, index ->
                photoId = photos[index].id
                rememberSelection()
                redraw()
            }.show()
    }

    private fun rememberSelection() {
        settings.edit().putLong("project", projectId).putLong("photo", photoId).apply()
    }

    private fun pickPhotos() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, IMPORT)
    }

    private fun launchWorkflow(workflow: Workflow) {
        startActivityForResult(Intent(this, WorkflowActivity::class.java)
            .putExtra("workflow", workflow.name).putExtra("project", projectId)
            .putExtra("photo", photoId), WORKFLOW)
    }

    private fun result(kind: String): ProjectStore.Task? {
        if (photoId == 0L) return null
        return db.result(projectId, kind, photoId)
    }

    private fun chooseScansForBatch() {
        val scans = db.confirmedScans(projectId)
        if (scans.isEmpty()) {
            message("Confirm subjects and complete Deep Scan first.")
            return
        }
        val checked = BooleanArray(scans.size)
        AlertDialog.Builder(this).setTitle("Choose scans to group for export")
            .setMultiChoiceItems(scans.map { scan ->
                val photo = scan.parent?.let(db::task)
                "${photo?.label ?: "Photo"} · result ${scan.id}"
            }.toTypedArray(), checked) { _, index, enabled -> checked[index] = enabled }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Group scans") { _, _ ->
                guarded {
                    db.queueConfirmedBatch(projectId, scans.filterIndexed { index, _ -> checked[index] }.map { it.id })
                    kick()
                }
            }.show()
    }

    private fun save(format: Int) {
        val perPhoto = if (photoId == 0L) emptyList() else
            db.completedResults(projectId, if (format == PNG) "png" else "scan", photoId)
        val available = if (format == PNG) perPhoto else perPhoto + db.projectBatches(projectId)
        if (available.isEmpty()) return
        if (available.size == 1) {
            openSavePicker(format, available.single())
            return
        }
        AlertDialog.Builder(this).setTitle("Choose result to export")
            .setItems(available.map { "${it.label} · result ${it.id}" }.toTypedArray()) { _, index ->
                guarded { openSavePicker(format, available[index]) }
            }.show()
    }

    private fun openSavePicker(format: Int, job: ProjectStore.Task) {
        val extension = when (format) { CSV -> "CSV"; PNG -> "PNG"; else -> "JSN" }
        val mime = when (format) { CSV -> "text/csv"; PNG -> "image/png"; else -> "application/json" }
        val suggested = exportName("ANT", job.id, extension)
        // JSON uses the explicit .json extension. CSV and PNG have three-letter extensions.
        val filename = if (format == JSON) suggested.substringBefore('.') + ".json"
            else suggested.substringBefore('.') + "." + extension.lowercase(Locale.ROOT)
        pendingResult = job.id
        settings.edit().putLong("pending", pendingResult).commit()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, filename)
            val folder = settings.getString("exportFolder", null)
            if (folder != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(folder))
        }, format)
    }

    private fun kick() {
        ProcessingService.schedule(this)
        redraw()
    }

    @Deprecated("Activity result callback for platform pickers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        guarded {
            when (requestCode) {
                WORKFLOW -> {
                    photoId = data.getLongExtra("photo", photoId)
                    rememberSelection()
                    redraw()
                }
                IMPORT -> {
                    val uris = data.clipData?.let { clip ->
                        List(clip.itemCount) { clip.getItemAt(it).uri }
                    } ?: listOfNotNull(data.data)
                    for (uri in uris.distinct()) {
                        try {
                            keepAccess(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            db.queue(projectId, "import", uri.toString(), documentName(this, uri))
                        } catch (_: SecurityException) {
                            db.fail(db.queue(projectId, "import", uri.toString(), "Import photo"),
                                "Android could not retain access to this photo. Select it through a different Files provider.")
                        }
                    }
                    if (uris.isNotEmpty()) kick()
                }
                FOLDER -> {
                    val folder = data.data ?: return@guarded
                    keepAccess(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val initial = DocumentsContract.buildDocumentUriUsingTree(
                        folder, DocumentsContract.getTreeDocumentId(folder)
                    )
                    settings.edit().putString("exportFolder", initial.toString()).apply()
                    message("Preferred folder saved.")
                }
                JSON, CSV, PNG -> {
                    val destination = data.data ?: return@guarded
                    val target = db.task(pendingResult)?.takeIf {
                        it.status == "done" &&
                            (if (requestCode == PNG) it.kind == "png" else it.kind in setOf("scan", "batch"))
                    } ?: error("The result is unavailable. Open the photo and use Save As again.")
                    val kind = when (requestCode) { CSV -> "csv"; PNG -> "save_png"; else -> "json" }
                    val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    keepAccess(destination, access)
                    db.queue(target.projectId, kind, destination.toString(), "Save ${kind.substringAfter('_').uppercase(Locale.ROOT)}", target.id)
                    pendingResult = 0L
                    settings.edit().remove("pending").apply()
                    kick()
                }
            }
        }
    }

    private fun openExport() {
        val exports = db.recent(projectId).filter {
            it.status == "done" && it.kind in setOf("csv", "json", "save_png")
        }
        if (exports.isEmpty()) {
            message("Save a result first.")
            return
        }
        AlertDialog.Builder(this).setTitle("Open saved file")
            .setItems(exports.map { it.detail.substringBefore('\n') }.toTypedArray()) { _, index ->
                guarded {
                    val export = exports[index]
                    val mime = when (export.kind) {
                        "csv" -> "text/csv"
                        "json" -> "application/json"
                        else -> "image/png"
                    }
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(export.input), mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }.show()
    }

    private fun keepAccess(uri: Uri, flags: Int) {
        require(uri.scheme == "content") { "Choose a document through Android Files." }
        contentResolver.takePersistableUriPermission(uri, flags)
    }

    private fun retryJob() {
        val jobs = db.recent(projectId).filter { it.status == "failed" }
        if (jobs.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Retry failed job")
            .setItems(jobs.map { "${it.label} · ${it.detail}" }.toTypedArray()) { _, index ->
                guarded { db.retry(jobs[index].id); kick() }
            }.show()
    }

    private fun showHealth() {
        val modelAvailable = runCatching { assets.open("face_landmarker.task").use { it.read() >= 0 } }
            .getOrDefault(false)
        val details = buildString {
            appendLine("Projects and jobs: available")
            appendLine(if (modelAvailable) "Offline facial model: bundled" else "Offline facial model: unavailable")
            appendLine("Manual subject selection and four independent workflows: available")
            append("Body mapping, tattoo verification and full restoration: pending")
        }
        AlertDialog.Builder(this).setTitle("System health").setMessage(details)
            .setPositiveButton("OK", null).show()
    }

    private fun text(parent: LinearLayout, copy: String, size: Float): TextView =
        TextView(this).apply {
            text = copy
            textSize = size
            setTextColor(Color.rgb(30, 37, 46))
            setPadding(0, 10, 0, 10)
            parent.addView(this)
        }

    private fun action(parent: LinearLayout, copy: String, run: () -> Unit): Button =
        Button(this).apply {
            text = copy
            isAllCaps = false
            setOnClickListener { guarded(run) }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    private fun guarded(run: () -> Unit) {
        try { run() } catch (error: Exception) {
            message(error.message ?: "Something went wrong. Try again.")
        }
    }

    private fun message(copy: String) = Toast.makeText(this, copy, Toast.LENGTH_LONG).show()

    private companion object {
        const val IMPORT = 10
        const val FOLDER = 11
        const val WORKFLOW = 12
        const val JSON = 20
        const val CSV = 21
        const val PNG = 22
    }
}
