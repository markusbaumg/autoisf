package app.aaps.workflow

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The polling behind [CalculationWorkflowImpl.awaitCalculationIdle], in virtual time.
 * (Upstream tests this on its coroutine executor; the 3.4 workflow is WorkManager based, so only the
 * wait itself is tested here, with the WorkManager query replaced by a flag.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalculationWorkflowAwaitIdleTest {

    @Test
    fun `is true at once when nothing runs`() = runTest {
        var polls = 0
        assertThat(CalculationWorkflowImpl.awaitIdle(1.seconds, 500) { polls++; false }).isTrue()
        assertThat(polls).isEqualTo(1)
    }

    @Test
    fun `waits until the calculation, loop stage included, has ended`() = runTest {
        var busy = true
        val idle = async { CalculationWorkflowImpl.awaitIdle(10.minutes, 500) { busy } }
        advanceTimeBy(3.minutes)
        runCurrent()
        assertThat(idle.isCompleted).isFalse()

        busy = false
        advanceTimeBy(600.milliseconds)
        runCurrent()
        assertThat(idle.isCompleted).isTrue()
        assertThat(idle.await()).isTrue()
    }

    @Test
    fun `gives up after the timeout`() = runTest {
        assertThat(CalculationWorkflowImpl.awaitIdle(100.milliseconds, 10) { true }).isFalse()
    }
}
