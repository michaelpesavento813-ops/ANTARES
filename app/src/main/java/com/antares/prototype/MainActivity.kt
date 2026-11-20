package com.antares.prototype

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private var source: Bitmap? = null
    private var result: Bitmap? = null
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private val sourceFile get() = File(filesDir, "source.png")
    private val resultFile get() = File(filesDir, "result.png")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
        }
        val title = TextView(this).apply { text = "ANTARES — Prototype"; textSize = 25f }
        layout.addView(title)
        status = TextView(this).apply { text = "Select a photograph to begin."; textSize = 16f }
        layout.addView(status)
        preview = ImageView(this).apply { adjustViewBounds = true }
        layout.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        fun action(label: String, run: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { run() } })
        }
        action("Select Photo") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 10)
        }
        action("Restorative Repair (basic grayscale preview)") {
            val bitmap = source ?: return@action message("Select a photo first.")
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            Canvas(output).drawBitmap(bitmap, 0f, 0f, paint)
            result = output
            persist(resultFile, output)
            preview.setImageBitmap(output)
            status.text = "Grayscale preview generated. This is not full restoration."
        }
        action("Save As…") {
            if (result == null) return@action message("Process a photo first.")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "image/png"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "ANT0001.PNG")
            }, 20)
        }
        action("Deep Scan") { message("Deep Scan is not yet integrated.") }
        action("Make Me Pretty") { message("Make Me Pretty is not yet integrated.") }
        action("System Health") {
            message("Photo engine: basic only; MediaPipe: not integrated; SQLite: not integrated.")
        }
        setContentView(layout)
        if (sourceFile.exists()) {
            source = BitmapFactory.decodeFile(sourceFile.absolutePath)
            result = if (resultFile.exists()) BitmapFactory.decodeFile(resultFile.absolutePath) else null
            preview.setImageBitmap(result ?: source)
            status.text = "Previous session restored."
        }
    }
    private fun message(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun persist(file: File, bitmap: Bitmap) {
        file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) error("PNG encode failed") }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            when (requestCode) {
                10 -> {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                    val longest = maxOf(options.outWidth, options.outHeight)
                    require(longest > 0) { "Unsupported image" }
                    val sample = (longest / 2048).coerceAtLeast(1)
                    val decoded = contentResolver.openInputStream(uri).use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: error("Could not decode photo")
                    source = decoded
                    result = null
                    persist(sourceFile, decoded)
                    resultFile.delete()
                    preview.setImageBitmap(decoded)
                    status.text = "Photo loaded (${decoded.width} × ${decoded.height})."
                }
                20 -> {
                    val bitmap = result ?: error("No result to save")
                    contentResolver.openOutputStream(uri, "wt")?.use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encode failed" }
                    } ?: error("Could not open destination")
                    status.text = "Saved PNG to selected destination."
                }
            }
        } catch (e: Exception) {
            status.text = "Error: ${e.message}"
            message(status.text.toString())
        }
    }
}
