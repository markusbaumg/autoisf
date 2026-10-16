package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpStatusProvider
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanKey
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider

/**
 * Phone-side safety for treatments requested from the watch:
 *  1. nothing is offered or delivered while "Controls from Watch" is off on the phone,
 *  2. a confirm from the watch delivers only if it matches the last request the phone offered,
 *     within 60 s, and only once.
 */
class DataHandlerMobileWatchBolusSafetyTest : TestBaseWithProfile() {

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
    private val ads = mock<AutosensDataStore>()
    private val toWatch = mutableListOf<EventData>()
    private lateinit var watchSubscription: Disposable

    private val wearControlOff = "WEAR_CONTROL_OFF"
    private val confirmRefused = "CONFIRM_REFUSED"

    @BeforeEach fun prepare() {
        sut = DataHandlerMobile(
            aapsSchedulers, context, rxBus, aapsLogger, rh, preferences, config,
            iobCobCalculator, processedTbrEbData, smbGlucoseStatusProvider, profileFunction, profileUtil,
            loop, processedDeviceStatusData, receiverStatusStore, quickWizard, trendCalculator, dateUtil,
            constraintsChecker, uel, activePlugin, commandQueue, fabricPrivacy, uiInteraction,
            persistenceLayer, importExportPrefs, decimalFormatter, Provider<BolusWizard> { mock() }, pumpStatusProvider
        )
        sut.automation = automation
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        whenever(preferences.get(BooleanKey.WearControl)).thenReturn(true)
        // constraints pass values through unchanged
        whenever(constraintsChecker.applyBolusConstraints(any())).thenAnswer { it.getArgument<Constraint<Double>>(0) }
        whenever(constraintsChecker.applyCarbsConstraints(any())).thenAnswer { it.getArgument<Constraint<Int>>(0) }
        whenever(rh.gs(R.string.wear_control_disabled_on_phone)).thenReturn(wearControlOff)
        whenever(rh.gs(R.string.wear_confirm_not_matching)).thenReturn(confirmRefused)
        whenever(rh.gs(app.aaps.core.ui.R.string.confirm)).thenReturn("Confirm")
        whenever(commandQueue.bolus(any(), anyOrNull())).thenReturn(true)
        watchSubscription = rxBus.toObservable(EventMobileToWear::class.java).subscribe { toWatch.add(it.payload) }
    }

    @AfterEach fun tearDown() {
        watchSubscription.dispose()
    }

    private fun errorsToWatch(): List<String> =
        toWatch.filterIsInstance<EventData.ConfirmAction>().filter { it.returnCommand is EventData.Error }.map { it.message }

    private fun confirmsOffered(): List<EventData> =
        toWatch.filterIsInstance<EventData.ConfirmAction>().mapNotNull { it.returnCommand }.filter { it !is EventData.Error }

    private fun deliveredBoluses(): List<DetailedBolusInfo> {
        val captor = argumentCaptor<DetailedBolusInfo>()
        verify(commandQueue, org.mockito.kotlin.atLeast(0)).bolus(captor.capture(), anyOrNull())
        return captor.allValues
    }

    private fun advanceClockTo(time: Long) {
        whenever(dateUtil.now()).thenReturn(time)
    }

    // ---- CHECK 1: Controls from Watch off ----

