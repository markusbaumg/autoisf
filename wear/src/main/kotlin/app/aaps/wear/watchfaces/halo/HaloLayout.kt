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
 * Current variant: "M", decluttered (owner: "messy, too much information, too little space"): ring R 150, so even
 * the knob at 12 o'clock stays below the system status icon (knob top at y 61, the icon ends at ~57); the time alone
 * on top (no date line); hero 140 px (7.7 mm cap height) with ~24 px of clear space above and below; the delta line;
 * one status line that is drawn only when it has something to say; and one bottom row COB - pill - IOB (COB only
 * with carbs on board, no outer IOB arc) with the values anchored at an equal gap to the pill. The bolus ball (when
 * enabled) takes the pill's slot but carries a loop badge.
 */
class HaloLayout(val d: Float, density: Float = 2f) {

    /** Design-grid unit: 1 design px in screen px. */
    val u = d / 450f
    val c = d / 2f

    private fun g(v: Float) = v * u
    private fun font(v: Float) = max(v * u, MIN_TEXT_PX)

    // Ring (BG scale, 270 deg with the gap at the bottom).
    val ringR = g(150f)
    val ringW = g(13f)
    val ringGlow = g(8f)
    val cutIn = g(141f)                 // threshold cuts R +- 9
    val cutOut = g(159f)
    val backdropR = g(162f)
    val tailR = g(137f)
    val tailW = g(3f)
    val tailMaxAlpha = 90               // ~35 %: an echo of the trend, not a stroke
    val tailMinLen = g(30f)             // shorter tails (a flat BG) are not drawn: a 1-2 mm faint tick looks like an artifact
    val tailTextClear = g(20f)          // the tail ends this far before the time / date boxes
    val knobR = g(11f)
    val knobSeparation = g(3f)          // dark ring around the knob, so it does not merge into the ring gradient
    val knobGlow = g(6f)
    val dotR = g(5.5f)
    val ambStroke = g(2f)
    val cutStroke = g(3f)

    /** Top edge of the knob (with its dark separation) at 12 o'clock: below the system status icon (~y 57). */
    val knobTop = c - ringR - knobR - knobSeparation

    // Time (the date line is gone: declutter round).
    val timeY = g(108f)
    val timeSize = font(36f)

    // Hero BG: cap height centred on heroY, ~29 px clear above (to the time) and ~24 px below (to the delta line).
    val heroY = g(200f)
    val heroSize = g(140f)
    val heroMaxW = g(224f)              // "6,4" / "224" fit at full size, "10,4" shrinks; inside the trend tail
    val heroGlow = g(10f)
    val heroStrike = g(2f)

    // TIEF / HOCH caption right of the digits (top-aligned to their cap height); digits + caption are centred as one
    // group and fitted to [heroGroupMaxW] (still inside the trend tail at the caption's height).
    val captionSize = font(22f)
    val captionGap = g(8f)
    val captionPad = g(7f)             // badge padding left / right of the word
    val captionBadgeH = g(30f)
    val captionRadius = g(7f)
    val captionStroke = g(2f)          // ambient badge outline (no fill: burn-in safe)
    val heroGroupMaxW = g(224f)

    // Emphasis step (urgent low / very high): ring track and fill wider, backdrop stronger.
    val ringWEmphasis = g(18f)

    // Status text in the meta line (W2 / W3: "Keine Daten · 17′"), fitted inside the ring at the delta line.
    val metaMaxW = g(236f)             // ~11 px clear of the ring track at the delta line
    // Phone-off glyph (W10) after the age.
    val phoneGlyphH = g(20f)
    val phoneGlyphW = g(12f)

    // Arrow, delta and BG age (the age only when it is getting old, see HaloFaceLogic.showBgAge).
    val deltaY = g(288f)
    val arrowSize = font(38f)
    val deltaSize = font(30f)           // ~1.6 mm cap height: the second most important value
    val arrowGap = g(6f)

    // Status line (a warning, an active temp target, a temp basal that matters), between the delta line and the pill;
    // empty most of the time.
    val secondaryY = g(330f)
    val secondarySize = font(24f)       // ~1.3 mm cap height
    val secondaryMaxW = g(170f)          // "TT 5.6 · 0 U/h" shrinks to fit, clear of the ring ends

