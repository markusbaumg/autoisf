package app.aaps.wear.interaction.actions

import app.aaps.wear.data.ComplicationData
import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.BolusRequestGuard.Reason
import app.aaps.wear.watchfaces.common.BolusRequestGuard.State
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.LoopRunTime
import app.aaps.wear.watchfaces.common.PhoneReach
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic
import java.util.Locale
import kotlin.math.abs

/**
 * State machine of the quick-bolus picker (QuickBolusActivity), opened by the "+ U" ball (Orbit, Halo, Glass) in "Auswahl" mode.
 * Pure logic with an injected clock, unit tested; the activity only draws [screen] and feeds taps and ticks in.
 *
 * - Nothing is preselected and nothing is remembered: every picker starts in [Phase.CHOOSING].
 * - Every input is ignored for [INPUT_LOCK_MS] after opening (the double tap that opened it, a sleeve). With
 *   [lockFromShown] (the activity) the lock is measured from the moment the picker is really on screen ([onShown]: first
 *   drawn frame, window focus, end of the open animation), not from construction: until then every input is ignored.
 * - [screen] and [onAmountTap] re-run the full [BolusRequestGuard] check (phone reachable with an answer that arrived
 *   after opening and is fresh, data < 12 min, max bolus known, Wear control, nothing pending) for every amount; an
 *   amount above the max bolus is greyed out on its own. A low or a fast fall ([Block]) turns every amount off.
 * - One tap on an enabled amount sends exactly one request: [BolusRequestGuard.sendSingle] stores the send time (the
 *   30 s lock shared with the face ball) and the picker goes to [Phase.WAITING]; every later tap is ignored.
 * - WAITING ends when the confirm screen appears (the activity is paused and finishes) or after [ANSWER_TIMEOUT_MS]
 *   with [Phase.NO_ANSWER] (shown for [NO_ANSWER_SHOW_MS], then closed). Nothing is ever sent again.
 * - CHOOSING closes after [IDLE_MS] without a touch.
 */
