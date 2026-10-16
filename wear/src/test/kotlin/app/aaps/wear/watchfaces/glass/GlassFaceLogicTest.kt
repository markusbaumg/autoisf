package app.aaps.wear.watchfaces.glass

import app.aaps.wear.watchfaces.glass.GlassFaceLogic.FooterInput
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.FooterWords
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Level
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Range
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.RunKind
import app.aaps.wear.watchfaces.kusi.BolusTapGesture
import app.aaps.wear.watchfaces.kusi.BolusTapGesture.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale

class GlassFaceLogicTest {

    private val now = 1_760_000_000_000L
    private val min = 60_000L

    // ------------------------------------------------------------------ stale / range

    @Test fun staleFromTwelveMinutesOrWithoutReading() {
        assertThat(GlassFaceLogic.isStale(0L, now)).isTrue()
        assertThat(GlassFaceLogic.isStale(now - 11 * min - 59_999, now)).isFalse()
        assertThat(GlassFaceLogic.isStale(now - 12 * min, now)).isTrue()
    }

    @Test fun rangeUsesThePhoneMarks() {
        // mmol 3.9 / 10.0 marks as mg/dL
        assertThat(GlassFaceLogic.range(169.0, 70.0, 180.0, 0, false)).isEqualTo(Range.IN)
        assertThat(GlassFaceLogic.range(212.0, 70.0, 180.0, 0, false)).isEqualTo(Range.HIGH)
        assertThat(GlassFaceLogic.range(61.0, 70.0, 180.0, 0, false)).isEqualTo(Range.LOW)
        assertThat(GlassFaceLogic.range(180.0, 70.0, 180.0, 0, false)).isEqualTo(Range.IN)
        // custom marks from the phone win over constants
        assertThat(GlassFaceLogic.range(150.0, 80.0, 140.0, 0, false)).isEqualTo(Range.HIGH)
        // without marks: the phone's sgvLevel
        assertThat(GlassFaceLogic.range(0.0, 0.0, 0.0, 1, false)).isEqualTo(Range.HIGH)
        assertThat(GlassFaceLogic.range(0.0, 0.0, 0.0, -1, false)).isEqualTo(Range.LOW)
        assertThat(GlassFaceLogic.range(0.0, 0.0, 0.0, 0, false)).isEqualTo(Range.IN)
        // stale wins
        assertThat(GlassFaceLogic.range(212.0, 70.0, 180.0, 1, true)).isEqualTo(Range.STALE)
    }

    @Test fun ageColourAndText() {
        assertThat(GlassFaceLogic.ageLevel(0)).isEqualTo(Level.NORMAL)
        assertThat(GlassFaceLogic.ageLevel(5)).isEqualTo(Level.NORMAL)
        assertThat(GlassFaceLogic.ageLevel(6)).isEqualTo(Level.WARN)
        assertThat(GlassFaceLogic.ageLevel(11)).isEqualTo(Level.WARN)
        assertThat(GlassFaceLogic.ageLevel(12)).isEqualTo(Level.ALERT)
        assertThat(GlassFaceLogic.ageLevel(null)).isEqualTo(Level.ALERT)
        assertThat(GlassFaceLogic.ageMinutes(now - 61_000, now)).isEqualTo(1L)
        assertThat(GlassFaceLogic.ageMinutes(now + 5_000, now)).isEqualTo(0L)
        assertThat(GlassFaceLogic.ageMinutes(0L, now)).isNull()
        assertThat(GlassFaceLogic.minutesText(17, "alt")).isEqualTo("17'")
        assertThat(GlassFaceLogic.minutesText(99, "alt")).isEqualTo("99'")
        assertThat(GlassFaceLogic.minutesText(100, "alt")).isEqualTo("alt")
        assertThat(GlassFaceLogic.minutesText(null, "alt")).isEqualTo("--")
    }

    @Test fun arrowChip() {
        assertThat(GlassFaceLogic.arrow("→", false)).isEqualTo("→︎")
        assertThat(GlassFaceLogic.arrow("⇊", false)).isEqualTo("⇊︎")
        assertThat(GlassFaceLogic.arrow("--", false)).isEmpty()
        assertThat(GlassFaceLogic.arrow("??", false)).isEmpty()
        assertThat(GlassFaceLogic.arrow("↗", true)).isEmpty()
    }

    // ------------------------------------------------------------------ mmol / mg/dL formatting

