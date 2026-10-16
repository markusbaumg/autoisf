package app.aaps.wear.watchfaces.glass

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
 * NOTE for the integrator: these signals are the seed of the shared `FaceState` helper; once the Halo
 * branch lands, move them there and let Glass/Orbit/Halo share them.
 */
object GlassFaceLogic {

    const val MINUTE_MS = 60_000L

    /** BG age (minutes): aging from 6', stale from 12', very stale from 30', digits replaced by "---" from 60'. */
    const val AGE_WARN_MIN = 6L
    const val STALE_MIN = 12L
    const val VERY_STALE_MIN = 30L
    const val BG_HIDE_MIN = 60L

    /** Loop age: amber from 6', red from 15'. */
    const val LOOP_WARN_MIN = 6L
    const val LOOP_BAD_MIN = 15L

    /** From 10' the "LOOP" label is hidden to make room for the two-digit value. */
    const val LOOP_LABEL_HIDE_MIN = 10L

    /** Loop pill minutes above this are shown as the "old" word, so the pill width stays bounded. */
    const val MAX_SHOWN_MIN = 99L

    /** No Status/SingleBg from the phone for at least this long: data counts as not fresh (phoneLink). */
    const val DATA_FRESH_MS = 12 * MINUTE_MS

    /** Status slot / footer: widest line (spec §4.8: the chord at 0.93 R, 257 px on the 450 grid). */
    const val FOOTER_MAX_WIDTH = 257f

    /** Footer text size and the floor a W1-W6 owner text may shrink to before it is ellipsised. */
    const val FOOTER_SIZE = 18f
    const val FOOTER_MIN_SIZE = 16f

    /** Fallback low/high marks when the phone did not send them. */
    const val DEFAULT_LOW_MGDL = 70.0
    const val DEFAULT_HIGH_MGDL = 180.0

    /** Derived urgency levels (contract gap G1: not sent by the phone). */
    const val URGENT_LOW_MGDL = 54.0
    const val VERY_HIGH_MGDL = 250.0
    const val SENTINEL_LOW_MGDL = 39.0
    const val SENTINEL_HIGH_MGDL = 400.0
    const val FAST_DELTA_MGDL = 10.0

    // ------------------------------------------------------------------------------------------- BG state

    enum class Range { IN, HIGH, VERY_HIGH, LOW, URGENT_LOW, STALE }

    enum class BgAge { FRESH, AGING, STALE, VERY_STALE, NONE }

    enum class Level { NORMAL, WARN, ALERT }

    enum class Sentinel { LOW, HIGH }

    enum class Trend(val angle: Float, val double: Boolean) {
        NONE(0f, false), FLAT(0f, false), UP45(45f, false), UP(90f, false), UP2(90f, true), UP3(90f, true),
        DOWN45(-45f, false), DOWN(-90f, false), DOWN2(-90f, true), DOWN3(-90f, true)
    }

    /** Whole minutes since [timeStamp] (clock skew clamps to 0), or null without a time stamp. */
    fun ageMinutes(timeStamp: Long, now: Long): Long? = if (timeStamp <= 0L) null else max(0L, (now - timeStamp) / MINUTE_MS)

    fun bgAge(ageMinutes: Long?): BgAge = when {
        ageMinutes == null              -> BgAge.NONE
        ageMinutes < AGE_WARN_MIN       -> BgAge.FRESH
        ageMinutes < STALE_MIN          -> BgAge.AGING
        ageMinutes < VERY_STALE_MIN     -> BgAge.STALE
        else                            -> BgAge.VERY_STALE
    }

    /** STALE, VERY_STALE and NONE: no range hue, empty chip, no delta. */
    fun isStale(age: BgAge): Boolean = age == BgAge.STALE || age == BgAge.VERY_STALE || age == BgAge.NONE

    /** Strike-through only for a real but old reading (not when there was never one). */
    fun strikeThrough(age: BgAge): Boolean = age == BgAge.STALE || age == BgAge.VERY_STALE

    fun lowLine(low: Double): Double = if (low > 0.0) low else DEFAULT_LOW_MGDL
    fun highLine(low: Double, high: Double): Double = if (high > lowLine(low)) high else DEFAULT_HIGH_MGDL

