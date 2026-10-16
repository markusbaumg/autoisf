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
 * All glucose values are mg/dL (as sent by the phone in SingleBg.sgv/low/high); the displayed strings come
 * from the phone in the user's unit and locale and are shown as sent (German keeps its decimal comma,
 * designsystem/states.md units.phone_locale); only placeholders are mapped to "--".
 */
object GlassFaceLogic {

    const val MINUTE_MS = 60_000L

    /** BG age (minutes) from which the reading is stale: grey aurora, struck-through value. */
    const val STALE_MIN = 12L

    /** BG age (minutes) from which the age turns amber. */
    const val AGE_WARN_MIN = 6L

    /** Loop age: amber from 6', red from 15'. */
    const val LOOP_WARN_MIN = 6L
    const val LOOP_BAD_MIN = 15L

    /** From 10' the "LOOP" label is hidden to make room for the two-digit value. */
    const val LOOP_LABEL_HIDE_MIN = 10L

    /** Minutes above this are shown as "old" instead of a number, so the width stays bounded. */
    const val MAX_SHOWN_MIN = 99L

    /** No Status/SingleBg from the phone for longer than this: "Phone disconnected". */
    const val DISCONNECT_MS = 12 * MINUTE_MS

    /** Footer: widest line before optional runs are dropped (the chord at y=390 allows about 310 px). */
    const val FOOTER_MAX_WIDTH = 300f

    /** Fallback low/high marks when the phone did not send them (3.9 / 10.0 mmol/L). */
    const val DEFAULT_LOW_MGDL = 70.0
    const val DEFAULT_HIGH_MGDL = 180.0

    // ------------------------------------------------------------------------------------------- BG state

    enum class Range { IN, HIGH, LOW, STALE }

    enum class Level { NORMAL, WARN, ALERT }

    /** True when there is no reading or it is at least [STALE_MIN] minutes old. */
    fun isStale(bgTimeStamp: Long, now: Long): Boolean = bgTimeStamp == 0L || now - bgTimeStamp >= STALE_MIN * MINUTE_MS

    /** Whole minutes since [timeStamp] (never negative), or null without a time stamp. */
    fun ageMinutes(timeStamp: Long, now: Long): Long? = if (timeStamp <= 0L) null else max(0L, (now - timeStamp) / MINUTE_MS)

    /**
     * Range key of the aurora and the accents. The phone's low/high marks are used when present,
     * otherwise its own sgvLevel (-1 low, 1 high).
     */
    fun range(sgvMgdl: Double, lowMgdl: Double, highMgdl: Double, sgvLevel: Long, stale: Boolean): Range = when {
        stale                                -> Range.STALE
        lowMgdl > 0.0 && highMgdl > lowMgdl -> when {
            sgvMgdl > highMgdl -> Range.HIGH
            sgvMgdl < lowMgdl  -> Range.LOW
            else               -> Range.IN
        }

        sgvLevel > 0L                        -> Range.HIGH
        sgvLevel < 0L                        -> Range.LOW
        else                                 -> Range.IN
    }

    /** Colour level of the BG age: normal below 6', warn from 6', alert from 12' (or no reading). */
    fun ageLevel(ageMinutes: Long?): Level = when {
        ageMinutes == null           -> Level.ALERT
        ageMinutes >= STALE_MIN      -> Level.ALERT
        ageMinutes >= AGE_WARN_MIN   -> Level.WARN
        else                         -> Level.NORMAL
    }

    /** "1'", "--" without a value, [oldWord] above [MAX_SHOWN_MIN]. */
    fun minutesText(minutes: Long?, oldWord: String): String = when {
        minutes == null           -> "--"
        minutes > MAX_SHOWN_MIN   -> oldWord
        else                      -> "$minutes'"
    }

    /** BG text size on the 450 grid: 84 px up to 3 characters ("9.4", "180"), 74 px for 4 ("11.8"), 64 px beyond. */
    fun bgTextSize(value: String): Float = when {
        value.length <= 3 -> 84f
        value.length == 4 -> 74f
        else              -> 64f
    }

