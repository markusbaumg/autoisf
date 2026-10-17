package app.aaps.wear.watchfaces.halo

import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class HaloLayoutTest {

    private val diameters = listOf(384f, 450f, 480f)

    @Test fun everyElementKeepsTheBezelMargin() {
        for (d in diameters) {
            val l = HaloLayout(d)
            for (b in l.boxes()) {
                assertWithMessage("${b.name} at d=$d: bezel margin ${l.bezelMargin(b)}")
                    .that(l.bezelMargin(b)).isAtLeast(b.minBezelFraction * d)
            }
            // The ring itself keeps >= 6 % of the diameter to the bezel.
            assertWithMessage("ring at d=$d").that(l.c - (l.ringR + l.ringW / 2f)).isAtLeast(0.06f * d)
            // The IOB arc is a bezel element, but stays fully on the glass.
            assertWithMessage("IOB arc at d=$d").that(l.c - (l.iobR + l.iobW / 2f)).isAtLeast(0.04f * d)
        }
    }

    @Test fun noElementOverlapsAnother() {
        for (d in diameters) {
            val boxes = HaloLayout(d).boxes()
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertWithMessage("${boxes[i].name} / ${boxes[j].name} at d=$d").that(boxes[i].overlaps(boxes[j])).isFalse()
            }
        }
    }

    @Test fun textStaysOffTheRingAndTheIobArc() {
        for (d in diameters) {
            val l = HaloLayout(d)
            for (b in l.boxes()) {
                assertWithMessage("${b.name} on the ring at d=$d").that(l.touchesRing(b)).isFalse()
                assertWithMessage("${b.name} on the IOB arc at d=$d").that(l.touchesIobArc(b)).isFalse()
            }
            val hero = l.boxes().first { it.name == "hero" }
            assertWithMessage("hero inside the trend tail at d=$d").that(l.insideTail(hero)).isTrue()
        }
    }

    @Test fun textNeverBelowTheFloorAndTapTargetIs48dp() {
        for (d in diameters) {
            val l = HaloLayout(d, density = 2f)
            for (size in listOf(l.timeSize, l.dateSize, l.deltaSize, l.secondarySize, l.pillTextSize, l.rowValueSize, l.rowUnitSize, l.ballAmountSize, l.ballSubSize)) {
                assertWithMessage("text size at d=$d").that(size).isAtLeast(HaloLayout.MIN_TEXT_PX)
            }
            assertWithMessage("hit radius at d=$d").that(l.ballHitR).isEqualTo(48f)
        }
    }

    @Test fun bottomRowStaysClearOfTheRingEnds() {
        for (d in diameters) {
            val l = HaloLayout(d)
            for (b in l.boxes().filter { it.name in setOf("secondary", "pill") }) {
                assertWithMessage("${b.name} near a ring end at d=$d").that(l.nearRingEnds(b, gap = 0.02f * d)).isFalse()
            }
            // The COB / IOB values (widest fitted width) keep 16 design px from the round caps, also of the IOB arc.
            for (b in listOf(l.cobBox(), l.iobBox())) {
                assertWithMessage("${b.name} near a ring end at d=$d").that(l.nearRingEnds(b, gap = 16f * l.u)).isFalse()
                assertWithMessage("${b.name} near an IOB arc end at d=$d").that(l.nearIobArcEnds(b, gap = 16f * l.u)).isFalse()
            }
        }
    }

    @Test fun cobAndIobKeepEqualGapsToThePill() {
        for (d in diameters) {
            val l = HaloLayout(d)
            val pill = l.pillBox()
            val left = pill.left - l.cobRight
            val right = l.iobLeft - pill.right
            assertWithMessage("gaps at d=$d").that(left).isWithin(0.01f).of(right)
            assertWithMessage("gap at d=$d").that(left).isGreaterThan(0f)
            // Anchors: COB ends at the gap, IOB starts at it, so the gaps do not depend on the values.
            assertWithMessage("cob anchor at d=$d").that(l.cobBox().right).isWithin(0.01f).of(l.cobRight)
            assertWithMessage("iob anchor at d=$d").that(l.iobBox().left).isWithin(0.01f).of(l.iobLeft)
        }
    }

    @Test fun trendTailStaysClearOfTimeAndDate() {
        for (d in diameters) {
            val l = HaloLayout(d)
            var a = HaloFaceLogic.RING_START
            while (a <= HaloFaceLogic.RING_START + HaloFaceLogic.RING_SWEEP) {
                if (l.tailDrawnAt(a)) for (b in listOf(l.timeBox(), l.dateBox())) {
                    assertWithMessage("tail at $a deg / ${b.name} at d=$d").that(l.tailDistance(a, b)).isAtLeast(l.tailTextClear - 0.5f)
                }
                a += 0.5f
            }
        }
    }

    @Test fun loopStatusIsAlwaysVisible() {
        for (d in diameters) {
            val l = HaloLayout(d)
            val ball = l.ballBox()
            val badge = l.badgeBox()
            // With the ball shown, the loop badge sits on the ball, on the glass, and clear of everything else.
            assertWithMessage("badge centre on the ball at d=$d").that(hypot(l.badgeX - l.ballX, l.badgeY - l.ballY)).isAtMost(l.ballR)
            assertWithMessage("badge on the ring at d=$d").that(l.touchesRing(badge)).isFalse()
            assertWithMessage("badge bezel at d=$d").that(l.bezelMargin(badge)).isAtLeast(0.06f * d)
            for (b in l.boxes().filter { it.name != "pill" }) {
                assertWithMessage("badge / ${b.name} at d=$d").that(badge.overlaps(b)).isFalse()
            }
            // The ball uses the pill's slot (never both), so the two cannot hide each other.
            assertWithMessage("ball in the pill slot at d=$d").that(ball.overlaps(l.pillBox())).isTrue()
        }
    }

    @Test fun bolusBallTakesThePillsPlaceAndItsTargetIsClear() {
        for (d in diameters) {
            val l = HaloLayout(d)
            val ball = l.ballBox()
            // Drawn ball: off the ring (and its round ends), on the glass, clear of the COB / IOB values and the secondary line.
            assertWithMessage("ball on the ring at d=$d").that(l.touchesRing(ball)).isFalse()
            assertWithMessage("ball near a ring end at d=$d").that(l.nearRingEnds(ball, gap = 0.02f * d)).isFalse()
            assertWithMessage("ball bezel at d=$d").that(l.bezelMargin(ball)).isAtLeast(0.06f * d)
            for (b in l.boxes().filter { it.name in setOf("secondary", "cob", "iob") }) {
                assertWithMessage("ball / ${b.name} at d=$d").that(ball.overlaps(b)).isFalse()
            }
            // Its tap area is cut off 4 px below the secondary line (a tap on the TT text can never arm a bolus),
            // but still covers the whole drawn ball.
            val secondary = l.boxes().first { it.name == "secondary" }
            assertWithMessage("ball hit top vs secondary at d=$d").that(l.ballHitTop).isAtLeast(secondary.bottom + 4f * l.u - 0.01f)
            assertWithMessage("ball hit top vs ball at d=$d").that(l.ballHitTop).isAtMost(l.ballY - l.ballR)
            // Its tap circle never reaches the hero double-tap zone.
            assertWithMessage("ball hit vs hero zone at d=$d").that(l.ballY - l.ballHitR).isGreaterThan(l.heroTapBottom)
            assertWithMessage("ball hit on the glass at d=$d").that(hypot(0f, l.ballY - l.c) + l.ballHitR).isAtMost(l.c)
        }
    }

    @Test fun widestDeltaFitsInsideTheTrendTail() {
        for (d in diameters) {
            val l = HaloLayout(d)
            // "+15.8" / "-10.4" with the arrow, and with the BG age ("+15.8 · 12'").
            for (b in listOf(l.deltaBox(5), l.deltaBox(11))) {
                assertWithMessage("delta inside the tail at d=$d").that(l.insideTail(b)).isTrue()
                assertWithMessage("delta on the ring at d=$d").that(l.touchesRing(b)).isFalse()
            }
            assertWithMessage("delta size at d=$d").that(l.deltaSize).isAtLeast(30f * l.u)
        }
    }

    @Test fun loopGlyphKeepsItsGapInsideThePill() {
        for (d in diameters) {
            val l = HaloLayout(d)
            val glyphLeft = l.pillGlyphX - l.pillGlyphR - l.pillGlyphStroke / 2f
            val glyphRight = l.pillGlyphX + l.pillGlyphR + l.pillGlyphStroke / 2f
            assertWithMessage("glyph inside the pill at d=$d").that(glyphLeft).isAtLeast(l.pillBox().left + 6f * l.u)
            // Widest label "25'" (~3 x 0.55 em), centred at pillTextX: >= 4 px to the glyph and inside the pill.
            val half = l.pillTextSize * 0.55f * 3f / 2f
            assertWithMessage("glyph / text gap at d=$d").that(l.pillTextX - half - glyphRight).isAtLeast(4f * l.u)
            assertWithMessage("text inside the pill at d=$d").that(l.pillTextX + half).isAtMost(l.pillBox().right)
            // "25'" and "--" fit at full size (no shrink); longer labels ("120'") shrink to pillTextMaxW.
            assertWithMessage("pill text max width at d=$d").that(l.pillTextMaxW).isAtLeast(2f * half)
            // The inner gaps stay balanced: glyph pad on the left vs. text pad on the right differ by < 4 design px.
            val leftPad = glyphLeft - l.pillBox().left
            val rightPad = l.pillBox().right - (l.pillTextX + half)
            assertWithMessage("pill pads at d=$d").that(kotlin.math.abs(leftPad - rightPad)).isAtMost(4f * l.u)
            // Safety status: >= 1.4 mm cap height on the 450 grid (26 design px).
            assertWithMessage("pill text size at d=$d").that(l.pillTextSize).isAtLeast(26f * l.u - 0.01f)
        }
    }

    @Test fun secondaryLineKeepsItsGapToTheDelta() {
        for (d in diameters) {
            val l = HaloLayout(d)
            val secondary = l.boxes().first { it.name == "secondary" }
            assertWithMessage("delta / secondary gap at d=$d").that(secondary.top - l.deltaBox(11).bottom).isAtLeast(8f * l.u)
        }
    }
}
