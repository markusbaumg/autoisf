package app.aaps.wear.watchfaces.halo

import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.FaceState.Trend
import app.aaps.wear.watchfaces.common.MonoAod

/**
 * Halo always-on: the family's one black-and-white look ([MonoAod]): the BG and its arrow, and at most one grey line of
 * words. This object only turns Halo's state into the shared [MonoAod.Model]; [MonoAod.Painter] draws it.
 * The ring, knob, tail, loop pill, IOB and the bolus ball are interactive-only.
 */
object HaloAod {

    /** Arrow direction (degrees clockwise from 12 o'clock) and number of heads; 0 heads = no arrow. */
    fun arrowOf(t: Trend): Pair<Float, Int> = when (t) {
        Trend.NONE   -> 0f to 0
        Trend.FLAT   -> 90f to 1
        Trend.UP45   -> 45f to 1
        Trend.UP     -> 0f to 1
        Trend.UP2,
        Trend.UP3    -> 0f to 2
        Trend.DOWN45 -> 135f to 1
        Trend.DOWN   -> 180f to 1
        Trend.DOWN2,
        Trend.DOWN3  -> 180f to 2
    }

    /** Only the always-on warnings (W1-W6) reach the line: a reservoir warning waits for the interactive face. */
    fun model(
        hero: String, range: Range, bgAge: BgAge, trend: Trend, caption: FaceState.Caption,
        warning: FaceState.StatusText?, age: String, agoFormat: String
    ): MonoAod.Model {
        val (deg, heads) = arrowOf(trend)
        val w = warning?.takeIf { it.warning.ambient && it.text.isNotEmpty() }?.text
        return MonoAod.model(hero, range, bgAge, caption, deg, heads, w, age, agoFormat)
    }
}
