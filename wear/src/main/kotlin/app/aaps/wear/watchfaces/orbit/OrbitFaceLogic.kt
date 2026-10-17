package app.aaps.wear.watchfaces.orbit

import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.LoopMode
import app.aaps.wear.watchfaces.common.FaceState.PhoneLink
import app.aaps.wear.watchfaces.common.FaceState.Range
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/**
 * Pure state logic of the "AAPS Orbit" face (no Android types, unit tested).
 *
 * State semantics come from the shared family helper [FaceState] (designsystem/states.md: bgAge, bgRange, loop
 * health, phone link, status slot W1..W10, display() fallbacks); this object only adds Orbit's shapes and geometry
 * (orbit_spec.md).
 * Geometry is on a 450 x 450 design grid, angles are degrees clockwise from 12 o'clock.
 */
object OrbitFaceLogic {

    const val CX = 225f
    const val CY = 225f

    /**
     * UX round 1: the track grows from r 168 to r 192 (no dead black margin), the satellites sit on r 174 (their
     * shells cover the track, 14 px from the bezel) and grow from r 34 to r 37.
     * UX round 3: satellites r 34 on r 171, so they keep 20 px (1.5 mm) to the glass edge: the Watch5 Pro's raised
     * bezel hides about 1 mm at an angle. Their inner edge stays at r 137.
     */
    const val ORBIT_R = 192f
    const val SAT_R = 171f
    const val BALL_R = 34f
    const val BOLUS_HALF = 33f

    /** Hit circle radius around the bolus button centre (spec §4.12): 48 px = 24 dp at xhdpi, the family target. */
    const val BOLUS_HIT_R = 48f

    /** Rim flash after a bolus request is sent (spec §4.12). */
    const val BOLUS_SEND_FLASH_MS = 150L

    /** Double-tap window of the graph (time-frame change), same as the bolus button. */
    const val GRAPH_DOUBLE_TAP_MS = 800L

    /**
     * Ball positions on the orbit (degrees clockwise from 12). Never change between modes. UX round 1: symmetric at
     * 2 / 4 / 8 / 10 o'clock (LOOP 10, IOB 2); the 3 / 9 o'clock pair holds TT and the bolus button, so neither
     * adds a lopsided state.
     */
    const val DEG_LOOP = 300f
    const val DEG_IOB = 60f
    const val DEG_BASAL = 120f
    const val DEG_BOLUS = 270f
    const val DEG_COB = 240f
    const val DEG_TT = 90f
    val BALL_DEGS = floatArrayOf(DEG_LOOP, DEG_IOB, DEG_BASAL, DEG_COB, DEG_TT)
    const val IDX_LOOP = 0
    const val IDX_IOB = 1
    const val IDX_BASAL = 2
    const val IDX_COB = 3
    const val IDX_TT = 4

    /** bgAge thresholds (states.md §1) in minutes since the last BG. */
    const val AGING_FROM_MIN = FaceState.AGING_MIN        // age label amber
    const val STALE_FROM_MIN = FaceState.STALE_MIN       // grey hero + strike, status slot, hollow now dot, no prediction, no corona
    const val VERY_STALE_FROM_MIN = FaceState.VERY_STALE_MIN
    const val HERO_DASHES_FROM_MIN = FaceState.BG_HIDE_MIN // BG digits replaced by "---"

    /** phoneLink: data older than this counts as stale (states.md §1). */
    const val LINK_STALE_MS = FaceState.DATA_FRESH_MS

    /** Urgent / very levels derived on the watch (gap G1). */
    const val URGENT_LOW_MGDL = FaceState.URGENT_LOW_MGDL
    const val VERY_HIGH_MGDL = FaceState.VERY_HIGH_MGDL
    const val SENTINEL_LOW_MGDL = FaceState.SENTINEL_LOW_MGDL
    const val SENTINEL_HIGH_MGDL = FaceState.SENTINEL_HIGH_MGDL
    const val FAST_DELTA_MGDL = FaceState.FAST_DELTA_MGDL

    /** IOB arc is full at this many units. */
    const val IOB_ARC_FULL_U = 6.0

