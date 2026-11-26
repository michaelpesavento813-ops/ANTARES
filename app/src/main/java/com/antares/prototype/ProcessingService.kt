package com.antares.prototype

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class ProcessingService : JobService() {
    @Volatile private var active: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        active = params
        executor.execute {
            try {
                ProjectStore(applicationContext).use { db ->
                    Processor(applicationContext, db).use { processor ->
                        while (active === params) {
                            val task = db.next() ?: break
                            try {
                                processor.run(task)
                            } catch (error: Exception) {
                                db.fail(task.id, actionMessage(task.kind, error))
                            } catch (error: LinkageError) {
                                db.fail(task.id, "Native image engine is unavailable on this device: ${error.javaClass.simpleName}.")
                            }
                        }
                    }
                }
            } finally {
                mainExecutorCompat {
                    if (active === params) {
                        active = null
                        jobFinished(params, false)
                        ProjectStore(applicationContext).use { if (it.next() != null) schedule(applicationContext) }
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) active = null
        return true
    }

    private fun mainExecutorCompat(action: () -> Unit) =
        android.os.Handler(mainLooper).post { action() }

    companion object {
        private const val JOB_ID = 41
        private val executor = Executors.newSingleThreadExecutor()

        internal fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // A finishing job may still appear pending in the framework. Replace that
            // schedule so its teardown cannot strand work queued at the idle boundary.
            check(scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, ProcessingService::class.java))
                .setPersisted(true)
                .setOverrideDeadline(0)
                .build()) == JobScheduler.RESULT_SUCCESS) { "Android could not start queued work. Reopen ANTARES to retry." }
        }

        private fun actionMessage(kind: String, error: Exception): String {
            val recovery = when (kind) {
                "import" -> "Try again or select the photo again."
                "csv", "json", "save_png" -> "Retry the save or choose a different destination."
                else -> "Try again. Check free storage and choose a supported photo."
            }
            return "${error.message?.take(220) ?: error.javaClass.simpleName}. $recovery"
        }
    }
}

internal fun outputFile(context: Context, taskId: Long, ext: String) =
    File(File(context.filesDir, "results").apply { mkdirs() }, "$taskId.$ext")

internal fun originalFile(context: Context, hash: String): File {
    require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid original checksum" }
    return File(File(context.filesDir, "originals").apply { mkdirs() }, hash)
}

internal fun documentName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "Selected document"

private class Processor(private val context: Context, private val db: ProjectStore) : AutoCloseable {
    private val resolver get() = context.contentResolver
    private var detector: FaceLandmarker? = null
    private var subjectDetector: FaceLandmarker? = null

    fun run(task: ProjectStore.Task) {
        when (task.kind) {
            "import" -> import(task)
            "scan" -> scan(task)
            "batch" -> batch(task)
            "png" -> if (task.input.isEmpty()) preview(task) else appearance(task)
            "json", "csv", "save_png" -> export(task)
            else -> error("Unknown job")
        }
    }

