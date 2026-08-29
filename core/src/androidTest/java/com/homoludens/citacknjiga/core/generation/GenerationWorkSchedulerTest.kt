package com.homoludens.citacknjiga.core.generation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.homoludens.citacknjiga.core.reconciliation.ReconciliationReport
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class GenerationWorkSchedulerTest {
    private lateinit var workManager: WorkManager

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    public fun tearDown() {
        workManager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @Test
    public fun enqueueUsesOfflineConstraintsAndKeepsOneWorkPerRun() {
        val scheduler = GenerationWorkScheduler(
            workManager = workManager,
            queue = FakeQueue(listOf("run")),
            constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .setRequiresCharging(true)
                .build(),
        )

        scheduler.enqueue("run")
        scheduler.enqueue("run")

        val work = workManager
            .getWorkInfosForUniqueWork(GenerationWorkContract.uniqueWorkName("run"))
            .get(5, TimeUnit.SECONDS)
        assertEquals(1, work.size)
        assertEquals(GenerationWorkContract.tag("run"), work.single().tags.single { it.startsWith("generation-run:") })
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, work.single().state)
    }

    @Test
    public fun reconciliationSchedulesEveryQueuedRunInStableOrder() {
        val scheduler = GenerationWorkScheduler(
            workManager = workManager,
            queue = FakeQueue(listOf("second", "first", "first")),
        )

        assertEquals(listOf("first", "second"), scheduler.reconcileAndEnqueue())
        assertTrue(
            workManager
                .getWorkInfosForUniqueWork(GenerationWorkContract.uniqueWorkName("first"))
                .get(5, TimeUnit.SECONDS)
                .isNotEmpty(),
        )
        assertTrue(
            workManager
                .getWorkInfosForUniqueWork(GenerationWorkContract.uniqueWorkName("second"))
                .get(5, TimeUnit.SECONDS)
                .isNotEmpty(),
        )
    }

    private class FakeQueue(
        private val ids: List<String>,
    ) : GenerationQueue {
        override fun reconcile() = ReconciliationReport(
            removedTemporaryFileCount = 0,
            interruptedRunIds = emptyList(),
            interruptedSegmentIds = emptyList(),
            invalidReadySegmentIds = emptyList(),
            staleProvenanceSegmentIds = emptyList(),
        )

        override fun queuedRunIds(): List<String> = ids
    }
}
