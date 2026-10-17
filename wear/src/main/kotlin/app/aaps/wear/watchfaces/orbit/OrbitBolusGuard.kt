package app.aaps.wear.watchfaces.orbit

/**
 * Hardened rules for the Orbit bolus button, on top of the shared [app.aaps.wear.watchfaces.halo.BolusTapGesture]
 * (which only knows arm / send / disarm / cooldown). Ball state machine of designsystem/states.md §4.6.
 *
 * The same rule set is being added to the shared gesture by the Halo work (BolusRequestGuard); once that lands
 * this class can be replaced by the shared one:
 * - default OFF (watch preference, own key, not settable from a CustomWatchface zip)
 * - HIDDEN: pref off, ambient (any variant), Wear control off, amount not in [ALLOWED_AMOUNTS], first start
 *   (nothing ever received from the phone)
 * - DISABLED: phone reachability not yet known or not reachable / no message within [DATA_FRESH_MS], BG older
 *   than [DATA_FRESH_MS], max bolus never synced from the phone, amount above the phone's max bolus
 *   (key_treatments_safety_max_bolus); a tap only explains why
 * - 800 ms double-tap window, 5 s cooldown after a request
 * - SENT: after a request the button stays dimmed with "…" for [PENDING_MS]; taps then only say so
 * - dedupe: a tap event delivered twice and a second request inside the cooldown/pending time are dropped
 *
 * Pure logic, unit tested. The face only sends a REQUEST (ActionBolusPreCheck); the phone checks it again and the
 * watch shows the standard confirmation.
 */
class OrbitBolusGuard(private val clock: () -> Long) {

    enum class State {

        /** Button not drawn, no hit target. */
        HIDDEN,

        /** Drawn dimmed; a tap shows the reason and never arms. */
        DISABLED,

        /** Drawn and active. */
        IDLE,

        /** A request was sent; dimmed with "…" until [PENDING_MS] passed. */
        SENT
    }

    enum class Reason { NONE, PREF_OFF, AMBIENT, NO_WEAR_CONTROL, AMOUNT_INVALID, FIRST_START, OVER_MAX_BOLUS, NO_MAX_BOLUS, NO_PHONE, STALE_DATA, PENDING }

    data class Check(val state: State, val reason: Reason)

    data class Input(
        val prefOn: Boolean,
        val ambient: Boolean,
        val wearControl: Boolean,
        val amount: Double,
        /** The phone's max bolus; NaN (or <= 0) when it was never synced. */
        val maxBolus: Double,
        /** CapabilityClient FILTER_REACHABLE; null until the first answer (treated as not reachable). */
        val phoneReachable: Boolean?,
        val lastPhoneMessage: Long,
        val bgTimeStamp: Long,
        val firstStart: Boolean = false
    )

    private var lastTapEventTime = Long.MIN_VALUE
    private var lastRequestAt: Long? = null

    fun check(i: Input): Check {
        val now = clock()
        return when {
            !i.prefOn                                                       -> Check(State.HIDDEN, Reason.PREF_OFF)
            i.ambient                                                       -> Check(State.HIDDEN, Reason.AMBIENT)
            !i.wearControl                                                  -> Check(State.HIDDEN, Reason.NO_WEAR_CONTROL)
            ALLOWED_AMOUNTS.none { kotlin.math.abs(it - i.amount) < 1e-9 }  -> Check(State.HIDDEN, Reason.AMOUNT_INVALID)
            i.firstStart                                                    -> Check(State.HIDDEN, Reason.FIRST_START)
            isPending(now)                                                  -> Check(State.SENT, Reason.PENDING)
            i.phoneReachable != true || i.lastPhoneMessage <= 0L || now - i.lastPhoneMessage > DATA_FRESH_MS ->
                Check(State.DISABLED, Reason.NO_PHONE)

            i.bgTimeStamp <= 0L || now - i.bgTimeStamp >= DATA_FRESH_MS     -> Check(State.DISABLED, Reason.STALE_DATA)
            i.maxBolus.isNaN() || i.maxBolus <= 0.0                         -> Check(State.DISABLED, Reason.NO_MAX_BOLUS)
            i.amount > i.maxBolus + 1e-9                                    -> Check(State.DISABLED, Reason.OVER_MAX_BOLUS)
            else                                                            -> Check(State.IDLE, Reason.NONE)
        }
    }

    private fun isPending(now: Long): Boolean {
        val at = lastRequestAt ?: return false
        val d = now - at
        return d in 0 until PENDING_MS
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

    /** Milliseconds until the SENT state ends, or 0. */
    fun pendingLeftMs(): Long {
        val at = lastRequestAt ?: return 0L
        return (PENDING_MS - (clock() - at)).coerceIn(0L, PENDING_MS)
    }

    companion object {

        const val WINDOW_MS = 800L
        const val COOLDOWN_MS = 5_000L
        const val PENDING_MS = 30_000L
        const val DATA_FRESH_MS = 12 * 60_000L

        /** Rim flash after a request is sent (spec §4.12). */
        const val SEND_FLASH_MS = 150L

        /** Hit circle radius around the button centre (spec §4.12). */
        const val HIT_R = 44f
        val ALLOWED_AMOUNTS = doubleArrayOf(0.5, 1.0)
    }
}