    /**
     * Range key of the aurora and the accents (states.md bgRange). The phone's low/high marks are used;
     * urgent low is below 54 mg/dL or a low that falls fast; very high is above 250 mg/dL. Stale beats range.
     */
    fun range(sgvMgdl: Double, lowMgdl: Double, highMgdl: Double, age: BgAge, fastFall: Boolean): Range {
        if (isStale(age)) return Range.STALE
        val lo = lowLine(lowMgdl)
        val hi = highLine(lowMgdl, highMgdl)
        return when {
            sgvMgdl < URGENT_LOW_MGDL || (sgvMgdl < lo && fastFall) -> Range.URGENT_LOW
            sgvMgdl < lo                                             -> Range.LOW
            sgvMgdl > VERY_HIGH_MGDL                                 -> Range.VERY_HIGH
            sgvMgdl > hi                                             -> Range.HIGH
            else                                                     -> Range.IN
        }
    }

    fun isLow(r: Range) = r == Range.LOW || r == Range.URGENT_LOW

    fun sentinel(sgvMgdl: Double, age: BgAge): Sentinel? = when {
        isStale(age)                     -> null
        sgvMgdl <= 0.0                   -> null
        sgvMgdl <= SENTINEL_LOW_MGDL     -> Sentinel.LOW
        sgvMgdl >= SENTINEL_HIGH_MGDL    -> Sentinel.HIGH
        else                             -> null
    }

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
    fun trend(slopeArrow: String, deltaMgdl: Double?): Trend = when (slopeArrow.trim()) {
        "⇈"      -> Trend.UP2
        "↑"      -> Trend.UP
        "↗"      -> Trend.UP45
        "→"      -> Trend.FLAT
        "↘"      -> Trend.DOWN45
        "↓"      -> Trend.DOWN
        "⇊"      -> Trend.DOWN2
        "X"      -> when {
            deltaMgdl == null -> Trend.NONE
            deltaMgdl < 0     -> Trend.DOWN3
            else              -> Trend.UP3
        }

        else     -> Trend.NONE
    }

    fun fastFall(trend: Trend, deltaMgdl: Double?): Boolean =
        trend == Trend.DOWN2 || trend == Trend.DOWN3 || (deltaMgdl != null && deltaMgdl <= -FAST_DELTA_MGDL)

    /** Delta as sent by the phone (already signed, phone locale); a missing sign is added; placeholders "--". */
    fun deltaText(delta: String): String {
        val d = delta.trim()
        if (d.isEmpty() || d.startsWith("--") || d.none { it.isDigit() }) return "--"
        return if (d[0] == '+' || d[0] == '-' || d[0] == '±') d else "+$d"
    }

    /** Which delta string to show (key_show_detailed_delta / key_show_avg_delta), as the stock faces do. */
    fun pickDelta(delta: String, avgDelta: String, deltaDetailed: String, avgDeltaDetailed: String, detailed: Boolean, avg: Boolean): String =
        when {
            detailed && avg -> avgDeltaDetailed
            detailed        -> deltaDetailed
            avg             -> avgDelta
            else            -> delta
        }

    /** The age label is optional (key_show_ago) only while fresh; the aging/stale warning always shows. */
    fun showAge(prefShowAgo: Boolean, age: BgAge): Boolean = prefShowAgo || age != BgAge.FRESH

    // ------------------------------------------------------------------------------------------- loop

    enum class LoopRun { RUNNING, OFF, NO_PROFILE }

    enum class LoopMode { CLOSED, OPEN, LGS, UNKNOWN }

    enum class LoopHealth { OK, WARN, BAD, NEVER, OFF, OPEN, NEUTRAL }

    /** Loop age in minutes from Status.openApsStatus (-1 or 0: no loop run). */
    fun loopAgeMinutes(openApsStatus: Long, now: Long): Long? = if (openApsStatus <= 0L) null else max(0L, (now - openApsStatus) / MINUTE_MS)

    /**
     * The phone puts text into Status.externalStatus only when there is no profile (one localised line,
     * no newline) or the loop is not running (localised "disabled" line ending with "\n");
     * the watch's empty store holds "no status".
     */
    fun loopRun(externalStatus: String): LoopRun {
        if (externalStatus.isBlank() || externalStatus.trim() == "no status") return LoopRun.RUNNING
        return if (externalStatus.endsWith("\n")) LoopRun.OFF else LoopRun.NO_PROFILE
    }

    /** First start: nothing ever arrived (no BG, no loop run, IOB placeholder). */
    fun firstStart(bgTimeStamp: Long, openApsStatus: Long, iobSum: String): Boolean =
        bgTimeStamp == 0L && openApsStatus <= 0L && iobSum.trim().let { it == "IOB" || it.isEmpty() }

    /** No profile: loopRun says so, or the phone sent an empty IOB. */
    fun noProfile(run: LoopRun, iobSum: String, firstStart: Boolean): Boolean = !firstStart && (run == LoopRun.NO_PROFILE || iobSum.isEmpty())

