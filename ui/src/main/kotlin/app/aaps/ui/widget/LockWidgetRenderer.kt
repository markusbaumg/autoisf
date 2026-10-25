package app.aaps.ui.widget

import android.app.PendingIntent
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.aaps.ui.R
import app.aaps.ui.widget.LockWidgetLogic.Arrow
import app.aaps.ui.widget.LockWidgetLogic.Model
import app.aaps.ui.widget.LockWidgetLogic.Palette
import app.aaps.ui.widget.LockWidgetLogic.PaletteKind
import app.aaps.ui.widget.LockWidgetLogic.Pill
import app.aaps.ui.widget.LockWidgetLogic.Run
import app.aaps.ui.widget.LockWidgetLogic.Texts
import app.aaps.ui.widget.LockWidgetLogic.Tier
import app.aaps.ui.widget.LockWidgetLogic.Weight
import kotlin.math.roundToInt

/**
 * Builds the RemoteViews of the lock-screen widget from a [Model] (KUSI: lock-widget). Display only.
 * One layout per tier and palette (the text shadow is an XML attribute): widget_lock_{l,m,s}_{light,ink}.
 */
object LockWidgetRenderer {

    fun layout(tier: Tier, kind: PaletteKind): Int = when (kind) {
        PaletteKind.LIGHT -> when (tier) {
            Tier.L -> R.layout.widget_lock_l_light
            Tier.M -> R.layout.widget_lock_m_light
            Tier.S -> R.layout.widget_lock_s_light
        }

        PaletteKind.INK   -> when (tier) {
            Tier.L -> R.layout.widget_lock_l_ink
            Tier.M -> R.layout.widget_lock_m_ink
            Tier.S -> R.layout.widget_lock_s_ink
        }
    }

    /** Texts in the system language (like Widget.gs), not the AAPS language setting */
    fun texts(ctx: Context): Texts = Texts(
        pillLow = ctx.getString(R.string.lock_widget_pill_low),
        pillHigh = ctx.getString(R.string.lock_widget_pill_high),
        pillStale = ctx.getString(R.string.lock_widget_pill_stale),
        pillNoData = ctx.getString(R.string.lock_widget_pill_no_data),
        now = ctx.getString(R.string.lock_widget_now),
        ago = ctx.getString(R.string.lock_widget_ago),
        iobLabel = ctx.getString(R.string.lock_widget_iob),
        insulin = ctx.getString(R.string.lock_widget_insulin),
        lastLow = ctx.getString(R.string.lock_widget_last_low),
        lastHigh = ctx.getString(R.string.lock_widget_last_high),
        lastIn = ctx.getString(R.string.lock_widget_last_in),
        since = ctx.getString(R.string.lock_widget_since),
        loopStalledShort = ctx.getString(R.string.lock_widget_loop_stalled_short),
        loopLate = ctx.getString(R.string.lock_widget_loop_late),
        loopStalled = ctx.getString(R.string.lock_widget_loop_stalled),
        loopOff = ctx.getString(R.string.lock_widget_loop_off),
        tempTarget = ctx.getString(R.string.lock_widget_temp_target),
        tempTargetNoEnd = ctx.getString(R.string.lock_widget_temp_target_no_end),
        basalLabel = ctx.getString(R.string.lock_widget_basal),
        basalZeroLow = ctx.getString(R.string.lock_widget_basal_zero_low),
        basalZero = ctx.getString(R.string.lock_widget_basal_zero),
        basalRate = ctx.getString(R.string.lock_widget_basal_rate),
        carbsLabel = ctx.getString(R.string.lock_widget_carbs),
        carbs = ctx.getString(R.string.lock_widget_carbs_value),
        tirLabel = ctx.getString(R.string.lock_widget_tir),
        percent = ctx.getString(R.string.lock_widget_percent)
    )

    private fun arrowIcon(a: Arrow): Int = when (a) {
        Arrow.FLAT   -> R.drawable.widget_lock_arrow_flat
        Arrow.UP45   -> R.drawable.widget_lock_arrow_up45
        Arrow.UP     -> R.drawable.widget_lock_arrow_up
        Arrow.DOWN45 -> R.drawable.widget_lock_arrow_down45
        Arrow.DOWN   -> R.drawable.widget_lock_arrow_down
        Arrow.UP2    -> R.drawable.widget_lock_arrow_up2
        Arrow.DOWN2  -> R.drawable.widget_lock_arrow_down2
    }

    private fun haloIcon(a: Arrow): Int = when (a) {
        Arrow.FLAT   -> R.drawable.widget_lock_arrow_halo_flat
        Arrow.UP45   -> R.drawable.widget_lock_arrow_halo_up45
        Arrow.UP     -> R.drawable.widget_lock_arrow_halo_up
        Arrow.DOWN45 -> R.drawable.widget_lock_arrow_halo_down45
        Arrow.DOWN   -> R.drawable.widget_lock_arrow_halo_down
        Arrow.UP2    -> R.drawable.widget_lock_arrow_halo_up2
        Arrow.DOWN2  -> R.drawable.widget_lock_arrow_halo_down2
    }

