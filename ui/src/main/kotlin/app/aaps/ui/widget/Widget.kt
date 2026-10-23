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
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.round
import app.aaps.ui.R
import app.aaps.ui.widget.WidgetLogic.BgRange
import app.aaps.ui.widget.WidgetLogic.MeterLevel
import dagger.android.HasAndroidInjector
import javax.inject.Inject

/**
 * Home-screen widget, design R2 "Luft" (glass card).
 * Shows BG + trend + data age, time in range 24 h / 7 d / 30 d, IOB / COB / TDD and two quick-bolus pills.
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
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var statsCache: WidgetStatsCache
    @Inject lateinit var widgetWizard: WidgetWizard

    companion object {

        // This object doesn't behave like singleton,
        // many threads were created. Making handler static resolve this issue
        private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

        fun updateWidget(context: Context, from: String) {
            context.sendBroadcast(Intent().also {
                it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, Widget::class.java)))
                it.putExtra("from", from)
                it.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            })
        }

        private val meterFills = intArrayOf(R.id.tir1_fill, R.id.tir2_fill, R.id.tir3_fill)
        private val meterTexts = intArrayOf(R.id.tir1, R.id.tir2, R.id.tir3)
    }

    private val intentAction = "OpenApp"

    /** Application context for the widget texts, see [gs] */
    private lateinit var appContext: Context

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
                updateHeader(views, now)
                updateBg(views, range, bg?.recalculated, bg?.timestamp, bg?.trendArrow, now)
                updateTir(views, range)
                updateBottom(views, range)
                updateBolusButtons(context, views, appWidgetId, bg?.recalculated, bg?.timestamp, now)
                updateCarbsButton(context, views, appWidgetId)
                // Instruct the widget manager to update the widget
                appWidgetManager.updateAppWidget(appWidgetId, sized(views, appWidgetManager, appWidgetId))
            }
        }
    }

    private fun isMmol() = profileFunction.getUnits() == GlucoseUnit.MMOL

    private fun updateHeader(views: RemoteViews, now: Long) {
        views.setTextViewText(R.id.profile, profileFunction.getProfileName())
        val targetMgdl = persistenceLayer.getTemporaryTargetActiveAt(now)?.let { (it.lowTarget + it.highTarget) / 2.0 }
            ?: profileFunction.getProfile()?.getTargetMgdl()
        if (targetMgdl != null) {
            views.setTextViewText(R.id.target, gs(R.string.widget_target, WidgetLogic.glucose(targetMgdl, isMmol())))
            views.setViewVisibility(R.id.target, View.VISIBLE)
        } else views.setViewVisibility(R.id.target, View.GONE)
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
        val col = rangeColor(range)
        views.setTextViewText(R.id.bg, bgMgdl?.let { WidgetLogic.glucose(it, isMmol()) } ?: gs(app.aaps.core.ui.R.string.value_unavailable_short))
        views.setTextColor(R.id.bg, col)

        // Same arrow as the overview; the newest glucose value's own arrow while the bucketed data is not there yet
        val arrow = if (range == BgRange.STALE || range == BgRange.NONE) null
        else WidgetLogic.trendArrow(trendCalculator.getTrendArrow(iobCobCalculator.ads), lastBgArrow)
        val arrowIcon = arrow?.let { WidgetLogic.arrowIcon(it) }
        if (arrowIcon != null) {
            views.setImageViewResource(R.id.arrow, arrowIcon)
            views.setInt(R.id.arrow, "setColorFilter", col)
            views.setViewVisibility(R.id.arrow, View.VISIBLE)
        } else views.setViewVisibility(R.id.arrow, View.GONE)

        val age = bgTimestamp?.let { WidgetLogic.ageMinutes(it, now) }
        views.setTextViewText(
            R.id.age, when {
                age == null -> ""
                age < 1     -> gs(R.string.widget_age_now)
                age < 60    -> gs(R.string.widget_age_min, age.toInt())
                else        -> gs(R.string.widget_age_hours, (age / 60).toInt())
            }
        )
        views.setTextColor(R.id.age, color(if (range == BgRange.STALE) R.color.widget_glass_high else R.color.widget_glass_txt2))

        when (range) {
            BgRange.IN                  -> views.setImageViewResource(R.id.bloom, R.drawable.widget_glass_bloom_in)
            BgRange.HIGH                -> views.setImageViewResource(R.id.bloom, R.drawable.widget_glass_bloom_high)
            BgRange.STALE, BgRange.NONE -> views.setImageViewResource(R.id.bloom, R.drawable.widget_glass_bloom_stale)
            BgRange.LOW                 -> Unit // no red glow under red digits
        }
        views.setViewVisibility(R.id.bloom, if (range == BgRange.LOW) View.GONE else View.VISIBLE)
    }

    private fun updateTir(views: RemoteViews, range: BgRange) {
        val stats = statsCache.stats
        val stale = range == BgRange.STALE || range == BgRange.NONE
        val labels = intArrayOf(R.string.widget_tir_24h, R.string.widget_tir_7d, R.string.widget_tir_30d)
        listOf(stats.tir24h, stats.tir7d, stats.tir30d).forEachIndexed { i, pct ->
            val level = WidgetLogic.meterLevel(pct)
            val fillColor = when {
                stale                    -> color(R.color.widget_glass_stale)
                level == MeterLevel.RED    -> color(R.color.widget_glass_low)
                level == MeterLevel.ORANGE -> color(R.color.widget_glass_high)
                else                     -> color(R.color.widget_glass_in)
            }
            views.setInt(meterFills[i], "setColorFilter", fillColor)
            views.setInt(meterFills[i], "setImageAlpha", if (stale) 150 else 255)
            views.setInt(meterFills[i], "setImageLevel", (pct ?: 0).coerceIn(0, 100) * 100)
            views.setTextViewText(meterTexts[i], withUnit(pct?.toString() ?: gs(app.aaps.core.ui.R.string.value_unavailable_short), "%", 0.76f))
            views.setTextColor(meterTexts[i], color(if (stale) R.color.widget_glass_txt2 else R.color.widget_glass_txt1))
            views.setContentDescription(meterTexts[i], gs(R.string.widget_tir_description, gs(labels[i]), pct?.let { "$it %" } ?: "--"))
        }
    }

    private fun updateBottom(views: RemoteViews, range: BgRange) {
        val stale = range == BgRange.STALE || range == BgRange.NONE
        val unitU = gs(R.string.widget_unit_insulin)
        val iob = iobCobCalculator.calculateIobFromBolus().round().iob + iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
        views.setTextViewText(R.id.iob, withUnit(WidgetLogic.decimal(iob, 2), unitU))
        val cob = iobCobCalculator.getCobInfo("Widget COB").displayCob
        views.setTextViewText(R.id.cob, withUnit(cob?.let { WidgetLogic.decimal(it, 0) } ?: gs(app.aaps.core.ui.R.string.value_unavailable_short), gs(R.string.widget_unit_carbs)))
        val valueColor = color(if (stale) R.color.widget_glass_txt2 else R.color.widget_glass_txt1)
        views.setTextColor(R.id.iob, valueColor)
        views.setTextColor(R.id.cob, valueColor)
        // TDD is history, not "now": one step quieter
        val tdd = statsCache.stats.tdd24h
        views.setTextViewText(R.id.tdd, withUnit(tdd?.let { WidgetLogic.decimal(it, 1) } ?: gs(app.aaps.core.ui.R.string.value_unavailable_short), unitU))
        views.setTextColor(R.id.tdd, color(R.color.widget_glass_txt2))
    }

    private fun updateBolusButtons(context: Context, views: RemoteViews, appWidgetId: Int, bgMgdl: Double?, bgTimestamp: Long?, now: Long) {
        if (!config.APS) {
            views.setViewVisibility(R.id.bolus1, View.GONE)
            views.setViewVisibility(R.id.bolus2, View.GONE)
            return
        }
        // Hint only, from in-memory data: WidgetBolusActivity checks everything again before and at confirmation
        val block = WidgetLogic.bolusBlock(
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
            views.setImageViewResource(bgId, if (block == null) R.drawable.widget_pill else R.drawable.widget_pill_disabled)
            views.setTextViewText(textId, withUnit("+$amountText", unitU, 0.79f, if (block == null) null else 0x6696A2BE))
            views.setTextColor(textId, if (block == null) color(R.color.widget_glass_txt1) else 0x8096A2BE.toInt())
            views.setContentDescription(
                id,
                if (block == null) gs(R.string.widget_bolus_request, amountText)
                else gs(R.string.widget_bolus_locked) + ": " + gs(WidgetBolusActivity.blockText(block))
            )
            // Also when locked: the activity then only explains why (never offers OK)
            views.setOnClickPendingIntent(id, WidgetBolusActivity.pendingIntent(context, appWidgetId, slot))
        }
    }

    /** Carbs pill: opens the AAPS bolus wizard (via WidgetWizardActivity). Not locked on a low BG, only by the wizard button's own conditions. */
    private fun updateCarbsButton(context: Context, views: RemoteViews, appWidgetId: Int) {
        if (!config.APS) {
            views.setViewVisibility(R.id.carbs, View.GONE)
            return
        }
        val block = widgetWizard.block()
        views.setInt(R.id.carbs_bg, "setImageLevel", 5000)
        views.setImageViewResource(R.id.carbs_bg, if (block == null) R.drawable.widget_pill else R.drawable.widget_pill_disabled)
        views.setTextColor(R.id.carbs_text, if (block == null) color(R.color.widget_glass_txt2) else 0x8096A2BE.toInt())
        views.setContentDescription(
            R.id.carbs,
            if (block == null) rh.gs(R.string.widget_carbs_description)
            else rh.gs(R.string.widget_wizard_not_opened, rh.gs(WidgetWizard.blockText(block)))
        )
        // Also when blocked: MainActivity then only says why
        views.setOnClickPendingIntent(R.id.carbs, WidgetWizardActivity.pendingIntent(context, appWidgetId))
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
        views.setTextViewTextSize(R.id.bg, TypedValue.COMPLEX_UNIT_DIP, bgDp)
        views.setTextViewTextSize(R.id.age, TypedValue.COMPLEX_UNIT_DIP, 12f * scale)
        views.setViewLayoutWidth(R.id.arrow, 22f * scale, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutHeight(R.id.arrow, 22f * scale, TypedValue.COMPLEX_UNIT_DIP)
        if (config.APS) views.setViewVisibility(R.id.carbs, if (WidgetLogic.showCarbsPill(widthDp, rh.gs(R.string.widget_carbs_pill).length)) View.VISIBLE else View.GONE)
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
