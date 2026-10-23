package app.aaps.ui.widget

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.TrendArrow
import app.aaps.ui.R
import java.util.Locale
import kotlin.math.abs

/**
 * Pure (Android free) logic of the home-screen widget, unit tested in WidgetLogicTest.
 * Nothing here feeds the loop, dosing or limits: it only decides what the widget shows
 * and whether the quick-bolus buttons may open the confirmation.
 */
object WidgetLogic {

    /** Time in range limits of the AAPS statistics screen (TirCalculatorImpl.stats). 3.9 / 10.0 mmol/L */
    const val TIR_LOW_MGDL = Constants.STATS_RANGE_LOW_MMOL * Constants.MMOLL_TO_MGDL
    const val TIR_HIGH_MGDL = Constants.STATS_RANGE_HIGH_MMOL * Constants.MMOLL_TO_MGDL

    /** BG below 3.9 mmol/L locks the bolus buttons */
    const val LOW_MGDL = TIR_LOW_MGDL

    /** Data older than this is stale (same minute as the watch faces flip to STALE) */
    const val STALE_MS = 12 * 60 * 1000L

    /** A reading this far in the future is not trusted */
    const val FUTURE_TOLERANCE_MS = 60 * 1000L

    /** Any bolus younger than this blocks a widget bolus (double tap, watch + widget) */
    const val RECENT_BOLUS_MS = 60 * 1000L

    /** The confirmation must be answered within this time */
    const val CONFIRM_TTL_MS = 60 * 1000L

    /** Configurable range of a quick-bolus button in U (constraints are applied on top at confirm time) */
    const val MIN_AMOUNT = 0.05
    const val MAX_AMOUNT = 3.0

    const val GOAL_PCT = 90

    enum class MeterLevel { RED, ORANGE, GREEN, NONE }

    /** red < 70, orange 70..89, green >= 90 of the displayed (rounded) percentage */
    fun meterLevel(pct: Int?): MeterLevel = when {
        pct == null -> MeterLevel.NONE
        pct >= GOAL_PCT -> MeterLevel.GREEN
        pct >= 70 -> MeterLevel.ORANGE
        else -> MeterLevel.RED
    }

    enum class BgRange { IN, HIGH, LOW, STALE, NONE }

    fun bgRange(bgMgdl: Double?, bgTimestamp: Long?, now: Long): BgRange = when {
        bgMgdl == null || bgTimestamp == null -> BgRange.NONE
        isStale(bgTimestamp, now) -> BgRange.STALE
        bgMgdl < LOW_MGDL -> BgRange.LOW
        bgMgdl > TIR_HIGH_MGDL -> BgRange.HIGH
        else -> BgRange.IN
    }

    fun isStale(bgTimestamp: Long?, now: Long): Boolean =
        bgTimestamp == null || now - bgTimestamp >= STALE_MS || bgTimestamp - now > FUTURE_TOLERANCE_MS

    /**
     * Counts of a time in range, same rule as TirCalculatorImpl:
     * value < 39 error (not counted), < low below, low..high in range, > high above.
     */
    data class TirCounts(val below: Int = 0, val inRange: Int = 0, val above: Int = 0) {

        val count get() = below + inRange + above

        operator fun plus(other: TirCounts) = TirCounts(below + other.below, inRange + other.inRange, above + other.above)

        /** Same as TirImpl.inRangePct(): 100 - below% - above% */
        fun inRangePct(): Double? =
            if (count > 0) 100.0 - below.toDouble() / count * 100.0 - above.toDouble() / count * 100.0 else null

        /** Rounded like the statistics screen does it (formatPercent "%1$.0f%%") */
        fun displayPct(): Int? = inRangePct()?.let { String.format(Locale.US, "%.0f", it).toInt() }
    }

    fun countTir(valuesMgdl: Iterable<Double>, lowMgdl: Double = TIR_LOW_MGDL, highMgdl: Double = TIR_HIGH_MGDL): TirCounts {
        var below = 0
        var inRange = 0
        var above = 0
        for (v in valuesMgdl) {
            if (v >= 39 && v < lowMgdl) below++
            if (v in lowMgdl..highMgdl) inRange++
            if (v > highMgdl) above++
        }
        return TirCounts(below, inRange, above)
    }

