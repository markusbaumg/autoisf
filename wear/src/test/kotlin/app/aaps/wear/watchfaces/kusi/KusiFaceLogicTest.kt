package app.aaps.wear.watchfaces.kusi

import app.aaps.wear.watchfaces.kusi.KusiFaceLogic.LoopState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KusiFaceLogicTest {

    @Test fun angleMapsScaleOntoRing() {
        assertThat(KusiFaceLogic.angle(40.0)).isEqualTo(135f)
        assertThat(KusiFaceLogic.angle(300.0)).isEqualTo(405f)
        assertThat(KusiFaceLogic.angle(170.0)).isWithin(0.01f).of(270f)
        assertThat(KusiFaceLogic.angle(10.0)).isEqualTo(135f)
        assertThat(KusiFaceLogic.angle(500.0)).isEqualTo(405f)
    }

    @Test fun loopStates() {
        val now = 10_000_000L
        assertThat(KusiFaceLogic.loopAgeMinutes(-1L, now)).isNull()
        assertThat(KusiFaceLogic.loopAgeMinutes(now - 3 * 60_000L, now)).isEqualTo(3L)
        assertThat(KusiFaceLogic.loopState(null)).isEqualTo(LoopState.STALE)
        assertThat(KusiFaceLogic.loopState(0)).isEqualTo(LoopState.OK)
        assertThat(KusiFaceLogic.loopState(5)).isEqualTo(LoopState.OK)
        assertThat(KusiFaceLogic.loopState(6)).isEqualTo(LoopState.WARN)
        assertThat(KusiFaceLogic.loopState(14)).isEqualTo(LoopState.WARN)
        assertThat(KusiFaceLogic.loopState(15)).isEqualTo(LoopState.STALE)
    }

    @Test fun staleBg() {
        val now = 10_000_000L
        assertThat(KusiFaceLogic.isStale(0L, now)).isTrue()
        assertThat(KusiFaceLogic.isStale(now - 12 * 60_000L, now)).isFalse()
        assertThat(KusiFaceLogic.isStale(now - 12 * 60_000L - 1, now)).isTrue()
        assertThat(KusiFaceLogic.bgAgeMinutes(now - 61_000L, now)).isEqualTo(1L)
        assertThat(KusiFaceLogic.bgAgeMinutes(0L, now)).isNull()
    }

    @Test fun iob() {
        assertThat(KusiFaceLogic.parseIob("2.53")).isEqualTo(2.53)
        assertThat(KusiFaceLogic.parseIob("-0,10")).isEqualTo(-0.10)
        assertThat(KusiFaceLogic.parseIob("1.2U")).isEqualTo(1.2)
        assertThat(KusiFaceLogic.parseIob("--")).isNull()
        assertThat(KusiFaceLogic.iobFraction(-1.0)).isEqualTo(0f)
        assertThat(KusiFaceLogic.iobFraction(3.0)).isEqualTo(0.5f)
        assertThat(KusiFaceLogic.iobFraction(12.0)).isEqualTo(1f)
    }

    @Test fun lines() {
        assertThat(KusiFaceLogic.deltaLine("+0.1", 0)).isEqualTo("+0.1 · 0'")
        assertThat(KusiFaceLogic.deltaLine("--", null)).isEqualTo("--")
        assertThat(KusiFaceLogic.secondaryLine("5.6", 2, "0.00U/h")).isEqualTo("TT 5.6   ·   0.00U/h")
        assertThat(KusiFaceLogic.secondaryLine("5.0 - 6.0", 0, "0.00U/h")).isEqualTo("0.00U/h")
    }
}
