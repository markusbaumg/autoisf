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
            assertWithMessage("IOB arc at d=$d").that(l.c - (l.iobR + l.iobW / 2f)).isAtLeast(0.02f * d)
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
            for (size in listOf(l.timeSize, l.dateSize, l.deltaSize, l.secondarySize, l.pillTextSize, l.rowValueSize, l.rowUnitSize)) {
                assertWithMessage("text size at d=$d").that(size).isAtLeast(HaloLayout.MIN_TEXT_PX)
            }
            assertWithMessage("hit radius at d=$d").that(l.ballHitR).isEqualTo(48f)
        }
    }

    @Test fun bottomRowStaysClearOfTheRingEnds() {
        for (d in diameters) {
            val l = HaloLayout(d)
            for (b in l.boxes().filter { it.name in setOf("secondary", "pill", "cob", "iob") }) {
                assertWithMessage("${b.name} near a ring end at d=$d").that(l.nearRingEnds(b, gap = 0.02f * d)).isFalse()
            }
        }
    }

    @Test fun bolusBallTakesThePillsPlaceAndItsTargetIsClear() {
        for (d in diameters) {
            val l = HaloLayout(d)
            val ball = l.ballBox()
            // Drawn ball: off the ring (and its round ends), on the glass, clear of the COB / IOB values and the secondary line.
            assertWithMessage("ball on the ring at d=$d").that(l.touchesRing(ball)).isFalse()
            assertWithMessage("ball near a ring end at d=$d").that(l.nearRingEnds(ball)).isFalse()
            assertWithMessage("ball bezel at d=$d").that(l.bezelMargin(ball)).isAtLeast(0.06f * d)
            for (b in l.boxes().filter { it.name in setOf("secondary", "cob", "iob") }) {
                assertWithMessage("ball / ${b.name} at d=$d").that(ball.overlaps(b)).isFalse()
            }
            // Its tap circle never reaches the hero double-tap zone.
            assertWithMessage("ball hit vs hero zone at d=$d").that(l.ballY - l.ballHitR).isGreaterThan(l.heroTapBottom)
            assertWithMessage("ball hit on the glass at d=$d").that(hypot(0f, l.ballY - l.c) + l.ballHitR).isAtMost(l.c)
        }
    }
}
