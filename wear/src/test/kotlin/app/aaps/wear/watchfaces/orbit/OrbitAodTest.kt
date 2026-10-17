package app.aaps.wear.watchfaces.orbit

import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.MonoAod
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Slot
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Tone
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Trend
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OrbitAodTest {

    private val de = OrbitFaceLogic.Labels()

    private fun model(hero: String = "6,4", range: Range = Range.IN, age: Long = 2, trend: Trend = Trend.FLAT, slot: Slot? = null): MonoAod.Model {
        val row = OrbitFaceLogic.deltaRow("+0,1", age, de)
        return OrbitAod.model(hero, range, OrbitFaceLogic.bgAge(age), trend, FaceState.caption(range), slot, row.agePart, "vor %s")
    }

    @Test fun freshInRangeHasNoLine() {
        val m = model()
        assertThat(m.grey).isFalse()
        assertThat(m.arrowHeads).isEqualTo(1)
        assertThat(m.lineShown).isFalse()
        assertThat(model(trend = Trend.NONE).arrowHeads).isEqualTo(0)
    }

    @Test fun rangesInWords() {
        assertThat(model(hero = "2,6", range = Range.URGENT_LOW, trend = Trend.DOWN2).badge).isEqualTo(MonoAod.Badge.URGENT_LOW)
        assertThat(model(hero = "3,4", range = Range.LOW).badge).isEqualTo(MonoAod.Badge.LOW)
        assertThat(model(hero = "22,2", range = Range.VERY_HIGH).badge).isEqualTo(MonoAod.Badge.VERY_HIGH)
        assertThat(model(hero = "12,4", range = Range.HIGH).badge).isNull()
    }

    @Test fun staleIsGreyStruckAndSaysHowOld() {
        val m = model(range = Range.STALE, age = 17, slot = Slot(3, "Keine Daten · 17′", Tone.ALERT))
        assertThat(m.grey).isTrue()
        assertThat(m.struck).isTrue()
        assertThat(m.arrowHeads).isEqualTo(0)
        assertThat(m.words).isEqualTo("Keine Daten · 17′")
        assertThat(model(range = Range.STALE, age = 17).words).isEqualTo("vor 17′")
    }

    @Test fun agingShowsTheAge() {
        assertThat(model(age = 8).words).isEqualTo("vor 8′")
    }

    @Test fun onlyW1ToW6ReachTheLine() {
        assertThat(model(slot = Slot(5, "Loop aus", Tone.WARN)).words).isEqualTo("Loop aus")
        val both = model(hero = "2,6", range = Range.URGENT_LOW, slot = Slot(6, "Loop 23′", Tone.ALERT))
        assertThat(both.badge).isEqualTo(MonoAod.Badge.URGENT_LOW)
        assertThat(both.words).isEqualTo("Loop 23′")
        assertThat(model(slot = Slot(7, "Reservoir 5 U", Tone.ALERT)).words).isNull()
        assertThat(model(slot = Slot(10, "", Tone.GLYPH)).words).isNull()
    }
}