    /**
     * Loop mode from the available-states list: the one of OPEN/LGS/CLOSED that is missing is the current one.
     * UNKNOWN when the list is empty, ambiguous or older than the last data by more than 15'.
     */
    fun loopMode(available: Set<String>, listTimeStamp: Long, lastData: Long): LoopMode {
        if (available.isEmpty() || listTimeStamp <= 0L) return LoopMode.UNKNOWN
        if (lastData > 0L && lastData - listTimeStamp > 15 * MINUTE_MS) return LoopMode.UNKNOWN
        val missing = listOf("LOOP_CLOSED" to LoopMode.CLOSED, "LOOP_OPEN" to LoopMode.OPEN, "LOOP_LGS" to LoopMode.LGS)
            .filter { it.first !in available }
        return if (missing.size == 1) missing[0].second else LoopMode.UNKNOWN
    }

    /**
     * Loop indicator (states.md loop.*): NEVER (no loop run: grey, never red) · OFF (disabled/suspended) ·
     * NEUTRAL (no profile) · OPEN (no age alarm) · OK < 6' · WARN 6-14' · BAD >= 15'.
     */
    fun loopHealth(ageMinutes: Long?, run: LoopRun, mode: LoopMode): LoopHealth = when {
        run == LoopRun.NO_PROFILE      -> LoopHealth.NEUTRAL
        run == LoopRun.OFF             -> LoopHealth.OFF
        ageMinutes == null             -> LoopHealth.NEVER
        mode == LoopMode.OPEN          -> LoopHealth.OPEN
        ageMinutes >= LOOP_BAD_MIN     -> LoopHealth.BAD
        ageMinutes >= LOOP_WARN_MIN    -> LoopHealth.WARN
        else                           -> LoopHealth.OK
    }

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

    enum class PhoneLink { OK, LOST_FRESH, LOST_STALE, SILENT }

    /** Answer of the last CapabilityClient lookup. UNKNOWN until the first answer (no warning, but no bolus either). */
    enum class PhoneReach { UNKNOWN, REACHABLE, UNREACHABLE }

    /** A bolus request is sent only on a "reachable" answer at most this old (asked again when the ball arms). */
    const val REACH_ANSWER_FRESH_MS = 10_000L

    /** Display link: an UNKNOWN answer does not warn (the first lookup is still running). */
    fun phoneLink(reach: PhoneReach, lastDataTimeStamp: Long, now: Long): PhoneLink =
        phoneLink(reach != PhoneReach.UNREACHABLE, lastDataTimeStamp, now)

    /** SEND gate: the phone answered "reachable" within [REACH_ANSWER_FRESH_MS]. */
    fun reachConfirmed(reach: PhoneReach, answeredAt: Long, now: Long): Boolean =
        reach == PhoneReach.REACHABLE && answeredAt > 0L && now - answeredAt in 0..REACH_ANSWER_FRESH_MS

    fun dataFresh(lastDataTimeStamp: Long, now: Long): Boolean = lastDataTimeStamp > 0L && now - lastDataTimeStamp < DATA_FRESH_MS

    fun phoneLink(reachable: Boolean, lastDataTimeStamp: Long, now: Long): PhoneLink {
        val fresh = dataFresh(lastDataTimeStamp, now)
        return when {
            reachable && fresh -> PhoneLink.OK
            reachable          -> PhoneLink.SILENT
            fresh              -> PhoneLink.LOST_FRESH
            else               -> PhoneLink.LOST_STALE
        }
    }

    // ------------------------------------------------------------------------------------------- values

    /** Phone string trimmed; the placeholders of the empty store and "??" become "--". */
    fun display(s: String): String {
        val t = s.trim()
        return if (t.isEmpty() || t in PLACEHOLDERS || t.none { it.isDigit() }) "--" else t
    }

    private val PLACEHOLDERS = setOf("IOB", "-.--", "--g", "-.--U/h", "no status", "??", "--")