    /**
     * Sparkline: fixed range 2.5..14.0 mmol/L (45..252 mg/dL). UX round 3: box x 119..331, the full width between the
     * 4 and 8 o'clock satellites (8 px clear of their shells), now at x 301.
     */
    const val SPARK_MIN_MGDL = 45.0
    const val SPARK_MAX_MGDL = 252.0
    const val SPARK_X0 = 119f
    const val SPARK_X_NOW = 301f
    const val SPARK_X1 = 331f
    const val SPARK_Y_TOP = 292f // UX round 3: 4 px lower, a top-clamp chevron keeps clear of a delta row with TIEF/HOCH
    const val SPARK_Y_BOTTOM = 340f

    /**
     * Delta row centre and the pill row under it (its own row, 8 px or more below the delta baseline: never over the
     * delta). UX round 2: delta 22 -> 26 px, pills 28 -> 32 px tall with 22 px text, the row 12 px below the delta.
     */
    const val DELTA_ROW_Y = 266f
    const val DELTA_SIZE = 26f

    /** UX round 4: 306 -> 312, at least 8 px between the delta row (and its TIEF/HOCH pill) and a status pill. */
    const val SLOT_Y = 312f

    /** UX round 3: status pills 34 px tall with 25 px text (a 1.4 mm cap height: the face's only W-level message). */
    const val SLOT_H = 34f
    const val SLOT_TEXT = 25f

    /**
     * UX round 3: a W1-W3 pill that replaces the delta row sits 6 px lower, so the comma tail of a struck "6,4" never
     * touches it; the sparkline then moves down into [SPARK_Y_TOP_SLOT]..[SPARK_Y_BOTTOM_SLOT].
     */
    const val SLOT_IN_DELTA_Y = DELTA_ROW_Y + 6f
    const val SPARK_Y_TOP_SLOT = 296f
    const val SPARK_Y_BOTTOM_SLOT = 344f

    /** Width of the pill row between the 4 and 8 o'clock satellites (their shells end at x 111 / 339). */
    const val PILL_ROW_MAX_WIDTH = 216f

    /**
     * With a status pill under the delta row the sparkline moves down into this box. UX round 4: a shorter box under the
     * lower pill (8 px clear of it), below the satellites' shells.
     */
    const val SPARK_Y_TOP_PILL = 338f
    const val SPARK_Y_BOTTOM_PILL = 370f

    /**
     * UX round 3: the TIEF/HOCH caption follows the delta on the delta row (as Glass), 30 px tall, so a W4-W9 pill
     * gets the whole pill row (TIEF + "Telefon getrennt · 14′" side by side would not fit between the satellites).
     */
    const val CAPTION_H = 30f
    const val CAPTION_GAP = 10f

    /** Time (UX round 3): 33 px, centred at y 74, below the system status icon (y 23-49 on the Watch5 Pro). */
    const val TIME_Y = 74f
    const val TIME_SIZE = 33f
    /** Default sparkline window (key_chart_time_frame, hours). */
    const val DEFAULT_TIME_FRAME_H = 3
    const val SPARK_HISTORY_MS = DEFAULT_TIME_FRAME_H * 60 * 60_000L
    const val PREDICTION_DOTS = 5
    const val PREDICTION_STEP_MS = 6 * 60_000L

    /** Width budgets (design px) for the worst-case text checks. */
    const val BALL_VALUE_MAX = 56f
    const val BALL_VALUE_HARD_MAX = 60f
    const val HERO_MAX_WIDTH = 200f
    const val DATE_LINE_MAX_WIDTH = 196f
    /** UX round 4: 212 -> 200, a HOCH/TIEF pill at the row's end keeps 20 px or more to the 4 and 8 o'clock satellites. */
    const val DELTA_ROW_MAX_WIDTH = 200f

    /** UX round 3: 210 (was 230): a delta-row pill keeps clear of the 4 and 8 o'clock satellites. */
    const val SLOT_MAX_WIDTH = 210f

    // ------------------------------------------------------------------ localised words

    /** Localised words, passed in so the logic stays pure. Defaults are German (the face's design language). */
    data class Labels(
        val again: String = "nochmal",
        val cob: String = "KH",
        val minutesFormat: String = "%d′",
        val hoursFormat: String = "%d Std",
        val daysFormat: String = "%d T",
        val waiting: String = "Warte auf AAPS",
        val phoneLostFormat: String = "Telefon getrennt · %s",
        val noDataFormat: String = "Keine Daten · %s",
        val noProfile: String = "Kein Profil",
        val loopOff: String = "Loop aus",
        val loopAgeFormat: String = "Loop %s",
        val loopOffShort: String = "AUS",
        val loopOpen: String = "Offen",
        val loopLgs: String = "LGS",
        val sentinelLow: String = "TIEF",
        val sentinelHigh: String = "HOCH",
        val weekFormat: String = "KW %d",
        val reservoirFormat: String = "Reservoir %s",
        val phoneBatteryFormat: String = "Tel %s %%"
    )

