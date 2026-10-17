package app.aaps.wear.watchfaces.halo

import app.aaps.wear.watchfaces.common.AodStyle
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Caption
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.FaceState.Trend
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HaloAodTest {

    private fun model(
        hero: String = "6,4", range: Range = Range.IN, age: BgAge = BgAge.FRESH, trend: Trend = Trend.FLAT,
        caption: Caption = Caption.NONE, warning: FaceState.StatusText? = null, sgv: Double = 115.0
    ) = HaloAod.model(hero, range, age, trend, "14:27", caption, warning, "+0,1", "2′", 70.0, 180.0, sgv)

    @Test fun stylesMapToTheFamilyLooks() {
        assertThat(HaloAod.look(AodStyle.OUTLINE)).isEqualTo(HaloAod.Look.BG_TIME)
        assertThat(HaloAod.look(AodStyle.MINIMAL)).isEqualTo(HaloAod.Look.BG_ONLY)
        assertThat(HaloAod.look(AodStyle.GLANCE)).isEqualTo(HaloAod.Look.RING_DELTA)
        assertThat(HaloAod.look(AodStyle.DEFAULT)).isEqualTo(HaloAod.Look.BG_TIME)
    }

    @Test fun freshReadingShowsHueArrowAndDot() {
        val m = model()
        assertThat(m.hue).isEqualTo(HaloAod.Hue.IN)
        assertThat(m.struck).isFalse()
        assertThat(m.trend).isEqualTo(Trend.FLAT)
        assertThat(m.badge).isNull()
        assertThat(m.warning).isNull()
        assertThat(m.bgAngle).isWithin(0.01f).of(HaloFaceLogic.angle(115.0))
    }

    @Test fun oldReadingIsGreyStruckWithoutArrowOrDot() {
        val w = FaceState.StatusText(FaceState.Warning.NO_DATA, "Keine Daten · 17′", FaceState.Level.ALERT)
        val m = model(range = Range.STALE, age = BgAge.STALE, warning = w)
        assertThat(m.hue).isEqualTo(HaloAod.Hue.STALE)
        assertThat(m.struck).isTrue()
        assertThat(m.trend).isEqualTo(Trend.NONE)
        assertThat(m.bgAngle).isNull()
        assertThat(m.warning).isEqualTo("Keine Daten · 17′")
        assertThat(m.warningLevel).isEqualTo(HaloAod.Level.ALERT)
    }

    @Test fun dangerLevelsGetAPill() {
        assertThat(model(hero = "2,6", range = Range.URGENT_LOW, caption = Caption.LOW_BADGE, sgv = 47.0).badge).isEqualTo(HaloAod.Badge.LOW)
        assertThat(model(hero = "22,2", range = Range.VERY_HIGH, caption = Caption.HIGH_WORD, sgv = 400.0).badge).isEqualTo(HaloAod.Badge.HIGH)
        // a plain low says it by colour + arrow
        val low = model(hero = "3,4", range = Range.LOW, caption = Caption.LOW_WORD, sgv = 61.0)
        assertThat(low.badge).isNull()
        assertThat(low.hue).isEqualTo(HaloAod.Hue.LOW)
    }

    @Test fun onlyAmbientWarningsAreShown() {
        val res = FaceState.StatusText(FaceState.Warning.RES_LOW, "Reservoir 20 U", FaceState.Level.WARN)
        assertThat(model(warning = res).warning).isNull()
        val loop = FaceState.StatusText(FaceState.Warning.LOOP_BAD, "Loop 23′", FaceState.Level.ALERT)
        assertThat(model(warning = loop).warning).isEqualTo("Loop 23′")
    }

    @Test fun agingReadingShowsTheAmberAge() {
        assertThat(model(age = BgAge.AGING).ageAmber).isTrue()
        assertThat(model(age = BgAge.FRESH).ageAmber).isFalse()
        assertThat(model(hero = FaceState.NO_BG, age = BgAge.NONE, sgv = 0.0).ageAmber).isFalse()
    }

    @Test fun arcsKeepTheTwelveOClockGap() {
        val pieces = HaloAod.bandSweeps(HaloFaceLogic.RING_START, HaloFaceLogic.RING_START + HaloFaceLogic.RING_SWEEP)
        assertThat(pieces).hasSize(2)
        for ((start, sweep) in pieces) {
            val end = start + sweep
            assertThat(end <= 270f - HaloAod.BAND_GAP_HALF + 0.01f || start >= 270f + HaloAod.BAND_GAP_HALF - 0.01f).isTrue()
        }
        assertThat(pieces.sumOf { it.second.toDouble() }).isWithin(0.1).of(270.0 - 2 * HaloAod.BAND_GAP_HALF)
        // an arc entirely below the gap stays one piece
        assertThat(HaloAod.bandSweeps(150f, 200f)).containsExactly(150f to 50f)
    }

    @Test fun ringStaysOnTheGlassWithTheBurnInShift() {
        // 14 px bezel margin of the render harness, plus the 4 px burn-in shift
        assertThat(225f - (HaloAod.BAND_R + HaloAod.RING_DOT_R + FaceState.BURN_IN_MAX_SHIFT)).isAtLeast(14f)
    }
}
