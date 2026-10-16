package app.aaps.wear.watchfaces.halo

import kotlin.math.max
import kotlin.math.min

/** Pure helpers for the "AAPS Halo" watch face (value mapping, states, formatting), unit tested. */
object HaloFaceLogic {

    /**
     * SharedPreferences keys of the bolus ball. Must equal key_halo_bolus_double_tap / key_halo_bolus_amount in
     * strings_halo.xml (checked by a unit test). They are watch-only settings and deliberately NOT part of
     * CwfMetadataKey, so an imported custom watch face can never switch the bolus request on.
     */
    const val PREF_BOLUS_DOUBLE_TAP = "halo_bolus_double_tap"
    const val PREF_BOLUS_AMOUNT = "halo_bolus_amount"

    /** Ring start (Canvas degrees, 0 = 3 o'clock, clockwise) and sweep; the gap is at the bottom. */
    const val RING_START = 135f
    const val RING_SWEEP = 270f

    /** Ring scale in mg/dL (2.2 .. 16.7 mmol/L). */
    const val SCALE_MIN_MGDL = 40.0
    const val SCALE_MAX_MGDL = 300.0

    const val DEFAULT_LOW_MGDL = 70.0
    const val DEFAULT_HIGH_MGDL = 180.0

    /** BG age (minutes) from which the age is shown amber, and from which the reading counts as stale (red). */
    const val BG_OLD_MINUTES = 6L
    const val BG_STALE_MINUTES = 12L

    const val NO_DATA = "--"

    /** Canvas angle on the ring for a BG value in mg/dL. */
    fun angle(mgdl: Double): Float {
        val f = ((mgdl - SCALE_MIN_MGDL) / (SCALE_MAX_MGDL - SCALE_MIN_MGDL)).coerceIn(0.0, 1.0)
        return RING_START + (f * RING_SWEEP).toFloat()
    }

    // ------------------------------------------------------------------ loop

    enum class LoopState { OK, WARN, STALE }

    /**
     * Loop age in whole minutes, or null when there is no loop status (-1).
     *
     * Note: the only loop time the watch receives is EventData.Status.openApsStatus. On an APS phone that is
     * loop.lastRun.lastTBREnact (the last time a temp basal was enacted), not lastRun.lastAPSRun; there is no
     * other field with the loop run time in the watch data. The stock AAPS faces use the same value. Showing the
     * real loop run age needs a phone-side change (a new field in EventData.Status).
     */
    fun loopAgeMinutes(openApsStatus: Long, now: Long): Long? =
        if (openApsStatus <= 0L) null else max(0L, (now - openApsStatus) / 60000L)

    /** <= 5' OK, 6..14' WARN, > 14' or no data STALE (the stock faces turn red after 14'). */
    fun loopState(ageMinutes: Long?): LoopState = when {
        ageMinutes == null -> LoopState.STALE
        ageMinutes <= 5    -> LoopState.OK
        ageMinutes <= 14   -> LoopState.WARN
        else               -> LoopState.STALE
    }

    // ------------------------------------------------------------------ BG age

    enum class AgeLevel { FRESH, OLD, STALE }

    /** BG age in whole minutes, or null without a reading. */
    fun bgAgeMinutes(timeStamp: Long, now: Long): Long? = if (timeStamp <= 0L) null else max(0L, (now - timeStamp) / 60000L)

    /** No reading or >= 12' STALE, 6..11' OLD, otherwise FRESH. */
    fun bgAgeLevel(ageMinutes: Long?): AgeLevel = when {
        ageMinutes == null               -> AgeLevel.STALE
        ageMinutes >= BG_STALE_MINUTES   -> AgeLevel.STALE
        ageMinutes >= BG_OLD_MINUTES     -> AgeLevel.OLD
        else                             -> AgeLevel.FRESH
    }

    fun isStale(timeStamp: Long, now: Long): Boolean = bgAgeLevel(bgAgeMinutes(timeStamp, now)) == AgeLevel.STALE

    /** How the hero BG is drawn in ambient: a stale reading must look different there too (struck through, dimmed). */
    enum class AmbientHero { OUTLINE, STRUCK }

    fun ambientHero(level: AgeLevel): AmbientHero = if (level == AgeLevel.STALE) AmbientHero.STRUCK else AmbientHero.OUTLINE

    /** Age label like "3'", or "--" without a reading. */
    fun ageText(ageMinutes: Long?): String = ageMinutes?.let { "$it'" } ?: NO_DATA

    // ------------------------------------------------------------------ text values

    /** "--" for missing values (blank, or only dashes like the "---" placeholders of the watch data). */
    fun orDash(value: String?): String =
        if (value == null || value.isBlank() || value.all { it == '-' }) NO_DATA else value

    /** Parse an IOB string like "2.53", "-0,10" or "1.2U"; null when unreadable. */
    fun parseIob(iob: String?): Double? {
        if (iob == null) return null
        val cleaned = iob.replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }
        return cleaned.toDoubleOrNull()
    }

    /** Fraction (0..1) of the IOB arc to fill; 6 U fills it, negative IOB fills nothing. */
    fun iobFraction(iob: Double?): Float = if (iob == null || iob <= 0.0) 0f else min(iob / 6.0, 1.0).toFloat()

    /** "TT 5.6   ·   0.00U/h" with an active temp target (level 2), otherwise only the basal ("--" without data). */
    fun secondaryLine(tempTarget: String, tempTargetLevel: Int, basal: String): String =
        if (tempTargetLevel == 2 && tempTarget.isNotBlank() && orDash(tempTarget) != NO_DATA) "TT $tempTarget   ·   ${orDash(basal)}"
        else orDash(basal)

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

    private val BURN_IN_X = floatArrayOf(0f, 5f, 0f, -5f, 3f, -3f)
    private val BURN_IN_Y = floatArrayOf(0f, 0f, 5f, 0f, -4f, -4f)

    /** Ambient shift (design px) for screens with burn-in protection; changes every minute, never more than 5 px. */
    fun burnInOffsetX(minute: Int): Float = BURN_IN_X[Math.floorMod(minute, BURN_IN_X.size)]
    fun burnInOffsetY(minute: Int): Float = BURN_IN_Y[Math.floorMod(minute, BURN_IN_Y.size)]
}