    // ------------------------------------------------------------------ BG age

    /** BG age in whole minutes, or null without a reading. A timestamp in the future counts as 0 (clock skew). */
    fun bgAgeMinutes(timeStamp: Long, now: Long): Long? = FaceState.ageMinutes(timeStamp, now)

    fun bgAge(ageMinutes: Long?): BgAge = FaceState.bgAge(ageMinutes)

    /** Grey hero, hollow now dot, no prediction and no corona. */
    fun isOld(a: BgAge): Boolean = FaceState.isOld(a)

    /** Strike-through over the BG (not for NONE, which shows "---"). */
    fun isStruck(a: BgAge): Boolean = FaceState.isStruck(a)

    /** "17′", "1 Std" from 60 min, "2 T" from 48 h, "--" when unknown. */
    fun ageText(minutes: Long?, labels: Labels): String = when {
        minutes == null       -> "--"
        minutes >= 48 * 60    -> String.format(Locale.ROOT, labels.daysFormat, minutes / 1440)
        minutes >= 60         -> String.format(Locale.ROOT, labels.hoursFormat, minutes / 60)
        else                  -> String.format(Locale.ROOT, labels.minutesFormat, minutes)
    }

    // ------------------------------------------------------------------ BG range

    fun fastFall(slopeArrow: String, deltaMgdl: Double?): Boolean =
        FaceState.fastFall(FaceState.trend(slopeArrow, deltaMgdl), deltaMgdl)

    /** Range hue only for FRESH/AGING; older data is STALE (stale beats range, states.md §3 rule 4). */
    fun range(sgv: Double, sgvLevel: Long, slopeArrow: String, deltaMgdl: Double?, age: BgAge): Range =
        FaceState.range(sgv, sgvLevel, age, fastFall(slopeArrow, deltaMgdl))

    fun isLow(r: Range) = FaceState.isLow(r)

    /** Corona emphasis step (+20 % alpha) for urgent low and very high. */
    fun emphasis(r: Range) = FaceState.emphasis(r)

    /** "---" without a reading or from 60 min; "400+" / "22,2+" for the high sentinel. The phone string is kept. */
    fun heroText(sgvString: String, sgv: Double, ageMinutes: Long?): String = FaceState.bgText(sgvString, sgv, ageMinutes)

    /** Sentinel LOW (sgv <= 39): the phone string plus the "TIEF" caption, treated as urgent low. */
    fun sentinelLow(sgv: Double, ageMinutes: Long?): Boolean =
        ageMinutes != null && ageMinutes < STALE_FROM_MIN && sgv > 0.0 && sgv <= SENTINEL_LOW_MGDL

    /**
     * Trend for the face-own vector glyph (spec §4.3). [deg] is the shaft direction clockwise from 12 o'clock,
     * [heads] 1 or 2 (fast). "??", "--" and unknown strings are NONE (no glyph, the BG re-centres).
     */
    enum class Trend(val deg: Float, val heads: Int) {
        NONE(0f, 0), UP2(0f, 2), UP(0f, 1), UP45(45f, 1), FLAT(90f, 1), DOWN45(135f, 1), DOWN(180f, 1), DOWN2(180f, 2)
    }

    fun trend(slopeArrow: String, deltaMgdl: Double?): Trend = when (slopeArrow.trim()) {
        "⇈"  -> Trend.UP2
        "↑"  -> Trend.UP
        "↗"  -> Trend.UP45
        "→"  -> Trend.FLAT
        "↘"  -> Trend.DOWN45
        "↓"  -> Trend.DOWN
        "⇊"  -> Trend.DOWN2
        "X"  -> if ((deltaMgdl ?: 0.0) < 0) Trend.DOWN2 else Trend.UP2
        else -> Trend.NONE
    }

    // ------------------------------------------------------------------ delta row

