package app.aaps.wear.watchfaces.kusi

import kotlin.math.max
import kotlin.math.min

/** Pure helpers for the Kusi watch face (value mapping, states), unit tested. */
object KusiFaceLogic {

    /** Ring start (Canvas degrees, 0 = 3 o'clock, clockwise) and sweep; the gap is at the bottom. */
    const val RING_START = 135f
    const val RING_SWEEP = 270f

    /** Ring scale in mg/dL (2.2 .. 16.7 mmol/L). */
    const val SCALE_MIN_MGDL = 40.0
    const val SCALE_MAX_MGDL = 300.0

    const val DEFAULT_LOW_MGDL = 70.0
    const val DEFAULT_HIGH_MGDL = 180.0

    /** Readings older than this are shown as stale. */
    const val STALE_MS = 12 * 60 * 1000L

    /** Canvas angle on the ring for a BG value in mg/dL. */
    fun angle(mgdl: Double): Float {
        val f = ((mgdl - SCALE_MIN_MGDL) / (SCALE_MAX_MGDL - SCALE_MIN_MGDL)).coerceIn(0.0, 1.0)
        return RING_START + (f * RING_SWEEP).toFloat()
    }

    enum class LoopState { OK, WARN, STALE }

    /** Loop age in whole minutes, or null when there is no loop status (-1). */
    fun loopAgeMinutes(openApsStatus: Long, now: Long): Long? =
        if (openApsStatus == -1L || openApsStatus <= 0L) null else max(0L, (now - openApsStatus) / 60000L)

    /** <= 5' OK, 6..14' WARN, > 14' or no data STALE. */
    fun loopState(ageMinutes: Long?): LoopState = when {
        ageMinutes == null -> LoopState.STALE
        ageMinutes <= 5    -> LoopState.OK
        ageMinutes <= 14   -> LoopState.WARN
        else               -> LoopState.STALE
    }

    /** True when there is no reading or it is older than 12 minutes. */
    fun isStale(timeStamp: Long, now: Long): Boolean = timeStamp == 0L || now - timeStamp > STALE_MS

    /** BG age in whole minutes, or null without a reading. */
    fun bgAgeMinutes(timeStamp: Long, now: Long): Long? = if (timeStamp == 0L) null else max(0L, (now - timeStamp) / 60000L)

    /** Parse an IOB string like "2.53", "-0,10" or "1.2U"; null when unreadable. */
    fun parseIob(iob: String?): Double? {
        if (iob == null) return null
        val cleaned = iob.replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }
        return cleaned.toDoubleOrNull()
    }

    /** Fraction (0..1) of the IOB arc to fill; 6 U fills it, negative IOB fills nothing. */
    fun iobFraction(iob: Double?): Float = if (iob == null || iob <= 0.0) 0f else min(iob / 6.0, 1.0).toFloat()

    /** Delta and BG age as one string, e.g. "+0.1 · 1'". */
    fun deltaLine(delta: String, ageMinutes: Long?): String =
        if (ageMinutes == null) delta else "$delta · $ageMinutes'"

    /** "TT 5.6   ·   0.00U/h" with an active temp target (level 2), otherwise only the basal. */
    fun secondaryLine(tempTarget: String, tempTargetLevel: Int, basal: String): String =
        if (tempTargetLevel == 2 && tempTarget.isNotBlank()) "TT $tempTarget   ·   $basal" else basal
}
