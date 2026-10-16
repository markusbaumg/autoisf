package app.aaps.wear.watchfaces.halo

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * All sizes and positions of the "AAPS Halo" face, in screen px, derived from the face diameter [d]
 * (min(width, height)). This is the one place to tune the face.
 *
 * Every value is written on a 450 px design grid (the Galaxy Watch5 Pro: 450 px, ~13 px per mm) and multiplied by
 * [u] = d / 450, i.e. it is a fixed fraction of the diameter. Two exceptions keep small screens usable:
 * - text never goes below [MIN_TEXT_PX] (a 384 px screen would otherwise shrink a 17 px label to 14.5 px);
 * - the bolus tap target is a fixed 48 dp ([TAP_TARGET_DP]), not scaled.
 *
 * Current variant: "L-" (a bit smaller than fit1): ring R 172 (76 % of the radius, top at y 46), hero 154 px
 * (8.4 mm cap height), secondary line above the pill, and one bottom row COB - pill - IOB (values with their unit,
 * no labels; IOB on the right, next to the end where its arc fills from).
 */
class HaloLayout(val d: Float, density: Float = 2f) {

    /** Design-grid unit: 1 design px in screen px. */
    val u = d / 450f
    val c = d / 2f

    private fun g(v: Float) = v * u
    private fun font(v: Float) = max(v * u, MIN_TEXT_PX)

    // Ring (BG scale, 270 deg with the gap at the bottom).
    val ringR = g(172f)
    val ringW = g(15f)
    val ringGlow = g(8f)
    val cutIn = g(163f)                 // threshold cuts R +- 9
    val cutOut = g(181f)
    val ambCutIn = g(165f)
    val ambCutOut = g(179f)
    val backdropR = g(182f)
    val tailR = g(157f)
    val tailW = g(3f)
    val knobR = g(13.5f)
    val knobGlow = g(6f)
    val dotR = g(7f)
    val ambKnobR = g(8f)
    val ambStroke = g(2f)
    val cutStroke = g(3f)

    // IOB arc at the right bezel, filling from its bottom end.
    val iobR = g(211f)
    val iobW = g(6f)
    val iobGlow = g(5f)
    val iobHalfSweep = 42f
    val ambIobW = g(1f)
    val ambIobFillW = g(2f)

    // Time and German date.
    val timeY = g(92f)
    val timeSize = font(39f)
    val dateY = g(124f)
    val dateSize = font(22f)

    // Hero BG.
    val heroY = g(211f)
    val heroSize = g(154f)
    val heroMaxW = g(252f)              // "10.4" shrinks to fit inside the trend tail
    val heroGlow = g(10f)
    val heroStrike = g(2f)
    val heroAmbStroke = g(2f)
    val heroAmbStrokeBurnIn = g(1.5f)

    // Arrow, delta and BG age.
    val deltaY = g(294f)
    val arrowSize = font(32f)
    val deltaSize = font(26f)
    val arrowGap = g(10f)

    // Secondary line (temp target + basal), between the delta line and the pill.
    val secondaryY = g(330f)
    val secondarySize = font(22f)
    val secondaryMaxW = g(210f)          // "TT 5.6 · 0.00U/h" is ~162; wider ones shrink, clear of the ring ends

    // Loop pill.
    val pillY = g(375f)
    val pillHalfW = g(40f)
    val pillHalfH = g(18f)
    val pillTextSize = font(23f)
    val pillDotX = c - g(25f)
    val pillDotR = g(6f)
    val pillDotGlow = g(4f)
    val pillAmbDotR = g(4.5f)
    val pillTextX = c + g(8f)
    val pillStroke = g(1f)

    // Bottom row: COB value (left) and IOB value (right) beside the pill, value + smaller unit, no labels.
    val rowY = pillY
    val cobX = g(138f)
    val iobX = g(312f)
    val rowValueSize = font(28f)
    val rowUnitSize = font(19f)
    val rowUnitGap = g(2f)
    val rowMaxW = g(64f)                // "12(5)g" / "-0.25U" shrink to fit

    // Bolus ball (optional): takes the pill's place in the ring gap; its 48 dp tap circle (y 328..424 at 450 px)
    // stays below the hero double-tap zone and clear of the ring ends.
    val ballX = c
    val ballY = g(376f)
    val ballR = g(26f)
    val ballStroke = g(2.5f)
    val ballGlow = g(5f)
    val ballGlowArmed = g(9f)
    val ballAmountSize = g(15f)
    val ballAmountDy = g(-5f)
    val ballSubSize = g(10f)
    val ballSubDy = g(11f)
    val ballSubMaxW = g(40f)
    val ballHitR = max(ballR, TAP_TARGET_DP / 2f * density)

