package app.aaps.wear.watchfaces.common

import kotlin.math.abs

/** Answer of the last CapabilityClient FILTER_REACHABLE lookup. UNKNOWN until the first answer. */
enum class PhoneReach { UNKNOWN, REACHABLE, UNREACHABLE }

/**
 * The one decision logic behind the double-tap bolus REQUEST button of the Halo, Glass and Orbit watch faces.
 * Each face only draws the button and feeds its taps in; every rule lives here.
 *
 * The faces only send a REQUEST (ActionBolusPreCheck), exactly like BolusActivity: the phone checks it again and
 * the watch shows the standard confirmation. This guard stops requests that cannot be right or would be lost:
 *
 * HIDDEN (not drawn, no hit target): preference off (default OFF, a watch-only key that a CustomWatchface zip cannot
 *   set, see [BolusRequestPrefs]), headless preview, ambient (any variant), Wear control off on the phone, an amount
 *   other than 0.5 / 1.0 U (the quick-bolus picker: 1.5 too when its switch is on; never above 1.5 U), first start
 *   (nothing ever received from the phone).
 * SENT: a request was sent less than [PENDING_MS] ago (its confirmation may still be open). The time is kept in
 *   [store] (SharedPreferences in the faces), so it survives a recreated service and a switch to another face.
 * DISABLED (a tap only explains why): phone not reachable or reachability not yet known ([PhoneReach] tri-state),
 *   no data from the phone for longer than [DATA_FRESH_MS], BG older than [DATA_FRESH_MS], max bolus never synced
 *   (key_treatments_safety_max_bolus unknown: refuse, no fallback), amount above the max bolus.
 * IDLE: may arm.
 *
 * Gesture ([gesture], [BolusTapGesture]): 800 ms double-tap window, 5 s cooldown. SEND additionally needs a
 * "reachable" answer that arrived after the first tap ([onReachAnswer], the face asks again when the button arms)
 * and no older than [REACH_ANSWER_FRESH_MS]. The same tap event delivered twice is dropped (dedupe).
 *
 * Pure logic with an injected clock, unit tested.
 */
