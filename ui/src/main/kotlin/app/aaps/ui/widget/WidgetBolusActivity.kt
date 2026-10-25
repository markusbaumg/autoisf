package app.aaps.ui.widget

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.StringRes
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.widget.WidgetLogic.Block
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Optional
import javax.inject.Inject
import kotlin.math.abs

/**
 * Entry point of the widget's quick-bolus pills. Not exported, no intent filter: only the widget's PendingIntent starts it.
 * The intent carries the widget id and the slot (1 | 2), never a dose. The amount comes from that widget's settings.
 * Slot [SLOT_EXPLAIN] (the lock text and the disabled calculator pill) only explains the current lock: no protection,
 * no confirmation, never a bolus.
 *
 * Same path as InsulinDialog (non record-only branch): constraints, ProtectionCheck BOLUS, OKDialog.showConfirmation,
 * uel.log + commandQueue.bolus. Locks (low / stale BG, pump or loop not ready, bolus running, recent bolus) are checked
 * before the confirmation is shown and again on fresh data when OK is pressed.
 */
class WidgetBolusActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var config: Config
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var loop: Loop
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var automation: Automation
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var widgetWizard: WidgetWizard

    companion object {

        const val ACTION_WIDGET_BOLUS = "app.aaps.ui.widget.BOLUS"
        const val EXTRA_WIDGET_ID = "widget_id"
        const val EXTRA_SLOT = "slot"

        /** Explanation only (lock text, disabled calculator pill): shows why the bolus is locked, never offers OK */
        const val SLOT_EXPLAIN = 0

        /** From tap to confirmation (protection included) */
        private const val REQUEST_CAP_MS = 120 * 1000L

        private fun dataUri(widgetId: Int, slot: Int): Uri = Uri.parse("aaps-widget://bolus/$widgetId/$slot")

        fun pendingIntent(context: Context, widgetId: Int, slot: Int): PendingIntent {
            val intent = Intent(context, WidgetBolusActivity::class.java)
                .setAction(ACTION_WIDGET_BOLUS)
                .setData(dataUri(widgetId, slot))
                .putExtra(EXTRA_WIDGET_ID, widgetId)
                .putExtra(EXTRA_SLOT, slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            return PendingIntent.getActivity(context, 0x5B00 + widgetId * 4 + slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        @StringRes fun blockText(block: Block): Int = when (block) {
            Block.NOT_SUPPORTED       -> R.string.widget_bolus_block_not_supported
            Block.NOT_INITIALIZED     -> R.string.widget_bolus_block_not_initialized
            Block.SCREEN_LOCKED       -> R.string.widget_bolus_block_screen_locked
            Block.PUMP_NOT_READY      -> R.string.widget_bolus_block_pump_not_ready
            Block.SUSPENDED           -> R.string.widget_bolus_block_suspended
            Block.BOLUS_RUNNING       -> R.string.widget_bolus_block_bolus_running
            Block.NO_BG               -> R.string.widget_bolus_block_no_bg
            Block.STALE               -> R.string.widget_bolus_block_stale
            Block.LOW                 -> R.string.widget_bolus_block_low
            Block.RECENT_BOLUS        -> R.string.widget_bolus_block_recent_bolus
            Block.INVALID_AMOUNT      -> R.string.widget_bolus_block_invalid_amount
            Block.CONSTRAINED_TO_ZERO -> R.string.widget_bolus_block_constrained
        }
    }

    private enum class Phase { NONE, PROTECTION, CONFIRM, DELIVERING, DONE }

    private data class Snapshot(
        val input: WidgetLogic.BolusInput,
        val amountAfterConstraints: Double,
        val newestBolus: Pair<Long, Long>?,
        val iob: Double
    )

    private val disposable = CompositeDisposable()
    private val handler = Handler(Looper.getMainLooper())
    private var phase = Phase.NONE
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var slot = 0
    private var requestStartedAt = 0L
    private var shownAt = 0L
    private var shown: Snapshot? = null
    private var confirmLostFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // never restore a confirmation (rotation, process death)
        if (savedInstanceState != null) return done()
        widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        slot = intent.getIntExtra(EXTRA_SLOT, 0)
        val ids = AppWidgetManager.getInstance(this)?.getAppWidgetIds(ComponentName(this, Widget::class.java)) ?: IntArray(0)
        if (intent.action != ACTION_WIDGET_BOLUS || slot !in SLOT_EXPLAIN..2 || widgetId !in ids || intent.data != dataUri(widgetId, slot)) {
            aapsLogger.warn(LTag.WIDGET, "Widget bolus: invalid request")
            return done()
        }
        if (!config.APS) return done()
        if (slot == SLOT_EXPLAIN) return explain()
        if (!config.appInitialized) return refuse(Block.NOT_INITIALIZED)
        if (screenLocked()) return refuse(Block.SCREEN_LOCKED)
        requestStartedAt = SystemClock.elapsedRealtime()
        phase = Phase.PROTECTION
        val cancelFail = Runnable { done() }
        val bolusProtection = Runnable { protectionCheck.queryProtection(this, ProtectionCheck.Protection.BOLUS, { prepare() }, cancelFail, cancelFail) }
        // MainActivity is bypassed, so its APPLICATION protection is asked here
        if (protectionCheck.isLocked(ProtectionCheck.Protection.APPLICATION))
            protectionCheck.queryProtection(this, ProtectionCheck.Protection.APPLICATION, bolusProtection, cancelFail, cancelFail)
        else bolusProtection.run()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // the amount never changes under the finger
        aapsLogger.debug(LTag.WIDGET, "Widget bolus: second request ignored")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The confirmation was dismissed with back: close this invisible activity too
        if (phase != Phase.CONFIRM) return
        if (!hasFocus) confirmLostFocus = true
        else if (confirmLostFocus) handler.postDelayed({ if (phase == Phase.CONFIRM) done() }, 300)
    }

    override fun onStop() {
        super.onStop()
        // home, recents, screen off while the confirmation is open: cancel
        if (phase == Phase.CONFIRM && !isChangingConfigurations) done()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        disposable.clear()
    }

    private fun done() {
        phase = Phase.DONE
        if (!isFinishing) finish()
    }

    private fun refuse(block: Block) = refuse(rh.gs(blockText(block)))

    private fun refuse(reason: String) {
        ToastUtils.warnToast(this, rh.gs(R.string.widget_bolus_not_given, reason))
        done()
    }

    /** Lock text / disabled calculator pill: the lock on fresh data, explained; the widget renders again either way */
    private fun explain() {
        phase = Phase.DONE
        disposable += Single.fromCallable { Optional.ofNullable(widgetWizard.quickBolusBlock()) }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ block ->
                           Widget.updateWidget(this, "WidgetBolusExplain")
                           if (isFinishing) return@subscribe
                           if (block.isPresent)
                               OKDialog.show(this, rh.gs(R.string.widget_bolus_title), rh.gs(R.string.widget_bolus_locked) + ": " + rh.gs(blockText(block.get())), true) { done() }
                           else {
                               ToastUtils.infoToast(this, rh.gs(R.string.widget_bolus_unlocked))
                               done()
                           }
                       }, {
                           aapsLogger.error(LTag.WIDGET, "Widget bolus explain failed", it)
                           done()
                       })
    }

    private fun screenLocked(): Boolean =
        (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked || !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive

    private fun amount(): Double =
        WidgetLogic.centiUnitsToUnits(preferences.get(if (slot == 1) IntComposedKey.WidgetBolusAmount1 else IntComposedKey.WidgetBolusAmount2, widgetId))

    /** All read-only. Runs on io. */
    private fun readSnapshot(): Snapshot {
        val now = dateUtil.now()
        val amount = amount()
        val after = constraintChecker.applyBolusConstraints(ConstraintObject(amount, aapsLogger)).value()
        val newest = persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)?.takeIf { it.isValid }
        val bg = lastBgData.lastBg()
        val input = WidgetLogic.BolusInput(
            aps = config.APS,
            appInitialized = config.appInitialized,
            pumpInitialized = activePlugin.activePump.isInitialized(),
            suspended = loop.runningMode.isSuspended(),
            bolusInQueue = commandQueue.bolusInQueue(),
            bgMgdl = bg?.recalculated,
            bgTimestamp = bg?.timestamp,
            now = now,
            screenLocked = screenLocked(),
            lastBolusAt = newest?.timestamp,
            amount = amount,
            amountAfterConstraints = after
        )
        val iob = iobCobCalculator.calculateIobFromBolus().round().iob + iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
        return Snapshot(input, after, newest?.let { Pair(it.id, it.timestamp) }, iob)
    }

    private fun prepare() {
        if (phase != Phase.PROTECTION || isFinishing) return
        if (SystemClock.elapsedRealtime() - requestStartedAt > REQUEST_CAP_MS) return refuse(rh.gs(R.string.widget_bolus_expired))
        disposable += Single.fromCallable { readSnapshot() }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ showConfirmation(it) }, {
                aapsLogger.error(LTag.WIDGET, "Widget bolus snapshot failed", it)
                refuse(rh.gs(R.string.widget_bolus_changed))
            })
    }

    private fun showConfirmation(snap: Snapshot) {
        if (phase != Phase.PROTECTION || isFinishing) return
        WidgetLogic.bolusBlock(snap.input)?.let { block ->
            phase = Phase.DONE
            OKDialog.show(this, rh.gs(R.string.widget_bolus_title), rh.gs(R.string.widget_bolus_not_given, rh.gs(blockText(block))), true) { done() }
            return
        }
        val pumpDescription = activePlugin.activePump.pumpDescription
        val amount = snap.input.amount ?: return done()
        val after = snap.amountAfterConstraints
        val unitU = rh.gs(R.string.widget_unit_insulin)
        val lines = mutableListOf<String>()
        lines.add(
            rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + (decimalFormatter.toPumpSupportedBolus(after, pumpDescription.bolusStep) + " " + unitU)
                .formatColor(this, rh, app.aaps.core.ui.R.attr.bolusColor)
        )
        if (abs(after - amount) >= 0.001)
            lines.add(rh.gs(app.aaps.core.ui.R.string.bolus_constraint_applied_warn, amount, after).formatColor(this, rh, app.aaps.core.ui.R.attr.warningColor))
        lines.add(rh.gs(R.string.widget_bolus_requested, WidgetLogic.amountText(amount)))
        lines.add(rh.gs(R.string.widget_bolus_iob_now, WidgetLogic.decimal(snap.iob, 2)))
        val bgMgdl = snap.input.bgMgdl
        val bgTimestamp = snap.input.bgTimestamp
        if (bgMgdl != null && bgTimestamp != null) {
            val age = WidgetLogic.ageMinutes(bgTimestamp, snap.input.now)
            val ageText = if (age < 1) rh.gs(R.string.widget_age_now) else rh.gs(R.string.widget_age_min, age.toInt())
            lines.add(rh.gs(R.string.widget_bolus_glucose, WidgetLogic.glucose(bgMgdl, profileFunction.getUnits() == GlucoseUnit.MMOL), ageText))
        }
        shown = snap
        shownAt = SystemClock.elapsedRealtime()
        confirmLostFocus = false
        phase = Phase.CONFIRM
        OKDialog.showConfirmation(this, rh.gs(R.string.widget_bolus_title), HtmlHelper.fromHtml(lines.joinToString("<br/>")), { onOk() }, { done() })
    }

    private fun onOk() {
        if (phase != Phase.CONFIRM) return
        phase = Phase.DELIVERING
        val shownSnap = shown ?: return done()
        disposable += Single.fromCallable { readSnapshot() }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ fresh ->
                           WidgetLogic.bolusBlock(fresh.input)?.let { return@subscribe refuse(it) }
                           when (WidgetLogic.acceptRefusal(shownAt, SystemClock.elapsedRealtime(), shownSnap.amountAfterConstraints, fresh.amountAfterConstraints, shownSnap.newestBolus, fresh.newestBolus)) {
                               WidgetLogic.Refusal.EXPIRED -> refuse(rh.gs(R.string.widget_bolus_expired))
                               WidgetLogic.Refusal.CHANGED -> refuse(rh.gs(R.string.widget_bolus_changed))
                               null                        -> deliver(shownSnap.amountAfterConstraints)
                           }
                       }, {
                           aapsLogger.error(LTag.WIDGET, "Widget bolus re-check failed", it)
                           refuse(rh.gs(R.string.widget_bolus_changed))
                       })
    }

    /** InsulinDialog's non record-only branch. Called while this activity is still resumed, then finish. */
    private fun deliver(insulin: Double) {
        if (phase != Phase.DELIVERING || isFinishing) return
        val detailedBolusInfo = DetailedBolusInfo()
        detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
        detailedBolusInfo.insulin = insulin
        detailedBolusInfo.context = null // CommandQueue shows the progress via BolusProgressHelperActivity
        detailedBolusInfo.notes = "Widget"
        detailedBolusInfo.timestamp = dateUtil.now()
        // same monitor as CommandQueueImplementation's @Synchronized methods: no other bolus can be queued (and replaced) in between
        synchronized(commandQueue) {
            if (commandQueue.bolusInQueue()) return refuse(Block.BOLUS_RUNNING)
            uel.log(Action.BOLUS, Sources.InsulinDialog, "Widget", ValueWithUnit.Insulin(insulin))
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    if (!result.success) {
                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                    } else {
                        automation.removeAutomationEventBolusReminder()
                    }
                }
            })
        }
        Widget.updateWidget(this, "WidgetBolus")
        done()
    }
}