    /** Rolling window: readings with timestamp in (now - 24 h, now] */
    fun rolling24hStart(now: Long): Long = now - 24 * 60 * 60 * 1000L

    // ---------------------------------------------------------------- formatting (German decimal comma)

    fun decimal(value: Double, decimals: Int): String =
        String.format(Locale.GERMANY, "%.${decimals}f", value).let { if (it.matches(Regex("-0(,0*)?"))) it.substring(1) else it }

    /** Glucose in the user's unit: mmol/L with 1 decimal, mg/dL without */
    fun glucose(mgdl: Double, mmol: Boolean): String =
        if (mmol) decimal(mgdl * Constants.MGDL_TO_MMOLL, 1) else decimal(mgdl, 0)

    /** Whole minutes since the reading, floored; never negative */
    fun ageMinutes(bgTimestamp: Long, now: Long): Long = ((now - bgTimestamp).coerceAtLeast(0)) / 60000L

    /** Quick-bolus label: "0,5", "1,0", "0,05" */
    fun amountText(u: Double): String =
        if (abs(u * 10 - Math.round(u * 10)) < 0.001) decimal(u, 1) else decimal(u, 2)

    /** Lightest tint the slider allows: widget_glass_tint.xml top 85 % x 0.36 = 30 %, the recipe's hard floor */
    const val TINT_FLOOR = 0.36

    /** On a bright wallpaper the tint never goes below this, otherwise the secondary labels lose their contrast */
    private const val DENSE_TINT = 0.72

    /**
     * Strength (0..1) of the glass smoke tint for the WidgetOpacity setting (0..255), linear from [TINT_FLOOR] to 1.
     * widget_glass_tint.xml runs 85 % -> 100 %, so the default setting (25) gives a light smoke of 36 % -> 42 %
     * (R2 had 44 % -> 56 %): more wallpaper shows through, the text shadows keep the contrast.
     */
    fun glassTintFraction(opacity: Int, brightWallpaper: Boolean = false): Double {
        val fraction = TINT_FLOOR + (1.0 - TINT_FLOOR) * opacity.coerceIn(0, 255) / 255.0
        return if (brightWallpaper) maxOf(fraction, DENSE_TINT) else fraction
    }

    /** Alpha (0..255) for setImageAlpha of the tint layer */
    fun glassTintAlpha(opacity: Int, brightWallpaper: Boolean = false): Int =
        Math.round(glassTintFraction(opacity, brightWallpaper) * 255).toInt()

    /** Tint strength shown next to the configuration slider, "42 %" */
    fun glassTintPercent(opacity: Int): Int = Math.round(glassTintFraction(opacity) * 100).toInt()

    // ---------------------------------------------------------------- trend arrow

    /**
     * Arrow to show: the one the overview shows (TrendCalculator on the bucketed data), or, while that is not
     * available (no bucketed data yet, NONE), the arrow delivered with the newest glucose value.
     * null = no arrow. TRIPLE_* / NONE have no arrow icon (the overview shows its "invalid" icon there).
     */
    fun trendArrow(calculated: TrendArrow?, lastBgArrow: TrendArrow?): TrendArrow? =
        listOf(calculated, lastBgArrow).firstOrNull { it != null && arrowIcon(it) != null }

    /** Widget arrow drawables: white fill, tinted in Widget.kt (the core icons use a theme attribute the launcher cannot resolve) */
    fun arrowIcon(arrow: TrendArrow): Int? = when (arrow) {
        TrendArrow.DOUBLE_DOWN     -> R.drawable.widget_arrow_double_down
        TrendArrow.SINGLE_DOWN     -> R.drawable.widget_arrow_single_down
        TrendArrow.FORTY_FIVE_DOWN -> R.drawable.widget_arrow_forty_five_down
        TrendArrow.FLAT            -> R.drawable.widget_arrow_flat
        TrendArrow.FORTY_FIVE_UP   -> R.drawable.widget_arrow_forty_five_up
        TrendArrow.SINGLE_UP       -> R.drawable.widget_arrow_single_up
        TrendArrow.DOUBLE_UP       -> R.drawable.widget_arrow_double_up
        TrendArrow.TRIPLE_DOWN, TrendArrow.TRIPLE_UP, TrendArrow.NONE -> null
    }