    /** Scale [size] down so that a text measured as [width] at [size] fits [maxWidth]. */
    fun fitSize(size: Float, width: Float, maxWidth: Float): Float =
        if (width <= maxWidth || width <= 0f) size else size * maxWidth / width

    /** Trend arrow for the chip, or "" for unknown/stale (empty chip). Text presentation selector appended. */
    fun arrow(slopeArrow: String, stale: Boolean): String {
        if (stale) return ""
        val a = slopeArrow.trim()
        return if (a in KNOWN_ARROWS) a + "︎" else ""
    }

    private val KNOWN_ARROWS = setOf("→", "↗", "↑", "⇈", "↘", "↓", "⇊")

    /** Delta as sent by the phone (already signed, 1 decimal mmol / 0 decimals mg/dL); a missing sign is added. */
    fun deltaText(delta: String): String {
        val d = delta.trim()
        if (d.isEmpty() || d.startsWith("--")) return "--"
        return if (d[0] == '+' || d[0] == '-' || d[0] == '±') d else "+$d"
    }

    // ------------------------------------------------------------------------------------------- loop / pills

    /** Loop age in minutes from Status.openApsStatus (-1 or 0: no loop run). */
    fun loopAgeMinutes(openApsStatus: Long, now: Long): Long? = if (openApsStatus <= 0L) null else max(0L, (now - openApsStatus) / MINUTE_MS)

    /**
     * Loop dot colour: ok < 6', warn 6..14', alert >= 15'. Loop disabled/suspended: warn. Never ran
     * (openApsStatus -1, e.g. pump control or a client without devicestatus): null = neutral grey, never red.
     */
    fun loopLevel(ageMinutes: Long?, loopDisabled: Boolean): Level? = when {
        loopDisabled                       -> Level.WARN
        ageMinutes == null                 -> null
        ageMinutes >= LOOP_BAD_MIN         -> Level.ALERT
        ageMinutes >= LOOP_WARN_MIN        -> Level.WARN
        else                               -> Level.NORMAL
    }

    /**
     * The phone puts text into Status.externalStatus only when the loop is disabled/suspended or there is no
     * profile (DataHandlerMobile.generateStatusString); the watch's empty store holds "no status".
     */
    fun loopDisabled(externalStatus: String): Boolean = externalStatus.isNotBlank() && externalStatus.trim() != "no status"

    /** The "LOOP" label is hidden once the value has two digits (or is the "old" word). */
    fun loopLabelVisible(ageMinutes: Long?): Boolean = ageMinutes == null || ageMinutes < LOOP_LABEL_HIDE_MIN

    /** Low-bit ambient: the loop value gets a "!" from 15'. */
    fun loopText(ageMinutes: Long?, oldWord: String, lowBit: Boolean): String {
        val t = minutesText(ageMinutes, oldWord)
        return if (lowBit && ageMinutes != null && ageMinutes >= LOOP_BAD_MIN) "$t!" else t
    }

    /** IOB as sent by the phone; placeholders of an empty store ("IOB", "-.--", "") become "--". */
    fun iobText(iobSum: String): String = display(iobSum)

    /** Phone string trimmed; the placeholders of the empty store and "??" become "--". */
    fun display(s: String): String {
        val t = s.trim()
        return if (t.isEmpty() || t in PLACEHOLDERS || t.none { it.isDigit() }) "--" else t
    }

    private val PLACEHOLDERS = setOf("IOB", "-.--", "--g", "-.--U/h", "no status", "??", "--")

    // ------------------------------------------------------------------------------------------- footer

    enum class RunKind { TT, COB, BASAL, PROFILE, PHONE_BATTERY, RESERVOIR, DISCONNECTED, WAITING }

    data class Run(val text: String, val kind: RunKind, val level: Level = Level.NORMAL)

