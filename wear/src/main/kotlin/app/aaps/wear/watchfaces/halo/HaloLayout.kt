package app.aaps.wear.watchfaces.halo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * All sizes and positions of the "AAPS Halo" face, in screen px, derived from the face diameter [d]
 * (min(width, height)). This is the one place to tune the face.
 *
 * Every value is written on a 450 px design grid (the Galaxy Watch5 Pro: 450 px, ~13 px per mm) and multiplied by
 * [u] = d / 450, i.e. it is a fixed fraction of the diameter. Two exceptions keep small screens usable:
 * - text never goes below [MIN_TEXT_PX] (a 384 px screen would otherwise shrink a 17 px label to 14.5 px);
 * - the bolus tap target is a fixed 48 dp ([TAP_TARGET_DP]), not scaled.
 *
 * Current variant: "M", refined (round 1): ring R 158 (top at y 60, clear of the system status icon), hero 140 px
 * (7.7 mm cap height), delta / secondary / bottom row on an even ~41 px step, and one bottom row COB - pill - IOB
 * with the values anchored at an equal gap to the pill (COB right-aligned, IOB left-aligned), so the row stays
 * balanced whatever the values are. The bolus ball (when enabled) takes the pill's slot but carries a loop badge.
 */
class HaloLayout(val d: Float, density: Float = 2f) {

    /** Design-grid unit: 1 design px in screen px. */
    val u = d / 450f
    val c = d / 2f

    private fun g(v: Float) = v * u
    private fun font(v: Float) = max(v * u, MIN_TEXT_PX)

    // Ring (BG scale, 270 deg with the gap at the bottom).
    val ringR = g(158f)
    val ringW = g(14f)
    val ringGlow = g(8f)
    val cutIn = g(149f)                 // threshold cuts R +- 9
    val cutOut = g(167f)
    val ambCutIn = g(151f)
    val ambCutOut = g(165f)
    val backdropR = g(170f)
    val tailR = g(143f)
    val tailW = g(3f)
    val tailMaxAlpha = 90               // ~35 %: an echo of the trend, not a stroke
    val tailTextClear = g(20f)          // the tail ends this far before the time / date boxes
    val knobR = g(13f)
    val knobSeparation = g(3.5f)        // dark ring around the knob, so it does not merge into the ring gradient
    val knobGlow = g(6f)
    val dotR = g(6.5f)
    val ambKnobR = g(7.5f)
    val ambStroke = g(2f)
    val cutStroke = g(3f)

    // IOB arc at the right bezel, filling from its bottom end.
    val iobR = g(207f)
    val iobW = g(7f)
    val iobGlow = g(5f)
    val iobHalfSweep = 45f
    val ambIobW = g(1f)
    val ambIobFillW = g(2f)

    // Time and German date.
    val timeY = g(102f)
    val timeSize = font(38f)
    val dateY = g(134f)
    val dateSize = font(22f)

    // Hero BG.
    val heroY = g(214f)
    val heroSize = g(140f)
    val heroMaxW = g(250f)              // "224" fits at full size, "12.4" shrinks ~5 %; inside the trend tail
    val heroGlow = g(10f)
    val heroStrike = g(2f)
    val heroAmbStroke = g(2.5f)
    val heroAmbStrokeBurnIn = g(2f)

    // Arrow, delta and BG age (the age only when it is getting old, see HaloFaceLogic.showBgAge).
    val deltaY = g(290f)
    val arrowSize = font(40f)
    val deltaSize = font(28f)
    val arrowGap = g(6f)

    // Secondary line (temp target + basal), between the delta line and the pill.
    val secondaryY = g(331f)
    val secondarySize = font(24f)
    val secondaryMaxW = g(180f)          // "TT 5.6 · 0.00U/h" shrinks to fit, clear of the ring ends

    // Loop pill: loop glyph (a circular arrow in the state colour) and the loop age.
    val pillY = g(372f)
    val pillHalfW = g(38f)
    val pillHalfH = g(17f)
    val pillTextSize = font(22f)
    val pillGlyphX = c - g(22f)
    val pillGlyphR = g(6.5f)
    val pillGlyphStroke = g(2.5f)
    val pillGlyphHead = g(4f)
    val pillGlyphGlow = g(4f)
    val pillTextX = c + g(10f)
    val pillStroke = g(1f)

