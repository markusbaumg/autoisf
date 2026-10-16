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
 * State semantics follow the family state catalogue (designsystem/states.md: bgAge, bgRange, loop health,
 * status slot W1..W10, display() fallbacks); geometry follows orbit_spec.md.
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

    /** bgAge thresholds (states.md §1) in minutes since the last BG. */
    const val AGING_FROM_MIN = 6L        // age label amber
    const val STALE_FROM_MIN = 12L       // grey hero + strike, status slot, hollow now dot, no prediction, no corona
    const val VERY_STALE_FROM_MIN = 30L
    const val HERO_DASHES_FROM_MIN = 60L // BG digits replaced by "---"

    /** phoneLink: data older than this counts as stale (states.md §1). */
    const val LINK_STALE_MS = 12 * 60_000L

    /** Urgent / very levels derived on the watch (gap G1). */
    const val URGENT_LOW_MGDL = 54.0
    const val VERY_HIGH_MGDL = 250.0
    const val SENTINEL_LOW_MGDL = 39.0
    const val SENTINEL_HIGH_MGDL = 400.0
    const val FAST_DELTA_MGDL = 10.0

    /** IOB arc is full at this many units. */
    const val IOB_ARC_FULL_U = 6.0

    /** Sparkline: fixed range 2.5..14.0 mmol/L (45..252 mg/dL), box x 151..291, y 275..321, now at x 265. */
    const val SPARK_MIN_MGDL = 45.0
    const val SPARK_MAX_MGDL = 252.0
    const val SPARK_X0 = 151f
    const val SPARK_X_NOW = 265f
    const val SPARK_X1 = 291f
    const val SPARK_Y_TOP = 275f
    const val SPARK_Y_BOTTOM = 321f

    /** With a status pill under the delta row the sparkline moves down into this box. */
    const val SPARK_Y_TOP_PILL = 298f
    const val SPARK_Y_BOTTOM_PILL = 334f
    const val SLOT_Y = 280f
    const val SPARK_HISTORY_MS = 2 * 60 * 60_000L
    const val PREDICTION_DOTS = 5
    const val PREDICTION_STEP_MS = 6 * 60_000L

    /** Width budgets (design px) for the worst-case text checks. */
    const val BALL_VALUE_MAX = 56f
    const val BALL_VALUE_HARD_MAX = 62f
    const val HERO_MAX_WIDTH = 200f
    const val DATE_LINE_MAX_WIDTH = 196f
    const val DELTA_ROW_MAX_WIDTH = 190f
    const val SLOT_MAX_WIDTH = 230f

    // ------------------------------------------------------------------ localised words

    /** Localised words, passed in so the logic stays pure. Defaults are German (the face's design language). */
    data class Labels(
        val again: String = "nochmal",
        val cob: String = "KH",
        val minutesFormat: String = "%d′",
        val hoursFormat: String = "%d Std",
        val daysFormat: String = "%d T",
        val old: String = "alt",
        val waiting: String = "Warte auf AAPS",
        val phoneLostFormat: String = "Telefon getrennt · %s",
        val noDataFormat: String = "Keine Daten · %s",
        val noProfile: String = "Kein Profil",
        val loopOff: String = "Loop aus",
        val loopAgeFormat: String = "Loop %s",
        val loopOffShort: String = "AUS",
        val loopOpen: String = "Offen",
        val pumpOnly: String = "Pumpe",
        val reservoirFormat: String = "Reservoir %s",
        val phoneBatteryFormat: String = "Tel %s %%"
    )

    // ------------------------------------------------------------------ BG age

    enum class BgAge { FRESH, AGING, STALE, VERY_STALE, NONE }

    /** BG age in whole minutes, or null without a reading. A timestamp in the future counts as 0 (clock skew). */
    fun bgAgeMinutes(timeStamp: Long, now: Long): Long? = if (timeStamp <= 0L) null else max(0L, (now - timeStamp) / 60_000L)

    fun bgAge(ageMinutes: Long?): BgAge = when {
        ageMinutes == null                  -> BgAge.NONE
        ageMinutes >= VERY_STALE_FROM_MIN   -> BgAge.VERY_STALE
        ageMinutes >= STALE_FROM_MIN        -> BgAge.STALE
        ageMinutes >= AGING_FROM_MIN        -> BgAge.AGING
        else                                -> BgAge.FRESH
    }

    /** Grey hero, hollow now dot, no prediction and no corona. */
    fun isOld(a: BgAge): Boolean = a == BgAge.STALE || a == BgAge.VERY_STALE || a == BgAge.NONE

    /** Strike-through over the BG (not for NONE, which shows "---"). */
    fun isStruck(a: BgAge): Boolean = a == BgAge.STALE || a == BgAge.VERY_STALE

    /** "17′", "1 Std" from 60 min, "2 T" from 48 h, "--" when unknown. */
    fun ageText(minutes: Long?, labels: Labels): String = when {
        minutes == null       -> "--"
        minutes >= 48 * 60    -> String.format(Locale.ROOT, labels.daysFormat, minutes / 1440)
        minutes >= 60         -> String.format(Locale.ROOT, labels.hoursFormat, minutes / 60)
        else                  -> String.format(Locale.ROOT, labels.minutesFormat, minutes)
    }

    // ------------------------------------------------------------------ BG range

    enum class Range { IN, HIGH, VERY_HIGH, LOW, URGENT_LOW, STALE }

    fun fastFall(slopeArrow: String, deltaMgdl: Double?): Boolean =
        slopeArrow == "⇊" || (slopeArrow == "X" && (deltaMgdl ?: 0.0) < 0) || (deltaMgdl != null && deltaMgdl <= -FAST_DELTA_MGDL)

    /** Range hue only for FRESH/AGING; older data is STALE (stale beats range, states.md §3 rule 4). */
    fun range(sgv: Double, sgvLevel: Long, slopeArrow: String, deltaMgdl: Double?, age: BgAge): Range {
        if (age != BgAge.FRESH && age != BgAge.AGING) return Range.STALE
        return when {
            sgv < URGENT_LOW_MGDL                                -> Range.URGENT_LOW
            sgvLevel == -1L && fastFall(slopeArrow, deltaMgdl)   -> Range.URGENT_LOW
            sgvLevel == -1L                                      -> Range.LOW
            sgv > VERY_HIGH_MGDL                                 -> Range.VERY_HIGH
            sgvLevel == 1L                                       -> Range.HIGH
            else                                                 -> Range.IN
        }
    }

    fun isLow(r: Range) = r == Range.LOW || r == Range.URGENT_LOW

    /** Corona emphasis step (+20 % alpha) for urgent low and very high. */
    fun emphasis(r: Range) = r == Range.URGENT_LOW || r == Range.VERY_HIGH

    /** "---" without a reading or from 60 min; "400+" / "22,2+" for the high sentinel. The phone string is kept. */
    fun heroText(sgvString: String, sgv: Double, ageMinutes: Long?): String {
        if (ageMinutes == null || ageMinutes >= HERO_DASHES_FROM_MIN) return "---"
        val s = sgvString.trim()
        if (s.isEmpty() || s == "---") return "---"
        return if (sgv >= SENTINEL_HIGH_MGDL && !s.endsWith("+")) "$s+" else s
    }

    /** Only the arrows the face knows; "??", "--" and unknown strings draw nothing. */
    fun arrowText(slopeArrow: String, deltaMgdl: Double?): String = when (slopeArrow.trim()) {
        "⇈", "↑", "↗", "→", "↘", "↓", "⇊" -> slopeArrow.trim()
        "X"                               -> if ((deltaMgdl ?: 0.0) < 0) "⇊" else "⇈"
        else                              -> ""
    }

    // ------------------------------------------------------------------ delta row

    /** Interactive delta row: "+0,1 · " and the age "1′" (amber from 6 min); [phoneOff] adds the phone-off glyph. */
    data class DeltaRow(val deltaPart: String, val agePart: String, val ageAmber: Boolean)

    fun deltaRow(delta: String, ageMinutes: Long?, labels: Labels): DeltaRow {
        val d = display(delta)
        return DeltaRow(
            deltaPart = "$d · ",
            agePart = ageText(ageMinutes, labels),
            ageAmber = (ageMinutes ?: 0L) >= AGING_FROM_MIN
        )
    }

    /** Ambient replacement of the delta row from 12 min: "– – 17′ alt". */
    fun oldAmbientText(ageMinutes: Long?, labels: Labels): String = "– – " + ageText(ageMinutes, labels) + " " + labels.old

    // ------------------------------------------------------------------ phone link and status slot

    enum class PhoneLink { OK, LOST_FRESH, LOST_STALE, SILENT }

    /** [dataAgeMs] is the age of the last message from the phone (status or BG). */
    fun phoneLink(reachable: Boolean, dataAgeMs: Long): PhoneLink = when {
        reachable && dataAgeMs < LINK_STALE_MS  -> PhoneLink.OK
        !reachable && dataAgeMs < LINK_STALE_MS -> PhoneLink.LOST_FRESH
        !reachable                              -> PhoneLink.LOST_STALE
        else                                    -> PhoneLink.SILENT
    }

    enum class Tone { INFO, WARN, ALERT, GLYPH }

    /** The one text that owns the status slot (pill under the delta row). [rank] is W1..W10. */
    data class Slot(val rank: Int, val text: String, val tone: Tone)

    data class SlotInput(
        val firstStart: Boolean,
        val link: PhoneLink,
        val dataAgeMinutes: Long?,
        val bgAgeMinutes: Long?,
        val bgAge: BgAge,
        val range: Range,
        val noProfile: Boolean,
        val loopOff: Boolean,
        val loopBad: Boolean,
        val loopAgeMinutes: Long?,
        val reservoirLevel: Int,
        val reservoirString: String,
        val batteryLevel: Int,
        val battery: String
    )

    /** Warning hierarchy W1..W10 (states.md §4.2): the first true one owns the slot. */
    fun statusSlot(i: SlotInput, ambient: Boolean, labels: Labels): Slot? {
        val res = i.reservoirString.trim()
        val resKnown = res.isNotEmpty() && res != "--"
        val batt = i.battery.trim().removeSuffix("%").trim()
        val battValue = batt.toIntOrNull()
        val slot = when {
            i.firstStart                                                    -> Slot(1, labels.waiting, Tone.INFO)
            i.link == PhoneLink.LOST_STALE                                  ->
                Slot(2, String.format(Locale.ROOT, labels.phoneLostFormat, ageText(i.dataAgeMinutes, labels)), Tone.ALERT)

            i.link == PhoneLink.SILENT || i.bgAge == BgAge.STALE || i.bgAge == BgAge.VERY_STALE ->
                Slot(3, String.format(Locale.ROOT, labels.noDataFormat, ageText(i.bgAgeMinutes ?: i.dataAgeMinutes, labels)), Tone.ALERT)

            i.noProfile                                                     -> Slot(4, labels.noProfile, Tone.WARN)
            i.loopOff                                                       -> Slot(5, labels.loopOff, Tone.WARN)
            i.loopBad                                                       ->
                Slot(6, String.format(Locale.ROOT, labels.loopAgeFormat, ageText(i.loopAgeMinutes, labels)), Tone.ALERT)

            i.reservoirLevel == 2 && resKnown                               -> Slot(7, String.format(Locale.ROOT, labels.reservoirFormat, res), Tone.ALERT)
            i.batteryLevel == 0 && battValue != null && battValue > 0       ->
                Slot(8, String.format(Locale.ROOT, labels.phoneBatteryFormat, batt), if (battValue < 10) Tone.ALERT else Tone.WARN)

            i.reservoirLevel == 1 && resKnown                               -> Slot(9, String.format(Locale.ROOT, labels.reservoirFormat, res), Tone.WARN)
            i.link == PhoneLink.LOST_FRESH                                  -> Slot(10, "", Tone.GLYPH)
            else                                                            -> null
        } ?: return null
        // a fresh low owns the face: minor warnings are suppressed
        if (isLow(i.range) && slot.rank >= 7) return null
        if (ambient && slot.rank > 6) return null
        return slot
    }

    /** The slot replaces the delta row (W2/W3: the data is old) instead of sitting under it. */
    fun slotReplacesDelta(slot: Slot?): Boolean = slot != null && (slot.rank == 2 || slot.rank == 3)

    /** First start: nothing has ever arrived from the phone. */
    fun firstStart(bgTimeStamp: Long, openApsStatus: Long, iobSum: String): Boolean =
        bgTimeStamp <= 0L && openApsStatus == -1L && (iobSum.trim() == "IOB" || iobSum.isBlank())

    /**
     * The phone sends a localised externalStatus: "" when the loop runs, the "no profile" text, or the
     * "loop disabled" text. Without a profile it sends no IOB either, which tells the two apart.
     */
    fun noProfile(externalStatus: String, iobSum: String): Boolean = externalStatus.isNotBlank() && iobSum.isBlank()
    fun loopOff(externalStatus: String, iobSum: String): Boolean = externalStatus.isNotBlank() && iobSum.isNotBlank()

    // ------------------------------------------------------------------ balls

    enum class Ring { NEUTRAL, DIM, GREEN, AMBER, RED, CYAN, TEAL, VIOLET }

    /**
     * One status ball. [pause] draws a pause glyph in place of the value (loop off), [dashed] a dashed ring
     * (unknown loop), [arc] an IOB arc fraction 0..1 on the rim, [dimValue] dims only the value.
     */
    data class Ball(
        val value: String,
        val label: String,
        val ring: Ring,
        val pause: Boolean = false,
        val dashed: Boolean = false,
        val arc: Float = 0f,
        val dimValue: Boolean = false
    )

    /** Loop age in whole minutes, or null when there was no loop run (-1). */
    fun loopAgeMinutes(openApsStatus: Long, now: Long): Long? =
        if (openApsStatus <= 0L) null else max(0L, (now - openApsStatus) / 60_000L)

    enum class LoopHealth { NEVER, OK, WARN, BAD }

    /** <= 5 OK, 6..14 WARN, >= 15 BAD. No loop run at all (-1) is NEVER: grey, never red. */
    fun loopHealth(ageMinutes: Long?): LoopHealth = when {
        ageMinutes == null -> LoopHealth.NEVER
        ageMinutes <= 5    -> LoopHealth.OK
        ageMinutes < 15    -> LoopHealth.WARN
        else               -> LoopHealth.BAD
    }

    /**
     * LOOP ball. Loop off: amber ring, pause glyph, "AUS". Never looped: dashed grey "--", or "Pumpe" when the
     * phone clearly runs without APS (pump data but no loop run). Otherwise the age coloured by health.
     */
    fun loopBall(openApsStatus: Long, externalStatus: String, iobSum: String, now: Long, labels: Labels): Ball {
        if (loopOff(externalStatus, iobSum)) return Ball("", labels.loopOffShort, Ring.AMBER, pause = true)
        val age = loopAgeMinutes(openApsStatus, now)
        return when (loopHealth(age)) {
            LoopHealth.NEVER -> if (display(iobSum) != "--") Ball(labels.pumpOnly, "", Ring.DIM)
                                else Ball("--", "LOOP", Ring.DIM, dashed = true)
            LoopHealth.OK    -> Ball(ageText(age, labels), "LOOP", Ring.GREEN)
            LoopHealth.WARN  -> Ball(ageText(age, labels), "LOOP", Ring.AMBER)
            LoopHealth.BAD   -> Ball(ageText(age, labels), "LOOP", Ring.RED)
        }
    }

    /** Phone placeholders that must never be drawn ("IOB" before the first status, "-.--", "--g", "no status"). */
    fun display(s: String): String {
        val t = s.trim()
        return if (t.isEmpty() || t == "IOB" || t == "-.--" || t == "--g" || t == "-.--U/h" || t == "no status" || t == "??" || t == "--") "--" else t
    }

    /** Leading number of "0.85 U/h", "0,00 U/h", "120%", "-0,35" or null. */
    fun leadingNumber(s: String): Double? {
        val m = Regex("""-?\d+([.,]\d+)?""").find(s) ?: return null
        return m.value.replace(',', '.').toDoubleOrNull()
    }

    /** IOB: cyan ring, arc = value / 6 U (capped, empty for <= 0), value dim when zero or unknown. */
    fun iobBall(iobSum: String): Ball {
        val v = display(iobSum)
        val n = leadingNumber(v)
        val arc = if (n == null || n <= 0.0) 0f else min(1.0, n / IOB_ARC_FULL_U).toFloat()
        return Ball(v, "IOB", Ring.CYAN, arc = arc, dimValue = n == null || abs(n) < 1e-6)
    }

    /** "0.00 U/h" -> "0.00", "120%" stays. */
    fun basalValue(currentBasal: String): String =
        display(display(currentBasal).replace("U/h", "").replace("E/h", ""))

    /** True when a temp basal covers [now]. The phone paints the running TBR a few minutes into the future. */
    fun tbrRunning(starts: LongArray, ends: LongArray, now: Long): Boolean {
        for (i in starts.indices) if (starts[i] <= now && now <= ends[i]) return true
        return false
    }

    /**
     * TBR (a "%" rate, or a temp basal covering now) > 0 teal, = 0 amber (the loop is withholding insulin);
     * an absolute 0,00 without a known TBR is amber as well (basal.zero_abs); otherwise neutral.
     */
    fun basalBall(currentBasal: String, tbrHint: Boolean): Ball {
        val value = basalValue(currentBasal)
        if (value == "--") return Ball(value, "BASAL", Ring.DIM)
        val rate = leadingNumber(value)
        val zero = rate != null && abs(rate) < 1e-6
        val tbr = tbrHint || value.endsWith("%")
        return when {
            tbr  -> Ball(value, "TBR", if (zero) Ring.AMBER else Ring.TEAL)
            zero -> Ball(value, "BASAL", Ring.AMBER)
            else -> Ball(value, "BASAL", Ring.NEUTRAL)
        }
    }

    /** COB > 0 amber, else dim. "12(3)g" counts as 12. Label "KH" in German. */
    fun cobBall(cob: String, labels: Labels): Ball {
        val v = display(cob)
        val n = leadingNumber(v)
        return Ball(v, labels.cob, if (n != null && n > 0.0) Ring.AMBER else Ring.DIM)
    }

    /** "5,0 - 6,0" -> "5,0-6,0", "5,6" stays. */
    fun compactTarget(s: String): String = display(s).replace(" ", "")

    /** Temp target active (level 2): violet "TT". Profile target, APS-adjusted or unknown: no ball (tt.profile). */
    fun ttBall(tempTarget: String, tempTargetLevel: Int): Ball? {
        if (tempTargetLevel != 2) return null
        val v = compactTarget(tempTarget)
        return if (v == "--") null else Ball(v, "TT", Ring.VIOLET)
    }

    /** Skeleton ball for first start / no profile: "--" in dim. */
    fun placeholderBall(label: String): Ball = Ball("--", label, Ring.DIM, dimValue = true)

    /** Value text size: 22 px; wider than 56 px at 22 -> 17 px bold; still too wide -> shrink to fit 62 px. */
    fun ballValueSize(widthAt22: Float): Float {
        if (widthAt22 <= BALL_VALUE_MAX) return 22f
        val at17 = widthAt22 * 17f / 22f
        return if (at17 <= BALL_VALUE_HARD_MAX) 17f else 17f * BALL_VALUE_HARD_MAX / at17
    }

    /** Shrinks a text size so a text measured at [size] fits [maxWidth]. */
    fun fitSize(widthAtSize: Float, size: Float, maxWidth: Float): Float =
        if (widthAtSize <= maxWidth || widthAtSize <= 0f) size else size * maxWidth / widthAtSize

    // ------------------------------------------------------------------ bolus amount

    /** Watch-generated number in the watch locale: "0,5 U" in German, "0.5 U" in English. */
    fun bolusAmountText(amount: Double, locale: Locale): String = String.format(locale, "%.1f U", amount)

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
     * Minute moon at 6 deg per minute. Hidden in the time/date gaps and behind the bolus button; behind a shown
     * ball ([ballShown]) it becomes a rim highlight that sweeps from the trailing side over the outer rim to the
     * leading side.
     */
    fun moon(minute: Int, gapTimeDeg: Float, gapDateDeg: Float, bolusShown: Boolean, ballShown: BooleanArray? = null): Moon {
        val deg = (minute % 60) * 6f
        if (abs(angleDiff(deg, 0f)) < gapTimeDeg + 2f || abs(angleDiff(deg, 180f)) < gapDateDeg + 2f) return Moon(MoonKind.HIDDEN, deg)
        if (bolusShown && abs(angleDiff(deg, DEG_BOLUS)) < BOLUS_OCCLUDE_DEG) return Moon(MoonKind.HIDDEN, deg)
        for (i in BALL_DEGS.indices) {
            if (ballShown != null && !ballShown[i]) continue
            val diff = angleDiff(deg, BALL_DEGS[i])
            if (abs(diff) < BALL_OCCLUDE_DEG)
                return Moon(MoonKind.RIM, deg, i, BALL_DEGS[i] + diff / BALL_OCCLUDE_DEG * 90f)
        }
        return Moon(MoonKind.DOT, deg)
    }

    // ------------------------------------------------------------------ sparkline

    fun sparkY(mgdl: Double, top: Float = SPARK_Y_TOP, bottom: Float = SPARK_Y_BOTTOM): Float {
        val c = mgdl.coerceIn(SPARK_MIN_MGDL, SPARK_MAX_MGDL)
        return (bottom - (c - SPARK_MIN_MGDL) / (SPARK_MAX_MGDL - SPARK_MIN_MGDL) * (bottom - top)).toFloat()
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

    /** Gap rule (graph.gaps): readings more than 15 min apart are not joined. */
    const val SPARK_GAP_MS = 15 * 60_000L
    fun sparkBreak(prevT: Long, t: Long): Boolean = t - prevT > SPARK_GAP_MS

    /** x of the k-th prediction dot (1..5), spread over x 265..291. */
    fun predictionX(k: Int): Float = SPARK_X_NOW + k * (SPARK_X1 - SPARK_X_NOW) / PREDICTION_DOTS
}
