package app.aaps.plugins.sync.wear.wearintegration

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TDD
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.automation.AutomationEvent
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpStatusProvider
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventWearUpdateGui
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.EventData.LoopStatesList.AvailableLoopState
import app.aaps.core.interfaces.rx.weardata.LoopStatusData
import app.aaps.core.interfaces.rx.weardata.TempTargetInfo
import app.aaps.core.interfaces.rx.weardata.TargetRange
import app.aaps.core.interfaces.rx.weardata.OapsResultInfo
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.generateCOBString
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.objects.wizard.QuickWizardEntry
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.sync.R
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.security.SecureRandom
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * How much BG history the watch gets in `GraphData`: the largest window a watch surface draws (the
 * chart timeframe of the watchfaces goes up to 5 h, the circle face's history ring 30 min), plus
 * margin. Raise it together with those settings, never on its own.
 */
internal const val WEAR_GRAPH_HOURS = 6L

/** How long a confirmation offered to the watch for an insulin-delivering action stays valid on the phone. */
internal const val WEAR_CONFIRM_TIMEOUT_MS = 60_000L

/**
 * How long after a confirm was accepted an identical confirm is treated as a duplicate (e.g. a double tap
 * on the watch's confirm button): it is refused silently instead of showing an error on the watch right
 * after a delivered treatment, which could prompt the user to request the same dose again.
 */
internal const val WEAR_DUPLICATE_CONFIRM_WINDOW_MS = 10_000L

/**
 * Time of the last loop run for EventData.Status.lastLoopRunAt (watch faces' loop age), 0 = unknown.
 * APS phone: Loop.lastRun.lastAPSRun, the time of the last APS decision (set on every run, whether or not a temp
 * basal was enacted; lastTBREnact, which openApsStatus carries, is reset to 0 on runs that enact nothing).
 * Loop.lastBgTriggeredRun is not used: nothing sets it. AAPSClient: the last suggested time from the device status.
 * Display only; nothing here influences dosing.
 */
internal fun wearLastLoopRunAt(isAps: Boolean, lastRun: Loop.LastRun?, clientOpenApsTimestamp: Long): Long =
    if (isAps) lastRun?.lastAPSRun?.takeIf { it > 0L } ?: 0L
    else clientOpenApsTimestamp.coerceAtLeast(0L)

@Singleton
class DataHandlerMobile @Inject constructor(
    private val aapsSchedulers: AapsSchedulers,
    private val context: Context,
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val config: Config,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val loop: Loop,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val receiverStatusStore: ReceiverStatusStore,
    private val quickWizard: QuickWizard,
    private val trendCalculator: TrendCalculator,
    private val dateUtil: DateUtil,
    private val constraintChecker: ConstraintsChecker,
    private val uel: UserEntryLogger,
    private val activePlugin: ActivePlugin,
    private val commandQueue: CommandQueue,
    private val fabricPrivacy: FabricPrivacy,
    private val uiInteraction: UiInteraction,
    private val persistenceLayer: PersistenceLayer,
    private val importExportPrefs: ImportExportPrefs,
    private val decimalFormatter: DecimalFormatter,
    private val bolusWizardProvider: Provider<BolusWizard>,
    private val pumpStatusProvider: PumpStatusProvider
) {

    @Inject lateinit var automation: Automation
    private val disposable = CompositeDisposable()

    /**
     * The last treatment confirmation this phone itself offered to the watch (bolus, wizard, quick wizard,
     * prime/fill, eCarbs). A confirm coming back from the watch is honoured only when it matches this
     * record, arrives within [WEAR_CONFIRM_TIMEOUT_MS] and is the first one to take it: the slot is emptied
     * atomically on every confirm, matching or not, so nothing is delivered twice.
     *
     * For the wizards the record also carries the calculated [wizard] and the [quickWizardEntry] it came
     * from, and delivery uses only this snapshot, so a concurrent precheck can never change what a confirm
     * delivers. [createdAt] is taken from [monotonicClock], so wall-clock changes cannot stretch the window.
     *
     * [requestId] is fresh for every precheck and travels to the watch inside the offered confirm (bolus
     * and fill), which echoes it back unchanged; such a confirm is honoured only with exactly this id, so
     * a delayed or repeated confirm of an earlier, identical request cannot approve a newer precheck.
     */
    internal class PendingWearConfirm(
        val kind: Kind,
        val requestId: Long,
        val insulin: Double,
        val carbs: Int,
        val createdAt: Long,
        val wizardTimeStamp: Long = 0L,
        val wizard: BolusWizard? = null,
        val quickWizardEntry: QuickWizardEntry? = null,
        val carbsTime: Long = 0L,
        val duration: Int = 0
    ) {

        enum class Kind { BOLUS, WIZARD, FILL, ECARBS }

        override fun toString(): String =
            "PendingWearConfirm(kind=$kind requestId=$requestId insulin=$insulin carbs=$carbs createdAt=$createdAt wizardTimeStamp=$wizardTimeStamp " +
                "quickWizard=${quickWizardEntry != null} carbsTime=$carbsTime duration=$duration)"
    }

    private class ConsumedWearConfirm(val confirm: PendingWearConfirm, val consumedAt: Long)

    /** Guards [pendingWearConfirm] and [lastConsumedConfirm]: a confirm takes the slot and classifies itself in one step. */
    private val wearConfirmLock = Any()

    private var pendingWearConfirm: PendingWearConfirm? = null

    /** The confirm accepted last, kept for [WEAR_DUPLICATE_CONFIRM_WINDOW_MS] to recognise duplicates. */
    private var lastConsumedConfirm: ConsumedWearConfirm? = null

    /** Source of [PendingWearConfirm.requestId]: starts at a random value (so ids differ across restarts), then counts up; never 0. */
    private val nextWearRequestId = AtomicLong(SecureRandom().nextLong() and 0x3FFF_FFFF_FFFF_FFFFL)

    /** Monotonic time source (ms) for the confirm window; unaffected by wall-clock changes. Replaced in tests. */
    @VisibleForTesting
    internal var monotonicClock: () -> Long = { SystemClock.elapsedRealtime() }

    init {
        // From Wear
        disposable += rxBus
            .toObservable(EventData.ActionPong::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "Pong received from ${it.sourceNodeId}")
                           fabricPrivacy.logCustom("WearOS_${it.apiLevel}")
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.CancelBolus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "CancelBolus received from ${it.sourceNodeId}")
                           activePlugin.activePump.stopBolusDelivering()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.OpenLoopRequestConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "OpenLoopRequestConfirmed received from ${it.sourceNodeId}")
                           loop.acceptChangeRequest()
                           (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(Constants.notificationID)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionResendData::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ResendData received from ${it.sourceNodeId}")
                           resendData(it.from)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionPumpStatus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionPumpStatus received from ${it.sourceNodeId}")
                           rxBus.send(
                               EventMobileToWear(
                                   EventData.ConfirmAction(
                                       rh.gs(R.string.pump_status).uppercase(),
                                       pumpStatusProvider.shortStatus(false),
                                       returnCommand = null
                                   )
                               )
                           )
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionLoopStatus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionLoopStatus received from ${it.sourceNodeId}")
                           rxBus.send(
                               EventMobileToWear(
                                   EventData.ConfirmAction(
                                       rh.gs(R.string.loop_status).uppercase(),
                                       "$targetsStatus\n\n$loopStatus\n\n$oAPSResultStatus",
                                       returnCommand = null
                                   )
                               )
                           )
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionLoopStatusDetailed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionLoopStatusDetailed received from ${it.sourceNodeId}")
                           val statusData = buildLoopStatusData()
                           rxBus.send(
                               EventMobileToWear(
                                   EventData.LoopStatusResponse(
                                       timeStamp = System.currentTimeMillis(),
                                       data = statusData
                                   )
                               )
                           )
                       }, fabricPrivacy::logException)

        disposable += rxBus
            .toObservable(EventData.LoopStatesRequest::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "LoopStatesRequest received from ${it.sourceNodeId}")
                           handleAvailableLoopStates()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.LoopStateSelected::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "LoopStateSelected received from ${it.sourceNodeId}")
                           handleLoopStateSelected(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.LoopStateConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "LoopStateSelected received from ${it.sourceNodeId}")
                           handleLoopStateConfirmed(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionTddStatus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe {
                aapsLogger.debug(LTag.WEAR, "ActionTddStatus received from ${it.sourceNodeId}")
                handleTddStatus()
            }
        disposable += rxBus
            .toObservable(EventData.ActionProfileSwitchSendInitialData::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionProfileSwitchSendInitialData received $it from ${it.sourceNodeId}")
                           handleProfileSwitchSendInitialData()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionProfileSwitchPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionProfileSwitchPreCheck received $it from ${it.sourceNodeId}")
                           if (wearControlEnabled()) handleProfileSwitchPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionProfileSwitchConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionProfileSwitchConfirmed received $it from ${it.sourceNodeId}")
                           if (wearControlEnabled()) doProfileSwitch(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionTempTargetPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionTempTargetPreCheck received $it from ${it.sourceNodeId}")
                           if (wearControlEnabled()) handleTempTargetPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionTempTargetConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionTempTargetConfirmed received $it from ${it.sourceNodeId}")
                           if (wearControlEnabled()) doTempTarget(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionBolusPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionBolusPreCheck received $it from ${it.sourceNodeId}")
                           handleBolusPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionBolusConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionBolusConfirmed received $it from ${it.sourceNodeId}")
                           val confirmed = it
                           if (acceptPendingConfirm(PendingWearConfirm.Kind.BOLUS) { p ->
                                   confirmed.requestId != 0L && p.requestId == confirmed.requestId && p.insulin == confirmed.insulin && p.carbs == confirmed.carbs
                               } != null)
                               doBolus(it.insulin, it.carbs, null, 0, null)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionECarbsPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionECarbsPreCheck received $it from ${it.sourceNodeId}")
                           handleECarbsPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionECarbsConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionECarbsConfirmed received $it from ${it.sourceNodeId}")
                           val confirmed = it
                           if (acceptPendingConfirm(PendingWearConfirm.Kind.ECARBS) { p ->
                                   p.carbs == confirmed.carbs && p.carbsTime == confirmed.carbsTime && p.duration == confirmed.duration
                               } != null)
                               doECarbs(it.carbs, it.carbsTime, it.duration)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionFillPresetPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionFillPresetPreCheck received $it from ${it.sourceNodeId}")
                           handleFillPresetPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionFillPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionFillPreCheck received $it from ${it.sourceNodeId}")
                           handleFillPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionFillConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionFillConfirmed received $it from ${it.sourceNodeId}")
                           val confirmed = it
                           if (acceptPendingConfirm(PendingWearConfirm.Kind.FILL) { p ->
                                   confirmed.requestId != 0L && p.requestId == confirmed.requestId && p.insulin == confirmed.insulin
                               } == null)
                               return@subscribe
                           if (constraintChecker.applyBolusConstraints(ConstraintObject(it.insulin, aapsLogger)).value() - it.insulin != 0.0) {
                               ToastUtils.showToastInUiThread(context, "aborting: previously applied constraint changed")
                               sendError("aborting: previously applied constraint changed")
                           } else
                               doFillBolus(it.insulin)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionQuickWizardPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionQuickWizardPreCheck received $it from ${it.sourceNodeId}")
                           handleQuickWizardPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionWizardPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionWizardPreCheck received $it from ${it.sourceNodeId}")
                           handleWizardPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionWizardConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionWizardConfirmed received $it from ${it.sourceNodeId}")
                           val confirmed = it
                           val accepted = acceptPendingConfirm(PendingWearConfirm.Kind.WIZARD) { p ->
                               val wizard = p.wizard
                               wizard != null && p.wizardTimeStamp == confirmed.timeStamp && wizard.timeStamp == confirmed.timeStamp &&
                                   p.insulin == wizard.calculatedTotalInsulin && p.carbs == wizard.carbs
                           } ?: return@subscribe
                           // deliver only from the snapshot taken by the precheck that was matched
                           doWizardBolus(accepted.wizard ?: return@subscribe, accepted.quickWizardEntry)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionUserActionPreCheck::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionUserActionPreCheck received $it from ${it.sourceNodeId}")
                           if (wearControlEnabled()) handleUserActionPreCheck(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionUserActionConfirmed::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "ActionUserActionConfirmed received $it from ${it.sourceNodeId}")
                           if (wearControlEnabled()) handleUserActionConfirmed(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.SnoozeAlert::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "SnoozeAlert received $it from ${it.sourceNodeId}")
                           uiInteraction.stopAlarm("Muted from wear")
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.WearException::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "WearException received $it from ${it.sourceNodeId}")
                           fabricPrivacy.logWearException(it)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionHeartRate::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ handleHeartRate(it) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionStepsRate::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ handleStepsCount(it) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventData.ActionGetCustomWatchface::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           aapsLogger.debug(LTag.WEAR, "Custom Watch face ${it.customWatchface} received from ${it.sourceNodeId}")
                           handleGetCustomWatchface(it)
                       }, fabricPrivacy::logException)
    }
    private fun maxOfNullable(vararg values: Long?): Long? {
        return values.filterNotNull().maxOrNull()
    }

    private fun buildLoopStatusData(): LoopStatusData {
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        val profile = profileFunction.getProfile()
        val usedAPS = activePlugin.activeAPS

        // Get data based on app type
        val (lastRunTimestamp, lastEnactTimestamp, apsResult) = if (config.APS) {
            // AAPS - use local loop data
            val lastRun = loop.lastRun

            // For enacted timestamp, use the LATEST of TBR or SMB
            val lastTbrEnact = lastRun?.lastTBREnact?.takeIf { it != 0L }
            val lastSmbEnact = lastRun?.lastSMBEnact?.takeIf { it != 0L }
            val lastEnact = maxOfNullable(lastTbrEnact, lastSmbEnact)

            Triple(
                lastRun?.lastAPSRun,
                lastEnact,
                lastRun?.constraintsProcessed
            )
        } else {
            // AAPSClient - use data from NS/device status
            val apsData = processedDeviceStatusData.openAPSData

            // Use clockEnacted only if it's within 30s of clockSuggested or newer
            val timeWindowMs = 30_000L
            val apsDataLastEnact = if (apsData.clockEnacted >= apsData.clockSuggested - timeWindowMs) {
                apsData.clockEnacted
            } else {
                null
            }
            Triple(
                apsData.clockSuggested,
                apsDataLastEnact,
                processedDeviceStatusData.getAPSResult()
            )
        }

        // Map loop mode
        val loopMode = when (loop.runningMode) {
            RM.Mode.CLOSED_LOOP -> LoopStatusData.LoopMode.CLOSED
            RM.Mode.OPEN_LOOP -> LoopStatusData.LoopMode.OPEN
            RM.Mode.CLOSED_LOOP_LGS -> LoopStatusData.LoopMode.LGS
            RM.Mode.DISABLED_LOOP -> LoopStatusData.LoopMode.DISABLED
            RM.Mode.SUSPENDED_BY_USER -> LoopStatusData.LoopMode.SUSPENDED
            RM.Mode.DISCONNECTED_PUMP -> LoopStatusData.LoopMode.DISCONNECTED
            RM.Mode.SUPER_BOLUS -> LoopStatusData.LoopMode.SUPERBOLUS
            else -> LoopStatusData.LoopMode.UNKNOWN
        }

        // Build temp target info
        val tempTargetInfo = tempTarget?.let {
            val units = if (profileUtil.units == GlucoseUnit.MGDL) "mg/dL" else "mmol/L"
            val targetString = profileUtil.toTargetRangeString(
                it.lowTarget,
                it.highTarget,
                GlucoseUnit.MGDL
            )
            val durationMin = ((it.end - dateUtil.now()) / 60000).toInt()

            TempTargetInfo(
                targetDisplay = targetString,
                endTime = it.end,
                durationMinutes = durationMin,
                units = units
            )
        }

        // Build default range
        val defaultRange = if (profile != null) {
            val units = if (profileUtil.units == GlucoseUnit.MGDL) "mg/dL" else "mmol/L"
            TargetRange(
                lowDisplay = profileUtil.fromMgdlToStringInUnits(profile.getTargetLowMgdl()),
                highDisplay = profileUtil.fromMgdlToStringInUnits(profile.getTargetHighMgdl()),
                targetDisplay = profileUtil.fromMgdlToStringInUnits(profile.getTargetMgdl()),
                units = units
            )
        } else {
            TargetRange("--", "--", "--", "")
        }

        // Build OAPS result info
        val oapsResultInfo = apsResult?.let { result ->
            val constrainedRate = result.rate
            val constrainedDuration = result.duration

            // Check if this is "let temp basal run" scenario
            // AAPS: rate=0.0 and duration=-1
            // AAPSClient: rate=-1.0 and duration=-1
            val isLetTempRun = if (config.APS) {
                constrainedRate == 0.0 && constrainedDuration == -1
            } else {
                constrainedRate == -1.0 && constrainedDuration == -1
            }

            // Determine what to display
            val (displayRate, displayDuration, displayPercent) = if (isLetTempRun) {
                // Get currently running temp basal from database
                val currentTbr = persistenceLayer.getTemporaryBasalActiveAt(dateUtil.now())

                if (currentTbr != null) {
                    // Calculate absolute rate
                    val rate = if (currentTbr.isAbsolute) {
                        currentTbr.rate
                    } else if (profile != null) {
                        // Percent-based TBR - convert to absolute
                        profile.getBasal(dateUtil.now()) * currentTbr.rate / 100.0
                    } else {
                        currentTbr.rate
                    }

                    // Calculate remaining duration
                    val remainingMin = ((currentTbr.end - dateUtil.now()) / 60000).toInt()

                    val percentValue = if (activePlugin.activePump.baseBasalRate > 0) {
                        ((rate / activePlugin.activePump.baseBasalRate) * 100).toInt()
                    } else 0

                    aapsLogger.debug(LTag.WEAR, "Let temp run - rate: $rate U/h ($percentValue%), remaining: $remainingMin min")

                    Triple(rate, remainingMin, percentValue)
                } else {
                    aapsLogger.debug(LTag.WEAR, "Let temp run requested but no active TBR found")
                    Triple(null, null, null)
                }
            } else {
                // Normal case - show the new requested values
                val percentValue = if (result.usePercent) {
                    result.percent
                } else if (activePlugin.activePump.baseBasalRate > 0) {
                    ((constrainedRate / activePlugin.activePump.baseBasalRate) * 100).toInt()
                } else null

                // For AAPSClient, use current TBR rate if available, otherwise use constrained rate
                val finalRate = if (!config.APS) {
                    val currentTbr = persistenceLayer.getTemporaryBasalActiveAt(dateUtil.now())
                    currentTbr?.rate ?: constrainedRate
                } else {
                    constrainedRate
                }

                Triple(finalRate, constrainedDuration, percentValue)
            }

            OapsResultInfo(
                changeRequested = result.isChangeRequested && !isLetTempRun,
                isLetTempRun = isLetTempRun,
                rate = displayRate,
                ratePercent = displayPercent,
                duration = displayDuration,
                reason = result.reason,
                smbAmount = result.smb
            )
        }

        return LoopStatusData(
            timestamp = System.currentTimeMillis(),
            loopMode = loopMode,
            apsName = if (loop.runningMode.isLoopRunning())
                (usedAPS as PluginBase).name else null,
            lastRun = lastRunTimestamp,
            lastEnact = lastEnactTimestamp,
            tempTarget = tempTargetInfo,
            defaultRange = defaultRange,
            oapsResult = oapsResultInfo
        )
    }

    private fun handleTddStatus() {
        val activePump = activePlugin.activePump
        var message: String
        // check if DB up to date
        val dummies: MutableList<TDD> = LinkedList()
        val historyList = getTDDList(dummies)
        if (isOldData(historyList)) {
            message = rh.gs(app.aaps.core.ui.R.string.tdd_old_data) + ", "
            //if pump is not busy: try to fetch data
            if (activePump.isBusy()) {
                message += rh.gs(app.aaps.core.ui.R.string.pump_busy)
            } else {
                message += rh.gs(R.string.pump_fetching_data)
                commandQueue.loadTDDs(object : Callback() {
                    override fun run() {
                        val dummies1: MutableList<TDD> = LinkedList()
                        val historyList1 = getTDDList(dummies1)
                        val reloadMessage =
                            if (isOldData(historyList1))
                                rh.gs(R.string.pump_old_data) + "\n" + generateTDDMessage(historyList1, dummies1)
                            else
                                generateTDDMessage(historyList1, dummies1)
                        rxBus.send(
                            EventMobileToWear(
                                EventData.ConfirmAction(
                                    rh.gs(app.aaps.core.ui.R.string.tdd),
                                    reloadMessage,
                                    returnCommand = null
                                )
                            )
                        )
                    }
                })
            }
        } else { // if up to date: prepare, send (check if CPP is activated -> add CPP stats)
            message = generateTDDMessage(historyList, dummies)
        }
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.tdd),
                    message,
                    returnCommand = null
                )
            )
        )
    }

    private fun handleWizardPreCheck(command: EventData.ActionWizardPreCheck) {
        clearPendingConfirm()
        if (!wearControlEnabled()) return
        val pump = activePlugin.activePump
        if (!pump.isInitialized() || loop.runningMode.isSuspended()) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_pump_not_available))
            return
        }
        val carbsBeforeConstraints = command.carbs
        val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(carbsBeforeConstraints, aapsLogger)).value()
        if (carbsAfterConstraints - carbsBeforeConstraints != 0) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_carbs_constraint))
            return
        }
        val percentage = command.percentage
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        if (profile == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_active_profile))
            return
        }
        val bgReading = iobCobCalculator.ads.actualBg()
        if (bgReading == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_actual_bg))
            return
        }
        val cobInfo = iobCobCalculator.getCobInfo("Wizard wear")
        if (cobInfo.displayCob == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_cob))
            return
        }
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())

        // Store the preference values before calling doCalc
        val useBgPref = preferences.get(BooleanKey.WearWizardBg)
        val useCobPref = preferences.get(BooleanKey.WearWizardCob)
        val useIobPref = preferences.get(BooleanKey.WearWizardIob)
        val useTTPref = preferences.get(BooleanKey.WearWizardTt)
        val useTrendPref = preferences.get(BooleanKey.WearWizardTrend)

        val bolusWizard = bolusWizardProvider.get().doCalc(
            profile = profile,
            profileName = profileName,
            tempTarget = tempTarget,
            carbs = carbsAfterConstraints,
            cob = cobInfo.displayCob!!,
            bg = bgReading.valueToUnits(profileFunction.getUnits()),
            correction = 0.0,
            percentageCorrection = percentage,
            useBg = useBgPref,
            useCob = useCobPref,
            includeBolusIOB = useIobPref,
            includeBasalIOB = useIobPref,
            useSuperBolus = false,
            useTT = useTTPref,
            useTrend = useTrendPref,
            useAlarm = false
        )
        val insulinAfterConstraints = bolusWizard.insulinAfterConstraints
        val minStep = pump.pumpDescription.pumpType.determineCorrectBolusStepSize(insulinAfterConstraints)
        if (abs(insulinAfterConstraints - bolusWizard.calculatedTotalInsulin) >= minStep) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_constraint_bolus_size, bolusWizard.calculatedTotalInsulin))
            return
        }
        if (bolusWizard.calculatedTotalInsulin <= 0 && bolusWizard.carbs <= 0) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_insulin_required))
            return
        }

        // Format temp target string if present
        val tempTargetString = if (useTTPref && tempTarget != null) {
            profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL)
        } else null

        // Build structured wizard result
        // Use the public properties that ARE available
        val wizardResult = EventData.ActionWizardResult(
            timestamp = bolusWizard.timeStamp,
            totalInsulin = bolusWizard.calculatedTotalInsulin,
            carbs = bolusWizard.carbs,
            ic = bolusWizard.ic,
            sens = bolusWizard.sens,
            insulinFromCarbs = bolusWizard.insulinFromCarbs,
            insulinFromBG = if (useBgPref) bolusWizard.insulinFromBG else null,
            insulinFromCOB = if (useCobPref) bolusWizard.insulinFromCOB else null,
            insulinFromBolusIOB = if (useIobPref) -bolusWizard.insulinFromBolusIOB else null,
            insulinFromBasalIOB = if (useIobPref) -bolusWizard.insulinFromBasalIOB else null,
            insulinFromTrend = if (useTrendPref) bolusWizard.insulinFromTrend else null,
            insulinFromSuperBolus = null,
            tempTarget = tempTargetString,
            percentageCorrection = if (percentage != 100) percentage else null,
            totalBeforePercentage = if (percentage != 100) bolusWizard.totalBeforePercentageAdjustment else null,
            cob = bolusWizard.cob
        )
        offerPendingConfirm(PendingWearConfirm.Kind.WIZARD, bolusWizard.calculatedTotalInsulin, bolusWizard.carbs, wizard = bolusWizard)
        rxBus.send(EventMobileToWear(wizardResult))
    }

    private fun handleUserActionPreCheck(command: EventData.ActionUserActionPreCheck) {
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        if (loop.runningMode.isLoopRunning() && pump.isInitialized() && profile != null) {
            val events = automation.userEvents()
            events.find { it.hashCode() == command.id }?.let { event ->
                if (event.isEnabled && event.canRun()) {
                    rxBus.send(
                        EventMobileToWear(
                            EventData.ConfirmAction(
                                rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), command.title,
                                returnCommand = EventData.ActionUserActionConfirmed(command.id, command.title)
                            )
                        )
                    )
                } else {
                    sendError(rh.gs(R.string.user_action_not_available, command.title))
                }
            } ?: apply {
                sendError(rh.gs(R.string.user_action_not_available, command.title))
            }
        } else {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_pump_not_available))
        }
    }

    private fun handleUserActionConfirmed(command: EventData.ActionUserActionConfirmed) {
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        if (loop.runningMode.isLoopRunning() && pump.isInitialized() && profile != null) {
            val events = automation.userEvents()
            events.find { it.hashCode() == command.id }?.let { event ->
                if (event.isEnabled && event.canRun()) {
                    disposable += Completable.fromAction { automation.processEvent(event) }
                        .subscribeOn(aapsSchedulers.io)
                        .subscribe()
                }
            }
        }
    }

    private fun handleQuickWizardPreCheck(command: EventData.ActionQuickWizardPreCheck) {
        clearPendingConfirm()
        if (!wearControlEnabled()) return
        val actualBg = iobCobCalculator.ads.actualBg()
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val quickWizardEntry = quickWizard.get(command.guid)
        if (quickWizardEntry == null) {
            sendError(rh.gs(R.string.quick_wizard_not_available))
            return
        }
        if (actualBg == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_actual_bg))
            return
        }
        if (profile == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_active_profile))
            return
        }
        val cobInfo = iobCobCalculator.getCobInfo("QuickWizard wear")
        if (cobInfo.displayCob == null) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_no_cob))
            return
        }
        val pump = activePlugin.activePump
        if (!pump.isInitialized() || loop.runningMode.isSuspended()) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_pump_not_available))
            return
        }

        val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg)

        val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(quickWizardEntry.carbs(), aapsLogger)).value()
        if (carbsAfterConstraints != quickWizardEntry.carbs()) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_carbs_constraint))
            return
        }
        val insulinAfterConstraints = wizard.insulinAfterConstraints
        val minStep = pump.pumpDescription.pumpType.determineCorrectBolusStepSize(insulinAfterConstraints)
        if (abs(insulinAfterConstraints - wizard.calculatedTotalInsulin) >= minStep) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_constraint_bolus_size, wizard.calculatedTotalInsulin))
            return
        }

        var eCarbsMessagePart = ""
        if (quickWizardEntry.useEcarbs() == QuickWizardEntry.YES) {
            val carbs2 = quickWizardEntry.carbs2()
            val offset = quickWizardEntry.time()
            val duration = quickWizardEntry.duration()

            eCarbsMessagePart += "\n+" + carbs2.toString() + rh.gs(R.string.grams_short) + "/" + duration.toString() + rh.gs(R.string.hour_short) + "(+" + offset.toString() +
                rh.gs(app.aaps.core.interfaces.R.string.shortminute) + ")"
        }

        var carbDelayMessagePart = ""
        if (quickWizardEntry.carbTime() > 0) {
            carbDelayMessagePart = "(+" + quickWizardEntry.carbTime().toString() + rh.gs(app.aaps.core.interfaces.R.string.shortminute) + ")"
            if (quickWizardEntry.useAlarm() == QuickWizardEntry.YES) {
                carbDelayMessagePart += "!"
            }
        }

        val message = rh.gs(R.string.quick_wizard_message, quickWizardEntry.buttonText(), wizard.calculatedTotalInsulin, quickWizardEntry.carbs()) +
            carbDelayMessagePart +
            eCarbsMessagePart + "\n_____________\n" + wizard.explainShort()

        offerPendingConfirm(PendingWearConfirm.Kind.WIZARD, wizard.calculatedTotalInsulin, wizard.carbs, wizard = wizard, quickWizardEntry = quickWizardEntry)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionWizardConfirmed(wizard.timeStamp)
                )
            )
        )
    }

    private fun handleBolusPreCheck(command: EventData.ActionBolusPreCheck) {
        clearPendingConfirm()
        if (!wearControlEnabled()) return
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(command.insulin, aapsLogger)).value()
        val cob = iobCobCalculator.ads.getLastAutosensData("carbsDialog", aapsLogger, dateUtil)?.cob ?: 0.0
        var carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(command.carbs, aapsLogger)).value()
        val pump = activePlugin.activePump
        if (insulinAfterConstraints > 0 && (!pump.isInitialized() || loop.runningMode.isSuspended())) {
            sendError(rh.gs(app.aaps.core.ui.R.string.wizard_pump_not_available))
            return
        }
        // Handle negative carbs constraint
        if (carbsAfterConstraints < 0) {
            if (carbsAfterConstraints < -cob) carbsAfterConstraints = ceil(-cob).toInt()
        }
        var message = ""
        message += rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + insulinAfterConstraints + rh.gs(R.string.units_short) + "\n"
        message += rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + carbsAfterConstraints + rh.gs(R.string.grams_short)
        if (insulinAfterConstraints - command.insulin != 0.0 || carbsAfterConstraints - command.carbs != 0)
            message += "\n" + rh.gs(app.aaps.core.ui.R.string.constraint_applied)
        val requestId = offerPendingConfirm(PendingWearConfirm.Kind.BOLUS, insulinAfterConstraints, carbsAfterConstraints)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionBolusConfirmed(insulinAfterConstraints, carbsAfterConstraints, requestId)
                )
            )
        )
    }

    private fun handleECarbsPreCheck(command: EventData.ActionECarbsPreCheck) {
        clearPendingConfirm()
        if (!wearControlEnabled()) return
        val startTimeStamp = System.currentTimeMillis() + T.mins(command.carbsTimeShift.toLong()).msecs()
        val cob = iobCobCalculator.ads.getLastAutosensData("carbsDialog", aapsLogger, dateUtil)?.cob ?: 0.0
        var carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(command.carbs, aapsLogger)).value()
        // Handle negative carbs constraint
        if (carbsAfterConstraints < 0) {
            if (carbsAfterConstraints < -cob) carbsAfterConstraints = ceil(-cob).toInt()
        }
        var message = rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + carbsAfterConstraints + rh.gs(R.string.grams_short) +
            "\n" + rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.timeString(startTimeStamp) +
            "\n" + rh.gs(app.aaps.core.ui.R.string.duration) + ": " + command.duration + rh.gs(R.string.hour_short)
        if (carbsAfterConstraints != command.carbs) {
            message += "\n" + rh.gs(app.aaps.core.ui.R.string.constraint_applied)
        }
        if (carbsAfterConstraints == 0) {
            sendError(rh.gs(app.aaps.core.ui.R.string.carb_equal_zero_no_action))
            return
        }
        offerPendingConfirm(PendingWearConfirm.Kind.ECARBS, 0.0, carbsAfterConstraints, carbsTime = startTimeStamp, duration = command.duration)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionECarbsConfirmed(carbsAfterConstraints, startTimeStamp, command.duration)
                )
            )
        )
    }

    private fun handleFillPresetPreCheck(command: EventData.ActionFillPresetPreCheck) {
        clearPendingConfirm()
        if (!wearControlEnabled()) return
        val amount: Double = when (command.button) {
            1    -> preferences.get(DoubleKey.ActionsFillButton1)
            2    -> preferences.get(DoubleKey.ActionsFillButton2)
            3    -> preferences.get(DoubleKey.ActionsFillButton3)
            else -> return
        }
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(amount, aapsLogger)).value()
        var message = rh.gs(app.aaps.core.ui.R.string.prime_fill) + ": " + insulinAfterConstraints + rh.gs(R.string.units_short)
        if (insulinAfterConstraints - amount != 0.0) message += "\n" + rh.gs(app.aaps.core.ui.R.string.constraint_applied)
        val requestId = offerPendingConfirm(PendingWearConfirm.Kind.FILL, insulinAfterConstraints, 0)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionFillConfirmed(insulinAfterConstraints, requestId)
                )
            )
        )
    }

    private fun handleFillPreCheck(command: EventData.ActionFillPreCheck) {
        clearPendingConfirm()
        if (!wearControlEnabled()) return
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(command.insulin, aapsLogger)).value()
        var message = rh.gs(app.aaps.core.ui.R.string.prime_fill) + ": " + insulinAfterConstraints + rh.gs(R.string.units_short)
        if (insulinAfterConstraints - command.insulin != 0.0) message += "\n" + rh.gs(app.aaps.core.ui.R.string.constraint_applied)
        val requestId = offerPendingConfirm(PendingWearConfirm.Kind.FILL, insulinAfterConstraints, 0)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionFillConfirmed(insulinAfterConstraints, requestId)
                )
            )
        )
    }

    private fun handleProfileSwitchSendInitialData() {
        val activeProfileSwitch = persistenceLayer.getEffectiveProfileSwitchActiveAt(dateUtil.now())
        if (activeProfileSwitch != null) { // read CPP values
            rxBus.send(
                EventMobileToWear(EventData.ActionProfileSwitchOpenActivity(T.msecs(activeProfileSwitch.originalTimeshift).hours().toInt(), activeProfileSwitch.originalPercentage, activeProfileSwitch.originalDuration.toInt()))
            )
        } else {
            sendError(rh.gs(R.string.no_active_profile))
            return
        }

    }

    private fun handleProfileSwitchPreCheck(command: EventData.ActionProfileSwitchPreCheck) {
        val activeProfileSwitch = persistenceLayer.getEffectiveProfileSwitchActiveAt(dateUtil.now())
        if (activeProfileSwitch == null) {
            sendError(rh.gs(R.string.no_active_profile))
        }
        if (command.percentage < Constants.CPP_MIN_PERCENTAGE || command.percentage > Constants.CPP_MAX_PERCENTAGE) {
            sendError(rh.gs(app.aaps.core.ui.R.string.valueoutofrange, "Profile-Percentage"))
        }
        if (command.timeShift < Constants.CPP_MIN_TIMESHIFT || command.timeShift > Constants.CPP_MAX_TIMESHIFT) {
            sendError(rh.gs(app.aaps.core.ui.R.string.valueoutofrange, "Profile-Timeshift"))
        }
        if (command.duration < 0 || command.duration > Constants.MAX_PROFILE_SWITCH_DURATION) {
            sendError(rh.gs(app.aaps.core.ui.R.string.valueoutofrange, "Profile-Duration"))
        }
        val profileName = profileFunction.getOriginalProfileName()
        val message = rh.gs(R.string.profile_message, profileName, command.timeShift, command.percentage, command.duration)
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(app.aaps.core.ui.R.string.confirm).uppercase(), message,
                    returnCommand = EventData.ActionProfileSwitchConfirmed(command.timeShift, command.percentage, command.duration)
                )
            )
        )
    }

    private fun formatGlucose(value: Double, isMgdl: Boolean): String {
        return if (isMgdl)
            String.format(Locale.getDefault(), "%.0f mg/dl", value)
        else
            String.format(Locale.getDefault(), "%.1f mmol/l", value)
    }

    private fun handleTempTargetPreCheck(action: EventData.ActionTempTargetPreCheck) {
        val title = rh.gs(app.aaps.core.ui.R.string.confirm).uppercase()
        var message = ""
        val presetIsMGDL = profileFunction.getUnits() == GlucoseUnit.MGDL
        when (action.command) {
            EventData.ActionTempTargetPreCheck.TempTargetCommand.PRESET_ACTIVITY -> {
                val activityTTDuration = preferences.get(IntKey.OverviewActivityDuration)
                val activityTT = preferences.get(UnitDoubleKey.OverviewActivityTarget)
                val formattedGlucoseValue = formatGlucose(activityTT, presetIsMGDL)
                val reason = rh.gs(app.aaps.core.ui.R.string.activity)
                message += rh.gs(R.string.wear_action_tempt_preset_message, reason, formattedGlucoseValue, activityTTDuration)
                rxBus.send(
                    EventMobileToWear(
                        EventData.ConfirmAction(
                            title, message,
                            returnCommand = EventData.ActionTempTargetConfirmed(presetIsMGDL, activityTTDuration, activityTT, activityTT)
                        )
                    )
                )
            }

            EventData.ActionTempTargetPreCheck.TempTargetCommand.PRESET_HYPO     -> {
                val hypoTTDuration = preferences.get(IntKey.OverviewHypoDuration)
                val hypoTT = preferences.get(UnitDoubleKey.OverviewHypoTarget)
                val formattedGlucoseValue = formatGlucose(hypoTT, presetIsMGDL)
                val reason = rh.gs(app.aaps.core.ui.R.string.hypo)
                message += rh.gs(R.string.wear_action_tempt_preset_message, reason, formattedGlucoseValue, hypoTTDuration)
                rxBus.send(
                    EventMobileToWear(
                        EventData.ConfirmAction(
                            title, message,
                            returnCommand = EventData.ActionTempTargetConfirmed(presetIsMGDL, hypoTTDuration, hypoTT, hypoTT)
                        )
                    )
                )
            }

            EventData.ActionTempTargetPreCheck.TempTargetCommand.PRESET_EATING   -> {
                val eatingSoonTTDuration = preferences.get(IntKey.OverviewEatingSoonDuration)
                val eatingSoonTT = preferences.get(UnitDoubleKey.OverviewEatingSoonTarget)
                val formattedGlucoseValue = formatGlucose(eatingSoonTT, presetIsMGDL)
                val reason = rh.gs(app.aaps.core.ui.R.string.eatingsoon)
                message += rh.gs(R.string.wear_action_tempt_preset_message, reason, formattedGlucoseValue, eatingSoonTTDuration)
                rxBus.send(
                    EventMobileToWear(
                        EventData.ConfirmAction(
                            title, message,
                            returnCommand = EventData.ActionTempTargetConfirmed(presetIsMGDL, eatingSoonTTDuration, eatingSoonTT, eatingSoonTT)
                        )
                    )
                )
            }

            EventData.ActionTempTargetPreCheck.TempTargetCommand.CANCEL          -> {
                message += rh.gs(R.string.wear_action_tempt_cancel_message)
                rxBus.send(
                    EventMobileToWear(
                        EventData.ConfirmAction(
                            title, message,
                            returnCommand = EventData.ActionTempTargetConfirmed(true, 0, 0.0, 0.0)
                        )
                    )
                )
            }

            EventData.ActionTempTargetPreCheck.TempTargetCommand.MANUAL          -> {
                if (profileFunction.getUnits() == GlucoseUnit.MGDL != action.isMgdl) {
                    sendError(rh.gs(R.string.wear_action_tempt_unit_error))
                    return
                }
                if (action.duration == 0) {
                    message += rh.gs(R.string.wear_action_tempt_zero_message)
                    rxBus.send(
                        EventMobileToWear(
                            EventData.ConfirmAction(
                                title, message,
                                returnCommand = EventData.ActionTempTargetConfirmed(true, 0, 0.0, 0.0)
                            )
                        )
                    )
                } else {
                    var low = action.low
                    var high = action.high
                    val lowFormattedGlucoseValue = formatGlucose(low, presetIsMGDL)
                    val highFormattedGlucoseValue = formatGlucose(high, presetIsMGDL)
                    if (!action.isMgdl) {
                        low *= Constants.MMOLL_TO_MGDL
                        high *= Constants.MMOLL_TO_MGDL
                    }
                    if (low < HardLimits.LIMIT_TEMP_MIN_BG[0] || low > HardLimits.LIMIT_TEMP_MIN_BG[1]) {
                        sendError(rh.gs(R.string.wear_action_tempt_min_bg_error))
                        return
                    }
                    if (high < HardLimits.LIMIT_TEMP_MAX_BG[0] || high > HardLimits.LIMIT_TEMP_MAX_BG[1]) {
                        sendError(rh.gs(R.string.wear_action_tempt_max_bg_error))
                        return
                    }
                    if (low > high) {
                        sendError(rh.gs(R.string.wear_action_tempt_range_error, lowFormattedGlucoseValue, highFormattedGlucoseValue))
                        return
                    }
                    message += if (low == high) rh.gs(R.string.wear_action_tempt_manual_message, lowFormattedGlucoseValue, action.duration)
                    else rh.gs(R.string.wear_action_tempt_manual_range_message, lowFormattedGlucoseValue, highFormattedGlucoseValue, action.duration)
                    rxBus.send(
                        EventMobileToWear(
                            EventData.ConfirmAction(
                                title, message,
                                returnCommand = EventData.ActionTempTargetConfirmed(presetIsMGDL, action.duration, action.low, action.high)
                            )
                        )
                    )
                }
            }
        }
    }

    // To make sure WearOS-sent loop state change is constrained
    private var lastAuthorizedLoopStateChangeTS: Long? = null
    private var lastLoopStates: List<AvailableLoopState>? = null

    private fun handleAvailableLoopStates() {
        // See LoopDialog for states list building logic
        if (config.AAPSCLIENT) return

        val pump = activePlugin.activePump
        val pumpDescription = pump.pumpDescription
        if (loop.runningMode.isSuspended()) return
        if (!profileFunction.isProfileValid("WearDataHandler_LoopChangeState")) return

        val disconnectDurs = arrayListOf<Int>()
        if (pumpDescription.tempDurationStep15mAllowed) disconnectDurs.add(15)
        if (pumpDescription.tempDurationStep30mAllowed) disconnectDurs.add(30)
        for (i in listOf(1, 2, 3)) disconnectDurs.add(i * 60)

        fun mapMode(mode: RM.Mode): AvailableLoopState? =
            when (mode) {
                RM.Mode.CLOSED_LOOP       -> AvailableLoopState(AvailableLoopState.LoopState.LOOP_CLOSED)
                RM.Mode.CLOSED_LOOP_LGS   -> AvailableLoopState(AvailableLoopState.LoopState.LOOP_LGS)
                RM.Mode.OPEN_LOOP         -> AvailableLoopState(AvailableLoopState.LoopState.LOOP_OPEN)
                RM.Mode.DISABLED_LOOP     -> AvailableLoopState(AvailableLoopState.LoopState.LOOP_DISABLE)
                RM.Mode.SUPER_BOLUS       -> null
                RM.Mode.DISCONNECTED_PUMP -> AvailableLoopState(AvailableLoopState.LoopState.PUMP_DISCONNECT, disconnectDurs)
                RM.Mode.SUSPENDED_BY_PUMP -> null
                RM.Mode.SUSPENDED_BY_USER -> AvailableLoopState(AvailableLoopState.LoopState.LOOP_USER_SUSPEND, listOf(1, 2, 3, 10).map { it * 60 })
                RM.Mode.SUSPENDED_BY_DST  -> null
                RM.Mode.RESUME            -> AvailableLoopState(AvailableLoopState.LoopState.LOOP_RESUME)
            }

        val states = loop.allowedNextModes().mapNotNull { mapMode(it) }
        lastAuthorizedLoopStateChangeTS = System.currentTimeMillis()
        lastLoopStates = states
        rxBus.send(
            EventMobileToWear(
                EventData.LoopStatesList(lastAuthorizedLoopStateChangeTS!!, states)
            )
        )
    }

    private fun handleLoopStateSelected(action: EventData.LoopStateSelected) {
        if (action.timeStamp != lastAuthorizedLoopStateChangeTS) return sendError(rh.gs(R.string.wear_action_loop_state_unauthorized))
        val newState = lastLoopStates?.elementAtOrNull(action.index) ?: return sendError(rh.gs(R.string.wear_action_loop_state_invalid))
        val nDuration = action.duration ?: 0
        rxBus.send(
            EventMobileToWear(
                EventData.ConfirmAction(
                    rh.gs(R.string.wear_action_loop_state_title),
                    when (newState.state) {
                        AvailableLoopState.LoopState.LOOP_CLOSED       -> rh.gs(R.string.wear_action_loop_state_now_closed)
                        AvailableLoopState.LoopState.LOOP_LGS          -> rh.gs(R.string.wear_action_loop_state_now_lgs)
                        AvailableLoopState.LoopState.LOOP_OPEN         -> rh.gs(R.string.wear_action_loop_state_now_open)
                        AvailableLoopState.LoopState.LOOP_RESUME       -> rh.gs(R.string.wear_action_loop_state_now_resumed)
                        AvailableLoopState.LoopState.LOOP_DISABLE      -> rh.gs(R.string.wear_action_loop_state_now_disabled)
                        AvailableLoopState.LoopState.SUPERBOLUS        -> rh.gs(R.string.wear_action_loop_state_now_superbolus)
                        AvailableLoopState.LoopState.LOOP_UNKNOWN      -> rh.gs(R.string.wear_action_loop_state_now_invalid)
                        AvailableLoopState.LoopState.LOOP_USER_SUSPEND -> rh.gs(R.string.wear_action_loop_state_changed_with_duration, rh.gs(R.string.wear_action_loop_state_now_suspended), nDuration)
                        AvailableLoopState.LoopState.LOOP_PUMP_SUSPEND -> rh.gs(R.string.wear_action_loop_state_now_pump_suspended)
                        AvailableLoopState.LoopState.PUMP_DISCONNECT   -> rh.gs(R.string.wear_action_loop_state_changed_with_duration, rh.gs(R.string.wear_action_loop_state_now_pump_disconnected), nDuration)
                    },
                    EventData.LoopStateConfirmed(action.timeStamp, action.index, action.duration)
                )
            )
        )
    }

    private fun handleLoopStateConfirmed(action: EventData.LoopStateConfirmed) {
        val profile = profileFunction.getProfile() ?: return sendError(rh.gs(R.string.no_active_profile))
        if (action.timeStamp != lastAuthorizedLoopStateChangeTS) return sendError(rh.gs(R.string.wear_action_loop_state_unauthorized))
        lastAuthorizedLoopStateChangeTS = null
        val newState = lastLoopStates?.elementAtOrNull(action.index) ?: return sendError(rh.gs(R.string.wear_action_loop_state_invalid))
        lastLoopStates = null

        val nDuration = action.duration ?: 0
        val durationValid = action.duration != null && action.duration!! > 0
        when (newState.state) {
            AvailableLoopState.LoopState.LOOP_CLOSED                                                                                           ->
                loop.handleRunningModeChange(newRM = RM.Mode.CLOSED_LOOP, action = Action.CLOSED_LOOP_MODE, source = Sources.Wear, profile = profile)

            AvailableLoopState.LoopState.LOOP_LGS                                                                                              ->
                loop.handleRunningModeChange(newRM = RM.Mode.CLOSED_LOOP_LGS, action = Action.LGS_LOOP_MODE, source = Sources.Wear, profile = profile)

            AvailableLoopState.LoopState.LOOP_OPEN                                                                                             ->
                loop.handleRunningModeChange(newRM = RM.Mode.OPEN_LOOP, action = Action.OPEN_LOOP_MODE, source = Sources.Wear, profile = profile)

            AvailableLoopState.LoopState.LOOP_DISABLE                                                                                          ->
                loop.handleRunningModeChange(newRM = RM.Mode.DISABLED_LOOP, action = Action.LOOP_DISABLED, source = Sources.Wear, profile = profile)

            AvailableLoopState.LoopState.LOOP_RESUME                                                                                           -> {
                loop.handleRunningModeChange(newRM = RM.Mode.RESUME, action = Action.LOOP_RESUME, source = Sources.Wear, profile = profile)
            }

            AvailableLoopState.LoopState.LOOP_USER_SUSPEND                                                                                     -> {
                if (!durationValid) return sendError(rh.gs(R.string.wear_action_loop_state_invalid))
                loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = nDuration, action = Action.SUSPEND, source = Sources.Wear, profile = profile)
            }

            AvailableLoopState.LoopState.PUMP_DISCONNECT                                                                                       -> {
                if (!durationValid) return sendError(rh.gs(R.string.wear_action_loop_state_invalid))
                loop.handleRunningModeChange(
                    newRM = RM.Mode.DISCONNECTED_PUMP,
                    durationInMinutes = nDuration,
                    action = Action.DISCONNECT,
                    source = Sources.Wear,
                    profile = profile,
                    listValues = listOf(if (nDuration >= 60) ValueWithUnit.Hour(nDuration / 60) else ValueWithUnit.Minute(nDuration))
                )
            }

            AvailableLoopState.LoopState.LOOP_UNKNOWN, AvailableLoopState.LoopState.SUPERBOLUS, AvailableLoopState.LoopState.LOOP_PUMP_SUSPEND -> {
                return sendError(rh.gs(R.string.wear_action_loop_state_invalid))
            }
        }
    }

    private fun QuickWizardEntry.toWear(): EventData.QuickWizard.QuickWizardEntry =
        EventData.QuickWizard.QuickWizardEntry(
            guid = guid(),
            buttonText = buttonText(),
            carbs = carbs(),
            validFrom = validFrom(),
            validTo = validTo()
        )

    fun resendData(from: String) {
        aapsLogger.debug(LTag.WEAR, "Sending data to wear from $from")
        // SingleBg
        iobCobCalculator.ads.lastBg()?.let { rxBus.send(EventMobileToWear(getSingleBG(it))) }
        // Preferences
        rxBus.send(
            EventMobileToWear(
                EventData.Preferences(
                    timeStamp = System.currentTimeMillis(),
                    wearControl = preferences.get(BooleanKey.WearControl),
                    unitsMgdl = profileFunction.getUnits() == GlucoseUnit.MGDL,
                    bolusPercentage = preferences.get(IntKey.OverviewBolusPercentage),
                    maxCarbs = preferences.get(IntKey.SafetyMaxCarbs),
                    maxBolus = preferences.get(DoubleKey.SafetyMaxBolus),
                    insulinButtonIncrement1 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement1),
                    insulinButtonIncrement2 = preferences.get(DoubleKey.OverviewInsulinButtonIncrement2),
                    carbsButtonIncrement1 = preferences.get(IntKey.OverviewCarbsButtonIncrement1),
                    carbsButtonIncrement2 = preferences.get(IntKey.OverviewCarbsButtonIncrement2)
                )
            )
        )
        // QuickWizard
        rxBus.send(
            EventMobileToWear(
                EventData.QuickWizard(
                    ArrayList(quickWizard.list().filter { it.forDevice(QuickWizardEntry.DEVICE_WATCH) }.map { it.toWear() })
                )
            )
        )
        //UserAction
        sendUserActions()
        // GraphData
        buildGraphData()?.let { rxBus.send(EventMobileToWear(it)) }
        // Treatments
        sendTreatments()
        // Status
        // Keep status last. Wear start refreshing after status received
        sendStatus(from)
        handleAvailableLoopStates()
    }

    /**
     * The BG history for the watch graph, or null before the bucketed table exists.
     *
     * The table holds 24 h plus the longest DIA, about 400 buckets (~120 KB as a message); the watch
     * draws at most [WEAR_GRAPH_HOURS] of it. Sending all of it cost the transfer, the parse and the
     * store on the watch, on every resend.
     */
    // internal, not private, so the test can drive it without the rest of resendData.
    internal fun buildGraphData(): EventData.GraphData? =
        iobCobCalculator.ads.getBucketedDataTableCopy()?.let { bucketedData ->
            val graphStart = dateUtil.now() - T.hours(WEAR_GRAPH_HOURS).msecs()
            val bgContext = singleBgContext()
            EventData.GraphData(ArrayList(bucketedData.filter { it.timestamp >= graphStart }.map { buildSingleBg(it, bgContext) }))
        }

    // The entries used to carry the send time. Nothing on the watch reads it, but the watch only
    // stores the list and refreshes its tile when the serialized list differs from the stored one,
    // and with a fresh time in every entry it always differed. The field stays, at 0, for the format.
    private fun AutomationEvent.toWear(): EventData.UserAction.UserActionEntry =
        EventData.UserAction.UserActionEntry(
            timeStamp = 0L,
            id = hashCode(),
            title = title
        )

    fun sendUserActions() {
        val events = automation.userEvents()
        rxBus.send(
            EventMobileToWear(
                EventData.UserAction(
                    ArrayList(events.filter { it.isEnabled && it.canRun() }.map { it.toWear() })
                )
            )
        )
    }

    private fun sendTreatments() {
        val now = System.currentTimeMillis()
        val startTimeWindow = now - (60000 * 60 * 5.5).toLong()
        val basals = arrayListOf<EventData.TreatmentData.Basal>()
        val temps = arrayListOf<EventData.TreatmentData.TempBasal>()
        val boluses = arrayListOf<EventData.TreatmentData.Treatment>()
        val predictions = arrayListOf<EventData.SingleBg>()
        val profile = profileFunction.getProfile() ?: return
        // One read for the whole window; the 5 min walk below asked the database at every step (66 of
        // them), on every resend.
        val tempBasals = processedTbrEbData.getTempBasalsIncludingConvertedExtended(startTimeWindow, now)
        var beginBasalSegmentTime = startTimeWindow
        var runningTime = startTimeWindow
        var beginBasalValue = profile.getBasal(beginBasalSegmentTime)
        var endBasalValue = beginBasalValue
        var tb1 = tempBasals.at(runningTime)
        var tb2: TB?
        var tbBefore = beginBasalValue
        var tbAmount = beginBasalValue
        var tbStart = runningTime
        if (tb1 != null) {
            val profileTB = profileFunction.getProfile(runningTime)
            if (profileTB != null) {
                tbAmount = tb1.convertedToAbsolute(runningTime, profileTB)
                tbStart = runningTime
            }
        }
        while (runningTime < now) {
            val profileTB = profileFunction.getProfile(runningTime) ?: return
            //basal rate
            endBasalValue = profile.getBasal(runningTime)
            if (endBasalValue != beginBasalValue) {
                //push the segment we recently left
                basals.add(EventData.TreatmentData.Basal(beginBasalSegmentTime, runningTime, beginBasalValue))

                //begin new Basal segment
                beginBasalSegmentTime = runningTime
                beginBasalValue = endBasalValue
            }

            //temps
            tb2 = tempBasals.at(runningTime)
            when {
                tb1 == null && tb2 == null -> {
                    //no temp stays no temp
                }

                tb1 != null && tb2 == null -> {
                    //temp is over -> push it
                    temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, runningTime, endBasalValue, tbAmount))
                    tb1 = null
                }

                tb1 == null && tb2 != null -> {
                    //temp begins
                    tb1 = tb2
                    tbStart = runningTime
                    tbBefore = endBasalValue
                    tbAmount = tb1.convertedToAbsolute(runningTime, profileTB)
                }

                tb1 != null && tb2 != null -> {
                    val currentAmount = tb2.convertedToAbsolute(runningTime, profileTB)
                    if (currentAmount != tbAmount) {
                        temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, runningTime, currentAmount, tbAmount))
                        tbStart = runningTime
                        tbBefore = tbAmount
                        tbAmount = currentAmount
                        tb1 = tb2
                    }
                }
            }
            runningTime += (5 * 60 * 1000L)
        }
        if (beginBasalSegmentTime != runningTime) {
            //push the remaining segment
            basals.add(EventData.TreatmentData.Basal(beginBasalSegmentTime, runningTime, beginBasalValue))
        }
        if (tb1 != null) {
            tb2 = tempBasals.at(now) //use "now" to express current situation
            if (tb2 == null) {
                //express the cancelled temp by painting it down one minute early
                temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, now - 60 * 1000, endBasalValue, tbAmount))
            } else {
                //express currently running temp by painting it a bit into the future
                val profileNow = profileFunction.getProfile(now)
                val currentAmount = tb2.convertedToAbsolute(now, profileNow!!)
                if (currentAmount != tbAmount) {
                    temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, now, tbAmount, tbAmount))
                    temps.add(EventData.TreatmentData.TempBasal(now, tbAmount, runningTime + 5 * 60 * 1000, currentAmount, currentAmount))
                } else {
                    temps.add(EventData.TreatmentData.TempBasal(tbStart, tbBefore, runningTime + 5 * 60 * 1000, tbAmount, tbAmount))
                }
            }
        } else {
            tb2 = tempBasals.at(now) //use "now" to express current situation
            if (tb2 != null) {
                //onset at the end
                val profileTB = profileFunction.getProfile(runningTime)
                val currentAmount = tb2.convertedToAbsolute(runningTime, profileTB!!)
                temps.add(EventData.TreatmentData.TempBasal(now - 60 * 1000, endBasalValue, runningTime + 5 * 60 * 1000, currentAmount, currentAmount))
            }
        }
        persistenceLayer.getBolusesFromTimeIncludingInvalid(startTimeWindow, true).blockingGet()
            .stream()
            .filter { (_, _, _, _, _, _, _, _, _, type) -> type !== BS.Type.PRIMING }
            .forEach { (_, _, _, isValid, _, _, timestamp, _, amount, type) -> boluses.add(EventData.TreatmentData.Treatment(timestamp, amount, 0.0, type === BS.Type.SMB, isValid)) }
        persistenceLayer.getCarbsFromTimeExpanded(startTimeWindow, true)
            .forEach { (_, _, _, isValid, _, _, timestamp, _, _, amount) -> boluses.add(EventData.TreatmentData.Treatment(timestamp, 0.0, amount, false, isValid)) }

        val apsResult = if (config.APS) {
            val lastRun = loop.lastRun
            if (lastRun?.request?.hasPredictions == true) {
                lastRun.constraintsProcessed
            } else null
        } else {
            processedDeviceStatusData.getAPSResult()
        }

        apsResult
            ?.predictionsAsGv
            ?.filter { it.value > 39 }
            ?.forEach { bg ->
                predictions.add(
                    EventData.SingleBg(
                        dataset = 0,
                        timeStamp = bg.timestamp,
                        glucoseUnits = GlucoseUnit.MGDL.asText,
                        sgv = bg.value,
                        high = 0.0,
                        low = 0.0,
                        color = predictionColor(context, bg)
                    )
                )
            }
        rxBus.send(EventMobileToWear(EventData.TreatmentData(temps, basals, boluses, predictions)))
    }

    private fun predictionColor(context: Context?, data: GV): Int {
        return when (data.sourceSensor) {
            SourceSensor.IOB_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.iobColor)
            SourceSensor.COB_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
            SourceSensor.A_COB_PREDICTION -> -0x7f000001 and rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
            SourceSensor.UAM_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.uamColor)
            SourceSensor.ZT_PREDICTION    -> rh.gac(context, app.aaps.core.ui.R.attr.ztColor)
            else                          -> rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor)
        }
    }

    private fun sendStatus(caller: String) {
        val profile = profileFunction.getProfile()
        var status = rh.gs(app.aaps.core.ui.R.string.noprofile)
        var iobSum = ""
        var iobDetail = ""
        var cobString = ""
        var currentBasal = ""
        var bgiString = ""
        if (config.appInitialized && profile != null) {
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            iobSum = decimalFormatter.to2Decimal(bolusIob.iob + basalIob.basaliob)
            iobDetail = "(${decimalFormatter.to2Decimal(bolusIob.iob)}|${decimalFormatter.to2Decimal(basalIob.basaliob)})"
            cobString = iobCobCalculator.getCobInfo("WatcherUpdaterService").generateCOBString(decimalFormatter)
            currentBasal =
                processedTbrEbData.getTempBasalIncludingConvertedExtended(System.currentTimeMillis())?.toStringShort(rh) ?: rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, profile.getBasal())

            //bgi
            if (glucoseStatusProvider.glucoseStatusData != null) {
                val bgi = -(bolusIob.activity + basalIob.activity) * 5 * profileUtil.fromMgdlToUnits(profile.getIsfMgdl("DataHandlerMobile $caller"))
                bgiString = "" + (if (bgi >= 0) "+" else "") + decimalFormatter.to1Decimal(bgi)
            }
            status = generateStatusString(profile)
        }

        //batteries
        val phoneBattery = receiverStatusStore.batteryLevel
        val rigBattery = processedDeviceStatusData.uploaderStatus.trim { it <= ' ' }
        //OpenAPS status
        val openApsStatus =
            if (config.APS) loop.lastRun?.let { if (it.lastTBREnact != 0L) it.lastTBREnact else -1 } ?: -1
            else processedDeviceStatusData.openApsTimestamp
        val lastLoopRunAt = wearLastLoopRunAt(config.APS, loop.lastRun, if (config.APS) 0L else processedDeviceStatusData.openApsTimestamp)
        // Patient name for followers
        val patientName = preferences.get(StringKey.GeneralPatientName)
        //temptarget
        val units = profileFunction.getUnits()
        var tempTargetLevel = 0
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            tempTargetLevel = 2     // Yellow
            profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units)
        } ?: profileFunction.getProfile()?.let { profile ->
            // If the target is not the same as set in the profile then oref has overridden it
            val targetUsed =
                if (config.APS) loop.lastRun?.constraintsProcessed?.targetBG ?: 0.0
                else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.targetBG ?: 0.0
                else 0.0

            if (targetUsed != 0.0 && abs(profile.getTargetMgdl() - targetUsed) > 0.01) {
                tempTargetLevel = 1     // Green
                profileUtil.toTargetRangeString(targetUsed, targetUsed, GlucoseUnit.MGDL, units)
            } else {
                profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units)
            }
        } ?: ""
        // Reservoir Level
        val pump = activePlugin.activePump
        val maxReading = pump.pumpDescription.maxResorvoirReading.toDouble()
        val reservoir = pump.reservoirLevel.let { if (pump.pumpDescription.isPatchPump && it > maxReading) maxReading else it }
        val reservoirString = if (reservoir > 0) decimalFormatter.to0Decimal(reservoir, rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname)) else ""
        val resUrgent = preferences.get(IntKey.OverviewResCritical)
        val resWarn = preferences.get(IntKey.OverviewResWarning)
        val reservoirLevel = when {
            reservoir <= resUrgent -> 2
            reservoir <= resWarn   -> 1
            else                   -> 0
        }

        rxBus.send(
            EventMobileToWear(
                EventData.Status(
                    dataset = 0,
                    externalStatus = status,
                    iobSum = iobSum,
                    iobDetail = iobDetail,
                    cob = cobString,
                    currentBasal = currentBasal,
                    battery = phoneBattery.toString(),
                    rigBattery = rigBattery,
                    openApsStatus = openApsStatus,
                    bgi = bgiString,
                    batteryLevel = if (phoneBattery >= 30) 1 else 0,
                    patientName = patientName,
                    tempTarget = tempTarget,
                    tempTargetLevel = tempTargetLevel,
                    reservoirString = reservoirString,
                    reservoir = reservoir,
                    reservoirLevel = reservoirLevel,
                    lastLoopRunAt = lastLoopRunAt
                )
            )
        )
    }

    private fun deltaString(deltaMGDL: Double, deltaMMOL: Double, units: GlucoseUnit): String {
        var deltaString = if (deltaMGDL >= 0) "+" else "-"
        deltaString += if (units == GlucoseUnit.MGDL) {
            decimalFormatter.to0Decimal(abs(deltaMGDL))
        } else {
            decimalFormatter.to1Decimal(abs(deltaMMOL))
        }
        return deltaString
    }

    private fun deltaStringDetailed(deltaMGDL: Double, deltaMMOL: Double, units: GlucoseUnit): String {
        var deltaStringDetailed = if (deltaMGDL >= 0) "+" else "-"
        deltaStringDetailed += if (units == GlucoseUnit.MGDL) {
            decimalFormatter.to1Decimal(abs(deltaMGDL))
        } else {
            decimalFormatter.to2Decimal(abs(deltaMMOL))
        }
        return deltaStringDetailed
    }

    /** What [buildSingleBg] needs that is the same for every point of a message. */
    private class SingleBgContext(
        val glucoseStatus: GlucoseStatus?,
        val units: GlucoseUnit,
        val lowLine: Double,
        val highLine: Double,
        val slopeArrow: String
    )

    // Read once per message, not once per graph point: getGlucoseStatusData copies the bucketed table
    // and fits a polynomial on every call.
    private fun singleBgContext(): SingleBgContext {
        val units = profileFunction.getUnits()
        return SingleBgContext(
            glucoseStatus = glucoseStatusProvider.getGlucoseStatusData(true),
            units = units,
            lowLine = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewLowMark), units),
            highLine = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewHighMark), units),
            slopeArrow = (trendCalculator.getTrendArrow(iobCobCalculator.ads) ?: TrendArrow.NONE).symbol
        )
    }

    private fun getSingleBG(glucoseValue: InMemoryGlucoseValue): EventData.SingleBg = buildSingleBg(glucoseValue, singleBgContext())

    private fun buildSingleBg(glucoseValue: InMemoryGlucoseValue, bgContext: SingleBgContext): EventData.SingleBg {
        val glucoseStatus = bgContext.glucoseStatus
        val units = bgContext.units
        val lowLine = bgContext.lowLine
        val highLine = bgContext.highLine

        return EventData.SingleBg(
            dataset = 0,
            timeStamp = glucoseValue.timestamp,
            sgvString = profileUtil.stringInCurrentUnitsDetect(glucoseValue.recalculated),
            glucoseUnits = units.asText,
            slopeArrow = bgContext.slopeArrow,
            delta = glucoseStatus?.let { deltaString(it.delta, it.delta * Constants.MGDL_TO_MMOLL, units) } ?: "--",
            deltaDetailed = glucoseStatus?.let { deltaStringDetailed(it.delta, it.delta * Constants.MGDL_TO_MMOLL, units) } ?: "--",
            avgDelta = glucoseStatus?.let { deltaString(it.shortAvgDelta, it.shortAvgDelta * Constants.MGDL_TO_MMOLL, units) } ?: "--",
            avgDeltaDetailed = glucoseStatus?.let { deltaStringDetailed(it.shortAvgDelta, it.shortAvgDelta * Constants.MGDL_TO_MMOLL, units) } ?: "--",
            sgvLevel = if (glucoseValue.recalculated > highLine) 1L else if (glucoseValue.recalculated < lowLine) -1L else 0L,
            sgv = glucoseValue.recalculated,
            high = highLine,
            low = lowLine,
            color = 0,
            deltaMgdl = glucoseStatus?.delta,
            avgDeltaMgdl = glucoseStatus?.shortAvgDelta
        )
    }

    //Check for Temp-Target:
    private
    val targetsStatus: String
        get() {
            var ret = rh.gs(app.aaps.core.ui.R.string.loopstatus_targets) + "\n"
            if (!config.APS) {
                return rh.gs(R.string.target_only_aps_mode)
            }
            val profile = profileFunction.getProfile() ?: return rh.gs(R.string.no_profile)
            //Check for Temp-Target:
            val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
            if (tempTarget != null) {
                val target = profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.lowTarget, GlucoseUnit.MGDL)
                ret += rh.gs(R.string.temp_target) + ": " + target
                ret += "\n" + rh.gs(R.string.until) + ": " + dateUtil.timeString(tempTarget.end)
                ret += "\n\n"
            }
            ret += rh.gs(R.string.default_range) + ": "
            ret += profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL)
            ret += " " + rh.gs(R.string.target) + ": " + profileUtil.fromMgdlToStringInUnits(profile.getTargetMgdl())
            return ret
        }

    private
    val oAPSResultStatus: String
        get() {
            var ret = rh.gs(app.aaps.core.ui.R.string.loopstatus_OAPS_result) + "\n"
            if (!config.APS)
                return rh.gs(R.string.aps_only)
            val usedAPS = activePlugin.activeAPS
            val result = usedAPS.lastAPSResult ?: return rh.gs(R.string.last_aps_result_na)
            ret += if (!result.isChangeRequested) {
                rh.gs(app.aaps.core.ui.R.string.nochangerequested) + "\n"
            } else if (result.rate == 0.0 && result.duration == 0) {
                rh.gs(app.aaps.core.ui.R.string.cancel_temp) + "\n"
            } else {
                rh.gs(R.string.rate_duration, result.rate, result.rate / activePlugin.activePump.baseBasalRate * 100, result.duration) + "\n"
            }
            ret += "\n" + rh.gs(app.aaps.core.ui.R.string.reason) + ": " + result.reason
            return ret
        }

    // decide if enabled/disabled closed/open; what Plugin as APS?
    private
    val loopStatus: String
        get() {
            var ret = ""
            // decide if enabled/disabled closed/open; what Plugin as APS?
            when (loop.runningMode) {
                RM.Mode.CLOSED_LOOP     -> ret += rh.gs(R.string.loop_status_closed) + "\n"
                RM.Mode.OPEN_LOOP       -> ret += rh.gs(R.string.loop_status_open) + "\n"
                RM.Mode.CLOSED_LOOP_LGS -> ret += rh.gs(R.string.loop_status_lgs) + "\n"
                RM.Mode.DISABLED_LOOP   -> ret += rh.gs(R.string.loop_status_disabled) + "\n"

                else                    -> { /* do nothing */
                }
            }
            if (loop.runningMode.isLoopRunning()) {
                val aps = activePlugin.activeAPS
                ret += rh.gs(R.string.aps) + ": " + (aps as PluginBase).name
                val lastRun = loop.lastRun
                if (lastRun != null) {
                    ret += "\n" + rh.gs(R.string.last_run) + ": " + dateUtil.timeString(lastRun.lastAPSRun)
                    if (lastRun.lastTBREnact != 0L) ret += "\n" + rh.gs(R.string.last_enact) + ": " + dateUtil.timeString(lastRun.lastTBREnact)
                }
            }
            return ret
        }

    private fun isOldData(historyList: List<TDD>): Boolean {
        val startsYesterday = activePlugin.activePump.pumpDescription.supportsTDDs
        val df: DateFormat = SimpleDateFormat("dd.MM.", Locale.getDefault())
        return historyList.size < 3 || df.format(Date(historyList[0].timestamp)) != df.format(Date(System.currentTimeMillis() - if (startsYesterday) 1000 * 60 * 60 * 24 else 0))
    }

    private fun getTDDList(returnDummies: List<TDD>): MutableList<TDD> {
        var historyList = persistenceLayer.getLastTotalDailyDoses(10, false).toMutableList()
        //var historyList = databaseHelper.getTDDs().toMutableList()
        historyList = historyList.subList(0, min(10, historyList.size))
        // fill single gaps - only needed for Dana*R data
        val dummies: MutableList<TDD> = returnDummies.toMutableList()
        val df: DateFormat = SimpleDateFormat("dd.MM.", Locale.getDefault())
        for (i in 0 until historyList.size - 1) {
            val elem1 = historyList[i]
            val elem2 = historyList[i + 1]
            if (df.format(Date(elem1.timestamp)) != df.format(Date(elem2.timestamp + 25 * 60 * 60 * 1000))) {
                val dummy = TDD(timestamp = elem1.timestamp - T.hours(24).msecs(), bolusAmount = elem1.bolusAmount / 2, basalAmount = elem1.basalAmount / 2)
                dummies.add(dummy)
                elem1.basalAmount /= 2.0
                elem1.bolusAmount /= 2.0
            }
        }
        historyList.addAll(dummies)
        historyList.sortWith { lhs, rhs -> (rhs.timestamp - lhs.timestamp).toInt() }
        return historyList
    }

    private val TDD.total
        get() = if (totalAmount > 0) totalAmount else basalAmount + bolusAmount

    private fun generateTDDMessage(historyList: MutableList<TDD>, dummies: List<TDD>): String {
        val profile = profileFunction.getProfile() ?: return rh.gs(R.string.no_profile)
        if (historyList.isEmpty()) {
            return rh.gs(R.string.no_history)
        }
        val df: DateFormat = SimpleDateFormat("dd.MM.", Locale.getDefault())
        var message = ""
        val refTDD = profile.baseBasalSum() * 2
        if (df.format(Date(historyList[0].timestamp)) == df.format(Date())) {
            val tdd = historyList[0].total
            historyList.removeAt(0)

            message += rh.gs(R.string.today) + ": " + rh.gs(R.string.tdd_line, tdd, 100 * tdd / refTDD) + "\n"
            message += "\n"
        }
        var weighted03 = 0.0
        var weighted05 = 0.0
        var weighted07 = 0.0
        historyList.reverse()
        for ((i, record) in historyList.withIndex()) {
            val tdd = record.total
            if (i == 0) {
                weighted03 = tdd
                weighted05 = tdd
                weighted07 = tdd
            } else {
                weighted07 = weighted07 * 0.3 + tdd * 0.7
                weighted05 = weighted05 * 0.5 + tdd * 0.5
                weighted03 = weighted03 * 0.7 + tdd * 0.3
            }
        }
        message += rh.gs(R.string.weighted) + ":\n"
        message += "0.3: " + rh.gs(R.string.tdd_line, weighted03, 100 * weighted03 / refTDD) + "\n"
        message += "0.5: " + rh.gs(R.string.tdd_line, weighted05, 100 * weighted05 / refTDD) + "\n"
        message += "0.7: " + rh.gs(R.string.tdd_line, weighted07, 100 * weighted07 / refTDD) + "\n"
        message += "\n"
        historyList.reverse()
        // add TDDs:
        for (record in historyList) {
            val tdd = record.total
            message += df.format(Date(record.timestamp)) + " " + rh.gs(R.string.tdd_line, tdd, 100 * tdd / refTDD)
            message += (if (dummies.contains(record)) "x" else "") + "\n"
        }
        return message
    }

    private fun generateStatusString(profile: Profile?): String {
        var status = ""
        profile ?: return rh.gs(app.aaps.core.ui.R.string.noprofile)
        if (!loop.runningMode.isLoopRunning()) status += rh.gs(R.string.disabled_loop) + "\n"
        return status
    }

    private fun doTempTarget(command: EventData.ActionTempTargetConfirmed) {
        if (command.duration != 0)
            disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                temporaryTarget = TT(
                    timestamp = System.currentTimeMillis(),
                    duration = TimeUnit.MINUTES.toMillis(command.duration.toLong()),
                    reason = TT.Reason.WEAR,
                    lowTarget = profileUtil.convertToMgdl(command.low, profileFunction.getUnits()),
                    highTarget = profileUtil.convertToMgdl(command.high, profileFunction.getUnits())
                ),
                action = Action.TT,
                source = Sources.Wear,
                note = null,
                listValues = listOfNotNull(
                    ValueWithUnit.TETTReason(TT.Reason.WEAR),
                    ValueWithUnit.fromGlucoseUnit(command.low, profileFunction.getUnits()),
                    ValueWithUnit.fromGlucoseUnit(command.high, profileFunction.getUnits()).takeIf { command.low != command.high },
                    ValueWithUnit.Minute(command.duration)
                )
            ).subscribe()
        else
            disposable += persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                timestamp = dateUtil.now(),
                action = Action.CANCEL_TT,
                source = Sources.Wear,
                note = null,
                listValues = listOf(ValueWithUnit.TETTReason(TT.Reason.WEAR))
            )
                .subscribe()
    }

    /**
     * Delivers a confirmed (quick) wizard bolus. Everything delivered comes from [wizard] and
     * [quickWizardEntry], the snapshot carried by the matched [PendingWearConfirm].
     */
    private fun doWizardBolus(wizard: BolusWizard, quickWizardEntry: QuickWizardEntry?) {
        var carbTime: Long? = null
        var carbTimeOffset: Long = 0
        var useAlarm = false
        val currentTime = Calendar.getInstance().timeInMillis
        var eventTime = currentTime
        var carbs2 = 0
        var duration = 0
        var notes: String? = null

        quickWizardEntry?.let { entry ->
            carbTimeOffset = entry.carbTime().toLong()
            carbTime = currentTime + (carbTimeOffset * 60000)
            useAlarm = entry.useAlarm() == QuickWizardEntry.YES
            notes = entry.buttonText()

            if (entry.useEcarbs() == QuickWizardEntry.YES) {
                val timeOffset = entry.time()
                eventTime += (timeOffset * 60000)
                carbs2 = entry.carbs2()
                duration = entry.duration()
            }
        }
        doBolus(wizard.calculatedTotalInsulin, wizard.carbs, carbTime, 0, wizard.createBolusCalculatorResult(), notes, superBolusSource = quickWizardEntry)
        doECarbs(carbs2, eventTime, duration, notes)

        if (useAlarm && wizard.carbs > 0 && carbTimeOffset > 0) {
            automation.scheduleTimeToEatReminder(T.mins(carbTimeOffset).secs().toInt())
        }
    }

    /**
     * @param superBolusSource the confirmed quick wizard entry; only when it is given and asks for a
     * superbolus is the superbolus running mode started. Never derived from shared state.
     */
    private fun doBolus(
        amount: Double, carbs: Int, carbsTime: Long?, carbsDuration: Int, bolusCalculatorResult: BCR?, notes: String? = null,
        superBolusSource: QuickWizardEntry? = null
    ) {
        val detailedBolusInfo = DetailedBolusInfo()
        detailedBolusInfo.insulin = amount
        detailedBolusInfo.carbs = carbs.toDouble()
        detailedBolusInfo.bolusType = BS.Type.NORMAL
        detailedBolusInfo.carbsTimestamp = carbsTime
        detailedBolusInfo.carbsDuration = T.hours(carbsDuration.toLong()).msecs()
        detailedBolusInfo.notes = notes
        if (detailedBolusInfo.insulin > 0 || detailedBolusInfo.carbs != 0.0) {

            val action = when {
                amount == 0.0     -> Action.CARBS
                carbs == 0        -> Action.BOLUS
                carbsDuration > 0 -> Action.EXTENDED_CARBS
                else              -> Action.TREATMENT
            }
            uel.log(
                action = action, source = Sources.Wear,
                listValues = listOfNotNull(
                    ValueWithUnit.Insulin(amount).takeIf { amount != 0.0 },
                    ValueWithUnit.Gram(carbs).takeIf { carbs != 0 },
                    ValueWithUnit.Hour(carbsDuration).takeIf { carbsDuration != 0 }
                )
            )
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    if (!result.success)
                        sendError(rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror) + "\n" + result.comment)
                }
            })
            bolusCalculatorResult?.let { persistenceLayer.insertOrUpdateBolusCalculatorResult(it).blockingGet() }
            superBolusSource?.let { entry ->
                if (entry.useSuperBolus() == QuickWizardEntry.YES) {
                    val profile = profileFunction.getProfile() ?: return
                    loop.handleRunningModeChange(
                        newRM = RM.Mode.SUPER_BOLUS,
                        action = Action.SUPERBOLUS_TBR,
                        source = Sources.Wear,
                        durationInMinutes = 2 * 60,
                        profile = profile
                    )
                }
            }
        }
    }

    private fun doFillBolus(amount: Double) {
        val detailedBolusInfo = DetailedBolusInfo()
        detailedBolusInfo.insulin = amount
        detailedBolusInfo.bolusType = BS.Type.PRIMING
        uel.log(
            action = Action.PRIME_BOLUS, source = Sources.Wear,
            listValues = listOfNotNull(ValueWithUnit.Insulin(amount).takeIf { amount != 0.0 })
        )
        commandQueue.bolus(detailedBolusInfo, object : Callback() {
            override fun run() {
                if (!result.success) {
                    sendError(rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror) + "\n" + result.comment)
                }
            }
        })
    }

    private fun doECarbs(carbs: Int, carbsTime: Long, duration: Int, notes: String? = null) {
        uel.log(
            action = if (duration == 0) Action.CARBS else Action.EXTENDED_CARBS,
            source = Sources.Wear,
            listValues = listOfNotNull(
                ValueWithUnit.Timestamp(carbsTime),
                ValueWithUnit.Gram(carbs),
                ValueWithUnit.Hour(duration).takeIf { duration != 0 }
            )
        )
        doBolus(0.0, carbs, carbsTime, duration, null, notes)
    }

    private fun doProfileSwitch(command: EventData.ActionProfileSwitchConfirmed) {
        //check for validity
        if (command.percentage < Constants.CPP_MIN_PERCENTAGE || command.percentage > Constants.CPP_MAX_PERCENTAGE)
            return
        if (command.timeShift < Constants.CPP_MIN_TIMESHIFT || command.timeShift > Constants.CPP_MAX_TIMESHIFT)
            return
        if (command.duration < 0 || command.duration > Constants.MAX_PROFILE_SWITCH_DURATION)
            return
        profileFunction.getProfile() ?: return
        //send profile to pump
        profileFunction.createProfileSwitch(
            durationInMinutes = command.duration,
            percentage = command.percentage,
            timeShiftInHours = command.timeShift,
            action = Action.PROFILE_SWITCH,
            source = Sources.Wear,
            listValues = listOfNotNull(
                ValueWithUnit.Percent(command.percentage),
                ValueWithUnit.Hour(command.timeShift).takeIf { command.timeShift != 0 },
                ValueWithUnit.Minute(command.duration)
            )
        )
    }

    /**
     * Phone-side gate for watch-initiated treatments: the watch hides its actions when "Controls from
     * Watch" is off, but the phone must not rely on that. Sends an error to the watch when refusing.
     */
    private fun wearControlEnabled(): Boolean {
        if (preferences.get(BooleanKey.WearControl)) return true
        refuseWearControlDisabled()
        return false
    }

    private fun refuseWearControlDisabled() {
        aapsLogger.warn(LTag.WEAR, "Watch treatment refused: Controls from Watch is disabled on the phone")
        sendError(rh.gs(R.string.wear_control_disabled_on_phone))
    }

    /** Stores a new pending confirmation, replacing any older one, and returns its fresh [PendingWearConfirm.requestId]. */
    private fun offerPendingConfirm(
        kind: PendingWearConfirm.Kind, insulin: Double, carbs: Int,
        wizard: BolusWizard? = null, quickWizardEntry: QuickWizardEntry? = null, carbsTime: Long = 0L, duration: Int = 0
    ): Long {
        var requestId: Long
        do requestId = nextWearRequestId.incrementAndGet() while (requestId == 0L)
        val pending = PendingWearConfirm(
            kind = kind, requestId = requestId, insulin = insulin, carbs = carbs, createdAt = monotonicClock(),
            wizardTimeStamp = wizard?.timeStamp ?: 0L, wizard = wizard, quickWizardEntry = quickWizardEntry, carbsTime = carbsTime, duration = duration
        )
        aapsLogger.debug(LTag.WEAR, "Watch confirm offered at ${dateUtil.now()}: $pending")
        synchronized(wearConfirmLock) { pendingWearConfirm = pending }
        return requestId
    }

    private fun clearPendingConfirm() {
        synchronized(wearConfirmLock) { pendingWearConfirm = null }
    }

    private sealed class ConfirmOutcome {
        class Accepted(val confirm: PendingWearConfirm) : ConfirmOutcome()
        object ControlDisabled : ConfirmOutcome()
        class Duplicate(val of: ConsumedWearConfirm, val ago: Long) : ConfirmOutcome()
        class Refused(val pending: PendingWearConfirm?, val age: Long?) : ConfirmOutcome()
    }

    /**
     * Takes the pending confirmation out of its slot (always, so it can be used at most once) and
     * returns it only when Controls from Watch is still on, it is of [kind], [matches] holds and it is
     * not older than [WEAR_CONFIRM_TIMEOUT_MS] (monotonic time). Returns null otherwise.
     *
     * A refusal sends an error to the watch, except for a duplicate of the confirm accepted just before
     * (same kind, [matches] holds for it, within [WEAR_DUPLICATE_CONFIRM_WINDOW_MS], nothing pending):
     * that one is only logged, because the treatment was already delivered and an error screen could
     * make the user request it again.
     *
     * Taking the slot, accepting and recognising a duplicate happen under one lock, so of two confirms
     * arriving at the same time exactly one is accepted and the other is always seen as its duplicate.
     */
    private fun acceptPendingConfirm(kind: PendingWearConfirm.Kind, matches: (PendingWearConfirm) -> Boolean): PendingWearConfirm? {
        val outcome = synchronized(wearConfirmLock) {
            val pending = pendingWearConfirm
            pendingWearConfirm = null
            if (!preferences.get(BooleanKey.WearControl)) return@synchronized ConfirmOutcome.ControlDisabled
            val now = monotonicClock()
            val age = pending?.let { now - it.createdAt }
            if (pending != null && pending.kind == kind && matches(pending) && age != null && age in 0..WEAR_CONFIRM_TIMEOUT_MS) {
                lastConsumedConfirm = ConsumedWearConfirm(pending, now)
                return@synchronized ConfirmOutcome.Accepted(pending)
            }
            val consumed = lastConsumedConfirm
            if (pending == null && consumed != null && consumed.confirm.kind == kind && now - consumed.consumedAt in 0..WEAR_DUPLICATE_CONFIRM_WINDOW_MS && matches(consumed.confirm))
                ConfirmOutcome.Duplicate(consumed, now - consumed.consumedAt)
            else
                ConfirmOutcome.Refused(pending, age)
        }
        when (outcome) {
            is ConfirmOutcome.Accepted        -> return outcome.confirm
            is ConfirmOutcome.ControlDisabled -> refuseWearControlDisabled()
            is ConfirmOutcome.Duplicate       ->
                aapsLogger.warn(LTag.WEAR, "Watch confirm $kind ignored: duplicate of the confirm accepted ${outcome.ago} ms ago (${outcome.of.confirm})")
            is ConfirmOutcome.Refused         -> {
                aapsLogger.warn(LTag.WEAR, "Watch confirm $kind refused at ${dateUtil.now()}: pending=${outcome.pending} age=${outcome.age}")
                sendError(rh.gs(R.string.wear_confirm_not_matching))
            }
        }
        return null
    }

    @Synchronized private fun sendError(errorMessage: String) {
        rxBus.send(EventMobileToWear(EventData.ConfirmAction(rh.gs(app.aaps.core.ui.R.string.error), errorMessage, returnCommand = EventData.Error(dateUtil.now())))) // ignore return path
    }

    /** Stores heart rate events coming from the Wear device. */
    private fun handleHeartRate(actionHeartRate: EventData.ActionHeartRate) {
        aapsLogger.debug(LTag.WEAR, "Heart rate received $actionHeartRate from ${actionHeartRate.sourceNodeId}")
        val hr = HR(
            duration = actionHeartRate.duration,
            timestamp = actionHeartRate.timestamp,
            beatsPerMinute = actionHeartRate.beatsPerMinute,
            device = actionHeartRate.device
        )
        disposable += persistenceLayer.insertOrUpdateHeartRate(hr).subscribe()
    }

    private fun handleStepsCount(actionStepsRate: EventData.ActionStepsRate) {
        aapsLogger.debug(LTag.WEAR, "Steps count received $actionStepsRate from ${actionStepsRate.sourceNodeId}")
        val stepsCount = SC(
            duration = actionStepsRate.duration,
            timestamp = actionStepsRate.timestamp,
            steps5min = actionStepsRate.steps5min,
            steps10min = actionStepsRate.steps10min,
            steps15min = actionStepsRate.steps15min,
            steps30min = actionStepsRate.steps30min,
            steps60min = actionStepsRate.steps60min,
            steps180min = actionStepsRate.steps180min,
            device = actionStepsRate.device
        )
        if (!preferences.get(BooleanKey.ActivityMonitorShowStepsFromSmartphone)) {
            disposable += persistenceLayer.insertOrUpdateStepsCount(stepsCount).subscribe()
        }
    }

    private fun handleGetCustomWatchface(command: EventData.ActionGetCustomWatchface) {
        val customWatchface = command.customWatchface
        aapsLogger.debug(LTag.WEAR, "Custom Watchface received from ${command.sourceNodeId}")
        val cwfData = customWatchface.customWatchfaceData
        rxBus.send(EventWearUpdateGui(cwfData, command.exportFile))
        val watchfaceName = preferences.get(StringNonKey.WearCwfWatchfaceName)
        val authorVersion = preferences.get(StringNonKey.WearCwfAuthorVersion)
        if (cwfData.metadata[CwfMetadataKey.CWF_NAME] != watchfaceName || cwfData.metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] != authorVersion) {
            preferences.put(StringNonKey.WearCwfWatchfaceName, cwfData.metadata[CwfMetadataKey.CWF_NAME] ?: "")
            preferences.put(StringNonKey.WearCwfAuthorVersion, cwfData.metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] ?: "")
            preferences.put(StringNonKey.WearCwfFileName, cwfData.metadata[CwfMetadataKey.CWF_FILENAME] ?: "")
        }

        if (command.exportFile)
            importExportPrefs.exportCustomWatchface(cwfData, command.withDate)
    }

}