    /**
     * Meta row: "+0,1 · " and the age "1′" (amber from 6 min). [showDelta] off drops the delta; [showAgo] off hides
     * a FRESH age, but an AGING (or older) age is always shown (toggle exception). Both empty: the row is empty.
     */
    data class DeltaRow(val deltaPart: String, val agePart: String, val ageAmber: Boolean) {

        val isEmpty: Boolean get() = deltaPart.isEmpty() && agePart.isEmpty()
    }

    fun deltaRow(delta: String, ageMinutes: Long?, labels: Labels, showDelta: Boolean = true, showAgo: Boolean = true): DeltaRow {
        val aging = (ageMinutes ?: 0L) >= AGING_FROM_MIN
        val age = if (showAgo || aging) ageText(ageMinutes, labels) else ""
        val d = if (showDelta) display(delta) else ""
        return DeltaRow(
            deltaPart = if (d.isEmpty()) "" else if (age.isEmpty()) d else "$d · ",
            agePart = age,
            ageAmber = aging
        )
    }

    /** Delta source (spec §4.4): detailed and/or average delta when those prefs are on. */
    fun deltaSource(delta: String, deltaDetailed: String, avgDelta: String, avgDeltaDetailed: String, detailed: Boolean, avg: Boolean): String = when {
        avg && detailed -> avgDeltaDetailed
        avg             -> avgDelta
        detailed        -> deltaDetailed
        else            -> delta
    }

    // ------------------------------------------------------------------ phone link and status slot

    /** [dataAgeMs] is the age of the last message from the phone (status or BG). */
    fun phoneLink(reachable: Boolean, dataAgeMs: Long): PhoneLink =
        if (dataAgeMs == Long.MAX_VALUE) FaceState.phoneLink(reachable, 0L, 1L)       // nothing ever received
        else FaceState.phoneLink(reachable, 1L, 1L + dataAgeMs)

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

    /** Family words from Orbit's labels. */
    fun words(labels: Labels): FaceState.Words = FaceState.Words(
        minutes = labels.minutesFormat, hours = labels.hoursFormat, days = labels.daysFormat, waiting = labels.waiting,
        phoneLost = labels.phoneLostFormat, noData = labels.noDataFormat, noProfile = labels.noProfile, loopOff = labels.loopOff,
        loopAge = labels.loopAgeFormat, reservoir = labels.reservoirFormat, phoneBattery = labels.phoneBatteryFormat.replace("%s", "%d"),
        low = labels.sentinelLow, high = labels.sentinelHigh
    )

    /**
     * Warning hierarchy W1..W10 from the shared [FaceState]: the first true one owns the slot. A fresh low suppresses
     * W7-W10; ambient keeps W1-W6.
     */
    fun statusSlot(i: SlotInput, ambient: Boolean, labels: Labels): Slot? {
        val loop = when {
            i.loopOff -> FaceState.LoopHealth.OFF
            i.loopBad -> FaceState.LoopHealth.BAD
            else      -> FaceState.LoopHealth.OK
        }
        val w = FaceState.warnings(
            FaceState.WarningInput(
                i.firstStart, i.link, i.bgAge, i.range, i.noProfile && !i.firstStart, loop, i.reservoirString, i.reservoirLevel, i.battery, i.batteryLevel
            )
        )
        val owner = FaceState.owner(w, ambient) ?: return null
        if (owner == FaceState.Warning.PHONE_LOST_FRESH) return Slot(10, "", Tone.GLYPH)
        val t = FaceState.statusText(owner, words(labels), i.dataAgeMinutes, i.bgAgeMinutes, i.loopAgeMinutes, i.reservoirString, i.battery) ?: return null
        val tone = when (t.level) {
            FaceState.Level.NORMAL -> Tone.INFO
            FaceState.Level.WARN   -> Tone.WARN
            FaceState.Level.ALERT  -> Tone.ALERT
        }
        return Slot(owner.rank, t.text, tone)
    }

    /** The slot replaces the delta row (W2/W3: the data is old) instead of sitting under it. */
    fun slotReplacesDelta(slot: Slot?): Boolean = slot != null && (slot.rank == 2 || slot.rank == 3)

    /** First start: nothing has ever arrived from the phone. */
    fun firstStart(bgTimeStamp: Long, openApsStatus: Long, iobSum: String): Boolean = FaceState.firstStart(bgTimeStamp, openApsStatus, iobSum)