    // Loop pill: loop glyph (a circular arrow in the state colour) and the loop age. The pill is sized to its content
    // (round 4): equal inner padding on both sides and a fixed glyph-to-text gap, the glyph + text group centred, so
    // the common "0'" does not sit in a lopsided, half-empty pill. [pillHalfW] is the widest pill (the layout boxes).
    val pillY = g(372f)
    val pillHalfW = g(46f)
    val pillMinHalfW = g(30f)
    val pillHalfH = g(19f)
    val pillTextSize = font(26f)        // ~1.4 mm cap height: the only safety status on the face
    val pillPad = g(12f)                // inner ink padding, left of the glyph and right of the text
    val pillGap = g(8f)                 // glyph ink to text ink
    val pillGlyphR = g(8.5f)            // ~1.4 mm: large enough to carry the state colour at a glance
    val pillGlyphStroke = g(3f)
    val pillGlyphHead = g(5f)
    val pillGlyphGlow = g(4f)
    /** Half the glyph's ink width (the arrow head stays inside the circle's ink). */
    val pillGlyphInkHalf = pillGlyphR + pillGlyphStroke / 2f
    /** Widest label ink that fits the widest pill at full size; wider labels ("120'") shrink to it. */
    val pillTextMaxW = 2f * (pillHalfW - pillPad - pillGlyphInkHalf) - pillGap
    val pillStroke = g(1f)

    /** Pill geometry for a label whose ink is [textInkW] wide (at full size): half width, glyph centre, text ink left. */
    data class Pill(val halfW: Float, val glyphX: Float, val textLeft: Float, val textScale: Float)

    fun pill(textInkW: Float): Pill {
        val scale = HaloFaceLogic.fitScale(textInkW, pillTextMaxW)
        val textW = textInkW * scale
        val groupW = 2f * pillGlyphInkHalf + pillGap + textW
        val halfW = (groupW / 2f + pillPad).coerceIn(pillMinHalfW, pillHalfW)
        val glyphX = c - groupW / 2f + pillGlyphInkHalf
        return Pill(halfW, glyphX, glyphX + pillGlyphInkHalf + pillGap, scale)
    }

    // Bottom row: COB value (left, right-aligned) and IOB value (right, left-aligned) at the same gap to the pill.
    val rowY = pillY
    val rowPillGap = g(16f)
    /** COB / IOB anchors for the widest pill (and the bolus ball); [cobRightFor] / [iobLeftFor] follow the drawn pill. */
    val cobRight = c - pillHalfW - rowPillGap
    val iobLeft = c + pillHalfW + rowPillGap
    fun cobRightFor(halfW: Float) = c - halfW - rowPillGap
    fun iobLeftFor(halfW: Float) = c + halfW + rowPillGap
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
    /** Top edge of the ball's tap area: the 48 dp circle is cut off here, 4 design px below the secondary line. */
    val ballHitTop = secondaryY + secondarySize * CAP_HEIGHT / 2f + g(4f)
    val badgeX = ballX + ballR * 0.7071f
    val badgeY = ballY - ballR * 0.7071f
    val badgeR = g(7f)
    val badgeRim = g(2.5f)
    val ballCountdownR = ballR + g(5f)
    val ballCountdownW = g(2.2f)
    /** Below this ball diameter (screen px) the armed label is "2×" instead of "nochmal". */
    val ballSmall = 2f * ballR < 50f

    // Double-tap zone of the hero BG (opens the main menu).
    val heroTapLeft = g(95f)
    val heroTapRight = g(355f)
    val heroTapTop = g(145f)
    val heroTapBottom = g(270f)

    /**
     * Half-width (degrees) of the tail gap around 12 o'clock (270 deg): the trend tail is not drawn within
     * 270 +- this, so it never points at the time.
     */
    val tailGapHalf: Float = run {
        // + 6: the tail passes the time's lower corner closer than its side (tested on 384..480 px)
        val half = timeBox().right - c + tailTextClear + g(6f)
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

    fun boxes(): List<Box> = listOf(
        timeBox(),
        textBox("hero", c, heroY, heroMaxW, heroSize),
        deltaBox(11),                                                                           // "-0.25 · 12'"
        textBox("secondary", c, secondaryY, secondaryMaxW, secondarySize),
        pillBox(),
        cobBox(),
        iobBox(),
    )

    /** Arrow plus a delta line of [chars] characters ("+15.8" = 5, with " · 12'" = 11). */
    fun deltaBox(chars: Int) = textBox("delta", c, deltaY, arrowSize + arrowGap + deltaSize * 0.52f * chars, arrowSize)

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
