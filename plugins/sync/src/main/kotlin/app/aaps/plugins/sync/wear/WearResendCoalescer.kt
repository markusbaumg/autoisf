package app.aaps.plugins.sync.wear

import io.reactivex.rxjava3.core.Scheduler
import java.util.concurrent.TimeUnit

/**
 * Turns a burst of "send the watch everything again" requests into one resend.
 *
 * Every resend builds the BG graph and all treatments again and sends about 8 messages to the
 * watch. After a BG the calculation and the loop each send their event within seconds, often more
 * than once, and each one used to start its own resend. A request now waits until [quietMs] have
 * passed without another one, and then the last reason is resent once. So two resends are at least
 * [quietMs] apart.
 *
 * Unlike a plain debounce, a stream of requests that never pauses for [quietMs] cannot hold the
 * resend back for ever: [maxWaitMs] after the first request of a burst it is sent anyway.
 *
 * Port of the intent of nightscout/dev 8592b7fcbf and 3b9b3786d7 (one 2 s debounce for all resend
 * triggers, no request lost) to the RxJava based plugin of this branch.
 */
class WearResendCoalescer(
    private val scheduler: Scheduler,
    private val quietMs: Long = QUIET_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val resend: (reason: String) -> Unit
) {

    private val lock = Any()
    private var pendingReason: String? = null
    private var firstRequestAt = 0L

    /** When the pending request is due: [quietMs] after the last request, at most [maxWaitMs] after the first. */
    private var deadline = 0L

    /**
     * At most one timer runs at a time and it is never disposed. Disposing a scheduleDirect task
     * that has already started on an io thread interrupts that thread (RxJava cancels with interrupt),
     * and the resend running on it would then fail on its blocking DB reads. Instead a new request
     * only moves [deadline]; the timer, when it fires early, waits again for the rest.
     */
    private var timerScheduled = false

    /** Asks for a full resend; [reason] goes to the log of the resend. */
    fun request(reason: String) {
        synchronized(lock) {
            val now = scheduler.now(TimeUnit.MILLISECONDS)
            if (pendingReason == null) firstRequestAt = now
            pendingReason = reason
            deadline = minOf(now + quietMs, firstRequestAt + maxWaitMs)
            if (!timerScheduled) {
                timerScheduled = true
                schedule(deadline - now)
            }
        }
    }

    /** Drops a request that has not been sent yet. A running timer then fires without sending. */
    fun cancel() {
        synchronized(lock) {
            pendingReason = null
        }
    }

    private fun schedule(delayMs: Long) {
        scheduler.scheduleDirect({ fire() }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun fire() {
        val reason = synchronized(lock) {
            if (pendingReason == null) {
                timerScheduled = false
                return
            }
            val now = scheduler.now(TimeUnit.MILLISECONDS)
            if (now < deadline) {
                schedule(deadline - now)
                return
            }
            timerScheduled = false
            pendingReason.also { pendingReason = null }
        } ?: return
        resend(reason)
    }

    companion object {

        /** The quiet time a burst of requests is waited out with, the same 2 s as upstream. */
        const val QUIET_MS = 2_000L

        /** The longest a request waits while requests keep coming. */
        const val MAX_WAIT_MS = 10_000L
    }
}
