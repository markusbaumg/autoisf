package app.aaps.workflow

import android.content.Context
import android.os.PowerManager
import app.aaps.core.interfaces.workflow.CalculationWakeLock
import java.util.concurrent.atomic.AtomicBoolean

/** [CalculationWakeLock] on Android: a partial wake lock, always with a timeout. */
internal class AndroidCalculationWakeLock(private val context: Context) : CalculationWakeLock {

    override fun acquire(timeoutMs: Long): CalculationWakeLock.Held {
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            // Not counted, so release() does nothing when the lock is already released, by its
            // timeout or by an earlier call. Held promises that release() is safe to call more than
            // once; a counted lock throws "WakeLock under-locked" on the second call.
            .apply { setReferenceCounted(false) }
        wakeLock.acquire(timeoutMs)
        return object : CalculationWakeLock.Held {
            override fun release() = wakeLock.release()
        }
    }

    companion object {

        const val TAG = "AndroidAPS:Calculation"
    }
}

/**
 * The wake lock of one main calculation run, from its enqueue until [InvokeLoopWorker] has ended.
 *
 * [InvokeLoopWorker] [claim]s it when it starts and releases it when it ends. A run that is replaced
 * by a newer one before its loop worker started never runs that worker, so the newer run gives the
 * lock back with [releaseIfUnclaimed]. A loop that is already running keeps its lock until it ends:
 * a cancelled worker still runs `loop.invoke()` to its end. The timeout covers a chain that fails.
 */
internal class LoopWakeLock(private val held: CalculationWakeLock.Held) {

    private val claimed = AtomicBoolean(false)

    /** Called by [InvokeLoopWorker] when it starts. */
    fun claim() {
        claimed.set(true)
    }

    fun release() = held.release()

    fun releaseIfUnclaimed() {
        if (claimed.compareAndSet(false, true)) held.release()
    }
}