    /**
     * The phone sends a localised externalStatus: "" when the loop runs, the "no profile" text, or the
     * "loop disabled" text. Without a profile it sends no IOB either, which tells the two apart.
     */
    fun noProfile(externalStatus: String, iobSum: String): Boolean = FaceState.loopRun(externalStatus, iobSum) == FaceState.LoopRun.NO_PROFILE
    fun loopOff(externalStatus: String, iobSum: String): Boolean = FaceState.loopRun(externalStatus, iobSum) == FaceState.LoopRun.OFF

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
    fun loopAgeMinutes(openApsStatus: Long, now: Long): Long? = FaceState.loopAgeMinutes(openApsStatus, now)

    enum class LoopHealth { NEVER, OK, WARN, BAD }

    /** A LoopStatesList older than the last status by more than this is not trusted (states.md loopMode). */
    const val LOOP_STATES_MAX_AGE_MS = FaceState.LOOP_STATES_MAX_AGE_MS

    /**
     * loopMode (states.md §1, dataset 0, best effort): the one of CLOSED / OPEN / LGS that is missing from the
     * offered LoopStatesList entries ([offered] holds their names). UNKNOWN when the list is missing, older than the
     * last status by > 15 min, or ambiguous.
     */
    fun loopMode(offered: Collection<String>, listTimeStamp: Long, lastStatus: Long): LoopMode = FaceState.loopMode(offered, listTimeStamp, lastStatus)

    /**
     * Red budget (states.md rule 3): the LOOP ring is muted when the BG accent and the pill are both red, and whenever
     * the pill already says the data is old (W2 / W3) or names the loop (W6), as [FaceState.loopNeutral].
     */
    fun muteLoopRing(range: Range, slot: Slot?): Boolean =
        (isLow(range) && slot?.tone == Tone.ALERT) || slot?.rank == 2 || slot?.rank == 3 || slot?.rank == 6

    /** <= 5 OK, 6..14 WARN, >= 15 BAD. No loop run at all (-1) is NEVER: grey, never red. */
    fun loopHealth(ageMinutes: Long?, realRun: Boolean = true): LoopHealth =
        when (FaceState.loopHealth(ageMinutes, FaceState.LoopRun.RUNNING, LoopMode.CLOSED, realRun)) {
            FaceState.LoopHealth.OK   -> LoopHealth.OK
            FaceState.LoopHealth.WARN -> LoopHealth.WARN
            FaceState.LoopHealth.BAD  -> LoopHealth.BAD
            else                      -> LoopHealth.NEVER
        }

    /**
     * LOOP ball. Loop off: amber ring, pause glyph, "AUS". Never looped: dashed grey "--" over "LOOP" (UX round 3: also
     * with pump data but no loop run; "Pumpe" read like a pump alarm). Otherwise the age coloured by health.
     */
    fun loopBall(
        openApsStatus: Long, externalStatus: String, iobSum: String, now: Long, labels: Labels, mode: LoopMode = LoopMode.UNKNOWN,
        realRun: Boolean = true
    ): Ball {
        if (loopOff(externalStatus, iobSum)) return Ball("", labels.loopOffShort, Ring.AMBER, pause = true)
        val age = loopAgeMinutes(openApsStatus, now)
        val health = loopHealth(age, realRun)
        // loop.open: neutral dashed loop.ok ring with "Offen"; the age is not an alarm
        if (mode == LoopMode.OPEN && health != LoopHealth.NEVER) return Ball(labels.loopOpen, "LOOP", Ring.GREEN, dashed = true)
        val label = if (mode == LoopMode.LGS) labels.loopLgs else "LOOP"
        return when (health) {
            LoopHealth.NEVER -> Ball("--", "LOOP", Ring.DIM, dashed = true)
            LoopHealth.OK    -> Ball(ageText(age, labels), label, Ring.GREEN)
            LoopHealth.WARN  -> Ball(ageText(age, labels), label, Ring.AMBER)
            LoopHealth.BAD   -> Ball(ageText(age, labels), label, Ring.RED)
        }
    }

    /** LOOP ball visibility: key_show_external_status, forced on for loop.bad (safety exception). */
    fun loopBallShown(showPref: Boolean, health: LoopHealth): Boolean = showPref || health == LoopHealth.BAD

