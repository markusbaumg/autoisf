package app.aaps.wear.watchfaces.orbit

/**
 * Hardened rules for the Orbit bolus button, on top of the shared [app.aaps.wear.watchfaces.kusi.BolusTapGesture]
 * (which only knows arm / send / disarm / cooldown).
 *
 * The same rule set is being added to the shared gesture by the Halo work; once that lands this class can be
 * replaced by the shared one:
 * - default OFF (watch preference, not settable from a CustomWatchface zip)
 * - never in ambient
 * - only with Wear control enabled on the phone
 * - amount must be > 0 and <= the phone's max bolus (key_treatments_safety_max_bolus)
 * - the phone must be connected (a message within [PHONE_FRESH_MS])
 * - 800 ms double-tap window, 5 s cooldown after a request
 * - dedupe: a tap event delivered twice and a second request inside the cooldown are dropped
 *
 * Pure logic, unit tested. The face only sends a REQUEST (ActionBolusPreCheck); the phone checks it again and the
 * watch shows the standard confirmation.
 */
class OrbitBolusGuard(private val clock: () -> Long) {

    enum class State {

        /** Button not drawn, no hit target. */
        HIDDEN,

        /** Drawn dimmed, taps ignored (phone not connected, amount above max bolus). */
        BLOCKED,

        /** Drawn and active. */
        READY
    }

    enum class Reason { NONE, PREF_OFF, AMBIENT, NO_WEAR_CONTROL, AMOUNT_INVALID, OVER_MAX_BOLUS, PHONE_LOST }

    data class Check(val state: State, val reason: Reason)

    private var lastTapEventTime = Long.MIN_VALUE
    private var lastRequestAt: Long? = null

    fun check(prefOn: Boolean, ambient: Boolean, wearControl: Boolean, amount: Double, maxBolus: Double, lastPhoneMessage: Long): Check {
        val now = clock()
        return when {
            !prefOn                                                     -> Check(State.HIDDEN, Reason.PREF_OFF)
            ambient                                                     -> Check(State.HIDDEN, Reason.AMBIENT)
            !wearControl                                                -> Check(State.HIDDEN, Reason.NO_WEAR_CONTROL)
            !(amount > 0.0) || amount.isNaN()                           -> Check(State.BLOCKED, Reason.AMOUNT_INVALID)
            amount > maxBolus + 1e-9                                    -> Check(State.BLOCKED, Reason.OVER_MAX_BOLUS)
            lastPhoneMessage <= 0L || now - lastPhoneMessage > PHONE_FRESH_MS -> Check(State.BLOCKED, Reason.PHONE_LOST)
            else                                                        -> Check(State.READY, Reason.NONE)
        }
    }

    /** False for the second delivery of the same tap event (same timestamp). */
    fun acceptTap(eventTime: Long): Boolean {
        if (eventTime == lastTapEventTime) return false
        lastTapEventTime = eventTime
        return true
    }

    /** False while a previous request is inside the cooldown, even when the gesture was reset in between. */
    fun mayRequest(): Boolean {
        val at = lastRequestAt ?: return true
        val d = clock() - at
        return d < 0 || d >= COOLDOWN_MS
    }

    fun recordRequest() {
        lastRequestAt = clock()
    }

    companion object {

        const val WINDOW_MS = 800L
        const val COOLDOWN_MS = 5_000L
        const val PHONE_FRESH_MS = 5 * 60_000L
        const val DEFAULT_MAX_BOLUS = 3.0
    }
}
