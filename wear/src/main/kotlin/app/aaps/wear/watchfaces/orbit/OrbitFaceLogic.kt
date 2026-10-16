package app.aaps.wear.watchfaces.orbit

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/**
 * Pure state logic of the "AAPS Orbit" face (no Android types, unit tested).
 *
 * Geometry is on a 450 x 450 design grid, angles are degrees clockwise from 12 o'clock.
 */
object OrbitFaceLogic {

    const val CX = 225f
    const val CY = 225f
    const val ORBIT_R = 168f
    const val BALL_R = 34f
    const val BOLUS_HALF = 33f

    /** Ball positions on the orbit (degrees clockwise from 12). Never change between modes. */
    const val DEG_LOOP = 45f
    const val DEG_IOB = 90f
    const val DEG_BASAL = 135f
    const val DEG_BOLUS = 270f
    const val DEG_COB = 225f
    const val DEG_TT = 315f
    val BALL_DEGS = floatArrayOf(DEG_LOOP, DEG_IOB, DEG_BASAL, DEG_COB, DEG_TT)
    const val IDX_LOOP = 0
    const val IDX_IOB = 1
    const val IDX_BASAL = 2
    const val IDX_COB = 3
    const val IDX_TT = 4

    /** Stale thresholds in minutes since the last BG. */
    const val AGE_AMBER_AFTER_MIN = 5L   // > 5: age amber
    const val OLD_FROM_MIN = 12L         // >= 12: pill, grey hero, hollow now dot, no prediction, no corona
    const val STRIKE_AFTER_MIN = 15L     // > 15: strike-through

    /** No message from the phone for longer than this shows the "phone disconnected" note. */
    const val PHONE_LOST_MS = 5 * 60_000L

    /** Sparkline: fixed range 2.5..14.0 mmol/L (45..252 mg/dL), box x 151..291, y 275..321, now at x 265. */
    const val SPARK_MIN_MGDL = 45.0
    const val SPARK_MAX_MGDL = 252.0
    const val SPARK_X0 = 151f
    const val SPARK_X_NOW = 265f
    const val SPARK_X1 = 291f
    const val SPARK_Y_TOP = 275f
    const val SPARK_Y_BOTTOM = 321f
    const val SPARK_HISTORY_MS = 2 * 60 * 60_000L
    const val PREDICTION_DOTS = 5
    const val PREDICTION_STEP_MS = 6 * 60_000L

    /** Reservoir and phone battery warning limits. */
    const val RESERVOIR_WARN_U = 20.0
    const val PHONE_BATTERY_WARN = 20

    /** Width budgets (design px) for the worst-case text checks. */
    const val BALL_VALUE_MAX = 56f
    const val BALL_VALUE_HARD_MAX = 62f
    const val HERO_MAX_WIDTH = 200f
    const val DATE_LINE_MAX_WIDTH = 196f
    const val DELTA_ROW_MAX_WIDTH = 190f

    // ------------------------------------------------------------------ BG state

    enum class Freshness { FRESH, AGE_WARN, OLD, STRUCK, NONE }

    /** BG age in whole minutes, or null without a reading. */
    fun bgAgeMinutes(timeStamp: Long, now: Long): Long? = if (timeStamp <= 0L) null else max(0L, (now - timeStamp) / 60_000L)

    fun freshness(ageMinutes: Long?): Freshness = when {
        ageMinutes == null                -> Freshness.NONE
        ageMinutes > STRIKE_AFTER_MIN     -> Freshness.STRUCK
        ageMinutes >= OLD_FROM_MIN        -> Freshness.OLD
        ageMinutes > AGE_AMBER_AFTER_MIN  -> Freshness.AGE_WARN
        else                              -> Freshness.FRESH
    }

    /** Grey hero, pill, hollow now dot, no prediction and no corona. */
    fun isOld(f: Freshness): Boolean = f == Freshness.OLD || f == Freshness.STRUCK || f == Freshness.NONE

    enum class Range { IN, HIGH, LOW }

    /** The phone computes sgvLevel against its own low/high marks. */
    fun range(sgvLevel: Long): Range = when (sgvLevel.toInt()) {
        1    -> Range.HIGH
        -1   -> Range.LOW
        else -> Range.IN
    }

    // ------------------------------------------------------------------ strings that depend on the language

    /** Localised words, passed in so the logic stays pure. Defaults are German (the face's design language). */
    data class Labels(
        val profile: String = "Profil",
        val again: String = "nochmal",
        val oldFormat: String = "%d' alt",
        val noData: String = "keine Daten",
        val phoneLost: String = "Telefon getrennt",
        val loopOff: String = "AUS",
        val loopSuspended: String = "PAUSE",
        val loopDisconnected: String = "GETR.",
        val loopOpen: String = "OFFEN",
        val phoneBatteryFormat: String = "Akku %d%%",
        val reservoirFormat: String = "Res %dU",
        val pumpBattery: String = "Pumpe Akku"
    )

