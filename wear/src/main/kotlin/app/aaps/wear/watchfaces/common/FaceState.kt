package app.aaps.wear.watchfaces.common

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The derived signals of the Halo / Glass / Orbit face family (designsystem/states.md §1, §4.2, §4.3), computed in
 * ONE place so the three faces can never disagree. Pure Kotlin (no Android types), unit tested.
 *
 * Each face only decides where and in which shape a state is drawn; what state applies, which colour token it gets
 * and what wins when several apply comes from here:
 * - BG age class: FRESH < 6′ · AGING 6-11′ · STALE 12-29′ · VERY_STALE >= 30′ · NONE (no reading).
 * - Range: URGENT_LOW (< 54 mg/dL, or low and falling fast) · LOW · IN · HIGH · VERY_HIGH (> 250 mg/dL). Stale beats
 *   range: from 12′ the range is STALE (no range hue anywhere).
 * - Loop health from the real loop run time (Status.lastLoopRunAt): OK <= 5′ · WARN 6-14′ · BAD >= 15′. An older phone
 *   app only sends the last temp basal enact (openApsStatus), which stays old on a stable loop: then 13′ / 20′.
 * - Phone link (reachability + data age), the status-slot warning W1..W10, the TIEF / HOCH caption.
 *
 * All glucose values are mg/dL. Display strings come from the phone in the user's unit and locale and are shown as
 * sent (German keeps its decimal comma); only placeholders become "--". Geometry never parses display strings,
 * except the IOB arc, which only has the string (comma or dot accepted).
 */
object FaceState {

    const val MINUTE_MS = 60_000L

    /** BG age (minutes). */
    const val AGING_MIN = 6L
    const val STALE_MIN = 12L
    const val VERY_STALE_MIN = 30L

    /** From this age the BG digits are replaced by "---" (an hour-old value is not a value any more). */
    const val BG_HIDE_MIN = 60L

    /** Urgency levels derived on the watch (contract gap G1, fixed until they become watch settings). */
    const val URGENT_LOW_MGDL = 54.0
    const val VERY_HIGH_MGDL = 250.0
    const val SENTINEL_LOW_MGDL = 39.0
    const val SENTINEL_HIGH_MGDL = 400.0

    /** |delta| per 5 min that counts as a fast fall / rise. */
    const val FAST_DELTA_MGDL = 10.0

    /** Fallback target marks when the phone did not send them. */
    const val DEFAULT_LOW_MGDL = 70.0
    const val DEFAULT_HIGH_MGDL = 180.0

    /** Loop age (minutes) from the real loop run time: WARN from 6′, BAD from 15′. */
    const val LOOP_WARN_MIN = 6L
    const val LOOP_BAD_MIN = 15L

    /** Loop age from an older phone app (last temp basal enact, not the run): WARN from 13′, BAD from 20′. */
    const val LEGACY_LOOP_WARN_MIN = 13L
    const val LEGACY_LOOP_BAD_MIN = 20L

    /** No Status / SingleBg from the phone for this long: the data counts as not fresh (phoneLink). */
    const val DATA_FRESH_MS = 12 * MINUTE_MS

    /** A LoopStatesList older than the last status by more than this is not trusted (loopMode). */
    const val LOOP_STATES_MAX_AGE_MS = 15 * MINUTE_MS

    const val PRIME = "′"
    const val NO_DATA = "--"
    const val NO_BG = "---"

    // ------------------------------------------------------------------------------------------- BG age

    enum class BgAge { FRESH, AGING, STALE, VERY_STALE, NONE }

    /** Whole minutes since [timeStamp] (a time stamp in the future counts as 0), or null without a reading. */
    fun ageMinutes(timeStamp: Long, now: Long): Long? = if (timeStamp <= 0L) null else max(0L, (now - timeStamp) / MINUTE_MS)

