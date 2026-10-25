package app.aaps.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.widget.WidgetLogic.Block
import app.aaps.ui.widget.WidgetLogic.WizardBlock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The widget's carbs pill opens the normal AAPS bolus wizard (WizardDialog) in MainActivity, exactly like the
 * overview's calculator button: ProtectionCheck BOLUS, then UiInteraction.runWizardDialog. No amount, no math here.
 *
 * MainActivity is exported, so it only opens the wizard after this process armed a request: the tap goes through
 * the non-exported [WidgetWizardActivity], which arms [WidgetWizard] and brings MainActivity to the front.
 *
 * The widget disables the calculator pill whenever its quick bolus is locked (low, old / no data, paused, pump, bolus
 * running, starting, ...): the tap is refused on fresh data and again before the wizard opens, so a widget that
 * rendered before the lock (or an armed request) never opens the wizard while it is locked.
 */
@Singleton
class WidgetWizard @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val loop: Loop,
    private val protectionCheck: ProtectionCheck,
    private val uiInteraction: UiInteraction,
    private val rh: ResourceHelper,
    private val commandQueue: CommandQueue,
    private val lastBgData: LastBgData,
    private val dateUtil: DateUtil
) {

    companion object {

        const val ACTION_OPEN_WIZARD = "app.aaps.ui.widget.OPEN_WIZARD"

        @StringRes fun blockText(block: WizardBlock): Int = when (block) {
            WizardBlock.NOT_SUPPORTED   -> R.string.widget_bolus_block_not_supported
            WizardBlock.NOT_INITIALIZED -> R.string.widget_bolus_block_not_initialized
            WizardBlock.NO_PROFILE      -> R.string.widget_wizard_block_no_profile
            WizardBlock.PUMP_NOT_READY  -> R.string.widget_bolus_block_pump_not_ready
            WizardBlock.PUMP_SUSPENDED  -> R.string.widget_wizard_block_pump_suspended
        }
    }

    /** elapsedRealtime of the last widget tap, until a MainActivity intent consumes it */
    private var armedAt: Long? = null

    /** elapsedRealtime of the tap behind the pending MainActivity request, null = nothing to open */
    private var requestedAt: Long? = null

    /** Same conditions as the overview's calculator button */
    fun block(): WizardBlock? {
        val pump = activePlugin.activePump
        return WidgetLogic.wizardBlock(
            aps = config.APS,
            appInitialized = config.appInitialized,
            profileAvailable = profileFunction.getProfile() != null,
            pumpInitialized = pump.isInitialized(),
            pumpSuspendedOrDisconnected = loop.runningMode == RM.Mode.DISCONNECTED_PUMP || pump.isSuspended()
        )
    }

    /**
     * The quick-bolus lock as the widget renders it (in-memory data only, no constraints or DB read). The widget's
     * calculator pill follows it too. WidgetBolusActivity checks the full lock again before and at the confirmation.
     */
    fun quickBolusBlock(bgMgdl: Double?, bgTimestamp: Long?, now: Long): Block? = WidgetLogic.bolusBlock(
        WidgetLogic.BolusInput(
            aps = config.APS,
            appInitialized = config.appInitialized,
            pumpInitialized = activePlugin.activePump.isInitialized(),
            suspended = loop.runningMode.isSuspended(),
            bolusInQueue = commandQueue.bolusInQueue(),
            bgMgdl = bgMgdl,
            bgTimestamp = bgTimestamp,
            now = now
        )
    )

    /** [quickBolusBlock] on the current BG */
    fun quickBolusBlock(): Block? = lastBgData.lastBg().let { quickBolusBlock(it?.recalculated, it?.timestamp, dateUtil.now()) }

    /** Why the wizard may not open from the widget now (quick-bolus lock first), null = it may */
    fun refusal(): String? {
        quickBolusBlock()?.let { return rh.gs(R.string.widget_wizard_not_opened, rh.gs(WidgetBolusActivity.blockText(it))) }
        block()?.let { return rh.gs(R.string.widget_wizard_not_opened, rh.gs(blockText(it))) }
        return null
    }

    @Synchronized fun arm() {
        armedAt = SystemClock.elapsedRealtime()
    }

    /**
     * MainActivity.onCreate / onNewIntent. The first OPEN_WIZARD intent consumes the armed tap, so a tap
     * can never be reused by a later (external) intent; any other intent drops a pending request.
     */
    @Synchronized fun onIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_WIZARD) {
            requestedAt = armedAt?.takeIf { WidgetLogic.wizardRequestValid(it, SystemClock.elapsedRealtime()) }
            armedAt = null
        } else requestedAt = null
    }

    /** MainActivity, after its APPLICATION protection passed. Opens the wizard once per armed tap. */
    fun openIfRequested(activity: FragmentActivity) {
        val valid = synchronized(this) {
            val v = WidgetLogic.wizardRequestValid(requestedAt, SystemClock.elapsedRealtime())
            requestedAt = null
            v
        }
        if (!valid) return
        refusal()?.let {
            ToastUtils.warnToast(activity, it)
            Widget.updateWidget(activity, "WidgetWizard")
            return
        }
        aapsLogger.debug(LTag.WIDGET, "Widget carbs: opening bolus wizard")
        protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
            if (!activity.isFinishing && !activity.supportFragmentManager.isStateSaved) uiInteraction.runWizardDialog(activity.supportFragmentManager)
        })
    }
}

/**
 * Entry point of the widget's carbs pill. Not exported, no intent filter: only the widget's PendingIntent starts it.
 * Arms [WidgetWizard] and brings MainActivity to the front, which then opens the bolus wizard.
 */
class WidgetWizardActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var config: Config
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var widgetWizard: WidgetWizard

    companion object {

        const val ACTION_WIDGET_WIZARD = "app.aaps.ui.widget.WIZARD"
        const val EXTRA_WIDGET_ID = "widget_id"

        private fun dataUri(widgetId: Int): Uri = Uri.parse("aaps-widget://wizard/$widgetId")

        fun pendingIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetWizardActivity::class.java)
                .setAction(ACTION_WIDGET_WIZARD)
                .setData(dataUri(widgetId))
                .putExtra(EXTRA_WIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            return PendingIntent.getActivity(context, 0x5C00 + widgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return finish()
        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val ids = AppWidgetManager.getInstance(this)?.getAppWidgetIds(ComponentName(this, Widget::class.java)) ?: IntArray(0)
        if (intent.action != ACTION_WIDGET_WIZARD || widgetId !in ids || intent.data != dataUri(widgetId)) {
            aapsLogger.warn(LTag.WIDGET, "Widget carbs: invalid request")
            return finish()
        }
        if (config.APS) {
            // The pill may have rendered before a lock: refuse on fresh data, never arm a locked request
            widgetWizard.refusal()?.let {
                aapsLogger.debug(LTag.WIDGET, "Widget carbs: locked")
                ToastUtils.warnToast(this, it)
                Widget.updateWidget(this, "WidgetWizard")
                return finish()
            }
            widgetWizard.arm()
            startActivity(
                Intent(this, uiInteraction.mainActivity)
                    .setAction(WidgetWizard.ACTION_OPEN_WIZARD)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }
}
