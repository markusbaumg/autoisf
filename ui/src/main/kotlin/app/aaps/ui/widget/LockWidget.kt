package app.aaps.ui.widget

import android.app.PendingIntent
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.format.DateFormat
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.round
import app.aaps.ui.R
import app.aaps.ui.widget.LockWidgetLogic.PaletteKind
import app.aaps.ui.widget.LockWidgetLogic.Tier
import app.aaps.ui.widget.WidgetLogic.BasalState
import dagger.android.HasAndroidInjector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * KUSI: lock-widget. Lock-screen widget "AAPS Sperrbildschirm" for Samsung Good Lock > LockStar (also works on a home screen).
 * Type only, no card: BG + trend arrow, age · IOB, one conditional line (loop / temp target / unusual basal / carbs) and,
 * in size L, time in range 24 h. Display only: no bolus, no wizard, a tap opens AAPS. Reads the same read-only sources
 * as [Widget]; never reads ISF (nothing that runs AutoISF). Updated through [Widget.updateWidget] (new BG, loop, 60 s tick).
 * Everything runs on its own handler thread inside try/catch, so a render error can never take down the AAPS process.
 */
class LockWidget : AppWidgetProvider() {

    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var loop: Loop
    @Inject lateinit var config: Config
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var statsCache: WidgetStatsCache
    @Inject lateinit var processedTbrEbData: ProcessedTbrEbData

