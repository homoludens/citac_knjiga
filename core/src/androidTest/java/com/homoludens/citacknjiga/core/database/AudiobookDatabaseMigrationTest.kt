package com.homoludens.citacknjiga.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

public class AudiobookDatabaseMigrationTest {
    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    public val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AudiobookDatabase::class.java,
    )

    @After
    public fun deleteTestDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    @Throws(IOException::class)
    public fun versionOneCreatesEveryRegisteredTable() {
        migrationTestHelper.createDatabase(DATABASE_NAME, 1).close()
        migrationTestHelper.runMigrationsAndValidate(DATABASE_NAME, 1, true).use { database ->
            val tables = database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }

            assertEquals(1, database.version)
            assertTrue(tables.containsAll(EXPECTED_TABLES))
        }
    }

    @Test
    @Throws(IOException::class)
    public fun versionOneMigratesExportCheckpoints() {
        migrationTestHelper.createDatabase(DATABASE_NAME, 1).close()
        migrationTestHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            AudiobookDatabase.MIGRATION_1_2,
        ).use { database ->
            val tables = database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertEquals(2, database.version)
            assertTrue(tables.contains("export_job_chapter"))
        }
    }

    @Test
    @Throws(IOException::class)
    public fun versionOneDataSurvivesExportCheckpointMigration() {
        migrationTestHelper.createDatabase(DATABASE_NAME, 1).use { database ->
            database.execSQL(
                """
                INSERT INTO book_project
                    (id, title, author, source_uri, source_fingerprint, language, status, created_at, updated_at)
                VALUES ('book', 'Book', 'Author', 'content://book', 'fingerprint', 'sr', 'READY', 1, 1)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO export_job
                    (id, book_project_id, destination_uri, selected_chapter_ids_json,
                     total_chapters, completed_chapters, status, created_at, updated_at)
                VALUES ('job', 'book', 'content://export', '[]', 0, 0, 'QUEUED', 2, 2)
                """.trimIndent(),
            )
        }

        migrationTestHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            AudiobookDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query("SELECT title FROM book_project WHERE id = 'book'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Book", cursor.getString(0))
            }
            database.query("SELECT destination_uri, manifest_name, cover_name FROM export_job WHERE id = 'job'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("content://export", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertTrue(cursor.isNull(2))
                }
        }
    }

    @Test
    @Throws(IOException::class)
    public fun versionThreeMigratesDeletingStateAndWordEstimate() {
        migrationTestHelper.createDatabase(DATABASE_NAME, 3).use { database ->
            database.execSQL(
                """
                INSERT INTO book_project
                    (id, title, source_uri, source_fingerprint, language, status, created_at, updated_at)
                VALUES ('book', 'Book', 'content://book', 'fingerprint', 'sr', 'READY', 1, 1)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO chapter
                    (id, book_project_id, ordinal, title, status, created_at, updated_at)
                VALUES ('chapter', 'book', 0, 'Chapter', 'READY', 1, 1)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO narration_block
                    (id, chapter_id, ordinal, block_type, source_text, status, created_at, updated_at)
                VALUES ('block', 'chapter', 0, 'PARAGRAPH', 'Text', 'PROCESSED', 1, 1)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO audio_segment
                    (id, chapter_id, narration_block_id, sequence, chunk_ordinal,
                     status, attempt_count, created_at, updated_at)
                VALUES ('segment', 'chapter', 'block', 0, 0, 'READY', 0, 1, 1)
                """.trimIndent(),
            )
        }

        migrationTestHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            AudiobookDatabase.MIGRATION_3_4,
        ).use { database ->
            assertEquals(4, database.version)
            database.query("SELECT is_deleting FROM book_project WHERE id = 'book'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT estimated_word_count FROM audio_segment WHERE id = 'segment'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        }

        val database = AudiobookDatabase.create(context, DATABASE_NAME)
        try {
            val dao = database.audiobookDao()
            val project = dao.findProjectById("book")!!
            val segment = dao.findAudioSegmentById("segment")!!
            assertFalse(project.isDeleting)
            assertEquals(null, segment.estimatedWordCount)
            dao.updateProject(project.copy(isDeleting = true))
            dao.updateAudioSegment(segment.copy(estimatedWordCount = 42))
        } finally {
            database.close()
        }

        AudiobookDatabase.create(context, DATABASE_NAME).let { database ->
            try {
                assertTrue(database.audiobookDao().findProjectById("book")!!.isDeleting)
                assertEquals(42, database.audiobookDao().findAudioSegmentById("segment")!!.estimatedWordCount)
            } finally {
                database.close()
            }
        }
    }

    @Test
    public fun newerDatabaseFailsWithoutDestructiveFallback() {
        context.openOrCreateDatabase(DATABASE_NAME, 0, null).let { database ->
            try {
                database.execSQL("CREATE TABLE future_table (id TEXT NOT NULL PRIMARY KEY)")
                database.version = 5
            } finally {
                database.close()
            }
        }

        val database = AudiobookDatabase.create(context, DATABASE_NAME)
        try {
            assertThrows(IllegalStateException::class.java) {
                database.openHelper.writableDatabase
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DATABASE_NAME = "audiobook-migration-test.db"
        val EXPECTED_TABLES = setOf(
            "book_project",
            "chapter",
            "narration_block",
            "audio_segment",
            "generation_run",
            "model_package",
            "playback_position",
            "export_job",
        )
    }
}
