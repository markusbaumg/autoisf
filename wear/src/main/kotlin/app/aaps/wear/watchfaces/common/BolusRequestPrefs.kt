package app.aaps.wear.watchfaces.common

import app.aaps.core.interfaces.sharedPreferences.SP
import java.util.Locale

/**
 * The one preference pair of the double-tap bolus request, shared by the Halo, Glass and Orbit faces, plus the
 * persisted time of the last sent request (so the 30 s pending window also holds across a face switch).
 *
 * Watch-only keys: they are deliberately NOT part of CwfMetadataKey, so an imported custom watch face can never
 * switch the bolus request on (checked by a unit test). Default OFF, amount 0.5 U.
 * Must equal key_face_bolus_request_enabled / key_face_bolus_request_amount in strings_face_bolus.xml (unit test).
 */
object BolusRequestPrefs {

    const val KEY_ENABLED = "face_bolus_request_enabled"
    const val KEY_AMOUNT = "face_bolus_request_amount"
    const val KEY_LAST_SENT_AT = "face_bolus_request_last_sent_at"
    const val DEFAULT_AMOUNT = "0.5"

    /** Orbit's bolus ball mode: "fixed" (default, today's double tap sends) or "picker" (double tap opens the picker). */
    const val KEY_MODE = "face_bolus_request_mode"
    const val MODE_FIXED = "fixed"
    const val MODE_PICKER = "picker"
    /** Picker only: offer 1.5 U as a third amount. Default off. */
    const val KEY_ALLOW_1_5 = "face_bolus_request_allow_1_5"

    enum class Mode { FIXED, PICKER }

    /** Per-face keys of earlier iterations (enabled, amount), migrated once by [migrate]. */
    val LEGACY_KEYS = listOf(
        "halo_bolus_double_tap" to "halo_bolus_amount",
        "orbit_bolus_double_tap" to "orbit_bolus_amount",
        "kusi_bolus_double_tap" to "kusi_bolus_amount"
    )
    val LEGACY_LAST_SENT_KEYS = listOf("halo_bolus_last_sent_at")

    fun enabled(sp: SP): Boolean = sp.getBoolean(KEY_ENABLED, false)

    /** 0.5 or 1.0; NaN when a value is stored that is not one of them (the button then stays hidden). */
    fun amount(sp: SP): Double = parseAmountStrict(sp.getStringOrNull(KEY_AMOUNT, null))

    /** Only an exact "picker" selects the picker; unset or anything else is the fixed ball (today's behaviour). */
    fun mode(sp: SP): Mode = if (sp.getStringOrNull(KEY_MODE, null)?.trim() == MODE_PICKER) Mode.PICKER else Mode.FIXED

    fun allow15(sp: SP): Boolean = sp.getBoolean(KEY_ALLOW_1_5, false)

    /** The picker's amounts: 0.5 and 1.0, plus 1.5 only with [allow15]; never above the hard ceiling. */
    fun pickerAmounts(allow15: Boolean): List<Double> =
        (if (allow15) BolusTapGesture.ALLOWED_AMOUNTS + BolusTapGesture.AMOUNT_1_5 else BolusTapGesture.ALLOWED_AMOUNTS)
            .filter { it <= BolusTapGesture.HARD_MAX_AMOUNT + 1e-6 }

    fun pickerAmounts(sp: SP): List<Double> = pickerAmounts(allow15(sp))

    /** The phone's max bolus (key_treatments_safety_max_bolus); NaN when it was never synced (=> refuse). */
    fun maxBolus(sp: SP, maxBolusKey: String): Double =
        if (sp.contains(maxBolusKey)) sp.getDouble(maxBolusKey, Double.NaN) else Double.NaN

    /** [sp] is resolved on every access (the faces create the guard before injection). */
    fun lastSentStore(sp: () -> SP): BolusRequestGuard.LastSentStore = object : BolusRequestGuard.LastSentStore {
        override var lastSentAt: Long
            get() = sp().getLong(KEY_LAST_SENT_AT, 0L)
            set(value) = sp().putLong(KEY_LAST_SENT_AT, value)
    }

    /** Unset: the default 0.5 U. Set: only an exact 0.5 or 1.0 counts, anything else is NaN. */
    fun parseAmountStrict(value: String?): Double {
        if (value.isNullOrBlank()) return BolusTapGesture.DEFAULT_AMOUNT
        val parsed = value.trim().replace(',', '.').toDoubleOrNull() ?: return Double.NaN
        return BolusTapGesture.ALLOWED_AMOUNTS.firstOrNull { kotlin.math.abs(it - parsed) < 1e-6 } ?: Double.NaN
    }

    /**
     * One-time move of the per-face settings (halo_ / orbit_ / kusi_) to the shared pair; idempotent, run by every
     * face on start. Never raises anything: the shared switch is on only when an old switch was on, and the amount is
     * the smallest valid amount of the faces that had it on (of all old faces when none was on). Existing shared
     * values win. The old keys are removed afterwards, the pending time keeps the latest value.
     */
    fun migrate(sp: SP) {
        val present = LEGACY_KEYS.filter { (e, a) -> sp.contains(e) || sp.contains(a) }
        if (present.isNotEmpty()) {
            val enabledOnes = present.filter { (e, _) -> sp.getBoolean(e, false) }
            if (!sp.contains(KEY_ENABLED)) sp.putBoolean(KEY_ENABLED, enabledOnes.isNotEmpty())
            if (!sp.contains(KEY_AMOUNT)) {
                val amounts = (enabledOnes.ifEmpty { present })
                    .map { (_, a) -> parseAmountStrict(sp.getStringOrNull(a, null)) }
                    .filterNot { it.isNaN() }
                amounts.minOrNull()?.let { sp.putString(KEY_AMOUNT, String.format(Locale.US, "%.1f", it)) }
            }
            present.forEach { (e, a) ->
                sp.remove(e)
                sp.remove(a)
            }
        }
        LEGACY_LAST_SENT_KEYS.filter { sp.contains(it) }.forEach { key ->
            val old = sp.getLong(key, 0L)
            if (old > sp.getLong(KEY_LAST_SENT_AT, 0L)) sp.putLong(KEY_LAST_SENT_AT, old)
            sp.remove(key)
        }
    }
}
