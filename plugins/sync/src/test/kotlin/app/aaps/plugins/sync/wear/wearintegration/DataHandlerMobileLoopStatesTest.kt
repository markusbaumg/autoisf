package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.pump.PumpStatusProvider
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.EventData.LoopStatesList.AvailableLoopState
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.plugins.sync.R
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.disposables.Disposable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Collections
import javax.inject.Provider

/**
 * Loop state tile on the watch: after a pump disconnect or a user suspend from the watch, the watch
 * must still get a state list it can resume / reconnect from, and an unchanged list must not invalidate
 * a tap that is already on its way (upstream f4347a9f0e, 90d32cdfe7).
 */
class DataHandlerMobileLoopStatesTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock lateinit var receiverStatusStore: ReceiverStatusStore
    @Mock lateinit var quickWizard: QuickWizard
    @Mock lateinit var trendCalculator: TrendCalculator
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var importExportPrefs: ImportExportPrefs
    @Mock lateinit var pumpStatusProvider: PumpStatusProvider
    @Mock lateinit var automation: Automation

    private lateinit var sut: DataHandlerMobile
    private val toWatch: MutableList<EventData> = Collections.synchronizedList(mutableListOf())
    private lateinit var watchSubscription: Disposable

    private val unauthorized = "UNAUTHORIZED"

    @BeforeEach fun prepare() {
        sut = DataHandlerMobile(
            aapsSchedulers, context, rxBus, aapsLogger, rh, preferences, config,
            iobCobCalculator, processedTbrEbData, smbGlucoseStatusProvider, profileFunction, profileUtil,
            loop, processedDeviceStatusData, receiverStatusStore, quickWizard, trendCalculator, dateUtil,
            constraintsChecker, uel, activePlugin, commandQueue, fabricPrivacy, uiInteraction,
            persistenceLayer, importExportPrefs, decimalFormatter, Provider<BolusWizard> { mock() }, pumpStatusProvider
        )
        sut.automation = automation
        whenever(profileFunction.isProfileValid(any())).thenReturn(true)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(rh.gs(R.string.wear_action_loop_state_unauthorized)).thenReturn(unauthorized)
        watchSubscription = rxBus.toObservable(EventMobileToWear::class.java).subscribe { toWatch.add(it.payload) }
    }

    @AfterEach fun tearDown() {
        watchSubscription.dispose()
    }

    private fun lists(): List<EventData.LoopStatesList> = synchronized(toWatch) { toWatch.toList() }.filterIsInstance<EventData.LoopStatesList>()

    private fun errors(): List<String> =
        synchronized(toWatch) { toWatch.toList() }.filterIsInstance<EventData.ConfirmAction>().filter { it.returnCommand is EventData.Error }.map { it.message }

    private fun givenMode(mode: RM.Mode, vararg next: RM.Mode) {
        whenever(loop.runningMode).thenReturn(mode)
        whenever(loop.allowedNextModes()).thenReturn(next.toList())
    }

    @Test fun `a disconnected pump still gets a list to reconnect from`() {
        givenMode(RM.Mode.DISCONNECTED_PUMP, RM.Mode.RESUME)

        rxBus.send(EventData.LoopStatesRequest(now))

        assertThat(lists()).hasSize(1)
        assertThat(lists().single().states.map { it.state }).containsExactly(AvailableLoopState.LoopState.LOOP_RESUME)
    }

    @Test fun `a user suspended loop still gets a list to resume from`() {
        givenMode(RM.Mode.SUSPENDED_BY_USER, RM.Mode.RESUME)

        rxBus.send(EventData.LoopStatesRequest(now))

        assertThat(lists()).hasSize(1)
    }

    @Test fun `no list while the pump or a DST change suspends the loop`() {
        givenMode(RM.Mode.SUSPENDED_BY_PUMP, RM.Mode.RESUME)
        rxBus.send(EventData.LoopStatesRequest(now))
        givenMode(RM.Mode.SUSPENDED_BY_DST, RM.Mode.RESUME)
        rxBus.send(EventData.LoopStatesRequest(now))

        assertThat(lists()).isEmpty()
    }

    @Test fun `an unchanged list keeps its timestamp, so a tap already sent is still accepted`() {
        givenMode(RM.Mode.CLOSED_LOOP, RM.Mode.OPEN_LOOP, RM.Mode.DISCONNECTED_PUMP)
        rxBus.send(EventData.LoopStatesRequest(now))
        val first = lists().single()
        Thread.sleep(2)

        rxBus.send(EventData.LoopStatesRequest(now))

        assertThat(lists().last().timeStamp).isEqualTo(first.timeStamp)
        rxBus.send(EventData.LoopStateConfirmed(first.timeStamp, 0))
        verify(loop).handleRunningModeChange(eq(RM.Mode.OPEN_LOOP), any(), any(), any(), any(), any())
        assertThat(errors()).isEmpty()
    }

    @Test fun `a changed list gets a new timestamp`() {
        givenMode(RM.Mode.CLOSED_LOOP, RM.Mode.OPEN_LOOP)
        rxBus.send(EventData.LoopStatesRequest(now))
        val first = lists().single()
        Thread.sleep(2)

        givenMode(RM.Mode.OPEN_LOOP, RM.Mode.CLOSED_LOOP)
        rxBus.send(EventData.LoopStatesRequest(now))

        assertThat(lists().last().timeStamp).isNotEqualTo(first.timeStamp)
    }

    @Test fun `a confirmed disconnect pushes a fresh list right away, and reconnect from it is accepted`() {
        givenMode(RM.Mode.CLOSED_LOOP, RM.Mode.DISCONNECTED_PUMP)
        rxBus.send(EventData.LoopStatesRequest(now))
        val beforeDisconnect = lists().single()
        // the change is written synchronously, so the list right after it shows the new mode
        whenever(loop.handleRunningModeChange(eq(RM.Mode.DISCONNECTED_PUMP), any(), any(), any(), any(), any())).thenAnswer {
            givenMode(RM.Mode.DISCONNECTED_PUMP, RM.Mode.RESUME)
            true
        }
        Thread.sleep(2)

        rxBus.send(EventData.LoopStateConfirmed(beforeDisconnect.timeStamp, 0, 60))

        assertThat(lists()).hasSize(2)
        val afterDisconnect = lists().last()
        assertThat(afterDisconnect.timeStamp).isNotEqualTo(beforeDisconnect.timeStamp)
        assertThat(afterDisconnect.states.map { it.state }).containsExactly(AvailableLoopState.LoopState.LOOP_RESUME)

        rxBus.send(EventData.LoopStateConfirmed(afterDisconnect.timeStamp, 0))

        verify(loop).handleRunningModeChange(eq(RM.Mode.RESUME), any(), any(), any(), any(), any())
        assertThat(errors()).isEmpty()
    }
}