    // ------------------------------------------------------------------ delta row

    /** Interactive delta row: "+0.1 · " and the age "1'" (the age turns amber after 5 min). */
    data class DeltaRow(val deltaPart: String, val agePart: String, val ageAmber: Boolean)

    fun deltaRow(delta: String, ageMinutes: Long?): DeltaRow =
        DeltaRow(
            deltaPart = if (ageMinutes == null) delta else "$delta · ",
            agePart = ageMinutes?.let { "$it'" } ?: "",
            ageAmber = (ageMinutes ?: 0L) > AGE_AMBER_AFTER_MIN
        )

    /** Text of the red pill (interactive) from 12 min: "17' alt", or "keine Daten" without a reading. */
    fun oldPillText(ageMinutes: Long?, labels: Labels): String =
        ageMinutes?.let { String.format(Locale.ROOT, labels.oldFormat, it) } ?: labels.noData

    /** Ambient replacement of the delta row from 12 min: "– – 17' alt". */
    fun oldAmbientText(ageMinutes: Long?, labels: Labels): String = "– – " + oldPillText(ageMinutes, labels)

    // ------------------------------------------------------------------ balls

    enum class Ring { NEUTRAL, DIM, GREEN, AMBER, RED, CYAN, TEAL, VIOLET }

    /** One status ball. [word] uses the 17 px bold state-word style (LOOP non-numeric states). */
    data class Ball(val value: String, val label: String, val ring: Ring, val word: Boolean = false)

    enum class LoopWord { OFF, SUSPENDED, DISCONNECTED, OPEN }

    fun loopWordText(word: LoopWord, labels: Labels): String = when (word) {
        LoopWord.OFF          -> labels.loopOff
        LoopWord.SUSPENDED    -> labels.loopSuspended
        LoopWord.DISCONNECTED -> labels.loopDisconnected
        LoopWord.OPEN         -> labels.loopOpen
    }

    /** Loop age in whole minutes, or null when there is no loop run (-1 / 0). */
    fun loopAgeMinutes(openApsStatus: Long, now: Long): Long? =
        if (openApsStatus <= 0L) null else max(0L, (now - openApsStatus) / 60_000L)

    /** <= 5 green, 6..14 amber, >= 15 red. No loop run at all (-1) is unknown, so dim grey, never red. */
    fun loopRing(ageMinutes: Long?): Ring = when {
        ageMinutes == null -> Ring.DIM
        ageMinutes <= 5    -> Ring.GREEN
        ageMinutes < 15    -> Ring.AMBER
        else               -> Ring.RED
    }

    /** Compact loop age: "0'", "59'", then hours "1h", "12h". */
    fun loopAgeText(ageMinutes: Long?): String = when {
        ageMinutes == null -> "--"
        ageMinutes < 60    -> "$ageMinutes'"
        else               -> "${ageMinutes / 60}h"
    }

    /**
     * The phone only transmits "loop disabled" (as a non-empty externalStatus). Suspended, pump disconnected and
     * open loop are not part of the wear protocol yet; [word] lets a future status field select them.
     */
    fun loopWord(externalStatus: String): LoopWord? = if (externalStatus.isNotBlank()) LoopWord.OFF else null

    fun loopBall(openApsStatus: Long, externalStatus: String, now: Long, labels: Labels, word: LoopWord? = loopWord(externalStatus)): Ball {
        if (word != null)
            return Ball(loopWordText(word, labels), "LOOP", if (word == LoopWord.OPEN) Ring.AMBER else Ring.RED, word = true)
        val age = loopAgeMinutes(openApsStatus, now)
        return Ball(loopAgeText(age), "LOOP", loopRing(age))
    }

    /** Phone placeholders that must never be drawn ("IOB" before the first status, "-.--", "--g", "no status"). */
    fun display(s: String): String {
        val t = s.trim()
        return if (t.isEmpty() || t == "IOB" || t == "-.--" || t == "--g" || t == "-.--U/h" || t == "no status" || t == "??") "--" else t
    }

    fun iobBall(iobSum: String): Ball = Ball(display(iobSum), "IOB", Ring.CYAN)

    /** "0.00 U/h" -> "0.00", "120%" stays. */
    fun basalValue(currentBasal: String): String =
        display(display(currentBasal).replace("U/h", "").replace("E/h", ""))

