package app.aaps.wear.watchfaces.halo

import app.aaps.wear.watchfaces.halo.BolusTapGesture.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BolusTapGestureTest {

    private var now = 1_000_000L
    private lateinit var gesture: BolusTapGesture

    @BeforeEach fun setUp() {
        now = 1_000_000L
        gesture = BolusTapGesture(clock = { now })
    }

    @Test fun singleTapNeverSends() {
        assertThat(gesture.onTap(insideButton = true, enabled = true)).isEqualTo(Decision.ARM)
        assertThat(gesture.isArmed).isTrue()
        now += 5_000
        assertThat(gesture.isArmed).isFalse()
        assertThat(gesture.expireIfDue()).isTrue()
        assertThat(gesture.expireIfDue()).isFalse()
    }

    @Test fun doubleTapInsideWindowSends() {
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.ARM)
        now += 400
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.SEND)
        assertThat(gesture.isArmed).isFalse()
    }

    @Test fun secondTapAtWindowEdgeSends() {
        gesture.onTap(true, true)
        now += BolusTapGesture.WINDOW_MS
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.SEND)
    }

    @Test fun secondTapAfterWindowArmsAgain() {
        gesture.onTap(true, true)
        now += BolusTapGesture.WINDOW_MS + 1
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.ARM)
        now += 100
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.SEND)
    }

    @Test fun tapOutsideDisarms() {
        gesture.onTap(true, true)
        now += 200
        assertThat(gesture.onTap(false, true)).isEqualTo(Decision.DISARM)
        now += 200
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.ARM)
    }

    @Test fun tapOutsideWhileIdleIsIgnored() {
        assertThat(gesture.onTap(false, true)).isEqualTo(Decision.IGNORE)
        assertThat(gesture.isArmed).isFalse()
    }

    @Test fun disabledAlwaysIgnores() {
        assertThat(gesture.onTap(true, false)).isEqualTo(Decision.IGNORE)
        now += 100
        assertThat(gesture.onTap(true, false)).isEqualTo(Decision.IGNORE)
        assertThat(gesture.isArmed).isFalse()
    }

    @Test fun disablingWhileArmedDisarmsAndDoesNotSend() {
        gesture.onTap(true, true)
        now += 100
        assertThat(gesture.onTap(true, false)).isEqualTo(Decision.DISARM)
        now += 100
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.ARM)
    }

    @Test fun resetForAmbientReturnsToIdle() {
        gesture.onTap(true, true)
        assertThat(gesture.reset()).isTrue()
        assertThat(gesture.reset()).isFalse()
        now += 100
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.ARM)
    }

    @Test fun burstOfTapsSendsOnlyOnce() {
        gesture.onTap(true, true)
        now += 150
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.SEND)
        now += 150
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.IGNORE)
        now += 150
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.IGNORE)
        now += BolusTapGesture.COOLDOWN_MS
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.ARM)
    }

    @Test fun hitCircle() {
        assertThat(BolusTapGesture.isInside(50f, 225f, 50f, 225f, 38f)).isTrue()
        assertThat(BolusTapGesture.isInside(50f + 38f, 225f, 50f, 225f, 38f)).isTrue()
        assertThat(BolusTapGesture.isInside(50f + 39f, 225f, 50f, 225f, 38f)).isFalse()
        assertThat(BolusTapGesture.isInside(225f, 225f, 50f, 225f, 38f)).isFalse()
    }

    @Test fun amountPreference() {
        assertThat(BolusTapGesture.parseAmount(null)).isEqualTo(0.5)
        assertThat(BolusTapGesture.parseAmount("0.5")).isEqualTo(0.5)
        assertThat(BolusTapGesture.parseAmount("1.0")).isEqualTo(1.0)
        assertThat(BolusTapGesture.parseAmount("1,0")).isEqualTo(1.0)
        assertThat(BolusTapGesture.parseAmount("5")).isEqualTo(0.5)
        assertThat(BolusTapGesture.parseAmount("abc")).isEqualTo(0.5)
    }

    @Test fun windowIs800msAndCooldown5s() {
        assertThat(BolusTapGesture.WINDOW_MS).isEqualTo(800L)
        assertThat(BolusTapGesture.COOLDOWN_MS).isEqualTo(5_000L)
    }

    @Test fun secondTapAt800msSendsAt801msArmsAgain() {
        gesture.onTap(true, true)
        now += 800
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.SEND)

        now += 10_000
        gesture.onTap(true, true)
        now += 801
        assertThat(gesture.isArmed).isFalse()
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.ARM)
    }

    @Test fun cooldownBoundaryAt5s() {
        gesture.onTap(true, true)
        now += 100
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.SEND)
        now += 4_999
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.IGNORE)
        now += 1
        assertThat(gesture.onTap(true, true)).isEqualTo(Decision.ARM)
    }

    @Test fun onlyHalfAndOneUnitAreAllowed() {
        assertThat(BolusTapGesture.ALLOWED_AMOUNTS).containsExactly(0.5, 1.0)
        assertThat(BolusTapGesture.parseAmount("2.0")).isEqualTo(0.5)
        assertThat(BolusTapGesture.parseAmount("0.1")).isEqualTo(0.5)
    }
}
