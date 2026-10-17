package app.aaps.plugins.main.general.persistentNotification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.extensions.generateCOBString
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("PrivatePropertyName", "DEPRECATION")
@Singleton
class PersistentNotificationPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val aapsSchedulers: AapsSchedulers,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val activePlugins: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val rxBus: RxBus,
    private val context: Context,
    private val notificationHolder: NotificationHolder,
    private val dummyServiceHelper: DummyServiceHelper,
    private val iconsProvider: IconsProvider,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val config: Config,
    private val decimalFormatter: DecimalFormatter
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .neverVisible(true)
        .pluginName(R.string.ongoingnotificaction)
        .enableByDefault(true)
        .alwaysEnabled(true)
        .showInList { false }
        .description(R.string.description_persistent_notification),
    aapsLogger, rh
) {

    // For Android Auto
    // Intents are not declared in manifest and not consumed, this is intentionally because actually we can't do anything with
    private val PACKAGE = "info.nightscout"
    private val READ_ACTION = "info.nightscout.androidaps.ACTION_MESSAGE_READ"
    private val REPLY_ACTION = "info.nightscout.androidaps.ACTION_MESSAGE_REPLY"
    private val CONVERSATION_ID = "conversation_id"
    private val EXTRA_VOICE_REPLY = "extra_voice_reply"
    // End Android auto

    private val disposable = CompositeDisposable()

    override fun onStart() {
        super.onStart()
        notificationHolder.createNotificationChannel()
        // Every rebuild reads the last BG, IOB (bolus and basal, from the DB) and COB and posts the
        // notification. The triggers come in bursts: after a BG the calculation, the loop and the
        // overview each fire within seconds, and an open settings screen forwards every preference
        // write (a NSClient full re-sync wrote ~20,000 of them in 23 s). Of the preferences only
        // the ones in [NOTIFICATION_PREFERENCE_KEYS] change the text, so only those rebuild it. One
        // rebuild per [UPDATE_WINDOW_MS] is enough for an ongoing notification: the first event of
        // a burst is handled at once, the rest of the burst once more when the window ends.
        disposable += Observable
            .merge(
                listOf(
                    rxBus.toObservable(EventRefreshOverview::class.java),
                    rxBus.toObservable(EventInitializationChanged::class.java),
                    rxBus.toObservable(EventAutosensCalculationFinished::class.java),
                    rxBus.toObservable(EventPreferenceChange::class.java)
                        .filter { it.changedKey in NOTIFICATION_PREFERENCE_KEYS }
                ).map { events -> events.map { } }
            )
            .throttleLatest(UPDATE_WINDOW_MS, TimeUnit.MILLISECONDS, aapsSchedulers.io, true)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
    }

    override fun onStop() {
        disposable.clear()
        dummyServiceHelper.stopService(context)
        super.onStop()
    }

    private fun triggerNotificationUpdate() {
        // An exception reaching the subscriber would dispose the merged, throttled stream and stop
        // every later rebuild (e.g. ForegroundServiceStartNotAllowedException from startService
        // while the app is in the background). Log it and keep the stream alive.
        try {
            updateNotification()
            dummyServiceHelper.startService(context)
        } catch (e: Exception) {
            fabricPrivacy.logException(e)
        }
    }

    private fun updateNotification() {
        if (!config.appInitialized) return
        val pump = activePlugins.activePump
        var line1: String?
        var line2: String? = null
        var line3: String? = null
        var unreadConversationBuilder: NotificationCompat.CarExtender.UnreadConversation.Builder? = null
        if (profileFunction.isProfileValid("Notification")) {
            var line1aa: String
            val lastBG = iobCobCalculator.ads.lastBg()
            val glucoseStatus = glucoseStatusProvider.glucoseStatusData
            if (lastBG != null) {
                line1aa = profileUtil.fromMgdlToStringInUnits(lastBG.recalculated)
                line1 = line1aa
                if (glucoseStatus != null) {
                    line1 += ("  Δ" + profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                        + " avgΔ" + profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.shortAvgDelta))
                    line1aa += "  " + lastBG.trendArrow.symbol
                } else {
                    line1 += " " +
                        rh.gs(R.string.old_data) +
                        " "
                    line1aa += "$line1."
                }
            } else {
                line1aa = rh.gs(app.aaps.core.ui.R.string.missed_bg_readings)
                line1 = line1aa
            }
            val activeTemp = processedTbrEbData.getTempBasalIncludingConvertedExtended(System.currentTimeMillis())
            if (activeTemp != null) {
                line1 += "  " + activeTemp.toStringShort(rh)
                line1aa += "  " + activeTemp.toStringShort(rh) + "."
            }
            //IOB
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            // each read once: COB and the profile name query the DB, the pump getter logs a line
            val cob = iobCobCalculator.getCobInfo("PersistentNotificationPlugin").generateCOBString(decimalFormatter)
            line2 =
                rh.gs(app.aaps.core.ui.R.string.treatments_iob_label_string) + " " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, (bolusIob.iob + basalIob.basaliob)) + " " + rh.gs(
                    app.aaps.core.ui.R
                        .string.cob
                ) + ": " + cob
            val line2aa =
                rh.gs(app.aaps.core.ui.R.string.treatments_iob_label_string) + " " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, (bolusIob.iob + basalIob.basaliob)) + ". " + rh.gs(
                    app.aaps.core.ui.R
                        .string.cob
                ) + ": " + cob + "."
            val baseBasalRate = rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, pump.baseBasalRate)
            val profileName = profileFunction.getProfileName()
            line3 = "$baseBasalRate - $profileName"
            var line3aa = "$baseBasalRate. - $profileName."
            /// For Android Auto
            val msgReadIntent = Intent()
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .setAction(READ_ACTION)
                .putExtra(CONVERSATION_ID, notificationHolder.notificationID)
                .setPackage(PACKAGE)
            val msgReadPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationHolder.notificationID,
                msgReadIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val msgReplyIntent = Intent()
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .setAction(REPLY_ACTION)
                .putExtra(CONVERSATION_ID, notificationHolder.notificationID)
                .setPackage(PACKAGE)
            val msgReplyPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationHolder.notificationID,
                msgReplyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // Build a RemoteInput for receiving voice input from devices
            val remoteInput = RemoteInput.Builder(EXTRA_VOICE_REPLY).build()
            // Create the UnreadConversation
            unreadConversationBuilder = NotificationCompat.CarExtender.UnreadConversation.Builder(line1aa + "\n" + line2aa)
                .setLatestTimestamp(System.currentTimeMillis())
                .setReadPendingIntent(msgReadPendingIntent)
                .setReplyAction(msgReplyPendingIntent, remoteInput)
            /// Add dot to produce a "more natural sounding result"
            unreadConversationBuilder.addMessage(line3aa)
            /// End Android Auto
        } else {
            line1 = rh.gs(app.aaps.core.ui.R.string.no_profile_set)
        }
        val builder = NotificationCompat.Builder(context, notificationHolder.channelID)
        builder.setOngoing(true)
        builder.setOnlyAlertOnce(true)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
        builder.setSmallIcon(iconsProvider.getNotificationIcon())
        builder.setLargeIcon(rh.decodeResource(iconsProvider.getIcon()))
        builder.setContentTitle(line1)
        if (line2 != null) builder.setContentText(line2)
        if (line3 != null) builder.setSubText(line3)
        /// Android Auto
        if (unreadConversationBuilder != null) {
            builder.extend(
                NotificationCompat.CarExtender()
                    .setUnreadConversation(unreadConversationBuilder.build())
            )
        }
        /// End Android Auto
        builder.setContentIntent(notificationHolder.openAppIntent(context))
        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = builder.build()
        mNotificationManager.notify(notificationHolder.notificationID, notification)
        notificationHolder.notification = notification
    }

    companion object {

        /** At most one notification rebuild per this many ms; the last event of a burst is never lost. */
        const val UPDATE_WINDOW_MS = 2_000L

        /**
         * The preferences the notification text depends on: the glucose units (ProfileUtil), the
         * language and simple mode (LocaleHelper reads simple_mode before language). Any other
         * preference shows with the next rebuild after a BG.
         */
        val NOTIFICATION_PREFERENCE_KEYS = setOf(StringKey.GeneralUnits.key, StringKey.GeneralLanguage.key, BooleanKey.GeneralSimpleMode.key)
    }
}
