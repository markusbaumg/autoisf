package app.aaps.workflow

import app.aaps.core.interfaces.workflow.CalculationWakeLock
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Who gives the lock of a main calculation run back: its loop worker, or the run that replaced it. */
class LoopWakeLockTest {

    private class CountingHeld : CalculationWakeLock.Held {

        var released = 0
        override fun release() {
            released++
        }
    }

    @Test
    fun `a replaced run whose loop worker never started gives its lock back`() {
        val held = CountingHeld()
        LoopWakeLock(held).releaseIfUnclaimed()
        assertThat(held.released).isEqualTo(1)
    }

    @Test
    fun `a running loop keeps its lock when its run is replaced, and releases it when it ends`() {
        val held = CountingHeld()
        val sut = LoopWakeLock(held)

        sut.claim()
        sut.releaseIfUnclaimed()
        assertThat(held.released).isEqualTo(0)

        sut.release()
        assertThat(held.released).isEqualTo(1)
    }
}
