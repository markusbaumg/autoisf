package app.aaps.wear.watchfaces.orbit

import app.aaps.wear.watchfaces.common.AodStyle
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Slot
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Tone
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Trend
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OrbitAodTest {

    private val de = OrbitFaceLogic.Labels()

    private fun model(
        hero: String = "6,4", range: Range = Range.IN, age: Long = 2, trend: Trend = Trend.FLAT, slot: Slot? = null, delta: String = "+0,1"
    ): OrbitAod.Model {
        val bgAge = OrbitFaceLogic.bgAge(age)
        return OrbitAod.model(hero, range, bgAge, trend, "14:27", FaceState.caption(range), slot, OrbitFaceLogic.deltaRow(delta, age, de), delta)
    }

    @Test fun styleValuesMapToOrbitLooks() {
        assertThat(OrbitAod.look(AodStyle.DEFAULT)).isEqualTo(OrbitAod.Look.BG_TIME)
        assertThat(OrbitAod.look(AodStyle.fromPref("minimal"))).isEqualTo(OrbitAod.Look.BG_ONLY)
        assertThat(OrbitAod.look(AodStyle.fromPref("glance"))).isEqualTo(OrbitAod.Look.BG_DELTA)
        assertThat(OrbitAod.look(AodStyle.fromPref(null))).isEqualTo(OrbitAod.Look.BG_TIME)
    }

    @Test fun freshInRangeHasNoLine() {
        val m = model()
        assertThat(m.hue).isEqualTo(OrbitAod.Hue.IN)
        assertThat(m.struck).isFalse()
        assertThat(m.trend).isEqualTo(Trend.FLAT)
        assertThat(m.lineShown).isFalse()
        assertThat(m.delta).isEqualTo("+0,1")
    }

    @Test fun urgentLowAndVeryHighSayItInWords() {
        val ul = model(hero = "2,6", range = Range.URGENT_LOW, trend = Trend.DOWN2)
        assertThat(ul.badge).isEqualTo("LOW")
        assertThat(ul.badgeBoxed).isTrue()
        assertThat(ul.hue).isEqualTo(OrbitAod.Hue.LOW)
        val low = model(hero = "3,4", range = Range.LOW)
        assertThat(low.badge).isEqualTo("LOW")
        assertThat(low.badgeBoxed).isFalse()
        val vh = model(hero = "22,2", range = Range.VERY_HIGH)
        assertThat(vh.badge).isEqualTo("HIGH")
        assertThat(model(hero = "12,4", range = Range.HIGH).badge).isNull()
    }

    @Test fun staleIsGreyStruckWithoutArrowAndSaysHowOld() {
        val m = model(range = Range.STALE, age = 17, slot = Slot(3, "Keine Daten · 17′", Tone.ALERT))
        assertThat(m.hue).isEqualTo(OrbitAod.Hue.STALE)
        assertThat(m.struck).isTrue()
        assertThat(m.trend).isEqualTo(Trend.NONE)
        assertThat(m.badge).isNull()
        assertThat(m.warning).isEqualTo("Keine Daten · 17′")
        assertThat(m.warningLevel).isEqualTo(OrbitAod.Level.ALERT)
        assertThat(m.ageAmber).isFalse()
    }

    @Test fun agingShowsTheAmberAge() {
        val m = model(age = 8)
        assertThat(m.ageAmber).isTrue()
        assertThat(m.age).isEqualTo("8′")
        assertThat(m.lineShown).isTrue()
    }

    @Test fun loopWarningsAndUrgentLowShareTheOneLine() {
        val off = model(slot = Slot(5, "Loop aus", Tone.WARN))
        assertThat(off.warning).isEqualTo("Loop aus")
        assertThat(off.warningLevel).isEqualTo(OrbitAod.Level.WARN)
        val both = model(hero = "2,6", range = Range.URGENT_LOW, slot = Slot(6, "Loop 23′", Tone.ALERT))
        assertThat(both.badge).isEqualTo("LOW")
        assertThat(both.warning).isEqualTo("Loop 23′")
        // W7+ (reservoir, phone battery) and the fresh phone-lost glyph never reach the always-on line
        assertThat(model(slot = Slot(7, "Reservoir 5 U", Tone.ALERT)).warning).isNull()
        assertThat(model(slot = Slot(10, "", Tone.GLYPH)).warning).isNull()
    }

    @Test fun noReadingShowsDashesWithoutBadge() {
        val m = model(hero = "---", range = Range.STALE, slot = Slot(1, "Warte auf AAPS", Tone.INFO))
        assertThat(m.struck).isFalse()
        assertThat(m.hue).isEqualTo(OrbitAod.Hue.STALE)
        assertThat(m.warning).isEqualTo("Warte auf AAPS")
    }

    @Test fun mgdlDeltaPassesThrough() {
        assertThat(model(hero = "224", range = Range.HIGH, delta = "+7").delta).isEqualTo("+7")
    }

    @Test fun sparkPointsNormalised() {
        val now = 10_000_000L
        val ts = LongArray(13) { now - (12 - it) * 5 * 60_000L }
        val v = DoubleArray(13) { 100.0 + it }
        val p = OrbitAod.sparkPoints(ts, v, now)
        assertThat(p.size).isEqualTo(26)
        assertThat(p[0]).isEqualTo(0f)
        assertThat(p[24]).isEqualTo(1f)
        // a calm hour keeps the minimum span: the line stays near the middle
        assertThat(p[1]).isGreaterThan(0.3f)
        assertThat(p[25]).isLessThan(0.7f)
        assertThat(OrbitAod.sparkPoints(LongArray(1) { now }, DoubleArray(1) { 100.0 }, now)).isEmpty()
    }
}
