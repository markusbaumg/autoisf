package app.aaps.wear.watchfaces.halo

import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.LoopHealth
import app.aaps.wear.watchfaces.common.FaceState.Warning
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class HaloFaceLogicTest {

    private fun health(age: Long?, realRun: Boolean = true) =
        FaceState.loopHealth(age, FaceState.LoopRun.RUNNING, FaceState.LoopMode.UNKNOWN, realRun)

    @Test fun angleMapsScaleOntoRing() {
        assertThat(HaloFaceLogic.angle(40.0)).isEqualTo(135f)
        assertThat(HaloFaceLogic.angle(300.0)).isEqualTo(405f)
        assertThat(HaloFaceLogic.angle(170.0)).isWithin(0.01f).of(270f)
        assertThat(HaloFaceLogic.angle(10.0)).isEqualTo(135f)
        assertThat(HaloFaceLogic.angle(500.0)).isEqualTo(405f)
    }

    @Test fun loopHealthFromTheRealRunTimeAndTheLegacyEnactTime() {
        val now = 10_000_000L
        assertThat(HaloFaceLogic.loopAgeMinutes(-1L, now)).isNull()
        assertThat(HaloFaceLogic.loopAgeMinutes(0L, now)).isNull()
        assertThat(HaloFaceLogic.loopAgeMinutes(now - 3 * 60_000L, now)).isEqualTo(3L)
        assertThat(health(null)).isEqualTo(LoopHealth.NEVER)
        // real loop run time (lastLoopRunAt): family thresholds
        assertThat(health(5)).isEqualTo(LoopHealth.OK)
        assertThat(health(6)).isEqualTo(LoopHealth.WARN)
        assertThat(health(14)).isEqualTo(LoopHealth.WARN)
        assertThat(health(15)).isEqualTo(LoopHealth.BAD)
        // older phone app (last temp basal enact): a stable loop keeping its temp basal is not broken
        assertThat(health(7, realRun = false)).isEqualTo(LoopHealth.OK)
        assertThat(health(12, realRun = false)).isEqualTo(LoopHealth.OK)
        assertThat(health(13, realRun = false)).isEqualTo(LoopHealth.WARN)
        assertThat(health(19, realRun = false)).isEqualTo(LoopHealth.WARN)
        assertThat(health(20, realRun = false)).isEqualTo(LoopHealth.BAD)
    }

    @Test fun loopTimeMinusOneKeepsTheLastValidValue() {
        val now = 10_000_000L
        val valid = now - 2 * 60_000L
        // -1 after a valid value ("no change needed" run, or phone restart): keep the remembered time, not red "--".
        val kept = HaloFaceLogic.rememberedLoopTime(-1L, valid)
        assertThat(kept).isEqualTo(valid)
        assertThat(health(HaloFaceLogic.loopAgeMinutes(kept, now))).isEqualTo(LoopHealth.OK)
        assertThat(HaloFaceLogic.rememberedLoopTime(0L, valid)).isEqualTo(valid)
    }

    @Test fun persistedLoopTimeSurvivesRestartAndMinusOne() {
        val now = 10_000_000L
        val valid = now - 3 * 60_000L
        val prefs = mutableMapOf<String, Long>()
        fun memory() = HaloFaceLogic.LoopTimeMemory({ prefs["loop"] ?: 0L }, { prefs["loop"] = it })
        // First face instance receives a valid time and persists it.
        assertThat(memory().onReceived(valid)).isTrue()
        assertThat(prefs["loop"]).isEqualTo(valid)
        // The face is recreated (watch reboot / service restart) and the phone, just restarted, sends -1:
        // the age comes from the persisted time, not "--".
        val recreated = memory()
        assertThat(recreated.time).isEqualTo(valid)
        assertThat(recreated.onReceived(-1L)).isFalse()
        assertThat(HaloFaceLogic.loopAgeMinutes(recreated.time, now)).isEqualTo(3L)
        assertThat(health(HaloFaceLogic.loopAgeMinutes(recreated.time, now))).isEqualTo(LoopHealth.OK)
        assertThat(prefs["loop"]).isEqualTo(valid)
        // Nothing ever received: grey UNKNOWN, and -1 is never stored.
        val empty = HaloFaceLogic.LoopTimeMemory({ -1L }, { error("must not store") })
        assertThat(empty.onReceived(-1L)).isFalse()
        assertThat(health(HaloFaceLogic.loopAgeMinutes(empty.time, now))).isEqualTo(LoopHealth.NEVER)
    }

    @Test fun loopTimeMinusOneWithoutHistoryIsUnknown() {
        val now = 10_000_000L
        val kept = HaloFaceLogic.rememberedLoopTime(-1L, 0L)
        assertThat(kept).isEqualTo(0L)
        assertThat(HaloFaceLogic.loopAgeMinutes(kept, now)).isNull()
        assertThat(health(HaloFaceLogic.loopAgeMinutes(kept, now))).isEqualTo(LoopHealth.NEVER)
        assertThat(HaloFaceLogic.rememberedLoopTime(-1L, -1L)).isEqualTo(0L)
    }

    @Test fun newerValidLoopTimeReplacesTheRememberedOne() {
        val now = 10_000_000L
        val old = now - 20 * 60_000L
        val fresh = now - 60_000L
        assertThat(HaloFaceLogic.rememberedLoopTime(fresh, old)).isEqualTo(fresh)
        assertThat(health(HaloFaceLogic.loopAgeMinutes(HaloFaceLogic.rememberedLoopTime(fresh, old), now))).isEqualTo(LoopHealth.OK)
        // A valid value always wins, also an older one (e.g. after the phone's clock or data was reset).
        assertThat(HaloFaceLogic.rememberedLoopTime(old, fresh)).isEqualTo(old)
    }

    @Test fun bgAgeIsAlwaysOnTheMetaLine() {
        // fresh / aging: arrow + delta + age; old without a slot owner: the age alone; W2 / W3: the warning with the age
        assertThat(HaloFaceLogic.meta(BgAge.FRESH, null)).isEqualTo(HaloFaceLogic.Meta.TREND_DELTA_AGE)
        assertThat(HaloFaceLogic.meta(BgAge.AGING, Warning.LOOP_BAD)).isEqualTo(HaloFaceLogic.Meta.TREND_DELTA_AGE)
        assertThat(HaloFaceLogic.meta(BgAge.STALE, Warning.NO_DATA)).isEqualTo(HaloFaceLogic.Meta.WARNING)
        assertThat(HaloFaceLogic.meta(BgAge.FRESH, Warning.PHONE_LOST)).isEqualTo(HaloFaceLogic.Meta.WARNING)
        assertThat(HaloFaceLogic.meta(BgAge.NONE, Warning.WAITING)).isEqualTo(HaloFaceLogic.Meta.AGE_ONLY)
        // the status line takes every other owner; W2 / W3 live in the meta line, W10 is a glyph
        assertThat(HaloFaceLogic.secondaryOwner(Warning.LOOP_OFF)).isEqualTo(Warning.LOOP_OFF)
        assertThat(HaloFaceLogic.secondaryOwner(Warning.NO_DATA)).isNull()
        assertThat(HaloFaceLogic.secondaryOwner(Warning.PHONE_LOST_FRESH)).isNull()
        assertThat(HaloFaceLogic.secondaryOwner(null)).isNull()
    }

    @Test fun cobOnlyWithCarbsOnBoard() {
        assertThat(HaloFaceLogic.cobShown("0g")).isFalse()
        assertThat(HaloFaceLogic.cobShown("0 g")).isFalse()
        assertThat(HaloFaceLogic.cobShown("--g")).isFalse()
        assertThat(HaloFaceLogic.cobShown("")).isFalse()
        assertThat(HaloFaceLogic.cobShown(null)).isFalse()
        assertThat(HaloFaceLogic.cobShown("12g")).isTrue()
        assertThat(HaloFaceLogic.cobShown("12(5)g")).isTrue()
        assertThat(HaloFaceLogic.cobShown("0,5g")).isTrue()
    }

    @Test fun basalOnlyWhenATempBasalMatters() {
        // plain rates (profile or routine SMB zero temps) stay hidden
        assertThat(HaloFaceLogic.basalShown("0.85U/h", freshLow = false)).isFalse()
        assertThat(HaloFaceLogic.basalShown("0,00U/h", freshLow = false)).isFalse()
        assertThat(HaloFaceLogic.basalShown("100%", freshLow = false)).isFalse()
        assertThat(HaloFaceLogic.basalShown("-.--U/h", freshLow = true)).isFalse()
        assertThat(HaloFaceLogic.basalShown("", freshLow = true)).isFalse()
        // a TBR percentage, and a zero rate during a low (insulin withheld)
        assertThat(HaloFaceLogic.basalShown("150%", freshLow = false)).isTrue()
        assertThat(HaloFaceLogic.basalShown("0%", freshLow = false)).isTrue()
        assertThat(HaloFaceLogic.basalShown("0,00U/h", freshLow = true)).isTrue()
        assertThat(HaloFaceLogic.basalShown("0.85U/h", freshLow = true)).isFalse()
    }

    @Test fun noDataIsDashes() {
        assertThat(HaloFaceLogic.orDash(null)).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("")).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("---")).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("-")).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("IOB")).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("-.--U/h")).isEqualTo("--")
        assertThat(HaloFaceLogic.orDash("-0,1")).isEqualTo("-0,1")
        assertThat(HaloFaceLogic.orDash("9,4")).isEqualTo("9,4")
    }

    @Test fun secondaryLine() {
        assertThat(HaloFaceLogic.secondaryLine("5,6", 2, "0,00U/h")).isEqualTo("TT 5,6  ·  0,00 U/h")
        assertThat(HaloFaceLogic.secondaryLine("5,0 - 6,0", 0, "0,85 U/h")).isEqualTo("0,85 U/h")
        assertThat(HaloFaceLogic.secondaryLine("--", 2, "")).isEqualTo("--")
        assertThat(HaloFaceLogic.secondaryLine("5,5", 2, "-.--U/h")).isEqualTo("TT 5,5  ·  --")
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
        assertThat(HaloFaceLogic.valueUnit("1,44", "U")).isEqualTo("1,44" to "U")
        assertThat(HaloFaceLogic.valueUnit("IOB", "U")).isEqualTo("--" to "")
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
        val xs = (0L until 60L).map { HaloFaceLogic.burnInOffsetX(it) }
        val ys = (0L until 60L).map { HaloFaceLogic.burnInOffsetY(it) }
        assertThat(xs.all { kotlin.math.abs(it) <= 4f }).isTrue()
        assertThat(ys.all { kotlin.math.abs(it) <= 4f }).isTrue()
        assertThat(xs.zip(ys).toSet().size).isGreaterThan(10)
    }

    @Test fun firstStartOnlyBeforeAnyData() {
        assertThat(HaloFaceLogic.firstStart(0L, -1L, "IOB")).isTrue()
        assertThat(HaloFaceLogic.firstStart(0L, 0L, "")).isTrue()
        assertThat(HaloFaceLogic.firstStart(1_000L, -1L, "IOB")).isFalse()
        assertThat(HaloFaceLogic.firstStart(0L, 1_000L, "IOB")).isFalse()
        assertThat(HaloFaceLogic.firstStart(0L, -1L, "0.50")).isFalse()
    }

    @Test fun firstStartHidesTheBolusBall() {
        val guard = app.aaps.wear.watchfaces.common.BolusRequestGuard(clock = { 1_000_000L })
        val input = app.aaps.wear.watchfaces.common.BolusRequestGuard.Input(
            prefOn = true, ambient = false, wearControl = true, amount = 0.5, maxBolus = 5.0, lastPhoneMessage = 0L, bgTimeStamp = 0L,
            firstStart = HaloFaceLogic.firstStart(0L, -1L, "IOB")
        )
        assertThat(guard.check(input).state).isEqualTo(app.aaps.wear.watchfaces.common.BolusRequestGuard.State.HIDDEN)
        assertThat(guard.check(input).reason).isEqualTo(app.aaps.wear.watchfaces.common.BolusRequestGuard.Reason.FIRST_START)
    }
}
