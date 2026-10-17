package app.aaps.wear.interaction.actions

import app.aaps.wear.interaction.actions.QuickBolusTouch.Companion.CLOSE
import app.aaps.wear.interaction.actions.QuickBolusTouch.Companion.NONE
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** A tap on the picker fires only for DOWN and UP on the same target, after the input lock, within the slop, one finger. */
class QuickBolusTouchTest {

    private val slop = 8f
    private val b05 = 0
    private val b10 = 1
    private val b15 = 2

    @Test fun cleanTapFires() {
        val t = QuickBolusTouch()
        t.down(b10, 225f, 238f, locked = false)
        t.move(228f, 240f, slop)
        assertThat(t.up(b10, 229f, 241f, slop)).isEqualTo(b10)
        // the gesture is over: a second UP fires nothing
        assertThat(t.up(b10, 229f, 241f, slop)).isEqualTo(NONE)
    }

    @Test fun dragOntoButtonDoesNotFire() {
        val t = QuickBolusTouch()
        // starts on the header, lifts on 1,5
        t.down(NONE, 225f, 120f, locked = false)
        t.move(300f, 200f, slop)
        assertThat(t.up(b15, 355f, 238f, slop)).isEqualTo(NONE)
        // starts between the buttons
        t.down(NONE, 160f, 300f, locked = false)
        assertThat(t.up(b05, 95f, 238f, slop)).isEqualTo(NONE)
    }

    @Test fun downOnOneButtonUpOnAnotherDoesNotFire() {
        val t = QuickBolusTouch()
        t.down(b05, 95f, 238f, locked = false)
        assertThat(t.up(b10, 225f, 238f, slop)).isEqualTo(NONE)
        t.down(b05, 95f, 238f, locked = false)
        assertThat(t.up(b15, 355f, 238f, slop)).isEqualTo(NONE)
    }

    @Test fun movingBeyondSlopDropsTheTapEvenWhenItComesBack() {
        val t = QuickBolusTouch()
        t.down(b10, 225f, 238f, locked = false)
        t.move(225f, 238f + slop + 1f, slop)
        t.move(225f, 238f, slop)
        assertThat(t.up(b10, 225f, 238f, slop)).isEqualTo(NONE)
        // a slide within the same button but beyond the slop, without MOVE events in between
        t.down(b10, 200f, 238f, locked = false)
        assertThat(t.up(b10, 250f, 238f, slop)).isEqualTo(NONE)
    }

    @Test fun pressDuringTheInputLockLiftingAfterItDoesNotFire() {
        val t = QuickBolusTouch()
        t.down(b05, 95f, 238f, locked = true)
        assertThat(t.armed).isFalse()
        assertThat(t.up(b05, 95f, 238f, slop)).isEqualTo(NONE)
    }

    @Test fun secondPointerOrCancelDropsTheGesture() {
        val t = QuickBolusTouch()
        // a palm: a second pointer comes down, the last one lifts over a button
        t.down(b10, 225f, 238f, locked = false)
        t.cancel() // ACTION_POINTER_DOWN
        assertThat(t.up(b10, 225f, 238f, slop)).isEqualTo(NONE)
        t.down(b10, 225f, 238f, locked = false)
        t.cancel() // ACTION_CANCEL
        assertThat(t.up(b10, 225f, 238f, slop)).isEqualTo(NONE)
    }

    @Test fun closeFollowsTheSameRule() {
        val t = QuickBolusTouch()
        t.down(CLOSE, 225f, 384f, locked = false)
        assertThat(t.up(CLOSE, 226f, 385f, slop)).isEqualTo(CLOSE)
        t.down(NONE, 225f, 330f, locked = false)
        assertThat(t.up(CLOSE, 225f, 384f, slop)).isEqualTo(NONE)
        t.down(CLOSE, 225f, 384f, locked = true)
        assertThat(t.up(CLOSE, 225f, 384f, slop)).isEqualTo(NONE)
    }
}