    fun bgAge(ageMinutes: Long?): BgAge = when {
        ageMinutes == null             -> BgAge.NONE
        ageMinutes < AGING_MIN         -> BgAge.FRESH
        ageMinutes < STALE_MIN         -> BgAge.AGING
        ageMinutes < VERY_STALE_MIN    -> BgAge.STALE
        else                           -> BgAge.VERY_STALE
    }

    /** STALE, VERY_STALE and NONE: no range hue, no trend, no delta. */
    fun isOld(age: BgAge): Boolean = age == BgAge.STALE || age == BgAge.VERY_STALE || age == BgAge.NONE

    /** Strike-through only for a real but old reading (NONE shows "---"). */
    fun isStruck(age: BgAge): Boolean = age == BgAge.STALE || age == BgAge.VERY_STALE

    /** Colour level of a label: NORMAL (txt2), WARN (amber, "look soon"), ALERT (red, "look now"). */
    enum class Level { NORMAL, WARN, ALERT }

    /** BG age label: normal while fresh, warn from 6′, alert from 12′ and without a reading. */
    fun ageLevel(age: BgAge): Level = when (age) {
        BgAge.FRESH                                  -> Level.NORMAL
        BgAge.AGING                                  -> Level.WARN
        BgAge.STALE, BgAge.VERY_STALE, BgAge.NONE    -> Level.ALERT
    }

    // ------------------------------------------------------------------------------------------- range

    enum class Range { IN, HIGH, VERY_HIGH, LOW, URGENT_LOW, STALE }

    fun lowLine(low: Double): Double = if (low > 0.0) low else DEFAULT_LOW_MGDL
    fun highLine(low: Double, high: Double): Double = if (high > lowLine(low)) high else DEFAULT_HIGH_MGDL

    /** The phone's sgvLevel from the target marks (-1 below low, 1 above high), as DataHandlerMobile computes it. */
    fun levelFromMarks(sgv: Double, low: Double, high: Double): Long = when {
        sgv < lowLine(low)        -> -1L
        sgv > highLine(low, high) -> 1L
        else                      -> 0L
    }

    /**
     * Range of the BG accent from [sgv] (mg/dL) and the phone's [sgvLevel]. Urgent low: below 54 mg/dL, or a low that
     * falls fast. Very high: above 250 mg/dL. Only for FRESH / AGING; older (or no) data is STALE.
     */
    fun range(sgv: Double, sgvLevel: Long, age: BgAge, fastFall: Boolean): Range {
        if (age != BgAge.FRESH && age != BgAge.AGING) return Range.STALE
        return when {
            sgv > 0.0 && sgv < URGENT_LOW_MGDL       -> Range.URGENT_LOW
            sgvLevel < 0L && fastFall                -> Range.URGENT_LOW
            sgvLevel < 0L                            -> Range.LOW
            sgv > VERY_HIGH_MGDL                     -> Range.VERY_HIGH
            sgvLevel > 0L                            -> Range.HIGH
            else                                     -> Range.IN
        }
    }

    fun isLow(r: Range): Boolean = r == Range.LOW || r == Range.URGENT_LOW
    fun isHigh(r: Range): Boolean = r == Range.HIGH || r == Range.VERY_HIGH

    /** The face's emphasis step (thicker ring / stronger aurora / corona): urgent low and very high. */
    fun emphasis(r: Range): Boolean = r == Range.URGENT_LOW || r == Range.VERY_HIGH

    enum class Sentinel { LOW, HIGH }

    /** The CGM's limits ("39" / "400"): only for a current reading. */
    fun sentinel(sgv: Double, age: BgAge): Sentinel? = when {
        isOld(age) || sgv <= 0.0     -> null
        sgv <= SENTINEL_LOW_MGDL     -> Sentinel.LOW
        sgv >= SENTINEL_HIGH_MGDL    -> Sentinel.HIGH
        else                         -> null
    }

    /**
     * Word next to the BG digits, so colour is never the only carrier (non-optional, no pref):
     * LOW_WORD "TIEF" for a low, LOW_BADGE "TIEF" in a filled box for an urgent low, HIGH_WORD "HOCH" above 250 mg/dL.
     * Nothing for in range, high (the number says it) and old data.
     */
    enum class Caption { NONE, LOW_WORD, LOW_BADGE, HIGH_WORD }