    private fun pillBackground(pill: Pill, kind: PaletteKind): Int {
        val ink = kind == PaletteKind.INK
        return when (pill) {
            Pill.TIEF        -> if (ink) R.drawable.widget_lock_pill_tief_ink else R.drawable.widget_lock_pill_tief
            Pill.TIEF_URGENT -> if (ink) R.drawable.widget_lock_pill_tief_urgent_ink else R.drawable.widget_lock_pill_tief_urgent
            Pill.HOCH        -> if (ink) R.drawable.widget_lock_pill_hoch_ink else R.drawable.widget_lock_pill_hoch
            Pill.ALT         -> if (ink) R.drawable.widget_lock_pill_alt_ink else R.drawable.widget_lock_pill_alt
            Pill.NO_DATA     -> if (ink) R.drawable.widget_lock_pill_nodata_ink else R.drawable.widget_lock_pill_nodata
        }
    }

    fun build(
        ctx: Context, model: Model, tier: Tier, palette: Palette, texts: Texts,
        scrimAlpha: Int, fontScale: Float, click: PendingIntent
    ): RemoteViews {
        val views = RemoteViews(ctx.packageName, layout(tier, palette.kind))
        val spec = LockWidgetLogic.spec(tier)
        val density = ctx.resources.displayMetrics.density
        fun px(dp: Float) = (dp * density).roundToInt()
        val light = palette.kind == PaletteKind.LIGHT

        views.setOnClickPendingIntent(R.id.lock_root, click)
        views.setContentDescription(R.id.lock_root, model.plain(texts))
        if (light) views.setInt(R.id.lock_scrim, "setImageAlpha", scrimAlpha)

        // Pill
        val pill = model.pill
        if (pill != null) {
            val urgent = pill == Pill.TIEF_URGENT
            val ring = if (urgent) LockWidgetLogic.URGENT_RING_DP else 0f
            val padV = if (urgent) (if (tier == Tier.S) spec.pillPadV else maxOf(spec.pillPadV, 4f)) + ring else spec.pillPadV
            views.setTextViewText(R.id.lock_pill, LockWidgetLogic.pillText(pill, texts))
            views.setTextColor(R.id.lock_pill, LockWidgetLogic.pillTextColor(pill, palette))
            views.setInt(R.id.lock_pill, "setBackgroundResource", pillBackground(pill, palette.kind))
            views.setTextViewTextSize(
                R.id.lock_pill, TypedValue.COMPLEX_UNIT_DIP,
                LockWidgetLogic.secondarySizeDp(if (urgent) LockWidgetLogic.PILL_URGENT_SP else LockWidgetLogic.PILL_SP, fontScale)
            )
            views.setViewPadding(R.id.lock_pill, px(spec.pillPadH + ring), px(padV), px(spec.pillPadH + ring), px(padV))
            // the ring belongs to the view, so the gap to the digits stays as rendered
            views.setViewLayoutMargin(R.id.lock_pill, RemoteViews.MARGIN_END, spec.pillGap - ring, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewVisibility(R.id.lock_pill, View.VISIBLE)
        } else views.setViewVisibility(R.id.lock_pill, View.GONE)

        // Hero
        views.setTextViewText(R.id.lock_bg, model.bg)
        views.setTextColor(R.id.lock_bg, model.bgColor)
        views.setTextViewTextSize(R.id.lock_bg, TypedValue.COMPLEX_UNIT_DIP, LockWidgetLogic.heroSizeDp(tier, fontScale))

        // Arrow (tinted in the hero colour) + untinted halo (light palette only)
        val arrow = model.arrow
        if (arrow != null) {
            views.setImageViewResource(R.id.lock_arrow, arrowIcon(arrow))
            views.setInt(R.id.lock_arrow, "setColorFilter", model.bgColor)
            if (light) views.setImageViewResource(R.id.lock_arrow_halo, haloIcon(arrow))
            views.setViewVisibility(R.id.lock_arrow_box, View.VISIBLE)
        } else views.setViewVisibility(R.id.lock_arrow_box, View.GONE)

        setLine(views, R.id.lock_meta, model.meta, spec.metaSp, fontScale)
        setLine(views, R.id.lock_line3, model.line3, spec.line3Sp, fontScale)
        setLine(views, R.id.lock_line4, model.line4, spec.line4Sp, fontScale)
        return views
    }

    private fun setLine(views: RemoteViews, id: Int, runs: List<Run>?, sp: Float, fontScale: Float) {
        if (runs.isNullOrEmpty()) {
            views.setViewVisibility(id, View.GONE)
            return
        }
        views.setTextViewText(id, spans(runs))
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, LockWidgetLogic.secondarySizeDp(sp, fontScale))
        views.setViewVisibility(id, View.VISIBLE)
    }

    /** One colour and weight per run (spans that survive RemoteViews) */
    fun spans(runs: List<Run>): CharSequence {
        val sb = SpannableStringBuilder()
        for (r in runs) {
            val start = sb.length
            sb.append(r.text)
            sb.setSpan(ForegroundColorSpan(r.color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.weight == Weight.MEDIUM) sb.setSpan(TypefaceSpan("sans-serif-medium"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
