package com.homoludens.citacknjiga.core.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.ExportJobEntity
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.database.PlaybackPositionEntity
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class ProjectDeletionCoordinatorAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var databaseName: String
    private lateinit var storage: AppPrivateStorage
    private lateinit var projectFiles: List<File>
    private val cancelledRuns = mutableListOf<String>()
    private val stoppedProjects = mutableListOf<String>()

    @Before
    public fun setUp() {
        databaseName = "deletion-${UUID.randomUUID()}.db"
        database = AudiobookDatabase.create(context, databaseName)
        storage = AppPrivateStorage(createTempDirectory().toFile())
        seedProject()
    }

    @After
    public fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        storage.rootDirectory.deleteRecursively()
    }

    @Test
    public fun idleDeletionRemovesAllPrivateArtifactsAndCascadedRows() {
        val externalExport = File(storage.rootDirectory.parentFile, "export-${UUID.randomUUID()}.m4a").apply {
            writeText("keep")
        }
        val coordinator = coordinator()

        val result = coordinator.deleteProject("book")

        assertNotNull(result)
        assertEquals(listOf("run"), cancelledRuns)
        assertEquals(listOf("book"), stoppedProjects)
        assertFalse(projectFiles.any(File::exists))
        assertTrue(externalExport.exists())
        assertTrue(database.audiobookDao().findAllProjects().isEmpty())
        assertTrue(database.audiobookDao().findAllChapters().isEmpty())
        assertTrue(database.audiobookDao().findChapterWithRelations("chapter") == null)
        assertTrue(database.audiobookDao().findAllAudioSegments().isEmpty())
        assertTrue(database.audiobookDao().findAllGenerationRuns().isEmpty())
        assertTrue(database.audiobookDao().findAllExportJobs().isEmpty())
        assertTrue(database.audiobookDao().findPlaybackPosition("book") == null)
        assertTrue(externalExport.delete())
    }

    @Test
    public fun generatingAndPlayingDeletionCancelsWorkAndStopsPlaybackBeforeCleanup() {
        database.audiobookDao().updateProject(
            database.audiobookDao().findProjectById("book")!!.copy(status = BookProjectStatus.GENERATING),
        )

        coordinator().deleteProject("book")

        assertEquals(listOf("run"), cancelledRuns)
        assertEquals(listOf("book"), stoppedProjects)
        assertFalse(database.audiobookDao().findProjectById("book") != null)
    }

    @Test
    public fun deletingMarkerIsRecoveredByAnewCoordinatorAfterRestart() {
        assertTrue(ProjectOperationCoordinator(database).beginDeletion("book"))
        database.close()
        database = AudiobookDatabase.create(context, databaseName)
        val restarted = coordinator()

        val report = restarted.reconcileDeletingProjects()

        assertEquals(listOf("book"), report.deletedProjectIds)
        assertTrue(report.unfinishedProjectIds.isEmpty())
        assertFalse(database.audiobookDao().findProjectById("book") != null)
        assertFalse(projectFiles.any(File::exists))
    }

    private fun coordinator() = ProjectDeletionCoordinator(
        database = database,
        storage = storage,
        workCanceller = ProjectWorkCanceller { cancelledRuns += it },
        playbackStopper = ProjectPlaybackStopper { stoppedProjects += it },
    )

    private fun seedProject() {
        val dao = database.audiobookDao()
        val source = storage.sourceDocument("book").apply { parentFile!!.mkdirs(); writeText("private source") }
        val sourcePdf = storage.sourcePdf("book").apply { writeText("private pdf") }
        val canonical = storage.canonicalChapterText("book", "chapter").apply {
            parentFile!!.mkdirs()
            writeText("canonical")
        }
        val cover = storage.coverImage("book").apply { parentFile!!.mkdirs(); writeText("cover") }
        val warning = storage.importWarnings("book").apply { parentFile!!.mkdirs(); writeText("warning") }
        val audio = storage.readySegmentAudio("book", "chapter", "segment").apply {
            parentFile!!.mkdirs()
            writeText("audio")
        }
        val temporary = listOf(
            storage.temporaryFile("epub-book", "source.epub"),
            storage.temporaryFile("canonical-fingerprint", "partial.tmp"),
            storage.temporaryFile("generation-run-segment", "audio.tmp"),
            storage.temporaryFile("export-book", "chapter.audio"),
        ).onEach { it.parentFile!!.mkdirs(); it.writeText("temporary") }
        projectFiles = listOf(source, sourcePdf, canonical, cover, warning, audio) + temporary

        dao.insertProject(
            BookProjectEntity(
                id = "book",
                title = "Book",
                sourceUri = "content://external/original.epub",
                sourceFingerprint = "fingerprint",
                sourcePath = source.path,
                coverPath = cover.path,
                status = BookProjectStatus.READY,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        dao.insertChapter(ChapterEntity("chapter", "book", 0, "Chapter", canonicalMarkdownPath = canonical.path, status = ChapterStatus.READY, createdAt = 1, updatedAt = 1))
        dao.insertNarrationBlock(NarrationBlockEntity("block", "chapter", 0, NarrationBlockType.PARAGRAPH, "Text", createdAt = 1, updatedAt = 1))
        dao.insertModelPackage(
            ModelPackageEntity(
                id = "model",
                packageIdentity = "model@1",
                packageVersion = "1",
                packageSha256 = "package",
                modelSha256 = "model",
                voiceSha256 = "voice",
                preprocessingVersion = "prep",
                pronunciationVersion = "pron",
                packagePath = "model.zip",
                status = ModelPackageStatus.ACTIVE,
                importedAt = 1,
            ),
        )
        dao.insertGenerationRun(
            GenerationRunEntity(
                id = "run",
                bookProjectId = "book",
                modelPackageId = "model",
                preprocessingVersion = "prep",
                pronunciationVersion = "pron",
                inferenceSettingsHash = "settings",
                audioProcessingVersion = "audio",
                status = GenerationRunStatus.QUEUED,
                requestedAt = 1,
            ),
        )
        dao.insertAudioSegment(
            AudioSegmentEntity(
                id = "segment",
                chapterId = "chapter",
                narrationBlockId = "block",
                sequence = 0,
                chunkOrdinal = 0,
                status = AudioSegmentStatus.READY,
                audioPath = audio.path,
                audioSha256 = "hash",
                sizeBytes = audio.length(),
                durationMs = 1,
                generationRunId = "run",
                modelPackageId = "model",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        dao.savePlaybackPosition(PlaybackPositionEntity("book", "chapter", "segment", positionMs = 5, updatedAt = 1))
        dao.insertExportJob(
            ExportJobEntity(
                id = "export-job",
                bookProjectId = "book",
                destinationUri = "content://external/export",
                selectedChapterIdsJson = "[\"chapter\"]",
                totalChapters = 1,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }
}
