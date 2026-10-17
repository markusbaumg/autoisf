package app.aaps.wear.watchfaces.glass

import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.MonoAod
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Trend
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlassAodTest {

    private fun model(
        hero: String = "6,4", range: Range = Range.IN, age: BgAge = BgAge.FRESH, trend: Trend = Trend.FLAT,
        owner: FaceState.Warning? = null, text: String? = null, ageText: String = "2′"
    ) = GlassAod.model(hero, range, age, trend, FaceState.caption(range), owner, text, ageText, "vor %s")

    @Test fun arrowsPointClockwiseFromTwelve() {
        assertThat(GlassAod.arrowOf(Trend.FLAT)).isEqualTo(90f to 1)
        assertThat(GlassAod.arrowOf(Trend.UP)).isEqualTo(0f to 1)
        assertThat(GlassAod.arrowOf(Trend.UP45)).isEqualTo(45f to 1)
        assertThat(GlassAod.arrowOf(Trend.DOWN45)).isEqualTo(135f to 1)
        assertThat(GlassAod.arrowOf(Trend.DOWN2)).isEqualTo(180f to 2)
        assertThat(GlassAod.arrowOf(Trend.NONE).second).isEqualTo(0)
    }

    @Test fun freshInRangeHasNoLine() {
        assertThat(model().lineShown).isFalse()
        assertThat(model().grey).isFalse()
    }

    @Test fun rangesAndWarningsInWords() {
        assertThat(model(hero = "2,6", range = Range.URGENT_LOW).badge).isEqualTo(MonoAod.Badge.URGENT_LOW)
        assertThat(model(hero = "3,4", range = Range.LOW).badge).isEqualTo(MonoAod.Badge.LOW)
        assertThat(model(hero = "22,2", range = Range.VERY_HIGH).badge).isEqualTo(MonoAod.Badge.VERY_HIGH)
        assertThat(model(owner = FaceState.Warning.LOOP_BAD, text = "Loop 23′").words).isEqualTo("Loop 23′")
        assertThat(model(owner = FaceState.Warning.RES_LOW, text = "Reservoir 5 U").words).isNull()
    }

    @Test fun staleIsGreyStruckAndSaysHowOld() {
        val m = model(range = Range.STALE, age = BgAge.STALE, ageText = "17′")
        assertThat(m.grey).isTrue()
        assertThat(m.struck).isTrue()
        assertThat(m.arrowHeads).isEqualTo(0)
        assertThat(m.words).isEqualTo("vor 17′")
    }
}