class QuickBolusPicker(
    private val clock: () -> Long,
    val guard: BolusRequestGuard,
    /** 0.5 / 1.0 (+ 1.5 with the switch), [app.aaps.wear.watchfaces.common.BolusRequestPrefs.pickerAmounts]. */
    val amounts: List<Double>,
    /**
     * True (QuickBolusActivity): the input lock starts at [onShown], not at construction, and everything is locked until
     * then. A Wear activity launch takes a few hundred ms before its first frame; measured from onCreate, the lock the user
     * sees would shrink to ~200-400 ms and a slow third tap on the ball (right over the 0,5 button) could count.
     */
    private val lockFromShown: Boolean = false
) {

    enum class Phase { CHOOSING, WAITING, NO_ANSWER, CLOSED }

    /** A BG reason that turns every amount off. */
    enum class Block { NONE, LOW, FALLING_FAST }

    /** Why the amounts are off (one line under the buttons), in priority order of [why]. */
    enum class Why { NONE, OFF, NO_WEAR_CONTROL, PENDING, CHECKING_PHONE, NO_PHONE, STALE, NO_MAX_BOLUS, LOW, FALLING_FAST }

    /** What the picker reads at a moment: the guard input (its amount is replaced per button) and the BG block. */
    data class Snapshot(val input: BolusRequestGuard.Input, val block: Block)

    data class Button(val amount: Double, val enabled: Boolean, val overMax: Boolean)

    data class Screen(
        val phase: Phase,
        val buttons: List<Button>,
        val why: Why,
        val sentAmount: Double?,
        val inputLocked: Boolean,
        /** The phone's max bolus, NaN when unknown. */
        val maxBolus: Double
    ) {

        /** The first greyed-out amount above the max bolus (its reason is shown while the others are usable). */
        val overMaxAmount: Double? get() = buttons.firstOrNull { it.overMax }?.amount
    }

    sealed class TapResult {

        /** Within [INPUT_LOCK_MS], or not CHOOSING (a request was already sent): nothing happens. */
        object Ignored : TapResult()

        /** The amount is off: show [why] (or the over-max line); nothing sent. */
        data class Refused(val why: Why, val overMax: Boolean = false) : TapResult()

        /** Send EventData.ActionBolusPreCheck([amount]) now, exactly once. */
        data class Send(val amount: Double) : TapResult()
    }

    enum class Tick { NONE, REDRAW, CLOSE }

    val openedAt: Long = clock()
    var phase: Phase = Phase.CHOOSING
        private set
    var sentAmount: Double? = null
        private set
    var sentAt: Long = 0L
        private set
    private var lastTouch = openedAt
    private var noAnswerAt = 0L

    /** Start of the input lock: [openedAt], or with [lockFromShown] the last [onShown] (null = not on screen yet). */
    var lockStart: Long? = if (lockFromShown) null else openedAt
        private set

    /** True for the first [INPUT_LOCK_MS] (a clock set back keeps it locked until the lock has really passed). */
    fun inputLocked(): Boolean {
        val start = lockStart ?: return true
        val dt = clock() - start
        return dt < INPUT_LOCK_MS
    }

    /**
     * The picker is visible (first frame drawn, window focus gained, open animation complete; called for each). While the
     * input lock still runs (or has not started), it (re)starts now, so it always covers [INPUT_LOCK_MS] after the LAST of
     * these signals that came in time; once the lock is over a late signal changes nothing. Returns true when it restarted.
     */
    fun onShown(): Boolean {
        if (!lockFromShown || phase != Phase.CHOOSING) return false
        if (lockStart != null && !inputLocked()) return false
        val now = clock()
        lockStart = now
        if (now > lastTouch) lastTouch = now
        return true
    }

    /** Re-check every amount (call on open, on every data change and on every tap). */
    fun screen(s: Snapshot): Screen {
        val why = why(s)
        val buttons = amounts.map { a ->
            val c = guard.check(s.input.copy(amount = a, allowedAmounts = amounts))
            val overMax = c.reason == Reason.OVER_MAX_BOLUS
            Button(a, enabled = phase == Phase.CHOOSING && why == Why.NONE && c.state == State.IDLE, overMax = overMax && why == Why.NONE)
        }
        return Screen(phase, buttons, why, sentAmount, inputLocked(), s.input.maxBolus.takeIf { it > 0.0 } ?: Double.NaN)
    }

    /** The reason that turns every amount off, or NONE (an amount above the max bolus is per button). */
    fun why(s: Snapshot): Why {
        val smallest = amounts.minOrNull() ?: return Why.OFF
        val c = guard.check(s.input.copy(amount = smallest, allowedAmounts = amounts))
        return when {
            c.state == State.HIDDEN && c.reason == Reason.NO_WEAR_CONTROL -> Why.NO_WEAR_CONTROL
            c.state == State.HIDDEN                                       -> Why.OFF
            c.reason == Reason.PENDING                                    -> Why.PENDING
            c.reason == Reason.STALE_DATA                                 -> Why.STALE
            s.block == Block.LOW                                          -> Why.LOW
            s.block == Block.FALLING_FAST                                 -> Why.FALLING_FAST
            // no answer at all yet (UNKNOWN): still asking, not "not connected"
            c.reason == Reason.NO_PHONE && guard.phoneReach == PhoneReach.UNKNOWN -> Why.CHECKING_PHONE
            c.reason == Reason.NO_PHONE                                   -> Why.NO_PHONE
            c.reason == Reason.NO_MAX_BOLUS                               -> Why.NO_MAX_BOLUS
            // reachable, but not yet answered since the picker opened (or that answer is no longer fresh)
            !guard.isReachConfirmedSince(openedAt)                        -> Why.CHECKING_PHONE
            else                                                          -> Why.NONE
        }
    }

    /** Any touch (keeps the idle close away while the user is looking). */
    fun onTouch() {
        if (!inputLocked()) lastTouch = clock()
    }

    /** One tap on the button of [amount]. */
    fun onAmountTap(amount: Double, s: Snapshot): TapResult {
        if (inputLocked() || phase != Phase.CHOOSING) return TapResult.Ignored
        lastTouch = clock()
        if (amounts.none { abs(it - amount) < 1e-6 }) return TapResult.Ignored
        val screen = screen(s)
        if (screen.why != Why.NONE) return TapResult.Refused(screen.why)
        val button = screen.buttons.first { abs(it.amount - amount) < 1e-6 }
        if (!button.enabled) return TapResult.Refused(Why.NONE, overMax = button.overMax)
        val reason = guard.sendSingle(s.input.copy(amount = amount, allowedAmounts = amounts), reachSince = openedAt)
        if (reason != Reason.NONE) return TapResult.Refused(
            when (reason) {
                Reason.OVER_MAX_BOLUS -> Why.NONE
                Reason.PENDING        -> Why.PENDING
                Reason.STALE_DATA     -> Why.STALE
                Reason.NO_MAX_BOLUS   -> Why.NO_MAX_BOLUS
                Reason.NO_PHONE       -> Why.NO_PHONE
                else                  -> Why.OFF
            }, overMax = reason == Reason.OVER_MAX_BOLUS
        )
        phase = Phase.WAITING
        sentAmount = amount
        sentAt = clock()
        return TapResult.Send(amount)
    }

    /** The close button, back or a swipe; ignored during the input lock. Returns true when the picker closes. */
    fun onClose(): Boolean {
        if (inputLocked()) return false
        phase = Phase.CLOSED
        return true
    }

    /** Closed from outside (screen off, paused, the confirm screen appeared): never ignored. */
    fun close() {
        phase = Phase.CLOSED
    }

    /** Timers: idle close, the answer timeout and the end of the "no answer" line. */
    fun tick(): Tick {
        val now = clock()
        return when (phase) {
            Phase.CHOOSING  -> if (now - lastTouch >= IDLE_MS || now < openedAt) closed() else Tick.NONE
            Phase.WAITING   -> if (now - sentAt >= ANSWER_TIMEOUT_MS || now < sentAt) {
                phase = Phase.NO_ANSWER
                noAnswerAt = now
                Tick.REDRAW
            } else Tick.NONE

            Phase.NO_ANSWER -> if (now - noAnswerAt >= NO_ANSWER_SHOW_MS || now < noAnswerAt) closed() else Tick.NONE
            Phase.CLOSED    -> Tick.CLOSE
        }
    }

    private fun closed(): Tick {
        close()
        return Tick.CLOSE
    }

    companion object {

        const val INPUT_LOCK_MS = 600L
        const val IDLE_MS = 10_000L
        const val ANSWER_TIMEOUT_MS = 10_000L
        const val NO_ANSWER_SHOW_MS = 3_000L

        /**
         * Low (below the low mark, under 54 mg/dL, the sensor's LOW sentinel) or falling fast (double arrow or the
         * fast delta) on any reading, whatever its age: no quick bolus. Old data is also the guard's STALE.
         */
        fun block(sgv: Double, sgvLevel: Long, slopeArrow: String, deltaMgdl: Double?, ageMinutes: Long?): Block {
            // any real reading counts, whatever its age: the low block never depends on the stale boundary (the guard's
            // STALE has priority in [why] anyway, so this only makes the picker stricter)
            if (ageMinutes == null) return Block.NONE
            val range = OrbitFaceLogic.range(sgv, sgvLevel, slopeArrow, deltaMgdl, FaceState.BgAge.FRESH)
            return when {
                FaceState.isLow(range) || (sgv > 0.0 && sgv <= OrbitFaceLogic.SENTINEL_LOW_MGDL) -> Block.LOW
                OrbitFaceLogic.fastFall(slopeArrow, deltaMgdl)                         -> Block.FALLING_FAST
                else                                                                   -> Block.NONE
            }
        }

        /** The guard input of the picker from the received data (prefOn and the rest as the face reads them). */
        fun snapshot(
            d: ComplicationData, now: Long, prefOn: Boolean, wearControl: Boolean, maxBolus: Double, amounts: List<Double>
        ): Snapshot {
            val bg = d.bgData
            val input = BolusRequestGuard.Input(
                prefOn = prefOn,
                ambient = false,
                wearControl = wearControl,
                amount = amounts.minOrNull() ?: Double.NaN,
                maxBolus = maxBolus,
                lastPhoneMessage = d.lastUpdateTimestamp,
                bgTimeStamp = bg.timeStamp,
                firstStart = FaceState.firstStart(bg.timeStamp, LoopRunTime.of(d.statusData), d.statusData.iobSum),
                allowedAmounts = amounts
            )
            val ageMin = FaceState.ageMinutes(bg.timeStamp, now)
            return Snapshot(input, block(bg.sgv, bg.sgvLevel, bg.slopeArrow, bg.deltaMgdl, ageMin))
        }

        /**
         * The phone's max bolus for the "max X U" line: rounded DOWN to 0.01 U, one decimal when that is exact
         * ("max 1,45 U", "max 2,0 U"), so it never reads higher than the value the guard compares with.
         */
        fun maxBolusText(maxBolus: Double, locale: Locale): String {
            val cents = kotlin.math.floor(maxBolus * 100.0 + 1e-6).toLong()
            return if (cents % 10L == 0L) String.format(locale, "%.1f", cents / 100.0)
            else String.format(locale, "%.2f", cents / 100.0)
        }

        /** "0,5" / "1,0" / "1,5" with the watch locale's decimal separator. */
        fun amountText(amount: Double, locale: Locale): String =
            FaceState.bolusAmountText(amount, locale).removePrefix("+")
    }
}
