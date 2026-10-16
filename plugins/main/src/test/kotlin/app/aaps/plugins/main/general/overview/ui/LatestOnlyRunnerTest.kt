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
        val runner = LatestOnlyRunner<Int>(scope, onError = { throw AssertionError(it) }) { value ->
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
        val runner = LatestOnlyRunner<Int>(scope, onError = { throw AssertionError(it) }) { started.add(it) }

        runner.submit(1)
        scope.advanceUntilIdle()
        runner.submit(2)
        scope.advanceUntilIdle()

        assertThat(started).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `a failed run does not block later requests and does not reach the scope`() {
        val started = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val uncaught = mutableListOf<Throwable>()
        val dispatcher = StandardTestDispatcher()
        // Like StatusLightHandler's scope: a SupervisorJob; the handler only records what would end the app
        val failingScope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught.add(e) })
        val runner = LatestOnlyRunner<Int>(failingScope, onError = { errors.add(it) }) { value ->
            started.add(value)
            if (value == 1) error("boom")
        }

        runner.submit(1)
        dispatcher.scheduler.advanceUntilIdle()
        runner.submit(2)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(started).containsExactly(1, 2).inOrder()
        assertThat(errors.map { it.message }).containsExactly("boom")
        assertThat(uncaught).isEmpty()
    }

    @Test
    fun `a value submitted during a run that fails still runs`() {
        val started = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val gate = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher()
        val failingScope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val runner = LatestOnlyRunner<Int>(failingScope, onError = { errors.add(it) }) { value ->
            started.add(value)
            if (value == 1) {
                gate.await()
                error("boom")
            }
        }

        runner.submit(1)
        dispatcher.scheduler.runCurrent()
        runner.submit(2)          // run 1 is busy: 2 waits
        gate.complete(Unit)       // run 1 now fails
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(started).containsExactly(1, 2).inOrder()
        assertThat(errors).hasSize(1)
    }
}