    /** Localised words used in the footer ("Tel %d%%", "Telefon getrennt", ...). */
    data class FooterWords(
        val phoneBattery: (Int) -> String,
        val disconnected: (String) -> String,
        val cob: (Int) -> String,
        val reservoir: (String) -> String,
        val oldWord: String,
        val waiting: String
    )

    data class FooterInput(
        val tempTarget: String,
        val tempTargetLevel: Int,
        val cob: String,
        val currentBasal: String,
        val profilePercent: Int?,
        val phoneBattery: String,
        val reservoirString: String,
        val reservoirLevel: Int,
        val lastDataTimeStamp: Long,
        val now: Long
    )

    /** True when nothing came from the phone for more than [DISCONNECT_MS] (or never). */
    fun isDisconnected(lastDataTimeStamp: Long, now: Long): Boolean = lastDataTimeStamp <= 0L || now - lastDataTimeStamp > DISCONNECT_MS

    /** Grams of COB from the phone's COB string ("24g", "24(10)g", "--g"); null when not readable. */
    fun cobGrams(cob: String): Int? = cob.trim().takeWhile { it.isDigit() }.toIntOrNull()

    /** Phone battery percent from Status.battery ("18"), or null; "0" means unknown. */
    fun phoneBatteryPercent(battery: String): Int? = battery.trim().removeSuffix("%").toIntOrNull()?.takeIf { it in 1..100 }

    /** Warning level of the phone battery: alert below 10 %, warn below 20 %, else none (null). */
    fun phoneBatteryLevel(percent: Int?): Level? = when {
        percent == null -> null
        percent < 10    -> Level.ALERT
        percent < 20    -> Level.WARN
        else            -> null
    }

    /** The phone's basal with a space before the unit ("0,00 U/h", "1.35U/h" -> "1.35 U/h", "90%"). */
    fun basalText(currentBasal: String): String {
        val s = display(currentBasal)
        if (s == "--") return s
        val i = s.indexOf("U/h")
        return if (i > 0 && s[i - 1] != ' ') s.substring(0, i) + " " + s.substring(i) else s
    }

    /** "12U" -> "12 U". */
    fun reservoirText(reservoirString: String): String {
        val s = reservoirString.trim()
        return if (s.endsWith("U") && s.length > 1 && s[s.length - 2] != ' ') s.dropLast(1) + " U" else s
    }

    /**
     * Footer runs in display order. With a lost phone the whole line is "Phone disconnected · 14'".
     * When the joined line is wider than [maxWidth], the profile run, then the COB run are dropped.
     * [measure] returns the width of a text at the footer size; [separator] is the joiner ("  ·  ").
     */
    fun footerRuns(input: FooterInput, words: FooterWords, separator: String, measure: (String) -> Float, maxWidth: Float = FOOTER_MAX_WIDTH): List<Run> {
        if (input.lastDataTimeStamp <= 0L) return listOf(Run(words.waiting, RunKind.WAITING))
        if (isDisconnected(input.lastDataTimeStamp, input.now))
            return listOf(Run(words.disconnected(minutesText(ageMinutes(input.lastDataTimeStamp, input.now), words.oldWord)), RunKind.DISCONNECTED, Level.ALERT))
        val runs = ArrayList<Run>(6)
        if (input.tempTargetLevel == 2 && input.tempTarget.isNotBlank() && input.tempTarget != "--")
            runs += Run("TT " + input.tempTarget.trim(), RunKind.TT)
        cobGrams(input.cob)?.takeIf { it > 0 }?.let { runs += Run(words.cob(it), RunKind.COB) }
        runs += Run(basalText(input.currentBasal), RunKind.BASAL)
        input.profilePercent?.takeIf { it != 100 }?.let { runs += Run("$it%", RunKind.PROFILE) }
        val batt = phoneBatteryPercent(input.phoneBattery)
        phoneBatteryLevel(batt)?.let { runs += Run(words.phoneBattery(batt!!), RunKind.PHONE_BATTERY, it) }
        if (input.reservoirLevel >= 1 && input.reservoirString.isNotBlank() && input.reservoirString != "--")
            runs += Run(words.reservoir(reservoirText(input.reservoirString)), RunKind.RESERVOIR, if (input.reservoirLevel >= 2) Level.ALERT else Level.WARN)

        for (drop in arrayOf(RunKind.PROFILE, RunKind.COB)) {
            if (lineWidth(runs, separator, measure) <= maxWidth) break
            runs.removeAll { it.kind == drop }
        }
        return runs
    }

