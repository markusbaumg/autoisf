package app.aaps.plugins.main.general.overview.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestOnlyRunnerTest {

    private val scope = TestScope(StandardTestDispatcher())

    @Test
    fun `requests arriving during a run collapse into one run for the newest`() {
        val started = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val runner = LatestOnlyRunner<Int>(scope) { value ->
            started.add(value)
            if (value == 1) gate.await()
        }

        runner.submit(1)
        scope.runCurrent()
        assertThat(started).containsExactly(1)

        // The first run is still busy: these must not start runs of their own
        runner.submit(2)
        runner.submit(3)
        runner.submit(4)
        scope.runCurrent()
        assertThat(started).containsExactly(1)

        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertThat(started).containsExactly(1, 4).inOrder()
    }

    @Test
    fun `a request after a finished run starts a new run`() {
        val started = mutableListOf<Int>()
        val runner = LatestOnlyRunner<Int>(scope) { started.add(it) }

        runner.submit(1)
        scope.advanceUntilIdle()
        runner.submit(2)
        scope.advanceUntilIdle()

        assertThat(started).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `a failed run does not block later requests`() {
        val started = mutableListOf<Int>()
        val dispatcher = StandardTestDispatcher()
        // Like StatusLightHandler's scope: a SupervisorJob, so one failed run does not cancel the scope
        val failingScope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val runner = LatestOnlyRunner<Int>(failingScope) { value ->
            started.add(value)
            if (value == 1) error("boom")
        }

        runner.submit(1)
        dispatcher.scheduler.advanceUntilIdle()
        runner.submit(2)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(started).containsExactly(1, 2).inOrder()
    }
}
