package app.aaps.core.interfaces.workflow

/**
 * Keeps the device awake from a new BG through its calculation run and loop.
 *
 * The system holds a wake lock for a WorkManager job only while that job runs. Nothing holds one while
 * a new BG waits in the history debounce (a coroutine `delay`, which does not wake a sleeping CPU),
 * nor between the workers of the calculation chain. With the screen off the CPU could sleep in such a
 * gap, and the loop for that BG then ran only when something else woke the device. Android answers
 * with a partial wake lock.
 *
 * Backported from upstream AndroidAPS (interface only; the 3.4 calculation still runs on WorkManager).
 */
interface CalculationWakeLock {

    /** Keeps the device awake until [Held.release] is called, but never longer than [timeoutMs]. */
    fun acquire(timeoutMs: Long): Held

    /** One acquired lock. [release] is safe to call more than once, and after the timeout ran out. */
    interface Held {

        fun release()
    }
}
