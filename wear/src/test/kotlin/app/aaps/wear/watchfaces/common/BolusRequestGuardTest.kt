package app.aaps.wear.watchfaces.common

import app.aaps.wear.watchfaces.common.BolusRequestGuard.Check
import app.aaps.wear.watchfaces.common.BolusRequestGuard.Reason
import app.aaps.wear.watchfaces.common.BolusRequestGuard.State
import app.aaps.wear.watchfaces.common.BolusRequestGuard.TapResult
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The shared rule set of the Halo, Glass and Orbit bolus request button. */
class BolusRequestGuardTest {

    private var now = 1_000_000_000_000L
    private var event = 1L
    private lateinit var store: BolusRequestGuard.MemoryStore
    private lateinit var guard: BolusRequestGuard

    @BeforeEach fun setUp() {
        now = 1_000_000_000_000L
        store = BolusRequestGuard.MemoryStore()
        guard = BolusRequestGuard(clock = { now }, store = store)
        guard.onReachAnswer(true)
    }

    private fun input(
        prefOn: Boolean = true, ambient: Boolean = false, wearControl: Boolean = true,
        amount: Double = 0.5, maxBolus: Double = 3.0, lastPhone: Long = now - 60_000L, bgTime: Long = now - 120_000L,
        headless: Boolean = false, firstStart: Boolean = false
    ) = BolusRequestGuard.Input(prefOn, ambient, wearControl, amount, maxBolus, lastPhone, bgTime, headless, firstStart)

    private fun check(i: BolusRequestGuard.Input = input()) = guard.check(i)
    private fun tap(inside: Boolean = true, i: BolusRequestGuard.Input = input()) = guard.onTap(event++, inside, i)

    /** ARM, a fresh "reachable" answer (the face asks on ARM), second tap inside the window. */
    private fun doubleTap(i: BolusRequestGuard.Input = input()): BolusRequestGuard.TapOutcome {
        assertThat(tap(i = i).result).isEqualTo(TapResult.ARM)
        now += 200
        guard.onReachAnswer(true)
        now += 200
        return tap(i = i)
    }

    @Test fun hardenedConstants() {
        assertThat(BolusTapGesture.WINDOW_MS).isEqualTo(800L)
        assertThat(BolusTapGesture.COOLDOWN_MS).isEqualTo(5_000L)
        assertThat(BolusRequestGuard.PENDING_MS).isEqualTo(30_000L)
        assertThat(BolusRequestGuard.DATA_FRESH_MS).isEqualTo(12 * 60_000L)
        assertThat(guard.gesture.windowMs).isEqualTo(800L)
        assertThat(guard.gesture.cooldownMs).isEqualTo(5_000L)
    }

    @Test fun hidden() {
        assertThat(check()).isEqualTo(Check(State.IDLE, Reason.NONE))
        assertThat(check(input(prefOn = false))).isEqualTo(Check(State.HIDDEN, Reason.PREF_OFF))
        assertThat(check(input(headless = true))).isEqualTo(Check(State.HIDDEN, Reason.HEADLESS))
        assertThat(check(input(ambient = true))).isEqualTo(Check(State.HIDDEN, Reason.AMBIENT))
        assertThat(check(input(wearControl = false))).isEqualTo(Check(State.HIDDEN, Reason.NO_WEAR_CONTROL))
        assertThat(check(input(amount = 0.0)).reason).isEqualTo(Reason.AMOUNT_INVALID)
        assertThat(check(input(amount = 0.7)).state).isEqualTo(State.HIDDEN)
        assertThat(check(input(amount = 2.0, maxBolus = 10.0)).state).isEqualTo(State.HIDDEN)
        assertThat(check(input(amount = Double.NaN)).state).isEqualTo(State.HIDDEN)
        assertThat(check(input(amount = 1.0)).state).isEqualTo(State.IDLE)
        // ambient wins over everything else (never drawn, never active)
        assertThat(check(input(ambient = true, lastPhone = 0L, bgTime = 0L)).state).isEqualTo(State.HIDDEN)
        assertThat(check(input(firstStart = true, lastPhone = 0L, bgTime = 0L))).isEqualTo(Check(State.HIDDEN, Reason.FIRST_START))
    }

