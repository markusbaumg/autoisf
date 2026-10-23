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
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.widget.WidgetLogic.WizardBlock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The widget's carbs pill opens the normal AAPS bolus wizard (WizardDialog) in MainActivity, exactly like the
 * overview's calculator button: ProtectionCheck BOLUS, then UiInteraction.runWizardDialog. No amount, no math here.
 *
 * MainActivity is exported, so it only opens the wizard after this process armed a request: the tap goes through
 * the non-exported [WidgetWizardActivity], which arms [WidgetWizard] and brings MainActivity to the front.
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
    private val rh: ResourceHelper
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

    private var armedAt: Long? = null
    private var requested = false

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

    @Synchronized fun arm() {
        armedAt = SystemClock.elapsedRealtime()
    }

    /** MainActivity.onCreate / onNewIntent */
    @Synchronized fun onIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_WIZARD) requested = true
    }

    /** MainActivity, after its APPLICATION protection passed. Opens the wizard once per armed tap. */
    fun openIfRequested(activity: FragmentActivity) {
        val valid = synchronized(this) {
            val v = requested && WidgetLogic.wizardRequestValid(armedAt, SystemClock.elapsedRealtime())
            requested = false
            armedAt = null
            v
        }
        if (!valid) return
        block()?.let {
            ToastUtils.warnToast(activity, rh.gs(R.string.widget_wizard_not_opened, rh.gs(blockText(it))))
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