    companion object {

        private const val TAG = "LockWidget"
        private const val EXTRA_FROM = "from"

        private val handler by lazy { Handler(HandlerThread("LockWidgetHandler").also { it.start() }.looper) }

        /** Last rendered state per widget id: an unchanged state is not sent to the host again */
        private val lastKeys = ConcurrentHashMap<Int, String>()

        /**
         * Fan-out from [Widget.updateWidget]. Returns at once when no lock widget is placed (the 60 s tick stays free).
         * Explicit broadcast to this provider only, so the home widget never receives lock widget ids.
         */
        fun updateWidget(context: Context, from: String) {
            try {
                val ids = AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, LockWidget::class.java))
                if (ids == null || ids.isEmpty()) return
                context.sendBroadcast(Intent(context, LockWidget::class.java).also {
                    it.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    it.putExtra(EXTRA_FROM, from)
                })
            } catch (e: Exception) {
                Log.e(TAG, "updateWidget failed", e)
            }
        }
    }

    private lateinit var appContext: Context

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
            appContext = context.applicationContext
            if (intent?.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
                // Only our own ids: an update broadcast meant for the home widget must never render lock views into it
                val manager = AppWidgetManager.getInstance(context) ?: return
                val own = manager.getAppWidgetIds(ComponentName(context, LockWidget::class.java)) ?: return
                val requested = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                val ids = if (requested == null) own else own.filter { it in requested }.toIntArray()
                if (ids.isEmpty()) return
                // a system update (boot, host restart, app update) always redraws; our own refreshes skip unchanged states
                render(manager, ids, force = intent.getStringExtra(EXTRA_FROM) == null)
                return
            }
            super.onReceive(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "onReceive failed", e)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(appWidgetManager, appWidgetIds, force = true)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        render(appWidgetManager, intArrayOf(appWidgetId), force = true)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { lastKeys.remove(it) }
    }

    private fun render(manager: AppWidgetManager, ids: IntArray, force: Boolean) {
        val ctx = appContext
        handler.post {
            try {
                if (!config.appInitialized) return@post
                statsCache.refreshIfNeeded()
                val input = collect()
                val colors = wallpaper(ctx)
                val palette = LockWidgetLogic.palette(colors.first)
                val scrim = colors.second
                val texts = LockWidgetRenderer.texts(ctx)
                val fontScale = ctx.resources.configuration.fontScale
                val click = PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, uiInteraction.mainActivity).also { it.action = "OpenAppLock" },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                for (id in ids) {
                    try {
                        val sizes = sizes(manager, id)
                        val tiers = sizes.map { LockWidgetLogic.tier(it.width, it.height) }
                        val models = tiers.distinct().associateWith { LockWidgetLogic.build(input, it, palette, texts) }
                        val key = "$tiers|${palette.kind}|$scrim|$fontScale|$models"
                        if (!force && lastKeys[id] == key) continue
                        val views =
                            if (sizes.size == 1) LockWidgetRenderer.build(ctx, models.getValue(tiers[0]), tiers[0], palette, texts, scrim, fontScale, click)
                            else RemoteViews(sizes.indices.associate { i ->
                                sizes[i] to LockWidgetRenderer.build(ctx, models.getValue(tiers[i]), tiers[i], palette, texts, scrim, fontScale, click)
                            })
                        manager.updateAppWidget(id, views)
                        lastKeys[id] = key
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.WIDGET, "LockWidget render $id failed", e)
                    }
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.WIDGET, "LockWidget render failed", e)
            }
        }
    }

    /** Sizes the host may show (portrait / landscape); without a list the portrait size (min width x max height) */
    private fun sizes(manager: AppWidgetManager, id: Int): List<SizeF> {
        val options = manager.getAppWidgetOptions(id)
        @Suppress("DEPRECATION")
        val list = options?.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }?.distinct()?.take(16)
        if (!list.isNullOrEmpty()) return list
        val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        // LockStar's default slot when the host tells nothing
        return listOf(SizeF(if (w > 0) w.toFloat() else 359f, if (h > 0) h.toFloat() else 274f))
    }

    /** Palette from the lock wallpaper (fallback: home wallpaper, then light) and the scrim strength. No permission needed. */
    private fun wallpaper(ctx: Context): Pair<PaletteKind, Int> =
        try {
            val wm = WallpaperManager.getInstance(ctx)
            val lock = wm.getWallpaperColors(WallpaperManager.FLAG_LOCK)
            val system = if (lock == null) wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) else null
            val kind = LockWidgetLogic.paletteKind(lock?.colorHints, system?.colorHints)
            kind to LockWidgetLogic.scrimAlpha((lock ?: system)?.primaryColor?.toArgb())
        } catch (e: Exception) {
            PaletteKind.LIGHT to LockWidgetLogic.SCRIM_ALPHA_MID
        }

    /** Read-only state, the same sources as [Widget] (BG, trend, IOB, COB, basal, temp target, last loop run, TIR 24 h) */
    private fun collect(): LockWidgetLogic.Input {
        val now = dateUtil.now()
        val bg = lastBgData.lastBg()
        val arrow = WidgetLogic.trendArrow(trendCalculator.getTrendArrow(iobCobCalculator.ads), bg?.trendArrow)
        val iob = iobCobCalculator.calculateIobFromBolus().round().iob + iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
        val cob = iobCobCalculator.getCobInfo("LockWidget COB").displayCob
        val profile = profileFunction.getProfile()
        val aps = config.APS
        val basal = if (aps && profile != null) basalState(profile, now) else BasalState.None
        val tt = persistenceLayer.getTemporaryTargetActiveAt(now)
        val mode = if (aps) loop.runningMode else null
        val loopOff = aps && (mode == RM.Mode.DISABLED_LOOP || mode?.isSuspended() == true || activePlugin.activePump.isSuspended())
        return LockWidgetLogic.Input(
            now = now,
            bgMgdl = bg?.recalculated,
            bgTimestamp = bg?.timestamp,
            arrow = arrow,
            mmol = profileFunction.getUnits() == GlucoseUnit.MMOL,
            iob = iob,
            cob = cob,
            aps = aps,
            loopOff = loopOff,
            lastLoopRun = if (aps) loop.lastRun?.lastAPSRun else null,
            ttMgdl = tt?.let { (it.lowTarget + it.highTarget) / 2.0 },
            ttMinutesLeft = tt?.let { WidgetLogic.ttMinutesLeft(it.timestamp, it.duration, now) },
            basal = basal,
            tir24h = statsCache.stats.tir24h,
            since = bg?.timestamp?.let { sinceText(it, now) }
        )
    }

    /** "17:16", "gestern 23:40", older: "23.10. 23:40" (system 12 / 24 h setting) */
    private fun sinceText(timestamp: Long, now: Long): String {
        val locale = appContext.resources.configuration.locales[0]
        val time = if (DateFormat.is24HourFormat(appContext)) "HHmm" else "hmma"
        return when (LockWidgetLogic.sinceDays(timestamp, now)) {
            0L   -> SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, time), locale).format(Date(timestamp))
            1L   -> appContext.getString(
                R.string.lock_widget_yesterday,
                SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, time), locale).format(Date(timestamp))
            )

            else -> SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dM$time"), locale).format(Date(timestamp))
        }
    }

    /** Same read-only basal state as Widget.basalState (pump / loop / DB), display only */
    private fun basalState(profile: Profile, now: Long): BasalState {
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val rate = tb?.convertedToAbsolute(now, profile)
        val zeroSince = if (tb != null && rate != null && rate < WidgetLogic.ZERO_RATE)
            WidgetLogic.zeroStreakStart(now) { t ->
                processedTbrEbData.getTempBasalIncludingConvertedExtended(t)?.takeIf { it.convertedToAbsolute(t, profile) < WidgetLogic.ZERO_RATE }?.timestamp
            }
        else null
        val smb = persistenceLayer.getNewestBolusOfType(BS.Type.SMB)?.takeIf { it.isValid }
        return WidgetLogic.basalState(
            WidgetLogic.BasalInput(
                now = now,
                mode = loop.runningMode,
                pumpSuspended = activePlugin.activePump.isSuspended(),
                suspendMinutesLeft = loop.minutesToEndOfSuspend(),
                tempBasal = tb != null,
                rate = rate,
                pct = tb?.let { WidgetLogic.tbrPercent(it.isAbsolute, it.rate, profile.getBasal(now)) },
                profileBasal = profile.getBasal(now),
                zeroSince = zeroSince,
                smbAmount = smb?.amount,
                smbAt = smb?.timestamp
            )
        )
    }
}
