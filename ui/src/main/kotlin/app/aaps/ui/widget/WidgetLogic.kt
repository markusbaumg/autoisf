package app.aaps.ui.widget

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TrendArrow
import app.aaps.ui.R
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

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

    /**
     * Whole calendar days from the first day with readings ([firstDayMidnight]) to the last midnight ([todayMidnight]),
     * i.e. the days the 7 d / 30 d meters really cover. null without data. Counted in local dates, so DST days count as one.
     */
    fun dataSpanDays(firstDayMidnight: Long?, todayMidnight: Long, zone: ZoneId = ZoneId.systemDefault()): Int? {
        if (firstDayMidnight == null) return null
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(firstDayMidnight).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(todayMidnight).atZone(zone).toLocalDate()
        ).toInt()
        return if (days > 0) days else null
    }

    /** Day count in a meter label ("15 T"): the real span, at most the meter's [maxDays]; [maxDays] without data */
    fun meterDays(spanDays: Int?, maxDays: Int): Int = spanDays?.coerceAtMost(maxDays) ?: maxDays

    /** Days of data needed before the widget shows the 14 day mean glucose and HbA1c */
    const val LONG_TERM_MIN_DAYS = 7

    /** Mean glucose and HbA1c rows: both or none, never "--" */
    fun showLongTerm(meanMgdl: Double?, hbA1c: Double?, longTermDays: Int): Boolean =
        meanMgdl != null && hbA1c != null && longTermDays >= LONG_TERM_MIN_DAYS

    /** Height the history column needs with the two long-term rows (3 x 17 + 7 + 2 x 16 + 5 padding) on top of the chrome */
    fun longTermFits(heightDp: Float): Boolean = heightDp >= CHROME_HEIGHT_DP + 95f

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

    /** Tint at the default WidgetOpacity setting: 42 % -> 50 % (R2 had 44 % -> 56 %), a step lighter without losing the BG contrast */
    const val TINT_DEFAULT = 0.50

    /** Default of IntComposedKey.WidgetOpacity, kept so stored settings keep their meaning */
    const val OPACITY_DEFAULT = 25

    /** On a bright wallpaper the tint never goes below this, otherwise the secondary labels lose their contrast */
    private const val DENSE_TINT = 0.72

    /**
     * Strength (0..1) of the glass smoke tint for the WidgetOpacity setting (0..255).
     * Two linear pieces: 0 -> [TINT_FLOOR], [OPACITY_DEFAULT] -> [TINT_DEFAULT], 255 -> 1.
     * widget_glass_tint.xml runs 85 % -> 100 %, so the default gives a smoke of 42 % -> 50 %; the slider reaches
     * 30 % -> 36 % (lighter) and 85 % -> 100 % (denser) at its ends.
     */
    fun glassTintFraction(opacity: Int, brightWallpaper: Boolean = false): Double {
        val o = opacity.coerceIn(0, 255)
        val fraction =
            if (o <= OPACITY_DEFAULT) TINT_FLOOR + (TINT_DEFAULT - TINT_FLOOR) * o / OPACITY_DEFAULT
            else TINT_DEFAULT + (1.0 - TINT_DEFAULT) * (o - OPACITY_DEFAULT) / (255.0 - OPACITY_DEFAULT)
        return if (brightWallpaper) maxOf(fraction, DENSE_TINT) else fraction
    }

    /** Alpha (0..255) for setImageAlpha of the tint layer */
    fun glassTintAlpha(opacity: Int, brightWallpaper: Boolean = false): Int =
        Math.round(glassTintFraction(opacity, brightWallpaper) * 255).toInt()

    /** Tint strength shown next to the configuration slider, "50 %" */
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

    /**
     * Arrow slot next to the BG: the trend arrow, a neutral dash when the BG is fresh but no trend is known
     * (never a blank, so "no arrow" is not read as "flat"), nothing for stale / no data.
     */
    fun arrowSlotIcon(range: BgRange, arrow: TrendArrow?): Int? = when {
        range == BgRange.STALE || range == BgRange.NONE -> null
        else -> arrow?.let { arrowIcon(it) } ?: R.drawable.widget_arrow_unknown
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

    // ---------------------------------------------------------------- BG size (by available space)

    /** BG text size of the layout (dp at font scale 1); arrow and age scale with it */
    const val BG_BASE_DP = 44f

    /** Largest BG size: about 1.45 x the base, keeps the card calm */
    const val BG_MAX_DP = 64f
    const val BG_MIN_DP = 30f

    /** widget_layout.xml: padding 12 + 4, header line ~17, bottom row 48 (spacers take only the rest) */
    private const val CHROME_HEIGHT_DP = 81f

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
     * The carbs pill (calculator glyph, no text) only shows when the bottom row has room for it, so the bolus pills are never clipped.
     * Row content: IOB 66 + COB 58 + TDD ~40 + bolus pills 64 + 8 + 64 + carbs pill 42 + 6 = 348 dp, plus 44 dp padding.
     * The glyph has the same width in every language.
     */
    fun showCarbsPill(widthDp: Float): Boolean = widthDp >= 396f

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

    // ---------------------------------------------------------------- locked bolus pills: short reason

    /** Short reason shown instead of the locked pills ("Bolus gesperrt · tief"); null = only "Bolus gesperrt" */
    fun lockReasonShort(block: Block): Int? = when (block) {
        Block.LOW                                   -> R.string.widget_lock_low
        Block.STALE                                 -> R.string.widget_lock_stale
        Block.NO_BG                                 -> R.string.widget_lock_no_bg
        Block.SUSPENDED                             -> R.string.widget_lock_paused
        Block.PUMP_NOT_READY                        -> R.string.widget_lock_pump
        Block.BOLUS_RUNNING                         -> R.string.widget_lock_running
        Block.NOT_INITIALIZED                       -> R.string.widget_lock_starting
        Block.RECENT_BOLUS                          -> R.string.widget_lock_recent
        Block.NOT_SUPPORTED, Block.SCREEN_LOCKED,
        Block.INVALID_AMOUNT, Block.CONSTRAINED_TO_ZERO -> null
    }

    // ---------------------------------------------------------------- header (display only, read-only data)

    /** Minutes (or h:mm from this many minutes on) in "noch 35′" / "seit 25′" */
    const val HM_FROM_MINUTES = 100

    /** Temp target: whole minutes left, rounded up, at least 1. null when it already ended or has no end */
    fun ttMinutesLeft(ttStart: Long, ttDurationMs: Long, now: Long): Int? {
        if (ttDurationMs <= 0) return null
        val leftMs = ttStart + ttDurationMs - now
        if (leftMs <= 0) return null
        return ceil(leftMs / 60000.0).toInt().coerceAtLeast(1)
    }

    /** Minutes as "35′" or, from [HM_FROM_MINUTES] on, "1:40" (without the leading word) */
    fun minutesOrHm(minutes: Long): String =
        if (minutes < HM_FROM_MINUTES) "$minutes′" else String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)

    /** No loop result older than this is shown (ISF / factor hidden) */
    const val ISF_MAX_AGE_MS = 15 * 60 * 1000L

    /** Current ISF (mg/dL) from the last loop result, null when there is none, it is too old or not positive */
    fun currentIsf(variableSens: Double?, lastApsRun: Long?, now: Long): Double? = when {
        variableSens == null || lastApsRun == null -> null
        variableSens.isNaN() || variableSens <= 0.0 -> null
        now - lastApsRun > ISF_MAX_AGE_MS || lastApsRun - now > FUTURE_TOLERANCE_MS -> null
        else -> variableSens
    }

    /**
     * AutoISF factor profile ISF / variable ISF (x1,4 = loop 1.4 times more aggressive than the profile),
     * null when it is not known or rounds to 1,00.
     */
    fun autoIsfFactor(profileSens: Double?, variableSens: Double?): Double? {
        if (profileSens == null || variableSens == null || profileSens <= 0.0 || variableSens <= 0.0) return null
        val f = Math.round(profileSens / variableSens * 100) / 100.0
        return if (abs(f - 1.0) < 0.005) null else f
    }

    /** "×1,4", "×1,15", "×0,89": 2 decimals, trailing zeros stripped */
    fun factorText(f: Double): String = "×" + decimal(f, 2).trimEnd('0').trimEnd(',')

    /** Basal rate text: 2 decimals, below 0.05 U/h the plain "0" */
    fun rateText(rate: Double): String = if (rate < ZERO_RATE) "0" else decimal(rate, 2)

    const val ZERO_RATE = 0.05

    /** A high temp from this percentage on gets a chip */
    const val HIGH_TEMP_PCT = 120

    /** An SMB is shown this long ("SMB 0,35 U · vor 3′") when no basal chip is shown */
    const val SMB_SHOW_MS = 5 * 60 * 1000L

    /** Zero-temp streak: at most this many TBRs / this far back */
    const val ZERO_STREAK_MAX_STEPS = 48
    const val ZERO_STREAK_MAX_MS = 4 * 60 * 60 * 1000L

    /**
     * Start of the zero-temp streak (how long the loop has held the basal at 0, not the 30 min TBR that is
     * re-issued every 5 minutes). [zeroTbStartAt] returns the start of the zero TBR running at a time, or null
     * when no zero TBR runs then. Walks back TBR by TBR (contiguous TBRs: the next one starts where the previous ended).
     */
    fun zeroStreakStart(now: Long, zeroTbStartAt: (Long) -> Long?): Long? {
        var start = zeroTbStartAt(now) ?: return null
        val limit = now - ZERO_STREAK_MAX_MS
        repeat(ZERO_STREAK_MAX_STEPS) {
            if (start <= limit) return limit
            val previous = zeroTbStartAt(start - 1000L) ?: return start
            if (previous >= start) return start
            start = previous
        }
        return start.coerceAtLeast(limit)
    }

    data class BasalInput(
        val now: Long,
        val mode: RM.Mode?,
        /** activePump.isSuspended() */
        val pumpSuspended: Boolean,
        /** loop.minutesToEndOfSuspend() */
        val suspendMinutesLeft: Int,
        /** a temp basal (or converted extended bolus) runs */
        val tempBasal: Boolean,
        /** running rate (U/h) and its percentage of the profile basal, only with [tempBasal] */
        val rate: Double? = null,
        val pct: Int? = null,
        val profileBasal: Double? = null,
        /** start of the zero-temp streak, see [zeroStreakStart] */
        val zeroSince: Long? = null,
        /** newest valid SMB */
        val smbAmount: Double? = null,
        val smbAt: Long? = null
    )

    /** What the right end of the header shows; [chip] = drawn as a chip */
    sealed class BasalState(val chip: Boolean) {
        /** running basal equals the profile basal: nothing is shown */
        object None : BasalState(false)
        object PumpStopped : BasalState(true)
        data class Disconnected(val minutesLeft: Int) : BasalState(true)
        data class Paused(val minutesLeft: Int) : BasalState(true)
        data class Zero(val sinceMinutes: Long) : BasalState(true)
        data class High(val rate: Double, val pct: Int) : BasalState(true)
        data class Smb(val amount: Double, val minutesAgo: Long) : BasalState(false)
        /** a lowered (or slightly raised) temp: quiet text, [lower] = with the low-temp glyph */
        data class Quiet(val rate: Double, val pct: Int, val lower: Boolean) : BasalState(false)
    }

    /** First match wins (chips before the SMB hint, the SMB hint before a quiet temp). Display only. */
    fun basalState(i: BasalInput): BasalState {
        val mode = i.mode
        val rate = if (i.tempBasal) i.rate else null
        val pct = if (i.tempBasal) i.pct else null
        val equalsProfile = pct == 100 || (rate != null && i.profileBasal != null && abs(rate - i.profileBasal) < 0.005)
        val smbAt = i.smbAt
        return when {
            i.pumpSuspended || mode == RM.Mode.SUSPENDED_BY_PUMP                     -> BasalState.PumpStopped
            mode == RM.Mode.DISCONNECTED_PUMP                                        -> BasalState.Disconnected(i.suspendMinutesLeft.coerceAtLeast(0))
            mode == RM.Mode.SUSPENDED_BY_USER || mode == RM.Mode.SUSPENDED_BY_DST ||
                mode == RM.Mode.SUPER_BOLUS                                          -> BasalState.Paused(i.suspendMinutesLeft.coerceAtLeast(0))
            rate != null && rate < ZERO_RATE                                         ->
                BasalState.Zero(((i.now - (i.zeroSince ?: i.now)).coerceAtLeast(0)) / 60000L)
            rate != null && pct != null && pct >= HIGH_TEMP_PCT                      -> BasalState.High(rate, pct)
            smbAt != null && smbRecent(smbAt, i.now) && i.smbAmount != null && i.smbAmount > 0.0 ->
                BasalState.Smb(i.smbAmount, ((i.now - smbAt).coerceAtLeast(0)) / 60000L)
            rate != null && pct != null && !equalsProfile                            -> BasalState.Quiet(rate, pct, lower = pct < 100)
            else                                                                     -> BasalState.None
        }
    }

    /** SMB younger than [SMB_SHOW_MS] (a reading up to 1 min in the future counts as now) */
    fun smbRecent(smbAt: Long?, now: Long): Boolean =
        smbAt != null && now - smbAt <= SMB_SHOW_MS && smbAt - now <= FUTURE_TOLERANCE_MS

    // ---------------------------------------------------------------- header width guard

    /** dp per character at 12.5 sp (Samsung One UI sans, mixed digits and letters, slightly generous) */
    private const val HEADER_DP_PER_CHAR = 6.6f
    private const val HEADER_GAP_DP = 12f
    private const val HEADER_PROFILE_MAX_DP = 110f

    data class HeaderFit(val showFactor: Boolean, val showTtSuffix: Boolean)

    /**
     * The header is one line: profile, target, ISF, basal. When the estimated width exceeds [availableDp], the AutoISF
     * factor goes first, then the temp-target remaining time. The profile name is capped by maxWidth (ellipsized).
     */
    fun headerFit(
        availableDp: Float, profileText: String, targetText: String, ttSuffix: String?,
        isfText: String?, factorText: String?, basalText: String?, basalChip: Boolean
    ): HeaderFit {
        fun w(s: String) = s.length * HEADER_DP_PER_CHAR
        val profile = minOf(w(profileText), HEADER_PROFILE_MAX_DP)
        val target = 15f + w(targetText)
        val suffix = ttSuffix?.let { 4f + w(it) * 0.88f } ?: 0f
        val isf = isfText?.let { HEADER_GAP_DP + w("ISF $it") } ?: 0f
        val factor = if (isfText != null) factorText?.let { 4f + w(it) * 0.88f } ?: 0f else 0f
        val basal = basalText?.let { HEADER_GAP_DP + w(it) * 0.92f + if (basalChip) 32f else 16f } ?: 0f
        val base = profile + HEADER_GAP_DP + target + isf + basal
        return when {
            base + suffix + factor <= availableDp -> HeaderFit(showFactor = factor > 0f, showTtSuffix = suffix > 0f)
            base + suffix <= availableDp           -> HeaderFit(showFactor = false, showTtSuffix = suffix > 0f)
            else                                   -> HeaderFit(showFactor = false, showTtSuffix = false)
        }
    }
}
