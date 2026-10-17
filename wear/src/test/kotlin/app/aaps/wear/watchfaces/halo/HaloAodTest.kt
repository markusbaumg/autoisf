package app.aaps.wear.watchfaces.halo

import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Caption
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.FaceState.Trend
import app.aaps.wear.watchfaces.common.MonoAod
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HaloAodTest {

    private fun model(
        hero: String = "6,4", range: Range = Range.IN, age: BgAge = BgAge.FRESH, trend: Trend = Trend.FLAT,
        caption: Caption = FaceState.caption(range), warning: FaceState.StatusText? = null
    ) = HaloAod.model(hero, range, age, trend, caption, warning, "2′", "vor %s")

    @Test fun freshReadingIsBgAndArrowOnly() {
        val m = model()
        assertThat(m.grey).isFalse()
        assertThat(m.arrowDeg).isEqualTo(90f)
        assertThat(m.arrowHeads).isEqualTo(1)
        assertThat(m.lineShown).isFalse()
        assertThat(model(trend = Trend.DOWN2).arrowHeads).isEqualTo(2)
    }

    @Test fun oldReadingIsGreyStruckWithoutArrow() {
        val w = FaceState.StatusText(FaceState.Warning.NO_DATA, "Keine Daten · 17′", FaceState.Level.ALERT)
        val m = model(range = Range.STALE, age = BgAge.STALE, warning = w)
        assertThat(m.grey).isTrue()
        assertThat(m.struck).isTrue()
        assertThat(m.arrowHeads).isEqualTo(0)
        assertThat(m.words).isEqualTo("Keine Daten · 17′")
    }

    @Test fun lowsAndVeryHighGetWords() {
        assertThat(model(hero = "2,6", range = Range.URGENT_LOW).badge).isEqualTo(MonoAod.Badge.URGENT_LOW)
        assertThat(model(hero = "3,4", range = Range.LOW).badge).isEqualTo(MonoAod.Badge.LOW)
        assertThat(model(hero = "22,2", range = Range.VERY_HIGH).badge).isEqualTo(MonoAod.Badge.VERY_HIGH)
    }

    @Test fun onlyAmbientWarningsAreShown() {
        val res = FaceState.StatusText(FaceState.Warning.RES_LOW, "Reservoir 20 U", FaceState.Level.WARN)
        assertThat(model(warning = res).words).isNull()
        val loop = FaceState.StatusText(FaceState.Warning.LOOP_BAD, "Loop 23′", FaceState.Level.ALERT)
        assertThat(model(warning = loop).words).isEqualTo("Loop 23′")
    }
}
