package app.aaps.wear.watchfaces.halo

import app.aaps.wear.watchfaces.halo.HaloFaceLogic.AgeLevel
import app.aaps.wear.watchfaces.halo.HaloFaceLogic.AmbientHero
import app.aaps.wear.watchfaces.halo.HaloFaceLogic.LoopState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class HaloFaceLogicTest {

    @Test fun angleMapsScaleOntoRing() {
        assertThat(HaloFaceLogic.angle(40.0)).isEqualTo(135f)
        assertThat(HaloFaceLogic.angle(300.0)).isEqualTo(405f)
        assertThat(HaloFaceLogic.angle(170.0)).isWithin(0.01f).of(270f)
        assertThat(HaloFaceLogic.angle(10.0)).isEqualTo(135f)
        assertThat(HaloFaceLogic.angle(500.0)).isEqualTo(405f)
    }

    @Test fun loopStates() {
        val now = 10_000_000L
        assertThat(HaloFaceLogic.loopAgeMinutes(-1L, now)).isNull()
        assertThat(HaloFaceLogic.loopAgeMinutes(0L, now)).isNull()
        assertThat(HaloFaceLogic.loopAgeMinutes(now - 3 * 60_000L, now)).isEqualTo(3L)
        assertThat(HaloFaceLogic.loopState(null)).isEqualTo(LoopState.UNKNOWN)
        assertThat(HaloFaceLogic.loopState(0)).isEqualTo(LoopState.OK)
        assertThat(HaloFaceLogic.loopState(5)).isEqualTo(LoopState.OK)
        assertThat(HaloFaceLogic.loopState(6)).isEqualTo(LoopState.WARN)
        assertThat(HaloFaceLogic.loopState(14)).isEqualTo(LoopState.WARN)
        assertThat(HaloFaceLogic.loopState(15)).isEqualTo(LoopState.STALE)
    }

    @Test fun loopTimeMinusOneKeepsTheLastValidValue() {
        val now = 10_000_000L
        val valid = now - 2 * 60_000L
        // -1 after a valid value ("no change needed" run, or phone restart): keep the remembered time, not red "--".
        val kept = HaloFaceLogic.rememberedLoopTime(-1L, valid)
        assertThat(kept).isEqualTo(valid)
        assertThat(HaloFaceLogic.loopState(HaloFaceLogic.loopAgeMinutes(kept, now))).isEqualTo(LoopState.OK)
        assertThat(HaloFaceLogic.rememberedLoopTime(0L, valid)).isEqualTo(valid)
    }

    @Test fun loopTimeMinusOneWithoutHistoryIsUnknown() {
        val now = 10_000_000L
        val kept = HaloFaceLogic.rememberedLoopTime(-1L, 0L)
        assertThat(kept).isEqualTo(0L)
        assertThat(HaloFaceLogic.loopAgeMinutes(kept, now)).isNull()
        assertThat(HaloFaceLogic.loopState(HaloFaceLogic.loopAgeMinutes(kept, now))).isEqualTo(LoopState.UNKNOWN)
        assertThat(HaloFaceLogic.rememberedLoopTime(-1L, -1L)).isEqualTo(0L)
    }

    @Test fun newerValidLoopTimeReplacesTheRememberedOne() {
        val now = 10_000_000L
        val old = now - 20 * 60_000L
        val fresh = now - 60_000L
        assertThat(HaloFaceLogic.rememberedLoopTime(fresh, old)).isEqualTo(fresh)
        assertThat(HaloFaceLogic.loopState(HaloFaceLogic.loopAgeMinutes(HaloFaceLogic.rememberedLoopTime(fresh, old), now))).isEqualTo(LoopState.OK)
        // A valid value always wins, also an older one (e.g. after the phone's clock or data was reset).
        assertThat(HaloFaceLogic.rememberedLoopTime(old, fresh)).isEqualTo(old)
    }

    @Test fun bgAgeLevels() {
        val now = 10_000_000L
        assertThat(HaloFaceLogic.bgAgeMinutes(0L, now)).isNull()
        assertThat(HaloFaceLogic.bgAgeMinutes(now - 61_000L, now)).isEqualTo(1L)
        assertThat(HaloFaceLogic.bgAgeLevel(null)).isEqualTo(AgeLevel.STALE)
        assertThat(HaloFaceLogic.bgAgeLevel(5)).isEqualTo(AgeLevel.FRESH)
        assertThat(HaloFaceLogic.bgAgeLevel(6)).isEqualTo(AgeLevel.OLD)
        assertThat(HaloFaceLogic.bgAgeLevel(11)).isEqualTo(AgeLevel.OLD)
        assertThat(HaloFaceLogic.bgAgeLevel(12)).isEqualTo(AgeLevel.STALE)
        assertThat(HaloFaceLogic.isStale(0L, now)).isTrue()
        assertThat(HaloFaceLogic.isStale(now - 12 * 60_000L + 1, now)).isFalse()
        assertThat(HaloFaceLogic.isStale(now - 12 * 60_000L, now)).isTrue()
    }

    @Test fun staleBgLooksDifferentInAmbient() {
        assertThat(HaloFaceLogic.ambientHero(AgeLevel.FRESH)).isEqualTo(AmbientHero.OUTLINE)
        assertThat(HaloFaceLogic.ambientHero(AgeLevel.OLD)).isEqualTo(AmbientHero.OUTLINE)
        assertThat(HaloFaceLogic.ambientHero(AgeLevel.STALE)).isEqualTo(AmbientHero.STRUCK)
        val now = 10_000_000L
        assertThat(HaloFaceLogic.ambientHero(HaloFaceLogic.bgAgeLevel(HaloFaceLogic.bgAgeMinutes(now - 13 * 60_000L, now)))).isEqualTo(AmbientHero.STRUCK)
        assertThat(HaloFaceLogic.ambientHero(HaloFaceLogic.bgAgeLevel(HaloFaceLogic.bgAgeMinutes(0L, now)))).isEqualTo(AmbientHero.STRUCK)
    }

    @Test fun noDataIsDashes() {
        assertThat(HaloFaceLogic.orDash(null)).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("")).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("---")).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("-")).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("-0.1")).isEqualTo("-0.1")
        assertThat(HaloFaceLogic.orDash("9.4")).isEqualTo("9.4")
        assertThat(HaloFaceLogic.ageText(null)).isEqualTo("--")
        assertThat(HaloFaceLogic.ageText(3)).isEqualTo("3'")
    }

    @Test fun iob() {
        assertThat(HaloFaceLogic.parseIob("2.53")).isEqualTo(2.53)
        assertThat(HaloFaceLogic.parseIob("-0,10")).isEqualTo(-0.10)
        assertThat(HaloFaceLogic.parseIob("1.2U")).isEqualTo(1.2)
        assertThat(HaloFaceLogic.parseIob("--")).isNull()
        assertThat(HaloFaceLogic.iobFraction(-1.0)).isEqualTo(0f)
        assertThat(HaloFaceLogic.iobFraction(3.0)).isEqualTo(0.5f)
        assertThat(HaloFaceLogic.iobFraction(12.0)).isEqualTo(1f)
    }

    @Test fun secondaryLine() {
        assertThat(HaloFaceLogic.secondaryLine("5.6", 2, "0.00U/h")).isEqualTo("TT 5.6   ·   0.00U/h")
        assertThat(HaloFaceLogic.secondaryLine("5.0 - 6.0", 0, "0.00U/h")).isEqualTo("0.00U/h")
        assertThat(HaloFaceLogic.secondaryLine("--", 2, "")).isEqualTo("--")
    }

    @Test fun fourCharMmolFits() {
        assertThat(HaloFaceLogic.fitScale(150f, 196f)).isEqualTo(1f)
        assertThat(HaloFaceLogic.fitScale(245f, 196f)).isWithin(1e-4f).of(0.8f)
        assertThat(HaloFaceLogic.fitScale(0f, 196f)).isEqualTo(1f)
    }

    @Test fun germanDate() {
        // 17 Oct 2026 is a Saturday; 16 Oct 2026 a Friday.
        val fri = LocalDate.of(2026, 10, 16)
        assertThat(HaloFaceLogic.germanDate(fri.dayOfWeek.value, fri.dayOfMonth, fri.monthValue)).isEqualTo("Fr 16. Okt")
        assertThat(HaloFaceLogic.germanDate(6, 17, 10)).isEqualTo("Sa 17. Okt")
        assertThat(HaloFaceLogic.germanDate(1, 2, 3)).isEqualTo("Mo 2. Mär")
        assertThat(HaloFaceLogic.germanDate(7, 31, 12)).isEqualTo("So 31. Dez")
        assertThat(HaloFaceLogic.germanDate(4, 1, 5)).isEqualTo("Do 1. Mai")
    }

    @Test fun timeText() {
        assertThat(HaloFaceLogic.timeText(0, 40, true)).isEqualTo("00:40")
        assertThat(HaloFaceLogic.timeText(17, 5, true)).isEqualTo("17:05")
        assertThat(HaloFaceLogic.timeText(17, 5, false)).isEqualTo("5:05")
        assertThat(HaloFaceLogic.timeText(0, 0, false)).isEqualTo("12:00")
        assertThat(HaloFaceLogic.timeText(12, 30, false)).isEqualTo("12:30")
    }

    @Test fun valueUnitSplitsNumberAndUnit() {
        assertThat(HaloFaceLogic.valueUnit("1.44", "U")).isEqualTo("1.44" to "U")
        assertThat(HaloFaceLogic.valueUnit("1.2U", "U")).isEqualTo("1.2" to "U")
        assertThat(HaloFaceLogic.valueUnit("-0.10", "U")).isEqualTo("-0.10" to "U")
        assertThat(HaloFaceLogic.valueUnit("0g", "g")).isEqualTo("0" to "g")
        assertThat(HaloFaceLogic.valueUnit("12(5)g", "g")).isEqualTo("12(5)" to "g")
        assertThat(HaloFaceLogic.valueUnit("--g", "g")).isEqualTo("--" to "")
        assertThat(HaloFaceLogic.valueUnit("", "U")).isEqualTo("--" to "")
        assertThat(HaloFaceLogic.valueUnit(null, "U")).isEqualTo("--" to "")
        assertThat(HaloFaceLogic.valueUnit("---", "g")).isEqualTo("--" to "")
    }

    @Test fun burnInOffsetIsSmallAndMoves() {
        val xs = (0 until 60).map { HaloFaceLogic.burnInOffsetX(it) }
        val ys = (0 until 60).map { HaloFaceLogic.burnInOffsetY(it) }
        assertThat(xs.all { kotlin.math.abs(it) <= 5f }).isTrue()
        assertThat(ys.all { kotlin.math.abs(it) <= 5f }).isTrue()
        assertThat(xs.zip(ys).toSet().size).isGreaterThan(3)
        assertThat(HaloFaceLogic.burnInOffsetX(-1)).isEqualTo(HaloFaceLogic.burnInOffsetX(5))
    }
}