    @Test fun bgSizeForMmolAndMgdl() {
        assertThat(GlassFaceLogic.bgTextSize("9.4")).isEqualTo(84f)
        assertThat(GlassFaceLogic.bgTextSize("11.8")).isEqualTo(74f)
        assertThat(GlassFaceLogic.bgTextSize("180")).isEqualTo(84f)
        assertThat(GlassFaceLogic.bgTextSize("95")).isEqualTo(84f)
        assertThat(GlassFaceLogic.bgTextSize("---")).isEqualTo(84f)
    }

    @Test fun deltaFormatting() {
        assertThat(GlassFaceLogic.deltaText("+0.1")).isEqualTo("+0.1")
        assertThat(GlassFaceLogic.deltaText("-0,4")).isEqualTo("-0,4") // phone locale kept
        assertThat(GlassFaceLogic.deltaText("+12")).isEqualTo("+12")
        assertThat(GlassFaceLogic.deltaText("0.0")).isEqualTo("+0.0")
        assertThat(GlassFaceLogic.deltaText("--")).isEqualTo("--")
    }

    @Test fun iobBasalReservoirFormatting() {
        assertThat(GlassFaceLogic.iobText("2,53")).isEqualTo("2,53")
        assertThat(GlassFaceLogic.iobText("-0.10")).isEqualTo("-0.10")
        assertThat(GlassFaceLogic.iobText("IOB")).isEqualTo("--")
        assertThat(GlassFaceLogic.iobText("-.--")).isEqualTo("--")
        assertThat(GlassFaceLogic.basalText("0,00 U/h")).isEqualTo("0,00 U/h")
        assertThat(GlassFaceLogic.basalText("1.35U/h")).isEqualTo("1.35 U/h")
        assertThat(GlassFaceLogic.basalText("-.--U/h")).isEqualTo("--")
        assertThat(GlassFaceLogic.basalText("90%")).isEqualTo("90%")
        assertThat(GlassFaceLogic.reservoirText("12U")).isEqualTo("12 U")
        assertThat(GlassFaceLogic.cobGrams("24g")).isEqualTo(24)
        assertThat(GlassFaceLogic.cobGrams("24(10)g")).isEqualTo(24)
        assertThat(GlassFaceLogic.cobGrams("--g")).isNull()
        assertThat(GlassFaceLogic.phoneBatteryPercent("18")).isEqualTo(18)
        assertThat(GlassFaceLogic.phoneBatteryPercent("--")).isNull()
        assertThat(GlassFaceLogic.phoneBatteryPercent("0")).isNull() // unknown, never a warning
    }

    // ------------------------------------------------------------------ loop

    @Test fun loopStates() {
        assertThat(GlassFaceLogic.loopAgeMinutes(-1L, now)).isNull()
        assertThat(GlassFaceLogic.loopAgeMinutes(now - 3 * min, now)).isEqualTo(3L)
        assertThat(GlassFaceLogic.loopLevel(0, false)).isEqualTo(Level.NORMAL)
        assertThat(GlassFaceLogic.loopLevel(5, false)).isEqualTo(Level.NORMAL)
        assertThat(GlassFaceLogic.loopLevel(6, false)).isEqualTo(Level.WARN)
        assertThat(GlassFaceLogic.loopLevel(14, false)).isEqualTo(Level.WARN)
        assertThat(GlassFaceLogic.loopLevel(15, false)).isEqualTo(Level.ALERT)
        assertThat(GlassFaceLogic.loopLevel(null, false)).isNull()            // never ran: grey, never red
        assertThat(GlassFaceLogic.loopLevel(1, true)).isEqualTo(Level.WARN)   // loop disabled/suspended
        assertThat(GlassFaceLogic.loopLevel(null, true)).isEqualTo(Level.WARN)
        assertThat(GlassFaceLogic.loopDisabled("no status")).isFalse()
        assertThat(GlassFaceLogic.loopDisabled("")).isFalse()
        assertThat(GlassFaceLogic.loopDisabled("Loop deaktiviert\n")).isTrue()
        assertThat(GlassFaceLogic.loopLabelVisible(9)).isTrue()
        assertThat(GlassFaceLogic.loopLabelVisible(10)).isFalse()
        assertThat(GlassFaceLogic.loopText(17, "alt", lowBit = true)).isEqualTo("17'!")
        assertThat(GlassFaceLogic.loopText(14, "alt", lowBit = true)).isEqualTo("14'")
        assertThat(GlassFaceLogic.loopText(17, "alt", lowBit = false)).isEqualTo("17'")
    }

