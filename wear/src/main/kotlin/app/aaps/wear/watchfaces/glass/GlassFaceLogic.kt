package app.aaps.wear.watchfaces.glass

import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Level
import app.aaps.wear.watchfaces.common.FaceState.LoopHealth
import app.aaps.wear.watchfaces.common.FaceState.LoopMode
import app.aaps.wear.watchfaces.common.FaceState.LoopRun
import app.aaps.wear.watchfaces.common.FaceState.PhoneLink
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.FaceState.Sentinel
import app.aaps.wear.watchfaces.common.FaceState.Warning
import app.aaps.wear.watchfaces.common.PhoneReach
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure state logic of the "AAPS Glass" watch face (no Android types), unit tested.
 *
 * Follows the family state catalogue (designsystem/states.md): derived signals (§1), the status-slot
 * warning hierarchy W1-W10 (§4.2), text fallbacks (§4.3) and the bolus ball state machine (§4.6).
 *
 * All glucose values are mg/dL (SingleBg.sgv/low/high). Displayed strings come from the phone in the
 * user's unit and locale and are shown as sent (German keeps its decimal comma, units.phone_locale);
 * only placeholders are mapped to "--". Geometry never parses display strings.
 *
 * The family signals (BG age, range, trend, loop health, phone link, warning order) are computed by the shared
 * [FaceState] helper; the functions here delegate to it, so Glass, Halo and Orbit can never disagree.
 */
object GlassFaceLogic {

    const val MINUTE_MS = 60_000L

    /** BG age (minutes): aging from 6', stale from 12', very stale from 30', digits replaced by "---" from 60'. */
    const val AGE_WARN_MIN = FaceState.AGING_MIN
    const val STALE_MIN = FaceState.STALE_MIN
    const val VERY_STALE_MIN = FaceState.VERY_STALE_MIN
    const val BG_HIDE_MIN = FaceState.BG_HIDE_MIN

    /** Loop age: amber from 6', red from 15'. */
    const val LOOP_WARN_MIN = FaceState.LOOP_WARN_MIN
    const val LOOP_BAD_MIN = FaceState.LOOP_BAD_MIN

    /** From 10' the "LOOP" label is hidden to make room for the two-digit value. */
    const val LOOP_LABEL_HIDE_MIN = 10L

    /** Loop pill minutes above this are shown as the "old" word, so the pill width stays bounded. */
    const val MAX_SHOWN_MIN = 99L

    /** No Status/SingleBg from the phone for at least this long: data counts as not fresh (phoneLink). */
    const val DATA_FRESH_MS = FaceState.DATA_FRESH_MS

    /** Status slot / footer: widest line (spec §4.8: the chord at 0.93 R, 257 px on the 450 grid). */
    const val FOOTER_MAX_WIDTH = 257f

    /** Footer text size and the floor a W1-W6 owner text may shrink to before it is ellipsised. */
    const val FOOTER_SIZE = 18f
    const val FOOTER_MIN_SIZE = 16f

    /** Fallback low/high marks when the phone did not send them. */
    const val DEFAULT_LOW_MGDL = FaceState.DEFAULT_LOW_MGDL
    const val DEFAULT_HIGH_MGDL = FaceState.DEFAULT_HIGH_MGDL

    /** Derived urgency levels (contract gap G1: not sent by the phone). */
    const val URGENT_LOW_MGDL = FaceState.URGENT_LOW_MGDL
    const val VERY_HIGH_MGDL = FaceState.VERY_HIGH_MGDL
    const val SENTINEL_LOW_MGDL = FaceState.SENTINEL_LOW_MGDL
    const val SENTINEL_HIGH_MGDL = FaceState.SENTINEL_HIGH_MGDL
    const val FAST_DELTA_MGDL = FaceState.FAST_DELTA_MGDL

    // ------------------------------------------------------------------------------------------- BG state

    enum class Trend(val angle: Float, val double: Boolean) {
        NONE(0f, false), FLAT(0f, false), UP45(45f, false), UP(90f, false), UP2(90f, true), UP3(90f, true),
        DOWN45(-45f, false), DOWN(-90f, false), DOWN2(-90f, true), DOWN3(-90f, true)
    }

    /** Whole minutes since [timeStamp] (clock skew clamps to 0), or null without a time stamp. */
    fun ageMinutes(timeStamp: Long, now: Long): Long? = FaceState.ageMinutes(timeStamp, now)

