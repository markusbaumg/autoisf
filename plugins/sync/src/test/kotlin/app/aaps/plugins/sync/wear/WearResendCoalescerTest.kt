package app.aaps.plugins.sync.wear

import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class WearResendCoalescerTest {

    private lateinit var scheduler: TestScheduler
    private val sent = mutableListOf<Pair<Long, String>>()
    private lateinit var sut: WearResendCoalescer

    @BeforeEach fun prepare() {
        scheduler = TestScheduler()
        sent.clear()
        sut = WearResendCoalescer(scheduler, quietMs = 2_000L, maxWaitMs = 10_000L) { sent.add(scheduler.now(TimeUnit.MILLISECONDS) to it) }
    }

    private fun advance(ms: Long) = scheduler.advanceTimeBy(ms, TimeUnit.MILLISECONDS)

    @Test fun `defaults wait 8 s of quiet and at most 20 s`() {
        val defaults = WearResendCoalescer(scheduler) { sent.add(scheduler.now(TimeUnit.MILLISECONDS) to it) }
        // BG, autosens result 2 s later, loop GUI events 3 s and 6 s after that: one resend
        defaults.request("BG")
        advance(2_000)
        defaults.request("EventAutosensCalculationFinished")
        advance(3_000)
        defaults.request("EventLoopUpdateGui")
        advance(3_000)
        defaults.request("EventLoopUpdateGui")
        advance(7_999)
        assertThat(sent).isEmpty()
        advance(1)
        assertThat(sent).containsExactly(16_000L to "EventLoopUpdateGui")
        sent.clear()
        // requests every 5 s never go quiet for 8 s: sent after 20 s anyway
        repeat(9) {
            defaults.request("R$it")
            advance(5_000)
        }
        assertThat(sent.map { it.first }).containsExactly(16_000L + 20_000L, 16_000L + 40_000L).inOrder()
    }

    @Test fun `one request is sent after the quiet time`() {
        sut.request("A")
        advance(1_999)
        assertThat(sent).isEmpty()
        advance(1)
        assertThat(sent).containsExactly(2_000L to "A")
    }

    @Test fun `a burst gives one resend with the last reason`() {
        sut.request("EventAutosensCalculationFinished")
        advance(500)
        sut.request("EventLoopUpdateGui")
        advance(1_000)
        sut.request("EventLoopUpdateGui2")
        advance(10_000)
        assertThat(sent).containsExactly(3_500L to "EventLoopUpdateGui2")
    }

    @Test fun `requests that never pause are still sent after max wait`() {
        // one request a second, as the preference storm did: a plain debounce would never fire
        repeat(25) {
            sut.request("R$it")
            advance(1_000)
        }
        assertThat(sent.map { it.first }).containsExactly(10_000L, 20_000L).inOrder()
        assertThat(sent.first().second).isEqualTo("R9")
    }

    @Test fun `separate requests are each sent, at least the quiet time apart`() {
        sut.request("A")
        advance(5_000)
        sut.request("B")
        advance(5_000)
        assertThat(sent).containsExactly(2_000L to "A", 7_000L to "B").inOrder()
    }

    @Test fun `cancel drops a request not sent yet`() {
        sut.request("A")
        sut.cancel()
        advance(20_000)
        assertThat(sent).isEmpty()
        sut.request("B")
        advance(2_000)
        assertThat(sent).containsExactly(22_000L to "B")
    }

    @Test fun `a request made while a resend runs is sent once more afterwards with the latest reason`() {
        var nested = true
        sut = WearResendCoalescer(scheduler, quietMs = 2_000L, maxWaitMs = 10_000L) {
            sent.add(scheduler.now(TimeUnit.MILLISECONDS) to it)
            if (nested) {
                nested = false
                sut.request("B")
                sut.request("C")
            }
        }
        sut.request("A")
        advance(20_000)
        assertThat(sent).containsExactly(2_000L to "A", 4_000L to "C").inOrder()
    }

    @Test fun `a burst uses one timer and never disposes a scheduled task`() {
        // Disposing an io task that has already started interrupts its thread; the coalescer must not do it.
        var workers = 0
        var disposed = 0
        val counting = object : Scheduler() {
            override fun now(unit: TimeUnit): Long = scheduler.now(unit)
            override fun createWorker(): Worker {
                workers++
                val inner = scheduler.createWorker()
                return object : Worker() {
                    override fun schedule(run: Runnable, delay: Long, unit: TimeUnit): Disposable = inner.schedule(run, delay, unit)
                    override fun dispose() {
                        disposed++
                        inner.dispose()
                    }
                    override fun isDisposed(): Boolean = inner.isDisposed
                }
            }
        }
        sut = WearResendCoalescer(counting, quietMs = 2_000L, maxWaitMs = 10_000L) { sent.add(scheduler.now(TimeUnit.MILLISECONDS) to it) }
        repeat(100) { sut.request("R$it") }
        advance(1_000)
        repeat(100) { sut.request("S$it") }
        advance(10_000)
        assertThat(sent).containsExactly(3_000L to "S99")
        assertThat(workers).isEqualTo(2)                  // the first timer and its one wait for the rest
        assertThat(disposed).isEqualTo(workers)           // only disposed by the scheduler itself after running
    }
}
