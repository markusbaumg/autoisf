package app.aaps.wear.watchfaces.halo

import app.aaps.wear.watchfaces.common.FaceState
import kotlin.math.max

/**
 * Pure helpers of the "AAPS Halo" watch face (ring mapping, loop time memory, which line carries which state, text
 * formatting), unit tested. The state itself (BG age, range, loop health, phone link, warning order) comes from the
 * family helper [FaceState], so Halo, Glass and Orbit can never disagree.
 */
object HaloFaceLogic {

    /** Ring start (Canvas degrees, 0 = 3 o'clock, clockwise) and sweep; the gap is at the bottom. */
    const val RING_START = 135f
    const val RING_SWEEP = 270f

    /** Ring scale in mg/dL (2.2 .. 16.7 mmol/L). */
    const val SCALE_MIN_MGDL = 40.0
    const val SCALE_MAX_MGDL = 300.0

    const val NO_DATA = FaceState.NO_DATA

    /** Canvas angle on the ring for a BG value in mg/dL. */
    fun angle(mgdl: Double): Float {
        val f = ((mgdl - SCALE_MIN_MGDL) / (SCALE_MAX_MGDL - SCALE_MIN_MGDL)).coerceIn(0.0, 1.0)
        return RING_START + (f * RING_SWEEP).toFloat()
    }

    // ------------------------------------------------------------------ first start

    /**
     * First start: nothing has arrived from the phone yet (no BG, no loop time, IOB placeholder). The bolus ball stays
     * hidden then, like on Glass and Orbit.
     */
    fun firstStart(bgTimeStamp: Long, loopTime: Long, iobSum: String): Boolean = FaceState.firstStart(bgTimeStamp, loopTime, iobSum)

    // ------------------------------------------------------------------ loop time

    /**
     * Loop time to show: the received time when it is valid (> 0), otherwise the remembered one.
     *
     * An older phone app sends openApsStatus = -1 whenever loop.lastRun.lastTBREnact is 0, which happens on every loop
     * run that enacts no new temp basal and after a phone app restart until the first enact. The stock faces skip -1 and
     * keep the last value; this does the same, and the caller persists the result.
     */
    fun rememberedLoopTime(received: Long, remembered: Long): Long = if (received > 0L) received else max(remembered, 0L)

    /** Loop age in whole minutes, or null when there is no loop time. */
    fun loopAgeMinutes(loopTime: Long, now: Long): Long? = FaceState.loopAgeMinutes(loopTime, now)

    /**
     * The face's loop time across updates and face recreation: starts from the persisted value ([load], e.g. after a
     * watch reboot or when the system recreates the face service), keeps it when the phone sends -1, and persists
     * ([store]) only a valid time received from the phone.
     */
    class LoopTimeMemory(load: () -> Long, private val store: (Long) -> Unit) {

        var time: Long = max(load(), 0L)
            private set

        /** Applies a received loop time; true when the remembered time changed. */
        fun onReceived(loopTime: Long): Boolean {
            val kept = rememberedLoopTime(loopTime, time)
            if (kept == time) return false
            time = kept
            store(kept)
            return true
        }
    }

    // ------------------------------------------------------------------ which line carries what

    /**
     * What the meta line under the BG shows. The BG age is ALWAYS visible there:
     * TREND_DELTA_AGE "→ +0,1 · 2′" while the reading is fresh or aging; AGE_ONLY "17′" (alert) for an old reading;
     * WARNING "Keine Daten · 17′" (alert) when W2 / W3 own the status slot: then the age, with its reason, is the most
     * prominent text under the struck-through value, and arrow and delta (which describe old data) are gone.
     */
    enum class Meta { TREND_DELTA_AGE, AGE_ONLY, WARNING }

    fun meta(age: FaceState.BgAge, owner: FaceState.Warning?): Meta = when {
        owner != null && owner.dataOld  -> Meta.WARNING
        FaceState.isOld(age)            -> Meta.AGE_ONLY
        else                            -> Meta.TREND_DELTA_AGE
    }