    /** Phone placeholders that must never be drawn ("IOB" before the first status, "-.--", "--g", "no status"). */
    fun display(s: String): String = FaceState.display(s)

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
        if (value == "--") return Ball(value, "U/h", Ring.DIM)
        val rate = leadingNumber(value)
        val zero = rate != null && abs(rate) < 1e-6
        val tbr = tbrHint || value.endsWith("%")
        return when {
            tbr  -> Ball(value, "TBR", if (zero) Ring.AMBER else Ring.TEAL)
            zero -> Ball(value, "U/h", Ring.AMBER)
            else -> Ball(value, "U/h", Ring.NEUTRAL)
        }
    }

    /** COB > 0 neutral (spec v2: amber means "look soon"), else dim. "12(3)g" counts as 12. Label "KH" in German. */
    fun cobBall(cob: String, labels: Labels): Ball {
        val v = display(cob)
        val n = leadingNumber(v)
        return Ball(v, labels.cob, if (n != null && n > 0.0) Ring.NEUTRAL else Ring.DIM)
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

    /** Ball value size (UX round 1: 22 -> 24) and the step-down size for wide values; labels 15 -> 18 (UX round 2, short words). */
    const val BALL_VALUE_SIZE = 24f
    const val BALL_VALUE_SMALL = 19f
    const val BALL_LABEL_SIZE = 18f

    /** Value text size: 24 px; wider than 56 px at 24 -> 19 px bold; still too wide -> shrink to fit 60 px. */
    fun ballValueSize(widthAt24: Float): Float {
        if (widthAt24 <= BALL_VALUE_MAX) return BALL_VALUE_SIZE
        val small = widthAt24 * BALL_VALUE_SMALL / BALL_VALUE_SIZE
        return if (small <= BALL_VALUE_HARD_MAX) BALL_VALUE_SMALL else BALL_VALUE_SMALL * BALL_VALUE_HARD_MAX / small
    }

    /**
     * Hero group budget (spec §4.2): BG + gap + glyph (both scaled with the BG size). UX round 3: 224 (was 240) so a
     * wide "22,2" + arrow keeps clear of the 10 and 2 o'clock satellites; the glyph sits closer (10 px, 36 px wide), so
     * BG and arrow read as one group centred on the dial.
     */
    const val HERO_GROUP_MAX = 224f
    const val HERO_GLYPH_GAP = 10f
    const val HERO_GLYPH_W = 36f
    const val HERO_MIN_SIZE = 60f

    /** Hero start size (UX round 1: 108 -> 120, the family's scale). Widths are measured at [HERO_REF] px. */
    const val HERO_SIZE = 120f
    const val HERO_REF = 108f

    fun heroGroupWidth(widthAt108: Float, size: Float, glyph: Boolean): Float =
        widthAt108 * size / HERO_REF + if (glyph) HERO_GLYPH_GAP + HERO_GLYPH_W * size / HERO_REF else 0f

    /** Hero centre line (the digits are centred on it). */
    const val HERO_Y = 190f

    /** UX round 4: the hero group keeps this much clear space to the 10 and 2 o'clock satellites (LOOP, IOB). */
    const val SAT_CLEAR = 20f

    /**
     * True when the hero group at [size] keeps [SAT_CLEAR] to the LOOP and IOB satellites: the digits' top corners
     * (cap top about 0.37 em above the centre line, taken 0.1 em inside) and the trend glyph's outer top corner (its strokes reach about
     * 0.14 em around the glyph centre) are checked against the satellite shells.
     */
    fun heroClear(widthAt108: Float, size: Float, glyph: Boolean): Boolean {
        val group = heroGroupWidth(widthAt108, size, glyph)
        val left = CX - group / 2f
        val digitsRight = left + widthAt108 * size / HERO_REF
        val minD = BALL_R + SAT_CLEAR
        // digits' outer top corners, 0.1 em inside the box (the bowls of 6/8/9/0 and the stems of 1/4 are round or inset)
        val inset = 0.10f * size
        val capTop = HERO_Y - 0.37f * size + inset
        fun clear(x: Float, y: Float, deg: Float) =
            kotlin.math.hypot(x - pointX(SAT_R, deg), y - pointY(SAT_R, deg)) >= minD
        if (!clear(left + inset, capTop, DEG_LOOP) || !clear(digitsRight - inset, capTop, DEG_IOB)) return false
        if (!glyph) return true
        val glyphCentre = digitsRight + HERO_GLYPH_GAP + HERO_GLYPH_W * size / HERO_REF / 2f
        return clear(glyphCentre + 0.14f * size, HERO_Y - 0.14f * size, DEG_IOB)
    }

    /**
     * [HERO_SIZE], stepped down by 4 until the group fits [HERO_GROUP_MAX] and (UX round 4) keeps clear of the
     * satellites ([heroClear]); minimum 60.
     */
    fun heroSize(widthAt108: Float, glyph: Boolean): Float {
        var size = HERO_SIZE
        while (size > HERO_MIN_SIZE && (heroGroupWidth(widthAt108, size, glyph) > HERO_GROUP_MAX || !heroClear(widthAt108, size, glyph))) size -= 4f
        return size
    }

    /** Shrinks a text size so a text measured at [size] fits [maxWidth]. */
    fun fitSize(widthAtSize: Float, size: Float, maxWidth: Float): Float =
        if (widthAtSize <= maxWidth || widthAtSize <= 0f) size else size * maxWidth / widthAtSize

    // ------------------------------------------------------------------ bolus amount

    /** Watch-generated number in the watch locale, the family's "+0,5" ("+0.5" in English); the unit is drawn under it. */
    fun bolusAmountText(amount: Double, locale: Locale): String = FaceState.bolusAmountText(amount, locale)

    // ------------------------------------------------------------------ time and date

    private val WEEKDAY = DateTimeFormatter.ofPattern("EE", Locale.GERMANY)
    private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.GERMANY)

    /**
     * Date from the renderer's date. German: "Fr 17. Okt" (Locale.GERMANY, trailing dots of the abbreviations
     * removed). Other locales: [bestPattern] (Android's getBestDateTimePattern(locale, "EEEdMMM"), e.g.
     * "EEE, MMM d" -> "Fri, Oct 17"). [weekFormat] non-null appends " · KW 42" (key_show_week_number, ISO week).
     */
    fun dateString(
        year: Int, month1: Int, day: Int, locale: Locale = Locale.GERMANY, bestPattern: String? = null, weekFormat: String? = null
    ): String {
        val d = LocalDate.of(year, month1, day)
        val base = if (locale.language == "de" || bestPattern.isNullOrBlank())
            "${WEEKDAY.format(d).trimEnd('.')} $day. ${MONTH.format(d).trimEnd('.')}"
        else DateTimeFormatter.ofPattern(bestPattern, locale).format(d)
        if (weekFormat == null) return base
        return base + " · " + String.format(Locale.ROOT, weekFormat, d.get(WeekFields.ISO.weekOfWeekBasedYear()))
    }

    /** "00:40" (24 h) or "12:40" (12 h; the AM/PM suffix is [amPm]) from the renderer's time. */
    fun timeString(hour: Int, minute: Int, is24h: Boolean): String {
        val h = if (is24h) hour else (hour % 12).let { if (it == 0) 12 else it }
        return if (is24h) String.format(Locale.ROOT, "%02d:%02d", h, minute) else String.format(Locale.ROOT, "%d:%02d", h, minute)
    }

    /** 12 h suffix (locale.en_12h): "AM" / "PM", empty in 24 h. */
    fun amPm(hour: Int, is24h: Boolean): String = if (is24h) "" else if (hour < 12) "AM" else "PM"

    /** Maximum burn-in shift (design px) in each direction (spec §6: (dx, dy) in [-4, 4]). */
    const val BURN_IN_MAX_SHIFT = 4

    /**
     * Burn-in shift for a minute: a deterministic hash of the minute key mapped to [-4, 4] per axis, so the offset
     * changes every minute and visits the whole square (not a fixed small cycle). [axis] 0 = x, 1 = y.
     */
    fun burnInShift(minuteKey: Long, axis: Int): Int = FaceState.burnInShift(minuteKey, axis)

    /** Graph double tap: 1 -> 2 -> 3 -> 4 -> 5 -> 1 hours (like the other AAPS faces). */
    fun nextTimeFrame(current: Int): Int = current.coerceIn(1, 5) % 5 + 1

    // ------------------------------------------------------------------ text width estimate

    /**
     * Conservative width estimate (design px) of [s] at [size] px: upper bounds of the per-glyph advance (in em) of
     * the default Android sans (Roboto / Google Sans) at Semibold / Bold. Used by the worst-case tests and as a
     * plausibility bound; the face itself measures with Paint.
     */
    fun estimateWidth(s: String, size: Float): Float {
        var em = 0f
        for (c in s) em += when (c) {
            in '0'..'9'           -> 0.60f
            ',', '.', ':', '′', '\'' -> 0.30f
            ' '                   -> 0.28f
            '·'                   -> 0.30f
            '-', '–', '(', ')'    -> 0.40f
            '+', '%'              -> 0.70f
            'm', 'w', 'M', 'W'    -> 0.92f
            'i', 'l', 'j', 'I', 'f', 't', 'r' -> 0.38f
            in 'A'..'Z'           -> 0.72f
            else                  -> 0.60f
        }
        return em * size
    }

    // ------------------------------------------------------------------ orbit geometry

    fun pointX(r: Float, deg: Float): Float = CX + r * kotlin.math.sin(Math.toRadians(deg.toDouble())).toFloat()
    fun pointY(r: Float, deg: Float): Float = CY - r * kotlin.math.cos(Math.toRadians(deg.toDouble())).toFloat()

    /** Text margin on each side of a track gap (UX round 1: the cut is the text width ±8 px, centred on it). */
    const val GAP_MARGIN = 8f

    /** Half angle (deg) of the track gap that holds a text of [textWidth]: (w + 16) / 2 / r rad, capped at [maxDeg]. */
    fun gapHalfDeg(textWidth: Float, maxDeg: Float = MAX_GAP_DEG): Float =
        min(Math.toDegrees(((textWidth + 2f * GAP_MARGIN) / 2f / ORBIT_R).toDouble()).toFloat(), maxDeg)

    /** Angular half width of the part of the track (r [ORBIT_R]) that a ball (r [BALL_R] at [SAT_R]) covers. */
    val BALL_HALF_DEG: Float = Math.toDegrees(
        kotlin.math.acos(((ORBIT_R * ORBIT_R + SAT_R * SAT_R - BALL_R * BALL_R) / (2f * ORBIT_R * SAT_R)).toDouble())
    ).toFloat()

    /** The gaps may grow until they touch the nearest ball (60 deg away from 0 and 180). */
    val MAX_GAP_DEG: Float = 60f - BALL_HALF_DEG - 0.5f

    /** Moon occlusion limit for a ball and for the bolus button. */
    val BALL_OCCLUDE_DEG: Float = BALL_HALF_DEG + 1.5f
    val BOLUS_OCCLUDE_DEG: Float = Math.toDegrees(atan((BOLUS_HALF * 1.1f / SAT_R).toDouble())).toFloat() + 1.5f

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

    /**
     * -1 below the box, +1 above, 0 inside. The line stays flat at the limit; one small chevron marks the end of each
     * clamped run (UX round 1: no caret per reading, which read as a sawtooth glitch), see [clampMarks].
     */
    fun sparkClamp(mgdl: Double): Int = when {
        mgdl < SPARK_MIN_MGDL -> -1
        mgdl > SPARK_MAX_MGDL -> 1
        else                  -> 0
    }

    /** x of a reading [t] when the right end of the history (x 265) is [now] and the window is [windowMs]. */
    fun sparkX(t: Long, now: Long, windowMs: Long = SPARK_HISTORY_MS): Float =
        SPARK_X_NOW - (now - t).toFloat() / windowMs * (SPARK_X_NOW - SPARK_X0)

    /**
     * Indices of the readings that get a clamp chevron: the last reading of each run of readings clamped to the same
     * edge (a run ends at a different clamp state or at a gap break). [clamps] per reading, [breaks] true where the line
     * restarts at that reading.
     */
    fun clampMarks(clamps: IntArray, breaks: BooleanArray): List<Int> {
        val out = ArrayList<Int>()
        for (i in clamps.indices) {
            if (clamps[i] == 0) continue
            val last = i == clamps.size - 1 || clamps[i + 1] != clamps[i] || breaks[i + 1]
            if (last) out += i
        }
        return out
    }

    /** Gap rule (graph.gaps): readings more than 15 min apart are not joined. */
    const val SPARK_GAP_MS = 15 * 60_000L
    fun sparkBreak(prevT: Long, t: Long): Boolean = t - prevT > SPARK_GAP_MS

    /** x of the k-th prediction dot (1..5), spread over x 265..291. */
    fun predictionX(k: Int): Float = SPARK_X_NOW + k * (SPARK_X1 - SPARK_X_NOW) / PREDICTION_DOTS
}