    // Double-tap zone of the hero BG (opens the main menu).
    val heroTapLeft = g(90f)
    val heroTapRight = g(360f)
    val heroTapTop = g(152f)
    val heroTapBottom = g(275f)

    /** Ambient burn-in shift, scaled like the rest. */
    fun burnIn(designPx: Float) = designPx * u

    // ------------------------------------------------------------------ layout check (unit tested)

    data class Box(val name: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val minBezelFraction: Float) {
        fun overlaps(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    }

    private fun textBox(name: String, cx: Float, cy: Float, width: Float, size: Float, bezel: Float = BEZEL_TEXT): Box {
        val h = size * CAP_HEIGHT
        return Box(name, cx - width / 2f, cy - h / 2f, cx + width / 2f, cy + h / 2f, bezel)
    }

    /** Approximate ink boxes at their widest (fit limits, or typical widest strings), for the layout test. */
    fun boxes(): List<Box> = listOf(
        textBox("time", c, timeY, timeSize * 0.56f * 5f, timeSize),                          // "00:00"
        textBox("date", c, dateY, dateSize * 0.52f * 10f, dateSize),                          // "Mi 17. Okt"
        textBox("hero", c, heroY, heroMaxW, heroSize),
        textBox("delta", c, deltaY, arrowSize + arrowGap + deltaSize * 0.52f * 11f, arrowSize), // "-0.25 · 12'"
        textBox("secondary", c, secondaryY, secondaryMaxW, secondarySize),
        Box("pill", c - pillHalfW, pillY - pillHalfH, c + pillHalfW, pillY + pillHalfH, BEZEL_TEXT),
        textBox("cob", cobX, rowY, rowMaxW, rowValueSize, BEZEL_SIDE),
        textBox("iob", iobX, rowY, rowMaxW, rowValueSize, BEZEL_SIDE),
    )

    /** The bolus ball's drawn box (it replaces the pill when shown). */
    fun ballBox(): Box = Box("ball", ballX - ballR, ballY - ballR, ballX + ballR, ballY + ballR, BEZEL_TEXT)

    /** True when [b] comes closer than [gap] to a round end cap of the ring (the arc check covers only the sweep). */
    fun nearRingEnds(b: Box, gap: Float = 0f): Boolean = listOf(HaloFaceLogic.RING_START, HaloFaceLogic.RING_START + HaloFaceLogic.RING_SWEEP).any { a ->
        val ex = c + ringR * kotlin.math.cos(Math.toRadians(a.toDouble())).toFloat()
        val ey = c + ringR * kotlin.math.sin(Math.toRadians(a.toDouble())).toFloat()
        val nx = ex.coerceIn(b.left, b.right)
        val ny = ey.coerceIn(b.top, b.bottom)
        hypot(nx - ex, ny - ey) < ringW / 2f + gap
    }

    /** True when any point of [b] lies on the ring stroke (within its 270 deg sweep). */
    fun touchesRing(b: Box): Boolean = touchesArc(b, ringR - ringW / 2f, ringR + ringW / 2f, HaloFaceLogic.RING_START, HaloFaceLogic.RING_SWEEP)

    /** True when any point of [b] lies on the IOB arc stroke. */
    fun touchesIobArc(b: Box): Boolean = touchesArc(b, iobR - iobW / 2f, iobR + iobW / 2f, -iobHalfSweep, 2f * iobHalfSweep)

    /** Trend tail: the hero must stay inside it. */
    fun insideTail(b: Box): Boolean = corners(b).all { (x, y) -> hypot(x - c, y - c) <= tailR - tailW / 2f }

    fun bezelMargin(b: Box): Float = c - corners(b).maxOf { (x, y) -> hypot(x - c, y - c) }

    private fun corners(b: Box) = listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)

    private fun touchesArc(b: Box, rIn: Float, rOut: Float, start: Float, sweep: Float): Boolean {
        val n = 24
        for (i in 0..n) for (j in 0..n) {
            val x = b.left + (b.right - b.left) * i / n
            val y = b.top + (b.bottom - b.top) * j / n
            val r = hypot(x - c, y - c)
            if (r < rIn || r > rOut) continue
            var a = Math.toDegrees(atan2((y - c).toDouble(), (x - c).toDouble())).toFloat()
            while (a < start) a += 360f
            if (a <= start + sweep) return true
        }
        return false
    }

    companion object {

        /** Smallest text size in px (~1.0 mm cap height on a 330 ppi watch). */
        const val MIN_TEXT_PX = 18f

        /** Wear OS minimum touch target. */
        const val TAP_TARGET_DP = 48f

        /** Cap height of the system font relative to its size. */
        const val CAP_HEIGHT = 0.711f

        /** Minimum distance of text to the bezel as a fraction of the diameter (main texts / IOB-COB values). */
        const val BEZEL_TEXT = 0.06f
        const val BEZEL_SIDE = 0.04f
    }
}
