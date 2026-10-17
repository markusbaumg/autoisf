package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.iob.InMemoryGlucoseValue
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
import app.aaps.core.objects.wizard.QuickWizardEntry
import app.aaps.plugins.sync.R
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.disposables.Disposable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Phone-side safety for treatments requested from the watch:
 *  1. nothing is offered or delivered while "Controls from Watch" is off on the phone,
 *  2. a confirm from the watch delivers only if it matches the last request the phone offered
 *     (for bolus and fill including its requestId), within 60 s, and only once.
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
    private val toWatch: MutableList<EventData> = Collections.synchronizedList(mutableListOf())
    private lateinit var watchSubscription: Disposable

    private val bolusWizard = mock<BolusWizard>(defaultAnswer = Answers.RETURNS_SELF)
    private var monotonicNow = 5_000_000L

    private val wearControlOff = "WEAR_CONTROL_OFF"
    private val confirmRefused = "CONFIRM_REFUSED"

    @BeforeEach fun prepare() {
        sut = DataHandlerMobile(
            aapsSchedulers, context, rxBus, aapsLogger, rh, preferences, config,
            iobCobCalculator, processedTbrEbData, smbGlucoseStatusProvider, profileFunction, profileUtil,
            loop, processedDeviceStatusData, receiverStatusStore, quickWizard, trendCalculator, dateUtil,
            constraintsChecker, uel, activePlugin, commandQueue, fabricPrivacy, uiInteraction,
            persistenceLayer, importExportPrefs, decimalFormatter, Provider<BolusWizard> { bolusWizard }, pumpStatusProvider
        )
        sut.automation = automation
        sut.monotonicClock = { monotonicNow }
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
        synchronized(toWatch) { toWatch.toList() }.filterIsInstance<EventData.ConfirmAction>().filter { it.returnCommand is EventData.Error }.map { it.message }

    private fun confirmsOffered(): List<EventData> =
        synchronized(toWatch) { toWatch.toList() }.filterIsInstance<EventData.ConfirmAction>().mapNotNull { it.returnCommand }.filter { it !is EventData.Error }

    /** Every bolus handed to the command queue so far, in order (reads the recorded calls, verifies nothing). */
    private fun deliveredBoluses(): List<DetailedBolusInfo> =
        mockingDetails(commandQueue).invocations.filter { it.method.name == "bolus" }.map { it.arguments[0] as DetailedBolusInfo }

    /** The bolus confirm the phone offered last, carrying its requestId, as the watch would echo it back. */
    private fun offeredBolus(): EventData.ActionBolusConfirmed = confirmsOffered().filterIsInstance<EventData.ActionBolusConfirmed>().last()

    /** The fill confirm the phone offered last, carrying its requestId, as the watch would echo it back. */
    private fun offeredFill(): EventData.ActionFillConfirmed = confirmsOffered().filterIsInstance<EventData.ActionFillConfirmed>().last()

    /** Moves both the wall clock and the monotonic clock forward. */
    private fun advanceClockBy(ms: Long) {
        monotonicNow += ms
        val wallNow = dateUtil.now()
        whenever(dateUtil.now()).thenReturn(wallNow + ms)
    }

    private fun verifySuperBolusStarted(times: Int) =
        verify(loop, times(times)).handleRunningModeChange(eq(RM.Mode.SUPER_BOLUS), any(), any(), any(), any(), any())

    /** Lets the wizard prechecks run through to the offer: profile, BG, COB and a calculated wizard. */
    private fun prepareWizard(insulin: Double, carbs: Int) {
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(profileFunction.getProfileName()).thenReturn("Test")
        whenever(ads.actualBg()).thenReturn(InMemoryGlucoseValue(timestamp = now, value = 120.0))
        whenever(iobCobCalculator.getCobInfo(any())).thenReturn(CobInfo(now, 0.0, 0.0))
        whenever(bolusWizard.timeStamp).thenReturn(now)
        whenever(bolusWizard.calculatedTotalInsulin).thenReturn(insulin)
        whenever(bolusWizard.insulinAfterConstraints).thenReturn(insulin)
        whenever(bolusWizard.carbs).thenReturn(carbs)
        whenever(bolusWizard.explainShort()).thenReturn("explain")
    }

    private fun quickWizardEntry(carbs: Int, superBolus: Boolean = false, eCarbs: Int = 0): QuickWizardEntry {
        val entry = mock<QuickWizardEntry>()
        whenever(entry.doCalc(any(), any(), any())).thenReturn(bolusWizard)
        whenever(entry.carbs()).thenReturn(carbs)
        whenever(entry.buttonText()).thenReturn("Meal")
        whenever(entry.carbTime()).thenReturn(0)
        whenever(entry.useAlarm()).thenReturn(QuickWizardEntry.NO)
        whenever(entry.useSuperBolus()).thenReturn(if (superBolus) QuickWizardEntry.YES else QuickWizardEntry.NO)
        whenever(entry.useEcarbs()).thenReturn(if (eCarbs > 0) QuickWizardEntry.YES else QuickWizardEntry.NO)
        whenever(entry.carbs2()).thenReturn(eCarbs)
        whenever(entry.time()).thenReturn(0)
        whenever(entry.duration()).thenReturn(if (eCarbs > 0) 2 else 0)
        whenever(quickWizard.get("guid")).thenReturn(entry)
        return entry
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

        rxBus.send(offeredBolus())

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

    @Test fun `profile switch, temp target and user action are refused while wear control is off`() {
        whenever(preferences.get(BooleanKey.WearControl)).thenReturn(false)

        rxBus.send(EventData.ActionProfileSwitchPreCheck(0, 150, 60))
        rxBus.send(EventData.ActionProfileSwitchConfirmed(0, 150, 60))
        rxBus.send(EventData.ActionTempTargetPreCheck(EventData.ActionTempTargetPreCheck.TempTargetCommand.MANUAL, true, 60, 80.0, 80.0))
        rxBus.send(EventData.ActionTempTargetConfirmed(true, 60, 80.0, 80.0))
        rxBus.send(EventData.ActionUserActionPreCheck(1, "action"))
        rxBus.send(EventData.ActionUserActionConfirmed(1, "action"))

        assertThat(errorsToWatch()).containsExactly(wearControlOff, wearControlOff, wearControlOff, wearControlOff, wearControlOff, wearControlOff)
        assertThat(confirmsOffered()).isEmpty()
        verify(profileFunction, never()).createProfileSwitch(any<Int>(), any(), any(), any(), any(), anyOrNull(), any())
        verify(persistenceLayer, never()).insertAndCancelCurrentTemporaryTarget(any(), any(), any(), anyOrNull(), any())
        verify(automation, never()).userEvents()
        verify(automation, never()).processEvent(any())
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

        rxBus.send(offeredBolus().copy(insulin = 1.5))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm with different carbs is refused`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))

        rxBus.send(offeredBolus().copy(carbs = 20))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm uses the amount after constraints`() {
        whenever(constraintsChecker.applyBolusConstraints(any())).thenAnswer {
            it.getArgument<Constraint<Double>>(0).also { c -> c.setIfSmaller(2.0, "max", this) }
        }
        rxBus.send(EventData.ActionBolusPreCheck(5.0, 0))

        // the watch confirms what the phone offered (2.0), not what was typed (5.0)
        val offered = offeredBolus()
        assertThat(offered.insulin).isEqualTo(2.0)
        assertThat(offered.carbs).isEqualTo(0)
        rxBus.send(offered.copy(insulin = 5.0))
        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())

        // confirming the offered value after a fresh precheck delivers exactly that value
        rxBus.send(EventData.ActionBolusPreCheck(5.0, 0))
        rxBus.send(offeredBolus())
        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(1)
        assertThat(delivered[0].insulin).isEqualTo(2.0)
    }

    @Test fun `bolus confirm after 61 s is refused`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        advanceClockBy(61_000L)

        rxBus.send(offeredBolus())

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm at 60 s is still accepted`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        advanceClockBy(60_000L)

        rxBus.send(offeredBolus())

        assertThat(errorsToWatch()).isEmpty()
        verify(commandQueue, times(1)).bolus(any(), anyOrNull())
    }

    @Test fun `setting the wall clock back does not stretch the 60 s window`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        monotonicNow += 61_000L
        whenever(dateUtil.now()).thenReturn(now - 50_000L)

        rxBus.send(offeredBolus())

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `a wall clock jump forward does not expire a fresh confirm`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        monotonicNow += 5_000L
        whenever(dateUtil.now()).thenReturn(now + 2 * 3_600_000L)

        rxBus.send(offeredBolus())

        assertThat(errorsToWatch()).isEmpty()
        verify(commandQueue, times(1)).bolus(any(), anyOrNull())
    }

    @Test fun `second bolus confirm (double tap) is ignored without an error on the watch`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))

        rxBus.send(offeredBolus())
        rxBus.send(offeredBolus())

        // the duplicate is not delivered, and no "refused" screen follows the delivered bolus
        assertThat(errorsToWatch()).isEmpty()
        verify(commandQueue, times(1)).bolus(any(), anyOrNull())
    }

    @Test fun `a late repeat of a delivered bolus confirm is refused with an error`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        rxBus.send(offeredBolus())
        advanceClockBy(WEAR_DUPLICATE_CONFIRM_WINDOW_MS + 1)

        rxBus.send(offeredBolus())

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, times(1)).bolus(any(), anyOrNull())
    }

    @Test fun `a different confirm right after a delivered bolus is refused with an error`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        rxBus.send(offeredBolus())

        rxBus.send(offeredBolus().copy(insulin = 1.5))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, times(1)).bolus(any(), anyOrNull())
    }

    @Test fun `a confirm of a replaced precheck is refused and also voids the newer pending bolus`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        val older = offeredBolus()
        rxBus.send(EventData.ActionBolusPreCheck(2.0, 0))
        val newer = offeredBolus()

        // the older confirm does not match the newer pending request; it empties the slot anyway
        rxBus.send(older)
        rxBus.send(newer)

        assertThat(errorsToWatch()).containsExactly(confirmRefused, confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    // ---- requestId: a confirm approves only the precheck it was offered for ----

    @Test fun `each precheck offers a fresh non-zero requestId`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        rxBus.send(EventData.ActionFillPreCheck(0.5))
        rxBus.send(EventData.ActionFillPreCheck(0.5))

        val ids = confirmsOffered().map {
            when (it) {
                is EventData.ActionBolusConfirmed -> it.requestId
                is EventData.ActionFillConfirmed  -> it.requestId
                else                              -> error("unexpected $it")
            }
        }
        assertThat(ids).hasSize(4)
        assertThat(ids).doesNotContain(0L)
        assertThat(ids.toSet()).hasSize(4)
    }

    @Test fun `delayed duplicate of an identical earlier bolus request does not approve the newer precheck`() {
        // request A is offered, confirmed and delivered
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        val requestA = offeredBolus()
        rxBus.send(requestA)
        assertThat(deliveredBoluses()).hasSize(1)

        // the user requests the identical bolus again (B); then a delayed copy of A's confirm arrives
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        val requestB = offeredBolus()
        assertThat(requestB.copy(requestId = requestA.requestId)).isEqualTo(requestA)
        rxBus.send(requestA)

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        assertThat(deliveredBoluses()).hasSize(1)
    }

    @Test fun `delayed confirm of an identical earlier bolus request is refused before the newer one is confirmed`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        val requestA = offeredBolus()
        advanceClockBy(30_000L)
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))

        rxBus.send(requestA)

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm with the correct requestId is delivered once`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        val requestB = offeredBolus()

        rxBus.send(requestB)
        rxBus.send(requestB)

        assertThat(errorsToWatch()).isEmpty()
        assertThat(deliveredBoluses()).hasSize(1)
    }

    @Test fun `bolus confirm without requestId (old payload) is refused`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        val legacy = EventData.deserialize("""{"type":"app.aaps.core.interfaces.rx.weardata.EventData.ActionBolusConfirmed","insulin":1.0,"carbs":10}""")
        assertThat(legacy).isEqualTo(EventData.ActionBolusConfirmed(1.0, 10, 0L))

        rxBus.send(legacy)

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus confirm with a wrong requestId is refused`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))

        rxBus.send(offeredBolus().let { it.copy(requestId = it.requestId + 1) })

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `delayed duplicate of an identical earlier fill request does not approve the newer precheck`() {
        rxBus.send(EventData.ActionFillPreCheck(0.5))
        val requestA = offeredFill()
        rxBus.send(requestA)
        assertThat(deliveredBoluses()).hasSize(1)

        rxBus.send(EventData.ActionFillPreCheck(0.5))
        rxBus.send(requestA)

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        assertThat(deliveredBoluses()).hasSize(1)
    }

    @Test fun `fill confirm without requestId (old payload) or with a wrong one is refused`() {
        rxBus.send(EventData.ActionFillPreCheck(0.5))
        rxBus.send(EventData.ActionFillConfirmed(0.5))
        rxBus.send(EventData.ActionFillPreCheck(0.5))
        rxBus.send(offeredFill().let { it.copy(requestId = it.requestId + 1) })

        assertThat(errorsToWatch()).containsExactly(confirmRefused, confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `two confirms arriving in parallel deliver once and the second stays silent`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) { round ->
                rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
                val offered = offeredBolus()
                val barrier = CyclicBarrier(2)
                val futures = List(2) { pool.submit { barrier.await(5, TimeUnit.SECONDS); rxBus.send(offered) } }
                futures.forEach { it.get(10, TimeUnit.SECONDS) }

                assertThat(deliveredBoluses()).hasSize(round + 1)
                assertThat(errorsToWatch()).isEmpty()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `happy path delivers the confirmed bolus exactly once`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        val offered = offeredBolus()
        assertThat(confirmsOffered()).containsExactly(offered)
        assertThat(offered.insulin).isEqualTo(1.0)
        assertThat(offered.carbs).isEqualTo(10)

        rxBus.send(offered)

        assertThat(errorsToWatch()).isEmpty()
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(1)
        assertThat(delivered[0].insulin).isEqualTo(1.0)
        assertThat(delivered[0].carbs).isEqualTo(10.0)
    }

    // ---- display-only confirm details ----

    private fun confirmActions(): List<EventData.ConfirmAction> =
        synchronized(toWatch) { toWatch.toList() }.filterIsInstance<EventData.ConfirmAction>().filter { it.returnCommand !is EventData.Error }

    @Test fun `offered confirms carry display-only details that never change what is delivered`() {
        whenever(ads.lastBg()).thenReturn(InMemoryGlucoseValue(timestamp = now - 120_000L, value = 65.0))

        rxBus.send(EventData.ActionBolusPreCheck(1.0, 10))
        val bolus = confirmActions().last()
        assertThat(bolus.details!!.kind).isEqualTo(EventData.ConfirmDetails.KIND_BOLUS)
        assertThat(bolus.details!!.insulin).isEqualTo(1.0)
        assertThat(bolus.details!!.carbs).isEqualTo(10)
        assertThat(bolus.details!!.bgMgdl).isEqualTo(65.0)
        // the confirm itself is exactly as before
        assertThat((bolus.returnCommand as EventData.ActionBolusConfirmed).insulin).isEqualTo(1.0)
        assertThat((bolus.returnCommand as EventData.ActionBolusConfirmed).carbs).isEqualTo(10)

        rxBus.send(EventData.ActionBolusPreCheck(0.0, 20))
        assertThat(confirmActions().last().details!!.kind).isEqualTo(EventData.ConfirmDetails.KIND_CARBS)

        rxBus.send(EventData.ActionFillPreCheck(0.5))
        assertThat(confirmActions().last().details!!.kind).isEqualTo(EventData.ConfirmDetails.KIND_FILL)

        rxBus.send(EventData.ActionECarbsPreCheck(30, 15, 3))
        confirmActions().last().details!!.let {
            assertThat(it.kind).isEqualTo(EventData.ConfirmDetails.KIND_ECARBS)
            assertThat(it.carbs).isEqualTo(30)
            assertThat(it.carbsDurationH).isEqualTo(3)
        }

        rxBus.send(offeredBolus()) // the bolus pending was replaced by the later prechecks
        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `a constrained bolus shows the amount that is delivered and names the requested one as a warning`() {
        whenever(constraintsChecker.applyBolusConstraints(any())).thenAnswer {
            it.getArgument<Constraint<Double>>(0).also { c -> c.setIfSmaller(2.0, "max", this) }
        }
        rxBus.send(EventData.ActionBolusPreCheck(5.0, 0))

        val confirm = confirmActions().last()
        val offered = confirm.returnCommand as EventData.ActionBolusConfirmed
        // the big amount on the watch, the command sent back and the pending amount are the same number
        assertThat(offered.insulin).isEqualTo(2.0)
        assertThat(confirm.details!!.insulin).isEqualTo(offered.insulin)
        assertThat(confirm.details!!.carbs).isEqualTo(offered.carbs)
        val constraint = confirm.details!!.warnings.single { it.code == EventData.ConfirmWarning.W_CONSTRAINT }
        assertThat(constraint.value).isEqualTo(5.0)
        assertThat(constraint.value2).isNull()

        // and that number is what the pending confirm delivers
        rxBus.send(offered)
        assertThat(errorsToWatch()).isEmpty()
        assertThat(deliveredBoluses().single().insulin).isEqualTo(2.0)
    }

    @Test fun `quick wizard confirm carries the button name and the calculation as details`() {
        prepareWizard(insulin = 2.0, carbs = 30)
        quickWizardEntry(30)
        rxBus.send(EventData.ActionQuickWizardPreCheck("guid"))
        confirmActions().last().details!!.let {
            assertThat(it.kind).isEqualTo(EventData.ConfirmDetails.KIND_WIZARD)
            assertThat(it.label).isEqualTo("Meal")
            assertThat(it.insulin).isEqualTo(2.0)
            assertThat(it.carbs).isEqualTo(30)
            assertThat(it.note).isEqualTo("explain")
        }
    }

    @Test fun `wizard confirm without precheck is refused`() {
        rxBus.send(EventData.ActionWizardConfirmed(now))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `bolus pending does not authorize a fill`() {
        rxBus.send(EventData.ActionBolusPreCheck(1.0, 0))

        // not even with the pending bolus's own requestId
        rxBus.send(EventData.ActionFillConfirmed(1.0, offeredBolus().requestId))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `fill confirm without precheck is refused, matching fill is delivered once`() {
        rxBus.send(EventData.ActionFillConfirmed(0.5))
        assertThat(errorsToWatch()).containsExactly(confirmRefused)

        rxBus.send(EventData.ActionFillPreCheck(0.5))
        rxBus.send(offeredFill())
        rxBus.send(offeredFill()) // duplicate: silently ignored

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(1)
        assertThat(delivered[0].insulin).isEqualTo(0.5)
    }

    // ---- eCarbs ----

    @Test fun `ecarbs confirm without precheck is refused`() {
        rxBus.send(EventData.ActionECarbsConfirmed(20, now, 2))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `ecarbs confirm with different carbs, time or duration is refused`() {
        rxBus.send(EventData.ActionECarbsPreCheck(20, 0, 2))
        val offered = confirmsOffered().single() as EventData.ActionECarbsConfirmed

        rxBus.send(EventData.ActionECarbsConfirmed(offered.carbs + 10, offered.carbsTime, offered.duration))
        rxBus.send(EventData.ActionECarbsPreCheck(20, 0, 2))
        rxBus.send(EventData.ActionECarbsConfirmed(offered.carbs, offered.carbsTime - 3_600_000L, offered.duration))
        rxBus.send(EventData.ActionECarbsPreCheck(20, 0, 2))
        rxBus.send(EventData.ActionECarbsConfirmed(offered.carbs, offered.carbsTime, offered.duration + 1))

        assertThat(errorsToWatch()).containsExactly(confirmRefused, confirmRefused, confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `ecarbs happy path delivers once, ecarbs confirm after 61 s is refused`() {
        rxBus.send(EventData.ActionECarbsPreCheck(20, 0, 2))
        val offered = confirmsOffered().single() as EventData.ActionECarbsConfirmed

        rxBus.send(offered)
        rxBus.send(offered)

        assertThat(errorsToWatch()).isEmpty()
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(1)
        assertThat(delivered[0].insulin).isEqualTo(0.0)
        assertThat(delivered[0].carbs).isEqualTo(20.0)

        rxBus.send(EventData.ActionECarbsPreCheck(20, 0, 2))
        val offered2 = confirmsOffered().last() as EventData.ActionECarbsConfirmed
        advanceClockBy(61_000L)
        rxBus.send(offered2)
        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        assertThat(deliveredBoluses()).hasSize(1)
    }

    @Test fun `stray ecarbs confirm after a superbolus quick wizard precheck neither delivers nor starts a superbolus`() {
        prepareWizard(insulin = 3.0, carbs = 30)
        quickWizardEntry(carbs = 30, superBolus = true)
        rxBus.send(EventData.ActionQuickWizardPreCheck("guid"))

        rxBus.send(EventData.ActionECarbsConfirmed(30, now, 0))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
        verifySuperBolusStarted(0)
    }

    // ---- wizard / quick wizard happy paths ----

    @Test fun `wizard happy path delivers the calculated bolus once, second confirm and late confirm are refused`() {
        prepareWizard(insulin = 2.5, carbs = 40)

        rxBus.send(EventData.ActionWizardPreCheck(40, 100))
        val result = toWatch.filterIsInstance<EventData.ActionWizardResult>().single()
        assertThat(result.totalInsulin).isEqualTo(2.5)
        rxBus.send(EventData.ActionWizardConfirmed(result.timestamp))
        rxBus.send(EventData.ActionWizardConfirmed(result.timestamp))

        assertThat(errorsToWatch()).isEmpty()
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(1)
        assertThat(delivered[0].insulin).isEqualTo(2.5)
        assertThat(delivered[0].carbs).isEqualTo(40.0)
        verifySuperBolusStarted(0)

        rxBus.send(EventData.ActionWizardPreCheck(40, 100))
        advanceClockBy(61_000L)
        rxBus.send(EventData.ActionWizardConfirmed(result.timestamp))
        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        assertThat(deliveredBoluses()).hasSize(1)
    }

    @Test fun `wizard confirm with a different timestamp is refused`() {
        prepareWizard(insulin = 2.5, carbs = 40)

        rxBus.send(EventData.ActionWizardPreCheck(40, 100))
        rxBus.send(EventData.ActionWizardConfirmed(now - 1))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun `quick wizard happy path delivers once from its own entry, including ecarbs and superbolus`() {
        prepareWizard(insulin = 3.0, carbs = 30)
        quickWizardEntry(carbs = 30, superBolus = true, eCarbs = 20)

        rxBus.send(EventData.ActionQuickWizardPreCheck("guid"))
        val offered = confirmsOffered().single()
        assertThat(offered).isEqualTo(EventData.ActionWizardConfirmed(now))
        rxBus.send(offered)
        rxBus.send(offered)

        assertThat(errorsToWatch()).isEmpty()
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(2)
        assertThat(delivered[0].insulin).isEqualTo(3.0)
        assertThat(delivered[0].carbs).isEqualTo(30.0)
        assertThat(delivered[1].insulin).isEqualTo(0.0)
        assertThat(delivered[1].carbs).isEqualTo(20.0)
        // the superbolus belongs to the confirmed quick wizard bolus only, not to its eCarbs part
        verifySuperBolusStarted(1)
    }

    @Test fun `a newer wizard precheck replaces a superbolus quick wizard, only the newer snapshot is delivered`() {
        prepareWizard(insulin = 3.0, carbs = 30)
        quickWizardEntry(carbs = 30, superBolus = true, eCarbs = 20)
        rxBus.send(EventData.ActionQuickWizardPreCheck("guid"))
        val older = confirmsOffered().single() as EventData.ActionWizardConfirmed

        // a newer plain wizard precheck arrives before the quick wizard is confirmed
        prepareWizard(insulin = 1.0, carbs = 10)
        whenever(bolusWizard.timeStamp).thenReturn(now + 1_000L)
        rxBus.send(EventData.ActionWizardPreCheck(10, 100))
        val newer = toWatch.filterIsInstance<EventData.ActionWizardResult>().last()
        assertThat(newer.timestamp).isEqualTo(now + 1_000L)

        rxBus.send(EventData.ActionWizardConfirmed(newer.timestamp))

        assertThat(errorsToWatch()).isEmpty()
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(1)
        assertThat(delivered[0].insulin).isEqualTo(1.0)
        assertThat(delivered[0].carbs).isEqualTo(10.0)
        // nothing of the replaced quick wizard (eCarbs part, superbolus) is delivered
        verifySuperBolusStarted(0)

        rxBus.send(older)
        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        assertThat(deliveredBoluses()).hasSize(1)
        verifySuperBolusStarted(0)
    }

    @Test fun `ecarbs confirm matching a quick wizard ecarbs part never starts a superbolus`() {
        prepareWizard(insulin = 3.0, carbs = 30)
        quickWizardEntry(carbs = 30, superBolus = true)
        rxBus.send(EventData.ActionQuickWizardPreCheck("guid"))
        rxBus.send(EventData.ActionECarbsPreCheck(20, 0, 2))
        val offered = confirmsOffered().last() as EventData.ActionECarbsConfirmed

        rxBus.send(offered)

        assertThat(errorsToWatch()).isEmpty()
        val delivered = deliveredBoluses()
        assertThat(delivered).hasSize(1)
        assertThat(delivered[0].insulin).isEqualTo(0.0)
        verifySuperBolusStarted(0)
    }

    @Test fun `quick wizard confirm after 61 s is refused`() {
        prepareWizard(insulin = 3.0, carbs = 30)
        quickWizardEntry(carbs = 30, superBolus = true)

        rxBus.send(EventData.ActionQuickWizardPreCheck("guid"))
        advanceClockBy(61_000L)
        rxBus.send(EventData.ActionWizardConfirmed(now))

        assertThat(errorsToWatch()).containsExactly(confirmRefused)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
        verifySuperBolusStarted(0)
    }
}
