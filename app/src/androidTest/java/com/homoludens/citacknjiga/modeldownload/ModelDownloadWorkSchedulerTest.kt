package com.homoludens.citacknjiga.modeldownload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.homoludens.citacknjiga.diagnostics.ModelEngine
import java.util.concurrent.TimeUnit
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
        assertEquals(ModelDownloadWorkContract.tag(ModelEngine.VITS), work.tags.single {
            it.startsWith(ModelDownloadWorkContract.TAG_PREFIX)
        })
    }
}
