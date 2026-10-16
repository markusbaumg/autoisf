package app.aaps.plugins.main.general.overview.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [block] for submitted values one at a time, and only for the newest value that arrived while a
 * run was busy. Values submitted in between are dropped.
 *
 * For a refresh that is requested more often than it can finish: each request used to start its own
 * coroutine that nothing cancelled, so slow runs piled up and competed for the database.
 * Cancelling the previous run would not help when its work is blocking and does not check for
 * cancellation; this never has more than one run going and never loses the latest request.
 */
internal class LatestOnlyRunner<T : Any>(
    private val scope: CoroutineScope,
    private val block: suspend (T) -> Unit
) {

    private val lock = Any()
    private var pending: T? = null
    private var running = false

    fun submit(value: T) {
        synchronized(lock) {
            pending = value
            if (running) return
            running = true
        }
        scope.launch {
            try {
                while (true) {
                    val next = synchronized(lock) {
                        val next = pending
                        pending = null
                        // Cleared under the same lock submit() checks, so a value submitted from now on
                        // starts a new run and none is left behind.
                        if (next == null) running = false
                        next
                    } ?: return@launch
                    block(next)
                }
            } catch (e: Throwable) {
                // Not in a finally: after a normal end another run may already own the flag.
                synchronized(lock) { running = false }
                throw e
            }
        }
    }
}