    // ------------------------------------------------------------------ footer / warnings

    private val de = FooterWords(
        phoneBattery = { "Tel $it%" },
        disconnected = { "Telefon getrennt · $it" },
        cob = { "COB $it g" },
        reservoir = { "Res $it" },
        oldWord = "alt",
        waiting = "Warte auf AAPS"
    )

    /** Rough width of the 18 px Medium face: 10 px per character. */
    private val measure: (String) -> Float = { it.length * 10f }

    private fun input(
        tt: String = "5.6", ttLevel: Int = 2, cob: String = "0g", basal: String = "0.00 U/h", profile: Int? = null,
        battery: String = "80", res: String = "120U", resLevel: Int = 0, last: Long = now - min
    ) = FooterInput(tt, ttLevel, cob, basal, profile, battery, res, resLevel, last, now)

    @Test fun footerShowsOnlyActiveRuns() {
        val runs = GlassFaceLogic.footerRuns(input(), de, "  ·  ", measure)
        assertThat(runs.map { it.text }).containsExactly("TT 5.6", "0.00 U/h").inOrder()
        // TT only with level 2 (active temp target), COB only when > 0
        val noTt = GlassFaceLogic.footerRuns(input(tt = "5.0 - 6.0", ttLevel = 0, cob = "24g"), de, "  ·  ", measure)
        assertThat(noTt.map { it.text }).containsExactly("COB 24 g", "0.00 U/h").inOrder()
    }

    @Test fun footerWarnings() {
        val runs = GlassFaceLogic.footerRuns(input(ttLevel = 0, cob = "24g", basal = "1,35 U/h", battery = "18"), de, "  ·  ", measure = { it.length * 8.5f })
        assertThat(runs.map { it.text }).containsExactly("COB 24 g", "1,35 U/h", "Tel 18%").inOrder()
        assertThat(runs.last().level).isEqualTo(Level.WARN)
        val critical = GlassFaceLogic.footerRuns(input(ttLevel = 0, battery = "9", resLevel = 2, res = "8U"), de, "  ·  ", measure)
        assertThat(critical.map { it.kind }).containsExactly(RunKind.BASAL, RunKind.PHONE_BATTERY, RunKind.RESERVOIR).inOrder()
        assertThat(critical[1].level).isEqualTo(Level.ALERT)
        assertThat(critical[2].text).isEqualTo("Res 8 U")
        assertThat(critical[2].level).isEqualTo(Level.ALERT)
        assertThat(GlassFaceLogic.phoneBatteryLevel(20)).isNull()
        assertThat(GlassFaceLogic.phoneBatteryLevel(19)).isEqualTo(Level.WARN)
        assertThat(GlassFaceLogic.phoneBatteryLevel(10)).isEqualTo(Level.WARN)
        assertThat(GlassFaceLogic.phoneBatteryLevel(9)).isEqualTo(Level.ALERT)
    }

    @Test fun disconnectOverridesTheFooter() {
        val runs = GlassFaceLogic.footerRuns(input(last = now - 14 * min, battery = "5"), de, "  ·  ", measure)
        assertThat(runs).hasSize(1)
        assertThat(runs[0].text).isEqualTo("Telefon getrennt · 14'")
        assertThat(runs[0].kind).isEqualTo(RunKind.DISCONNECTED)
        assertThat(runs[0].level).isEqualTo(Level.ALERT)
        assertThat(GlassFaceLogic.isDisconnected(now - 12 * min, now)).isFalse()
        assertThat(GlassFaceLogic.isDisconnected(now - 12 * min - 1, now)).isTrue()
        assertThat(GlassFaceLogic.isDisconnected(0L, now)).isTrue()
        val never = GlassFaceLogic.footerRuns(input(last = 0L), de, "  ·  ", measure)
        assertThat(never.single().text).isEqualTo("Warte auf AAPS")       // first start
        assertThat(never.single().kind).isEqualTo(RunKind.WAITING)
        assertThat(never.single().level).isEqualTo(Level.NORMAL)
        val long = GlassFaceLogic.footerRuns(input(last = now - 300 * min), de, "  ·  ", measure)
        assertThat(long[0].text).isEqualTo("Telefon getrennt · alt")
    }

