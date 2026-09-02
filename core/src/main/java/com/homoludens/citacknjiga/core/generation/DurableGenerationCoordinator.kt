package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import java.util.UUID

/** The persisted identity and key for one request block. */
public data class PlannedGenerationSegment(
    public val narrationBlockId: String,
    public val chapterId: String,
    public val provenance: GenerationProvenance,
)

/** Engine-specific planning output consumed by the engine-independent coordinator. */
public data class GenerationEnginePlan(
    public val modelPackage: ModelPackageEntity,
    public val segments: List<PlannedGenerationSegment>,
) {
    init {
        require(segments.isNotEmpty()) { "Generation plan must contain a narratable segment" }
        require(segments.map { it.narrationBlockId }.toSet().size == segments.size) {
            "Generation plan contains duplicate narration blocks"
        }
        require(segments.all { it.provenance.modelPackageId == modelPackage.id }) {
            "Generation plan provenance does not match its model package"
        }
    }
}

/** Plans one engine without owning Room, WorkManager, or document-format behavior. */
public interface GenerationEnginePlanner {
    public val engine: GenerationEngine

    public fun plan(request: GenerationRequest): GenerationEnginePlan
}

public data class QueuedGeneration(
    public val runId: String,
    public val segmentIds: List<String>,
)

/** Creates the durable run and segment checkpoints shared by PDF and EPUB callers. */
public class DurableGenerationCoordinator(
    private val database: AudiobookDatabase,
    planners: Collection<GenerationEnginePlanner>,
    private val enqueue: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runIdFactory: (GenerationRequest) -> String = { request ->
        "${request.engine.id}-${UUID.randomUUID()}"
    },
) {
    private val plannersByEngine = planners.associateBy(GenerationEnginePlanner::engine).also { values ->
        require(values.size == planners.size) { "Generation planners must have unique engines" }
    }

    /** Persists all planning output before scheduling the resumable worker. */
    public fun queue(request: GenerationRequest): QueuedGeneration {
        val planner = plannersByEngine[request.engine]
            ?: error("No generation planner is registered for ${request.engine.id}")
        val dao = database.audiobookDao()
        val project = dao.findProjectById(request.projectId) ?: error("Project does not exist")
        require(project.sourceFingerprint == request.sourceFingerprint) {
            "Generation request source does not match the project"
        }
        check(!project.isDeleting) { "Project ${project.id} is being deleted" }
        require(request.narrationBlocks.isNotEmpty()) { "Generation request has no narratable blocks" }
        request.narrationBlocks.forEach { requested ->
            val block = dao.findNarrationBlockById(requested.id)
                ?: error("Narration block ${requested.id} does not exist")
            require(block.chapterId == requested.chapterId && block.sourceText == requested.text) {
                "Generation request block ${requested.id} is not current"
            }
            val chapter = dao.findChapterById(block.chapterId)
                ?: error("Chapter ${block.chapterId} does not exist")
            require(chapter.bookProjectId == project.id) {
                "Narration block ${requested.id} does not belong to project ${project.id}"
            }
        }

        val plan = planner.plan(request)
        require(plan.segments.all { it.provenance.engine == request.engine.id }) {
            "Generation planner returned mixed engine provenance"
        }
        require(plan.segments.map { it.narrationBlockId } == request.narrationBlocks.map { it.id }) {
            "Generation planner changed the request block order"
        }
        val runId = runIdFactory(request).also { require(it.isNotBlank()) }
        val now = clock()
        val runProvenance = plan.segments.first().provenance
        val run = GenerationRunEntity(
            id = runId,
            bookProjectId = project.id,
            modelPackageId = plan.modelPackage.id,
            preprocessingVersion = runProvenance.preprocessingVersion,
            pronunciationVersion = runProvenance.pronunciationVersion,
            inferenceSettingsHash = runProvenance.inferenceSettingsHash,
            audioProcessingVersion = runProvenance.audioProcessingVersion,
            requestedAt = now,
            engine = runProvenance.engine ?: request.engine.id,
            modelRevision = runProvenance.modelRevision,
            speakerId = runProvenance.speakerId,
            frontendVersion = runProvenance.frontendVersion,
            nativeSampleRate = runProvenance.nativeSampleRateHz,
            finalSampleRate = runProvenance.finalSampleRateHz,
            resamplerVersion = runProvenance.resamplerVersion,
            runtimeId = runProvenance.runtimeId,
            runtimeVersion = runProvenance.runtimeVersion,
        )
        val sequenceByChapter = dao.findAllAudioSegments()
            .groupBy(AudioSegmentEntity::chapterId)
            .mapValues { (_, segments) -> segments.maxOfOrNull(AudioSegmentEntity::sequence)?.plus(1) ?: 0 }
            .toMutableMap()
        val segments = plan.segments.map { planned ->
            val sequence = sequenceByChapter.getOrDefault(planned.chapterId, 0)
            sequenceByChapter[planned.chapterId] = sequence + 1
            val provenance = planned.provenance
            val sourceText = request.narrationBlocks.first { it.id == planned.narrationBlockId }.text
            AudioSegmentEntity(
                id = "$runId-${planned.narrationBlockId}",
                chapterId = planned.chapterId,
                narrationBlockId = planned.narrationBlockId,
                sequence = sequence,
                chunkOrdinal = 0,
                estimatedWordCount = ApproximateWordCounter.count(sourceText),
                generationKey = provenance.generationKey,
                generationRunId = runId,
                modelPackageId = provenance.modelPackageId,
                modelPackageSha256 = provenance.modelPackageSha256,
                voiceSha256 = provenance.voiceSha256,
                preprocessingVersion = provenance.preprocessingVersion,
                pronunciationVersion = provenance.pronunciationVersion,
                inferenceSettingsHash = provenance.inferenceSettingsHash,
                audioProcessingVersion = provenance.audioProcessingVersion,
                createdAt = now,
                updatedAt = now,
                engine = provenance.engine ?: request.engine.id,
                modelRevision = provenance.modelRevision,
                speakerId = provenance.speakerId,
                frontendVersion = provenance.frontendVersion,
                nativeSampleRate = provenance.nativeSampleRateHz,
                finalSampleRate = provenance.finalSampleRateHz,
                resamplerVersion = provenance.resamplerVersion,
                runtimeId = provenance.runtimeId,
                runtimeVersion = provenance.runtimeVersion,
            )
        }

        database.runInTransaction {
            check(dao.findProjectById(project.id)?.isDeleting == false) {
                "Project ${project.id} is being deleted"
            }
            require(segments.none { candidate ->
                dao.findAllAudioSegments().any { existing ->
                    existing.narrationBlockId == candidate.narrationBlockId
                }
            }) { "Generation request targets a block with existing audio work" }
            val existingPackage = dao.findModelPackageById(plan.modelPackage.id)
            if (existingPackage == null) {
                dao.insertModelPackage(plan.modelPackage)
            } else {
                require(existingPackage.id == plan.modelPackage.id &&
                    existingPackage.packageIdentity == plan.modelPackage.packageIdentity &&
                    existingPackage.packageVersion == plan.modelPackage.packageVersion &&
                    existingPackage.packageSha256 == plan.modelPackage.packageSha256 &&
                    existingPackage.modelSha256 == plan.modelPackage.modelSha256 &&
                    existingPackage.voiceSha256 == plan.modelPackage.voiceSha256 &&
                    existingPackage.preprocessingVersion == plan.modelPackage.preprocessingVersion &&
                    existingPackage.pronunciationVersion == plan.modelPackage.pronunciationVersion
                ) {
                    "Registered model package identity changed"
                }
            }
            dao.insertGenerationRun(run)
            segments.forEach(dao::insertAudioSegment)
        }
        enqueue(runId)
        return QueuedGeneration(runId, segments.map(AudioSegmentEntity::id))
    }
}

/** Dispatches a worker to the engine recorded in its durable run row. */
public class SelectingGenerationRunExecutor(
    database: AudiobookDatabase,
    executors: Map<GenerationEngine, GenerationRunExecutor>,
) : GenerationRunExecutor {
    private val dao = database.audiobookDao()
    private val executorsById = executors.mapKeys { (engine, _) -> engine.id }

    override suspend fun execute(runId: String): BoundedGenerationResult {
        val run = dao.findGenerationRunById(runId) ?: error("Generation run $runId does not exist")
        val engine = run.engine?.let { value -> GenerationEngine.entries.firstOrNull { it.id == value } }
            ?: error("Generation run $runId has no supported engine")
        return executorsById[engine.id]?.execute(runId)
            ?: error("No generation executor is registered for ${engine.id}")
    }
}
