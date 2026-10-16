package app.aaps.plugins.sync.xdrip

/**
 * Decides when the xDrip external status line is broadcast.
 *
 * Several events per BG (new BG, new history data, IOB/COB calculation finished) used to send
 * one broadcast each, and every broadcast starts xDrip's ExternalStatusService as a foreground
 * service. This class:
 *  - coalesces triggers that arrive close together: the send waits until [debounceMs] passed
 *    without a new trigger, but never longer than [maxWaitMs] after the first trigger of a burst
 *    ([delayForTrigger]);
 *  - drops a send whose text equals the last sent text ([shouldSend]), unless [heartbeatMs]
 *    passed since the last broadcast. xDrip stamps each status line with its arrival time,
 *    exports that time (web service "aaps-ts", broadcast API "external.timeStamp") and hides lines
 *    older than 5 h, so an unchanged line is still refreshed regularly.
 *
 * Not thread safe; callers synchronize.
 */
class StatusLineThrottle(
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val heartbeatMs: Long = HEARTBEAT_MS
) {

    private var lastSentStatus: String? = null
    private var lastSentAt = 0L
    private var burstStartedAt: Long? = null

    /**
     * Registers a trigger at [now] and returns the delay after which the pending send should run.
     * A previously scheduled send is expected to be replaced by the caller.
     */
    fun delayForTrigger(now: Long): Long {
        val start = burstStartedAt ?: now.also { burstStartedAt = it }
        val deadline = start + maxWaitMs
        return (deadline - now).coerceIn(0L, debounceMs)
    }

    /** Called when the pending send runs: ends the current burst. */
    fun burstFinished() {
        burstStartedAt = null
    }

    /**
     * Returns true when [status] must be broadcast at [now] and records it as sent.
     * [force] bypasses the comparison (explicit refresh, e.g. app start).
     */
    fun shouldSend(status: String, now: Long, force: Boolean = false): Boolean {
        val send = force || status != lastSentStatus || now - lastSentAt >= heartbeatMs || now < lastSentAt
        if (send) {
            lastSentStatus = status
            lastSentAt = now
        }
        return send
    }

    /** Forgets the last sent line, so the next one is broadcast (e.g. after sending was disabled). */
    fun reset() {
        lastSentStatus = null
        lastSentAt = 0L
    }

    companion object {

        const val DEBOUNCE_MS = 2_000L
        const val MAX_WAIT_MS = 10_000L
        const val HEARTBEAT_MS = 5 * 60_000L
    }
}
