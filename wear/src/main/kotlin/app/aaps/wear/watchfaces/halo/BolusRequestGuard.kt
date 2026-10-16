package app.aaps.wear.watchfaces.halo

/**
 * Watch-side checks before the Halo face sends a bolus REQUEST (ActionBolusPreCheck) after a double-tap.
 *
 * The phone still runs every real check and the watch shows the standard confirmation; this guard only stops
 * requests that cannot be right or would be lost:
 * - Wear control off on the phone,
 * - an amount other than 0.5 / 1.0 U, or above the max bolus the phone sent to the watch,
 * - a request already pending (sent less than [pendingMs] ago, its confirmation may still be open),
 * - the phone not reachable (the data layer silently drops messages without a node),
 * - no data from the phone for longer than [freshMs] (connection not really alive).
 *
 * The time of the last sent request lives in [store]; the face backs it with SharedPreferences, so the pending
 * window survives a recreated watch face service (re-selection, process restart, a new engine).
 *
 * Pure logic with an injected clock, unit tested.
 */
class BolusRequestGuard(
    private val clock: () -> Long,
    val pendingMs: Long = PENDING_MS,
    val freshMs: Long = DATA_FRESH_MS,
    private val store: LastSentStore = MemoryStore()
) {

    /** Where the time of the last sent request is kept (0 = never). */
    interface LastSentStore {

        var lastSentAt: Long
    }

    class MemoryStore : LastSentStore {

        override var lastSentAt: Long = 0L
    }

    enum class Verdict { SEND, REFUSED_WEAR_CONTROL, REFUSED_AMOUNT, REFUSED_MAX_BOLUS, REFUSED_PENDING, REFUSED_NO_PHONE, REFUSED_STALE_DATA }

    /**
     * True while a sent request may still be waiting for its confirmation. A stored time in the future
     * (clock set back) also counts while it is within [pendingMs].
     */
    val isPending: Boolean
        get() {
            val sentAt = store.lastSentAt
            return sentAt > 0L && kotlin.math.abs(clock() - sentAt) < pendingMs
        }

    /**
     * @param amount requested insulin (U)
     * @param maxBolus max bolus from the watch preferences (key_treatments_safety_max_bolus, sent by the phone)
     * @param wearControl Wear control enabled on the phone
     * @param phoneReachable a phone node with the AAPS capability is reachable
     * @param lastPhoneDataAt time of the last data received from the phone (ComplicationData.lastUpdateTimestamp), 0 = never
     */
    fun evaluate(amount: Double, maxBolus: Double, wearControl: Boolean, phoneReachable: Boolean, lastPhoneDataAt: Long): Verdict {
        val now = clock()
        return when {
            !wearControl                                                -> Verdict.REFUSED_WEAR_CONTROL
            BolusTapGesture.ALLOWED_AMOUNTS.none { kotlin.math.abs(it - amount) < 1e-6 } -> Verdict.REFUSED_AMOUNT
            maxBolus <= 0.0 || amount > maxBolus + 1e-6                 -> Verdict.REFUSED_MAX_BOLUS
            isPending                                                   -> Verdict.REFUSED_PENDING
            !phoneReachable                                             -> Verdict.REFUSED_NO_PHONE
            lastPhoneDataAt <= 0L || now - lastPhoneDataAt !in 0..freshMs -> Verdict.REFUSED_STALE_DATA
            else                                                        -> Verdict.SEND
        }
    }

    /** Evaluate and, when the verdict is SEND, record the request as pending. */
    fun checkAndRecord(amount: Double, maxBolus: Double, wearControl: Boolean, phoneReachable: Boolean, lastPhoneDataAt: Long): Verdict =
        evaluate(amount, maxBolus, wearControl, phoneReachable, lastPhoneDataAt).also { if (it == Verdict.SEND) store.lastSentAt = clock() }

    companion object {

        /** A second request is refused for this long after a sent one (the confirmation may still be open). */
        const val PENDING_MS = 30_000L

        /** Same limit as the complications' "no sync" (Constants.STALE_MS). */
        const val DATA_FRESH_MS = 12 * 60 * 1000L
    }
}
