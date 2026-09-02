package com.homoludens.citacknjiga.core.generation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class DurableGenerationCoordinatorAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var databaseName: String
    private val executedEngines = mutableListOf<String>()

    @Before
    public fun setUp() {
        databaseName = "durable-generation-${UUID.randomUUID()}.db"
        database = AudiobookDatabase.create(context, databaseName)
        val dao = database.audiobookDao()
        dao.insertProject(
            BookProjectEntity(
                id = "book",
                title = "Book",
                sourceUri = "content://book",
                sourceFingerprint = "fingerprint",
                status = BookProjectStatus.READY,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        listOf("kokoro" to 0, "vits" to 1).forEach { (engine, ordinal) ->
            dao.insertChapter(
                ChapterEntity(
                    id = "$engine-chapter",
                    bookProjectId = "book",
                    ordinal = ordinal,
                    title = engine,
                    status = ChapterStatus.PENDING,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            dao.insertNarrationBlock(
                NarrationBlockEntity(
                    id = "$engine-block",
                    chapterId = "$engine-chapter",
                    ordinal = 0,
                    blockType = NarrationBlockType.PARAGRAPH,
                    sourceText = "Text for $engine.",
                    status = NarrationBlockStatus.PROCESSED,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        }
    }

    @After
    public fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    public fun bothEnginesPersistRunsSegmentsAndSelectedProvenance() {
        val queued = GenerationEngine.entries.map { engine ->
            coordinator().queue(request(engine))
        }
        val dao = database.audiobookDao()

        GenerationEngine.entries.forEachIndexed { index, engine ->
            val run = dao.findGenerationRunById(queued[index].runId)!!
            val segment = dao.findAudioSegmentById(queued[index].segmentIds.single())!!
            assertEquals(GenerationRunStatus.QUEUED, run.status)
            assertEquals(engine.id, run.engine)
            assertEquals(engine.id, segment.engine)
            assertEquals(run.id, segment.generationRunId)
            assertEquals("${engine.id}-model", run.modelPackageId)
            assertEquals("${engine.id}-model", segment.modelPackageId)
            assertEquals(3, segment.estimatedWordCount)
            assertTrue(segment.generationKey!!.isNotBlank())
        }
    }

    @Test
    public fun plannedWordEstimateRemainsReadableAfterDatabaseReopen() {
        val queued = coordinator().queue(request(GenerationEngine.VITS))
        database.close()
        database = AudiobookDatabase.create(context, databaseName)

        assertEquals(3, database.audiobookDao().findAudioSegmentById(queued.segmentIds.single())!!.estimatedWordCount)
    }

    @Test
    public fun workerExecutorDispatchesFromPersistedEngine() = runBlocking {
        val queued = coordinator().queue(request(GenerationEngine.VITS))
        val selector = SelectingGenerationRunExecutor(
            database = database,
            executors = mapOf(
                GenerationEngine.KOKORO to executor("kokoro"),
                GenerationEngine.VITS to executor("vits"),
            ),
        )

        selector.execute(queued.runId)

        assertEquals(listOf("vits"), executedEngines)
    }

    private fun coordinator() = DurableGenerationCoordinator(
        database = database,
        planners = GenerationEngine.entries.map(::FakePlanner),
        enqueue = {},
        clock = { 10L },
        runIdFactory = { request -> "${request.engine.id}-run" },
    )

    private fun request(engine: GenerationEngine) = GenerationRequest(
        projectId = "book",
        sourceFingerprint = "fingerprint",
        scope = GenerationScope.Chapter("${engine.id}-chapter"),
        engine = engine,
        narrationBlocks = listOf(
            GenerationNarrationBlock(
                id = "${engine.id}-block",
                chapterId = "${engine.id}-chapter",
                ordinal = 0,
                text = "Text for ${engine.id}.",
            ),
        ),
    )

    private fun executor(engine: String) = GenerationRunExecutor {
        executedEngines += engine
        BoundedGenerationResult(
            runId = "unused",
            status = BoundedGenerationStatus.COMPLETED,
            generatedSegmentIds = emptyList(),
            failedSegmentIds = emptyList(),
        )
    }

    private class FakePlanner(
        override val engine: GenerationEngine,
    ) : GenerationEnginePlanner {
        override fun plan(request: GenerationRequest): GenerationEnginePlan {
            val packageId = "${engine.id}-model"
            val provenance = GenerationProvenance(
                generationKey = "${engine.id}-generation-key",
                modelPackageId = packageId,
                modelPackageSha256 = "${engine.id}-package-sha",
                voiceSha256 = "${engine.id}-voice-sha",
                preprocessingVersion = "${engine.id}-preprocessing-v1",
                pronunciationVersion = "${engine.id}-pronunciation-v1",
                inferenceSettingsHash = "${engine.id}-settings",
                audioProcessingVersion = "audio-v1",
                engine = engine.id,
            )
            return GenerationEnginePlan(
                modelPackage = ModelPackageEntity(
                    id = packageId,
                    packageIdentity = "$packageId@1",
                    packageVersion = "1",
                    packageSha256 = "${engine.id}-package-sha",
                    modelSha256 = "${engine.id}-model-sha",
                    voiceSha256 = "${engine.id}-voice-sha",
                    preprocessingVersion = provenance.preprocessingVersion,
                    pronunciationVersion = provenance.pronunciationVersion,
                    packagePath = "$packageId.zip",
                    status = ModelPackageStatus.INSTALLED,
                    importedAt = 1,
                ),
                segments = request.narrationBlocks.map { block ->
                    PlannedGenerationSegment(block.id, block.chapterId, provenance)
                },
            )
        }
    }
}