    private fun import(task: ProjectStore.Task) {
        val temporary = File(File(context.filesDir, "inbox").apply { mkdirs() }, "${task.id}.input")
        val checksum: String
        val byteCount: Long
        try {
            val uri = uri(task.input)
            val digest = MessageDigest.getInstance("SHA-256")
            byteCount = resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    copy(input, output, digest, 128L * 1024 * 1024).also { output.fd.sync() }
                }
            } ?: error("Cannot read the selected document")
            checksum = hex(digest.digest())
            val bounds = bounds(temporary)
            val orientation = orientation(temporary)
            val thumb = upright(temporary, 256)
            val fingerprint = try { differenceHash(thumb) } finally { thumb.recycle() }
            val saved = originalFile(context, checksum)
            if (!saved.exists()) check(temporary.renameTo(saved)) { "Could not store the original" }
            else check(saved.inputStream().use { digest(it) } == checksum) { "Stored original failed verification" }
            val match = db.duplicate(task.projectId, checksum)
            val note = match?.let { "Exact SHA-256 match to ${it.imageAlias}." } ?: "Original preserved."
            db.complete(task.id, note, ImageMetadata(
                checksum, byteCount, bounds.outWidth, bounds.outHeight, orientation, fingerprint
            ))
        } finally {
            temporary.delete()
        }
    }

    private fun imported(task: ProjectStore.Task): ProjectStore.Task =
        db.task(requireNotNull(task.parent))?.takeIf {
            it.kind == "import" && it.status == "done" && it.projectId == task.projectId
        } ?: error("Original import is unavailable")

    private fun scan(task: ProjectStore.Task) {
        val photo = imported(task)
        val file = originalFile(context, requireNotNull(photo.hash))
        val bitmap = upright(file, 2048)
        val request = task.input.takeIf { it.isNotEmpty() }?.let(WorkflowRequest::decode)
        val region = request?.also {
            require(it.flow == Workflow.SCAN && it.references.single().photoId == photo.id)
        }?.references?.single()?.region
        val area = region?.pixels(bitmap.width, bitmap.height)
        val selected = if (area != null) Bitmap.createBitmap(bitmap, area.left, area.top, area.width(), area.height()) else bitmap
        val document = try {
            val current = if (region == null) {
                detector ?: createDetector(1).also { detector = it }
            } else {
                subjectDetector ?: createDetector(2).also { subjectDetector = it }
            }
            val image = BitmapImageBuilder(selected).build()
            val faces = try { current.detect(image).faceLandmarks() } finally { image.close() }
            require(faces.size < 2) { "The confirmed region still contains multiple faces. Select a smaller area" }
            val points = JSONArray()
            faces.firstOrNull()?.forEach { point ->
                val x = if (area == null) point.x().toDouble() else (area.left + point.x() * area.width()) / bitmap.width.toDouble()
                val y = if (area == null) point.y().toDouble() else (area.top + point.y() * area.height()) / bitmap.height.toDouble()
                points.put(JSONObject().put("x", x).put("y", y).put("z", point.z().toDouble())
                    .put("confidence", JSONObject.NULL))
            }
            check(points.length() == 0 || points.length() == 478) { "Unexpected landmark count" }
            val record = JSONObject()
                .put("filename", photo.label).put("alias", photo.imageAlias)
                .put("imageId", photo.id).put("bytes", photo.bytes).put("sha256", photo.hash)
                .put("width", bitmap.width).put("height", bitmap.height)
                .put("sourceWidth", photo.width).put("sourceHeight", photo.height)
                .put("sourceExifOrientation", orientation(file))
                .put("status", if (points.length() == 0) "no_face" else "detected")
                .put("landmarkCount", points.length()).put("landmarks", points)
            if (request != null) record.put("selection", JSONObject(request.encode()))
                .put("measurement", "estimated")
                .put("coordinateSystem", "normalized upright source x,y; selected crop model-relative z")
                .put("confidenceStatus", "per-landmark confidence unavailable")
                .put("settings", JSONObject().put("delegate", "CPU").put("runningMode", "IMAGE")
                    .put("numFaces", 2).put("runtimeVersion", "1.2.3"))
            JSONObject().put("schema", "antares-face-landmarks-v1")
                .put("model", "mediapipe-face-landmarker-float16-v1")
                .put("coordinateSystem", "normalized image x,y; model-relative z")
                .put("projectId", task.projectId).put("batchId", task.id)
                .put("results", JSONArray().put(record))
        } finally {
            if (selected !== bitmap) selected.recycle()
            bitmap.recycle()
        }
        atomic(outputFile(context, task.id, "json")) { it.write(document.toString().toByteArray(Charsets.UTF_8)) }
        val result = document.getJSONArray("results").getJSONObject(0)
        db.complete(task.id, if (result.getInt("landmarkCount") == 0) "No face detected." else "478 landmarks ready.")
    }

    private fun createDetector(maxFaces: Int): FaceLandmarker = FaceLandmarker.createFromOptions(
        context, FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setNumFaces(maxFaces).setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false).build()
    )

    private fun appearance(task: ProjectStore.Task) {
        val request = WorkflowRequest.decode(task.input)
        require(request.references.first().photoId == task.parent)
        fun photo(reference: SubjectReference): Bitmap {
            val source = db.task(reference.photoId)?.takeIf {
                it.kind == "import" && it.status == "done" && it.projectId == task.projectId
            } ?: error("A confirmed reference is unavailable")
            val file = originalFile(context, requireNotNull(source.hash))
            if (request.flow == Workflow.COMPOSITE) return upright(file, 2048)
            val size = bounds(file)
            require(size.outWidth.toLong() * size.outHeight <= 12_500_000L) {
                "This edit requires an image of 12.5 megapixels or less to preserve all outside pixels"
            }
            return upright(file, maxOf(size.outWidth, size.outHeight))
        }
        val design = request.composite
        val result = if (design != null) {
            require(design.reviewed) { "Inspect and confirm the subject cutout before rendering" }
            CompositeRenderer(context, task.projectId, COMPOSITE_SOURCE_EDGE).use { renderer ->
                request.references.forEachIndexed { index, reference ->
                    val cutout = renderer.cutout(reference, design.layers[index])
                    try {
                        val mask = Bitmap.createBitmap(cutout.width, cutout.height, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(mask).apply {
                                drawColor(Color.WHITE)
                                drawBitmap(cutout, 0f, 0f, Paint().apply {
                                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                                })
                            }
                            atomic(outputFile(context, task.id, "mask-$index.png")) { output ->
                                check(mask.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Isolation mask could not be written" }
                            }
                        } finally { mask.recycle() }
                    } finally { cutout.recycle() }
                }
                renderer.render(request.references, design)
            }
        } else if (request.flow == Workflow.COMPOSITE) {
            val crops = mutableListOf<Bitmap>()
            try {
                for (reference in request.references) {
                    val source = photo(reference)
                    try {
                        val area = reference.region.pixels(source.width, source.height)
                        val crop = Bitmap.createBitmap(source, area.left, area.top, area.width(), area.height())
                        crops.add(if (crop === source) requireNotNull(crop.copy(Bitmap.Config.ARGB_8888, false)) else crop)
                    } finally { source.recycle() }
                }
                composite(crops, sideBySide = request.settings == 0)
            } finally { crops.forEach { it.recycle() } }
        } else {
            val reference = request.references.single()
            val source = photo(reference)
            try {
                val region = request.age?.let { ageRegion(context, source, reference.region, it) } ?: reference.region
                applyAppearance(source, region, request)
            } finally { source.recycle() }
        }
        try {
            atomic(outputFile(context, task.id, "png")) { output ->
                check(result.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoder failed" }
            }
        } finally { result.recycle() }
        db.complete(task.id, "${request.flow.title} result ready. Use Save As PNG.")
    }

    private fun scans(task: ProjectStore.Task): Sequence<ProjectStore.Task> =
        if (task.kind == "scan") sequenceOf(task) else task.input.splitToSequence(',').map { id ->
            db.task(id.toLong())?.takeIf { it.kind == "scan" && it.projectId == task.projectId && it.status == "done" }
                ?: error("A photo scan failed. Retry the failed photo before retrying this project scan")
        }

    private fun scanRecord(task: ProjectStore.Task): JSONObject =
        JSONObject(outputFile(context, task.id, "json").readText())
            .getJSONArray("results").getJSONObject(0)

    private fun batch(task: ProjectStore.Task) {
        var count = 0
        atomic(outputFile(context, task.id, "json")) { stream ->
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            writer.write(JSONObject()
                .put("schema", "antares-face-landmarks-v1")
                .put("model", "mediapipe-face-landmarker-float16-v1")
                .put("coordinateSystem", "normalized image x,y; model-relative z")
                .put("projectId", task.projectId).put("batchId", task.id).toString().dropLast(1))
            writer.write(",\"results\":[")
            scans(task).forEach { child ->
                if (count > 0) writer.write(",")
                writer.write(scanRecord(child).toString())
                count++
            }
            writer.write("]}")
            writer.flush()
        }
        db.complete(task.id, "Project scan ready · $count photos.")
    }

    private fun preview(task: ProjectStore.Task) {
        val photo = imported(task)
        val source = upright(originalFile(context, requireNotNull(photo.hash)), 2048)
        try {
            val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            try {
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                }
                Canvas(result).drawBitmap(source, 0f, 0f, paint)
                atomic(outputFile(context, task.id, "png")) {
                    check(result.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoder failed" }
                }
            } finally { result.recycle() }
        } finally { source.recycle() }
        db.complete(task.id, "Grayscale preview ready.")
    }

    private fun export(task: ProjectStore.Task) {
        val result = db.task(requireNotNull(task.parent))?.takeIf {
            it.status == "done" && it.projectId == task.projectId &&
                (if (task.kind == "save_png") it.kind == "png" else it.kind in setOf("scan", "batch"))
        } ?: error("Analysis result is unavailable")
        val file = when (task.kind) {
            "json" -> outputFile(context, result.id, "json")
            "save_png" -> outputFile(context, result.id, "png")
            else -> {
                val csv = outputFile(context, result.id, "csv")
                atomic(csv) { stream ->
                    val writer = stream.bufferedWriter(Charsets.UTF_8)
                    writer.appendLine("batch_id,image_id,filename,status,width,height,landmark_index,x,y,z")
                    scans(result).forEach { child ->
                        val row = scanRecord(child)
                        val points = row.getJSONArray("landmarks")
                        repeat(maxOf(1, points.length())) { index ->
                            val point = points.optJSONObject(index)
                            writer.appendLine(listOf(
                                result.id, row.getLong("imageId"), row.getString("filename"),
                                row.getString("status"), row.getInt("width"), row.getInt("height"),
                                if (point != null) index else null, point?.getDouble("x"),
                                point?.getDouble("y"), point?.getDouble("z")
                            ).joinToString(",") { csvCell(it) })
                        }
                    }
                    writer.flush()
                }
                csv
            }
        }
        check(file.isFile && file.length() > 0) { "Result file is missing" }
        val target = uri(task.input)
        val marker = outputFile(context, task.id, "export")
        val expected = file.inputStream().use { digest(it) }
        val observed = resolver.openInputStream(target)?.use { input ->
            file.inputStream().use { source -> comparePrefix(input, source) }
        } ?: error("Cannot read the selected destination")
        if (observed != file.length()) {
            require(observed == 0L || (observed > 0 && marker.isFile && marker.readText() == expected)) {
                "Destination contains an existing document. Create a new file"
            }
            if (!marker.isFile) atomic(marker) { it.write(expected.toByteArray(Charsets.UTF_8)) }
            resolver.openOutputStream(target, "wt")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
                output.flush()
            } ?: error("Cannot write the selected destination")
        }
        val verified = resolver.openInputStream(target)?.use { digest(it, file.length()) }
        check(expected == verified) { "Saved document failed readback verification" }
        val name = documentName(context, target)
        db.complete(task.id, "Saved $name · ${file.length()} bytes · result ${result.id}\n$target")
        marker.delete()
    }

    override fun close() {
        detector?.close()
        subjectDetector?.close()
    }
}