    /** Leading number of "0.85 U/h", "0,00 U/h", "120%" or null. */
    fun leadingNumber(s: String): Double? {
        val m = Regex("""-?\d+([.,]\d+)?""").find(s) ?: return null
        return m.value.replace(',', '.').toDoubleOrNull()
    }

    /** True when a temp basal covers [now]. The phone paints the running TBR a few minutes into the future. */
    fun tbrRunning(starts: LongArray, ends: LongArray, now: Long): Boolean {
        for (i in starts.indices) if (starts[i] <= now && now <= ends[i]) return true
        return false
    }

    /** TBR > 0 teal, TBR = 0 amber (the loop is withholding insulin), no TBR neutral. */
    fun basalBall(currentBasal: String, tbr: Boolean): Ball {
        val value = basalValue(currentBasal)
        if (!tbr) return Ball(value, "BASAL", Ring.NEUTRAL)
        val rate = leadingNumber(currentBasal)
        return Ball(value, "TBR", if (rate != null && abs(rate) < 1e-6) Ring.AMBER else Ring.TEAL)
    }

    /** COB > 0 amber, else dim. "12(3)g" counts as 12. */
    fun cobBall(cob: String): Ball {
        val v = display(cob)
        val n = leadingNumber(v)
        return Ball(v, "COB", if (n != null && n > 0.0) Ring.AMBER else Ring.DIM)
    }

    /** "5.0 - 6.0" -> "5.0-6.0", "5.6" stays. */
    fun compactTarget(s: String): String = display(s).replace(" ", "")

    /**
     * Temp target active (level 2): violet "TT". Otherwise dim, the profile target with the label "Profil"
     * (the profile percentage is not transmitted to the watch).
     */
    fun ttBall(tempTarget: String, tempTargetLevel: Int, labels: Labels): Ball =
        if (tempTargetLevel == 2) Ball(compactTarget(tempTarget), "TT", Ring.VIOLET)
        else Ball(compactTarget(tempTarget), labels.profile, Ring.DIM)

    /** Value text size: 22 px; wider than 56 px at 22 -> 17 px bold; still too wide -> shrink to fit 62 px. */
    fun ballValueSize(widthAt22: Float): Float {
        if (widthAt22 <= BALL_VALUE_MAX) return 22f
        val at17 = widthAt22 * 17f / 22f
        return if (at17 <= BALL_VALUE_HARD_MAX) 17f else 17f * BALL_VALUE_HARD_MAX / at17
    }

    /** Shrinks a text size so a text measured at [size] fits [maxWidth]. */
    fun fitSize(widthAtSize: Float, size: Float, maxWidth: Float): Float =
        if (widthAtSize <= maxWidth || widthAtSize <= 0f) size else size * maxWidth / widthAtSize

    // ------------------------------------------------------------------ warnings on the date line

    /** At most one warning, priority: reservoir, phone battery, pump battery. Null when all is OK. */
    fun warning(reservoir: Double, phoneBattery: String, pumpBatteryLow: Boolean, labels: Labels): String? {
        if (reservoir > 0.0 && reservoir < RESERVOIR_WARN_U)
            return String.format(Locale.ROOT, labels.reservoirFormat, reservoir.toInt())
        val battery = phoneBattery.trim().removeSuffix("%").trim().toIntOrNull()
        if (battery != null && battery in 0 until PHONE_BATTERY_WARN)
            return String.format(Locale.ROOT, labels.phoneBatteryFormat, battery)
        if (pumpBatteryLow) return labels.pumpBattery
        return null
    }

    const val DATE_SEPARATOR = " · "

    // ------------------------------------------------------------------ time and date

