package com.homoludens.citacknjiga.modeldownload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.homoludens.citacknjiga.diagnostics.ModelEngine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

public class ModelDownloadWorkSchedulerTest {
    private lateinit var workManager: WorkManager

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
    }

    @After
    public fun tearDown() {
        workManager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @Test
    public fun schedulesOnlyConnectedNetworkWorkForTheSelectedEngine() {
        val scheduler = ModelDownloadWorkScheduler({ workManager })

        scheduler.enqueue(ModelEngine.VITS)

        val work = workManager.getWorkInfosForUniqueWork(
            ModelDownloadWorkContract.uniqueWorkName(ModelEngine.VITS),
        ).get(5, TimeUnit.SECONDS).single()
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertEquals(false, work.constraints.requiresBatteryNotLow())
        assertEquals(false, work.constraints.requiresStorageNotLow())
        assertEquals("model-download-v2-vits", ModelDownloadWorkContract.uniqueWorkName(ModelEngine.VITS))
        assertEquals(ModelDownloadWorkContract.tag(ModelEngine.VITS), work.tags.single {
            it.startsWith(ModelDownloadWorkContract.TAG_PREFIX)
        })
    }

    @Test
    public fun observingCurrentStateCancelsWorkPersistedWithLegacyConstraints() = runBlocking {
        val legacy = OneTimeWorkRequestBuilder<LegacyDelayedWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        workManager.enqueueUniqueWork(
            ModelDownloadWorkContract.legacyUniqueWorkName(ModelEngine.VITS),
            ExistingWorkPolicy.KEEP,
            legacy,
        ).result.get(5, TimeUnit.SECONDS)

        ModelDownloadWorkScheduler({ workManager }).workInfo(ModelEngine.VITS).first()

        workManager.getWorkInfoByIdFlow(legacy.id).first { it?.state?.isFinished == true }
        assertEquals(WorkInfo.State.CANCELLED, requireNotNull(workManager.getWorkInfoById(legacy.id).get()).state)
    }
}

private class LegacyDelayedWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}
