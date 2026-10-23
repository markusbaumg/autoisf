package app.aaps.ui.widget

import app.aaps.core.data.configuration.Constants
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

    /**
     * Alpha (0..255) of the glass smoke tint for the WidgetOpacity setting (0..255).
     * widget_glass_tint.xml runs 78 % -> 100 %, so the default setting (25) gives the recipe's "smoke" 44 % -> 56 %.
     * Never below 0.52 (top 41 %), the recipe's hard floor is 30 %. On a bright wallpaper the recipe's "dense" tier
     * (66 % -> 76 %) is the minimum, otherwise the secondary labels lose their contrast.
     */
    fun glassTintAlpha(opacity: Int, brightWallpaper: Boolean = false): Int {
        val fraction = 0.52 + 0.48 * opacity.coerceIn(0, 255) / 255.0
        return Math.round((if (brightWallpaper) maxOf(fraction, DENSE_TINT) else fraction) * 255).toInt()
    }

    private const val DENSE_TINT = 0.80

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

    // ---------------------------------------------------------------- BG size (by available space)

    /** BG text size of the layout (dp at font scale 1); arrow and age scale with it */
    const val BG_BASE_DP = 44f

    /** Largest BG size: about 1.45 x the base, keeps the card calm */
    const val BG_MAX_DP = 64f
    const val BG_MIN_DP = 30f

    /** widget_layout.xml: padding 16 + 8, header line ~17, bottom row 48 */
    private const val CHROME_HEIGHT_DP = 89f

    /** widget_layout.xml: padding 22 + 22, TIR column 142 */
    private const val CHROME_WIDTH_DP = 186f

    /**
     * Text box (no font padding) is ~1.17 em high: 1.3 em leaves a margin.
     * Width per em: "14,4" ~2.0 em + 8 dp gap + age column up to 9 chars of 12/44 em ("vor 3 Std").
     */
    private const val HEIGHT_PER_EM = 1.3f
    private const val WIDTH_PER_EM = 2.0f + 8f / BG_BASE_DP + 9 * 0.52f * 12f / BG_BASE_DP

    /** BG size (dp) that fits the middle row of a widget of [widthDp] x [heightDp] */
    fun bgTextSizeDp(widthDp: Float, heightDp: Float): Float {
        val byHeight = (heightDp - CHROME_HEIGHT_DP) / HEIGHT_PER_EM
        val byWidth = (widthDp - CHROME_WIDTH_DP) / WIDTH_PER_EM
        return minOf(BG_MAX_DP, byHeight, byWidth).coerceAtLeast(BG_MIN_DP)
    }

    /**
     * The carbs pill only shows when the bottom row has room for it, so the bolus pills are never clipped.
     * Row content: IOB 66 + COB 58 + TDD ~40 + bolus pills 64 + 8 + 64 + carbs pill ~42 + 6 = 348 dp, plus 44 dp padding.
     * A longer label than "KH" needs ~8 dp per extra character.
     */
    fun showCarbsPill(widthDp: Float, labelChars: Int): Boolean =
        widthDp >= 396f + 8f * (labelChars - 2).coerceAtLeast(0)

    // ---------------------------------------------------------------- carbs pill -> bolus wizard

    /** Same conditions as the overview's calculator button (OverviewFragment). BG is not checked: carbs are fine on a low. */
    enum class WizardBlock { NOT_SUPPORTED, NOT_INITIALIZED, NO_PROFILE, PUMP_NOT_READY, PUMP_SUSPENDED }

    fun wizardBlock(aps: Boolean, appInitialized: Boolean, profileAvailable: Boolean, pumpInitialized: Boolean, pumpSuspendedOrDisconnected: Boolean): WizardBlock? = when {
        !aps -> WizardBlock.NOT_SUPPORTED
        !appInitialized -> WizardBlock.NOT_INITIALIZED
        !profileAvailable -> WizardBlock.NO_PROFILE
        !pumpInitialized -> WizardBlock.PUMP_NOT_READY
        pumpSuspendedOrDisconnected -> WizardBlock.PUMP_SUSPENDED
        else -> null
    }

    /** A carbs pill tap opens the wizard in MainActivity only within this time (elapsedRealtime), APPLICATION protection included */
    const val WIZARD_REQUEST_TTL_MS = 60 * 1000L

    fun wizardRequestValid(armedAt: Long?, nowElapsed: Long): Boolean =
        armedAt != null && nowElapsed - armedAt in 0..WIZARD_REQUEST_TTL_MS
}
