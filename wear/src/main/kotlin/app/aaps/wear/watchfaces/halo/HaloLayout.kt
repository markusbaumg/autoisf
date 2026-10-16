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
 * Current variant: "S+" (a bit bigger than v1, v1 layout with the IOB/COB column on the right).
 */
class HaloLayout(val d: Float, density: Float = 2f) {

    /** Design-grid unit: 1 design px in screen px. */
    val u = d / 450f
    val c = d / 2f

    private fun g(v: Float) = v * u
    private fun font(v: Float) = max(v * u, MIN_TEXT_PX)

    // Ring (BG scale, 270 deg with the gap at the bottom).
    val ringR = g(146f)
    val ringW = g(14f)
    val ringGlow = g(8f)
    val cutIn = g(137f)                 // threshold cuts R +- 9
    val cutOut = g(155f)
    val ambCutIn = g(139f)
    val ambCutOut = g(153f)
    val backdropR = g(158f)
    val tailR = g(132f)
    val tailW = g(3f)
    val knobR = g(12f)
    val knobGlow = g(6f)
    val dotR = g(6f)
    val ambKnobR = g(7f)
    val ambStroke = g(2f)
    val cutStroke = g(3f)

    // IOB arc at the right bezel, filling from its bottom end.
    val iobR = g(209f)
    val iobW = g(5f)
    val iobGlow = g(5f)
    val iobHalfSweep = 50f
    val ambIobW = g(1f)
    val ambIobFillW = g(2f)

    // Time and German date.
    val timeY = g(118f)
    val timeSize = font(34f)
    val dateY = g(149f)
    val dateSize = font(19f)

    // Hero BG.
    val heroY = g(226f)
    val heroSize = g(122f)
    val heroMaxW = g(220f)              // "10.4" shrinks to fit inside the trend tail
    val heroGlow = g(10f)
    val heroStrike = g(2f)
    val heroAmbStroke = g(2f)
    val heroAmbStrokeBurnIn = g(1.5f)

    // Arrow, delta and BG age.
    val deltaY = g(294f)
    val arrowSize = font(30f)
    val deltaSize = font(24f)
    val arrowGap = g(10f)

    // Secondary line (temp target + basal), below the pill.
    val secondaryY = g(402f)
    val secondarySize = font(18f)
    val secondaryMaxW = g(140f)

    // Loop pill.
    val pillY = g(370f)
    val pillHalfW = g(40f)
    val pillHalfH = g(16f)
    val pillTextSize = font(19f)
    val pillDotX = c - g(25f)
    val pillDotR = g(5f)
    val pillDotGlow = g(4f)
    val pillAmbDotR = g(4f)
    val pillTextX = c + g(9f)
    val pillStroke = g(1f)

    // IOB / COB column between the ring and the IOB arc.
    val sideX = g(403f)
    val sideMaxW = g(42f)
    val sideLabelSize = font(17f)
    val sideValueSize = font(22f)
    val iobLabelY = g(196f)
    val iobValueY = g(216f)
    val cobLabelY = g(244f)
    val cobValueY = g(264f)

    // Bolus ball at 9 o'clock (optional), clear of the ring's outer edge (x = 72).
    val ballX = g(45f)
    val ballY = c
    val ballR = g(25f)
    val ballStroke = g(2.5f)
    val ballGlow = g(5f)
    val ballGlowArmed = g(9f)
    val ballAmountSize = g(14f)
    val ballAmountDy = g(-5f)
    val ballSubSize = g(10f)
    val ballSubDy = g(11f)
    val ballSubMaxW = g(40f)
    val ballHitR = max(ballR, TAP_TARGET_DP / 2f * density)

    // Double-tap zone of the hero BG (opens the main menu).
    val heroTapLeft = g(105f)
    val heroTapRight = g(345f)
    val heroTapTop = g(180f)
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
        Box("pill", c - pillHalfW, pillY - pillHalfH, c + pillHalfW, pillY + pillHalfH, BEZEL_TEXT),
        textBox("secondary", c, secondaryY, secondaryMaxW, secondarySize),
        textBox("iobLabel", sideX, iobLabelY, sideLabelSize * 0.62f * 3f, sideLabelSize, BEZEL_SIDE),
        textBox("iobValue", sideX, iobValueY, sideMaxW, sideValueSize, BEZEL_SIDE),
        textBox("cobLabel", sideX, cobLabelY, sideLabelSize * 0.62f * 3f, sideLabelSize, BEZEL_SIDE),
        textBox("cobValue", sideX, cobValueY, sideMaxW, sideValueSize, BEZEL_SIDE),
    )

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
        const val MIN_TEXT_PX = 17f

        /** Wear OS minimum touch target. */
        const val TAP_TARGET_DP = 48f

        /** Cap height of the system font relative to its size. */
        const val CAP_HEIGHT = 0.711f

        /** Minimum distance of text to the bezel as a fraction of the diameter (main texts / IOB-COB column). */
        const val BEZEL_TEXT = 0.06f
        const val BEZEL_SIDE = 0.04f
    }
}
