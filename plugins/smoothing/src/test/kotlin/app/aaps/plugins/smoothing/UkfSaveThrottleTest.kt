package app.aaps.plugins.smoothing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UkfSaveThrottleTest {

    private fun shouldSave(sinceSave: Int, sessionCount: Long) =
        UnscentedKalmanFilterPlugin.shouldSave(sinceSave, sessionCount)

    @Test
    fun savesEveryBgAtSessionStart() {
        for (count in 0L..UnscentedKalmanFilterPlugin.SAVE_ALWAYS_BELOW_SESSION_COUNT)
            assertThat(shouldSave(1, count)).isTrue()
    }

    @Test
    fun skipsSaveInRunningSessionUntilThreshold() {
        val session = 1000L
        for (since in 1 until UnscentedKalmanFilterPlugin.SAVE_EVERY_MEASUREMENTS)
            assertThat(shouldSave(since, session)).isFalse()
        assertThat(shouldSave(UnscentedKalmanFilterPlugin.SAVE_EVERY_MEASUREMENTS, session)).isTrue()
        assertThat(shouldSave(UnscentedKalmanFilterPlugin.SAVE_EVERY_MEASUREMENTS + 7, session)).isTrue()
    }

    @Test
    fun oneMinuteSensorSavesAboutOncePer50Bg() {
        var since = 0
        var saves = 0
        for (bg in 1..600L) {               // 10 h of Libre 3, session already running
            since += 1
            if (shouldSave(since, 10_000L + bg)) {
                saves++
                since = 0
            }
        }
        assertThat(saves).isEqualTo(12)
    }
}
