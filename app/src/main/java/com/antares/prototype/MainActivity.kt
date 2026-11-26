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
import android.widget.ImageView
import android.widget.PopupMenu
import java.util.concurrent.Executors
import kotlin.concurrent.withLock
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
    private var projectId = 0L
    private var photoId = 0L
    private var pendingResult = 0L
    private var lastUi: List<Any> = emptyList()

    private lateinit var projectLabel: Button
    private lateinit var filesMenu: Button
    private lateinit var thumbnail: ImageView
    private val background = Executors.newSingleThreadExecutor()
    private var renderedPhoto = 0L
    private var picture: android.graphics.Bitmap? = null
    private var archiveBusy = false
    private var archiveSubject = 0L
    private var deleteAfterBackup = false
    private lateinit var photoLabel: TextView
    private lateinit var jobsLabel: TextView
    private lateinit var progress: ProgressBar

    private val refresh = object : Runnable {
        override fun run() {
            guarded { redraw() }
            handler.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectId = savedInstanceState?.getLong("project") ?: settings.getLong("project", 0L)
        photoId = savedInstanceState?.getLong("photo") ?: settings.getLong("photo", 0L)
        pendingResult = savedInstanceState?.getLong("pending") ?: settings.getLong("pending", 0L)
        archiveSubject = savedInstanceState?.getLong("archiveSubject") ?: settings.getLong("archiveSubject", 0L)
        deleteAfterBackup = savedInstanceState?.getBoolean("deleteAfterBackup") ?: settings.getBoolean("deleteAfterBackup", false)

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
        val subjectBar = LinearLayout(this)
        screen.addView(subjectBar)
        projectLabel = Button(this).apply {
            isAllCaps = false
            setOnClickListener { guarded { chooseProject() } }
        }
        subjectBar.addView(projectLabel, LinearLayout.LayoutParams(0, -2, 1f))
        subjectBar.addView(Button(this).apply {
            text = "•••"
            contentDescription = "SUBJECT Management"
            setOnClickListener { guarded { manageSubjects(this) } }
        }, LinearLayout.LayoutParams(-2, -2))
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
        photoLabel = text(body, "", 17f)
        thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Active subject photograph preview"
            setOnClickListener { guarded { choosePhoto() } }
        }
        body.addView(thumbnail, LinearLayout.LayoutParams(-1, (245 * resources.displayMetrics.density).toInt()))
        filesMenu = action(body, "Photos and files") { openFilesMenu() }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = "Processing in progress"
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        jobsLabel = text(body, "", 15f)
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
        background.shutdown()
        picture?.recycle()
        db.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("project", projectId)
        outState.putLong("photo", photoId)
        outState.putLong("pending", pendingResult)
        outState.putLong("archiveSubject", archiveSubject)
        outState.putBoolean("deleteAfterBackup", deleteAfterBackup)
        super.onSaveInstanceState(outState)
    }

    private fun redraw() {
        val project = db.projects().firstOrNull { it.id == projectId }
        if (project == null) {
            projectId = 0
            photoId = 0
            projectLabel.text = "Active SUBJECT ▾"
            projectLabel.contentDescription = "Active SUBJECT, none selected"
            photoLabel.text = "Select or create a subject."
            filesMenu.isEnabled = false
            showPreview(null)
            if (!archiveBusy) {
                progress.visibility = View.GONE
                jobsLabel.text = ""
            }
            return
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
        val key = listOf(project, latest, photoId, queued, archiveBusy)
        if (lastUi == key) return
        lastUi = key
        projectLabel.text = "SUBJECT · ${project.name}  ▾"
        projectLabel.contentDescription = "Active SUBJECT ${project.name}"
        photoLabel.text = chosen?.let { "${it.label} · ${it.imageAlias}" } ?: "Add reference photos."
        filesMenu.isEnabled = true
        showPreview(chosen)
        progress.visibility = if (queued > 0 || archiveBusy) View.VISIBLE else View.GONE
        if (!archiveBusy) {
            jobsLabel.text = when {
                queued > 0 -> "Working · $queued active or queued"
                latest.isNotEmpty() -> status(latest.first()).substringBefore('\n')
                else -> "Ready"
            }
        }
    }

    private fun showPreview(photo: ProjectStore.Task?) {
        val id = photo?.id ?: 0
        if (renderedPhoto == id) return
        renderedPhoto = id
        thumbnail.setImageDrawable(null)
        picture?.recycle()
        picture = null
        val hash = photo?.hash ?: return
        background.execute {
            val bitmap = runCatching { upright(originalFile(this, hash), 640) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || renderedPhoto != id) bitmap?.recycle()
                else {
                    picture = bitmap
                    thumbnail.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun status(task: ProjectStore.Task): String = when (task.status) {
        "queued" -> "Queued or processing…"
        "failed" -> "Failed. ${task.detail}"
        else -> task.detail
    }

    private fun chooseProject() {
        val projects = db.projects()
        val dialog = AlertDialog.Builder(this).setTitle("Active SUBJECT")
            .setNeutralButton("Create SUBJECT", { _, _ -> projectName(null) })
            .setNegativeButton("Cancel", null)
        if (projects.isEmpty()) dialog.setMessage("Create a subject before beginning processing.")
        else dialog.setItems(projects.map { it.name }.toTypedArray()) { _, index -> selectProject(projects[index].id) }
        dialog.show()
    }

    private fun selectProject(id: Long) {
        projectId = id
        photoId = 0L
        lastUi = emptyList()
        rememberSelection()
        redraw()
    }

    private fun manageSubjects(anchor: View) {
        val active = db.projects().firstOrNull { it.id == projectId }
        val menu = PopupMenu(this, anchor)
        val options = listOf("Create SUBJECT Database", "Rename SUBJECT Database", "Backup SUBJECT Database",
            "Restore SUBJECT Database", "Delete SUBJECT Database")
        options.forEachIndexed { index, label ->
            menu.menu.add(0, index, index, label).isEnabled = !archiveBusy && (index in listOf(0, 3) || active != null)
        }
        menu.setOnMenuItemClickListener { item ->
            guarded {
                when (item.itemId) {
                    0 -> projectName(null)
                    1 -> projectName(requireNotNull(active))
                    2 -> startBackup(requireNotNull(active), false)
                    3 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "application/zip"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }, RESTORE)
                    4 -> confirmDelete(requireNotNull(active))
                }
            }
            true
        }
        menu.show()
    }

    private fun projectName(project: ProjectStore.Project?) {
        val input = EditText(this).apply {
            hint = "Subject name"
            isSingleLine = true
            setText(project?.name.orEmpty())
        }
        val action = if (project == null) "Create" else "Rename"
        val alert = AlertDialog.Builder(this).setTitle("$action SUBJECT Database").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton(action, null).create()
        alert.setOnShowListener {
            alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    if (project == null) selectProject(db.addProject(input.text.toString()))
                    else {
                        db.renameProject(project.id, input.text.toString())
                        lastUi = emptyList()
                        redraw()
                    }
                    alert.dismiss()
                } catch (_: SQLiteConstraintException) {
                    input.error = "A subject with this name already exists."
                } catch (_: IllegalArgumentException) {
                    input.error = "Enter a name with 1 to 80 characters."
                }
            }
        }
        alert.show()
    }

    private fun confirmDelete(project: ProjectStore.Project, backedUp: Boolean = false) {
        val rows = db.snapshot(project.id)
        val photos = rows.count { it.kind == "import" }
        val dialog = AlertDialog.Builder(this).setTitle("Delete SUBJECT ${project.name}?")
            .setMessage("This removes this subject’s $photos photo records, analysis, composites, history, and settings. Original photos outside ANTARES and source copies used by other subjects remain intact.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete ${project.name}") { _, _ ->
                guarded {
                    archiveWork("Deleting subject…", {
                        subjectArchiveLock.withLock {
                            val removed = ProjectStore(applicationContext).use { it.deleteProject(project.id) }
                            removed.forEach { id ->
                                listOf("json", "csv", "png", "export").forEach { outputFile(this, id, it).delete() }
                            }
                            settings.edit().remove("exportFolder:${project.id}").apply()
                        }
                    }) {
                        if (projectId == project.id) selectProject(0)
                        message("Deleted subject ${project.name}.")
                    }
                }
            }
        if (!backedUp) dialog.setNeutralButton("Back up first") { _, _ -> guarded { startBackup(project, true) } }
        dialog.show()
    }

    private fun startBackup(project: ProjectStore.Project, thenDelete: Boolean) {
        db.snapshot(project.id)
        archiveSubject = project.id
        deleteAfterBackup = thenDelete
        settings.edit().putLong("archiveSubject", archiveSubject).putBoolean("deleteAfterBackup", thenDelete).apply()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "ANTARES-subject-${project.id}.zip")
            val folder = settings.getString("exportFolder:${project.id}", null)
            if (folder != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(folder))
        }, BACKUP)
    }

    private fun <T> archiveWork(label: String, work: () -> T, finished: (T) -> Unit) {
        check(!archiveBusy) { "A subject backup or restore is already running" }
        archiveBusy = true
        jobsLabel.text = label
        progress.visibility = View.VISIBLE
        background.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                archiveBusy = false
                progress.visibility = View.GONE
                lastUi = emptyList()
                result.fold(onSuccess = finished, onFailure = {
                    jobsLabel.text = it.message ?: "Operation failed."
                    message(jobsLabel.text.toString())
                })
                redraw()
            }
        }
    }

    private fun openFilesMenu() {
        val menu = PopupMenu(this, filesMenu)
        val options = listOf(
            "Add photos", "Choose imported photo", "Save As JSON", "Save As CSV", "Save As PNG",
            "Group confirmed scans for export", "Open saved file", "Preferred export folder", "Retry failed job", "System health"
        )
        val scanReady = result("scan") != null || db.latestBatch(projectId) != null
        options.forEachIndexed { index, label ->
            menu.menu.add(0, index, index, label).isEnabled = when (index) {
                2, 3 -> scanReady
                4 -> result("png") != null
                8 -> db.recent(projectId).any { it.status == "failed" }
                else -> true
            }
        }
        menu.setOnMenuItemClickListener { item ->
            guarded {
                when (item.itemId) {
                    0 -> pickPhotos()
                    1 -> choosePhoto()
                    2 -> save(JSON)
                    3 -> save(CSV)
                    4 -> save(PNG)
                    5 -> chooseScansForBatch()
                    6 -> openExport()
                    7 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), FOLDER)
                    8 -> retryJob()
                    9 -> showHealth()
                }
            }
            true
        }
        menu.show()
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
        if (projectId == 0L) {
            chooseProject()
            return
        }
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
            val folder = settings.getString("exportFolder:$projectId", null)
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
                BACKUP -> {
                    val destination = data.data ?: return@guarded
                    val subject = archiveSubject
                    val promptDelete = deleteAfterBackup
                    archiveWork("Backing up subject…", { SubjectArchive(applicationContext).backup(subject, destination) }) { saved ->
                        message("Verified backup saved as $saved.")
                        if (promptDelete) db.projects().firstOrNull { it.id == subject }?.let { confirmDelete(it, true) }
                    }
                }
                RESTORE -> {
                    val source = data.data ?: return@guarded
                    archiveWork("Restoring subject…", { SubjectArchive(applicationContext).restore(source) }) { restored ->
                        selectProject(restored)
                        message("Subject restored.")
                    }
                }
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
                    settings.edit().putString("exportFolder:$projectId", initial.toString()).apply()
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
            it.status == "done" && it.input.isNotEmpty() && it.kind in setOf("csv", "json", "save_png")
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
        const val BACKUP = 13
        const val RESTORE = 14
        const val JSON = 20
        const val CSV = 21
        const val PNG = 22
    }
}