    private val WEEKDAY = DateTimeFormatter.ofPattern("EE", Locale.GERMANY)
    private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.GERMANY)

    /** "Fr 17. Okt" (Locale.GERMANY, trailing dots of the abbreviations removed) from the renderer's date. */
    fun dateString(year: Int, month1: Int, day: Int): String {
        val d = LocalDate.of(year, month1, day)
        return "${WEEKDAY.format(d).trimEnd('.')} $day. ${MONTH.format(d).trimEnd('.')}"
    }

    /** "00:40" (24 h) or "12:40" (12 h, no AM/PM) from the renderer's time. */
    fun timeString(hour: Int, minute: Int, is24h: Boolean): String {
        val h = if (is24h) hour else (hour % 12).let { if (it == 0) 12 else it }
        return if (is24h) String.format(Locale.ROOT, "%02d:%02d", h, minute) else String.format(Locale.ROOT, "%d:%02d", h, minute)
    }

    /** Burn-in shift per minute: (0,0) -> (2,0) -> (2,2) -> (0,2). */
    fun burnInShiftX(minute: Int): Float = if (minute % 4 == 1 || minute % 4 == 2) 2f else 0f
    fun burnInShiftY(minute: Int): Float = if (minute % 4 == 2 || minute % 4 == 3) 2f else 0f

    // ------------------------------------------------------------------ orbit geometry

    fun pointX(r: Float, deg: Float): Float = CX + r * kotlin.math.sin(Math.toRadians(deg.toDouble())).toFloat()
    fun pointY(r: Float, deg: Float): Float = CY - r * kotlin.math.cos(Math.toRadians(deg.toDouble())).toFloat()

    /** Half angle (deg) of the track gap that holds a text of [textWidth]: (w + 20) / 2 / r rad, capped at [maxDeg]. */
    fun gapHalfDeg(textWidth: Float, maxDeg: Float = MAX_GAP_DEG): Float =
        min(Math.toDegrees(((textWidth + 20f) / 2f / ORBIT_R).toDouble()).toFloat(), maxDeg)

    /** Angular half width of a ball as seen from the centre. */
    val BALL_HALF_DEG: Float = Math.toDegrees(asin((BALL_R / ORBIT_R).toDouble())).toFloat()

    /** The gaps may grow until they touch the nearest ball (45 deg away from 0 and 180). */
    val MAX_GAP_DEG: Float = 45f - BALL_HALF_DEG - 0.5f

    /** Moon occlusion limit for a ball and for the bolus button. */
    val BALL_OCCLUDE_DEG: Float = BALL_HALF_DEG + 1.5f
    val BOLUS_OCCLUDE_DEG: Float = Math.toDegrees(atan((BOLUS_HALF * 1.1f / ORBIT_R).toDouble())).toFloat() + 1.5f

    /** Signed shortest angular difference a - b in (-180, 180]. */
    fun angleDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    enum class MoonKind { HIDDEN, DOT, RIM }

    /** [ball] is the index into [BALL_DEGS] for RIM; [rimDeg] is the arc centre on the ball rim (clockwise from 12). */
    data class Moon(val kind: MoonKind, val deg: Float, val ball: Int = -1, val rimDeg: Float = 0f)

    /**
     * Minute moon at 6 deg per minute. Hidden in the time/date gaps and behind the bolus button; behind a ball it
     * becomes a rim highlight that sweeps from the trailing side over the outer rim to the leading side.
     */
    fun moon(minute: Int, gapTimeDeg: Float, gapDateDeg: Float, bolusShown: Boolean): Moon {
        val deg = (minute % 60) * 6f
        if (abs(angleDiff(deg, 0f)) < gapTimeDeg + 2f || abs(angleDiff(deg, 180f)) < gapDateDeg + 2f) return Moon(MoonKind.HIDDEN, deg)
        if (bolusShown && abs(angleDiff(deg, DEG_BOLUS)) < BOLUS_OCCLUDE_DEG) return Moon(MoonKind.HIDDEN, deg)
        for (i in BALL_DEGS.indices) {
            val diff = angleDiff(deg, BALL_DEGS[i])
            if (abs(diff) < BALL_OCCLUDE_DEG)
                return Moon(MoonKind.RIM, deg, i, BALL_DEGS[i] + diff / BALL_OCCLUDE_DEG * 90f)
        }
        return Moon(MoonKind.DOT, deg)
    }

    // ------------------------------------------------------------------ sparkline

    fun sparkY(mgdl: Double): Float {
        val c = mgdl.coerceIn(SPARK_MIN_MGDL, SPARK_MAX_MGDL)
        return (SPARK_Y_BOTTOM - (c - SPARK_MIN_MGDL) / (SPARK_MAX_MGDL - SPARK_MIN_MGDL) * (SPARK_Y_BOTTOM - SPARK_Y_TOP)).toFloat()
    }

    /** -1 below the box (caret ▼ on the bottom edge), +1 above (▲ on the top edge), 0 inside. */
    fun sparkClamp(mgdl: Double): Int = when {
        mgdl < SPARK_MIN_MGDL -> -1
        mgdl > SPARK_MAX_MGDL -> 1
        else                  -> 0
    }

    /** x of a reading [t] when the right end of the history (x 265) is [now]. */
    fun sparkX(t: Long, now: Long): Float =
        SPARK_X_NOW - (now - t).toFloat() / SPARK_HISTORY_MS * (SPARK_X_NOW - SPARK_X0)

    /** x of the k-th prediction dot (1..5), spread over x 265..291. */
    fun predictionX(k: Int): Float = SPARK_X_NOW + k * (SPARK_X1 - SPARK_X_NOW) / PREDICTION_DOTS
}
