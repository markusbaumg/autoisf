package app.aaps.wear.watchfaces.orbit

import app.aaps.wear.watchfaces.kusi.BolusTapGesture
import app.aaps.wear.watchfaces.kusi.BolusTapGesture.Decision
import app.aaps.wear.watchfaces.orbit.OrbitBolusGuard.Reason
import app.aaps.wear.watchfaces.orbit.OrbitBolusGuard.State
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OrbitBolusGuardTest {

    private var now = 1_000_000_000L
    private val guard = OrbitBolusGuard { now }
    private val gesture = BolusTapGesture({ now }, OrbitBolusGuard.WINDOW_MS, OrbitBolusGuard.COOLDOWN_MS)

    private fun check(
        prefOn: Boolean = true, ambient: Boolean = false, wearControl: Boolean = true,
        amount: Double = 0.5, maxBolus: Double = 3.0, lastPhone: Long = now - 60_000L
    ) = guard.check(prefOn, ambient, wearControl, amount, maxBolus, lastPhone)

    @Test fun defaultsAreHardened() {
        assertThat(OrbitBolusGuard.WINDOW_MS).isEqualTo(800L)
        assertThat(OrbitBolusGuard.COOLDOWN_MS).isEqualTo(5_000L)
    }

    @Test fun visibilityAndBlocking() {
        assertThat(check()).isEqualTo(OrbitBolusGuard.Check(State.READY, Reason.NONE))
        assertThat(check(prefOn = false).state).isEqualTo(State.HIDDEN)
        assertThat(check(ambient = true)).isEqualTo(OrbitBolusGuard.Check(State.HIDDEN, Reason.AMBIENT))
        assertThat(check(wearControl = false).reason).isEqualTo(Reason.NO_WEAR_CONTROL)
        assertThat(check(amount = 1.0, maxBolus = 0.8)).isEqualTo(OrbitBolusGuard.Check(State.BLOCKED, Reason.OVER_MAX_BOLUS))
        assertThat(check(amount = 1.0, maxBolus = 1.0).state).isEqualTo(State.READY)
        assertThat(check(amount = 0.0).reason).isEqualTo(Reason.AMOUNT_INVALID)
        assertThat(check(amount = Double.NaN).reason).isEqualTo(Reason.AMOUNT_INVALID)
        assertThat(check(lastPhone = 0L).reason).isEqualTo(Reason.PHONE_LOST)
        assertThat(check(lastPhone = now - OrbitBolusGuard.PHONE_FRESH_MS).state).isEqualTo(State.READY)
        assertThat(check(lastPhone = now - OrbitBolusGuard.PHONE_FRESH_MS - 1).reason).isEqualTo(Reason.PHONE_LOST)
        // ambient wins over everything else (never drawn, never active)
        assertThat(check(ambient = true, lastPhone = 0L).state).isEqualTo(State.HIDDEN)
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
        // r = 40 around (57, 225)
        assertThat(BolusTapGesture.isInside(57f + 39f, 225f, 57f, 225f, 40f)).isTrue()
        assertThat(BolusTapGesture.isInside(57f + 29f, 225f + 29f, 57f, 225f, 40f)).isFalse()
    }
}
