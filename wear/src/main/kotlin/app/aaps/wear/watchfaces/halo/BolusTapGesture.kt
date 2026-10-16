package app.aaps.wear.watchfaces.halo

/**
 * Double-tap gesture of the "+X U" bolus request ball on the Halo watch face.
 *
 * Pure logic (no Android types) so it can be unit tested with an injected clock.
 *
 * - IDLE: a tap inside the button arms it ([Decision.ARM]); the face vibrates once and redraws.
 * - ARMED: a second tap inside the button within [windowMs] returns [Decision.SEND]; the face then
 *   only sends a bolus REQUEST (ActionBolusPreCheck). The phone runs every check and the watch shows
 *   the standard confirmation screen; nothing is delivered without confirming there.
 * - A tap outside the button, an expired window, ambient mode or a disabled button returns to IDLE.
 * - After a SEND, taps are ignored for [cooldownMs] so a burst of taps cannot raise a second request.
 * - A single tap never sends.
 */
class BolusTapGesture(
    private val clock: () -> Long,
    val windowMs: Long = WINDOW_MS,
    val cooldownMs: Long = COOLDOWN_MS
) {

    enum class Decision {

        /** Nothing happened (button disabled, tap outside while idle, cooldown). */
        IGNORE,

        /** First tap accepted: vibrate, redraw armed, schedule the disarm. */
        ARM,

        /** Second tap within the window: send the bolus request. */
        SEND,

        /** Armed state was cancelled (tap outside the button). Redraw idle. */
        DISARM
    }

    private var armedAt: Long? = null
    private var lastSendAt: Long? = null

    /** True while the first tap was accepted and the window is still open. */
    val isArmed: Boolean
        get() {
            val at = armedAt ?: return false
            return clock() - at in 0..windowMs
        }

    /**
     * Handle one completed tap (tap-up).
     *
     * @param insideButton the tap landed inside the button's hit circle
     * @param enabled the preference is on, Wear control is allowed, the face is interactive and the button is shown
     */
    fun onTap(insideButton: Boolean, enabled: Boolean): Decision {
        val now = clock()
        if (!enabled) {
            val wasArmed = armedAt != null
            armedAt = null
            return if (wasArmed) Decision.DISARM else Decision.IGNORE
        }
        // Drop a stale arm (the scheduled disarm may not have run yet).
        armedAt?.let { if (now - it !in 0..windowMs) armedAt = null }

        if (!insideButton) {
            if (armedAt != null) {
                armedAt = null
                return Decision.DISARM
            }
            return Decision.IGNORE
        }
        lastSendAt?.let { if (now - it in 0 until cooldownMs) return Decision.IGNORE }

        return if (armedAt == null) {
            armedAt = now
            Decision.ARM
        } else {
            armedAt = null
            lastSendAt = now
            Decision.SEND
        }
    }

    /** Called by the scheduled disarm. Returns true when the armed state just ended (redraw needed). */
    fun expireIfDue(): Boolean {
        val at = armedAt ?: return false
        if (clock() - at in 0..windowMs) return false
        armedAt = null
        return true
    }

    /** Back to IDLE (ambient, preference off, face hidden). Returns true when it was armed. */
    fun reset(): Boolean {
        val wasArmed = armedAt != null
        armedAt = null
        return wasArmed
    }

    companion object {

        const val WINDOW_MS = 800L
        const val COOLDOWN_MS = 5000L
        const val DEFAULT_AMOUNT = 0.5
        /** The only amounts the ball can request (watch setting 0.5 / 1.0 U). */
        val ALLOWED_AMOUNTS = listOf(0.5, 1.0)

        /** True when (x, y) lies inside the circle at (cx, cy) with radius r. */
        fun isInside(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean {
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= r * r
        }

        /** Parse the amount preference; only 0.5 and 1.0 are allowed, anything else falls back to 0.5. */
        fun parseAmount(value: String?): Double {
            val parsed = value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return DEFAULT_AMOUNT
            return ALLOWED_AMOUNTS.firstOrNull { kotlin.math.abs(it - parsed) < 1e-6 } ?: DEFAULT_AMOUNT
        }
    }
}
