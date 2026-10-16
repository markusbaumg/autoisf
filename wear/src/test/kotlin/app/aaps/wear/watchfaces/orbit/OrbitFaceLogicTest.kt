package app.aaps.wear.watchfaces.orbit

import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Freshness
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.LoopWord
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.MoonKind
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Range
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Ring
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OrbitFaceLogicTest {

    private val de = OrbitFaceLogic.Labels()
    private val now = 1_760_000_000_000L
    private fun min(m: Long) = m * 60_000L

    // ------------------------------------------------------------ stale thresholds

    @Test fun freshnessThresholds() {
        assertThat(OrbitFaceLogic.freshness(null)).isEqualTo(Freshness.NONE)
        assertThat(OrbitFaceLogic.freshness(0)).isEqualTo(Freshness.FRESH)
        assertThat(OrbitFaceLogic.freshness(5)).isEqualTo(Freshness.FRESH)
        assertThat(OrbitFaceLogic.freshness(6)).isEqualTo(Freshness.AGE_WARN)
        assertThat(OrbitFaceLogic.freshness(11)).isEqualTo(Freshness.AGE_WARN)
        assertThat(OrbitFaceLogic.freshness(12)).isEqualTo(Freshness.OLD)
        assertThat(OrbitFaceLogic.freshness(15)).isEqualTo(Freshness.OLD)
        assertThat(OrbitFaceLogic.freshness(16)).isEqualTo(Freshness.STRUCK)
        assertThat(OrbitFaceLogic.isOld(Freshness.AGE_WARN)).isFalse()
        assertThat(OrbitFaceLogic.isOld(Freshness.OLD)).isTrue()
        assertThat(OrbitFaceLogic.isOld(Freshness.STRUCK)).isTrue()
        assertThat(OrbitFaceLogic.isOld(Freshness.NONE)).isTrue()
    }

    @Test fun bgAge() {
        assertThat(OrbitFaceLogic.bgAgeMinutes(0L, now)).isNull()
        assertThat(OrbitFaceLogic.bgAgeMinutes(now - 61_000L, now)).isEqualTo(1L)
        assertThat(OrbitFaceLogic.bgAgeMinutes(now + 30_000L, now)).isEqualTo(0L)
    }

    @Test fun rangeFromPhoneLevel() {
        assertThat(OrbitFaceLogic.range(0)).isEqualTo(Range.IN)
        assertThat(OrbitFaceLogic.range(1)).isEqualTo(Range.HIGH)
        assertThat(OrbitFaceLogic.range(-1)).isEqualTo(Range.LOW)
    }

    @Test fun deltaRowAndPill() {
        val fresh = OrbitFaceLogic.deltaRow("+0.1", 1)
        assertThat(fresh.deltaPart + fresh.agePart).isEqualTo("+0.1 · 1'")
        assertThat(fresh.ageAmber).isFalse()
        assertThat(OrbitFaceLogic.deltaRow("+0.1", 5).ageAmber).isFalse()
        assertThat(OrbitFaceLogic.deltaRow("+0.1", 6).ageAmber).isTrue()
        // mg/dL delta strings pass through unchanged
        val mg = OrbitFaceLogic.deltaRow("-12", 3)
        assertThat(mg.deltaPart + mg.agePart).isEqualTo("-12 · 3'")
        assertThat(OrbitFaceLogic.oldPillText(17, de)).isEqualTo("17' alt")
        assertThat(OrbitFaceLogic.oldPillText(null, de)).isEqualTo("keine Daten")
        assertThat(OrbitFaceLogic.oldAmbientText(17, de)).isEqualTo("– – 17' alt")
    }

    // ------------------------------------------------------------ balls

    @Test fun loopBall() {
        assertThat(OrbitFaceLogic.loopBall(now, "", now, de)).isEqualTo(OrbitFaceLogic.Ball("0'", "LOOP", Ring.GREEN))
        assertThat(OrbitFaceLogic.loopBall(now - min(5), "", now, de).ring).isEqualTo(Ring.GREEN)
        assertThat(OrbitFaceLogic.loopBall(now - min(6), "", now, de).ring).isEqualTo(Ring.AMBER)
        assertThat(OrbitFaceLogic.loopBall(now - min(14), "", now, de).ring).isEqualTo(Ring.AMBER)
        val red = OrbitFaceLogic.loopBall(now - min(23), "", now, de)
        assertThat(red.value).isEqualTo("23'")
        assertThat(red.ring).isEqualTo(Ring.RED)
        assertThat(OrbitFaceLogic.loopBall(now - min(130), "", now, de).value).isEqualTo("2h")
        // never looped (pumpcontrol, first start): unknown is grey, never red
        assertThat(OrbitFaceLogic.loopBall(-1L, "", now, de)).isEqualTo(OrbitFaceLogic.Ball("--", "LOOP", Ring.DIM))
        // loop disabled: the phone sends a non-empty status text
        val off = OrbitFaceLogic.loopBall(now, "Loop Disabled\n", now, de)
        assertThat(off).isEqualTo(OrbitFaceLogic.Ball("AUS", "LOOP", Ring.RED, word = true))
    }

    @Test fun loopWords() {
        assertThat(OrbitFaceLogic.loopBall(now, "", now, de, LoopWord.SUSPENDED).value).isEqualTo("PAUSE")
        assertThat(OrbitFaceLogic.loopBall(now, "", now, de, LoopWord.DISCONNECTED).value).isEqualTo("GETR.")
        val open = OrbitFaceLogic.loopBall(now, "", now, de, LoopWord.OPEN)
        assertThat(open.value).isEqualTo("OFFEN")
        assertThat(open.ring).isEqualTo(Ring.AMBER)
        assertThat(open.word).isTrue()
    }

    @Test fun basalBall() {
        assertThat(OrbitFaceLogic.basalBall("0.85 U/h", tbr = false)).isEqualTo(OrbitFaceLogic.Ball("0.85", "BASAL", Ring.NEUTRAL))
        assertThat(OrbitFaceLogic.basalBall("0.00 U/h", tbr = true)).isEqualTo(OrbitFaceLogic.Ball("0.00", "TBR", Ring.AMBER))
        assertThat(OrbitFaceLogic.basalBall("0,00 U/h", tbr = true).ring).isEqualTo(Ring.AMBER)
        assertThat(OrbitFaceLogic.basalBall("1.20 U/h", tbr = true)).isEqualTo(OrbitFaceLogic.Ball("1.20", "TBR", Ring.TEAL))
        assertThat(OrbitFaceLogic.basalBall("120%", tbr = true)).isEqualTo(OrbitFaceLogic.Ball("120%", "TBR", Ring.TEAL))
        assertThat(OrbitFaceLogic.basalBall("0%", tbr = true).ring).isEqualTo(Ring.AMBER)
        assertThat(OrbitFaceLogic.basalBall("", tbr = false).value).isEqualTo("--")
        assertThat(OrbitFaceLogic.basalBall("-.--U/h", tbr = false).value).isEqualTo("--")
        assertThat(OrbitFaceLogic.basalBall("0,85 U/h", tbr = false).value).isEqualTo("0,85")
    }

    @Test fun tbrRunning() {
        val starts = longArrayOf(now - min(60), now - min(20))
        val ends = longArrayOf(now - min(30), now + min(5))
        assertThat(OrbitFaceLogic.tbrRunning(starts, ends, now)).isTrue()
        assertThat(OrbitFaceLogic.tbrRunning(starts, ends, now + min(6))).isFalse()
        assertThat(OrbitFaceLogic.tbrRunning(LongArray(0), LongArray(0), now)).isFalse()
    }

    @Test fun cobIobTt() {
        assertThat(OrbitFaceLogic.cobBall("0g")).isEqualTo(OrbitFaceLogic.Ball("0g", "COB", Ring.DIM))
        assertThat(OrbitFaceLogic.cobBall("12g").ring).isEqualTo(Ring.AMBER)
        assertThat(OrbitFaceLogic.cobBall("12(3)g").ring).isEqualTo(Ring.AMBER)
        assertThat(OrbitFaceLogic.cobBall("--g")).isEqualTo(OrbitFaceLogic.Ball("--", "COB", Ring.DIM))
        assertThat(OrbitFaceLogic.cobBall("20(30)g").value).isEqualTo("20(30)g")
        assertThat(OrbitFaceLogic.iobBall("2.53")).isEqualTo(OrbitFaceLogic.Ball("2.53", "IOB", Ring.CYAN))
        assertThat(OrbitFaceLogic.iobBall("-0.12").ring).isEqualTo(Ring.CYAN)
        assertThat(OrbitFaceLogic.iobBall("").value).isEqualTo("--")
        assertThat(OrbitFaceLogic.iobBall("IOB").value).isEqualTo("--")       // placeholder before the first status
        assertThat(OrbitFaceLogic.iobBall("1,25").value).isEqualTo("1,25")    // phone locale kept
        assertThat(OrbitFaceLogic.ttBall("5.6", 2, de)).isEqualTo(OrbitFaceLogic.Ball("5.6", "TT", Ring.VIOLET))
        assertThat(OrbitFaceLogic.ttBall("101", 2, de).value).isEqualTo("101")
        assertThat(OrbitFaceLogic.ttBall("5.0 - 6.0", 0, de)).isEqualTo(OrbitFaceLogic.Ball("5.0-6.0", "Profil", Ring.DIM))
        assertThat(OrbitFaceLogic.ttBall("6.0", 1, de).ring).isEqualTo(Ring.DIM)
    }

    // ------------------------------------------------------------ warnings

    @Test fun warningsPriority() {
        assertThat(OrbitFaceLogic.warning(150.0, "80", false, de)).isNull()
        assertThat(OrbitFaceLogic.warning(0.0, "80", false, de)).isNull() // unknown reservoir
        assertThat(OrbitFaceLogic.warning(12.4, "15", true, de)).isEqualTo("Res 12U")
        assertThat(OrbitFaceLogic.warning(50.0, "15", true, de)).isEqualTo("Akku 15%")
        assertThat(OrbitFaceLogic.warning(50.0, "20", false, de)).isNull()
        assertThat(OrbitFaceLogic.warning(50.0, "", true, de)).isEqualTo("Pumpe Akku")
        assertThat(OrbitFaceLogic.warning(50.0, "x", false, de)).isNull()
    }

    // ------------------------------------------------------------ time and date

    @Test fun germanDate() {
        assertThat(OrbitFaceLogic.dateString(2025, 10, 17)).isEqualTo("Fr 17. Okt")
        assertThat(OrbitFaceLogic.dateString(2026, 10, 17)).isEqualTo("Sa 17. Okt")
        assertThat(OrbitFaceLogic.dateString(2026, 5, 1)).isEqualTo("Fr 1. Mai")
        assertThat(OrbitFaceLogic.dateString(2026, 3, 2)).isEqualTo("Mo 2. März")
        assertThat(OrbitFaceLogic.dateString(2026, 9, 30)).startsWith("Mi 30. Sep")
        assertThat(OrbitFaceLogic.dateString(2026, 9, 30)).doesNotContain(".S")
        assertThat(OrbitFaceLogic.dateString(2026, 12, 31)).isEqualTo("Do 31. Dez")
    }

    @Test fun timeFormat() {
        assertThat(OrbitFaceLogic.timeString(0, 40, true)).isEqualTo("00:40")
        assertThat(OrbitFaceLogic.timeString(13, 5, true)).isEqualTo("13:05")
        assertThat(OrbitFaceLogic.timeString(0, 40, false)).isEqualTo("12:40")
        assertThat(OrbitFaceLogic.timeString(13, 5, false)).isEqualTo("1:05")
    }

    @Test fun burnInCycle() {
        val shifts = (0..4).map { OrbitFaceLogic.burnInShiftX(it) to OrbitFaceLogic.burnInShiftY(it) }
        assertThat(shifts).containsExactly(0f to 0f, 2f to 0f, 2f to 2f, 0f to 2f, 0f to 0f).inOrder()
    }

    // ------------------------------------------------------------ geometry, widths, moon

    @Test fun geometry() {
        assertThat(OrbitFaceLogic.pointX(168f, 0f)).isWithin(0.01f).of(225f)
        assertThat(OrbitFaceLogic.pointY(168f, 0f)).isWithin(0.01f).of(57f)
        assertThat(OrbitFaceLogic.pointX(168f, 45f)).isWithin(0.1f).of(343.8f)
        assertThat(OrbitFaceLogic.pointY(168f, 45f)).isWithin(0.1f).of(106.2f)
        assertThat(OrbitFaceLogic.pointX(168f, 270f)).isWithin(0.01f).of(57f)
        assertThat(OrbitFaceLogic.pointY(168f, 180f)).isWithin(0.01f).of(393f)
        // round-safe: the bolus button corner stays inside r 205
        val corner = kotlin.math.hypot(225f - (57f - 33f) - 16f * (1 - 0.7071f), 33f - 16f * (1 - 0.7071f))
        assertThat(corner).isLessThan(205f)
    }

    @Test fun gapWidths() {
        // "00:40" at 30 px is about 80 px wide -> (80 + 20) / 2 / 168 rad = 17 deg
        assertThat(OrbitFaceLogic.gapHalfDeg(80f)).isWithin(0.1f).of(17.05f)
        // worst-case date line "Mi 30. Sep · Akku 15%" (~200 px) is capped before it reaches the balls at 135 / 225 deg
        assertThat(OrbitFaceLogic.gapHalfDeg(400f)).isEqualTo(OrbitFaceLogic.MAX_GAP_DEG)
        assertThat(180f - OrbitFaceLogic.MAX_GAP_DEG).isGreaterThan(OrbitFaceLogic.DEG_BASAL + OrbitFaceLogic.BALL_HALF_DEG)
    }

    @Test fun worstCaseWidths() {
        // ball value: 22 px up to 56 px, 17 px bold beyond, shrink when even that is too wide
        assertThat(OrbitFaceLogic.ballValueSize(50f)).isEqualTo(22f)
        assertThat(OrbitFaceLogic.ballValueSize(70f)).isEqualTo(17f)    // "PAUSE", "10.55"
        val tiny = OrbitFaceLogic.ballValueSize(110f)                  // "5.0-6.0" style ranges
        assertThat(tiny).isLessThan(17f)
        assertThat(110f * tiny / 22f).isWithin(0.01f).of(OrbitFaceLogic.BALL_VALUE_HARD_MAX)
        // hero "388" mg/dL at 108 px bold is ~190 px: unchanged; a wider string shrinks to 200 px
        assertThat(OrbitFaceLogic.fitSize(190f, 108f, OrbitFaceLogic.HERO_MAX_WIDTH)).isEqualTo(108f)
        assertThat(OrbitFaceLogic.fitSize(250f, 108f, OrbitFaceLogic.HERO_MAX_WIDTH) * 250f / 108f).isWithin(0.01f).of(200f)
        // the hero plus arrow never reaches the IOB ball (left edge 359)
        val heroRight = 209f + OrbitFaceLogic.HERO_MAX_WIDTH / 2f
        assertThat(heroRight + 24f + 22f).isLessThan(OrbitFaceLogic.pointX(168f, 90f) - OrbitFaceLogic.BALL_R)
    }

    @Test fun moonPositions() {
        val gapT = 17f
        val gapD = 20f
        // in the time gap
        assertThat(OrbitFaceLogic.moon(0, gapT, gapD, false).kind).isEqualTo(MoonKind.HIDDEN)
        assertThat(OrbitFaceLogic.moon(57, gapT, gapD, false).kind).isEqualTo(MoonKind.HIDDEN)
        // in the date gap
        assertThat(OrbitFaceLogic.moon(30, gapT, gapD, false).kind).isEqualTo(MoonKind.HIDDEN)
        // free orbit
        val dot = OrbitFaceLogic.moon(40, gapT, gapD, false)
        assertThat(dot.kind).isEqualTo(MoonKind.DOT)
        assertThat(dot.deg).isEqualTo(240f)
        // 9 o'clock: free without the button, hidden behind it
        assertThat(OrbitFaceLogic.moon(45, gapT, gapD, false).kind).isEqualTo(MoonKind.DOT)
        assertThat(OrbitFaceLogic.moon(45, gapT, gapD, true).kind).isEqualTo(MoonKind.HIDDEN)
        // behind the TT ball at 315 deg: 00:52 -> 312 deg, rim highlight on the trailing side
        val rim = OrbitFaceLogic.moon(52, gapT, gapD, true)
        assertThat(rim.kind).isEqualTo(MoonKind.RIM)
        assertThat(rim.ball).isEqualTo(OrbitFaceLogic.IDX_TT)
        assertThat(rim.rimDeg).isLessThan(315f)
        assertThat(rim.rimDeg).isWithin(0.5f).of(315f - 3f / OrbitFaceLogic.BALL_OCCLUDE_DEG * 90f)
        // behind the LOOP ball at 45 deg (00:07 -> 42 deg)
        val loop = OrbitFaceLogic.moon(7, gapT, gapD, false)
        assertThat(loop.kind).isEqualTo(MoonKind.RIM)
        assertThat(loop.ball).isEqualTo(OrbitFaceLogic.IDX_LOOP)
        assertThat(loop.rimDeg).isWithin(0.01f).of(45f - 3f / OrbitFaceLogic.BALL_OCCLUDE_DEG * 90f)
        assertThat(OrbitFaceLogic.BALL_OCCLUDE_DEG).isWithin(0.1f).of(13.2f)
    }

    @Test fun angleDiffWraps() {
        assertThat(OrbitFaceLogic.angleDiff(350f, 10f)).isEqualTo(-20f)
        assertThat(OrbitFaceLogic.angleDiff(10f, 350f)).isEqualTo(20f)
        assertThat(OrbitFaceLogic.angleDiff(180f, 0f)).isEqualTo(180f)
    }

    // ------------------------------------------------------------ sparkline

    @Test fun sparkline() {
        assertThat(OrbitFaceLogic.sparkY(45.0)).isEqualTo(OrbitFaceLogic.SPARK_Y_BOTTOM)
        assertThat(OrbitFaceLogic.sparkY(252.0)).isEqualTo(OrbitFaceLogic.SPARK_Y_TOP)
        assertThat(OrbitFaceLogic.sparkY(400.0)).isEqualTo(OrbitFaceLogic.SPARK_Y_TOP)
        assertThat(OrbitFaceLogic.sparkY(20.0)).isEqualTo(OrbitFaceLogic.SPARK_Y_BOTTOM)
        // the 10 mmol/L (180 mg/dL) high line sits at about 35 % from the top
        val f = (OrbitFaceLogic.sparkY(180.0) - OrbitFaceLogic.SPARK_Y_TOP) / (OrbitFaceLogic.SPARK_Y_BOTTOM - OrbitFaceLogic.SPARK_Y_TOP)
        assertThat(f).isWithin(0.02f).of(0.35f)
        assertThat(OrbitFaceLogic.sparkClamp(30.0)).isEqualTo(-1)
        assertThat(OrbitFaceLogic.sparkClamp(300.0)).isEqualTo(1)
        assertThat(OrbitFaceLogic.sparkClamp(120.0)).isEqualTo(0)
        assertThat(OrbitFaceLogic.sparkX(now, now)).isEqualTo(OrbitFaceLogic.SPARK_X_NOW)
        assertThat(OrbitFaceLogic.sparkX(now - OrbitFaceLogic.SPARK_HISTORY_MS, now)).isWithin(0.01f).of(OrbitFaceLogic.SPARK_X0)
        assertThat(OrbitFaceLogic.predictionX(5)).isWithin(0.01f).of(OrbitFaceLogic.SPARK_X1)
    }
}