    fun caption(range: Range): Caption = when (range) {
        Range.LOW        -> Caption.LOW_WORD
        Range.URGENT_LOW -> Caption.LOW_BADGE
        Range.VERY_HIGH  -> Caption.HIGH_WORD
        else             -> Caption.NONE
    }

    /** Hero BG text: "---" without a reading or from 60′; otherwise the phone string, "+" appended at the high sentinel. */
    fun bgText(sgvString: String, sgv: Double, ageMinutes: Long?): String {
        val s = sgvString.trim()
        if (ageMinutes == null || ageMinutes >= BG_HIDE_MIN || s.isEmpty() || s.none { it.isDigit() }) return NO_BG
        return if (sgv >= SENTINEL_HIGH_MGDL && !s.endsWith("+")) "$s+" else s
    }

    // ------------------------------------------------------------------------------------------- trend

    enum class Trend(val fast: Boolean, val rising: Boolean, val falling: Boolean) {
        NONE(false, false, false), FLAT(false, false, false),
        UP45(false, true, false), UP(false, true, false), UP2(true, true, false), UP3(true, true, false),
        DOWN45(false, false, true), DOWN(false, false, true), DOWN2(true, false, true), DOWN3(true, false, true)
    }

    /** Trend from the phone's arrow; "X" takes the delta's sign; anything else ("??", "--", unknown) is NONE. */
    fun trend(slopeArrow: String, deltaMgdl: Double?): Trend = when (slopeArrow.trim().removeSuffix("︎")) {
        "⇈"  -> Trend.UP2
        "↑"  -> Trend.UP
        "↗"  -> Trend.UP45
        "→"  -> Trend.FLAT
        "↘"  -> Trend.DOWN45
        "↓"  -> Trend.DOWN
        "⇊"  -> Trend.DOWN2
        "X"  -> when {
            deltaMgdl == null -> Trend.NONE
            deltaMgdl < 0     -> Trend.DOWN3
            else              -> Trend.UP3
        }

        else -> Trend.NONE
    }

    fun fastFall(trend: Trend, deltaMgdl: Double?): Boolean =
        trend == Trend.DOWN2 || trend == Trend.DOWN3 || (deltaMgdl != null && deltaMgdl <= -FAST_DELTA_MGDL)

    fun fastRise(trend: Trend, deltaMgdl: Double?): Boolean =
        trend == Trend.UP2 || trend == Trend.UP3 || (deltaMgdl != null && deltaMgdl >= FAST_DELTA_MGDL)

    // ------------------------------------------------------------------------------------------- text

    private val PLACEHOLDERS = setOf("IOB", "-.--", "--g", "-.--U/h", "no status", "??", "--", "---")

    /** Phone string trimmed; empty, placeholders of the empty store and digit-free strings become "--". */
    fun display(s: String?): String {
        val t = s?.trim() ?: return NO_DATA
        return if (t.isEmpty() || t in PLACEHOLDERS || t.none { it.isDigit() }) NO_DATA else t
    }

    /** Delta as sent (signed, phone locale); a missing sign is added; placeholders "--". */
    fun deltaText(delta: String): String {
        val d = display(delta)
        if (d == NO_DATA) return d
        return if (d[0] == '+' || d[0] == '-' || d[0] == '±' || d[0] == '−') d else "+$d"
    }

    /** Which delta string to show (key_show_detailed_delta / key_show_avg_delta), as the stock faces do. */
    fun pickDelta(delta: String, avgDelta: String, deltaDetailed: String, avgDeltaDetailed: String, detailed: Boolean, avg: Boolean): String =
        when {
            detailed && avg -> avgDeltaDetailed
            detailed        -> deltaDetailed
            avg             -> avgDelta
            else            -> delta
        }

