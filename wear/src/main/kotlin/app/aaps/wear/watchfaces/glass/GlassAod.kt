package app.aaps.wear.watchfaces.glass

import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.MonoAod
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Trend

/**
 * Glass always-on: the family's one black-and-white look ([MonoAod]): the time, the BG and its arrow, at most one grey line
 * of words and the watch battery. This object only turns Glass's state into the shared [MonoAod.Model]; [MonoAod.Painter] draws it.
 * No card, chart, pills or ball: the bolus ball is never drawn in ambient.
 */
object GlassAod {

    /** Glass's trend angle (0 = right, 90 = up) as degrees clockwise from 12 o'clock, and the number of heads. */
    fun arrowOf(t: Trend): Pair<Float, Int> =
        if (t == Trend.NONE) 0f to 0 else (90f - t.angle) to (if (t.double) 2 else 1)

    /**
     * [owner] is the slot owner and [ownerText] its words as the footer / meta row says them; only W1-W6 reach the
     * always-on line (a reservoir or phone battery warning waits for the interactive face).
     */
    fun model(
        hero: String, range: Range, bgAge: BgAge, trend: Trend, caption: FaceState.Caption,
        owner: FaceState.Warning?, ownerText: String?, age: String, agoFormat: String
    ): MonoAod.Model {
        val (deg, heads) = arrowOf(trend)
        val warning = ownerText?.takeIf { owner != null && owner.ambient && it.isNotBlank() }
        return MonoAod.model(hero, range, bgAge, caption, deg, heads, warning, age, agoFormat)
    }
}