    /** Numeric value of a phone string with either decimal separator ("2,53", "-0.35"), or null. */
    fun parseNumber(s: String): Double? {
        val t = display(s)
        if (t == "--") return null
        val num = t.takeWhile { it.isDigit() || it == '-' || it == '+' || it == ',' || it == '.' }.replace(',', '.')
        return num.toDoubleOrNull()
    }

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
    fun phoneBatteryPercent(battery: String): Int? = battery.trim().removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 1..100 }

    // ------------------------------------------------------------------------------------------- status slot

    /** Rank of the warning that owns the status slot (states.md §4.2). */
    enum class Warning(val rank: Int) {
        WAITING(1), PHONE_LOST(2), NO_DATA(3), NO_PROFILE(4), LOOP_OFF(5), LOOP_BAD(6),
        RES_CRITICAL(7), PHONE_BATTERY(8), RES_LOW(9), PHONE_LOST_FRESH(10)
    }

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
    fun warnings(i: SlotInput): List<Warning> {
        val w = ArrayList<Warning>(4)
        if (i.firstStart) w += Warning.WAITING
        if (i.link == PhoneLink.LOST_STALE) w += Warning.PHONE_LOST
        if (i.link != PhoneLink.LOST_STALE && !i.firstStart && (i.link == PhoneLink.SILENT || strikeThrough(i.bgAge))) w += Warning.NO_DATA
        if (i.noProfile) w += Warning.NO_PROFILE
        if (i.loop == LoopHealth.OFF) w += Warning.LOOP_OFF
        if (i.loop == LoopHealth.BAD) w += Warning.LOOP_BAD
        val res = display(i.reservoirString) != "--"
        if (i.reservoirLevel == 2 && res) w += Warning.RES_CRITICAL
        if (i.phoneBatteryLevel == 0 && phoneBatteryPercent(i.phoneBattery) != null) w += Warning.PHONE_BATTERY
        if (i.reservoirLevel == 1 && res) w += Warning.RES_LOW
        if (i.link == PhoneLink.LOST_FRESH) w += Warning.PHONE_LOST_FRESH
        val freshLow = (i.bgAge == BgAge.FRESH || i.bgAge == BgAge.AGING) && isLow(i.range)
        if (freshLow) w.removeAll { it.rank > 6 }
        return w
    }

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

    /**
     * Hardened gesture timing (family rule set; the shared BolusTapGesture defaults are 1500/3000 at this
     * base and are overridden here): second tap within 800 ms, then 5 s cooldown; a sent request stays
     * pending for 30 s, during which further taps only show a toast.
     */
    const val BOLUS_WINDOW_MS = 800L
    const val BOLUS_COOLDOWN_MS = 5000L
    const val BOLUS_PENDING_MS = 30_000L

    enum class BallState { HIDDEN, DISABLED, IDLE, ARMED, SENT }

    enum class Refusal { NONE, NO_PHONE, STALE_DATA, MAX_BOLUS }

    /**
     * Why a bolus request must not be sent now: phone not reachable, data not fresh (no Status/SingleBg for
     * 12', or the BG itself stale), the reachability not yet known ([PhoneReach.UNKNOWN]), or the amount above the phone's max bolus (key_treatments_safety_max_bolus;
     * unknown max = refused).
     */
    fun refusal(link: PhoneLink, bgAge: BgAge, amount: Double, maxBolus: Double, reach: PhoneReach): Refusal = when {
        reach != PhoneReach.REACHABLE                  -> Refusal.NO_PHONE
        link != PhoneLink.OK                           -> if (link == PhoneLink.SILENT) Refusal.STALE_DATA else Refusal.NO_PHONE
        isStale(bgAge)                                 -> Refusal.STALE_DATA
        maxBolus <= 0.0 || amount > maxBolus + 1e-9    -> Refusal.MAX_BOLUS
        else                                           -> Refusal.NONE
    }

    /**
     * Ball state (states.md §4.6). HIDDEN: preference off (default), ambient (any), headless preview,
     * Wear control off on the phone, amount not allowed or first start. DISABLED: a [Refusal]. SENT while a
     * request is pending. ARMED during the window. IDLE otherwise.
     */
    fun ballState(
        prefOn: Boolean, ambient: Boolean, headless: Boolean, wearControl: Boolean, amountAllowed: Boolean, firstStart: Boolean,
        refusal: Refusal, armed: Boolean, sentAt: Long, now: Long
    ): BallState = when {
        !prefOn || ambient || headless || !wearControl || !amountAllowed || firstStart -> BallState.HIDDEN
        sentAt > 0L && now - sentAt in 0 until BOLUS_PENDING_MS                       -> BallState.SENT
        refusal != Refusal.NONE                                                       -> BallState.DISABLED
        armed                                                                         -> BallState.ARMED
        else                                                                          -> BallState.IDLE
    }

    /** "+0,5" / "+1,0" with the watch locale's decimal separator. */
    fun bolusAmountText(amount: Double, locale: Locale): String {
        val tenths = Math.round(abs(amount) * 10).toInt()
        val sep = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return "+${tenths / 10}$sep${tenths % 10}"
    }

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
