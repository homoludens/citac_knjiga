package com.homoludens.citacknjiga.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
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
    public fun newerDatabaseFailsWithoutDestructiveFallback() {
        context.openOrCreateDatabase(DATABASE_NAME, 0, null).let { database ->
            try {
                database.execSQL("CREATE TABLE future_table (id TEXT NOT NULL PRIMARY KEY)")
                database.version = 2
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