    @Test fun worstCaseFooterDropsProfileThenCob() {
        // TT 10.0 - 11.0 · COB 124 g · 12.35 U/h · 110% · Tel 9% · Res 8 U at 10 px/char is far wider than 300 px
        val runs = GlassFaceLogic.footerRuns(
            input(tt = "10.0 - 11.0", cob = "124g", basal = "12.35 U/h", profile = 110, battery = "9", resLevel = 2, res = "8U"),
            de, "  ·  ", measure
        )
        assertThat(runs.map { it.kind }).doesNotContain(RunKind.PROFILE)
        assertThat(runs.map { it.kind }).doesNotContain(RunKind.COB)
        // warnings are never dropped
        assertThat(runs.map { it.kind }).containsAtLeast(RunKind.PHONE_BATTERY, RunKind.RESERVOIR)
        // the face then scales the rest down to the max width
        val width = GlassFaceLogic.lineWidth(runs, "  ·  ", measure)
        val size = GlassFaceLogic.fitSize(18f, width, GlassFaceLogic.FOOTER_MAX_WIDTH)
        assertThat(width * size / 18f).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH + 0.01f)
    }

    @Test fun narrowFooterKeepsProfile() {
        val runs = GlassFaceLogic.footerRuns(input(ttLevel = 0, profile = 90), de, "  ·  ", measure)
        assertThat(runs.map { it.text }).containsExactly("0.00 U/h", "90%").inOrder()
    }

    @Test fun mockupLineFitsAt18px() {
        // "COB 24 g · 1.35 U/h · Tel 18%" (about 270 px in the spec) must not lose runs
        val runs = GlassFaceLogic.footerRuns(input(ttLevel = 0, cob = "24g", basal = "1.35U/h", battery = "18"), de, "  ·  ", measure = { it.length * 8.5f })
        assertThat(runs).hasSize(3)
    }

    @Test fun fitSize() {
        assertThat(GlassFaceLogic.fitSize(84f, 150f, 176f)).isEqualTo(84f)
        assertThat(GlassFaceLogic.fitSize(84f, 352f, 176f)).isEqualTo(42f)
        assertThat(GlassFaceLogic.fitSize(18f, 0f, 84f)).isEqualTo(18f)
    }

    // ------------------------------------------------------------------ time / date

    @Test fun germanDateFromRendererTime() {
        assertThat(GlassFaceLogic.dateText(LocalDate.of(2026, 10, 17), Locale.GERMANY)).isEqualTo("Sa 17. Okt")
        assertThat(GlassFaceLogic.dateText(LocalDate.of(2025, 10, 17), Locale.GERMANY)).isEqualTo("Fr 17. Okt")
        assertThat(GlassFaceLogic.dateText(LocalDate.of(2026, 5, 1), Locale.GERMANY)).isEqualTo("Fr 1. Mai")
        assertThat(GlassFaceLogic.dateText(LocalDate.of(2026, 3, 2), Locale.GERMANY)).isEqualTo("Mo 2. März")
    }

    @Test fun timeText() {
        assertThat(GlassFaceLogic.timeText(0, 40, true)).isEqualTo("00:40")
        assertThat(GlassFaceLogic.timeText(23, 5, true)).isEqualTo("23:05")
        assertThat(GlassFaceLogic.timeText(0, 40, false)).isEqualTo("12:40")
        assertThat(GlassFaceLogic.timeText(13, 7, false)).isEqualTo("1:07")
    }

    // ------------------------------------------------------------------ sparkline

    @Test fun sparklineGeometry() {
        assertThat(GlassFaceLogic.nowX).isWithin(0.5f).of(294f)
        assertThat(GlassFaceLogic.sparkX(0f)).isEqualTo(84f)
        assertThat(GlassFaceLogic.sparkX(47f)).isEqualTo(366f)
        assertThat(GlassFaceLogic.sparkY(54.0)).isEqualTo(312f)
        assertThat(GlassFaceLogic.sparkY(216.0)).isEqualTo(256f)
        assertThat(GlassFaceLogic.sparkY(20.0)).isEqualTo(312f)
        assertThat(GlassFaceLogic.sparkY(400.0)).isEqualTo(256f)
    }

    @Test fun sparklineAnchorMovesOnNewBgOrFiveMinuteBoundary() {
        val slot = GlassFaceLogic.SLOT_MS
        val base = (now / slot) * slot
        val bgTs = base + 60_000
        // fresh BG sits on the now line, also a few minutes later
        assertThat(GlassFaceLogic.sparkAnchor(bgTs, bgTs + 3 * min)).isEqualTo(bgTs)
        // older BG: axis follows the clock in 5' steps only
        assertThat(GlassFaceLogic.sparkAnchor(bgTs, base + 7 * min)).isEqualTo(base + slot)
        assertThat(GlassFaceLogic.sparkAnchor(bgTs, base + 9 * min)).isEqualTo(base + slot)
        assertThat(GlassFaceLogic.sparkAnchor(0L, base + 11 * min)).isEqualTo(base + 2 * slot)
        assertThat(GlassFaceLogic.slotOf(bgTs, bgTs)).isEqualTo(35f)
        assertThat(GlassFaceLogic.slotOf(bgTs - 35 * slot, bgTs)).isEqualTo(0f)
        assertThat(GlassFaceLogic.slotOf(bgTs - 36 * slot, bgTs)).isNull()
        assertThat(GlassFaceLogic.slotOf(bgTs + 12 * slot, bgTs)).isEqualTo(47f)
    }

    // ------------------------------------------------------------------ bolus ball states

    @Test fun bolusHardenedRules() {
        fun allowed(pref: Boolean = true, wear: Boolean = true, ambient: Boolean = false, disc: Boolean = false, amount: Double = 0.5, max: Double = 3.0) =
            GlassFaceLogic.bolusAllowed(pref, wear, ambient, disc, amount, max)
        assertThat(allowed()).isTrue()
        assertThat(allowed(pref = false)).isFalse()       // default OFF
        assertThat(allowed(wear = false)).isFalse()       // Wear control off on the phone
        assertThat(allowed(ambient = true)).isFalse()     // never in ambient
        assertThat(allowed(disc = true)).isFalse()        // phone disconnected / data not fresh
        assertThat(allowed(amount = 1.0, max = 0.5)).isFalse() // above the phone's max bolus
        assertThat(allowed(amount = 0.5, max = 0.5)).isTrue()
        assertThat(allowed(max = 0.0)).isFalse()          // max bolus unknown
    }

    @Test fun bolusGestureWithHardenedTiming() {
        var t = 1_000_000L
        val g = BolusTapGesture(clock = { t }, windowMs = GlassFaceLogic.BOLUS_WINDOW_MS, cooldownMs = GlassFaceLogic.BOLUS_COOLDOWN_MS)
        assertThat(g.onTap(true, true)).isEqualTo(Decision.ARM)
        t += 801
        assertThat(g.onTap(true, true)).isEqualTo(Decision.ARM)  // 800 ms window passed: re-arm, no send
        t += 500
        assertThat(g.onTap(true, true)).isEqualTo(Decision.SEND)
        t += 4_000
        assertThat(g.onTap(true, true)).isEqualTo(Decision.IGNORE) // 5 s cooldown
        t += 1_001
        assertThat(g.onTap(true, true)).isEqualTo(Decision.ARM)
        assertThat(g.reset()).isTrue()                              // entering ambient disarms
        assertThat(g.onTap(true, false)).isEqualTo(Decision.IGNORE) // disabled
    }

    @Test fun ballHitAndRing() {
        assertThat(GlassFaceLogic.ballHit(46f, 176f)).isTrue()
        assertThat(GlassFaceLogic.ballHit(46f + 44f, 176f)).isTrue()
        assertThat(GlassFaceLogic.ballHit(46f + 45f, 176f)).isFalse()
        assertThat(GlassFaceLogic.ballHit(5f, 176f)).isFalse()       // off the round screen
        assertThat(GlassFaceLogic.ballHit(150f, 176f)).isFalse()     // hero card
        assertThat(GlassFaceLogic.armProgress(1000, 1000, 800)).isEqualTo(1f)
        assertThat(GlassFaceLogic.armProgress(1000, 1400, 800)).isEqualTo(0.5f)
        assertThat(GlassFaceLogic.armProgress(1000, 2000, 800)).isEqualTo(0f)
        assertThat(GlassFaceLogic.bolusAmountText(0.5)).isEqualTo("+0.5")
        assertThat(GlassFaceLogic.bolusAmountText(1.0)).isEqualTo("+1.0")
    }

    @Test fun burnInOffsetRange() {
        assertThat(GlassFaceLogic.burnInOffset(0.0)).isEqualTo(-4f)
        assertThat(GlassFaceLogic.burnInOffset(1.0)).isEqualTo(4f)
        assertThat(GlassFaceLogic.burnInOffset(0.5)).isEqualTo(0f)
    }
}
