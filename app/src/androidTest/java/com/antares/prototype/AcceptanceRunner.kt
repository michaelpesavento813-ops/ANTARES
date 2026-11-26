package com.antares.prototype

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class AcceptanceRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        val owned = mutableListOf<Uri>()
        try {
            val checkStarted = SystemClock.elapsedRealtime()
            val resolver = targetContext.contentResolver
            val subjectName = "Acceptance subject ${System.nanoTime()}"
            val active = ProjectStore(targetContext).use { it.addProject(subjectName) }
            val prefs = targetContext.getSharedPreferences("workspace", android.content.Context.MODE_PRIVATE)
            check(prefs.edit().putLong("project", active).remove("photo").commit())
            val mainActivity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            verifyWorkflowRoutes(mainActivity, subjectName)
            check(android.os.Build.VERSION.SDK_INT == 35) { "Acceptance requires API 35" }
            val fixture = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            fixture.eraseColor(Color.rgb(30, 75, 105))
            val bytes = ByteArrayOutputStream().apply {
                check(fixture.compress(Bitmap.CompressFormat.PNG, 100, this))
                fixture.recycle()
            }.toByteArray()

            fun doc(name: String, mime: String, content: ByteArray? = null): Uri {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ANTARES acceptance")
                }
                return requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)).also {
                    owned.add(it)
                    resolver.openOutputStream(it, "wt")!!.use { stream ->
                        if (content != null) stream.write(content)
                    }
                }
            }

            fun read(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
            fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
                .joinToString("") { "%02x".format(it) }

            val source = doc("acceptance.png", "image/png", bytes)
            val portraitBytes = context.assets.open("astronaut.png").use { it.readBytes() }
            val portrait = doc("portrait.png", "image/png", portraitBytes)
            val malformed = doc("malformed.png", "image/png", "damaged input".toByteArray())
            val jsonTarget = doc("ant_acceptance.json", "application/json")
            val csvTarget = doc("ant_acceptance.csv", "text/csv")
            val occupied = doc("ant_existing.csv", "text/csv", "existing bytes".toByteArray())
            val batchJsonTarget = doc("project_output.json", "application/json")
            val batchCsvTarget = doc("project_output.csv", "text/csv")
            val recoveryTarget = doc("interrupted_output.json", "application/json")
            val pngTarget = doc("ant_grayscale.png", "image/png")
            val project = active

            fun submit(kind: String, uri: Uri? = null, parent: Long? = null): Long =
                ProjectStore(targetContext).use { it.queue(project, kind, uri?.toString().orEmpty(), "Test $kind", parent) }

            fun await(id: Long, status: String = "done"): ProjectStore.Task {
                ProcessingService.schedule(targetContext)
                val end = SystemClock.elapsedRealtime() + 150_000
                ProjectStore(targetContext).use { db ->
                    while (SystemClock.elapsedRealtime() < end) {
                        val job = requireNotNull(db.task(id))
                        if (job.status != "queued") {
                            check(job.status == status) { "Job ${job.kind}: ${job.status}, ${job.detail}" }
                            return job
                        }
                        SystemClock.sleep(250)
                    }
                }
                error("Timed out waiting for job $id")
            }

            val badImport = submit("import", malformed)
            val imported = submit("import", source)
            val photo = await(imported)
            await(badImport, "failed")
            check(photo.hash == sha(bytes) && photo.bytes == bytes.size.toLong() && photo.width == 256)
            check(originalFile(targetContext, photo.hash!!).readBytes().contentEquals(bytes))
            val exact = await(submit("import", source))
            check(exact.detail.contains("Exact SHA-256 match") && exact.imageAlias != photo.imageAlias)

            val portraitImport = submit("import", portrait)
            await(portraitImport)
            val scan = submit("scan", parent = imported)
            val preview = submit("png", parent = imported)
            await(scan)
            await(preview)
            await(submit("json", jsonTarget, scan))
            await(submit("csv", csvTarget, scan))
            await(submit("csv", occupied, scan), "failed")
            await(submit("save_png", pngTarget, preview))
            val json = JSONObject(read(jsonTarget).decodeToString())
            val record = json.getJSONArray("results").getJSONObject(0)
            val csv = read(csvTarget).decodeToString()
            check(json.getLong("batchId") == scan)
            check(record.getString("status") == "no_face" && record.getInt("landmarkCount") == 0)
            check(record.getString("sha256") == sha(bytes) && csv.contains("\"no_face\""))
            check(read(occupied).decodeToString() == "existing bytes")
            val png = read(pngTarget)
            check(png.size > 8 && png.copyOfRange(1, 4).decodeToString() == "PNG")
            check(read(source).contentEquals(bytes))
            val batch = ProjectStore(targetContext).use { it.queueBatch(project) }
            await(batch)
            await(submit("json", batchJsonTarget, batch))
            await(submit("csv", batchCsvTarget, batch))
            val projectJson = JSONObject(read(batchJsonTarget).decodeToString())
            val rows = projectJson.getJSONArray("results")
            check(projectJson.getLong("batchId") == batch && rows.length() == 3)
            val face = rows.getJSONObject(2)
            check(face.getString("status") == "detected" && face.getInt("landmarkCount") == 478)
            val measured = face.getJSONArray("landmarks")
            val expected = context.assets.open("astronaut-cpu-reference.json").use {
                JSONArray(it.bufferedReader().readText())
            }
            check(expected.length() == measured.length())
            var maxDelta = 0.0
            for (index in 0 until expected.length()) {
                for (axis in listOf("x", "y", "z")) {
                    maxDelta = maxOf(maxDelta, abs(
                        measured.getJSONObject(index).getDouble(axis) - expected.getJSONObject(index).getDouble(axis)
                    ))
                }
            }
            check(maxDelta <= 0.005) { "Reference landmark drift $maxDelta exceeds 0.005" }
            val csvRows = read(batchCsvTarget).decodeToString().lineSequence().filter { it.isNotBlank() }.toList()
            check(csvRows.size == 481 && csvRows.drop(1).all { it.startsWith("\"$batch\",") })
            check(read(portrait).contentEquals(portraitBytes))
            val completeJson = read(jsonTarget)
            val pendingExport = submit("json", recoveryTarget, scan)
            resolver.openOutputStream(recoveryTarget, "wt")!!.use { it.write(completeJson, 0, 30) }
            outputFile(targetContext, pendingExport, "export").writeText(sha(completeJson))
            await(pendingExport)
            check(read(recoveryTarget).contentEquals(completeJson))
            verifyCrowd(portraitBytes)
            val selectedPhoto = SubjectReference(imported, Region(0.2f, 0.2f, 0.75f, 0.75f))
            val selectedPortrait = SubjectReference(portraitImport, Region(0.27f, 0.12f, 0.62f, 0.42f))
            fun workflow(request: WorkflowRequest): Long = ProjectStore(targetContext).use {
                it.queue(project, if (request.flow == Workflow.SCAN) "scan" else "png",
                    request.encode(), request.flow.title, request.references.first().photoId)
            }
            val repair = workflow(WorkflowRequest(Workflow.REPAIR, listOf(selectedPhoto), 0, 70))
            val cosmetic = workflow(WorkflowRequest(Workflow.COSMETIC, listOf(selectedPhoto), 1, 70))
            val collage = workflow(WorkflowRequest(Workflow.COMPOSITE, listOf(selectedPhoto, selectedPortrait), 0, 45))
            val subjectScan = workflow(WorkflowRequest(Workflow.SCAN, listOf(selectedPortrait), 0, 45))
            for (id in listOf(repair, cosmetic, collage, subjectScan)) await(id)
            for (id in listOf(repair, cosmetic)) {
                val derivative = requireNotNull(BitmapFactory.decodeFile(outputFile(targetContext, id, "png").path))
                check(derivative.getPixel(10, 10) == Color.rgb(30, 75, 105))
                check(derivative.getPixel(128, 128) != Color.rgb(30, 75, 105))
                derivative.recycle()
            }
            val collageOutput = requireNotNull(BitmapFactory.decodeFile(outputFile(targetContext, collage, "png").path))
            check(collageOutput.width == 1024 && collageOutput.height == 512)
            collageOutput.recycle()
            val selectionOutput = JSONObject(outputFile(targetContext, subjectScan, "json").readText())
                .getJSONArray("results").getJSONObject(0)
            check(selectionOutput.getInt("landmarkCount") == 478)
            check(selectionOutput.getJSONObject("selection").getJSONArray("references")
                .getJSONObject(0).getLong("photoId") == selectedPortrait.photoId)
            ProjectStore(targetContext).use { workspace ->
                check(workspace.completedResults(project, "png", imported).map { it.id }.containsAll(listOf(repair, cosmetic, collage)))
                check(workspace.confirmedScans(project).map { it.id }.contains(subjectScan))
                check(workspace.confirmedScans(project).none { it.id == scan })
            }
            check(read(source).contentEquals(bytes) && read(portrait).contentEquals(portraitBytes))
            ProjectStore(targetContext).use { reopened ->
                check(reopened.task(imported)?.status == "done")
                check(reopened.result(project, "scan", imported)?.id != null)
                check(reopened.latestBatch(project)?.id == batch)
            }
            repeat(12) { attempt ->
                SystemClock.sleep((attempt % 4) * 17L)
                val duplicate = await(submit("import", source))
                check(duplicate.hash == photo.hash && duplicate.detail.contains("Exact SHA-256 match"))
            }
            val manualStroke = MaskBrush(true, 0.16f,
                listOf(CompositePoint(0.5f, 0.35f), CompositePoint(0.5f, 0.65f)))
            val manualLayer = ForegroundStyle(0.5f, 0.5f, manualOnly = true, brushes = listOf(manualStroke))
            val proof = CompositeDesign(layers = listOf(manualLayer), width = 512, height = 512, reviewed = true)
            val automaticLayer = ForegroundStyle(0.5f, 0.5f)
            CompositeRenderer(targetContext, project, 640).use { engine ->
                val automatic = engine.cutout(selectedPortrait, automaticLayer)
                try {
                    val all = IntArray(automatic.width * automatic.height)
                    automatic.getPixels(all, 0, automatic.width, 0, 0, automatic.width, automatic.height)
                    check(all.any { Color.alpha(it) == 0 } && all.any { Color.alpha(it) >= 240 })
                } finally { automatic.recycle() }
                for (mode in BackgroundMode.entries) {
                    val chosen = proof.copy(mode = mode, solid = Color.rgb(112, 75, 25),
                        backgroundPhoto = if (mode == BackgroundMode.CUSTOM) portraitImport else null)
                    val background = engine.render(listOf(selectedPhoto), chosen)
                    try {
                        check(background.width == 512 && background.height == 512)
                        val corner = background.getPixel(0, 0)
                        when (mode) {
                            BackgroundMode.GREEN -> check(corner == Color.GREEN)
                            BackgroundMode.TRANSPARENT -> check(Color.alpha(corner) == 0)
                            BackgroundMode.SOLID -> check(corner == Color.rgb(112, 75, 25))
                            BackgroundMode.ORIGINAL -> check(corner == Color.rgb(30, 75, 105))
                            BackgroundMode.CUSTOM -> check(Color.alpha(corner) == 255)
                        }
                    } finally { background.recycle() }
                }
                val eraser = MaskBrush(false, 0.12f, listOf(CompositePoint(0.5f, 0.5f)))
                val corrected = engine.cutout(selectedPhoto, manualLayer.copy(brushes = listOf(manualStroke, eraser)))
                try { check(Color.alpha(corrected.getPixel(corrected.width / 2, corrected.height / 2)) == 0) }
                finally { corrected.recycle() }
            }
            val isolatedTask = workflow(WorkflowRequest(Workflow.COMPOSITE, listOf(selectedPhoto), 0, 50,
                composite = proof.copy(mode = BackgroundMode.TRANSPARENT)))
            await(isolatedTask)
            val alphaFile = outputFile(targetContext, isolatedTask, "mask-0.png")
            check(alphaFile.isFile && alphaFile.length() > 0)
            val ageTasks = (-40..40 step 40).map { years ->
                workflow(WorkflowRequest(Workflow.COSMETIC, listOf(selectedPortrait), 0, 100,
                    age = AgeDesign(years, 100, AgeScope.FACE_AND_VISIBLE_BODY))).also { await(it) }
            }
            val faceOnlyAge = workflow(WorkflowRequest(Workflow.COSMETIC, listOf(selectedPortrait), 0, 100,
                age = AgeDesign(-15, 70, AgeScope.FACE))).also { await(it) }
            val sourceBitmap = BitmapFactory.decodeByteArray(portraitBytes, 0, portraitBytes.size)
            try {
                val zero = BitmapFactory.decodeFile(outputFile(targetContext, ageTasks[1], "png").path)
                try { check(sourceBitmap.sameAs(zero)) } finally { zero.recycle() }
                for (ageTask in listOf(ageTasks.first(), ageTasks.last(), faceOnlyAge)) {
                    val aged = BitmapFactory.decodeFile(outputFile(targetContext, ageTask, "png").path)
                    try {
                        check(aged.width == sourceBitmap.width && aged.height == sourceBitmap.height)
                        check(!aged.sameAs(sourceBitmap))
                        check(aged.getPixel(5, 5) == sourceBitmap.getPixel(5, 5))
                    } finally { aged.recycle() }
                }
            } finally { sourceBitmap.recycle() }
            check(runCatching { AgeDesign(41, 50, AgeScope.FACE) }.isFailure)
            val backupTarget = doc("ant_subject.zip", "application/zip")
            val brokenTarget = doc("ant_truncated_subject.zip", "application/zip")
            val independent = ProjectStore(targetContext).use { it.addProject("Separate subject ${System.nanoTime()}") }
            ProjectStore(targetContext).use { subjects ->
                subjects.renameProject(project, "Renamed $subjectName")
                check(subjects.projects().first { it.id == project }.name == "Renamed $subjectName")
                check(subjects.photos(independent).isEmpty())
                check(runCatching { subjects.queue(independent, "scan", "", "Invalid reference", imported) }.isFailure)
            }
            val archive = SubjectArchive(targetContext)
            archive.backup(project, backupTarget)
            val archiveBytes = read(backupTarget)
            resolver.openOutputStream(brokenTarget, "wt")!!.use { it.write(archiveBytes, 0, archiveBytes.size / 2) }
            ProjectStore(targetContext).use { subjects ->
                val known = subjects.projects()
                check(runCatching { archive.restore(brokenTarget) }.isFailure)
                check(subjects.projects() == known)
            }
            val restored = archive.restore(backupTarget)
            check(restored != project && restored != independent)
            ProjectStore(targetContext).use { subjects ->
                val oldRows = subjects.snapshot(project)
                val newRows = subjects.snapshot(restored)
                check(oldRows.size == newRows.size && newRows.all { it.projectId == restored })
                check(newRows.map { it.id }.toSet().intersect(oldRows.map { it.id }.toSet()).isEmpty())
                check(subjects.photos(restored).map { it.hash } == subjects.photos(project).map { it.hash })
                val restoredScan = subjects.confirmedScans(restored).single()
                val rawScan = JSONObject(outputFile(targetContext, restoredScan.id, "json").readText())
                val restoredPhoto = rawScan.getJSONArray("results").getJSONObject(0).getLong("imageId")
                check(rawScan.getLong("projectId") == restored)
                check(subjects.task(restoredPhoto)?.projectId == restored)
                val reopenedIsolation = subjects.composites(restored).single()
                check(outputFile(targetContext, reopenedIsolation.id, "mask-0.png").isFile)
                val reopenedDesign = requireNotNull(WorkflowRequest.decode(reopenedIsolation.input).composite)
                val reopenedRef = WorkflowRequest.decode(reopenedIsolation.input).references.single()
                val reusedDesign = reopenedDesign.copy(mode = BackgroundMode.GREEN,
                    layers = listOf(reopenedDesign.layers.single().copy(reusedMask = reopenedIsolation.id, brushes = emptyList())))
                CompositeRenderer(targetContext, restored, 640).use { editor ->
                    val recolored = editor.render(listOf(reopenedRef), reusedDesign)
                    try { check(recolored.getPixel(0, 0) == Color.GREEN) } finally { recolored.recycle() }
                }
                val pending = subjects.queue(independent, "import", "", "Pending database safety check")
                check(runCatching { subjects.deleteProject(independent) }.isFailure)
                check(subjects.task(pending)?.projectId == independent)
                subjects.fail(pending, "Cancelled")
                subjects.deleteProject(project)
                check(subjects.projects().none { it.id == project })
                check(subjects.photos(restored).isNotEmpty() && subjects.projects().any { it.id == independent })
            }
            check(read(source).contentEquals(bytes) && read(portrait).contentEquals(portraitBytes))
            report.putString("stream", "\nPASS: Android 15 import, malformed isolation, exact match, no-face and 478-point inference, batch JSON/CSV/PNG readback, partial export recovery, occupied destination rejection, reopened queue, and 12 idle handoffs. Five backgrounds, native cutout, mask brushes, mask reuse, and signed age controls passed. Subject creation, rename, isolation, verified backup, truncated restore rollback, remapped restore and pending-delete rejection passed. Four workflow routes, cancellation, region-limited edits, two-face and 16-face isolation checks, and two-reference composite passed. Max normalized coordinate drift $maxDelta. Duration ${SystemClock.elapsedRealtime() - checkStarted} ms.\n")
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("stream", "\nFAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, report)
        } finally {
            owned.forEach { runCatching { targetContext.contentResolver.delete(it, null, null) } }
        }
    }

    private fun verifyWorkflowRoutes(main: Activity, subjectName: String) {
        for (workflow in Workflow.entries) {
            val monitor = addMonitor(WorkflowActivity::class.java.name, null, false)
            runOnMainSync {
                requireNotNull(view(main.findViewById(android.R.id.content), workflow.title) as? Button).performClick()
            }
            val screen = requireNotNull(waitForMonitorWithTimeout(monitor, 10000)) {
                "No dedicated screen for ${workflow.title}"
            }
            runOnMainSync {
                val contents = screen.findViewById<View>(android.R.id.content)
                check(view(contents, workflow.title) is TextView)
                check(view(contents, "SUBJECT · $subjectName") is TextView)
                val action = if (workflow == Workflow.COMPOSITE) "Confirm and add reference" else "Confirm subject and start"
                check(view(contents, action)?.isEnabled == false)
                requireNotNull(view(contents, "Cancel workflow") as? Button).performClick()
            }
            waitForIdleSync()
            removeMonitor(monitor)
        }
    }

    private fun view(parent: View, label: String): View? {
        if (parent is TextView && parent.text.toString() == label) return parent
        if (parent is ViewGroup) for (index in 0 until parent.childCount) {
            view(parent.getChildAt(index), label)?.let { return it }
        }
        return null
    }

    private fun verifyCrowd(portrait: ByteArray) {
        val original = requireNotNull(BitmapFactory.decodeByteArray(portrait, 0, portrait.size))
        val tile = 256
        try {
            for ((columns, rows) in listOf(2 to 1, 4 to 4)) {
                val frame = Bitmap.createBitmap(columns * tile, rows * tile, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(frame)
                    repeat(columns * rows) { index ->
                        val left = (index % columns) * tile
                        val top = (index / columns) * tile
                        canvas.drawBitmap(original, null, android.graphics.Rect(left, top, left + tile, top + tile), null)
                    }
                    val candidates = detectSubjects(targetContext, frame)
                    check(candidates.size >= columns * rows) {
                        "Expected ${columns * rows} crowd proposals, found ${candidates.size}"
                    }
                    check(!isolatedRegion(Region(0f, 0f, 1f, 1f), candidates))
                    check(candidates.any { isolatedRegion(it, candidates) })
                } finally { frame.recycle() }
            }
        } finally { original.recycle() }
    }
}