    fun centiUnitsToUnits(cu: Int): Double = cu / 100.0

    fun unitsToCentiUnits(u: Double): Int = Math.round(u * 100).toInt()

    fun clampAmount(u: Double): Double = u.coerceIn(MIN_AMOUNT, MAX_AMOUNT)

    // ---------------------------------------------------------------- bolus lock

    enum class Block {
        NOT_SUPPORTED,      // AAPSClient / no APS build
        NOT_INITIALIZED,    // app still starting
        SCREEN_LOCKED,
        PUMP_NOT_READY,
        SUSPENDED,          // loop suspended, pump disconnected or suspended, super bolus
        BOLUS_RUNNING,
        NO_BG,
        STALE,
        LOW,
        RECENT_BOLUS,
        INVALID_AMOUNT,
        CONSTRAINED_TO_ZERO
    }

    data class BolusInput(
        val aps: Boolean,
        val appInitialized: Boolean,
        val pumpInitialized: Boolean,
        val suspended: Boolean,
        val bolusInQueue: Boolean,
        val bgMgdl: Double?,
        val bgTimestamp: Long?,
        val now: Long,
        val screenLocked: Boolean = false,
        /** timestamp of the newest valid NORMAL bolus, null if unknown (widget refresh does not read the DB) */
        val lastBolusAt: Long? = null,
        /** requested amount, null if not checked (widget refresh) */
        val amount: Double? = null,
        /** amount after constraints, null if not checked (widget refresh) */
        val amountAfterConstraints: Double? = null
    )

    /** First match wins. null = bolus may be offered */
    fun bolusBlock(i: BolusInput): Block? = when {
        !i.aps -> Block.NOT_SUPPORTED
        !i.appInitialized -> Block.NOT_INITIALIZED
        i.screenLocked -> Block.SCREEN_LOCKED
        !i.pumpInitialized -> Block.PUMP_NOT_READY
        i.suspended -> Block.SUSPENDED
        i.bolusInQueue -> Block.BOLUS_RUNNING
        i.bgMgdl == null || i.bgTimestamp == null -> Block.NO_BG
        isStale(i.bgTimestamp, i.now) -> Block.STALE
        i.bgMgdl < LOW_MGDL -> Block.LOW
        i.lastBolusAt != null && abs(i.now - i.lastBolusAt) < RECENT_BOLUS_MS -> Block.RECENT_BOLUS
        i.amount != null && (i.amount.isNaN() || i.amount < MIN_AMOUNT - 0.0001 || i.amount > MAX_AMOUNT + 0.0001) -> Block.INVALID_AMOUNT
        i.amountAfterConstraints != null && (i.amountAfterConstraints.isNaN() || i.amountAfterConstraints <= 0.0) -> Block.CONSTRAINED_TO_ZERO
        else -> null
    }

    enum class Refusal { EXPIRED, CHANGED }

    /**
     * Extra checks at the moment OK is pressed (after [bolusBlock] passed on fresh data).
     * @param shownAt elapsedRealtime when the confirmation was shown
     */
    fun acceptRefusal(
        shownAt: Long, nowElapsed: Long,
        shownAmount: Double, freshAmount: Double,
        shownNewestBolus: Pair<Long, Long>?, freshNewestBolus: Pair<Long, Long>?
    ): Refusal? = when {
        nowElapsed - shownAt !in 0..CONFIRM_TTL_MS -> Refusal.EXPIRED
        abs(shownAmount - freshAmount) >= 0.001 -> Refusal.CHANGED
        shownNewestBolus != freshNewestBolus -> Refusal.CHANGED
        else -> null
    }
}