    fun lineWidth(runs: List<Run>, separator: String, measure: (String) -> Float): Float {
        var w = 0f
        runs.forEachIndexed { i, r -> if (i > 0) w += measure(separator); w += measure(r.text) }
        return w
    }

    // ------------------------------------------------------------------------------------------- time / date

    /** "HH:mm" (24 h) or "h:mm" (12 h) without allocation-heavy formatters. */
    fun timeText(hour: Int, minute: Int, is24Hour: Boolean): String {
        val h = if (is24Hour) hour else (hour % 12).let { if (it == 0) 12 else it }
        val hh = if (is24Hour && h < 10) "0$h" else "$h"
        val mm = if (minute < 10) "0$minute" else "$minute"
        return "$hh:$mm"
    }

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

    /** Y scale 3.0 .. 12.0 mmol/L in mg/dL. */
    const val SPARK_MIN_MGDL = 54.0
    const val SPARK_MAX_MGDL = 216.0

    /** x of slot [i] (fractional), "now" is slot 35. */
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

    /** Fraction (1..0) of the armed countdown ring still to show. */
    fun armProgress(armedAt: Long, now: Long, windowMs: Long): Float =
        if (windowMs <= 0) 0f else (1f - (now - armedAt).toFloat() / windowMs).coerceIn(0f, 1f)

    // ------------------------------------------------------------------------------------------- bolus ball

    /**
     * Hardened rules for the double-tap bolus REQUEST (shared rule set of the Halo/Glass/Orbit faces):
     * the preference is on (default off), Wear control is enabled on the phone, the face is interactive
     * (never in ambient), the phone is connected and its data fresh, and the amount does not exceed the
     * phone's max bolus (key_treatments_safety_max_bolus; unknown max = not allowed).
     */
    fun bolusAllowed(prefOn: Boolean, wearControl: Boolean, ambient: Boolean, disconnected: Boolean, amount: Double, maxBolus: Double): Boolean =
        prefOn && wearControl && !ambient && !disconnected && amount > 0.0 && maxBolus > 0.0 && amount <= maxBolus + 1e-9

    /** "+0.5" / "+1.0". */
    fun bolusAmountText(amount: Double): String {
        val tenths = Math.round(abs(amount) * 10).toInt()
        return "+${tenths / 10}.${tenths % 10}"
    }

    /** Hit test of the ball (r=44 around the centre) clipped to the round screen (r=225). */
    fun ballHit(x: Float, y: Float, cx: Float = BALL_X, cy: Float = BALL_Y, r: Float = BALL_HIT_R): Boolean {
        val dx = x - cx
        val dy = y - cy
        val sx = x - 225f
        val sy = y - 225f
        return dx * dx + dy * dy <= r * r && sx * sx + sy * sy <= 225f * 225f
    }

    /** Hardened gesture timing: second tap within 800 ms, then 5 s cooldown (BolusTapGesture defaults are 1500/3000). */
    const val BOLUS_WINDOW_MS = 800L
    const val BOLUS_COOLDOWN_MS = 5000L

    const val BALL_X = 46f
    const val BALL_Y = 176f
    const val BALL_R = 30f
    const val BALL_HIT_R = 44f

    /** Random burn-in offset in [-4, +4] px from a 0..1 source. */
    fun burnInOffset(random01: Double): Float = (min(1.0, max(0.0, random01)) * 8.0 - 4.0).toFloat()
}
