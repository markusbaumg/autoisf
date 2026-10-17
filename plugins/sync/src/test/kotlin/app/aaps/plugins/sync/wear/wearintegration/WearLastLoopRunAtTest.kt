package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.aps.Loop
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WearLastLoopRunAtTest {

    @Test
    fun apsUsesLastApsRunEvenWithoutTempBasalEnact() {
        val run = Loop.LastRun().apply {
            lastAPSRun = 1_000_000L
            lastTBREnact = 0L // "no change needed": openApsStatus would be -1
        }
        assertThat(wearLastLoopRunAt(isAps = true, lastRun = run, clientOpenApsTimestamp = 0L)).isEqualTo(1_000_000L)
    }

    @Test
    fun apsUsesLastApsRunNotTheOlderEnact() {
        val run = Loop.LastRun().apply {
            lastAPSRun = 2_000_000L
            lastTBREnact = 1_500_000L
        }
        assertThat(wearLastLoopRunAt(isAps = true, lastRun = run, clientOpenApsTimestamp = 0L)).isEqualTo(2_000_000L)
    }

    @Test
    fun apsWithoutRunSinceStartIsZero() {
        assertThat(wearLastLoopRunAt(isAps = true, lastRun = null, clientOpenApsTimestamp = 5L)).isEqualTo(0L)
        assertThat(wearLastLoopRunAt(isAps = true, lastRun = Loop.LastRun().apply { lastAPSRun = 0L }, clientOpenApsTimestamp = 5L)).isEqualTo(0L)
    }

    @Test
    fun clientUsesDeviceStatusSuggestedTime() {
        assertThat(wearLastLoopRunAt(isAps = false, lastRun = null, clientOpenApsTimestamp = 3_000_000L)).isEqualTo(3_000_000L)
        assertThat(wearLastLoopRunAt(isAps = false, lastRun = null, clientOpenApsTimestamp = -1L)).isEqualTo(0L)
    }
}
