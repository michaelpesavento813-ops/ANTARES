package com.antares.prototype

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal val subjectArchiveLock = ReentrantLock()

internal class SubjectArchive(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun backup(project: Long, destination: Uri) = temporary { directory ->
        val archive = File(directory, "backup.zip")
        ProjectStore(context).use { db ->
            val subject = db.projects().firstOrNull { it.id == project } ?: error("Subject no longer exists")
            val rows = db.snapshot(project)
            val files = linkedMapOf<String, File>()
            for (row in rows.filter { it.status == "done" }) {
                when (row.kind) {
                    "import" -> {
                        val hash = requireNotNull(row.hash)
                        files["original-$hash"] = originalFile(context, hash)
                    }
                    "scan", "batch" -> files["result-${row.id}.json"] = outputFile(context, row.id, "json")
                    "png" -> files["result-${row.id}.png"] = outputFile(context, row.id, "png")
                }
            }
            val manifest = JSONObject().put("format", "antares-subject").put("version", 1)
                .put("subject", subject.name).put("tasks", JSONArray(rows.map { encode(it) }))
                .put("files", JSONObject().apply {
                    files.forEach { (name, file) ->
                        check(file.isFile) { "Missing subject file $name" }
                        val checksum = file.inputStream().use(::archiveDigest)
                        if (name.startsWith("original-")) check(checksum == name.substringAfter("original-")) {
                            "An original failed verification"
                        }
                        put(name, checksum)
                    }
                })
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for ((name, file) in files) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        require(destination.scheme == "content") { "Choose a destination through Android Files" }
        check(resolver.openInputStream(destination)?.use { it.read() } == -1) {
            "Choose a new empty backup file"
        }
        resolver.openOutputStream(destination, "wt")?.use { output ->
            archive.inputStream().use { it.copyTo(output) }
        } ?: error("Could not write the backup")
        val expected = archive.inputStream().use(::archiveDigest)
        val actual = resolver.openInputStream(destination)?.use(::archiveDigest)
        check(actual == expected) { "Backup failed readback verification" }
        documentName(context, destination)
    }

    fun restore(source: Uri): Long = temporary { directory ->
        require(source.scheme == "content") { "Choose an ANTARES backup through Android Files" }
        val manifest = resolver.openInputStream(source)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                check(zip.nextEntry?.name == "manifest.json") { "Choose an ANTARES subject backup" }
                val first = ByteArrayOutputStream()
                copyBounded(zip, first, 32L * 1024 * 1024)
                val record = JSONObject(first.toString(Charsets.UTF_8.name()))
                check(record.getString("format") == "antares-subject" && record.getInt("version") == 1)
                val files = record.getJSONObject("files")
                val permitted = files.keys().asSequence().toSet()
                require(permitted.size <= 100_000 && permitted.all { FILE_NAME.matches(it) }) { "Invalid backup entries" }
                val seen = mutableSetOf<String>()
                var budget = 2L * 1024 * 1024 * 1024
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(entry.name in permitted && seen.add(entry.name) && !entry.isDirectory) { "Unexpected backup entry" }
                    val staged = File(directory, entry.name)
                    budget -= staged.outputStream().use { copyBounded(zip, it, minOf(budget, 160L * 1024 * 1024)) }
                    val hash = staged.inputStream().use(::archiveDigest)
                    check(hash == files.getString(entry.name)) { "Backup data failed checksum verification" }
                    if (entry.name.startsWith("original-")) check(entry.name == "original-$hash")
                }
                check(seen == permitted) { "The backup is incomplete" }
                record
            }
        } ?: error("Could not read the backup")
        install(manifest, directory)
    }

    private fun install(manifest: JSONObject, directory: File): Long {
        val rows = manifest.getJSONArray("tasks")
        require(rows.length() <= 100_000)
        val records = List(rows.length()) { decode(rows.getJSONObject(it)) }
        require(records.map { it.id }.distinct().size == records.size)
        require(records.all { it.status in setOf("done", "failed") && it.kind in KINDS })
        val subject = manifest.getString("subject").trim()
        require(subject.length in 1..80)
        val remapped = mutableMapOf<Long, Long>()
        val createdOutputs = mutableListOf<File>()
        ProjectStore(context).use { store ->
            val database = store.writableDatabase
            database.beginTransactionNonExclusive()
            try {
                val names = store.projects().map { it.name }.toSet()
                val candidateName = if (subject !in names) subject else generateSequence(1) { it + 1 }
                    .map { "${subject.take(56)} (restored $it)" }.first { it !in names }
                val id = store.addProject(candidateName)
                for (row in records.sortedBy { it.id }) {
                    val parent = row.parent?.let { remapped.getValue(it) }
                    val archivedInput = when {
                        row.kind == "batch" -> row.input.split(',').joinToString(",") { remapped.getValue(it.toLong()).toString() }
                        row.kind in setOf("png", "scan") && row.input.isNotEmpty() -> {
                            val request = WorkflowRequest.decode(row.input)
                            request.copy(references = request.references.map { it.copy(photoId = remapped.getValue(it.photoId)) }).encode()
                        }
                        else -> ""
                    }
                    val newId = store.restoreTask(id, row, archivedInput, parent)
                    remapped[row.id] = newId
                    if (row.status != "done") continue
                    when (row.kind) {
                        "import" -> {
                            val hash = requireNotNull(row.hash)
                            val backup = File(directory, "original-$hash")
                            check(backup.isFile) { "An original is missing from the backup" }
                            val original = originalFile(context, hash)
                            if (original.isFile) check(original.inputStream().use(::archiveDigest) == hash)
                            else {
                                val temporary = File(original.parentFile, ".${UUID.randomUUID()}.restore")
                                try {
                                    temporary.outputStream().use { output ->
                                        backup.inputStream().use { it.copyTo(output) }
                                        output.fd.sync()
                                    }
                                    if (!original.exists()) check(temporary.renameTo(original))
                                } finally { temporary.delete() }
                            }
                        }
                        "scan", "batch", "png" -> {
                            val ext = if (row.kind == "png") "png" else "json"
                            val backup = File(directory, "result-${row.id}.$ext")
                            check(backup.isFile) { "A result is missing from the backup" }
                            val target = outputFile(context, newId, ext)
                            createdOutputs += target
                            if (ext == "png") atomic(target) { stream -> backup.inputStream().use { it.copyTo(stream) } }
                            else {
                                val document = JSONObject(backup.readText()).put("projectId", id).put("batchId", newId)
                                val results = document.getJSONArray("results")
                                repeat(results.length()) {
                                    val record = results.getJSONObject(it)
                                    val imageId = remapped.getValue(record.getLong("imageId"))
                                    record.put("imageId", imageId).put("alias", exportName("IMG", imageId, "IMG").substringBefore('.'))
                                    val selection = record.optJSONObject("selection")
                                    if (selection != null) {
                                        val refs = selection.getJSONArray("references")
                                        repeat(refs.length()) { index ->
                                            val reference = refs.getJSONObject(index)
                                            reference.put("photoId", remapped.getValue(reference.getLong("photoId")))
                                        }
                                    }
                                }
                                atomic(target) { it.write(document.toString().toByteArray(Charsets.UTF_8)) }
                            }
                        }
                    }
                }
                database.setTransactionSuccessful()
                return id
            } catch (error: Exception) {
                createdOutputs.forEach { it.delete() }
                throw error
            } finally { database.endTransaction() }
        }
    }

    private fun <T> temporary(work: (File) -> T): T = subjectArchiveLock.withLock {
        val directory = File(context.cacheDir, "subject-${UUID.randomUUID()}")
        check(directory.mkdir())
        try { work(directory) } finally { directory.deleteRecursively() }
    }

    private fun encode(row: ProjectStore.Task) = JSONObject()
        .put("id", row.id).put("kind", row.kind)
        .put("input", if (row.kind in setOf("scan", "png", "batch")) row.input else "")
        .put("label", row.label).put("status", row.status)
        .put("detail", row.detail.substringBefore('\n'))
        .put("parent", row.parent ?: JSONObject.NULL).put("hash", row.hash ?: JSONObject.NULL)
        .put("bytes", row.bytes).put("width", row.width).put("height", row.height)
        .put("orientation", row.orientation).put("perceptualHash", row.perceptualHash ?: JSONObject.NULL)
        .put("created", row.created)

    private fun decode(data: JSONObject) = ProjectStore.Task(
        data.getLong("id"), 0, data.getString("kind"), data.getString("input"), data.getString("label"),
        data.getString("status"), data.getString("detail"), if (data.isNull("parent")) null else data.getLong("parent"),
        if (data.isNull("hash")) null else data.getString("hash"), data.getLong("bytes"), data.getInt("width"),
        data.getInt("height"), data.getInt("orientation"),
        if (data.isNull("perceptualHash")) null else data.getString("perceptualHash"), data.getLong("created")
    )

    private companion object {
        val FILE_NAME = Regex("original-[a-f0-9]{64}|result-[1-9][0-9]*\\.(json|png)")
        val KINDS = setOf("import", "scan", "batch", "png", "csv", "json", "save_png")
    }
}

private fun copyBounded(input: InputStream, output: OutputStream, maximum: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return bytes
        bytes += count
        check(bytes <= maximum) { "Backup exceeds supported size limits" }
        output.write(buffer, 0, count)
    }
}

private fun archiveDigest(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