    @Test fun phoneReachabilityIsTriState() {
        val fresh = BolusRequestGuard(clock = { now })
        assertThat(fresh.phoneReach).isEqualTo(PhoneReach.UNKNOWN)
        // unknown until the first CapabilityClient answer: no bolus
        assertThat(fresh.check(input())).isEqualTo(Check(State.DISABLED, Reason.NO_PHONE))
        assertThat(fresh.onReachAnswer(false)).isTrue()
        assertThat(fresh.phoneReach).isEqualTo(PhoneReach.UNREACHABLE)
        assertThat(fresh.check(input())).isEqualTo(Check(State.DISABLED, Reason.NO_PHONE))
        assertThat(fresh.onReachAnswer(true)).isTrue()
        assertThat(fresh.onReachAnswer(true)).isFalse()
        assertThat(fresh.check(input()).state).isEqualTo(State.IDLE)
    }

    @Test fun disabled() {
        // max bolus never synced from the phone: refuse, no 3 U fallback
        assertThat(check(input(maxBolus = Double.NaN))).isEqualTo(Check(State.DISABLED, Reason.NO_MAX_BOLUS))
        assertThat(check(input(maxBolus = 0.0)).reason).isEqualTo(Reason.NO_MAX_BOLUS)
        assertThat(check(input(amount = 1.0, maxBolus = 0.8))).isEqualTo(Check(State.DISABLED, Reason.OVER_MAX_BOLUS))
        assertThat(check(input(amount = 1.0, maxBolus = 1.0)).state).isEqualTo(State.IDLE)
        // stale / missing data from the phone
        assertThat(check(input(lastPhone = 0L))).isEqualTo(Check(State.DISABLED, Reason.STALE_DATA))
        assertThat(check(input(lastPhone = now - BolusRequestGuard.DATA_FRESH_MS)).state).isEqualTo(State.IDLE)
        assertThat(check(input(lastPhone = now - BolusRequestGuard.DATA_FRESH_MS - 1)).reason).isEqualTo(Reason.STALE_DATA)
        assertThat(check(input(bgTime = 0L))).isEqualTo(Check(State.DISABLED, Reason.STALE_DATA))
        assertThat(check(input(bgTime = now - BolusRequestGuard.DATA_FRESH_MS - 1)).reason).isEqualTo(Reason.STALE_DATA)
        assertThat(check(input(bgTime = now - BolusRequestGuard.DATA_FRESH_MS)).state).isEqualTo(State.IDLE)
        // a little phone clock drift is fine, a time far in the future is not
        assertThat(check(input(bgTime = now + 30_000L)).state).isEqualTo(State.IDLE)
        assertThat(check(input(bgTime = now + BolusRequestGuard.FUTURE_TOLERANCE_MS + 1)).reason).isEqualTo(Reason.STALE_DATA)
    }

    @Test fun doubleTapSendsOnceAndRecords() {
        assertThat(doubleTap()).isEqualTo(BolusRequestGuard.TapOutcome(TapResult.SEND, onButton = true))
        assertThat(store.lastSentAt).isEqualTo(now)
        assertThat(guard.isPending).isTrue()
        assertThat(check()).isEqualTo(Check(State.SENT, Reason.PENDING))
        // SENT outranks a lost phone, so the user sees that the request went out
        guard.onReachAnswer(false)
        assertThat(check().state).isEqualTo(State.SENT)
    }

    @Test fun singleTapNeverSends() {
        assertThat(tap().result).isEqualTo(TapResult.ARM)
        guard.onReachAnswer(true)
        now += 801
        assertThat(guard.isArmed).isFalse()
        assertThat(tap().result).isEqualTo(TapResult.ARM) // too slow: re-arms only
        assertThat(store.lastSentAt).isEqualTo(0L)
    }

    @Test fun sendNeedsAReachableAnswerAfterTheFirstTap() {
        assertThat(tap().result).isEqualTo(TapResult.ARM)
        now += 300
        // no answer since the first tap (only the one from setUp): refused, nothing recorded
        assertThat(tap()).isEqualTo(BolusRequestGuard.TapOutcome(TapResult.REFUSED, Reason.NO_PHONE, onButton = true))
        assertThat(store.lastSentAt).isEqualTo(0L)
        // an "unreachable" answer while armed disarms
        now += BolusTapGesture.COOLDOWN_MS
        assertThat(tap().result).isEqualTo(TapResult.ARM)
        guard.onReachAnswer(false)
        assertThat(guard.isArmed).isFalse()
        assertThat(tap().result).isEqualTo(TapResult.REFUSED)
    }

