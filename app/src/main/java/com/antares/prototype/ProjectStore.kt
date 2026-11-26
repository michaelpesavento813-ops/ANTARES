package com.antares.prototype

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

internal class ProjectStore(context: Context) :
    SQLiteOpenHelper(context, "antares.db", null, 1) {

    data class Project(val id: Long, val name: String)
    data class Task(
        val id: Long, val projectId: Long, val kind: String, val input: String,
        val label: String, val status: String, val detail: String, val parent: Long?,
        val hash: String?, val bytes: Long, val width: Int, val height: Int
    ) {
        val imageAlias: String get() = exportName("IMG", id, "IMG").substringBefore('.')
    }

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE project(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)")
        db.execSQL("""
            CREATE TABLE task(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL REFERENCES project(id),
                kind TEXT NOT NULL CHECK(kind IN ('import','scan','batch','png','json','csv','save_png')),
                input TEXT NOT NULL,
                label TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','done','failed')),
                detail TEXT NOT NULL DEFAULT '',
                parent INTEGER REFERENCES task(id),
                hash TEXT, bytes INTEGER DEFAULT 0, width INTEGER DEFAULT 0,
                height INTEGER DEFAULT 0, orientation INTEGER, perceptual_hash TEXT,
                created INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX queue ON task(status, id)")
        db.execSQL("CREATE INDEX project_tasks ON task(project_id, id)")
        db.execSQL("INSERT INTO project(name) VALUES ('My photos')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Database migration is required for version $newVersion")

    fun projects(): List<Project> = readableDatabase.rawQuery("SELECT id,name FROM project ORDER BY id", null).use {
        buildList { while (it.moveToNext()) add(Project(it.getLong(0), it.getString(1))) }
    }

    fun addProject(name: String): Long = writableDatabase.insertOrThrow("project", null, ContentValues().apply {
        put("name", name.trim().also { require(it.isNotEmpty() && it.length <= 80) })
    })

    fun queue(
        project: Long, kind: String, input: String, label: String, parent: Long? = null
    ): Long = writableDatabase.insertOrThrow("task", null, ContentValues().apply {
        put("project_id", project)
        put("kind", kind)
        put("input", input)
        put("label", label)
        put("parent", parent)
        put("created", System.currentTimeMillis())
    })

    fun queueBatch(project: Long): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val photos = photos(project)
            require(photos.isNotEmpty()) { "Add photos before starting a project scan" }
            val children = photos.map { queue(project, "scan", "", "Deep Scan · ${it.imageAlias}", it.id) }
            val batch = queue(project, "batch", children.joinToString(","), "Project scan")
            db.setTransactionSuccessful()
            return batch
        } finally { db.endTransaction() }
    }

    private fun tasks(where: String, arguments: Array<String>): List<Task> = readableDatabase.rawQuery(
        "SELECT id,project_id,kind,input,label,status,detail,parent,hash,bytes,width,height FROM task $where", arguments
    ).use {
        buildList {
            while (it.moveToNext()) add(Task(
                it.getLong(0), it.getLong(1), it.getString(2), it.getString(3),
                it.getString(4), it.getString(5), it.getString(6),
                if (it.isNull(7)) null else it.getLong(7), it.getString(8),
                it.getLong(9), it.getInt(10), it.getInt(11)
            ))
        }
    }

    fun queuedCount(project: Long): Int = readableDatabase.rawQuery(
        "SELECT count(*) FROM task WHERE project_id=? AND status='queued'", arrayOf(project.toString())
    ).use { it.moveToFirst(); it.getInt(0) }

    fun next() = tasks("WHERE status='queued' ORDER BY id LIMIT 1", emptyArray()).firstOrNull()
    fun task(id: Long) = tasks("WHERE id=?", arrayOf(id.toString())).singleOrNull()
    fun recent(project: Long) = tasks("WHERE project_id=? ORDER BY id DESC LIMIT 30", arrayOf(project.toString()))
    fun photos(project: Long) = tasks("WHERE project_id=? AND kind='import' AND status='done' ORDER BY id", arrayOf(project.toString()))
    fun latestBatch(project: Long) = tasks(
        "WHERE project_id=? AND kind='batch' AND status='done' ORDER BY id DESC LIMIT 1",
        arrayOf(project.toString())
    ).firstOrNull()
    fun result(project: Long, kind: String, parent: Long) = tasks(
        "WHERE project_id=? AND kind=? AND parent=? AND status='done' ORDER BY id DESC LIMIT 1",
        arrayOf(project.toString(), kind, parent.toString())
    ).firstOrNull()
    fun duplicate(project: Long, hash: String) = tasks(
        "WHERE project_id=? AND hash=? AND kind='import' AND status='done' ORDER BY id LIMIT 1",
        arrayOf(project.toString(), hash)
    ).firstOrNull()

    fun complete(id: Long, detail: String, metadata: ImageMetadata? = null) {
        update(id, ContentValues().apply {
            put("status", "done")
            put("detail", detail)
            if (metadata != null) {
                put("hash", metadata.hash)
                put("bytes", metadata.bytes)
                put("width", metadata.width)
                put("height", metadata.height)
                put("orientation", metadata.orientation)
                put("perceptual_hash", metadata.perceptualHash)
            }
        })
    }

    fun fail(id: Long, detail: String) = update(id, ContentValues().apply {
        put("status", "failed")
        put("detail", detail)
    })

    fun retry(id: Long) = update(id, ContentValues().apply {
        put("status", "queued")
        put("detail", "")
    })

    private fun update(id: Long, values: ContentValues) {
        check(writableDatabase.update("task", values, "id=?", arrayOf(id.toString())) == 1)
    }
}

internal data class ImageMetadata(
    val hash: String, val bytes: Long, val width: Int, val height: Int,
    val orientation: Int, val perceptualHash: String
)

internal fun exportName(prefix: String, id: Long, ext: String): String {
    require(id > 0 && prefix.matches(Regex("[A-Z]{1,3}")) && ext.matches(Regex("[A-Z]{3}")))
    val sequence = id.toString(36).uppercase(Locale.ROOT)
    return "$prefix${sequence.padStart(8 - prefix.length, '0')}.$ext"
}

internal fun csvCell(value: Any?): String {
    val text = value?.toString().orEmpty()
    val safe = if (value is String && (
            text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
            text.firstOrNull() in listOf('\t', '\r', '\n')
        )) "'$text" else text
    return "\"${safe.replace("\"", "\"\"")}\""
}
