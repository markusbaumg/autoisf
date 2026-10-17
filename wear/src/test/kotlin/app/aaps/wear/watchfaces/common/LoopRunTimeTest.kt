package app.aaps.wear.watchfaces.common

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LoopRunTimeTest {

    private fun status(openApsStatus: Long, lastLoopRunAt: Long = 0L) = EventData.Status(
        dataset = 0, externalStatus = "ok", iobSum = "1.00", iobDetail = "", cob = "", currentBasal = "", battery = "80",
        rigBattery = "", openApsStatus = openApsStatus, bgi = "", batteryLevel = 1, tempTarget = "", tempTargetLevel = 0,
        reservoirString = "", reservoir = 0.0, reservoirLevel = 0, lastLoopRunAt = lastLoopRunAt
    )

    @Test fun realRunTimeWinsOverEnact() {
        assertThat(LoopRunTime.of(status(openApsStatus = 1_000L, lastLoopRunAt = 9_000L))).isEqualTo(9_000L)
    }

    @Test fun realRunTimeWinsWhenNoEnact() {
        assertThat(LoopRunTime.of(status(openApsStatus = -1L, lastLoopRunAt = 9_000L))).isEqualTo(9_000L)
    }

    @Test fun olderPhoneFallsBackToOpenApsStatus() {
        assertThat(LoopRunTime.of(status(openApsStatus = 1_000L))).isEqualTo(1_000L)
        assertThat(LoopRunTime.of(status(openApsStatus = -1L))).isEqualTo(-1L)
    }

    @Test fun loopAgeOnAllFacesUsesRunTime() {
        val now = 20 * 60_000L
        val t = LoopRunTime.of(status(openApsStatus = 0L + 60_000L, lastLoopRunAt = now - 2 * 60_000L))
        assertThat(app.aaps.wear.watchfaces.halo.HaloFaceLogic.loopAgeMinutes(t, now)).isEqualTo(2L)
        assertThat(app.aaps.wear.watchfaces.glass.GlassFaceLogic.loopAgeMinutes(t, now)).isEqualTo(2L)
        assertThat(app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.loopAgeMinutes(t, now)).isEqualTo(2L)
    }
}
