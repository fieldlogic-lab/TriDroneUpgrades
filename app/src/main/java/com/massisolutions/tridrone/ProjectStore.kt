package com.massisolutions.tridrone

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

data class SurveyProject(
    val id: String,
    val name: String,
    val description: String?,
    val createdUtc: Long,
    val modifiedUtc: Long,
    val archived: Boolean,
    val offlineMapReference: String?
)

enum class CollectionStatus { RECORDING, COMPLETED, INTERRUPTED, IMPORTED }

data class CollectionSession(
    val id: String,
    val projectId: String,
    val number: Long,
    val name: String,
    val startedUtc: Long,
    val endedUtc: Long?,
    val status: CollectionStatus,
    val endTimeBasis: String?,
    val observationCount: Long,
    val validCount: Long,
    val metadata: JSONObject,
    val legacyStem: String?,
    val csvHeader: String
)

data class SurveyPosition(
    val observationId: String,
    val sessionId: String,
    val utcMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val rawDepthMeters: Double?,
    val valid: Boolean
)

/** All operations use app-private storage. Run calls that can scan or export files off the UI thread. */
class ProjectStore internal constructor(
    context: Context,
    databaseName: String = "survey_projects.db",
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val helper = Database(context, databaseName)
    private val files = context.filesDir
    private val hydroFolder = File(files, "hydro_surveys")
    private val db: SQLiteDatabase by lazy {
        helper.writableDatabase.also {
            recoverInterrupted(it)
            importLegacy(it)
        }
    }
    private val warnings = mutableListOf<String>()
    val migrationWarnings: List<String> get() {
        // Initialize the database and run migration before publishing warnings.
        activeProject()
        return warnings.toList()
    }

    fun projects(): List<SurveyProject> = db.rawQuery(
        "SELECT * FROM projects ORDER BY archived, modified_utc DESC, id", null
    ).use { rows -> buildList { while (rows.moveToNext()) add(rows.project()) } }

    fun activeProject(): SurveyProject? = db.rawQuery(
        """SELECT projects.* FROM projects JOIN app_state ON projects.id = app_state.active_project_id
            WHERE app_state.singleton = 1 AND projects.archived = 0""", null
    ).use { if (it.moveToFirst()) it.project() else null }

    fun createProject(name: String, description: String? = null): SurveyProject = db.transaction {
        val project = insertProject(this, name, description, clock())
        select(this, project.id)
        project
    }

    fun selectProject(id: String) {
        db.transaction {
            check(project(this, id)?.archived == false) { "Select an available project" }
            select(this, id)
        }
    }

    fun editProject(id: String, name: String, description: String?) {
        val values = ContentValues().apply {
            put("name", requiredName(name))
            put("description", description?.trim()?.takeIf { it.isNotEmpty() })
            put("modified_utc", clock())
        }
        check(db.update("projects", values, "id = ?", arrayOf(id)) == 1) { "Project not found" }
    }

    fun archiveProject(id: String, archived: Boolean = true) {
        db.transaction {
            if (archived) {
                rawQuery(
                    "SELECT 1 FROM sessions WHERE project_id = ? AND status = 'RECORDING'", arrayOf(id)
                ).use { check(!it.moveToFirst()) { "Stop recording before archiving this project" } }
                update("app_state", ContentValues().apply { putNull("active_project_id") },
                    "active_project_id = ?", arrayOf(id))
            }
            val values = ContentValues().apply {
                put("archived", if (archived) 1 else 0)
                put("modified_utc", clock())
            }
            check(update("projects", values, "id = ?", arrayOf(id)) == 1) { "Project not found" }
        }
    }

    fun sessions(projectId: String): List<CollectionSession> = db.rawQuery(
        "$SESSION_QUERY WHERE s.project_id = ? ORDER BY s.number", arrayOf(projectId)
    ).use { rows -> buildList { while (rows.moveToNext()) add(rows.session()) } }

    fun session(id: String): CollectionSession? = db.rawQuery(
        "$SESSION_QUERY WHERE s.id = ?", arrayOf(id)
    ).use { if (it.moveToFirst()) it.session() else null }

    fun renameSession(id: String, name: String) {
        db.transaction {
            val projectId = sessionProject(this, id)
            update("sessions", ContentValues().apply { put("name", requiredName(name)) },
                "id = ?", arrayOf(id))
            touchProject(this, projectId, clock())
        }
    }

    fun startSession(
        projectId: String,
        startedUtc: Long,
        metadata: JSONObject,
        legacyStem: String? = null
    ): CollectionSession = db.transaction {
        val selected = checkNotNull(project(this, projectId)) { "Select an available project" }
        val activeId = rawQuery("SELECT active_project_id FROM app_state WHERE singleton = 1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        check(!selected.archived && activeId == projectId) { "Select an available project" }
        val snapshot = JSONObject(metadata.toString()).put("project_id", selected.id).put("project", selected.name)
        require(legacyStem == null || SAFE_STEM.matches(legacyStem)) { "Invalid recording name" }
        val session = insertSession(this, projectId, startedUtc, snapshot, legacyStem,
            HydroCsv.headerFor(snapshot.optString("protocol")).trimEnd('\n'), CollectionStatus.RECORDING)
        touchProject(this, projectId, startedUtc)
        session
    }

    /** An observation is counted only after its SQLite write commits. */
    fun append(sessionId: String, depth: HydroDepth, paired: HydroGnss?, csvRow: String) {
        db.compileStatement(
            """INSERT INTO observations(id, session_id, project_id, utc_ms, elapsed_ns, latitude,
                longitude, raw_depth_m, valid, csv_row)
                SELECT ?, s.id, s.project_id, ?, ?, ?, ?, ?, ?, ? FROM sessions s
                WHERE s.id = ? AND s.status = 'RECORDING' AND s.csv_header = ?"""
        ).use { statement ->
            statement.bindString(1, UUID.randomUUID().toString())
            statement.bindLong(2, depth.utcMillis)
            statement.bindLong(3, depth.elapsedNanos)
            statement.bindNullable(4, paired?.latitude)
            statement.bindNullable(5, paired?.longitude)
            statement.bindNullable(6, depth.meters)
            statement.bindLong(7, if (depth.valid && paired != null) 1 else 0)
            statement.bindString(8, csvRow.removeSuffix("\n"))
            statement.bindString(9, sessionId)
            statement.bindString(10, HydroCsv.headerFor(depth.protocol).trimEnd('\n'))
            check(statement.executeInsert() != -1L) { "No active collection for this sounder protocol" }
        }
    }

    fun finishSession(sessionId: String, endedUtc: Long, interrupted: Boolean = false) {
        db.transaction {
            val projectId = sessionProject(this, sessionId)
            val values = ContentValues().apply {
                put("ended_utc", endedUtc)
                put("end_time_basis", "recording stop time")
                put("status", if (interrupted) CollectionStatus.INTERRUPTED.name else CollectionStatus.COMPLETED.name)
            }
            if (update("sessions", values, "id = ? AND status = 'RECORDING'", arrayOf(sessionId)) > 0) {
                touchProject(this, projectId, endedUtc)
            }
        }
    }

    fun positionsForProject(
        id: String, afterUtc: Long = Long.MIN_VALUE, afterId: String = "", limit: Int = 1000
    ): List<SurveyPosition> = positions("project_id", id, afterUtc, afterId, limit)

    fun positionsForSession(
        id: String, afterUtc: Long = Long.MIN_VALUE, afterId: String = "", limit: Int = 1000, validOnly: Boolean = false
    ): List<SurveyPosition> = positions("session_id", id, afterUtc, afterId, limit, validOnly)

    private fun positions(
        column: String, id: String, afterUtc: Long, afterId: String, limit: Int, validOnly: Boolean = false
    ): List<SurveyPosition> {
        require(limit > 0)
        return db.rawQuery(
            """SELECT id, session_id, utc_ms, latitude, longitude, raw_depth_m, valid FROM observations
                WHERE $column = ? AND latitude IS NOT NULL AND longitude IS NOT NULL
                ${if (validOnly) "AND valid = 1" else ""}
                AND utc_ms >= ? AND (utc_ms > ? OR id > ?)
                ORDER BY utc_ms, id LIMIT ?""",
            arrayOf(id, afterUtc.toString(), afterUtc.toString(), afterId, limit.toString())
        ).use { rows -> buildList {
            while (rows.moveToNext()) add(SurveyPosition(
                rows.getString(0), rows.getString(1), rows.getLong(2), rows.getDouble(3), rows.getDouble(4),
                if (rows.isNull(5)) null else rows.getDouble(5), rows.getInt(6) != 0
            ))
        } }
    }

    fun exportSession(id: String, output: OutputStream) =
        exporter().session(requireFinished(id), output)

    fun exportAudit(id: String, output: OutputStream) =
        exporter().audit(requireFinished(id), output)

    fun exportProject(id: String, output: OutputStream) {
        val project = checkNotNull(project(db, id)) { "Project not found" }
        val sessions = sessions(id)
        check(sessions.none { it.status == CollectionStatus.RECORDING }) { "Stop recording before exporting this project" }
        exporter().project(project, sessions, output)
    }

    fun previewRows(id: String, limit: Int = 100): String {
        require(limit > 0)
        val collection = checkNotNull(session(id)) { "Collection not found" }
        return buildString {
            appendLine(collection.csvHeader)
            db.rawQuery("SELECT csv_row FROM observations WHERE session_id = ? ORDER BY sequence LIMIT ?",
                arrayOf(id, limit.toString())).use { rows ->
                while (rows.moveToNext()) appendLine(rows.getString(0))
            }
        }
    }

    private fun requireFinished(id: String): CollectionSession {
        val collection = checkNotNull(session(id)) { "Collection not found" }
        check(collection.status != CollectionStatus.RECORDING) { "Stop recording before exporting this collection" }
        return collection
    }

    private fun exporter() = ProjectExport(db, files)

    internal fun close() = helper.close()

    private fun recoverInterrupted(database: SQLiteDatabase) {
        database.transaction {
            rawQuery("SELECT DISTINCT project_id FROM sessions WHERE status = 'RECORDING'", null).use { projects ->
                while (projects.moveToNext()) touchProject(this, projects.getString(0), clock())
            }
            execSQL("""UPDATE sessions SET status = 'INTERRUPTED',
                end_time_basis = 'last committed observation or start when empty after process loss',
                ended_utc = max(started_utc,
                coalesce((SELECT max(utc_ms) FROM observations WHERE session_id = sessions.id), started_utc))
                WHERE status = 'RECORDING'""")
        }
    }

    private fun importLegacy(database: SQLiteDatabase) {
        if (!hydroFolder.exists()) return
        val sources = hydroFolder.listFiles { file -> file.isFile && LEGACY_CSV.matches(file.name) }
            ?: error("Cannot read legacy survey storage")
        for (file in sources.sortedBy { it.name }) {
            val stem = file.nameWithoutExtension
            val imported = database.rawQuery(
                "SELECT metadata_json FROM sessions WHERE legacy_stem = ?", arrayOf(stem)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (imported != null) {
                val rejected = JSONObject(imported).optLong("unimported_record_count")
                if (rejected > 0) warnings.add("${file.name}: $rejected records remain in the original file.")
                continue
            }
            try {
                val rejected = file.bufferedReader().use { reader ->
                    val header = reader.readLine().orEmpty().removePrefix("\uFEFF")
                    check(header == HydroCsv.header.trimEnd('\n') || header == HydroCsv.ceeHeader.trimEnd('\n')) {
                        "Unrecognized CSV columns"
                    }
                    val (projectName, metadata) = legacyMetadata(stem)
                    database.transaction {
                        val started = metadata.optLong("started_utc_ms", stem.removePrefix("hydro_").toLong())
                        val projectId = legacyProject(this, projectName, started)
                        val collection = insertSession(this, projectId, started, metadata, stem,
                            header, CollectionStatus.IMPORTED)
                        importLegacyRows(this, reader, collection, stem, metadata)
                    }
                }
                if (rejected > 0) warnings.add("${file.name}: $rejected records remain in the original file.")
            } catch (_: SQLiteFullException) {
                warnings.add("${file.name}: Device storage is full. Original files were retained. Free space and reopen TriDrone to retry.")
                break
            } catch (exception: java.io.IOException) {
                warnings.add("${file.name}: ${exception.message ?: "Cannot read original survey"}")
            } catch (exception: IllegalStateException) {
                warnings.add("${file.name}: ${exception.message ?: "Cannot import original survey"}")
            }
        }
    }

    private fun legacyMetadata(stem: String): Pair<String, JSONObject> {
        val file = File(hydroFolder, "$stem.metadata.json")
        val metadata = if (file.isFile) try {
            JSONObject(file.readText())
        } catch (_: org.json.JSONException) {
            JSONObject().put("original_metadata_status", "UNPARSEABLE_ORIGINAL_RETAINED")
        } else JSONObject().put("original_metadata_status", "MISSING")
        val name = metadata.optString("project").trim().ifEmpty { "Recovered survey collections" }
        return name to metadata
    }

    private fun legacyProject(database: SQLiteDatabase, name: String, started: Long): String {
        val existing = database.rawQuery("SELECT id FROM projects WHERE legacy_group = ?", arrayOf(name))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        return existing ?: insertProject(database, name, "Imported from existing live survey files.", started, name).id
    }

    private fun importLegacyRows(
        database: SQLiteDatabase,
        reader: java.io.BufferedReader,
        collection: CollectionSession,
        stem: String,
        metadata: JSONObject
    ): Long {
        var rejected = 0L
        var ended = collection.startedUtc
        val index = collection.csvHeader.split(',').withIndex().associate { it.value to it.index }
        fun List<String>.field(name: String) = this[index.getValue(name)]
        val records = HydroCsvRecords(reader)
        while (true) {
            val record = records.next() ?: break
            if (record.raw.isBlank()) continue
            val fields = record.cells
            val utc = fields?.firstOrNull()?.toLongOrNull()
            val elapsed = fields?.getOrNull(1)?.toLongOrNull()
            if (fields == null || fields.size != index.size || utc == null || elapsed == null) {
                rejected++
                continue
            }
            val values = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("session_id", collection.id)
                put("project_id", collection.projectId)
                put("utc_ms", utc)
                put("elapsed_ns", elapsed)
                put("latitude", fields.field("latitude_deg").toDoubleOrNull())
                put("longitude", fields.field("longitude_deg").toDoubleOrNull())
                put("raw_depth_m", fields.field("raw_depth_m").toDoubleOrNull())
                val valid = fields.field("depth_validity") == "VALID_PROVISIONAL" &&
                    fields.field("pairing_status") == "PAIRED_RECEIPT_TIME"
                put("valid", if (valid) 1 else 0)
                put("csv_row", record.raw)
            }
            database.insertOrThrow("observations", null, values)
            ended = maxOf(ended, utc)
        }
        metadata.put("legacy_source_basename", stem)
            .put("unimported_record_count", rejected)
            .put("end_time_basis", "last imported observation or start when empty")
        val values = ContentValues().apply {
            put("ended_utc", ended)
            put("end_time_basis", "last imported observation or start when empty")
            put("metadata_json", metadata.toString())
        }
        database.update("sessions", values, "id = ?", arrayOf(collection.id))
        touchProject(database, collection.projectId, ended)
        return rejected
    }

    private fun insertProject(
        database: SQLiteDatabase, name: String, description: String?, now: Long, legacyGroup: String? = null
    ): SurveyProject {
        val project = SurveyProject(UUID.randomUUID().toString(), requiredName(name),
            description?.trim()?.takeIf { it.isNotEmpty() }, now, now, false, null)
        val values = ContentValues().apply {
            put("id", project.id)
            put("name", project.name)
            put("description", project.description)
            put("created_utc", now)
            put("modified_utc", now)
            put("legacy_group", legacyGroup)
        }
        database.insertOrThrow("projects", null, values)
        return project
    }

    private fun insertSession(
        database: SQLiteDatabase, projectId: String, startedUtc: Long, metadata: JSONObject,
        legacyStem: String?, csvHeader: String, status: CollectionStatus
    ): CollectionSession {
        val number = database.rawQuery(
            "SELECT coalesce(max(number), 0) + 1 FROM sessions WHERE project_id = ?", arrayOf(projectId)
        ).use { it.moveToFirst(); it.getLong(0) }
        val id = UUID.randomUUID().toString()
        val name = String.format(Locale.US, "Collection %03d", number)
        val values = ContentValues().apply {
            put("id", id)
            put("project_id", projectId)
            put("number", number)
            put("name", name)
            put("started_utc", startedUtc)
            put("status", status.name)
            put("metadata_json", metadata.toString())
            put("legacy_stem", legacyStem)
            put("csv_header", csvHeader)
        }
        database.insertOrThrow("sessions", null, values)
        return CollectionSession(id, projectId, number, name, startedUtc, null, status, null, 0, 0, metadata,
            legacyStem, csvHeader)
    }

    private fun project(database: SQLiteDatabase, id: String): SurveyProject? =
        database.rawQuery("SELECT * FROM projects WHERE id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.project() else null }

    private fun sessionProject(database: SQLiteDatabase, id: String): String =
        database.rawQuery("SELECT project_id FROM sessions WHERE id = ?", arrayOf(id)).use {
            check(it.moveToFirst()) { "Collection not found" }
            it.getString(0)
        }

    private fun select(database: SQLiteDatabase, projectId: String) {
        database.execSQL("UPDATE app_state SET active_project_id = ? WHERE singleton = 1", arrayOf(projectId))
    }

    private fun touchProject(database: SQLiteDatabase, projectId: String, now: Long) {
        database.execSQL("UPDATE projects SET modified_utc = max(modified_utc, ?) WHERE id = ?",
            arrayOf(now, projectId))
    }

    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1, {
        // Let SQLite report corruption without the default handler deleting the survey files.
    }) {
        init { setWriteAheadLoggingEnabled(true) }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE projects(
                id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, description TEXT,
                created_utc INTEGER NOT NULL, modified_utc INTEGER NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0, 1)),
                offline_map_reference TEXT, legacy_group TEXT UNIQUE)""")
            db.execSQL("""CREATE TABLE sessions(
                id TEXT PRIMARY KEY NOT NULL, project_id TEXT NOT NULL REFERENCES projects(id),
                number INTEGER NOT NULL, name TEXT NOT NULL, started_utc INTEGER NOT NULL,
                ended_utc INTEGER, end_time_basis TEXT, status TEXT NOT NULL CHECK(status IN ('RECORDING','COMPLETED','INTERRUPTED','IMPORTED')),
                metadata_json TEXT NOT NULL, legacy_stem TEXT UNIQUE, csv_header TEXT NOT NULL,
                UNIQUE(project_id, number), UNIQUE(id, project_id))""")
            db.execSQL("CREATE UNIQUE INDEX only_one_recording ON sessions(status) WHERE status = 'RECORDING'")
            db.execSQL("""CREATE TABLE observations(
                sequence INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE,
                session_id TEXT NOT NULL, project_id TEXT NOT NULL,
                utc_ms INTEGER NOT NULL, elapsed_ns INTEGER NOT NULL,
                latitude REAL, longitude REAL, raw_depth_m REAL, valid INTEGER NOT NULL CHECK(valid IN (0, 1)),
                csv_row TEXT NOT NULL,
                FOREIGN KEY(session_id, project_id) REFERENCES sessions(id, project_id))""")
            db.execSQL("CREATE INDEX observations_session_order ON observations(session_id, sequence)")
            db.execSQL("CREATE INDEX observations_session_valid ON observations(session_id) WHERE valid = 1")
            db.execSQL("CREATE INDEX observations_session_time ON observations(session_id, utc_ms, id)")
            db.execSQL("CREATE INDEX observations_project_time ON observations(project_id, utc_ms, id)")
            db.execSQL("""CREATE TABLE app_state(
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                active_project_id TEXT REFERENCES projects(id))""")
            db.execSQL("INSERT INTO app_state(singleton) VALUES(1)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported project database migration $oldVersion to $newVersion")
        }
    }

    companion object {
        private val SAFE_STEM = Regex("hydro_[A-Za-z0-9_-]+")
        private val LEGACY_CSV = Regex("hydro_[0-9]{1,18}\\.csv")
        private const val SESSION_QUERY = """SELECT s.*,
            (SELECT count(*) FROM observations o WHERE o.session_id = s.id) AS event_count,
            (SELECT count(*) FROM observations o WHERE o.session_id = s.id AND o.valid = 1) AS valid_count
            FROM sessions s"""
        @Volatile private var instance: ProjectStore? = null

        @Synchronized fun get(context: Context): ProjectStore =
            instance ?: ProjectStore(context.applicationContext).also { instance = it }
    }
}

private fun requiredName(name: String) = name.trim().also { require(it.isNotEmpty()) { "Enter a name" } }

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransactionNonExclusive()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

private fun SQLiteStatement.bindNullable(index: Int, value: Double?) {
    if (value == null) bindNull(index) else bindDouble(index, value)
}

private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
private fun Cursor.nullableString(name: String): String? =
    getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
private fun Cursor.nullableLong(name: String): Long? =
    getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }

private fun Cursor.project() = SurveyProject(string("id"), string("name"), nullableString("description"),
    long("created_utc"), long("modified_utc"), long("archived") != 0L, nullableString("offline_map_reference"))

private fun Cursor.session() = CollectionSession(string("id"), string("project_id"), long("number"), string("name"),
    long("started_utc"), nullableLong("ended_utc"), CollectionStatus.valueOf(string("status")),
    nullableString("end_time_basis"), long("event_count"), long("valid_count"), JSONObject(string("metadata_json")), nullableString("legacy_stem"),
    string("csv_header"))
