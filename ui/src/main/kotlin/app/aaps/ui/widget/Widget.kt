package app.aaps.ui.widget

import android.app.PendingIntent
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Bundle
import android.os.HandlerThread
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TrendArrow
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
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.round
import app.aaps.ui.R
import app.aaps.ui.widget.WidgetLogic.BasalState
import app.aaps.ui.widget.WidgetLogic.BgRange
import app.aaps.ui.widget.WidgetLogic.Block
import app.aaps.ui.widget.WidgetLogic.CalcPill
import app.aaps.ui.widget.WidgetLogic.MeterLevel
import dagger.android.HasAndroidInjector
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Home-screen widget, design R2 "Luft" (glass card).
 * Shows BG + trend + data age, time in range 24 h / 7 d / 30 d, 14 day mean glucose + HbA1c, IOB / COB / TDD,
 * a calculator pill (bolus wizard) and two quick-bolus pills. Every quick-bolus lock disables the calculator pill too.
 * The pills never bolus: they open [WidgetBolusActivity], which asks for protection and an extra confirmation.
 */
class Widget : AppWidgetProvider() {

    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var loop: Loop
    @Inject lateinit var config: Config
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var statsCache: WidgetStatsCache
    @Inject lateinit var widgetWizard: WidgetWizard
    @Inject lateinit var processedTbrEbData: ProcessedTbrEbData