    fun bgAge(ageMinutes: Long?): BgAge = FaceState.bgAge(ageMinutes)

    /** STALE, VERY_STALE and NONE: no range hue, empty chip, no delta. */
    fun isStale(age: BgAge): Boolean = FaceState.isOld(age)

    /** Strike-through only for a real but old reading (not when there was never one). */
    fun strikeThrough(age: BgAge): Boolean = FaceState.isStruck(age)

    fun lowLine(low: Double): Double = FaceState.lowLine(low)
    fun highLine(low: Double, high: Double): Double = FaceState.highLine(low, high)

    /**
     * Range key of the aurora and the accents (states.md bgRange). The phone's low/high marks are used;
     * urgent low is below 54 mg/dL or a low that falls fast; very high is above 250 mg/dL. Stale beats range.
     */
    fun range(sgvMgdl: Double, lowMgdl: Double, highMgdl: Double, age: BgAge, fastFall: Boolean): Range =
        FaceState.range(sgvMgdl, FaceState.levelFromMarks(sgvMgdl, lowMgdl, highMgdl), age, fastFall)

    fun isLow(r: Range) = FaceState.isLow(r)

    fun sentinel(sgvMgdl: Double, age: BgAge): Sentinel? = FaceState.sentinel(sgvMgdl, age)

    /** Hero BG text: "---" without a reading or from 60'; the phone string otherwise, "+" appended at the high sentinel. */
    fun bgText(sgvString: String, ageMinutes: Long?, sentinel: Sentinel?): String {
        val s = sgvString.trim()
        if (ageMinutes == null || ageMinutes >= BG_HIDE_MIN || s.isEmpty() || s.none { it.isDigit() }) return "---"
        return if (sentinel == Sentinel.HIGH && !s.endsWith("+")) "$s+" else s
    }

    /** Colour level of the BG age label: normal below 6', warn from 6', alert from 12' (or no reading). */
    fun ageLevel(ageMinutes: Long?): Level = when {
        ageMinutes == null          -> Level.ALERT
        ageMinutes >= STALE_MIN     -> Level.ALERT
        ageMinutes >= AGE_WARN_MIN  -> Level.WARN
        else                        -> Level.NORMAL
    }

    /** Localised unit words: "%d Std" / "%d T" in German. */
    data class AgeWords(val hours: (Long) -> String, val days: (Long) -> String)

    /** "17′", hours from 60' ("3 Std"), days from 48 h ("2 T"), "--" when unknown. */
    fun ageText(minutes: Long?, words: AgeWords): String = when {
        minutes == null        -> "--"
        minutes >= 48 * 60     -> words.days(minutes / 1440)
        minutes >= 60          -> words.hours(minutes / 60)
        else                   -> "$minutes$PRIME"
    }

    const val PRIME = "′"

    /** BG digits: start size, step, floor and the widest allowed text with and without the trend chip (spec §4.1). */
    const val BG_SIZE = 84f
    const val BG_SIZE_STEP = 2f
    const val BG_MIN_SIZE = 56f
    const val BG_MAX_WIDTH = 170f
    const val BG_MAX_WIDTH_NO_CHIP = 230f

    /**
     * BG text size on the 450 grid: 84 px, stepping down 2 px until the text measured at that size is at most
     * [maxWidth], floor 56 px. Decided by measured width, never by character count. [measureAt] returns the
     * width of the text at a given size.
     */
    fun bgFitSize(measureAt: (Float) -> Float, maxWidth: Float = BG_MAX_WIDTH): Float {
        var size = BG_SIZE
        while (size > BG_MIN_SIZE && measureAt(size) > maxWidth) size -= BG_SIZE_STEP
        return max(size, BG_MIN_SIZE)
    }

    /** Scale [size] down so that a text measured as [width] at [size] fits [maxWidth]. */
    fun fitSize(size: Float, width: Float, maxWidth: Float): Float =
        if (width <= maxWidth || width <= 0f) size else size * maxWidth / width

    /** Trend from the phone's arrow; "X" uses the delta sign; anything else (unknown, "??", "--") is NONE. */
    fun trend(slopeArrow: String, deltaMgdl: Double?): Trend = Trend.valueOf(FaceState.trend(slopeArrow, deltaMgdl).name)