    /** The secondary line (TT / basal) carries the slot owner, except W2 / W3 (in the meta line) and W10 (a glyph). */
    fun secondaryOwner(owner: FaceState.Warning?): FaceState.Warning? =
        owner?.takeIf { !it.dataOld && it != FaceState.Warning.PHONE_LOST_FRESH }

    /** Ambient ring track: the range as a shape, never only as a colour. */
    enum class Track { SOLID, DASHED, DASHED_BOLD, DOUBLE }

    fun ambientTrack(range: FaceState.Range): Track = when (range) {
        FaceState.Range.HIGH                            -> Track.DASHED
        FaceState.Range.VERY_HIGH                       -> Track.DASHED_BOLD
        FaceState.Range.LOW, FaceState.Range.URGENT_LOW -> Track.DOUBLE
        else                                            -> Track.SOLID
    }

    // ------------------------------------------------------------------ text values

    /** "--" for missing values (blank, the placeholders of the watch data store, no digits). */
    fun orDash(value: String?): String = FaceState.display(value)

    /**
     * Splits a value into number and [unit] for the bottom row: "1,44" / "1,2U" with "U" -> ("1,44", "U"),
     * "12(5)g" with "g" -> ("12(5)", "g"). Missing data ("", "IOB", "--g", "---") -> ("--", ""), without a unit.
     */
    fun valueUnit(raw: String?, unit: String): Pair<String, String> {
        val v = orDash(raw).removeSuffix(unit).trim()
        return if (v.isEmpty() || v == NO_DATA || v.none { it.isDigit() }) NO_DATA to "" else v to unit
    }

    const val SEP = "  ·  "

    /** "TT 5,6 · 0,85 U/h" with an active temp target (level 2), otherwise only the basal ("--" without data). */
    fun secondaryLine(tempTarget: String, tempTargetLevel: Int, basal: String): String {
        val b = FaceState.spacedUnit(basal)
        val tt = FaceState.display(tempTarget)
        return if (tempTargetLevel == 2 && tt != NO_DATA) "TT $tt$SEP$b" else b
    }

    /** Text scale so that a text of [width] fits into [maxWidth] (never enlarges). */
    fun fitScale(width: Float, maxWidth: Float): Float = if (width <= maxWidth || width <= 0f) 1f else maxWidth / width

    // ------------------------------------------------------------------ time and date

    private val DAYS_DE = arrayOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    private val MONTHS_DE = arrayOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")

    /**
     * German short date, e.g. "Fr 17. Okt" (the "ccc d. LLL" stand-alone form of Locale.GERMANY, without the dots
     * the format form adds). Fixed tables, so the result does not depend on the platform's CLDR data.
     *
     * @param isoDayOfWeek 1 = Monday .. 7 = Sunday
     * @param month 1..12
     */
    fun germanDate(isoDayOfWeek: Int, dayOfMonth: Int, month: Int): String =
        "${DAYS_DE[(isoDayOfWeek - 1).coerceIn(0, 6)]} $dayOfMonth. ${MONTHS_DE[(month - 1).coerceIn(0, 11)]}"

    /** "07:05" (24 h) or "7:05" (12 h) from the renderer's hour and minute. */
    fun timeText(hour: Int, minute: Int, is24Hour: Boolean): String {
        val h = if (is24Hour) hour else (hour % 12).let { if (it == 0) 12 else it }
        val hh = if (is24Hour && h < 10) "0$h" else h.toString()
        val mm = if (minute < 10) "0$minute" else minute.toString()
        return "$hh:$mm"
    }

    // ------------------------------------------------------------------ burn-in protection

    /** Ambient shift (design px) on burn-in screens: a new offset in [-4, 4] every minute ([minuteKey] = epoch minute). */
    fun burnInOffsetX(minuteKey: Long): Float = FaceState.burnInShift(minuteKey, 0).toFloat()
    fun burnInOffsetY(minuteKey: Long): Float = FaceState.burnInShift(minuteKey, 1).toFloat()
}
