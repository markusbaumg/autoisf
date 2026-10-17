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

    /** Loop pill minutes above this are shown as the "old" word, so the pill width stays bounded. */
    const val MAX_SHOWN_MIN = 99L

    /** No Status/SingleBg from the phone for at least this long: data counts as not fresh (phoneLink). */
    const val DATA_FRESH_MS = FaceState.DATA_FRESH_MS

    /**
     * Status slot / footer: widest line. UX round 3: the footer sits at y 406 (the content block moved down off the
     * system status icon), where the chord at the cap bottom is about 239 px on the 450 grid.
     */
    /**
     * Glass/Orbit UX round 1: the footer moves up to y 394 and its width is the chord at its cap bottom less a 16 px
     * margin to the round edge on each side (the Watch5 Pro's raised bezel shades the outer 1 mm; at 236 px the "%"
     * ended 6 px from the edge). See [chordWidth].
     */
    /**
     * Glass/Orbit UX round 3: the footer is no longer an always-on line (the default basal left it, see
     * [basalInFooter]); it moves 8 px down to y 402 so the stats row above can drop 12 px and the chart gets air. Its
     * width is the chord at the new cap bottom (about y 411) less the 16 px margin.
     */
    const val FOOTER_Y = 402f
    const val EDGE_MARGIN = 16f
    const val FOOTER_MAX_WIDTH = 188f

    /** Width of the screen chord at [y] (450 grid) less [margin] to the round edge on each side. */
    fun chordWidth(y: Float, margin: Float = EDGE_MARGIN): Float {
        val r = 225f - margin
        val dy = y - 225f
        return if (abs(dy) >= r) 0f else 2f * kotlin.math.sqrt(r * r - dy * dy)
    }

    /**
     * Footer text size and the floor a W1-W6 owner text may shrink to before it is ellipsised (UX round 3: 22/18 ->
     * 26/22, a 1.4 mm cap height: the footer is Glass's only W-level message). Next to a W7-W9 warning the TT, basal,
     * reservoir and phone-battery runs are dropped first ([footerRuns]), so the warning keeps its size.
     */
    const val FOOTER_SIZE = 26f
    const val FOOTER_MIN_SIZE = 22f

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

    /**
     * UX round 3: the decimal separator of the BG ("6,4", "6.4") is set tight: each side loses [SEPARATOR_KERN] em,
     * so the German comma no longer reads as "6 , 4". Returns (before, separator, after), or null without one.
     * UX round 4: a positive value here TIGHTENS (it is subtracted on both sides); 0.06 -> 0.07 at the larger hero.
     */
    fun splitDecimal(s: String): Triple<String, String, String>? {
        val i = s.indexOfFirst { it == ',' || it == '.' }
        if (i <= 0 || i >= s.length - 1) return null
        return Triple(s.substring(0, i), s.substring(i, i + 1), s.substring(i + 1))
    }

    const val SEPARATOR_KERN = 0.07f

    /**
     * Hue of the BG digits (spec §4.1, F1): low and urgent low are red, never white; stale grey.
     * Glass/Orbit UX round 2 (sunlight): high and very high are amber digits (the glow alone washed out outdoors, so 6,4
     * and 12,4 looked alike); in range is a pale mint (the family maps hue to state the same way on all three faces,
     * Glass keeps its calmer, lighter tint).
     */
    /**
     * Glass/Orbit UX round 6: VERY_STALE (30' or more) is a step quieter than STALE: grey digits at 60 %, so a
     * 45' old value no longer reads like a current one.
     */
    enum class DigitTone { IN, HIGH, LOW, STALE, VERY_STALE }

    fun digitTone(range: Range, stale: Boolean, veryStale: Boolean = false): DigitTone = when {
        stale && veryStale                                 -> DigitTone.VERY_STALE
        stale                                              -> DigitTone.STALE
        range == Range.LOW || range == Range.URGENT_LOW    -> DigitTone.LOW
        range == Range.HIGH || range == Range.VERY_HIGH    -> DigitTone.HIGH
        else                                               -> DigitTone.IN
    }

    /**
     * BG digits: start size, step, floor and the widest allowed text with and without the trend chip (spec §4.1).
     * UX round 2: 116 -> 128 px (about 91 px cap height, Halo's size): the trend chip shrinks to r 18 and the TIEF/HOCH
     * word moved to the delta line, so the digits get 216 px; without the chip the full inner card width (12 px padding).
     * UX round 4: 128 -> 150 px (about 107 px cap height, the digits fill about two thirds of the card), 240 px next to
     * the chip, 300 px without it (the card widened to x 63..387).
     * Glass rework: 150 -> 134 px (about 95 px cap height, Halo's scale; 107 px read "too big"), with 16 px of glass
     * above the cap so the digits no longer touch the card's top edge or its rim glow.
     * Glass polish round 1 (owner on the watch: "stuffed into the card, too big"): 134 -> 112 px, an 80 px cap, about
     * 52 % of the 154 px card, with about 19 px of glass above the cap and 17 px under the delta row.
     */
    const val BG_SIZE = 112f
    const val BG_SIZE_STEP = 2f
    const val BG_MIN_SIZE = 80f
    /** Glass polish round 1: the card's inner width (300) less the chip (r 25) and its 18 px gap. */
    const val BG_MAX_WIDTH = 230f // Glass polish round 1: 230, the widest group (digits, gap, chip) centred on the card
    const val BG_MAX_WIDTH_NO_CHIP = 300f

    /**
     * BG text size on the 450 grid: [BG_SIZE], stepping down 2 px until the text measured at that size is at most
     * [maxWidth], floor [BG_MIN_SIZE]. Decided by measured width, never by character count. [measureAt] returns the
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

    /**
     * Delta as sent by the phone (already signed, phone locale); a missing sign is added; placeholders "--".
     * Glass live round 1: a zero delta is a reading too ("BG steady"), never blank: an unsigned or "+" zero becomes
     * "±0,0" (separator as sent); "-0,0" stays as the phone sends it (the rounded value was falling).
     */
    fun deltaText(delta: String): String {
        val d = FaceState.deltaText(delta)
        if (d == FaceState.NO_DATA || !isZero(d)) return d
        return if (d[0] == '+') "±" + d.substring(1) else d
    }

    private fun isZero(d: String): Boolean = d.any { it.isDigit() } && d.none { it in '1'..'9' }

    /**
     * Which delta string to show (key_show_detailed_delta / key_show_avg_delta), as the stock faces do. Glass live
     * round 1: a picked variant the phone did not fill (placeholder) falls back to the plain delta, so the line keeps
     * a number.
     */
    fun pickDelta(delta: String, avgDelta: String, deltaDetailed: String, avgDeltaDetailed: String, detailed: Boolean, avg: Boolean): String {
        val picked = FaceState.pickDelta(delta, avgDelta, deltaDetailed, avgDeltaDetailed, detailed, avg)
        return if (FaceState.display(picked) == FaceState.NO_DATA) delta else picked
    }

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

    /**
     * Loop pill value: "2′", "--" when never, [oldWord] above 99'. No bare "!" (spec: never a carrier on its own);
     * a bad loop is carried by the ring of the dot and the status slot text, also in ambient.
     */
    fun loopText(ageMinutes: Long?, oldWord: String): String = when {
        ageMinutes == null             -> "--"
        ageMinutes > MAX_SHOWN_MIN     -> oldWord
        else                           -> "$ageMinutes$PRIME"
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
        val cut = ellipsizeKeepTail(first.text, budget - (width - measure(first.text)), measure)
        return FooterFit(size, listOf(first.copy(text = cut)) + runs.drop(1))
    }

    const val ELLIPSIS = "…"

    /** Separator before the age token of a W-level text ("Telefon getrennt · 14′"). */
    const val TAIL_SEP = " · "

    /**
     * UX round 4 (safety): like [ellipsize], but a trailing " · <age>" token is never cut: only the label before it
     * is shortened ("Phone disc… · 14′"), so the age of a lost phone or of missing data always stays on screen.
     */
    fun ellipsizeKeepTail(s: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(s) <= maxWidth) return s
        val i = s.lastIndexOf(TAIL_SEP)
        if (i <= 0) return ellipsize(s, maxWidth, measure)
        val tail = s.substring(i)
        val label = ellipsize(s.substring(0, i), maxWidth - measure(tail), measure)
        return label + tail
    }

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

    /**
     * Plot rect on the 450 grid: 36 history slots (5', 3 h) and, when a forecast is drawn, [SPARK_FORECAST] future
     * slots at the SAME time scale. With the bolus ball the plot starts right of it ([SPARK_X0_BALL]).
     *
     * History: UX round 3 dropped the plot plate (plate + band + hairlines read as an empty progress bar); values sit
     * [SPARK_INSET] px inside the plot so the line and the now dot's glow never touch the card or the pills.
     * Glass polish round 1 (owner: "the line doesn't look correct"): linear scale, smooth trace, no dashed now line.
     * Glass polish round 2: the y range always contains the target band (both edges inside the plot, the line's height
     * says "near low / near high" at a glance), the forecast is 30 min at the history's time scale (it took a third of
     * the plot for one faint line), and without a forecast now sits at [SPARK_X1_NOW], clear of the card's corner.
     * Glass polish round 3: the forecast is 45 min (9 slots; the 30 min stub read as an ellipsis after the now dot).
     * Geometry with the ball and a forecast: history x 144..328 (184 px), forecast 328..376; without the ball now is
     * at about x 314.
     */
    const val SPARK_X0 = 72f
    const val SPARK_X0_BALL = 144f // Glass polish round 1: 140 -> 144, 17 px clear of the ball
    const val SPARK_Y0 = 294f // 8 px under the card
    const val SPARK_X1 = 376f // Glass polish round 1: 380 -> 376, the forecast's end keeps 30 px to the bezel
    const val SPARK_X1_NOW = 368f // Glass polish round 2: now without a forecast, 19 px left of the card's corner
    const val SPARK_Y1 = 354f
    const val SPARK_INSET = 8f
    const val SPARK_HISTORY = 36
    const val SPARK_FORECAST = 9 // Glass polish round 2: 12 -> 6 (30 min); round 3: 9 (45 min), the history's 5' pitch
    const val SPARK_TOTAL = SPARK_HISTORY + SPARK_FORECAST
    const val SLOT_MS = 5 * MINUTE_MS

    /** A gap longer than this breaks the line (graph.gaps). */
    const val GAP_MS = 15 * MINUTE_MS

    /**
     * Y range (linear). Safety (Glass/Orbit UX round 2): the range always covers every visible value (history, current
     * reading, forecast) within 40-400 mg/dL, so a very high or urgent low trace is never clamped flat at the edge.
     * Glass polish round 2: it also always contains the target band plus [SPARK_BAND_MARGIN_MGDL], so both band edges
     * are inside the plot (the reference never runs off it) and a flat BG sits at its true height in the band; with the
     * default band (70-180) 0,2 mmol/l of noise moves the line about 1,2 px. [SPARK_MIN_SPAN_MGDL] (5 mmol/l) still
     * holds for a narrow band. Without values the range is the default target band with a margin.
     * Glass polish round 3: the margin 10 -> 15 mg/dL (both edges about 5 px inside the plot, room for the trace above
     * and below), and as soon as a visible value is below the low line the range starts at [SPARK_FLOOR_MGDL], so the
     * depth under the low edge is on a fixed scale (2,6 and 3,5 look clearly different, a hypo is never understated).
     */
    const val SPARK_BAND_MARGIN_MGDL = 15.0
    const val SPARK_MIN_SPAN_MGDL = 90.0
    const val SPARK_FLOOR_MGDL = 40.0
    const val SPARK_CEIL_MGDL = 400.0
    const val SPARK_EMPTY_LO = 60.0
    const val SPARK_EMPTY_HI = 190.0

    /**
     * (lo, hi) of the plot for the visible values [minMgdl]..[maxMgdl] (NaN when there are none) and the target band
     * [lowMgdl]..[highMgdl].
     */
    fun sparkRange(
        minMgdl: Double, maxMgdl: Double, lowMgdl: Double = DEFAULT_LOW_MGDL, highMgdl: Double = DEFAULT_HIGH_MGDL
    ): Pair<Double, Double> {
        if (minMgdl.isNaN() || maxMgdl.isNaN()) return SPARK_EMPTY_LO to SPARK_EMPTY_HI
        var lo = if (minMgdl < lowMgdl) SPARK_FLOOR_MGDL
        else min(minMgdl, lowMgdl - SPARK_BAND_MARGIN_MGDL).coerceAtLeast(SPARK_FLOOR_MGDL)
        var hi = max(maxMgdl, highMgdl + SPARK_BAND_MARGIN_MGDL).coerceAtMost(SPARK_CEIL_MGDL).coerceAtLeast(lo)
        if (hi - lo < SPARK_MIN_SPAN_MGDL) {
            val mid = (lo + hi) / 2.0
            lo = mid - SPARK_MIN_SPAN_MGDL / 2.0
            hi = mid + SPARK_MIN_SPAN_MGDL / 2.0
            if (lo < SPARK_FLOOR_MGDL) { hi += SPARK_FLOOR_MGDL - lo; lo = SPARK_FLOOR_MGDL }
            if (hi > SPARK_CEIL_MGDL) { lo -= hi - SPARK_CEIL_MGDL; hi = SPARK_CEIL_MGDL }
        }
        return lo to hi
    }

    /**
     * x of slot [slot] (fractional), "now" is slot 35; with [forecast] the [SPARK_FORECAST] future slots follow up to
     * [SPARK_X1], else now is the plot's right end at [SPARK_X1_NOW].
     */
    fun sparkX(slot: Float, x0: Float = SPARK_X0, forecast: Boolean = true): Float {
        val last = if (forecast) SPARK_TOTAL - 1 else SPARK_HISTORY - 1
        val x1 = if (forecast) SPARK_X1 else SPARK_X1_NOW
        return x0 + slot / last * (x1 - x0)
    }

    /** x of the now dot (about 314 with a forecast, 328 with the ball too, [SPARK_X1_NOW] without a forecast). */
    fun nowX(x0: Float = SPARK_X0, forecast: Boolean = true): Float = sparkX((SPARK_HISTORY - 1).toFloat(), x0, forecast)

    /** y for a value in mg/dL, linear over [lo]..[hi] inside the plot less [SPARK_INSET], NOT clamped (band edges). */
    fun sparkYRaw(mgdl: Double, lo: Double, hi: Double): Float {
        val top = SPARK_Y0 + SPARK_INSET
        val bottom = SPARK_Y1 - SPARK_INSET
        val f = if (hi > lo) (mgdl - lo) / (hi - lo) else 0.5
        return (bottom - f * (bottom - top)).toFloat()
    }

    /** y for a value in mg/dL, clamped to the plot less [SPARK_INSET] at the top and bottom. */
    fun sparkY(mgdl: Double, lo: Double = SPARK_EMPTY_LO, hi: Double = SPARK_EMPTY_HI): Float =
        sparkYRaw(mgdl.coerceIn(lo, hi), lo, hi)

    /**
     * Forecast dots (Glass polish round 3): dot centres along the polyline [xy] (n points, x/y pairs, the first is the
     * now dot), the first [start] px from it (clear of the now dot's ring and glow), then one every [step] px of path
     * length. Written to [out] as x/y pairs; returns the number of dots (at most out.size / 2).
     */
    fun forecastDots(xy: FloatArray, n: Int, start: Float, step: Float, out: FloatArray): Int {
        var count = 0
        var next = start
        var walked = 0f
        for (i in 1 until n) {
            val x0 = xy[2 * i - 2]; val y0 = xy[2 * i - 1]
            val dx = xy[2 * i] - x0; val dy = xy[2 * i + 1] - y0
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len <= 0f) continue
            while (next <= walked + len + 0.01f && 2 * count + 1 < out.size) {
                val f = ((next - walked) / len).coerceIn(0f, 1f)
                out[2 * count] = x0 + f * dx
                out[2 * count + 1] = y0 + f * dy
                count++
                next += step
            }
            walked += len
        }
        return count
    }

    /** A band edge at [y] is drawn only inside the plot (an edge outside the range is not a line at the plot's border). */
    fun bandEdgeVisible(y: Float): Boolean = y > SPARK_Y0 + 1f && y < SPARK_Y1 - 1f

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

    /**
     * Hit test of the ball (r=[BALL_HIT_R], 24 dp at xhdpi, around the centre) clipped to the round screen (r=225).
     * UX round 4: clipped at [top] (2 px under the hero card, whose double tap opens the menu) and at [right] (8 px
     * left of the graph; with the ball shown the pill row also starts right of it), so no other zone ever arms a bolus.
     */
    fun ballHit(
        x: Float, y: Float, cx: Float = BALL_X, cy: Float = BALL_Y, r: Float = BALL_HIT_R,
        top: Float = BALL_HIT_TOP, right: Float = BALL_HIT_RIGHT
    ): Boolean {
        val dx = x - cx
        val dy = y - cy
        val sx = x - 225f
        val sy = y - 225f
        return y >= top && x <= right && dx * dx + dy * dy <= r * r && sx * sx + sy * sy <= 225f * 225f
    }

    /** Fraction (1..0) of the armed countdown ring still to show. */
    fun armProgress(armedAt: Long, now: Long, windowMs: Long): Float =
        if (windowMs <= 0) 0f else (1f - (now - armedAt).toFloat() / windowMs).coerceIn(0f, 1f)

    /**
     * UX round 2: docked at the left end of the sparkline band (the plot starts right of it).
     * UX round 4: (90, 322); Glass rework (92, 324), 10 px under the card, 30 px or more from the bezel; its hit area reaches 78 px down from
     * the card to the bezel and 88 px across, the pill row moves right of it.
     */
    const val BALL_X = 96f // Glass/Orbit UX round 6: (96, 325) r 31 (was (90, 322) r 28): a larger visible target, 13 px clear of the chart, 30 px to the bezel. Glass/Orbit UX round 4: (90, 322), 8 px under the taller card, 30 px to the bezel. Before: Glass/Orbit UX round 2: (92, 324) -> (88, 318): 12 px to the graph at 128, 31 px to the bezel
    const val BALL_Y = 325f
    const val BALL_R = 31f

    /** Pill row band (UX round 4: borderless; Glass/Orbit UX round 3: text centred at y 372, 12 px lower). */
    const val PILL_Y0 = 362f
    const val PILL_Y = 372f

    /**
     * Hit-area clips (fixed values: the target of a dosing control must not move as a side effect of a layout change).
     * The top was 2 px under the card when the card ended at 286 (it now ends at [HERO_Y1] 280, so 8 px under it);
     * the right edge is 12 px left of the graph ([SPARK_X0_BALL]). Glass polish round 2: the right edge is a literal
     * 132 again (round 1 derived it from SPARK_X0_BALL 144 and widened the target by 4 px by accident).
     */
    const val BALL_HIT_TOP = 288f
    const val BALL_HIT_RIGHT = 132f

    /** Hit radius on the 450 grid at xhdpi: 48 px = 24 dp, the family's 48 dp target (the face scales it to stay 24 dp). */
    const val BALL_HIT_R = 48f

    // ------------------------------------------------------------------------------------------- layout variants

    /**
     * Hero card positions (spec §3, §4.3; UX round 2): the BG digits left, the small trend chip in the right column
     * level with the digits, and the delta · age line (with the TIEF/HOCH pill after it) at full width under the
     * digits, 8 px or more below the comma. Without the chip (key_show_direction off) the digits centre. An empty
     * meta line (no delta, no age, no caption) centres the digits vertically.
     */
    data class HeroLayout(
        val bgX: Float, val bgY: Float, val bgMaxWidth: Float, val chipY: Float, val metaCentreX: Float, val metaY: Float,
        val chipX: Float = CHIP_X
    )

    /**
     * UX round 3: the card is y 120..274 (the time and date moved below the system status icon).
     * UX round 4: x 63..387, y 116..284 for the 150 px digits; the delta line at y 266, about 8 px under the comma.
     * Glass rework: y 120..286 (14 px under the date); 134 px digits with the cap from y 133 to the baseline at 228,
     * the chip centred on the cap (y 180), the delta line at y 268: 10 px between the comma's tail and the delta.
     */
    /**
     * Glass/Orbit UX round 2: the card is 4 px shorter (y 120..282, the digits 2 px up) and the date 1 px up, so the date
     * keeps 15 px of air above the card's highlight and the lower stack gets room; the digits stay 134 px (measured on
     * the renders: the "6" is 98 px tall, Halo's 101 px).
     */
    const val HERO_X0 = 63f
    const val HERO_X1 = 387f
    // Glass live round 1: the card's top 120 -> 126 (the time and date moved below the Samsung status icon); the
    // digits 5 px and the meta row 3 px down, the bottom (and everything under it) unchanged
    // Glass polish round 1: 126 -> 132 with the time block (time 13 px clear of the status icon's disc); the card is
    // 154 px tall, the 112 px digits (cap y 151..230) and the 26 px delta row (centre 260) with ~19 / ~17 px margins
    // Glass polish round 2: the bottom 286 -> 280 and the content 4 px up, so the bolus ball (top at 294) keeps 14 px
    // to the card; margins inside: ~15 px over the cap, ~14 px under the delta row (the comma-to-delta gap unchanged)
    const val HERO_Y0 = 132f
    const val HERO_Y1 = 280f
    // Glass polish round 3: the meta row 256 -> 258, 7 px of air under the comma's tail (margin under the row ~16 px)
    const val META_Y = 258f

    /** Digits' centre next to the chip (Glass/Orbit UX round 4: 198 -> 200, equal optical padding left and right). */
    const val BG_X_CHIP = 191f // Glass polish round 1: the widest group (230 px digits, gap, chip) centred on the card

    /** Trend chip column (UX round 4: r 18 -> 24). */
    /** Glass/Orbit UX round 6: r 24 -> 28 at x 348, the arrow about 0.6 of the disc (the main signal after the BG). */
    /** Glass polish round 1: r 28 -> 25 with the smaller digits; [CHIP_X] is the widest group's chip (see [heroGroup]). */
    const val CHIP_X = 349f
    const val CHIP_R = 25f
    const val CHIP_GAP = 18f

    /** Digits' vertical centre with the delta row, and centred in the card without it (Glass polish round 2: 4 px / 3 px up). */
    const val BG_Y = 188f
    const val BG_Y_NO_META = 208f

    fun heroLayout(showChip: Boolean, metaEmpty: Boolean): HeroLayout = when {
        showChip  -> if (metaEmpty) HeroLayout(BG_X_CHIP, BG_Y_NO_META, BG_MAX_WIDTH, BG_Y_NO_META - 2f, 225f, META_Y) else HeroLayout(BG_X_CHIP, BG_Y, BG_MAX_WIDTH, BG_Y - 2f, 225f, META_Y)
        metaEmpty -> HeroLayout(225f, BG_Y_NO_META, BG_MAX_WIDTH_NO_CHIP, BG_Y_NO_META - 2f, 225f, META_Y)
        else      -> HeroLayout(225f, BG_Y, BG_MAX_WIDTH_NO_CHIP, BG_Y - 2f, 225f, META_Y)
    }

    /**
     * Glass polish round 1: digits and chip are ONE group centred on the card (with the chip pinned right, "6,4" sat
     * left of centre with a wide hole before the chip). [bgWidth] is the measured width of the fitted digits.
     */
    fun heroGroup(l: HeroLayout, showChip: Boolean, bgWidth: Float): HeroLayout {
        if (!showChip) return l
        val w = bgWidth.coerceAtMost(l.bgMaxWidth) + CHIP_GAP + 2f * CHIP_R
        val left = 225f - w / 2f
        return l.copy(bgX = left + bgWidth.coerceAtMost(l.bgMaxWidth) / 2f, chipX = left + w - CHIP_R)
    }

    /**
     * UX round 3: the trend chip is drawn only for a current reading. A struck (stale) reading and "---" centre the
     * digits with the age under them (as Halo), instead of a lone age in the chip corner or an empty hollow chip.
     */
    fun chipShown(pref: Boolean, struck: Boolean, bgText: String): Boolean = pref && !struck && bgText != "---"

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

    /**
     * age.none / first start: no delta, no age, the meta line is empty (spec §4.4). Glass live round 1: a fresh
     * reading always shows its delta, as Halo does (key_show_delta off left a lone "0′" under the digits on the watch).
     */
    fun metaVisible(age: BgAge, prefShowAgo: Boolean): Pair<Boolean, Boolean> =
        if (age == BgAge.NONE) false to false
        else !isStale(age) to showAge(prefShowAgo, age)

    /**
     * Glass/Orbit UX round 2 (one owner per fact, as Halo): when W2/W3 own the slot ("Keine Daten · 17′", "Telefon
     * getrennt · 14′") that text goes to the card's META row under the struck digits, and the footer keeps its normal
     * line (TT, basal). Returns the warning for the META row, or null.
     */
    fun metaWarning(owner: Warning?): Warning? = owner?.takeIf { it.dataOld }

    /**
     * Warnings for the footer: none while a W2/W3 owner sits in the META row ([metaWarning]).
     * Glass/Orbit UX round 3 (one owner per fact): interactive, a bad loop ("Loop 23′") is said once, by the red loop
     * glyph and its red age in the stats row right above; the footer keeps only non-numeric states ("Loop aus", "Kein
     * Profil", "Reservoir 5 U"). Ambient has no hue, so there the footer keeps it.
     */
    fun footerWarnings(warnings: List<Warning>, ambient: Boolean = true): List<Warning> = when {
        metaWarning(warnings.firstOrNull()) != null -> emptyList()
        ambient                                     -> warnings
        else                                        -> warnings.filter { it != Warning.LOOP_BAD }
    }

    /**
     * Glass/Orbit UX round 3 (seven text tiers were too many): the basal joins the footer only when it says something,
     * a TBR in percent or a zero rate; the plain profile rate ("0,85 U/h") is no longer always on.
     */
    fun basalInFooter(pref: Boolean, basal: String): Boolean = pref && basalTone(basal).let { it == Tone.TBR || it == Tone.WARN }

    /** Text of a W2/W3 [owner] for the META row, as the footer would have shown it. */
    fun warningText(owner: Warning, i: FooterInput, words: SlotWords): Run? = warningRun(owner, i, words)

    /**
     * The loop glyph is greyed (and the age dimmed) while W2/W3 own the slot: the loop age then only repeats that the
     * phone or the data is gone (UX round 2; the hollow-dot rule of the earlier dot).
     */
    fun loopGlyphMuted(owner: Warning?): Boolean = metaWarning(owner) != null

    /** The LOOP/LGS label is hidden only for an open loop ("Offen" fills the pill); never dropped for width (spec F14). */
    fun loopLabelAllowed(health: LoopHealth): Boolean = health != LoopHealth.OPEN

    /** Pill row width budget on the 450 grid (the chord at the pill row's lower edge less the rounded ends). */
    const val PILL_ROW_MAX_WIDTH = 270f // Glass/Orbit UX round 3: the row is 12 px lower: x 90..360 (16 px from the edge at y 384)

    /**
     * UX round 4: with the ball shown the pill row moves right of its hit area: x 140..392 (the chord at the row's
     * lower edge), centred at 266.
     */
    const val PILL_ROW_BALL_X0 = SPARK_X0_BALL
    const val PILL_ROW_BALL_X1 = 360f // Glass/Orbit UX round 3: 372 -> 360 with the lower row, 16 px from the round edge

    /** (centre, max width) of the pill row with or without the ball. */
    fun pillRowFrame(ballShown: Boolean): Pair<Float, Float> =
        if (ballShown) (PILL_ROW_BALL_X0 + PILL_ROW_BALL_X1) / 2f to (PILL_ROW_BALL_X1 - PILL_ROW_BALL_X0) else 225f to PILL_ROW_MAX_WIDTH

    /**
     * Left edges of the pills of the given [widths], centred on [centre] with [gap] between them. Zero widths are
     * skipped (their left edge is NaN).
     */
    fun pillRow(widths: FloatArray, gap: Float, centre: Float = 225f): FloatArray {
        val shown = widths.filter { it > 0f }
        val total = shown.sum() + gap * (shown.size - 1).coerceAtLeast(0)
        var x = centre - total / 2f
        return FloatArray(widths.size) { i ->
            if (widths[i] <= 0f) Float.NaN else x.also { x += widths[i] + gap }
        }
    }

    // ------------------------------------------------------------------------------------------- ambient range shape

    enum class OutlineStyle { NONE, SOLID, DASHED, DOUBLE }

    /** Ambient grey of an outline: ambLine (24 %), amb3 (43 %), amb2, amb1 (white in low-bit). */
    enum class AmbTone { LINE, A3, A2, A1 }

    /**
     * Hero card outline in ambient (spec §9: the range as a shape, colour is never the only carrier).
     * [innerWidth] is the inner line of a double outline (5 px inside). [boldBg]: BG digits weight 600.
     */
    data class AmbientOutline(val style: OutlineStyle, val width: Float, val innerWidth: Float, val tone: AmbTone, val boldBg: Boolean)

    const val DOUBLE_OUTLINE_GAP = 5f

    /**
     * UX round 1: the outline stays a quiet 1 px line (never the brightest thing on the screen); the shape (dashed,
     * double) carries the range, the BG digits and the TIEF/HOCH word carry the weight.
     * UX round 3: urgent low is ONE 2 px line (the double 1+1 px line plus the boxed TIEF read heavy), so low (double
     * thin line, plain TIEF word) and urgent low (single heavy line, double-outlined TIEF badge) differ by shape, not
     * only by grey level; in low-bit both are white.
     */
    /**
     * Glass/Orbit UX round 6: urgent low is a 3 px line (2 px read too close to low's double line), and with burn-in
     * protection the static card outline is dropped for in range, high and stale (only the temporary lows keep it).
     */
    fun ambientOutline(range: Range, lowBit: Boolean, burnIn: Boolean = false): AmbientOutline =
        if (burnIn && range != Range.LOW && range != Range.URGENT_LOW) AmbientOutline(OutlineStyle.NONE, 0f, 0f, AmbTone.LINE, false)
        else ambientOutlineByRange(range, lowBit)

    private fun ambientOutlineByRange(range: Range, lowBit: Boolean): AmbientOutline = when (range) {
        Range.IN, Range.STALE -> if (lowBit) AmbientOutline(OutlineStyle.NONE, 0f, 0f, AmbTone.LINE, false)
                                 else AmbientOutline(OutlineStyle.SOLID, 1f, 0f, AmbTone.LINE, false)
        Range.HIGH            -> AmbientOutline(OutlineStyle.DASHED, 1f, 0f, AmbTone.LINE, false)
        Range.VERY_HIGH       -> AmbientOutline(OutlineStyle.DASHED, 1f, 0f, AmbTone.A3, false)
        Range.LOW             -> AmbientOutline(OutlineStyle.DOUBLE, 1f, 1f, AmbTone.LINE, false)
        Range.URGENT_LOW      -> AmbientOutline(OutlineStyle.SOLID, 3f, 0f, AmbTone.A3, true)
    }

    /** Random burn-in offset in [-4, +4] px from a 0..1 source. */
    fun burnInOffset(random01: Double): Float = (min(1.0, max(0.0, random01)) * 8.0 - 4.0).toFloat()
}