    fun fastFall(trend: Trend, deltaMgdl: Double?): Boolean = FaceState.fastFall(FaceState.Trend.valueOf(trend.name), deltaMgdl)

    /** Delta as sent by the phone (already signed, phone locale); a missing sign is added; placeholders "--". */
    fun deltaText(delta: String): String = FaceState.deltaText(delta)

    /** Which delta string to show (key_show_detailed_delta / key_show_avg_delta), as the stock faces do. */
    fun pickDelta(delta: String, avgDelta: String, deltaDetailed: String, avgDeltaDetailed: String, detailed: Boolean, avg: Boolean): String =
        FaceState.pickDelta(delta, avgDelta, deltaDetailed, avgDeltaDetailed, detailed, avg)

    /** The age label is optional (key_show_ago) only while fresh; the aging/stale warning always shows. */
    fun showAge(prefShowAgo: Boolean, age: BgAge): Boolean = prefShowAgo || age != BgAge.FRESH

    // ------------------------------------------------------------------------------------------- loop

    /** Loop age in minutes from Status.openApsStatus (-1 or 0: no loop run). */
    fun loopAgeMinutes(openApsStatus: Long, now: Long): Long? = FaceState.loopAgeMinutes(openApsStatus, now)

    /**
     * The phone puts text into Status.externalStatus only when there is no profile (one localised line,
     * no newline) or the loop is not running (localised "disabled" line ending with "\n");
     * the watch's empty store holds "no status".
     */
    fun loopRun(externalStatus: String, iobSum: String = ""): LoopRun = FaceState.loopRun(externalStatus, iobSum)

    /** First start: nothing ever arrived (no BG, no loop run, IOB placeholder). */
    fun firstStart(bgTimeStamp: Long, openApsStatus: Long, iobSum: String): Boolean = FaceState.firstStart(bgTimeStamp, openApsStatus, iobSum)

    /** No profile: loopRun says so, or the phone sent an empty IOB. */
    fun noProfile(run: LoopRun, iobSum: String, firstStart: Boolean): Boolean = FaceState.noProfile(run, iobSum, firstStart)

    /**
     * Loop mode from the available-states list: the one of OPEN/LGS/CLOSED that is missing is the current one.
     * UNKNOWN when the list is empty, ambiguous or older than the last data by more than 15'.
     */
    fun loopMode(available: Set<String>, listTimeStamp: Long, lastData: Long): LoopMode = FaceState.loopMode(available, listTimeStamp, lastData)

    /**
     * Loop indicator (states.md loop.*): NEVER (no loop run: grey, never red) · OFF (disabled/suspended) ·
     * NEUTRAL (no profile) · OPEN (no age alarm) · OK < 6' · WARN 6-14' · BAD >= 15'.
     */
    fun loopHealth(ageMinutes: Long?, run: LoopRun, mode: LoopMode, realRun: Boolean = true): LoopHealth =
        FaceState.loopHealth(ageMinutes, run, mode, realRun)

    /** The "LOOP" label is hidden once the value has two digits (or is the "old" word). */
    fun loopLabelVisible(ageMinutes: Long?): Boolean = ageMinutes == null || ageMinutes < LOOP_LABEL_HIDE_MIN

    /** Loop pill value: "2′", "--" when never, [oldWord] above 99'. Low-bit ambient adds "!" from 15'. */
    fun loopText(ageMinutes: Long?, oldWord: String, lowBit: Boolean): String {
        val t = when {
            ageMinutes == null             -> "--"
            ageMinutes > MAX_SHOWN_MIN     -> oldWord
            else                           -> "$ageMinutes$PRIME"
        }
        return if (lowBit && ageMinutes != null && ageMinutes >= LOOP_BAD_MIN) "$t!" else t
    }

    // ------------------------------------------------------------------------------------------- phone link

    /** Display link: an UNKNOWN answer does not warn (the first lookup is still running). */
    fun phoneLink(reach: PhoneReach, lastDataTimeStamp: Long, now: Long): PhoneLink = FaceState.phoneLink(reach, lastDataTimeStamp, now)

    fun dataFresh(lastDataTimeStamp: Long, now: Long): Boolean = FaceState.dataFresh(lastDataTimeStamp, now)

    fun phoneLink(reachable: Boolean, lastDataTimeStamp: Long, now: Long): PhoneLink = FaceState.phoneLink(reachable, lastDataTimeStamp, now)

    // ------------------------------------------------------------------------------------------- values

