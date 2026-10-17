package app.aaps.wear.watchfaces.orbit

import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.MonoAod
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Slot
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Trend

/**
 * Orbit always-on: the family's one black-and-white look ([MonoAod]): the BG and its arrow, and at most one grey line
 * of words. This object only turns Orbit's state into the shared [MonoAod.Model]; [MonoAod.Painter] draws it.
 * No ring, discs, chart, date or bolus button.
 */
object OrbitAod {

    /** Only the slot's W1-W6 words reach the line (W7+ and the phone-lost glyph wait for the interactive face). */
    fun model(
        hero: String, range: Range, bgAge: BgAge, trend: Trend, caption: FaceState.Caption, slot: Slot?, age: String, agoFormat: String
    ): MonoAod.Model {
        val warning = slot?.takeIf { it.rank in 1..6 && it.text.isNotEmpty() }?.text
        return MonoAod.model(hero, range, bgAge, caption, trend.deg, if (trend == Trend.NONE) 0 else trend.heads, warning, age, agoFormat)
    }
}
