package app.aaps.receivers

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.RM
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventProfileSwitchChanged
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import com.google.common.truth.Truth.assertThat
import app.aaps.plugins.configuration.maintenance.MaintenancePlugin
import app.aaps.plugins.constraints.dstHelper.DstHelperPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class KeepAliveWorkerTest : TestBaseWithProfile() {

    private lateinit var worker: KeepAliveWorker

    @Mock private lateinit var loop: Loop
    @Mock private lateinit var maintenancePlugin: MaintenancePlugin
    @Mock private lateinit var dstHelperPlugin: DstHelperPlugin
    @Mock private lateinit var workerParameters: WorkerParameters
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var ads: AutosensDataStore
    @Mock private lateinit var localAlertUtils: LocalAlertUtils
    @Mock private lateinit var workManager: WorkManager
    @Mock private lateinit var listenableFuture: ListenableFuture<List<WorkInfo>>
    @Mock private lateinit var mockedRxBus: RxBus

    @BeforeEach
    fun setUp() {
        // Configure mocks provided by the base class or declared here.
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(workManager.getWorkInfos(any())).thenReturn(listenableFuture)
        whenever(listenableFuture.get()).thenReturn(emptyList())
        whenever(workerParameters.inputData).thenReturn(workDataOf("schedule" to "KA_5"))
    }

    // Helper to create the worker instance directly
    private fun createWorker(): KeepAliveWorker =
        KeepAliveWorker(context, workerParameters).also {
            // Manually inject all mocks.
            it.persistenceLayer = persistenceLayer
            it.config = config
            it.iobCobCalculator = iobCobCalculator
            it.loop = loop
            it.dateUtil = dateUtil
            it.activePlugin = activePlugin
            it.profileFunction = profileFunction
            it.rxBus = mockedRxBus
            it.commandQueue = commandQueue
            it.maintenancePlugin = maintenancePlugin
            it.preferences = preferences
            it.dstHelperPlugin = dstHelperPlugin
            it.aapsLogger = aapsLogger
            it.localAlertUtils = localAlertUtils
            it.workManager = workManager
            it.rh = rh
        }

    @Test
    fun `checkPump requests status when connection is outdated`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(loop.runningMode).thenReturn(RM.Mode.OPEN_LOOP)
        whenever(profileFunction.getRequestedProfile()).thenReturn(profileSwitch)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)).thenReturn(true)
        testPumpPlugin.lastData = now - T.mins(20).msecs()

        // Act
        worker.checkPump()

        // Assert
        verify(commandQueue).readStatus(anyOrNull(), anyOrNull())
        Unit
    }

    @Test
    fun `checkPump sends profile switch event if profile is mismatched`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(loop.runningMode).thenReturn(RM.Mode.OPEN_LOOP)
        whenever(profileFunction.getRequestedProfile()).thenReturn(profileSwitch)
        testPumpPlugin.isProfileSet = false

        // Act
        worker.checkPump()

        // Assert
        verify(mockedRxBus).send(any<EventProfileSwitchChanged>())
    }

    @Test
    fun `checkPump does nothing if mode is DISCONNECTED_PUMP`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(loop.runningMode).thenReturn(RM.Mode.DISCONNECTED_PUMP)
        testPumpPlugin.lastData = now - T.mins(20).msecs()

        // Act
        worker.doWorkAndLog()

        // Assert
        verify(commandQueue, never()).readStatus(any(), anyOrNull())
        verify(mockedRxBus, never()).send(any<EventProfileSwitchChanged>())
    }

    @Test
    fun `checkAPS schedules device status upload if BG is missing`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        whenever(ads.actualBg()).thenReturn(null)

        // Act
        worker.doWorkAndLog()

        // Assert
        verify(loop).scheduleBuildAndStoreDeviceStatus("KeepAliveWorker")
    }

    @Test
    fun `databaseCleanup does NOT run if it was run less than a day ago`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(preferences.get(LongNonKey.LastCleanupRun)).thenReturn(now - T.hours(12).msecs())

        // Act
        worker.doWorkAndLog()

        // Assert
        verify(persistenceLayer, never()).cleanupDatabase(any(), any())
        Unit
    }

    @Test
    fun `databaseCleanup uses MaintenanceCleanupDays when above the lower bound`() {
        worker = createWorker()
        whenever(preferences.get(LongNonKey.LastCleanupRun)).thenReturn(now - T.days(2).msecs())
        whenever(preferences.get(IntKey.MaintenanceCleanupDays)).thenReturn(14)
        whenever(persistenceLayer.cleanupDatabase(any(), any())).thenReturn("")

        worker.databaseCleanup()

        verify(persistenceLayer).cleanupDatabase(eq(14L), eq(false))
        verify(preferences).put(eq(LongNonKey.LastCleanupRun), any<Long>())
    }

    @Test
    fun `databaseCleanup never keeps less than the dosing lookback`() {
        worker = createWorker()
        whenever(preferences.get(LongNonKey.LastCleanupRun)).thenReturn(0L)
        whenever(preferences.get(IntKey.MaintenanceCleanupDays)).thenReturn(7)
        whenever(persistenceLayer.cleanupDatabase(any(), any())).thenReturn("")

        worker.databaseCleanup()

        verify(persistenceLayer).cleanupDatabase(eq(KeepAliveWorker.MIN_AUTO_CLEANUP_KEEP_DAYS), eq(false))
    }

    @Test
    fun `autoCleanupKeepDays is max of setting and lower bound`() {
        assertThat(KeepAliveWorker.autoCleanupKeepDays(7)).isEqualTo(10L)
        assertThat(KeepAliveWorker.autoCleanupKeepDays(10)).isEqualTo(10L)
        assertThat(KeepAliveWorker.autoCleanupKeepDays(14)).isEqualTo(14L)
        assertThat(KeepAliveWorker.autoCleanupKeepDays(93)).isEqualTo(93L)
    }

    @Test
    fun `checkLoopRunning passes last decision, active loop and last BG to alert check`() {
        worker = createWorker()
        val lastRun = Loop.LastRun().also { it.lastAPSRun = 12345L }
        val gv = org.mockito.kotlin.mock<app.aaps.core.data.model.GV>()
        whenever(gv.timestamp).thenReturn(67890L)
        whenever(config.APS).thenReturn(true)
        whenever(loop.isEnabled()).thenReturn(true)
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        whenever(loop.lastRun).thenReturn(lastRun)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(gv)
        val pump = readyPump()

        worker.checkLoopRunning()

        verify(localAlertUtils).checkLoopNotRunningAlert(12345L, true, 67890L)
        verify(pump).isInitialized()
    }

    private fun readyPump(initialized: Boolean = true, basal: Double = 0.8): Pump =
        org.mockito.kotlin.mock<Pump>().also {
            whenever(it.isInitialized()).thenReturn(initialized)
            whenever(it.baseBasalRate).thenReturn(basal)
            whenever(activePlugin.activePump).thenReturn(it)
        }

    @Test
    fun `checkLoopRunning reports loop inactive when pump is not initialized or has no basal`() {
        worker = createWorker()
        whenever(config.APS).thenReturn(true)
        whenever(loop.isEnabled()).thenReturn(true)
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        whenever(loop.lastRun).thenReturn(null)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(null)

        // Pump driver not ready (e.g. after a restart)
        readyPump(initialized = false)
        worker.checkLoopRunning()
        // Dash pod change: no active pod -> no basal program -> baseBasalRate 0.0
        readyPump(initialized = true, basal = 0.0)
        worker.checkLoopRunning()

        verify(localAlertUtils, org.mockito.kotlin.times(2)).checkLoopNotRunningAlert(0L, false, 0L)

        readyPump()
        worker.checkLoopRunning()
        verify(localAlertUtils).checkLoopNotRunningAlert(0L, true, 0L)
    }

    @Test
    fun `checkLoopRunning reports loop inactive when suspended or disconnected`() {
        worker = createWorker()
        whenever(config.APS).thenReturn(true)
        whenever(loop.isEnabled()).thenReturn(true)
        whenever(loop.lastRun).thenReturn(null)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(null)

        whenever(loop.runningMode).thenReturn(RM.Mode.SUSPENDED_BY_USER)
        worker.checkLoopRunning()
        whenever(loop.runningMode).thenReturn(RM.Mode.DISCONNECTED_PUMP)
        worker.checkLoopRunning()

        verify(localAlertUtils, org.mockito.kotlin.times(2)).checkLoopNotRunningAlert(0L, false, 0L)
    }

    @Test
    fun `checkLoopRunning does nothing without APS`() {
        worker = createWorker()
        whenever(config.APS).thenReturn(false)

        worker.checkLoopRunning()

        verify(localAlertUtils, never()).checkLoopNotRunningAlert(any(), any(), any())
    }
}