    // Bottom row: COB value (left, right-aligned) and IOB value (right, left-aligned) at the same gap to the pill.
    val rowY = pillY
    val rowPillGap = g(16f)
    val cobRight = c - pillHalfW - rowPillGap
    val iobLeft = c + pillHalfW + rowPillGap
    val rowValueSize = font(30f)
    val rowUnitSize = font(20f)
    val rowUnitGap = g(3f)
    val rowUnitAlpha = 0xA6             // unit in its value's hue at ~65 %
    val rowMaxW = g(72f)                // "12(5)g" / "-0.25U" shrink to fit

    // Bolus ball (optional): in the pill's slot. The loop state stays visible as a badge on the ball. Its 48 dp tap
    // circle stays below the hero double-tap zone and on the glass.
    val ballX = c
    val ballY = pillY
    val ballR = g(28f)
    val ballStroke = g(2.5f)
    val ballGlow = g(5f)
    val ballGlowArmed = g(9f)
    val ballAmountSize = font(20f)
    val ballAmountDy = g(-6f)
    val ballSubSize = font(18f)
    val ballSubDy = g(12f)
    val ballSubMaxW = g(46f)
    val ballHitR = max(ballR, TAP_TARGET_DP / 2f * density)
    val badgeX = ballX + ballR * 0.7071f
    val badgeY = ballY - ballR * 0.7071f
    val badgeR = g(7f)
    val badgeRim = g(2.5f)

    // Double-tap zone of the hero BG (opens the main menu).
    val heroTapLeft = g(95f)
    val heroTapRight = g(355f)
    val heroTapTop = g(160f)
    val heroTapBottom = g(275f)

    /**
     * Half-width (degrees) of the tail gap around 12 o'clock (270 deg): the trend tail is not drawn within
     * 270 +- this, so it never points at the time or the date.
     */
    val tailGapHalf: Float = run {
        val half = max(timeBox().right - c, dateBox().right - c) + tailTextClear
        Math.toDegrees(asin(min(1f, half / tailR).toDouble())).toFloat()
    }

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
    fun timeBox() = textBox("time", c, timeY, timeSize * 0.56f * 5f, timeSize)              // "00:00"
    fun dateBox() = textBox("date", c, dateY, dateSize * 0.52f * 10f, dateSize)              // "Mi 17. Okt"

    fun boxes(): List<Box> = listOf(
        timeBox(),
        dateBox(),
        textBox("hero", c, heroY, heroMaxW, heroSize),
        textBox("delta", c, deltaY, arrowSize + arrowGap + deltaSize * 0.52f * 11f, arrowSize), // "-0.25 · 12'"
        textBox("secondary", c, secondaryY, secondaryMaxW, secondarySize),
        pillBox(),
        cobBox(),
        iobBox(),
    )

    fun pillBox() = Box("pill", c - pillHalfW, pillY - pillHalfH, c + pillHalfW, pillY + pillHalfH, BEZEL_TEXT)
    fun cobBox() = textBox("cob", cobRight - rowMaxW / 2f, rowY, rowMaxW, rowValueSize, BEZEL_SIDE)
    fun iobBox() = textBox("iob", iobLeft + rowMaxW / 2f, rowY, rowMaxW, rowValueSize, BEZEL_SIDE)

    /** The bolus ball's drawn box (it takes the pill's slot when shown). */
    fun ballBox(): Box = Box("ball", ballX - ballR, ballY - ballR, ballX + ballR, ballY + ballR, BEZEL_TEXT)

    /** The loop badge on the ball (shown instead of the pill while the ball is shown). */
    fun badgeBox(): Box = (badgeR + badgeRim).let { r -> Box("badge", badgeX - r, badgeY - r, badgeX + r, badgeY + r, BEZEL_TEXT) }

    /** True when the tail is drawn at ring angle [a] (Canvas degrees, 135..405). */
    fun tailDrawnAt(a: Float): Boolean = abs(a - 270f) > tailGapHalf

    /** Distance of the tail point at angle [a] to box [b]. */
    fun tailDistance(a: Float, b: Box): Float {
        val x = c + tailR * kotlin.math.cos(Math.toRadians(a.toDouble())).toFloat()
        val y = c + tailR * kotlin.math.sin(Math.toRadians(a.toDouble())).toFloat()
        return hypot(x - x.coerceIn(b.left, b.right), y - y.coerceIn(b.top, b.bottom)) - tailW / 2f
    }

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
