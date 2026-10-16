package app.aaps.plugins.sync.xdrip

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StatusLineThrottleTest {

    private val t0 = 1_000_000L

    @Test
    fun firstLineIsSent() {
        val sut = StatusLineThrottle()
        assertThat(sut.shouldSend("1.00U 0g", t0)).isTrue()
    }

    @Test
    fun unchangedLineIsSkipped() {
        val sut = StatusLineThrottle()
        assertThat(sut.shouldSend("1.00U 0g", t0)).isTrue()
        assertThat(sut.shouldSend("1.00U 0g", t0 + 60_000)).isFalse()
        assertThat(sut.shouldSend("1.00U 0g", t0 + 120_000)).isFalse()
    }

    @Test
    fun changedLineIsSent() {
        val sut = StatusLineThrottle()
        assertThat(sut.shouldSend("1.00U 0g", t0)).isTrue()
        assertThat(sut.shouldSend("0.98U 0g", t0 + 1_000)).isTrue()
        assertThat(sut.shouldSend("0.98U 0g", t0 + 2_000)).isFalse()
    }

    @Test
    fun unchangedLineIsResentAfterHeartbeat() {
        val sut = StatusLineThrottle()
        assertThat(sut.shouldSend("x", t0)).isTrue()
        assertThat(sut.shouldSend("x", t0 + StatusLineThrottle.HEARTBEAT_MS - 1)).isFalse()
        assertThat(sut.shouldSend("x", t0 + StatusLineThrottle.HEARTBEAT_MS)).isTrue()
        // heartbeat restarts from the last send
        assertThat(sut.shouldSend("x", t0 + StatusLineThrottle.HEARTBEAT_MS + 1_000)).isFalse()
    }

    @Test
    fun forceSendsUnchangedLine() {
        val sut = StatusLineThrottle()
        assertThat(sut.shouldSend("x", t0)).isTrue()
        assertThat(sut.shouldSend("x", t0 + 1_000, force = true)).isTrue()
    }

    @Test
    fun resetSendsUnchangedLine() {
        val sut = StatusLineThrottle()
        assertThat(sut.shouldSend("x", t0)).isTrue()
        sut.reset()
        assertThat(sut.shouldSend("x", t0 + 1_000)).isTrue()
    }

    @Test
    fun clockGoingBackwardsSends() {
        val sut = StatusLineThrottle()
        assertThat(sut.shouldSend("x", t0)).isTrue()
        assertThat(sut.shouldSend("x", t0 - 60_000)).isTrue()
    }

    @Test
    fun emptyLineIsHandledLikeAnyOther() {
        val sut = StatusLineThrottle()
        assertThat(sut.shouldSend("", t0)).isTrue()
        assertThat(sut.shouldSend("", t0 + 1_000)).isFalse()
    }

    @Test
    fun triggersAreDebounced() {
        val sut = StatusLineThrottle()
        assertThat(sut.delayForTrigger(t0)).isEqualTo(StatusLineThrottle.DEBOUNCE_MS)
        assertThat(sut.delayForTrigger(t0 + 500)).isEqualTo(StatusLineThrottle.DEBOUNCE_MS)
    }

    @Test
    fun debounceIsCappedByMaxWait() {
        val sut = StatusLineThrottle()
        sut.delayForTrigger(t0)
        assertThat(sut.delayForTrigger(t0 + 9_000)).isEqualTo(1_000L)
        assertThat(sut.delayForTrigger(t0 + 10_000)).isEqualTo(0L)
        assertThat(sut.delayForTrigger(t0 + 15_000)).isEqualTo(0L)
    }

    @Test
    fun newBurstStartsAfterSend() {
        val sut = StatusLineThrottle()
        sut.delayForTrigger(t0)
        sut.burstFinished()
        assertThat(sut.delayForTrigger(t0 + 20_000)).isEqualTo(StatusLineThrottle.DEBOUNCE_MS)
        assertThat(sut.delayForTrigger(t0 + 29_000)).isEqualTo(1_000L)
    }
}