class BolusRequestGuard(
    private val clock: () -> Long,
    private val store: LastSentStore = MemoryStore()
) {

    /** Where the time of the last sent request is kept (0 = never). */
    interface LastSentStore {

        var lastSentAt: Long
    }

    class MemoryStore : LastSentStore {

        override var lastSentAt: Long = 0L
    }

    enum class State {

        /** Button not drawn, no hit target. */
        HIDDEN,

        /** Drawn dimmed; a tap shows the reason and never arms. */
        DISABLED,

        /** Drawn and active. */
        IDLE,

        /** A request was sent; dimmed until [PENDING_MS] passed, a tap only says so. */
        SENT
    }

    enum class Reason {
        NONE, PREF_OFF, HEADLESS, AMBIENT, NO_WEAR_CONTROL, AMOUNT_INVALID, FIRST_START,
        PENDING, NO_PHONE, STALE_DATA, NO_MAX_BOLUS, OVER_MAX_BOLUS
    }

    data class Check(val state: State, val reason: Reason)

    data class Input(
        val prefOn: Boolean,
        val ambient: Boolean,
        val wearControl: Boolean,
        /** Requested insulin (U); NaN when the stored amount is not one of [BolusTapGesture.ALLOWED_AMOUNTS]. */
        val amount: Double,
        /** The phone's max bolus; NaN (or <= 0) when it was never synced. */
        val maxBolus: Double,
        /** Time the watch last received data from the phone (ComplicationData.lastUpdateTimestamp, watch clock), 0 = never. */
        val lastPhoneMessage: Long,
        /** Time stamp of the latest BG, 0 = none. */
        val bgTimeStamp: Long,
        val headless: Boolean = false,
        val firstStart: Boolean = false,
        /**
         * The amounts that may be requested: [BolusTapGesture.ALLOWED_AMOUNTS] for the fixed ball; the quick-bolus
         * picker adds 1.5 only when its switch is on ([BolusRequestPrefs.pickerAmounts]). Never above
         * [BolusTapGesture.HARD_MAX_AMOUNT], whatever the list says.
         */
        val allowedAmounts: List<Double> = BolusTapGesture.ALLOWED_AMOUNTS
    )

    enum class TapResult {

        /** Not a bolus tap: the face may handle it as its own tap (menu, graph ...). */
        IGNORE,

        /** The same tap event was delivered again: drop it completely. */
        DUPLICATE,

        /** Tap on a DISABLED / SENT button, or SEND refused at the last moment: show [TapOutcome.reason]. */
        REFUSED,

        /** First tap accepted: vibrate, redraw armed, ask for a fresh reachability answer. */
        ARM,

        /** Second tap in the window and every rule passed: send ActionBolusPreCheck(amount) now. */
        SEND,

        /** Armed state cancelled (tap elsewhere, button no longer active). */
        DISARM
    }

    /** [onButton]: the tap landed on a shown button, the face must not use it for anything else. */
    data class TapOutcome(val result: TapResult, val reason: Reason = Reason.NONE, val onButton: Boolean = false)

    val gesture = BolusTapGesture(clock, BolusTapGesture.WINDOW_MS, BolusTapGesture.COOLDOWN_MS)

    var phoneReach: PhoneReach = PhoneReach.UNKNOWN
        private set
    var reachAnsweredAt: Long = 0L
        private set

    /** Time of the tap that armed the button (for countdown drawing and the fresh-reachability rule). */
    var armedAt: Long = 0L
        private set

    private var lastTapEventTime = Long.MIN_VALUE

    /** Store a CapabilityClient answer (a failed lookup counts as not reachable). Returns true when it changed. */
    fun onReachAnswer(reachable: Boolean): Boolean {
        val reach = if (reachable) PhoneReach.REACHABLE else PhoneReach.UNREACHABLE
        reachAnsweredAt = clock()
        if (reach == phoneReach) return false
        phoneReach = reach
        if (reach != PhoneReach.REACHABLE) gesture.reset()
        return true
    }

    /**
     * True while a sent request may still be waiting for its confirmation. A stored time in the future
     * (clock set back) also counts while it is within [PENDING_MS].
     */
    val isPending: Boolean
        get() {
            val sentAt = store.lastSentAt
            return sentAt > 0L && abs(clock() - sentAt) < PENDING_MS
        }

    /** Milliseconds until the SENT state ends, or 0. */
    fun pendingLeftMs(): Long {
        if (!isPending) return 0L
        return (PENDING_MS - abs(clock() - store.lastSentAt)).coerceIn(0L, PENDING_MS)
    }

    fun check(i: Input): Check {
        val now = clock()
        return when {
            !i.prefOn                                                           -> Check(State.HIDDEN, Reason.PREF_OFF)
            i.headless                                                          -> Check(State.HIDDEN, Reason.HEADLESS)
            i.ambient                                                           -> Check(State.HIDDEN, Reason.AMBIENT)
            !i.wearControl                                                      -> Check(State.HIDDEN, Reason.NO_WEAR_CONTROL)
            !isAllowedAmount(i.amount, i.allowedAmounts)                        -> Check(State.HIDDEN, Reason.AMOUNT_INVALID)
            i.firstStart                                                        -> Check(State.HIDDEN, Reason.FIRST_START)
            isPending                                                           -> Check(State.SENT, Reason.PENDING)
            phoneReach != PhoneReach.REACHABLE                                  -> Check(State.DISABLED, Reason.NO_PHONE)
            !isFresh(i.lastPhoneMessage, now)                                   -> Check(State.DISABLED, Reason.STALE_DATA)
            !isFresh(i.bgTimeStamp, now)                                        -> Check(State.DISABLED, Reason.STALE_DATA)
            i.maxBolus.isNaN() || i.maxBolus <= 0.0                             -> Check(State.DISABLED, Reason.NO_MAX_BOLUS)
            i.amount > i.maxBolus + EPS                                         -> Check(State.DISABLED, Reason.OVER_MAX_BOLUS)
            else                                                                -> Check(State.IDLE, Reason.NONE)
        }
    }

    /**
     * Handle one completed tap (tap-up). [insideButton]: the tap lies in the face's hit area of the button
     * (ignored while the button is HIDDEN). The verdict is computed fresh from [input] at the moment of the tap.
     */
    fun onTap(eventTime: Long, insideButton: Boolean, input: Input, commitSend: Boolean = true): TapOutcome {
        if (eventTime == lastTapEventTime) return TapOutcome(TapResult.DUPLICATE)
        lastTapEventTime = eventTime

        val c = check(input)
        val inside = insideButton && c.state != State.HIDDEN
        if (inside && c.state != State.IDLE) {
            gesture.reset()
            return TapOutcome(TapResult.REFUSED, c.reason, onButton = true)
        }
        val now = clock()
        return when (gesture.onTap(inside, c.state == State.IDLE)) {
            BolusTapGesture.Decision.ARM    -> {
                armedAt = now
                TapOutcome(TapResult.ARM, onButton = true)
            }

            BolusTapGesture.Decision.SEND   ->
                if (!reachConfirmedSince(armedAt, now)) TapOutcome(TapResult.REFUSED, Reason.NO_PHONE, onButton = true)
                else {
                    // [commitSend] false (picker mode): the double tap only OPENS the quick-bolus picker, nothing is
                    // requested yet, so the 30 s pending lock does not start
                    if (commitSend) store.lastSentAt = now
                    TapOutcome(TapResult.SEND, onButton = true)
                }

            BolusTapGesture.Decision.DISARM -> TapOutcome(TapResult.DISARM, onButton = inside)
            BolusTapGesture.Decision.IGNORE -> TapOutcome(TapResult.IGNORE, onButton = inside)
        }
    }

    /**
     * Quick-bolus picker: one tap on an amount = one request. Re-runs [check] for [input] and needs a "reachable"
     * answer that arrived after [reachSince] (the picker asks when it opens) and is at most [REACH_ANSWER_FRESH_MS]
     * old. On success the send time is stored (the 30 s pending lock shared with the face ball) and [Reason.NONE] is
     * returned: the caller must send ActionBolusPreCheck(input.amount) now. Otherwise the refusal reason.
     */
    fun sendSingle(input: Input, reachSince: Long): Reason {
        val c = check(input)
        if (c.state != State.IDLE) return if (c.reason == Reason.NONE) Reason.PREF_OFF else c.reason
        val now = clock()
        if (!reachConfirmedSince(reachSince, now)) return Reason.NO_PHONE
        store.lastSentAt = now
        return Reason.NONE
    }

    /** True when "reachable" was answered after [since] and that answer is still fresh (see [sendSingle]). */
    fun isReachConfirmedSince(since: Long): Boolean = reachConfirmedSince(since, clock())

    /** Back to IDLE (ambient, preference off, face hidden). Returns true when it was armed. */
    fun reset(): Boolean = gesture.reset()

    val isArmed: Boolean get() = gesture.isArmed

    /**
     * "Reachable" answered strictly after [since] (the face asks again on ARM; an answer in the same millisecond
     * belongs to an older query) and not older than [REACH_ANSWER_FRESH_MS].
     */
    private fun reachConfirmedSince(since: Long, now: Long): Boolean =
        phoneReach == PhoneReach.REACHABLE && reachAnsweredAt > since &&
            now - reachAnsweredAt in 0..REACH_ANSWER_FRESH_MS

    private fun isFresh(timeStamp: Long, now: Long): Boolean =
        timeStamp > 0L && now - timeStamp <= DATA_FRESH_MS && timeStamp - now <= FUTURE_TOLERANCE_MS

    companion object {

        /** A second request is refused for this long after a sent one (the confirmation may still be open). */
        const val PENDING_MS = 30_000L

        /** Same limit as the complications' "no sync" (Constants.STALE_MS). */
        const val DATA_FRESH_MS = 12 * 60 * 1000L

        /** SEND needs a "reachable" answer at most this old (and newer than the first tap). */
        const val REACH_ANSWER_FRESH_MS = 10_000L

        /** A time stamp slightly ahead of the watch clock (phone clock drift) still counts as fresh. */
        const val FUTURE_TOLERANCE_MS = 60_000L

        private const val EPS = 1e-6

        /** One of [allowed] and never above the hard [BolusTapGesture.HARD_MAX_AMOUNT] ceiling. */
        fun isAllowedAmount(amount: Double, allowed: List<Double> = BolusTapGesture.ALLOWED_AMOUNTS): Boolean =
            !amount.isNaN() && amount > 0.0 && amount <= BolusTapGesture.HARD_MAX_AMOUNT + EPS && allowed.any { abs(it - amount) < EPS }
    }
}