    /** Leading number of a phone string with either decimal separator ("2,53", "-0.35", "0,85U/h", "120%"), or null. */
    fun parseNumber(s: String?): Double? {
        val t = display(s)
        if (t == NO_DATA) return null
        val m = Regex("""[-+−]?\d+([.,]\d+)?""").find(t) ?: return null
        return m.value.replace('−', '-').replace(',', '.').toDoubleOrNull()
    }

    /** German / SI spacing between number and unit: "0,85U/h" -> "0,85 U/h", "120%" -> "120 %"; placeholders "--". */
    fun spacedUnit(s: String?): String {
        val t = display(s)
        if (t == NO_DATA) return t
        return Regex("""(\d)(U/h|E/h|U|E|%|g)$""").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
    }

    /** IOB / basal / COB tone, shared so the faces colour the same value the same way. */
    enum class Tone { TXT1, TXT2, TXT3, INS, TBR, WARN }

    /** IOB value: ins when positive, txt1 when negative, dim for "0,00" and unknown. */
    fun iobTone(iobSum: String): Tone {
        val v = parseNumber(iobSum) ?: return Tone.TXT3
        return when {
            abs(v) < 0.005 -> Tone.TXT3
            v < 0          -> Tone.TXT1
            else           -> Tone.INS
        }
    }

    /** IOB arc fraction: value / 6 U, capped; <= 0 or unknown empty. */
    fun iobFraction(iobSum: String): Float {
        val v = parseNumber(iobSum) ?: return 0f
        return if (v <= 0.0) 0f else (v / 6.0).coerceAtMost(1.0).toFloat()
    }

    /**
     * Basal: TBR percent in tbr ("0 %" warn); a zero absolute basal in warn (zero temp or zero profile both deserve a
     * look), except during a fresh low, where withholding insulin is the right thing (then txt2); unknown dim.
     */
    fun basalTone(basal: String, freshLow: Boolean = false): Tone {
        val b = display(basal)
        val v = parseNumber(b)
        return when {
            b == NO_DATA               -> Tone.TXT3
            b.endsWith("%")            -> if (v == 0.0 && !freshLow) Tone.WARN else Tone.TBR
            v == 0.0                   -> if (freshLow) Tone.TXT2 else Tone.WARN
            else                       -> Tone.TXT2
        }
    }

