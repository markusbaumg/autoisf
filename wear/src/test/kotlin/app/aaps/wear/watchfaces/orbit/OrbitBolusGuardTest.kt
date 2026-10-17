package app.aaps.wear.watchfaces.orbit

import app.aaps.wear.watchfaces.halo.BolusTapGesture
import app.aaps.wear.watchfaces.halo.BolusTapGesture.Decision
import app.aaps.wear.watchfaces.orbit.OrbitBolusGuard.Reason
import app.aaps.wear.watchfaces.orbit.OrbitBolusGuard.State
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OrbitBolusGuardTest {

    private var now = 1_000_000_000_000L
    private val guard = OrbitBolusGuard { now }
    private val gesture = BolusTapGesture({ now }, OrbitBolusGuard.WINDOW_MS, OrbitBolusGuard.COOLDOWN_MS)

    private fun check(
        prefOn: Boolean = true, ambient: Boolean = false, wearControl: Boolean = true,
        amount: Double = 0.5, maxBolus: Double = 3.0, reachable: Boolean? = true,
        lastPhone: Long = now - 60_000L, bgTime: Long = now - 120_000L, firstStart: Boolean = false
    ) = guard.check(OrbitBolusGuard.Input(prefOn, ambient, wearControl, amount, maxBolus, reachable, lastPhone, bgTime, firstStart))

    @Test fun defaultsAreHardened() {
        assertThat(OrbitBolusGuard.WINDOW_MS).isEqualTo(800L)
        assertThat(OrbitBolusGuard.COOLDOWN_MS).isEqualTo(5_000L)
        assertThat(OrbitBolusGuard.PENDING_MS).isEqualTo(30_000L)
        assertThat(OrbitBolusGuard.DATA_FRESH_MS).isEqualTo(12 * 60_000L)
    }

    @Test fun hidden() {
        assertThat(check()).isEqualTo(OrbitBolusGuard.Check(State.IDLE, Reason.NONE))
        assertThat(check(prefOn = false)).isEqualTo(OrbitBolusGuard.Check(State.HIDDEN, Reason.PREF_OFF))
        assertThat(check(ambient = true)).isEqualTo(OrbitBolusGuard.Check(State.HIDDEN, Reason.AMBIENT))
        assertThat(check(wearControl = false)).isEqualTo(OrbitBolusGuard.Check(State.HIDDEN, Reason.NO_WEAR_CONTROL))
        assertThat(check(amount = 0.0).reason).isEqualTo(Reason.AMOUNT_INVALID)
        assertThat(check(amount = 0.7).state).isEqualTo(State.HIDDEN)
        assertThat(check(amount = Double.NaN).state).isEqualTo(State.HIDDEN)
        assertThat(check(amount = 1.0).state).isEqualTo(State.IDLE)
        // ambient wins over everything else (never drawn, never active)
        assertThat(check(ambient = true, lastPhone = 0L, bgTime = 0L).state).isEqualTo(State.HIDDEN)
        // first start (nothing ever received): hidden, not disabled
        assertThat(check(firstStart = true, lastPhone = 0L, bgTime = 0L)).isEqualTo(OrbitBolusGuard.Check(State.HIDDEN, Reason.FIRST_START))
    }

    @Test fun disabled() {
        assertThat(check(reachable = false)).isEqualTo(OrbitBolusGuard.Check(State.DISABLED, Reason.NO_PHONE))
        // reachability unknown until the first CapabilityClient answer
        assertThat(check(reachable = null)).isEqualTo(OrbitBolusGuard.Check(State.DISABLED, Reason.NO_PHONE))
        // max bolus never synced from the phone: no 3 U fallback
        assertThat(check(maxBolus = Double.NaN)).isEqualTo(OrbitBolusGuard.Check(State.DISABLED, Reason.NO_MAX_BOLUS))
        assertThat(check(maxBolus = 0.0).reason).isEqualTo(Reason.NO_MAX_BOLUS)
        assertThat(check(lastPhone = 0L).reason).isEqualTo(Reason.NO_PHONE)
        assertThat(check(lastPhone = now - OrbitBolusGuard.DATA_FRESH_MS).state).isEqualTo(State.IDLE)
        assertThat(check(lastPhone = now - OrbitBolusGuard.DATA_FRESH_MS - 1).reason).isEqualTo(Reason.NO_PHONE)
        assertThat(check(bgTime = 0L)).isEqualTo(OrbitBolusGuard.Check(State.DISABLED, Reason.STALE_DATA))
        assertThat(check(bgTime = now - OrbitBolusGuard.DATA_FRESH_MS).reason).isEqualTo(Reason.STALE_DATA)
        assertThat(check(bgTime = now - OrbitBolusGuard.DATA_FRESH_MS + 1).state).isEqualTo(State.IDLE)
        assertThat(check(amount = 1.0, maxBolus = 0.8)).isEqualTo(OrbitBolusGuard.Check(State.DISABLED, Reason.OVER_MAX_BOLUS))
        assertThat(check(amount = 1.0, maxBolus = 1.0).state).isEqualTo(State.IDLE)
    }

    @Test fun sentForPendingTime() {
        guard.recordRequest()
        assertThat(check()).isEqualTo(OrbitBolusGuard.Check(State.SENT, Reason.PENDING))
        // SENT outranks a lost phone, so the user sees that the request went out
        assertThat(check(reachable = false).state).isEqualTo(State.SENT)
        assertThat(guard.pendingLeftMs()).isEqualTo(OrbitBolusGuard.PENDING_MS)
        now += OrbitBolusGuard.PENDING_MS - 1
        assertThat(check().state).isEqualTo(State.SENT)
        now += 1
        assertThat(check().state).isEqualTo(State.IDLE)
        assertThat(guard.pendingLeftMs()).isEqualTo(0L)
        // hidden still wins
        guard.recordRequest()
        assertThat(check(ambient = true).state).isEqualTo(State.HIDDEN)
    }

    @Test fun dedupeSameTapEvent() {
        assertThat(guard.acceptTap(42L)).isTrue()
        assertThat(guard.acceptTap(42L)).isFalse()
        assertThat(guard.acceptTap(43L)).isTrue()
    }

    @Test fun requestCooldownSurvivesGestureReset() {
        assertThat(guard.mayRequest()).isTrue()
        guard.recordRequest()
        now += 4_999L
        assertThat(guard.mayRequest()).isFalse()
        now += 1L
        assertThat(guard.mayRequest()).isTrue()
    }

    @Test fun doubleTapWithinWindowSendsOnce() {
        assertThat(gesture.onTap(insideButton = true, enabled = true)).isEqualTo(Decision.ARM)
        now += 799L
        assertThat(gesture.isArmed).isTrue()
        assertThat(gesture.onTap(insideButton = true, enabled = true)).isEqualTo(Decision.SEND)
        // a burst of further taps inside the cooldown raises nothing
        now += 200L
        assertThat(gesture.onTap(insideButton = true, enabled = true)).isEqualTo(Decision.IGNORE)
        now += 4_000L
        assertThat(gesture.onTap(insideButton = true, enabled = true)).isEqualTo(Decision.IGNORE)
        now += 1_000L
        assertThat(gesture.onTap(insideButton = true, enabled = true)).isEqualTo(Decision.ARM)
    }

    @Test fun slowSecondTapOnlyReArms() {
        assertThat(gesture.onTap(insideButton = true, enabled = true)).isEqualTo(Decision.ARM)
        now += 801L
        assertThat(gesture.isArmed).isFalse()
        assertThat(gesture.onTap(insideButton = true, enabled = true)).isEqualTo(Decision.ARM)
    }

    @Test fun tapOutsideOrDisabledDisarms() {
        gesture.onTap(insideButton = true, enabled = true)
        assertThat(gesture.onTap(insideButton = false, enabled = true)).isEqualTo(Decision.DISARM)
        gesture.onTap(insideButton = true, enabled = true)
        // entering ambient / phone lost: the face passes enabled = false
        assertThat(gesture.onTap(insideButton = true, enabled = false)).isEqualTo(Decision.DISARM)
        assertThat(gesture.onTap(insideButton = true, enabled = false)).isEqualTo(Decision.IGNORE)
    }

    @Test fun hitArea() {
        // r = 44 around (57, 225), at least 8 px from the graph zone (x 143)
        assertThat(OrbitBolusGuard.HIT_R).isEqualTo(44f)
        assertThat(BolusTapGesture.isInside(57f + 43f, 225f, 57f, 225f, OrbitBolusGuard.HIT_R)).isTrue()
        assertThat(BolusTapGesture.isInside(57f + 32f, 225f + 32f, 57f, 225f, OrbitBolusGuard.HIT_R)).isFalse()
        assertThat(57f + OrbitBolusGuard.HIT_R + 8f).isAtMost(143f)
        assertThat(OrbitBolusGuard.SEND_FLASH_MS).isEqualTo(150L)
    }
}