    @Test fun tapOnDisabledOrSentButtonOnlyExplains() {
        assertThat(tap(i = input(maxBolus = Double.NaN))).isEqualTo(BolusRequestGuard.TapOutcome(TapResult.REFUSED, Reason.NO_MAX_BOLUS, onButton = true))
        assertThat(guard.isArmed).isFalse()
        doubleTap()
        now += 6_000
        assertThat(tap()).isEqualTo(BolusRequestGuard.TapOutcome(TapResult.REFUSED, Reason.PENDING, onButton = true))
    }

    @Test fun conditionChangingWhileArmedNeverSends() {
        assertThat(tap().result).isEqualTo(TapResult.ARM)
        guard.onReachAnswer(true)
        now += 300
        assertThat(tap(i = input(amount = 1.0, maxBolus = 0.5)).result).isEqualTo(TapResult.REFUSED)
        assertThat(store.lastSentAt).isEqualTo(0L)
        // ambient: hidden, the tap is not a button tap at all
        assertThat(tap().result).isEqualTo(TapResult.ARM)
        assertThat(tap(i = input(ambient = true))).isEqualTo(BolusRequestGuard.TapOutcome(TapResult.DISARM, onButton = false))
    }

    @Test fun hiddenButtonIsNoTapTarget() {
        assertThat(tap(i = input(prefOn = false))).isEqualTo(BolusRequestGuard.TapOutcome(TapResult.IGNORE, onButton = false))
        assertThat(tap(inside = false)).isEqualTo(BolusRequestGuard.TapOutcome(TapResult.IGNORE, onButton = false))
    }

    @Test fun tapElsewhereDisarms() {
        assertThat(tap().result).isEqualTo(TapResult.ARM)
        assertThat(tap(inside = false)).isEqualTo(BolusRequestGuard.TapOutcome(TapResult.DISARM, onButton = false))
        assertThat(tap().result).isEqualTo(TapResult.ARM)
        assertThat(guard.reset()).isTrue() // entering ambient
        assertThat(guard.isArmed).isFalse()
    }

    @Test fun duplicateTapEventIsDropped() {
        assertThat(guard.onTap(42L, true, input()).result).isEqualTo(TapResult.ARM)
        now += 100
        guard.onReachAnswer(true)
        assertThat(guard.onTap(42L, true, input()).result).isEqualTo(TapResult.DUPLICATE)
        assertThat(guard.onTap(43L, true, input()).result).isEqualTo(TapResult.SEND)
    }

    @Test fun pendingWindowAndCooldown() {
        doubleTap()
        assertThat(guard.pendingLeftMs()).isEqualTo(BolusRequestGuard.PENDING_MS)
        // a burst of taps inside the cooldown/pending time raises nothing
        now += 200
        assertThat(tap().result).isEqualTo(TapResult.REFUSED)
        now += BolusRequestGuard.PENDING_MS - 201
        assertThat(check().state).isEqualTo(State.SENT)
        now += 1
        assertThat(guard.isPending).isFalse()
        assertThat(guard.pendingLeftMs()).isEqualTo(0L)
        assertThat(doubleTap().result).isEqualTo(TapResult.SEND)
    }

    @Test fun pendingWindowSurvivesANewGuardWithTheSameStore() {
        doubleTap()
        now += 5_000
        // e.g. the watch face service was recreated, or another face was selected, while the confirmation is open
        val second = BolusRequestGuard(clock = { now }, store = store)
        second.onReachAnswer(true)
        assertThat(second.check(input())).isEqualTo(Check(State.SENT, Reason.PENDING))
        now += BolusRequestGuard.PENDING_MS
        assertThat(second.check(input()).state).isEqualTo(State.IDLE)
    }

    @Test fun clockSetBackKeepsThePendingWindow() {
        doubleTap()
        now -= 10_000
        assertThat(check().state).isEqualTo(State.SENT)
        now -= BolusRequestGuard.PENDING_MS
        assertThat(check().state).isEqualTo(State.IDLE)
    }

    @Test fun allowedAmounts() {
        assertThat(BolusRequestGuard.isAllowedAmount(0.5)).isTrue()
        assertThat(BolusRequestGuard.isAllowedAmount(1.0)).isTrue()
        assertThat(BolusRequestGuard.isAllowedAmount(1.5)).isFalse()
        assertThat(BolusRequestGuard.isAllowedAmount(Double.NaN)).isFalse()
    }
}
