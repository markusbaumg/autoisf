package app.aaps.wear.interaction.actions

import kotlin.math.hypot

/**
 * One tap on the quick-bolus picker, decided from the whole gesture (pure logic, unit tested). A target (an amount
 * button or ×) fires only when ALL of these hold:
 * - ACTION_DOWN landed on it after the picker's input lock had ended (a press that starts during the lock is dropped,
 *   even if it lifts later),
 * - ACTION_UP lands on the same target,
 * - the finger never moved more than the touch slop from where it went down,
 * - no second pointer touched the screen (a palm) and the gesture was not cancelled.
 * A drag, a sleeve or a palm that slides onto a button therefore never sends.
 *
 * Coordinates are in one consistent unit (the view passes design px and the slop converted to design px).
 */
class QuickBolusTouch {

    private var target = NONE
    private var downX = 0f
    private var downY = 0f

    /** ACTION_DOWN at ([x], [y]) on [hit] ([NONE], [CLOSE] or an amount index); [locked]: the input lock still runs. */
    fun down(hit: Int, x: Float, y: Float, locked: Boolean) {
        target = if (locked) NONE else hit
        downX = x
        downY = y
    }

    /** ACTION_MOVE: leaving the slop circle drops the gesture for good. */
    fun move(x: Float, y: Float, slop: Float) {
        if (target != NONE && hypot(x - downX, y - downY) > slop) target = NONE
    }

    /** ACTION_POINTER_DOWN (a second finger, a palm) or ACTION_CANCEL: the gesture is dropped. */
    fun cancel() {
        target = NONE
    }

    /** ACTION_UP at ([x], [y]) on [hit]: the target that was tapped, or [NONE]. Always ends the gesture. */
    fun up(hit: Int, x: Float, y: Float, slop: Float): Int {
        val t = target
        target = NONE
        if (t == NONE || hit != t) return NONE
        if (hypot(x - downX, y - downY) > slop) return NONE
        return t
    }

    /** True while a DOWN is still a candidate for a tap (tests). */
    val armed: Boolean get() = target != NONE

    companion object {

        const val NONE = -1
        const val CLOSE = -2
    }
}