    /** Phone string trimmed; the placeholders of the empty store and "??" become "--". */
    fun display(s: String): String = FaceState.display(s)

    /** Numeric value of a phone string with either decimal separator ("2,53", "-0.35"), or null. */
    fun parseNumber(s: String): Double? = FaceState.parseNumber(s)

    enum class Tone { TXT1, TXT2, TXT3, INS, TT, TBR, WARN, ALERT }

    /** IOB value colour: ins when positive, txt1 when negative, dim for "0,00" and unknown. */
    fun iobTone(iobSum: String): Tone {
        val v = parseNumber(iobSum) ?: return Tone.TXT3
        return when {
            abs(v) < 0.005 -> Tone.TXT3
            v < 0          -> Tone.TXT1
            else           -> Tone.INS
        }
    }

    /** Basal colour: TBR percent in tbr ("0%" warn), zero absolute basal warn, unknown dim, else txt2. */
    fun basalTone(basal: String): Tone {
        val b = display(basal)
        return when {
            b == "--"                                          -> Tone.TXT3
            b.endsWith("%")                                    -> if (parseNumber(b) == 0.0) Tone.WARN else Tone.TBR
            b.startsWith("0,00") || b.startsWith("0.00")       -> Tone.WARN
            else                                               -> Tone.TXT2
        }
    }

    /** The phone's basal with a space before the unit ("1,35U/h" -> "1,35 U/h"), "--" for placeholders. */
    fun basalText(currentBasal: String): String {
        val s = display(currentBasal)
        if (s == "--") return s
        val i = s.indexOf("U/h")
        return if (i > 0 && s[i - 1] != ' ') s.substring(0, i) + " " + s.substring(i) else s
    }

    /** Phone battery percent from Status.battery ("18"), or null; "0" means unknown. */
    fun phoneBatteryPercent(battery: String): Int? = FaceState.phoneBatteryPercent(battery)

    // ------------------------------------------------------------------------------------------- status slot

    /** Rank of the warning that owns the status slot (states.md §4.2). */
    data class SlotInput(
        val firstStart: Boolean,
        val link: PhoneLink,
        val bgAge: BgAge,
        val range: Range,
        val noProfile: Boolean,
        val loop: LoopHealth,
        val reservoirString: String,
        val reservoirLevel: Int,
        val phoneBattery: String,
        val phoneBatteryLevel: Int
    )

    /** All active warnings, highest rank first. Fresh lows suppress W7-W10 (the user sees one thing). */
    fun warnings(i: SlotInput): List<Warning> = FaceState.warnings(
        FaceState.WarningInput(
            i.firstStart, i.link, i.bgAge, i.range, i.noProfile, i.loop, i.reservoirString, i.reservoirLevel, i.phoneBattery, i.phoneBatteryLevel
        )
    )

    enum class RunKind { WARNING, TT, COB, BASAL, BGI, RESERVOIR, PHONE_BATTERY }

    /** One run of the footer line; [keep] orders what is dropped first when the line is too wide (low first). */
    data class Run(val text: String, val kind: RunKind, val tone: Tone, val keep: Int)

    /** Localised words used in the status slot. */
    data class SlotWords(
        val waiting: String,
        val phoneLost: (String) -> String,
        val noData: (String) -> String,
        val noProfile: String,
        val loopOff: String,
        val loopAge: (String) -> String,
        val reservoir: (String) -> String,
        val phoneBattery: (Int) -> String,
        val cob: (String) -> String,
        val bgi: (String) -> String,
        val age: AgeWords
    )

    data class FooterInput(
        val warnings: List<Warning>,
        val dataAgeMinutes: Long?,
        val bgAgeMinutes: Long?,
        val loopAgeMinutes: Long?,
        val tempTarget: String,
        val tempTargetLevel: Int,
        val cob: String,
        val currentBasal: String,
        val bgi: String,
        val reservoirString: String,
        val reservoirLevel: Int,
        val phoneBattery: String,
        val phoneBatteryLevel: Int,
        val showTt: Boolean = true,
        val showCob: Boolean = true,
        val showBasal: Boolean = true,
        val showBgi: Boolean = false,
        val showReservoir: Boolean = true,
        val showPhoneBattery: Boolean = true,
        val ambient: Boolean = false
    )

