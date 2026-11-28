package io.github.drivesync.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.drivesync.core.data.db.DriveSyncDatabase
import io.github.drivesync.core.data.history.HistoryRepository
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.sync.model.FileFilters
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RunConditions
import io.github.drivesync.core.sync.store.BaseUpdate
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Upgrades a database built from the exported v1 schema and checks that existing rows survive. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @After
    fun deleteDb() {
        context.deleteDatabase(name)
    }

    @Test
    fun `v1 rows survive the upgrades`() =
        runTest {
            createV1()
            val db =
                Room
                    .databaseBuilder(context, DriveSyncDatabase::class.java, name)
                    .addMigrations(
                        DriveSyncDatabase.MIGRATION_1_2,
                        DriveSyncDatabase.MIGRATION_2_3,
                        DriveSyncDatabase.MIGRATION_3_4,
                        DriveSyncDatabase.MIGRATION_4_5,
                    ).allowMainThreadQueries()
                    .build()
            try {
                val row = db.base().entries("p1").single()
                assertEquals("notes/a.md", row.path)
                assertEquals("f1", row.remoteFileId)
                assertEquals(2L, row.remoteVersion)
                assertNull(row.remotePath)
                val pair = PairRepository(db).pair("p1")!!
                assertEquals(FileFilters(), pair.settings.filters)
                assertEquals(RunConditions(), pair.settings.conditions)
                // History starts empty; the first change saves the upgraded base as its starting point.
                val history = HistoryRepository(db)
                assertTrue(history.timeline("p1").isEmpty)
                PairRepository(db).baseStore("p1").commit(listOf(BaseUpdate.Remove(RelPath.of("notes/a.md"))))
                assertEquals(2, history.timeline("p1").points.size)
            } finally {
                db.close()
            }
        }

    /** The v1 tables exactly as Room exported them, plus one pair and one base row. */
    private fun createV1() {
        val schema = JSONObject(File(SCHEMA_V1).readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_NAME, table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(pairInsert(entities.getJSONObject(0)))
            db.execSQL(
                "INSERT INTO base_entries (pairId, path, isFolder, md5, localSize, localLastModified, " +
                    "remoteFileId, remoteVersion) VALUES ('p1', 'notes/a.md', 0, 'abc', 3, 1000, 'f1', 2)",
            )
            db.version = 1
        }
    }

    /** A pairs row with id p1, valid enum names, and a placeholder for every other column. */
    private fun pairInsert(pairs: JSONObject): String {
        val fields = pairs.getJSONArray("fields")
        val columns = mutableListOf<String>()
        val values = mutableListOf<String>()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            val column = field.getString("columnName")
            columns += column
            values +=
                when {
                    column == "id" -> "'p1'"
                    column == "direction" -> "'TWO_WAY'"
                    column == "conflictPolicy" -> "'KEEP_BOTH'"
                    column == "status" -> "'IDLE'"
                    field.getString("affinity") == "TEXT" -> "'x'"
                    else -> "0"
                }
        }
        return "INSERT INTO pairs (${columns.joinToString()}) VALUES (${values.joinToString()})"
    }

    private companion object {
        const val SCHEMA_V1 = "schemas/io.github.drivesync.core.data.db.DriveSyncDatabase/1.json"
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