    /** Phone battery percent from Status.battery ("18"), or null; "0" means unknown. */
    fun phoneBatteryPercent(battery: String): Int? = battery.trim().removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 1..100 }

    /** Localised words. Defaults are German (the family's design language); faces pass their resources. */
    data class Words(
        val minutes: String = "%d′",
        val hours: String = "%d Std",
        val days: String = "%d T",
        val waiting: String = "Warte auf AAPS",
        val phoneLost: String = "Telefon getrennt · %s",
        val noData: String = "Keine Daten · %s",
        val noProfile: String = "Kein Profil",
        val loopOff: String = "Loop aus",
        val loopAge: String = "Loop %s",
        val reservoir: String = "Reservoir %s",
        val phoneBattery: String = "Tel %d %%",
        val low: String = "TIEF",
        val high: String = "HOCH"
    )

    /** "17′", hours from 60′ ("3 Std"), days from 48 h ("2 T"), "--" when unknown. */
    fun ageText(minutes: Long?, words: Words): String = when {
        minutes == null     -> NO_DATA
        minutes >= 48 * 60  -> String.format(Locale.ROOT, words.days, minutes / 1440)
        minutes >= 60       -> String.format(Locale.ROOT, words.hours, minutes / 60)
        else                -> String.format(Locale.ROOT, words.minutes, minutes)
    }

    // ------------------------------------------------------------------------------------------- loop

    /** Loop time: the real loop run time (Status.lastLoopRunAt) or, from an older phone app, the last TBR enact. */
    data class LoopTime(val time: Long, val realRun: Boolean)

    fun loopTime(lastLoopRunAt: Long, openApsStatus: Long): LoopTime =
        if (lastLoopRunAt > 0L) LoopTime(lastLoopRunAt, true) else LoopTime(openApsStatus, false)

    /** Loop age in whole minutes, or null when there was no loop run (-1 / 0). */
    fun loopAgeMinutes(loopTime: Long, now: Long): Long? = if (loopTime <= 0L) null else max(0L, (now - loopTime) / MINUTE_MS)

    enum class LoopRun { RUNNING, OFF, NO_PROFILE }

    /**
     * The phone puts text into Status.externalStatus only when there is no profile (one localised line, and no IOB
     * either) or when the loop is not running (the localised "disabled" line ending with "\n"). The watch's empty
     * store holds "no status".
     */
    fun loopRun(externalStatus: String, iobSum: String): LoopRun = when {
        externalStatus.isBlank() || externalStatus.trim() == "no status" -> LoopRun.RUNNING
        externalStatus.endsWith("\n")                                    -> LoopRun.OFF
        iobSum.isBlank()                                                 -> LoopRun.NO_PROFILE
        else                                                             -> LoopRun.OFF
    }

    /** First start: nothing ever arrived (no BG, no loop run, IOB placeholder). */
    fun firstStart(bgTimeStamp: Long, loopTime: Long, iobSum: String): Boolean =
        bgTimeStamp <= 0L && loopTime <= 0L && iobSum.trim().let { it == "IOB" || it.isEmpty() }

    /** No profile: the phone says so, or sent an empty IOB (never during the first start). */
    fun noProfile(run: LoopRun, iobSum: String, firstStart: Boolean): Boolean = !firstStart && (run == LoopRun.NO_PROFILE || iobSum.isEmpty())

    enum class LoopMode { CLOSED, OPEN, LGS, UNKNOWN }

    /**
     * Loop mode from the offered LoopStatesList entries: the one of CLOSED / OPEN / LGS that is missing is the current
     * one. UNKNOWN when the list is missing, ambiguous or older than the last status by more than 15′.
     */
    fun loopMode(offered: Collection<String>, listTimeStamp: Long, lastStatus: Long): LoopMode {
        if (listTimeStamp <= 0L || offered.isEmpty()) return LoopMode.UNKNOWN
        if (lastStatus > 0L && lastStatus - listTimeStamp > LOOP_STATES_MAX_AGE_MS) return LoopMode.UNKNOWN
        val missing = listOf("LOOP_CLOSED" to LoopMode.CLOSED, "LOOP_OPEN" to LoopMode.OPEN, "LOOP_LGS" to LoopMode.LGS)
            .filter { it.first !in offered }
        return if (missing.size == 1) missing[0].second else LoopMode.UNKNOWN
    }

    /**
     * Loop indicator: NEUTRAL (no profile) · OFF (disabled / suspended) · NEVER (no loop run: grey, never red) ·
     * OPEN (open loop: the age is no alarm) · OK · WARN · BAD by age ([realRun]: 6′ / 15′, else 13′ / 20′).
     */
    enum class LoopHealth { OK, WARN, BAD, NEVER, OFF, OPEN, NEUTRAL }

    fun loopHealth(ageMinutes: Long?, run: LoopRun, mode: LoopMode, realRun: Boolean = true): LoopHealth {
        val warn = if (realRun) LOOP_WARN_MIN else LEGACY_LOOP_WARN_MIN
        val bad = if (realRun) LOOP_BAD_MIN else LEGACY_LOOP_BAD_MIN
        return when {
            run == LoopRun.NO_PROFILE  -> LoopHealth.NEUTRAL
            run == LoopRun.OFF         -> LoopHealth.OFF
            ageMinutes == null         -> LoopHealth.NEVER
            mode == LoopMode.OPEN      -> LoopHealth.OPEN
            ageMinutes >= bad          -> LoopHealth.BAD
            ageMinutes >= warn         -> LoopHealth.WARN
            else                       -> LoopHealth.OK
        }
    }

    // ------------------------------------------------------------------------------------------- phone link

    enum class PhoneLink { OK, LOST_FRESH, LOST_STALE, SILENT }

    fun dataFresh(lastDataTimeStamp: Long, now: Long): Boolean = lastDataTimeStamp > 0L && now - lastDataTimeStamp < DATA_FRESH_MS

    /**
     * OK: reachable and data < 12′ · LOST_FRESH: unreachable, data still fresh · LOST_STALE: unreachable and old data
     * ("disconnected") · SILENT: reachable but nothing new for 12′ (AAPS killed, no CGM, NS down).
     */
    fun phoneLink(reachable: Boolean, lastDataTimeStamp: Long, now: Long): PhoneLink {
        val fresh = dataFresh(lastDataTimeStamp, now)
        return when {
            reachable && fresh -> PhoneLink.OK
            reachable          -> PhoneLink.SILENT
            fresh              -> PhoneLink.LOST_FRESH
            else               -> PhoneLink.LOST_STALE
        }
    }

    /** An UNKNOWN reachability answer (first lookup still running) does not warn. */
    fun phoneLink(reach: PhoneReach, lastDataTimeStamp: Long, now: Long): PhoneLink =
        phoneLink(reach != PhoneReach.UNREACHABLE, lastDataTimeStamp, now)

    // ------------------------------------------------------------------------------------------- status slot

    /** What owns the status slot (states.md §4.2), highest rank first. */
    enum class Warning(val rank: Int) {
        WAITING(1), PHONE_LOST(2), NO_DATA(3), NO_PROFILE(4), LOOP_OFF(5), LOOP_BAD(6),
        RES_CRITICAL(7), PHONE_BATTERY(8), RES_LOW(9), PHONE_LOST_FRESH(10);

        /** W2 / W3: the data itself is old; the face shows the age with the warning instead of trend and delta. */
        val dataOld: Boolean get() = this == PHONE_LOST || this == NO_DATA

        /** Kept in ambient (W1-W6). */
        val ambient: Boolean get() = rank <= 6
    }

    data class WarningInput(
        val firstStart: Boolean,
        val link: PhoneLink,
        val bgAge: BgAge,
        val range: Range,
        val noProfile: Boolean,
        val loop: LoopHealth,
        val reservoirString: String = "",
        val reservoirLevel: Int = 0,
        val phoneBattery: String = "",
        val phoneBatteryLevel: Int = 1
    )

    /**
     * All active warnings, highest rank first. A fresh low suppresses W7-W10 (the user sees one thing; W1-W6 always
     * show, they say the shown BG or the loop cannot be trusted).
     */
    fun warnings(i: WarningInput): List<Warning> {
        val w = ArrayList<Warning>(4)
        if (i.firstStart) w += Warning.WAITING
        if (!i.firstStart && i.link == PhoneLink.LOST_STALE) w += Warning.PHONE_LOST
        if (!i.firstStart && i.link != PhoneLink.LOST_STALE && (i.link == PhoneLink.SILENT || isStruck(i.bgAge))) w += Warning.NO_DATA
        if (i.noProfile) w += Warning.NO_PROFILE
        if (i.loop == LoopHealth.OFF) w += Warning.LOOP_OFF
        if (i.loop == LoopHealth.BAD) w += Warning.LOOP_BAD
        val res = display(i.reservoirString) != NO_DATA
        if (i.reservoirLevel == 2 && res) w += Warning.RES_CRITICAL
        if (i.phoneBatteryLevel == 0 && phoneBatteryPercent(i.phoneBattery) != null) w += Warning.PHONE_BATTERY
        if (i.reservoirLevel == 1 && res) w += Warning.RES_LOW
        if (i.link == PhoneLink.LOST_FRESH) w += Warning.PHONE_LOST_FRESH
        if (isLow(i.range)) w.removeAll { it.rank > 6 }
        return w
    }

    /** The warning that owns the slot (ambient keeps W1-W6), or null. */
    fun owner(warnings: List<Warning>, ambient: Boolean): Warning? = warnings.firstOrNull { !ambient || it.ambient }

    /** One line of status text and its colour level (WAITING is NORMAL: nothing is wrong yet). */
    data class StatusText(val warning: Warning, val text: String, val level: Level)

    /**
     * Slot text of [w]: "Telefon getrennt · 14′" (data age), "Keine Daten · 17′" (BG age, or the data age when newer
     * data never came), "Loop 23′", "Reservoir 5 U", "Tel 18 %"; null for W10 (a glyph, no text).
     */
    fun statusText(
        w: Warning, words: Words, dataAgeMinutes: Long?, bgAgeMinutes: Long?, loopAgeMinutes: Long?, reservoirString: String, phoneBattery: String
    ): StatusText? = when (w) {
        Warning.WAITING          -> StatusText(w, words.waiting, Level.NORMAL)
        Warning.PHONE_LOST       -> StatusText(w, String.format(Locale.ROOT, words.phoneLost, ageText(dataAgeMinutes, words)), Level.ALERT)
        Warning.NO_DATA          -> {
            val age = if (bgAgeMinutes == null && dataAgeMinutes == null) null else max(bgAgeMinutes ?: 0L, dataAgeMinutes ?: 0L)
            StatusText(w, String.format(Locale.ROOT, words.noData, ageText(age, words)), Level.ALERT)
        }

        Warning.NO_PROFILE       -> StatusText(w, words.noProfile, Level.WARN)
        Warning.LOOP_OFF         -> StatusText(w, words.loopOff, Level.WARN)
        Warning.LOOP_BAD         -> StatusText(w, String.format(Locale.ROOT, words.loopAge, ageText(loopAgeMinutes, words)), Level.ALERT)
        Warning.RES_CRITICAL     -> StatusText(w, String.format(Locale.ROOT, words.reservoir, spacedUnit(reservoirString)), Level.ALERT)
        Warning.PHONE_BATTERY    -> phoneBatteryPercent(phoneBattery)?.let {
            StatusText(w, String.format(Locale.ROOT, words.phoneBattery, it), if (it < 10) Level.ALERT else Level.WARN)
        }

        Warning.RES_LOW          -> StatusText(w, String.format(Locale.ROOT, words.reservoir, spacedUnit(reservoirString)), Level.WARN)
        Warning.PHONE_LOST_FRESH -> null
    }

    /**
     * Red budget (states.md §3 rule 3): while the slot says the data is old (W2 / W3) or names the loop (W6), the loop
     * element does not add its own red; it is drawn neutral.
     */
    fun loopNeutral(owner: Warning?): Boolean = owner == Warning.PHONE_LOST || owner == Warning.NO_DATA || owner == Warning.LOOP_BAD

    // ------------------------------------------------------------------------------------------- bolus ball / ambient

    /** "+0,5" / "+1,0" with the watch locale's decimal separator (a number the watch generates). */
    fun bolusAmountText(amount: Double, locale: Locale): String {
        val tenths = Math.round(abs(amount) * 10).toInt()
        val sep = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return "+${tenths / 10}$sep${tenths % 10}"
    }

    /** Maximum burn-in shift (design px) in each direction. */
    const val BURN_IN_MAX_SHIFT = 4

    /**
     * Burn-in shift for a minute: a deterministic hash of the minute key mapped to [-4, 4] per axis, so the offset
     * changes every minute and visits the whole square. [axis] 0 = x, 1 = y.
     */
    fun burnInShift(minuteKey: Long, axis: Int): Int {
        var h = minuteKey * 0x9E3779B97F4A7C15uL.toLong() + axis * 0x632BE59BD9B4E019L
        h = h xor (h ushr 31)
        h *= 0x7FB5D329728EA185L
        h = h xor (h ushr 27)
        val span = 2 * BURN_IN_MAX_SHIFT + 1
        return (Math.floorMod(h, span.toLong()) - BURN_IN_MAX_SHIFT).toInt()
    }
}
