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
            val mainActivity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            verifyWorkflowRoutes(mainActivity)
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
            val project = ProjectStore(targetContext).use { it.addProject("Acceptance ${System.nanoTime()}") }

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
            check(read(source).contentEquals(bytes) && read(portrait).contentEquals(portraitBytes))
            ProjectStore(targetContext).use { reopened ->
                check(reopened.task(imported)?.status == "done")
                check(reopened.result(project, "scan", imported)?.id != null)
                check(reopened.latestBatch(project)?.id == batch)
            }
            report.putString("stream", "\nPASS: Android 15 import, malformed isolation, exact match, no-face and 478-point inference, batch JSON/CSV/PNG readback, partial export recovery, occupied destination rejection and reopened queue. Four workflow routes, cancellation, region-limited edits, two-face rejection, and two-reference composite passed. Max normalized coordinate drift $maxDelta. Duration ${SystemClock.elapsedRealtime() - checkStarted} ms.\n")
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("stream", "\nFAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, report)
        } finally {
            owned.forEach { runCatching { targetContext.contentResolver.delete(it, null, null) } }
        }
    }

    private fun verifyWorkflowRoutes(main: Activity) {
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
        val pair = Bitmap.createBitmap(original.width * 2, original.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(pair)
            canvas.drawBitmap(original, 0f, 0f, null)
            canvas.drawBitmap(original, original.width.toFloat(), 0f, null)
            val candidates = detectSubjects(targetContext, pair)
            check(candidates.size >= 2) { "Expected at least two crowd proposals, found ${candidates.size}" }
            check(!isolatedRegion(Region(0f, 0f, 1f, 1f), candidates))
            check(candidates.any { isolatedRegion(it, candidates) })
        } finally {
            pair.recycle()
            original.recycle()
        }
    }
}