private fun uri(value: String) = Uri.parse(value).also {
    require(it.scheme == "content" && !it.authority.isNullOrBlank()) { "Choose a document through Android Files" }
}

private fun comparePrefix(target: InputStream, expected: InputStream): Long {
    val actual = target.buffered()
    val source = expected.buffered()
    var count = 0L
    while (true) {
        val value = actual.read()
        if (value == -1) return count
        if (source.read() != value) return -1
        count++
    }
}

internal fun atomic(file: File, write: (OutputStream) -> Unit) {
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        write(stream)
        stream.fd.sync()
        atomic.finishWrite(stream)
    } catch (error: Throwable) {
        atomic.failWrite(stream)
        throw error
    }
}

private fun copy(input: InputStream, output: OutputStream?, digest: MessageDigest, limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return total
        total += count
        require(total <= limit) { "File exceeds the supported byte limit" }
        digest.update(buffer, 0, count)
        output?.write(buffer, 0, count)
    }
}

private fun digest(input: InputStream, limit: Long = Long.MAX_VALUE): String {
    val hash = MessageDigest.getInstance("SHA-256")
    copy(input, null, hash, limit)
    return hex(hash.digest())
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

private fun bounds(file: File): BitmapFactory.Options = BitmapFactory.Options().apply {
    inJustDecodeBounds = true
    BitmapFactory.decodeFile(file.path, this)
    require(outWidth > 0 && outHeight > 0 && outWidth.toLong() * outHeight <= 150_000_000L) {
        "Unsupported, damaged, or oversized image"
    }
}

private fun orientation(file: File): Int = try {
    ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (_: java.io.IOException) { ExifInterface.ORIENTATION_UNDEFINED }

internal fun upright(file: File, limit: Int): Bitmap {
    val options = bounds(file)
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > limit) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: error("Image decoding failed")
    val matrix = Matrix()
    when (orientation(file)) {
        2 -> matrix.setScale(-1f, 1f)
        3 -> matrix.setRotate(180f)
        4 -> matrix.setScale(1f, -1f)
        5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        6 -> matrix.setRotate(90f)
        7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        8 -> matrix.setRotate(-90f)
    }
    return if (matrix.isIdentity) bitmap
    else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}

private fun differenceHash(bitmap: Bitmap): String {
    val thumb = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
    fun luma(pixel: Int) = 299 * Color.red(pixel) + 587 * Color.green(pixel) + 114 * Color.blue(pixel)
    var result = 0UL
    for (y in 0 until 8) for (x in 0 until 8) {
        result = result shl 1
        if (luma(thumb.getPixel(x, y)) > luma(thumb.getPixel(x + 1, y))) result = result or 1UL
    }
    if (thumb !== bitmap) thumb.recycle()
    return result.toString(16).padStart(16, '0')
}