    companion object {

        // This object doesn't behave like singleton,
        // many threads were created. Making handler static resolve this issue
        private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

        fun updateWidget(context: Context, from: String) {
            LockWidget.updateWidget(context, from) // KUSI: lock-widget
            context.sendBroadcast(Intent().also {
                it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, Widget::class.java)))
                it.putExtra("from", from)
                it.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            })
        }

        private val meterFills = intArrayOf(R.id.tir1_fill, R.id.tir2_fill, R.id.tir3_fill)
        private val meterTexts = intArrayOf(R.id.tir1, R.id.tir2, R.id.tir3)

        /** widget_glass_txt3 #B4BED5, solid: glyph of the disabled calculator pill (the pill background marks it locked) */
        private const val DISABLED_TEXT_COLOR = 0xFFB4BED5.toInt()

        /** Alpha (0..255) of the meter fills during a low, so the red BG is the only loud thing (texts use solid txt3) */
        private const val LOW_DIM_ALPHA = 0x73

        /** First render (wall clock) of the current self-ending lock (bolus running), see [scheduleLockFollowUp] */
        private var lockedSince: Long? = null
        private var lockFollowUp: Runnable? = null

        /**
         * A lock that ends without a new BG or loop run (bolus running) would stay on the widget until the next refresh:
         * render once more after [WidgetLogic.lockFollowUpDelay] (10 s, repeated while it lasts, at most 3 min).
         * Nothing is scheduled while unlocked or for the other locks. Runs on [handler].
         */
        private fun scheduleLockFollowUp(context: Context, block: Block?, now: Long) {
            lockedSince = if (block == Block.BOLUS_RUNNING || block == Block.RECENT_BOLUS) lockedSince ?: now else null
            lockFollowUp?.let { handler.removeCallbacks(it) }
            lockFollowUp = null
            val delay = WidgetLogic.lockFollowUpDelay(block, lockedSince, now) ?: return
            val appContext = context.applicationContext
            lockFollowUp = Runnable { updateWidget(appContext, "LockFollowUp") }.also { handler.postDelayed(it, delay) }
        }
    }

    private val intentAction = "OpenApp"

    /** Application context for the widget texts, see [gs] */
    private lateinit var appContext: Context

    /** BG text size relative to the fresh size (stale / no data smaller), set by [updateBg], used by [applySize] */
    private var bgScale = 1f

    override fun onReceive(context: Context, intent: Intent?) {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
        appContext = context.applicationContext
        aapsLogger.debug(LTag.WIDGET, "onReceive ${intent?.extras?.getString("from")}")
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        // resized: BG size and carbs pill follow the new size
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            preferences.remove(IntComposedKey.WidgetBolusAmount1, appWidgetId)
            preferences.remove(IntComposedKey.WidgetBolusAmount2, appWidgetId)
        }
    }

    private fun color(id: Int) = rh.gc(id)

    /**
     * Widget texts in the system language, like the labels the launcher resolves from widget_layout.xml ("KH", "7 T").
     * rh.gs() follows the AAPS language setting instead, which mixed English ("Target", "now") into a German widget.
     */
    private fun gs(id: Int, vararg args: Any?): String = appContext.getString(id, *args)

    /** Glass recipe "auto tier": a wallpaper that supports dark text is bright and needs the denser tint. No permission needed. */
    private fun brightWallpaper(context: Context): Boolean =
        try {
            val hints = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints ?: 0
            hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        } catch (e: Exception) {
            false
        }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val opacity = preferences.get(IntComposedKey.WidgetOpacity, appWidgetId)
        val useBlack = preferences.get(BooleanComposedKey.WidgetUseBlack, appWidgetId)

        // Create an Intent to launch MainActivity when clicked
        val intent = Intent(context, uiInteraction.mainActivity).also { it.action = intentAction }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Widgets allow click handlers to only launch pending intents
        views.setOnClickPendingIntent(R.id.widget_layout, pendingIntent)

        // Glass: the opacity setting scales the smoke tint, "use black" turns it into dark glass
        // Rim: the bright top layer of the film / pills is a <clip>; levels are 0..10000 of the height
        views.setInt(R.id.glass_film, "setImageLevel", 3500)
        views.setInt(R.id.bolus1_bg, "setImageLevel", 5000)
        views.setInt(R.id.bolus2_bg, "setImageLevel", 5000)
        views.setImageViewResource(R.id.glass_tint, if (useBlack) R.drawable.widget_glass_tint_black else R.drawable.widget_glass_tint)
        views.setInt(R.id.glass_tint, "setImageAlpha", WidgetLogic.glassTintAlpha(opacity, brightWallpaper(context)))
        when {
            useBlack || config.APS -> views.setInt(R.id.glass_tint, "setColorFilter", Color.TRANSPARENT)
            config.AAPSCLIENT1     -> views.setInt(R.id.glass_tint, "setColorFilter", color(R.color.widget_glass_tint_aapsclient1))
            config.AAPSCLIENT2     -> views.setInt(R.id.glass_tint, "setColorFilter", color(R.color.widget_glass_tint_aapsclient2))
        }

        handler.post {
            if (config.appInitialized) {
                statsCache.refreshIfNeeded()
                val now = dateUtil.now()
                val bg = lastBgData.lastBg()
                val range = WidgetLogic.bgRange(bg?.recalculated, bg?.timestamp, now)
                updateHeader(views, range, now, headerWidthDp(appWidgetManager, appWidgetId))
                updateBg(views, range, bg?.recalculated, bg?.timestamp, bg?.trendArrow, now)
                updateTir(views, range)
                updateLongTerm(views, range)
                updateBottom(views, range)
                val bolusBlock = updateBolusButtons(context, views, appWidgetId, bg?.recalculated, bg?.timestamp, now)
                updateCarbsButton(context, views, appWidgetId, bolusBlock)
                // Instruct the widget manager to update the widget
                appWidgetManager.updateAppWidget(appWidgetId, sized(views, appWidgetManager, appWidgetId))
                scheduleLockFollowUp(context, bolusBlock, now)
            }
        }
    }

    private fun isMmol() = profileFunction.getUnits() == GlucoseUnit.MMOL

    /** Width the header line has: the portrait (narrowest) widget width minus the 2 x 22 dp padding */
    private fun headerWidthDp(appWidgetManager: AppWidgetManager, appWidgetId: Int): Float {
        val w = appWidgetManager.getAppWidgetOptions(appWidgetId)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        return (if (w > 0) w.toFloat() else 360f) - 44f
    }

    /**
     * Header: profile name (always, "Sport 80 %"), target ("◎ 5,0", temp target blue + "noch 35′"), current ISF from
     * the last loop result ("ISF 4,1 ×1,15"), and at the right end the basal only when it deviates from the profile
     * (chip) or an SMB just ran. Read-only: nothing here feeds the loop.
     */
    private fun updateHeader(views: RemoteViews, range: BgRange, now: Long, availableDp: Float) {
        val stale = range == BgRange.STALE || range == BgRange.NONE
        val txt1 = color(R.color.widget_glass_txt1)
        val txt2 = color(R.color.widget_glass_txt2)
        val txt3 = color(R.color.widget_glass_txt3)

        // Profile: always, as it is (name + % of a percentage switch)
        val eps = persistenceLayer.getEffectiveProfileSwitchActiveAt(now)
        val profileName = eps?.originalProfileName?.takeIf { it.isNotBlank() } ?: profileFunction.getProfileName()
        val pct = eps?.originalPercentage?.takeIf { it != 100 }
        val profileText = SpannableStringBuilder(profileName).also { sb ->
            if (pct != null) appendStyled(sb, " $pct %", txt3, 0.88f)
        }
        views.setTextViewText(R.id.profile, profileText)
        views.setTextColor(R.id.profile, if (stale) txt3 else txt2)
        views.setViewVisibility(R.id.profile, View.VISIBLE)

        val profile = profileFunction.getProfile()
        if (profile == null) {
            listOf(R.id.target_icon, R.id.target, R.id.isf, R.id.basal).forEach { views.setViewVisibility(it, View.GONE) }
            return
        }

        // Target: temp target midpoint or the profile target
        val tt = persistenceLayer.getTemporaryTargetActiveAt(now)
        val targetMgdl = tt?.let { (it.lowTarget + it.highTarget) / 2.0 } ?: profile.getTargetMgdl()
        val targetText = WidgetLogic.glucose(targetMgdl, isMmol())
        val ttLeft = tt?.let { WidgetLogic.ttMinutesLeft(it.timestamp, it.duration, now) }
        val ttSuffix = ttLeft?.let { gs(R.string.widget_tt_left, WidgetLogic.durationShort(it.toLong())) }

        // ISF: last loop result only, hidden without a recent one (> 15 min)
        val run = loop.lastRun
        val req = run?.request
        val isf = if (config.APS) WidgetLogic.currentIsf(req?.variableSens, run?.lastAPSRun, now) else null
        val isfText = isf?.let { WidgetLogic.glucose(it, isMmol()) }
        val factor = if (isf != null && !stale) req?.oapsProfileAutoIsf?.let { WidgetLogic.autoIsfFactor(it.sens, it.variable_sens) } else null
        val factorText = factor?.let { WidgetLogic.factorText(it) }

        // Basal
        val basal = if (config.APS) basalState(profile, now) else BasalState.None
        val basalPlain = basalText(basal)

        val fit = WidgetLogic.headerFit(
            availableDp, profileText.toString(), targetText, ttSuffix, isfText, factorText, basalPlain, basal.chip,
            appContext.resources.configuration.fontScale
        )

        // Target view
        views.setViewVisibility(R.id.target_icon, View.VISIBLE)
        views.setViewVisibility(R.id.target, View.VISIBLE)
        views.setInt(R.id.target_icon, "setColorFilter", if (tt != null && !stale) color(R.color.widget_glass_tt) else txt3)
        val target = SpannableStringBuilder()
        appendStyled(target, targetText, if (stale) txt3 else if (tt != null) txt1 else txt2, medium = true)
        if (ttSuffix != null && fit.showTtSuffix) appendStyled(target, " $ttSuffix", txt3, 0.88f)
        views.setTextViewText(R.id.target, target)
        views.setContentDescription(R.id.target, gs(R.string.widget_target, targetText + (ttSuffix?.let { ", $it" } ?: "")))

        // ISF view
        if (isfText != null && fit.showIsf) {
            val sb = SpannableStringBuilder()
            appendStyled(sb, gs(R.string.widget_isf) + " ", txt3)
            appendStyled(sb, isfText, if (stale) txt3 else txt2, medium = true)
            if (factorText != null && fit.showFactor) appendStyled(sb, " $factorText", txt3, 0.88f)
            views.setTextViewText(R.id.isf, sb)
            views.setContentDescription(R.id.isf, gs(R.string.widget_isf_description, isfText + (factorText?.let { " $it" } ?: "")))
            views.setViewVisibility(R.id.isf, View.VISIBLE)
        } else views.setViewVisibility(R.id.isf, View.GONE)

        updateBasal(views, basal, basalPlain, stale)
    }

    /** Basal slot state from read-only pump / loop / DB data */
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

    /** Main text and suffix of the basal slot, null when nothing is shown */
    private fun basalParts(state: BasalState): Pair<String, String?>? {
        val rateUnit = gs(R.string.widget_unit_rate)
        fun minutesLeft(m: Int) = if (m > 0) gs(R.string.widget_tt_left, WidgetLogic.durationShort(m.toLong())) else null
        return when (state) {
            BasalState.None            -> null
            BasalState.PumpStopped     -> gs(R.string.widget_pump_stopped) to null
            is BasalState.Disconnected -> gs(R.string.widget_disconnected) to minutesLeft(state.minutesLeft)
            is BasalState.Paused       -> gs(R.string.widget_paused) to minutesLeft(state.minutesLeft)
            is BasalState.Zero         -> "0 $rateUnit" to gs(R.string.widget_since, WidgetLogic.durationShort(state.sinceMinutes, state.capped))
            // U/h only: on a low profile hour the % reads like an overdose (0,95 U/h = 633 % of 0,15 U/h)
            is BasalState.High         -> "${WidgetLogic.rateText(state.rate)} $rateUnit" to null
            is BasalState.Quiet        -> "${WidgetLogic.rateText(state.rate)} $rateUnit" to null
            is BasalState.Smb          ->
                (if (state.minutesAgo < 1) gs(R.string.widget_smb_now, WidgetLogic.amountText(state.amount))
                else gs(R.string.widget_smb_ago, WidgetLogic.amountText(state.amount), state.minutesAgo.toInt())) to null
        }
    }

    private fun basalText(state: BasalState): String? = basalParts(state)?.let { (main, suffix) -> main + (suffix?.let { " $it" } ?: "") }

    private fun updateBasal(views: RemoteViews, state: BasalState, plain: String?, stale: Boolean) {
        val parts = basalParts(state)
        if (parts == null || plain == null) {
            views.setViewVisibility(R.id.basal, View.GONE)
            return
        }
        val (main, suffix) = parts
        val txt1 = color(R.color.widget_glass_txt1)
        val txt2 = color(R.color.widget_glass_txt2)
        val txt3 = color(R.color.widget_glass_txt3)
        val rateUnit = gs(R.string.widget_unit_rate)
        val sb = SpannableStringBuilder()
        val mainColor = if (!state.chip) txt3 else if (stale) txt2 else txt1
        if (main.endsWith(" $rateUnit")) {
            appendStyled(sb, main.removeSuffix(" $rateUnit"), mainColor, medium = state.chip)
            appendStyled(sb, " $rateUnit", txt3, 0.85f)
        } else appendStyled(sb, main, mainColor, medium = state.chip)
        if (suffix != null) appendStyled(sb, " $suffix", txt3, if (state.chip) 0.96f else 1f)
        views.setTextViewText(R.id.basal_text, sb)
        views.setContentDescription(R.id.basal, gs(R.string.widget_basal_description, plain))

        val (icon, iconColor) = when (state) {
            BasalState.PumpStopped                           -> R.drawable.widget_ic_pause to color(R.color.widget_glass_low)
            is BasalState.Disconnected, is BasalState.Paused -> R.drawable.widget_ic_pause to color(R.color.widget_glass_high)
            is BasalState.Zero                               -> R.drawable.widget_ic_tbr_zero to color(R.color.widget_glass_tbr)
            is BasalState.High                               -> R.drawable.widget_ic_tbr_high to color(R.color.widget_glass_tbr)
            is BasalState.Quiet                              -> (if (state.lower) R.drawable.widget_ic_tbr_low else null) to txt3
            BasalState.None, is BasalState.Smb               -> null to txt3
        }
        if (icon != null) {
            views.setImageViewResource(R.id.basal_icon, icon)
            views.setInt(R.id.basal_icon, "setColorFilter", iconColor)
            views.setViewVisibility(R.id.basal_icon, View.VISIBLE)
        } else views.setViewVisibility(R.id.basal_icon, View.GONE)

        if (state.chip) {
            views.setInt(R.id.basal, "setBackgroundResource", R.drawable.widget_header_chip)
            views.setViewPadding(R.id.basal, dp(7f), 0, dp(9f), 0)
        } else {
            views.setInt(R.id.basal, "setBackgroundResource", 0)
            views.setViewPadding(R.id.basal, 0, 0, 0, 0)
        }
        views.setViewVisibility(R.id.basal, View.VISIBLE)
    }

    private fun rangeColor(range: BgRange): Int = color(
        when (range) {
            BgRange.IN                -> R.color.widget_glass_in
            BgRange.HIGH              -> R.color.widget_glass_high
            BgRange.LOW               -> R.color.widget_glass_low
            BgRange.STALE, BgRange.NONE -> R.color.widget_glass_stale
        }
    )

    private fun updateBg(views: RemoteViews, range: BgRange, bgMgdl: Double?, bgTimestamp: Long?, lastBgArrow: TrendArrow?, now: Long) {
        val freshness = if (range == BgRange.STALE || range == BgRange.NONE) WidgetLogic.freshness(bgTimestamp, now) else WidgetLogic.Freshness.FRESH
        if (freshness != WidgetLogic.Freshness.FRESH) {
            updateBgOld(views, freshness, bgMgdl, bgTimestamp, now)
            return
        }
        bgScale = 1f
        val col = rangeColor(range)
        views.setTextViewText(R.id.bg, bgMgdl?.let { WidgetLogic.glucose(it, isMmol()) } ?: gs(R.string.widget_value_unknown))
        views.setTextColor(R.id.bg, col)
        views.setContentDescription(R.id.bg, null)
        views.setInt(R.id.bg_block, "setGravity", android.view.Gravity.CENTER_VERTICAL)
        views.setViewPadding(R.id.bg_block, 0, 0, 0, 0)
        views.setViewVisibility(R.id.age_column, View.VISIBLE)
        views.setViewVisibility(R.id.bg_status, View.GONE)

        // Same arrow as the overview; the newest glucose value's own arrow while the bucketed data is not there yet
        // A fresh BG without a known trend gets a neutral dash in txt3, never a blank
        val arrow = WidgetLogic.trendArrow(trendCalculator.getTrendArrow(iobCobCalculator.ads), lastBgArrow)
        val arrowIcon = WidgetLogic.arrowSlotIcon(range, arrow)
        if (arrowIcon != null) {
            views.setImageViewResource(R.id.arrow, arrowIcon)
            views.setInt(R.id.arrow, "setColorFilter", if (arrow == null) color(R.color.widget_glass_txt3) else col)
            views.setViewVisibility(R.id.arrow, View.VISIBLE)
        } else views.setViewVisibility(R.id.arrow, View.GONE)

        val age = bgTimestamp?.let { WidgetLogic.ageMinutes(it, now) }
        val ageText = when {
            age == null -> ""
            age < 1     -> gs(R.string.widget_age_now)
            age < 60    -> gs(R.string.widget_age_min, age.toInt())
            else        -> gs(R.string.widget_age_hours, (age / 60).toInt())
        }
        views.setTextViewText(R.id.age, ageText)
        views.setTextColor(R.id.age, color(R.color.widget_glass_txt2))

        when (range) {
            BgRange.IN                  -> views.setImageViewResource(R.id.bloom, R.drawable.widget_glass_bloom_in)
            BgRange.HIGH                -> views.setImageViewResource(R.id.bloom, R.drawable.widget_glass_bloom_high)
            BgRange.STALE, BgRange.NONE -> views.setImageViewResource(R.id.bloom, R.drawable.widget_glass_bloom_stale)
            BgRange.LOW                 -> Unit // no red glow under red digits
        }
        views.setViewVisibility(R.id.bloom, if (range == BgRange.LOW) View.GONE else View.VISIBLE)
    }

    /**
     * Stale (12..30 min): smaller BG, no arrow, "vor 23 Min." under it. No data (>= 30 min or none): "– –" and
     * "Keine Daten seit 01:18". Calm grey only for an in-range last value: a last low keeps a dimmed red number and the
     * word "zuletzt tief" (never colour alone), a last high a dimmed amber and "zuletzt hoch". No red glow.
     */
    private fun updateBgOld(views: RemoteViews, freshness: WidgetLogic.Freshness, bgMgdl: Double?, bgTimestamp: Long?, now: Long) {
        val last = bgMgdl?.let { WidgetLogic.lastRange(it) }
        val amber = color(R.color.widget_glass_high)
        val red = color(R.color.widget_glass_low)
        val txt3 = color(R.color.widget_glass_txt3)
        views.setViewVisibility(R.id.age_column, View.GONE)
        views.setViewVisibility(R.id.bg_status, View.VISIBLE)
        views.setImageViewResource(R.id.bloom, R.drawable.widget_glass_bloom_stale)
        views.setViewVisibility(R.id.bloom, View.VISIBLE)
        views.setInt(R.id.bg_block, "setGravity", android.view.Gravity.TOP or android.view.Gravity.START)

        if (freshness == WidgetLogic.Freshness.STALE && bgMgdl != null && bgTimestamp != null) {
            bgScale = WidgetLogic.STALE_BG_SCALE
            val bgText = WidgetLogic.glucose(bgMgdl, isMmol())
            views.setTextViewText(R.id.bg, bgText)
            views.setTextColor(
                R.id.bg, color(
                    when (last) {
                        BgRange.LOW  -> R.color.widget_glass_low_dim
                        BgRange.HIGH -> R.color.widget_glass_high_dim
                        else         -> R.color.widget_glass_txt3
                    }
                )
            )
            val ageText = gs(R.string.widget_age_minutes_long, WidgetLogic.ageMinutes(bgTimestamp, now).toInt())
            val sb = SpannableStringBuilder()
            appendStyled(sb, ageText, amber)
            when (last) {
                BgRange.LOW  -> appendStyled(sb, " · " + gs(R.string.widget_last_low), red)
                BgRange.HIGH -> appendStyled(sb, " · " + gs(R.string.widget_last_high), amber)
                else         -> Unit
            }
            views.setTextViewText(R.id.bg_status, sb)
            views.setContentDescription(R.id.bg, "$bgText, $sb")
            // BG baseline ~99 dp, the age line on the Ø Glukose baseline (~119 dp) at 406 x 206
            views.setViewPadding(R.id.bg_block, 0, dp(11f), 0, 0)
            views.setViewLayoutMargin(R.id.bg_status, RemoteViews.MARGIN_TOP, 0f, TypedValue.COMPLEX_UNIT_DIP)
            return
        }

        // No data: calm dashes, the time of the last value
        bgScale = WidgetLogic.NO_DATA_BG_SCALE
        views.setTextViewText(R.id.bg, SpannableStringBuilder("–\u2009–").also {
            it.setSpan(TypefaceSpan("sans-serif"), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        })
        views.setTextColor(R.id.bg, txt3)
        val since = bgTimestamp?.let { timeOfDay(it, now) }
        val lowLast = last == BgRange.LOW && bgMgdl != null && since != null
        val status = when {
            since == null -> gs(R.string.widget_no_data)
            lowLast       -> gs(R.string.widget_no_data_since_low, since, WidgetLogic.glucose(bgMgdl!!, isMmol()))
            else          -> gs(R.string.widget_no_data_since, since)
        }
        views.setTextViewText(R.id.bg_status, status)
        views.setTextColor(R.id.bg_status, if (lowLast) red else amber)
        views.setContentDescription(R.id.bg, status.replace("\n", " · "))
        // dash baseline 24 dp above the first text line; the (last) text line on the Ø Glukose baseline
        views.setViewPadding(R.id.bg_block, 0, dp(if (lowLast) 0f else 14.5f), 0, 0)
        views.setViewLayoutMargin(R.id.bg_status, RemoteViews.MARGIN_TOP, 4f, TypedValue.COMPLEX_UNIT_DIP)
    }

    /** "01:18" (system 12 / 24 h setting); older than a day with the date */
    private fun timeOfDay(timestamp: Long, now: Long): String {
        val locale = appContext.resources.configuration.locales[0]
        val skeleton = (if (now - timestamp >= 20 * 60 * 60 * 1000L) "dM" else "") + if (DateFormat.is24HourFormat(appContext)) "HHmm" else "hmma"
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(timestamp))
    }

    private fun updateTir(views: RemoteViews, range: BgRange) {
        val stats = statsCache.stats
        val stale = range == BgRange.STALE || range == BgRange.NONE
        // Low: the meters step back (dimmed) so the red BG is the only loud thing
        val low = range == BgRange.LOW
        views.setTextColor(R.id.tir1_label, dimmed(color(R.color.widget_glass_txt2), low))
        views.setTextColor(R.id.tir2_label, dimmed(color(R.color.widget_glass_txt2), low))
        views.setTextColor(R.id.tir3_label, dimmed(color(R.color.widget_glass_txt2), low))
        // 7 / 30 d labels show the days really covered ("15 T" with 15 days of history), the nominal days without data
        val labelTexts = listOf(
            gs(R.string.widget_tir_24h),
            gs(R.string.widget_tir_days, WidgetLogic.meterDays(stats.spanDays, 7)),
            gs(R.string.widget_tir_days, WidgetLogic.meterDays(stats.spanDays, 30))
        )
        views.setTextViewText(R.id.tir2_label, labelTexts[1])
        views.setTextViewText(R.id.tir3_label, labelTexts[2])
        listOf(stats.tir24h, stats.tir7d, stats.tir30d).forEachIndexed { i, pct ->
            val level = WidgetLogic.meterLevel(pct)
            val fillColor = when {
                stale                    -> color(R.color.widget_glass_stale)
                level == MeterLevel.RED    -> color(R.color.widget_glass_low)
                level == MeterLevel.ORANGE -> color(R.color.widget_glass_high)
                else                     -> color(R.color.widget_glass_in)
            }
            views.setInt(meterFills[i], "setColorFilter", fillColor)
            views.setInt(meterFills[i], "setImageAlpha", if (low) LOW_DIM_ALPHA else if (stale) 150 else 255)
            views.setInt(meterFills[i], "setImageLevel", (pct ?: 0).coerceIn(0, 100) * 100)
            views.setTextViewText(meterTexts[i], pct?.let { withUnit(it.toString(), "%", 0.76f) } ?: gs(R.string.widget_value_unknown))
            views.setTextColor(meterTexts[i], dimmed(color(if (stale) R.color.widget_glass_txt2 else R.color.widget_glass_txt1), low))
            views.setContentDescription(meterTexts[i], gs(R.string.widget_tir_description, labelTexts[i], pct?.let { "$it %" } ?: "--"))
        }
    }

    /**
     * 14 day mean glucose and HbA1c (statistics screen numbers). Long-term values: never range coloured, not dimmed when stale.
     * Both rows or none; [applySize] hides them again where the widget is too low.
     */
    private fun updateLongTerm(views: RemoteViews, range: BgRange) {
        val stats = statsCache.stats
        val low = range == BgRange.LOW
        views.setTextColor(R.id.mean_label, dimmed(color(R.color.widget_glass_txt3), low))
        views.setTextColor(R.id.a1c_label, dimmed(color(R.color.widget_glass_txt3), low))
        views.setTextColor(R.id.mean_value, dimmed(color(R.color.widget_glass_txt2), low))
        views.setTextColor(R.id.a1c_value, dimmed(color(R.color.widget_glass_txt2), low))
        val mean = stats.meanMgdl
        val a1c = stats.hbA1c
        val show = WidgetLogic.showLongTerm(mean, a1c, stats.longTermDays)
        setLongTermVisible(views, show)
        if (show && mean != null && a1c != null) {
            val meanText = WidgetLogic.glucose(mean, isMmol())   // "7,1" / "128", no visible unit like the BG
            val unitText = gs(if (isMmol()) app.aaps.core.ui.R.string.mmol else app.aaps.core.ui.R.string.mgdl)
            views.setTextViewText(R.id.mean_value, meanText)
            views.setContentDescription(R.id.mean_row, gs(R.string.widget_mean_glucose_description, meanText, unitText))
            val a1cText = WidgetLogic.decimal(a1c, 1)
            views.setTextViewText(R.id.a1c_value, withUnit(a1cText, "%", 0.76f))
            views.setContentDescription(R.id.a1c_row, gs(R.string.widget_hba1c_description, a1cText))
        }
    }

    /** Group visible: the 5-line history block moves down 5 dp so the air above and below it is equal */
    private fun setLongTermVisible(views: RemoteViews, show: Boolean) {
        views.setViewVisibility(R.id.longterm_group, if (show) View.VISIBLE else View.GONE)
        views.setViewPadding(R.id.history_block, 0, if (show) dp(5f) else 0, 0, 0)
    }

    /** Solid txt3 instead of [c] when [dim] (meter and long-term texts during a low); an alpha dim measured only 1.95:1 */
    private fun dimmed(c: Int, dim: Boolean): Int = if (dim) color(R.color.widget_glass_txt3) else c

    private fun dp(value: Float): Int = Math.round(value * appContext.resources.displayMetrics.density)

    private fun updateBottom(views: RemoteViews, range: BgRange) {
        val stale = range == BgRange.STALE || range == BgRange.NONE
        val unitU = gs(R.string.widget_unit_insulin)
        val iob = iobCobCalculator.calculateIobFromBolus().round().iob + iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
        views.setTextViewText(R.id.iob, withUnit(WidgetLogic.decimal(iob, 2), unitU))
        val cob = iobCobCalculator.getCobInfo("Widget COB").displayCob
        // Unknown (no data): a calm "–" without unit, never "N/A g"
        views.setTextViewText(R.id.cob, cob?.let { withUnit(WidgetLogic.decimal(it, 0), gs(R.string.widget_unit_carbs)) } ?: gs(R.string.widget_value_unknown))
        // Stale: every live value greys out (txt3), not only the BG; otherwise IOB / KH / TDD in the normal txt1
        val valueColor = color(if (stale) R.color.widget_glass_txt3 else R.color.widget_glass_txt1)
        views.setTextColor(R.id.iob, valueColor)
        views.setTextColor(R.id.cob, valueColor)
        val tdd = statsCache.stats.tdd24h
        views.setTextViewText(R.id.tdd, tdd?.let { withUnit(WidgetLogic.decimal(it, 1), unitU) } ?: gs(R.string.widget_value_unknown))
        views.setTextColor(R.id.tdd, valueColor)
    }

    /** Quick-bolus pills or the lock text; returns the lock (null = unlocked or no APS build) */
    private fun updateBolusButtons(context: Context, views: RemoteViews, appWidgetId: Int, bgMgdl: Double?, bgTimestamp: Long?, now: Long): Block? {
        if (!config.APS) {
            views.setViewVisibility(R.id.bolus1, View.GONE)
            views.setViewVisibility(R.id.bolus2, View.GONE)
            views.setViewVisibility(R.id.bolus_locked, View.GONE)
            return null
        }
        // Hint only, from in-memory data: WidgetBolusActivity checks everything again before and at confirmation
        val block = widgetWizard.quickBolusBlock(bgMgdl, bgTimestamp, now)
        // Locked: the pills give way to "Bolus" / "gesperrt · tief"; a tap explains it in full.
        // On stale data with a last low the low reason wins (text only, the block is the same)
        if (block != null) {
            val reason = WidgetLogic.lockReasonShort(block, bgMgdl, bgTimestamp, now)
            val sb = SpannableStringBuilder()
            if (reason == null) appendStyled(sb, gs(R.string.widget_locked), color(R.color.widget_glass_txt2))
            else {
                val reasonText = gs(reason)
                val full = gs(R.string.widget_quick_bolus_locked, reasonText)
                val at = full.lastIndexOf(reasonText)
                appendStyled(sb, full, color(R.color.widget_glass_txt2))
                if (reason == R.string.widget_lock_low && at >= 0) {
                    sb.setSpan(ForegroundColorSpan(color(R.color.widget_glass_low)), at, at + reasonText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(TypefaceSpan("sans-serif-medium"), at, at + reasonText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            views.setTextViewText(R.id.bolus_locked_text, sb)
            val sentence = reason?.let { gs(R.string.widget_bolus_locked_reason, gs(it)) } ?: gs(R.string.widget_bolus_locked)
            views.setContentDescription(R.id.bolus_locked, sentence + ": " + gs(WidgetBolusActivity.blockText(block)))
            // Explanation only: never a bolus confirmation, even when the lock has ended by the time of the tap
            views.setOnClickPendingIntent(R.id.bolus_locked, WidgetBolusActivity.pendingIntent(context, appWidgetId, WidgetBolusActivity.SLOT_EXPLAIN))
            views.setViewVisibility(R.id.bolus_locked, View.VISIBLE)
            views.setViewVisibility(R.id.bolus1, View.GONE)
            views.setViewVisibility(R.id.bolus2, View.GONE)
            return block
        }
        views.setViewVisibility(R.id.bolus_locked, View.GONE)
        val unitU = gs(R.string.widget_unit_insulin)
        listOf(
            Triple(1, R.id.bolus1, IntComposedKey.WidgetBolusAmount1),
            Triple(2, R.id.bolus2, IntComposedKey.WidgetBolusAmount2)
        ).forEach { (slot, id, key) ->
            val amount = WidgetLogic.centiUnitsToUnits(preferences.get(key, appWidgetId))
            val bgId = if (slot == 1) R.id.bolus1_bg else R.id.bolus2_bg
            val textId = if (slot == 1) R.id.bolus1_text else R.id.bolus2_text
            val amountText = WidgetLogic.amountText(amount)
            views.setViewVisibility(id, View.VISIBLE)
            views.setImageViewResource(bgId, R.drawable.widget_pill)
            views.setInt(bgId, "setImageAlpha", 255)
            views.setTextViewText(textId, withUnit("+$amountText", unitU, 0.79f))
            views.setTextColor(textId, color(R.color.widget_glass_txt1))
            views.setContentDescription(id, gs(R.string.widget_bolus_request, amountText))
            // WidgetBolusActivity checks every lock again before and at the confirmation
            views.setOnClickPendingIntent(id, WidgetBolusActivity.pendingIntent(context, appWidgetId, slot))
        }
        return null
    }

    /**
     * Calculator pill: opens the AAPS bolus wizard (via WidgetWizardActivity). Disabled (dimmed like a locked pill) whenever
     * the quick bolus is locked, for every reason ([bolusBlock]), or the wizard button's own conditions fail. A tap on a
     * pill disabled by the quick-bolus lock only explains the lock (WidgetBolusActivity, explanation mode); one disabled by
     * the wizard conditions lets MainActivity say why. WidgetWizard checks both again before the wizard opens. No dosing change.
     */
    private fun updateCarbsButton(context: Context, views: RemoteViews, appWidgetId: Int, bolusBlock: Block?) {
        if (!config.APS) {
            views.setViewVisibility(R.id.carbs, View.GONE)
            return
        }
        val wizardBlock = widgetWizard.block()
        val pill = WidgetLogic.calcPill(bolusBlock, wizardBlock)
        val enabled = pill == CalcPill.OPEN
        views.setInt(R.id.carbs_bg, "setImageLevel", 5000)
        views.setImageViewResource(R.id.carbs_bg, if (enabled) R.drawable.widget_pill else R.drawable.widget_pill_disabled)
        views.setInt(R.id.carbs_bg, "setImageAlpha", if (enabled) 255 else 150)
        views.setInt(R.id.carbs_icon, "setColorFilter", if (enabled) color(R.color.widget_glass_txt2) else DISABLED_TEXT_COLOR)
        views.setInt(R.id.carbs_icon, "setImageAlpha", if (enabled) 255 else 150)
        when (pill) {
            CalcPill.BOLUS_LOCKED   -> {
                val reason = gs(WidgetBolusActivity.blockText(bolusBlock!!))
                views.setContentDescription(R.id.carbs, gs(R.string.widget_calc_locked, reason))
                views.setOnClickPendingIntent(R.id.carbs, WidgetBolusActivity.pendingIntent(context, appWidgetId, WidgetBolusActivity.SLOT_EXPLAIN))
            }

            CalcPill.WIZARD_BLOCKED -> {
                views.setContentDescription(R.id.carbs, gs(R.string.widget_calc_locked, gs(WidgetWizard.blockText(wizardBlock!!))))
                // MainActivity only says why (WidgetWizard.openIfRequested)
                views.setOnClickPendingIntent(R.id.carbs, WidgetWizardActivity.pendingIntent(context, appWidgetId))
            }

            CalcPill.OPEN           -> {
                views.setContentDescription(R.id.carbs, gs(R.string.widget_carbs_description))
                views.setOnClickPendingIntent(R.id.carbs, WidgetWizardActivity.pendingIntent(context, appWidgetId))
            }
        }
    }

    /**
     * One layout per size the launcher may show (portrait / landscape), so the BG is as big as its row allows
     * and the carbs pill only shows where the bottom row has room. Without a size list: the smaller size.
     */
    private fun sized(views: RemoteViews, appWidgetManager: AppWidgetManager, appWidgetId: Int): RemoteViews {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        @Suppress("DEPRECATION")
        val sizes = options?.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }
        if (sizes.isNullOrEmpty()) {
            val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
            // widget_info.xml minimum size when the launcher tells nothing
            applySize(views, if (w > 0) w.toFloat() else 360f, if (h > 0) h.toFloat() else 170f)
            return views
        }
        if (sizes.size == 1) return views.also { applySize(it, sizes[0].width, sizes[0].height) }
        return RemoteViews(sizes.distinct().take(16).associateWith { size -> RemoteViews(views).also { applySize(it, size.width, size.height) } })
    }

    private fun applySize(views: RemoteViews, widthDp: Float, heightDp: Float) {
        val bgDp = WidgetLogic.bgTextSizeDp(widthDp, heightDp)
        val scale = bgDp / WidgetLogic.BG_BASE_DP
        views.setTextViewTextSize(R.id.bg, TypedValue.COMPLEX_UNIT_DIP, bgDp * bgScale)
        views.setTextViewTextSize(R.id.age, TypedValue.COMPLEX_UNIT_DIP, 12f * scale)
        views.setViewLayoutWidth(R.id.arrow, 22f * scale, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutHeight(R.id.arrow, 22f * scale, TypedValue.COMPLEX_UNIT_DIP)
        if (config.APS) {
            val carbs = WidgetLogic.showCarbsPill(widthDp)
            views.setViewVisibility(R.id.carbs, if (carbs) View.VISIBLE else View.GONE)
        }
        // Too low for the two long-term rows: hide them, the meters stay centred
        if (!WidgetLogic.longTermFits(heightDp)) setLongTermVisible(views, false)
    }

    /** Appends [text] in [color], optionally smaller and medium weight (spans that survive RemoteViews) */
    private fun appendStyled(sb: SpannableStringBuilder, text: String, color: Int, relativeSize: Float = 1f, medium: Boolean = false) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (relativeSize != 1f) sb.setSpan(RelativeSizeSpan(relativeSize), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (medium) sb.setSpan(TypefaceSpan("sans-serif-medium"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** "3,85 U": value in the view's colour, unit smaller and quieter */
    private fun withUnit(value: String, unit: String, relativeSize: Float = 0.69f, unitColor: Int? = null): CharSequence =
        SpannableStringBuilder(value).also {
            val start = it.length
            it.append(if (unit == "%") " %" else " $unit")
            it.setSpan(RelativeSizeSpan(relativeSize), start, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            it.setSpan(ForegroundColorSpan(unitColor ?: color(R.color.widget_glass_txt3)), start, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
}