    private fun warningRun(w: Warning, i: FooterInput, words: SlotWords): Run? = when (w) {
        Warning.WAITING          -> Run(words.waiting, RunKind.WARNING, Tone.TXT2, 100)
        Warning.PHONE_LOST       -> Run(words.phoneLost(ageText(i.dataAgeMinutes, words.age)), RunKind.WARNING, Tone.ALERT, 100)
        Warning.NO_DATA          -> Run(words.noData(ageText(maxOf(i.dataAgeMinutes ?: 0, i.bgAgeMinutes ?: 0).takeIf { i.dataAgeMinutes != null || i.bgAgeMinutes != null }, words.age)), RunKind.WARNING, Tone.ALERT, 100)
        Warning.NO_PROFILE       -> Run(words.noProfile, RunKind.WARNING, Tone.WARN, 100)
        Warning.LOOP_OFF         -> Run(words.loopOff, RunKind.WARNING, Tone.WARN, 100)
        Warning.LOOP_BAD         -> Run(words.loopAge(ageText(i.loopAgeMinutes, words.age)), RunKind.WARNING, Tone.ALERT, 100)
        Warning.RES_CRITICAL     -> Run(words.reservoir(i.reservoirString.trim()), RunKind.WARNING, Tone.ALERT, 100)
        Warning.PHONE_BATTERY    -> phoneBatteryPercent(i.phoneBattery)?.let { Run(words.phoneBattery(it), RunKind.WARNING, if (it < 10) Tone.ALERT else Tone.WARN, 100) }
        Warning.RES_LOW          -> Run(words.reservoir(i.reservoirString.trim()), RunKind.WARNING, Tone.WARN, 100)
        Warning.PHONE_LOST_FRESH -> null // glyph only
    }

    /**
     * Footer = status slot. A W1-W6 owner takes the whole line. Otherwise: a W7-W9 owner first (interactive
     * only), then TT, COB, basal, BGI and the low-priority info runs (reservoir, phone battery). Ambient keeps
     * W1-W6 and the TT only. Runs are dropped lowest [Run.keep] first while the line is wider than [maxWidth].
     */
    fun footerRuns(i: FooterInput, words: SlotWords, separator: String, measure: (String) -> Float, maxWidth: Float = FOOTER_MAX_WIDTH): List<Run> {
        val owner = i.warnings.firstOrNull()
        if (owner != null && owner.rank <= 6) return listOfNotNull(warningRun(owner, i, words))
        val runs = ArrayList<Run>(7)
        if (owner != null && owner.rank in 7..9 && !i.ambient) warningRun(owner, i, words)?.let { runs += it }
        if (i.showTt && i.tempTargetLevel == 2 && display(i.tempTarget) != "--") runs += Run("TT " + i.tempTarget.trim(), RunKind.TT, Tone.TT, 90)
        if (!i.ambient) {
            if (i.showCob) {
                val c = display(i.cob)
                when {
                    c == "--"            -> runs += Run(words.cob("--"), RunKind.COB, Tone.TXT3, 40)
                    !c.startsWith("0g") && !c.startsWith("0 g") -> runs += Run(words.cob(c), RunKind.COB, Tone.TXT2, 40)
                }
            }
            if (i.showBasal) runs += Run(basalText(i.currentBasal), RunKind.BASAL, basalTone(i.currentBasal), 50)
            if (i.showBgi && display(i.bgi) != "--") runs += Run(words.bgi(i.bgi.trim()), RunKind.BGI, Tone.TXT2, 10)
            if (i.showReservoir && i.reservoirLevel == 0 && display(i.reservoirString) != "--")
                runs += Run("Res " + i.reservoirString.trim(), RunKind.RESERVOIR, Tone.TXT2, 20)
            if (i.showPhoneBattery && i.phoneBatteryLevel == 1)
                phoneBatteryPercent(i.phoneBattery)?.let { runs += Run(words.phoneBattery(it), RunKind.PHONE_BATTERY, Tone.TXT2, 25) }
        }
        while (runs.size > 1 && lineWidth(runs, separator, measure) > maxWidth) {
            val drop = runs.filter { it.keep < 100 }.minByOrNull { it.keep } ?: break
            runs.remove(drop)
        }
        return runs
    }

    data class FooterFit(val size: Float, val runs: List<Run>)

