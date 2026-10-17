package app.aaps.wear.watchfaces.common

import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Caption
import app.aaps.wear.watchfaces.common.FaceState.Level
import app.aaps.wear.watchfaces.common.FaceState.LoopHealth
import app.aaps.wear.watchfaces.common.FaceState.LoopMode
import app.aaps.wear.watchfaces.common.FaceState.LoopRun
import app.aaps.wear.watchfaces.common.FaceState.PhoneLink
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.FaceState.Trend
import app.aaps.wear.watchfaces.common.FaceState.Warning
import app.aaps.wear.watchfaces.common.FaceState.WarningInput
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class FaceStateTest {

    private val now = 100_000_000L
    private fun min(m: Long) = now - m * 60_000L

    @Test fun bgAgeClasses() {
        assertThat(FaceState.ageMinutes(0L, now)).isNull()
        assertThat(FaceState.ageMinutes(now + 120_000L, now)).isEqualTo(0L)   // clock skew
        assertThat(FaceState.bgAge(null)).isEqualTo(BgAge.NONE)
        assertThat(FaceState.bgAge(5)).isEqualTo(BgAge.FRESH)
        assertThat(FaceState.bgAge(6)).isEqualTo(BgAge.AGING)
        assertThat(FaceState.bgAge(11)).isEqualTo(BgAge.AGING)
        assertThat(FaceState.bgAge(12)).isEqualTo(BgAge.STALE)
        assertThat(FaceState.bgAge(29)).isEqualTo(BgAge.STALE)
        assertThat(FaceState.bgAge(30)).isEqualTo(BgAge.VERY_STALE)
        assertThat(FaceState.ageLevel(BgAge.FRESH)).isEqualTo(Level.NORMAL)
        assertThat(FaceState.ageLevel(BgAge.AGING)).isEqualTo(Level.WARN)
        assertThat(FaceState.ageLevel(BgAge.STALE)).isEqualTo(Level.ALERT)
        assertThat(FaceState.isStruck(BgAge.NONE)).isFalse()
        assertThat(FaceState.isOld(BgAge.NONE)).isTrue()
    }

    @Test fun rangeWithUrgentLevelsAndStaleBeatsRange() {
        assertThat(FaceState.range(120.0, 0, BgAge.FRESH, false)).isEqualTo(Range.IN)
        assertThat(FaceState.range(200.0, 1, BgAge.FRESH, false)).isEqualTo(Range.HIGH)
        assertThat(FaceState.range(250.0, 1, BgAge.FRESH, false)).isEqualTo(Range.HIGH)
        assertThat(FaceState.range(251.0, 1, BgAge.FRESH, false)).isEqualTo(Range.VERY_HIGH)
        assertThat(FaceState.range(65.0, -1, BgAge.FRESH, false)).isEqualTo(Range.LOW)
        assertThat(FaceState.range(65.0, -1, BgAge.AGING, true)).isEqualTo(Range.URGENT_LOW)
        assertThat(FaceState.range(54.0, -1, BgAge.FRESH, false)).isEqualTo(Range.LOW)
        assertThat(FaceState.range(53.9, -1, BgAge.FRESH, false)).isEqualTo(Range.URGENT_LOW)
        assertThat(FaceState.range(40.0, -1, BgAge.STALE, true)).isEqualTo(Range.STALE)
        assertThat(FaceState.range(300.0, 1, BgAge.NONE, false)).isEqualTo(Range.STALE)
        assertThat(FaceState.levelFromMarks(65.0, 70.0, 180.0)).isEqualTo(-1L)
        assertThat(FaceState.levelFromMarks(181.0, 0.0, 0.0)).isEqualTo(1L)
    }

    @Test fun captionWordsSoColourIsNeverTheOnlyCarrier() {
        assertThat(FaceState.caption(Range.LOW)).isEqualTo(Caption.LOW_WORD)
        assertThat(FaceState.caption(Range.URGENT_LOW)).isEqualTo(Caption.LOW_BADGE)
        assertThat(FaceState.caption(Range.VERY_HIGH)).isEqualTo(Caption.HIGH_WORD)
        assertThat(FaceState.caption(Range.HIGH)).isEqualTo(Caption.NONE)
        assertThat(FaceState.caption(Range.STALE)).isEqualTo(Caption.NONE)
    }

    @Test fun bgTextKeepsThePhoneString() {
        assertThat(FaceState.bgText("6,4", 115.0, 2)).isEqualTo("6,4")
        assertThat(FaceState.bgText("22,2", 400.0, 2)).isEqualTo("22,2+")
        assertThat(FaceState.bgText("6,4", 115.0, 60)).isEqualTo("---")
        assertThat(FaceState.bgText("6,4", 115.0, null)).isEqualTo("---")
        assertThat(FaceState.bgText("---", 0.0, 1)).isEqualTo("---")
    }

    @Test fun trendAndFastFall() {
        assertThat(FaceState.trend("⇊", -12.0)).isEqualTo(Trend.DOWN2)
        assertThat(FaceState.trend("X", -5.0)).isEqualTo(Trend.DOWN3)
        assertThat(FaceState.trend("X", 5.0)).isEqualTo(Trend.UP3)
        assertThat(FaceState.trend("??", 0.0)).isEqualTo(Trend.NONE)
        assertThat(FaceState.trend("→︎", 0.0)).isEqualTo(Trend.FLAT)
        assertThat(FaceState.fastFall(Trend.DOWN, -10.0)).isTrue()
        assertThat(FaceState.fastFall(Trend.DOWN, -9.0)).isFalse()
        assertThat(FaceState.fastRise(Trend.UP2, null)).isTrue()
    }

    @Test fun displayAndGermanNumbers() {
        assertThat(FaceState.display("IOB")).isEqualTo("--")
        assertThat(FaceState.display("-.--U/h")).isEqualTo("--")
        assertThat(FaceState.display(" 1,25 ")).isEqualTo("1,25")
        assertThat(FaceState.deltaText("0,1")).isEqualTo("+0,1")
        assertThat(FaceState.deltaText("-0,3")).isEqualTo("-0,3")
        assertThat(FaceState.deltaText("--")).isEqualTo("--")
        assertThat(FaceState.parseNumber("-0,35")).isEqualTo(-0.35)
        assertThat(FaceState.parseNumber("0.85U/h")).isEqualTo(0.85)
        assertThat(FaceState.spacedUnit("0,85U/h")).isEqualTo("0,85 U/h")
        assertThat(FaceState.spacedUnit("120%")).isEqualTo("120 %")
        assertThat(FaceState.spacedUnit("142U")).isEqualTo("142 U")
        assertThat(FaceState.spacedUnit("0,85 U/h")).isEqualTo("0,85 U/h")
        assertThat(FaceState.iobFraction("3,00")).isEqualTo(0.5f)
        assertThat(FaceState.iobFraction("-1,00")).isEqualTo(0f)
        assertThat(FaceState.bolusAmountText(0.5, Locale.GERMANY)).isEqualTo("+0,5")
        assertThat(FaceState.bolusAmountText(1.0, Locale.US)).isEqualTo("+1.0")
        assertThat(FaceState.basalTone("0,00U/h")).isEqualTo(FaceState.Tone.WARN)
        assertThat(FaceState.basalTone("0,00U/h", freshLow = true)).isEqualTo(FaceState.Tone.TXT2)
        assertThat(FaceState.basalTone("120%")).isEqualTo(FaceState.Tone.TBR)
    }

    @Test fun ageText() {
        val w = FaceState.Words()
        assertThat(FaceState.ageText(null, w)).isEqualTo("--")
        assertThat(FaceState.ageText(17, w)).isEqualTo("17′")
        assertThat(FaceState.ageText(185, w)).isEqualTo("3 Std")
        assertThat(FaceState.ageText(3 * 1440, w)).isEqualTo("3 T")
    }

    @Test fun loopRunAndHealth() {
        assertThat(FaceState.loopRun("", "1,20")).isEqualTo(LoopRun.RUNNING)
        assertThat(FaceState.loopRun("no status", "IOB")).isEqualTo(LoopRun.RUNNING)
        assertThat(FaceState.loopRun("Loop deaktiviert\n", "1,20")).isEqualTo(LoopRun.OFF)
        assertThat(FaceState.loopRun("Kein Profil", "")).isEqualTo(LoopRun.NO_PROFILE)
        assertThat(FaceState.loopTime(min(2), min(9))).isEqualTo(FaceState.LoopTime(min(2), true))
        assertThat(FaceState.loopTime(0L, min(9))).isEqualTo(FaceState.LoopTime(min(9), false))
        assertThat(FaceState.loopHealth(3, LoopRun.OFF, LoopMode.CLOSED)).isEqualTo(LoopHealth.OFF)
        assertThat(FaceState.loopHealth(30, LoopRun.RUNNING, LoopMode.OPEN)).isEqualTo(LoopHealth.OPEN)
        assertThat(FaceState.loopHealth(null, LoopRun.RUNNING, LoopMode.CLOSED)).isEqualTo(LoopHealth.NEVER)
        assertThat(FaceState.loopHealth(15, LoopRun.RUNNING, LoopMode.CLOSED)).isEqualTo(LoopHealth.BAD)
        assertThat(FaceState.loopHealth(15, LoopRun.RUNNING, LoopMode.CLOSED, realRun = false)).isEqualTo(LoopHealth.WARN)
        assertThat(FaceState.loopMode(listOf("LOOP_OPEN", "LOOP_LGS"), now, now)).isEqualTo(LoopMode.CLOSED)
        assertThat(FaceState.loopMode(listOf("LOOP_OPEN"), now, now)).isEqualTo(LoopMode.UNKNOWN)
    }

    @Test fun phoneLink() {
        assertThat(FaceState.phoneLink(true, min(2), now)).isEqualTo(PhoneLink.OK)
        assertThat(FaceState.phoneLink(true, min(13), now)).isEqualTo(PhoneLink.SILENT)
        assertThat(FaceState.phoneLink(false, min(2), now)).isEqualTo(PhoneLink.LOST_FRESH)
        assertThat(FaceState.phoneLink(false, min(14), now)).isEqualTo(PhoneLink.LOST_STALE)
        assertThat(FaceState.phoneLink(PhoneReach.UNKNOWN, min(2), now)).isEqualTo(PhoneLink.OK)
    }

    private fun input(
        link: PhoneLink = PhoneLink.OK, age: BgAge = BgAge.FRESH, range: Range = Range.IN, loop: LoopHealth = LoopHealth.OK,
        resLevel: Int = 0, battLevel: Int = 1, firstStart: Boolean = false, noProfile: Boolean = false
    ) = WarningInput(firstStart, link, age, range, noProfile, loop, "5U", resLevel, "8", battLevel)

    @Test fun warningOrderW1toW10() {
        assertThat(FaceState.warnings(input())).isEmpty()
        assertThat(FaceState.warnings(input(firstStart = true, link = PhoneLink.LOST_STALE, age = BgAge.NONE)).first()).isEqualTo(Warning.WAITING)
        assertThat(FaceState.warnings(input(link = PhoneLink.LOST_STALE, age = BgAge.STALE, loop = LoopHealth.BAD)))
            .containsExactly(Warning.PHONE_LOST, Warning.LOOP_BAD).inOrder()
        assertThat(FaceState.warnings(input(age = BgAge.STALE)).first()).isEqualTo(Warning.NO_DATA)
        assertThat(FaceState.warnings(input(link = PhoneLink.SILENT)).first()).isEqualTo(Warning.NO_DATA)
        assertThat(FaceState.warnings(input(loop = LoopHealth.OFF, resLevel = 2)))
            .containsExactly(Warning.LOOP_OFF, Warning.RES_CRITICAL).inOrder()
        assertThat(FaceState.warnings(input(battLevel = 0, resLevel = 1, link = PhoneLink.LOST_FRESH)))
            .containsExactly(Warning.PHONE_BATTERY, Warning.RES_LOW, Warning.PHONE_LOST_FRESH).inOrder()
    }

    @Test fun freshLowSuppressesMinorWarningsButNeverW1toW6() {
        assertThat(FaceState.warnings(input(range = Range.URGENT_LOW, resLevel = 2, battLevel = 0))).isEmpty()
        assertThat(FaceState.warnings(input(range = Range.LOW, loop = LoopHealth.BAD, resLevel = 2))).containsExactly(Warning.LOOP_BAD)
    }

    @Test fun ambientKeepsW1toW6() {
        val w = FaceState.warnings(input(resLevel = 2))
        assertThat(FaceState.owner(w, ambient = false)).isEqualTo(Warning.RES_CRITICAL)
        assertThat(FaceState.owner(w, ambient = true)).isNull()
    }

    @Test fun statusTexts() {
        val w = FaceState.Words()
        assertThat(FaceState.statusText(Warning.NO_DATA, w, 3, 17, 2, "", "")!!.text).isEqualTo("Keine Daten · 17′")
        assertThat(FaceState.statusText(Warning.PHONE_LOST, w, 14, 14, 2, "", "")!!.text).isEqualTo("Telefon getrennt · 14′")
        assertThat(FaceState.statusText(Warning.LOOP_BAD, w, 0, 1, 23, "", "")!!.level).isEqualTo(Level.ALERT)
        assertThat(FaceState.statusText(Warning.RES_CRITICAL, w, 0, 1, 2, "5U", "")!!.text).isEqualTo("Reservoir 5 U")
        assertThat(FaceState.statusText(Warning.PHONE_BATTERY, w, 0, 1, 2, "", "8")!!.text).isEqualTo("Tel 8 %")
        assertThat(FaceState.statusText(Warning.PHONE_BATTERY, w, 0, 1, 2, "", "8")!!.level).isEqualTo(Level.ALERT)
        assertThat(FaceState.statusText(Warning.PHONE_LOST_FRESH, w, 0, 1, 2, "", "")).isNull()
        assertThat(FaceState.loopNeutral(Warning.NO_DATA)).isTrue()
        assertThat(FaceState.loopNeutral(Warning.LOOP_OFF)).isFalse()
    }

    @Test fun burnInShiftStaysWithinFourPx() {
        val xs = (0L until 500L).map { FaceState.burnInShift(it, 0) }
        assertThat(xs.all { it in -4..4 }).isTrue()
        assertThat(xs.toSet().size).isEqualTo(9)
    }
}