    @Test fun `bolus precheck is refused while wear control is off`() {
        whenever(preferences.get(BooleanKey.WearControl)).thenReturn(false)

        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))

        assertThat(errorsToWatch()).containsExactly(wearControlOff)
        assertThat(confirmsOffered()).isEmpty()
    }

    @Test fun `bolus confirm is refused when wear control was switched off after the precheck`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        whenever(preferences.get(BooleanKey.WearControl)).thenReturn(false)

        rxBus.send(EventData.ActionBolusConfirmed(1.0, 10))

        assertThat(errorsToWatch()).containsExactly(wearControlOff)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `wizard, quick wizard, ecarbs and fill prechecks are refused while wear control is off`() {
        whenever(preferences.get(BooleanKey.WearControl)).thenReturn(false)

        rxBus.send(EventData.ActionWizardPreCheck(20, 100))
        rxBus.send(EventData.ActionQuickWizardPreCheck("guid"))
        rxBus.send(EventData.ActionECarbsPreCheck(20, 0, 0))
        rxBus.send(EventData.ActionFillPreCheck(0.5))
        rxBus.send(EventData.ActionFillPresetPreCheck(1))

        assertThat(errorsToWatch()).containsExactly(wearControlOff, wearControlOff, wearControlOff, wearControlOff, wearControlOff)
        assertThat(confirmsOffered()).isEmpty()
    }

    @Test fun `ecarbs confirm is refused while wear control is off`() {
        whenever(preferences.get(BooleanKey.WearControl)).thenReturn(false)

        rxBus.send(EventData.ActionECarbsConfirmed(20, now, 0))

        assertThat(errorsToWatch()).containsExactly(wearControlOff)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    // ---- CHECK 2: confirm must match the pending request ----

    @Test fun `bolus confirm without precheck is refused`() {
        rxBus.send(EventData.ActionBolusConfirmed(1.0, 0))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm with a different insulin amount is refused`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))

        rxBus.send(EventData.ActionBolusConfirmed(1.5, 10))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm with different carbs is refused`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))

        rxBus.send(EventData.ActionBolusConfirmed(1.0, 20))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm uses the amount after constraints`() {
        whenever(constraintsChecker.applyBolusConstraints(any())).thenAnswer {
            it.getArgument<Constraint<Double>>(0).also { c -> c.setIfSmaller(2.0, "max", this) }
        }
        rxBus.send(EventData.ActionBolusPreCheck(5.0, 0))

        // the watch confirms what the phone offered (2.0), not what was typed (5.0)
        assertThat(confirmsOffered()).containsExactly(EventData.ActionBolusConfirmed(2.0, 0))
        rxBus.send(EventData.ActionBolusConfirmed(5.0, 0))
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm after 61 s is refused`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        advanceClockTo(now + 61_000L)

        rxBus.send(EventData.ActionBolusConfirmed(1.0, 10))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm at 60 s is still accepted`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        advanceClockTo(now + 60_000L)

        rxBus.send(EventData.ActionBolusConfirmed(1.0, 10))

        assertThat(errorsToWatch()).isEmpty()
        verify(commandQueue, times(1)).bolus(any(), anyOrNull())
    }

    @Test fun `second bolus confirm is refused`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))

        rxBus.send(EventData.ActionBolusConfirmed(1.0, 10))
        rxBus.send(EventData.ActionBolusConfirmed(1.0, 10))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, times(1)).bolus(any(), anyOrNull())
    }

    @Test fun `a newer precheck replaces the pending bolus`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        rxBus.send(EventData.ActionBolusPreCheck(2.0, 0))

        rxBus.send(EventData.ActionBolusConfirmed(1.0, 10))
        rxBus.send(EventData.ActionBolusConfirmed(2.0, 0))

        assertThat(errorsToWatch()).containsExactly(confirmRefused, confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `happy path delivers the confirmed bolus exactly once`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        assertThat(confirmsOffered()).containsExactly(EventData.ActionBolusConfirmed(1.0, 10))

        rxBus.send(EventData.ActionBolusConfirmed(1.0, 10))

        assertThat(errorsToWatch()).isEmpty()
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(1)
        assertThat(delivered[0].insulin).isEqualTo(1.0)
        assertThat(delivered[0].carbs).isEqualTo(10.0)
    }

    @Test fun `wizard confirm without precheck is refused`() {
        rxBus.send(EventData.ActionWizardConfirmed(now))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus pending does not authorize a fill`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 0))

        rxBus.send(EventData.ActionFillConfirmed(1.0))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `fill confirm without precheck is refused, matching fill is delivered once`() {
        rxBus.send(EventData.ActionFillConfirmed(0.5))
        assertThat(errorsToWatch()).containsExactly(confirmRefused)

        rxBus.send(EventData.ActionFillPreCheck(0.5))
        rxBus.send(EventData.ActionFillConfirmed(0.5))
        rxBus.send(EventData.ActionFillConfirmed(0.5))

        assertThat(errorsToWatch()).containsExactly(confirmRefused, confirmRefused)
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(1)
        assertThat(delivered[0].insulin).isEqualTo(0.5)
    }
}