    /**
     * Size of the footer line ([measure] works at [FOOTER_SIZE]): shrunk to fit [maxWidth], never below
     * [FOOTER_MIN_SIZE]; if it still does not fit at the floor (a long W1-W6 owner or a long translation),
     * the first run is ellipsised.
     */
    fun fitFooter(runs: List<Run>, separator: String, measure: (String) -> Float, maxWidth: Float = FOOTER_MAX_WIDTH): FooterFit {
        if (runs.isEmpty()) return FooterFit(FOOTER_SIZE, runs)
        val width = lineWidth(runs, separator, measure)
        val size = max(FOOTER_MIN_SIZE, fitSize(FOOTER_SIZE, width, maxWidth))
        val budget = maxWidth * FOOTER_SIZE / size // width available at FOOTER_SIZE
        if (width <= budget + 0.01f) return FooterFit(size, runs)
        val first = runs[0]
        val cut = ellipsize(first.text, budget - (width - measure(first.text)), measure)
        return FooterFit(size, listOf(first.copy(text = cut)) + runs.drop(1))
    }

    const val ELLIPSIS = "…"

    /** [s] cut at the end with "…" so that it measures at most [maxWidth]; unchanged when it fits. */
    fun ellipsize(s: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(s) <= maxWidth) return s
        var n = s.length
        while (n > 0 && measure(s.substring(0, n).trimEnd() + ELLIPSIS) > maxWidth) n--
        return s.substring(0, n).trimEnd() + ELLIPSIS
    }

    fun lineWidth(runs: List<Run>, separator: String, measure: (String) -> Float): Float {
        var w = 0f
        runs.forEachIndexed { i, r -> if (i > 0) w += measure(separator); w += measure(r.text) }
        return w
    }

    // ------------------------------------------------------------------------------------------- time / date

    /** "HH:mm" (24 h) or "h:mm" (12 h). */
    fun timeText(hour: Int, minute: Int, is24Hour: Boolean): String {
        val h = if (is24Hour) hour else (hour % 12).let { if (it == 0) 12 else it }
        val hh = if (is24Hour && h < 10) "0$h" else "$h"
        val mm = if (minute < 10) "0$minute" else "$minute"
        return "$hh:$mm"
    }

    /** 12 h suffix ("AM"/"PM", drawn smaller after the time), null in 24 h. */
    fun amPm(hour: Int, is24Hour: Boolean): String? = if (is24Hour) null else if (hour < 12) "AM" else "PM"

    /** "Fr 17. Okt" (pattern "EE d. MMM" with the dots of the abbreviations stripped). */
    fun dateText(date: LocalDate, locale: Locale): String {
        val wd = date.format(DateTimeFormatter.ofPattern("EE", locale)).removeSuffix(".")
        val mon = date.format(DateTimeFormatter.ofPattern("MMM", locale)).removeSuffix(".")
        return "$wd ${date.dayOfMonth}. $mon"
    }

    // ------------------------------------------------------------------------------------------- sparkline

    /** Plot rect on the 450 grid, 36 history slots (5') + 12 prediction slots. */
    const val SPARK_X0 = 84f
    const val SPARK_Y0 = 256f
    const val SPARK_X1 = 366f
    const val SPARK_Y1 = 312f
    const val SPARK_HISTORY = 36
    const val SPARK_TOTAL = 48
    const val SLOT_MS = 5 * MINUTE_MS

    /** A gap longer than this breaks the line (graph.gaps). */
    const val GAP_MS = 15 * MINUTE_MS

    /** Y range: 2,5-14 mmol/L (45-252 mg/dL), clamped. */
    const val SPARK_MIN_MGDL = 45.0
    const val SPARK_MAX_MGDL = 252.0

    /** x of slot [slot] (fractional), "now" is slot 35. */
    fun sparkX(slot: Float): Float = SPARK_X0 + slot / (SPARK_TOTAL - 1) * (SPARK_X1 - SPARK_X0)

    /** x of the now line (about 294). */
    val nowX: Float get() = sparkX((SPARK_HISTORY - 1).toFloat())

    /** y for a value in mg/dL, clamped to the plot. */
    fun sparkY(mgdl: Double): Float {
        val v = mgdl.coerceIn(SPARK_MIN_MGDL, SPARK_MAX_MGDL)
        return (SPARK_Y1 - (v - SPARK_MIN_MGDL) / (SPARK_MAX_MGDL - SPARK_MIN_MGDL) * (SPARK_Y1 - SPARK_Y0)).toFloat()
    }

    /**
     * Time at the now line. A fresh reading sits exactly on it; otherwise the axis follows the clock in
     * 5' steps, so the line shifts only on a new BG or at a 5' boundary.
     */
    fun sparkAnchor(bgTimeStamp: Long, now: Long): Long =
        if (bgTimeStamp > 0L && now - bgTimeStamp < SLOT_MS) bgTimeStamp else floor(now.toDouble() / SLOT_MS).toLong() * SLOT_MS

    /** Fractional slot for a time stamp relative to [anchor] (slot 35), or null outside the plot. */
    fun slotOf(timeStamp: Long, anchor: Long): Float? {
        val slot = (SPARK_HISTORY - 1) + (timeStamp - anchor).toFloat() / SLOT_MS
        return if (slot < -0.01f || slot > SPARK_TOTAL - 1 + 0.01f) null else slot
    }

    // ------------------------------------------------------------------------------------------- bolus ball

    enum class BallState { HIDDEN, DISABLED, IDLE, ARMED, SENT }

    /**
     * Drawn ball state (states.md §4.6) from the shared [BolusRequestGuard] verdict: every rule (default off, ambient,
     * Wear control, phone link, data age, max bolus, pending) lives there; Glass only adds ARMED for the drawing.
     */
    fun ballState(check: BolusRequestGuard.Check, armed: Boolean): BallState = when (check.state) {
        BolusRequestGuard.State.HIDDEN   -> BallState.HIDDEN
        BolusRequestGuard.State.SENT     -> BallState.SENT
        BolusRequestGuard.State.DISABLED -> BallState.DISABLED
        BolusRequestGuard.State.IDLE     -> if (armed) BallState.ARMED else BallState.IDLE
    }

    /** Message of a refused bolus tap (a toast); one per guard reason, so the user learns what to fix. */
    enum class Refusal { PENDING, NO_PHONE, STALE_DATA, OVER_MAX_BOLUS, NO_MAX_BOLUS, NO_WEAR_CONTROL }

    /**
     * Toast for a REFUSED tap, or null when the reason never refuses a tap on a drawn ball (HIDDEN reasons, NONE).
     * "Max bolus not synced" and "Wear control off" get their own text: "above the max bolus" would be wrong there.
     */
    fun refusal(reason: BolusRequestGuard.Reason): Refusal? = when (reason) {
        BolusRequestGuard.Reason.PENDING         -> Refusal.PENDING
        BolusRequestGuard.Reason.NO_PHONE        -> Refusal.NO_PHONE
        BolusRequestGuard.Reason.STALE_DATA      -> Refusal.STALE_DATA
        BolusRequestGuard.Reason.OVER_MAX_BOLUS  -> Refusal.OVER_MAX_BOLUS
        BolusRequestGuard.Reason.NO_MAX_BOLUS    -> Refusal.NO_MAX_BOLUS
        BolusRequestGuard.Reason.NO_WEAR_CONTROL -> Refusal.NO_WEAR_CONTROL
        else                                     -> null
    }

    /** "+0,5" / "+1,0" with the watch locale's decimal separator. */
    fun bolusAmountText(amount: Double, locale: Locale): String = FaceState.bolusAmountText(amount, locale)

    /** Hit test of the ball (r=44 around the centre) clipped to the round screen (r=225). */
    fun ballHit(x: Float, y: Float, cx: Float = BALL_X, cy: Float = BALL_Y, r: Float = BALL_HIT_R): Boolean {
        val dx = x - cx
        val dy = y - cy
        val sx = x - 225f
        val sy = y - 225f
        return dx * dx + dy * dy <= r * r && sx * sx + sy * sy <= 225f * 225f
    }

    /** Fraction (1..0) of the armed countdown ring still to show. */
    fun armProgress(armedAt: Long, now: Long, windowMs: Long): Float =
        if (windowMs <= 0) 0f else (1f - (now - armedAt).toFloat() / windowMs).coerceIn(0f, 1f)

    const val BALL_X = 46f
    const val BALL_Y = 176f
    const val BALL_R = 30f
    const val BALL_HIT_R = 44f

    // ------------------------------------------------------------------------------------------- layout variants

    /** Hero card positions (spec §3, §4.3): chip on the right, or removed (key_show_direction off) with BG and meta centred. */
    data class HeroLayout(val bgX: Float, val bgY: Float, val bgMaxWidth: Float, val chipY: Float, val metaCentreX: Float, val metaY: Float)

    fun heroLayout(showChip: Boolean, metaEmpty: Boolean): HeroLayout = when {
        showChip  -> HeroLayout(184f, 176f, BG_MAX_WIDTH, if (metaEmpty) 175f else 152f, 312f, 200f)
        metaEmpty -> HeroLayout(225f, 175f, BG_MAX_WIDTH_NO_CHIP, 152f, 225f, 220f)
        else      -> HeroLayout(225f, 166f, BG_MAX_WIDTH_NO_CHIP, 152f, 225f, 220f)
    }

    /** Which pills are drawn: both; LOOP alone, centred (key_show_iob off); IOB alone, centred (pumpcontrol: no APS). */
    enum class Pills { BOTH, LOOP_ONLY, IOB_ONLY }

    fun pills(showIob: Boolean, pumpControl: Boolean): Pills = when {
        pumpControl -> Pills.IOB_ONLY
        !showIob    -> Pills.LOOP_ONLY
        else        -> Pills.BOTH
    }

    /** graph.no_predictions: pref off, stale data, open loop, client or pumpcontrol flavour. */
    fun showPredictions(pref: Boolean, stale: Boolean, mode: LoopMode, client: Boolean, pumpControl: Boolean): Boolean =
        pref && !stale && mode != LoopMode.OPEN && !client && !pumpControl

    /** age.none / first start: no delta, no age, the meta line is empty (spec §4.4). */
    fun metaVisible(age: BgAge, prefShowAgo: Boolean, prefShowDelta: Boolean): Pair<Boolean, Boolean> =
        if (age == BgAge.NONE) false to false
        else (!isStale(age) && prefShowDelta) to showAge(prefShowAgo, age)

    /**
     * Loop dot fill (spec §4.6): OK and WARN are filled, except that a filled warn dot turns into a hollow ring
     * while W2/W3 own the status slot (red budget, the footer already names the connection problem).
     */
    fun loopDotFilled(health: LoopHealth, owner: Warning?): Boolean = when (health) {
        LoopHealth.OK   -> true
        LoopHealth.WARN -> owner != Warning.PHONE_LOST && owner != Warning.NO_DATA
        else            -> false
    }

    /** The LOOP/LGS label is hidden only for an open loop ("Offen" fills the pill); width decides the rest. */
    fun loopLabelAllowed(health: LoopHealth): Boolean = health != LoopHealth.OPEN

    // ------------------------------------------------------------------------------------------- ambient range shape

    enum class OutlineStyle { NONE, SOLID, DASHED, DOUBLE }

    /** Ambient grey of an outline: ambLine, amb2, amb1 (white in low-bit). */
    enum class AmbTone { LINE, A2, A1 }

    /**
     * Hero card outline in ambient (spec §9: the range as a shape, colour is never the only carrier).
     * [innerWidth] is the inner line of a double outline (5 px inside). [boldBg]: BG digits weight 600.
     */
    data class AmbientOutline(val style: OutlineStyle, val width: Float, val innerWidth: Float, val tone: AmbTone, val boldBg: Boolean)

    const val DOUBLE_OUTLINE_GAP = 5f

    fun ambientOutline(range: Range, lowBit: Boolean): AmbientOutline = when (range) {
        Range.IN, Range.STALE -> if (lowBit) AmbientOutline(OutlineStyle.NONE, 0f, 0f, AmbTone.LINE, false)
                                 else AmbientOutline(OutlineStyle.SOLID, 1f, 0f, AmbTone.LINE, false)
        Range.HIGH            -> AmbientOutline(OutlineStyle.DASHED, 1f, 0f, AmbTone.A2, false)
        Range.VERY_HIGH       -> AmbientOutline(OutlineStyle.DASHED, 2f, 0f, AmbTone.A1, false)
        Range.LOW             -> AmbientOutline(OutlineStyle.DOUBLE, 1f, 1f, AmbTone.A2, false)
        Range.URGENT_LOW      -> AmbientOutline(OutlineStyle.DOUBLE, 1f, 2f, AmbTone.A1, true)
    }

    /** Random burn-in offset in [-4, +4] px from a 0..1 source. */
    fun burnInOffset(random01: Double): Float = (min(1.0, max(0.0, random01)) * 8.0 - 4.0).toFloat()
}
