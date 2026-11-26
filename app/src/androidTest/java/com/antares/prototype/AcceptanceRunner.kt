package com.antares.prototype

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import org.json.JSONObject
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
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                    if (content != null) resolver.openOutputStream(it, "wt")!!.use { stream -> stream.write(content) }
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
            val firstLandmark = face.getJSONArray("landmarks").getJSONObject(0)
            check(firstLandmark.getDouble("x") in 0.2..0.7 && firstLandmark.getDouble("y") in 0.10..0.45)
            val csvRows = read(batchCsvTarget).decodeToString().lineSequence().filter { it.isNotBlank() }.toList()
            check(csvRows.size == 481 && csvRows.drop(1).all { it.startsWith("\"$batch\",") })
            check(read(portrait).contentEquals(portraitBytes))
            val completeJson = read(jsonTarget)
            val pendingExport = submit("json", recoveryTarget, scan)
            resolver.openOutputStream(recoveryTarget, "wt")!!.use { it.write(completeJson, 0, 30) }
            outputFile(targetContext, pendingExport, "export").writeText(sha(completeJson))
            await(pendingExport)
            check(read(recoveryTarget).contentEquals(completeJson))
            ProjectStore(targetContext).use { reopened ->
                check(reopened.task(imported)?.status == "done")
                check(reopened.result(project, "scan", imported)?.id != null)
                check(reopened.latestBatch(project)?.id == batch)
            }
            report.putString("stream", "\nPASS: Android 15 import, malformed isolation, exact match, no-face and 478-point inference, batch JSON/CSV/PNG readback, partial export recovery, occupied destination rejection and reopened queue. Duration ${SystemClock.elapsedRealtime() - checkStarted} ms.\n")
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("stream", "\nFAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, report)
        } finally {
            owned.forEach { runCatching { targetContext.contentResolver.delete(it, null, null) } }
        }
    }

}
