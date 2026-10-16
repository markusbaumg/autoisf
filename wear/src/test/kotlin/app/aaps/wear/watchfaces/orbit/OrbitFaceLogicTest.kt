package app.aaps.wear.watchfaces.orbit

import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.BgAge
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.LoopHealth
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.MoonKind
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.PhoneLink
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Range
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Ring
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.SlotInput
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Tone
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class OrbitFaceLogicTest {

    private val de = OrbitFaceLogic.Labels()
    private val en = OrbitFaceLogic.Labels(
        again = "again", cob = "COB", hoursFormat = "%d h", daysFormat = "%d d", old = "old", waiting = "Waiting for AAPS",
        phoneLostFormat = "Phone disconnected · %s", noDataFormat = "No data · %s", noProfile = "No profile", loopOff = "Loop off",
        loopOffShort = "OFF", pumpOnly = "Pump", phoneBatteryFormat = "Phone %s %%"
    )
    private val now = 1_760_000_000_000L
    private fun min(m: Long) = m * 60_000L

    // ------------------------------------------------------------ age (age.*)

    @Test fun bgAgeThresholds() {
        assertThat(OrbitFaceLogic.bgAge(null)).isEqualTo(BgAge.NONE)
        assertThat(OrbitFaceLogic.bgAge(0)).isEqualTo(BgAge.FRESH)
        assertThat(OrbitFaceLogic.bgAge(5)).isEqualTo(BgAge.FRESH)
        assertThat(OrbitFaceLogic.bgAge(6)).isEqualTo(BgAge.AGING)
        assertThat(OrbitFaceLogic.bgAge(11)).isEqualTo(BgAge.AGING)
        assertThat(OrbitFaceLogic.bgAge(12)).isEqualTo(BgAge.STALE)
        assertThat(OrbitFaceLogic.bgAge(29)).isEqualTo(BgAge.STALE)
        assertThat(OrbitFaceLogic.bgAge(30)).isEqualTo(BgAge.VERY_STALE)
        assertThat(OrbitFaceLogic.isOld(BgAge.AGING)).isFalse()
        assertThat(OrbitFaceLogic.isOld(BgAge.STALE)).isTrue()
        assertThat(OrbitFaceLogic.isOld(BgAge.NONE)).isTrue()
        assertThat(OrbitFaceLogic.isStruck(BgAge.STALE)).isTrue()
        assertThat(OrbitFaceLogic.isStruck(BgAge.VERY_STALE)).isTrue()
        assertThat(OrbitFaceLogic.isStruck(BgAge.NONE)).isFalse()
        assertThat(OrbitFaceLogic.isStruck(BgAge.AGING)).isFalse()
    }

    @Test fun bgAgeMinutes() {
        assertThat(OrbitFaceLogic.bgAgeMinutes(0L, now)).isNull()
        assertThat(OrbitFaceLogic.bgAgeMinutes(now - 61_000L, now)).isEqualTo(1L)
        // age.clock_skew: a future timestamp counts as fresh
        assertThat(OrbitFaceLogic.bgAgeMinutes(now + 30_000L, now)).isEqualTo(0L)
        // conn.restored_old_data: a 3 h old reading is very stale from the first frame
        assertThat(OrbitFaceLogic.bgAge(OrbitFaceLogic.bgAgeMinutes(now - min(180), now))).isEqualTo(BgAge.VERY_STALE)
    }

    @Test fun ageText() {
        assertThat(OrbitFaceLogic.ageText(null, de)).isEqualTo("--")
        assertThat(OrbitFaceLogic.ageText(17, de)).isEqualTo("17′")
        assertThat(OrbitFaceLogic.ageText(59, de)).isEqualTo("59′")
        assertThat(OrbitFaceLogic.ageText(60, de)).isEqualTo("1 Std")
        assertThat(OrbitFaceLogic.ageText(185, de)).isEqualTo("3 Std")
        assertThat(OrbitFaceLogic.ageText(48 * 60, de)).isEqualTo("2 T")
        assertThat(OrbitFaceLogic.ageText(185, en)).isEqualTo("3 h")
    }

    // ------------------------------------------------------------ range (bg.*)

    @Test fun rangeFromPhoneLevelAndDerivedLevels() {
        assertThat(OrbitFaceLogic.range(115.0, 0, "→", 1.0, BgAge.FRESH)).isEqualTo(Range.IN)
        assertThat(OrbitFaceLogic.range(200.0, 1, "→", 1.0, BgAge.FRESH)).isEqualTo(Range.HIGH)
        assertThat(OrbitFaceLogic.range(260.0, 1, "→", 1.0, BgAge.AGING)).isEqualTo(Range.VERY_HIGH)
        assertThat(OrbitFaceLogic.range(65.0, -1, "→", -2.0, BgAge.FRESH)).isEqualTo(Range.LOW)
        assertThat(OrbitFaceLogic.range(50.0, -1, "→", 0.0, BgAge.FRESH)).isEqualTo(Range.URGENT_LOW)
        // low and falling fast is urgent
        assertThat(OrbitFaceLogic.range(65.0, -1, "⇊", null, BgAge.FRESH)).isEqualTo(Range.URGENT_LOW)
        assertThat(OrbitFaceLogic.range(65.0, -1, "↘", -12.0, BgAge.FRESH)).isEqualTo(Range.URGENT_LOW)
        assertThat(OrbitFaceLogic.range(65.0, -1, "X", -3.0, BgAge.FRESH)).isEqualTo(Range.URGENT_LOW)
        // stale beats range
        assertThat(OrbitFaceLogic.range(50.0, -1, "→", 0.0, BgAge.STALE)).isEqualTo(Range.STALE)
        assertThat(OrbitFaceLogic.range(115.0, 0, "→", 0.0, BgAge.NONE)).isEqualTo(Range.STALE)
        assertThat(OrbitFaceLogic.emphasis(Range.URGENT_LOW)).isTrue()
        assertThat(OrbitFaceLogic.emphasis(Range.VERY_HIGH)).isTrue()
        assertThat(OrbitFaceLogic.emphasis(Range.HIGH)).isFalse()
    }

    @Test fun heroTextMmolAndMgdl() {
        // phone strings kept as they are: German comma, mg/dL integers
        assertThat(OrbitFaceLogic.heroText("6,4", 115.0, 1)).isEqualTo("6,4")
        assertThat(OrbitFaceLogic.heroText("11,8", 212.0, 1)).isEqualTo("11,8")
        assertThat(OrbitFaceLogic.heroText("115", 115.0, 1)).isEqualTo("115")
        // sentinels
        assertThat(OrbitFaceLogic.heroText("400", 400.0, 1)).isEqualTo("400+")
        assertThat(OrbitFaceLogic.heroText("22,2", 400.0, 1)).isEqualTo("22,2+")
        assertThat(OrbitFaceLogic.heroText("39", 39.0, 1)).isEqualTo("39")
        // no reading, very old
        assertThat(OrbitFaceLogic.heroText("6,4", 115.0, null)).isEqualTo("---")
        assertThat(OrbitFaceLogic.heroText("6,4", 115.0, 59)).isEqualTo("6,4")
        assertThat(OrbitFaceLogic.heroText("6,4", 115.0, 60)).isEqualTo("---")
    }

    @Test fun arrows() {
        assertThat(OrbitFaceLogic.arrowText("→", 0.0)).isEqualTo("→")
        assertThat(OrbitFaceLogic.arrowText("⇊", -12.0)).isEqualTo("⇊")
        assertThat(OrbitFaceLogic.arrowText("X", -20.0)).isEqualTo("⇊")
        assertThat(OrbitFaceLogic.arrowText("X", 20.0)).isEqualTo("⇈")
        assertThat(OrbitFaceLogic.arrowText("??", 0.0)).isEmpty()
        assertThat(OrbitFaceLogic.arrowText("--", null)).isEmpty()
    }

    @Test fun deltaRow() {
        val fresh = OrbitFaceLogic.deltaRow("+0,1", 1, de)
        assertThat(fresh.deltaPart + fresh.agePart).isEqualTo("+0,1 · 1′")
        assertThat(fresh.ageAmber).isFalse()
        assertThat(OrbitFaceLogic.deltaRow("+0,1", 5, de).ageAmber).isFalse()
        assertThat(OrbitFaceLogic.deltaRow("+0,1", 6, de).ageAmber).isTrue()
        // mg/dL delta strings pass through unchanged
        val mg = OrbitFaceLogic.deltaRow("-12", 3, de)
        assertThat(mg.deltaPart + mg.agePart).isEqualTo("-12 · 3′")
        // delta.missing
        assertThat(OrbitFaceLogic.deltaRow("--", 3, de).deltaPart).isEqualTo("-- · ")
        assertThat(OrbitFaceLogic.oldAmbientText(17, de)).isEqualTo("– – 17′ alt")
        assertThat(OrbitFaceLogic.oldAmbientText(17, en)).isEqualTo("– – 17′ old")
    }

    // ------------------------------------------------------------ phone link and status slot (§4.2)

    @Test fun phoneLink() {
        assertThat(OrbitFaceLogic.phoneLink(true, min(3))).isEqualTo(PhoneLink.OK)
        assertThat(OrbitFaceLogic.phoneLink(false, min(3))).isEqualTo(PhoneLink.LOST_FRESH)
        assertThat(OrbitFaceLogic.phoneLink(false, min(12))).isEqualTo(PhoneLink.LOST_STALE)
        assertThat(OrbitFaceLogic.phoneLink(true, min(12))).isEqualTo(PhoneLink.SILENT)
        assertThat(OrbitFaceLogic.phoneLink(false, Long.MAX_VALUE)).isEqualTo(PhoneLink.LOST_STALE)
    }

    private fun input(
        firstStart: Boolean = false, link: PhoneLink = PhoneLink.OK, dataAge: Long? = 1, bgAgeMin: Long? = 1,
        range: Range = Range.IN, noProfile: Boolean = false, loopOff: Boolean = false, loopAge: Long? = 2,
        resLevel: Int = 0, res: String = "86U", battLevel: Int = 1, batt: String = "64"
    ) = SlotInput(
        firstStart, link, dataAge, bgAgeMin, OrbitFaceLogic.bgAge(bgAgeMin), range, noProfile, loopOff,
        OrbitFaceLogic.loopHealth(loopAge) == LoopHealth.BAD, loopAge, resLevel, res, battLevel, batt
    )

    @Test fun statusSlotHierarchy() {
        assertThat(OrbitFaceLogic.statusSlot(input(), false, de)).isNull()
        assertThat(OrbitFaceLogic.statusSlot(input(firstStart = true, bgAgeMin = null), false, de))
            .isEqualTo(OrbitFaceLogic.Slot(1, "Warte auf AAPS", Tone.INFO))
        assertThat(OrbitFaceLogic.statusSlot(input(link = PhoneLink.LOST_STALE, dataAge = 14, bgAgeMin = 14, range = Range.STALE), false, de))
            .isEqualTo(OrbitFaceLogic.Slot(2, "Telefon getrennt · 14′", Tone.ALERT))
        assertThat(OrbitFaceLogic.statusSlot(input(link = PhoneLink.LOST_STALE, dataAge = 14), false, en)!!.text)
            .isEqualTo("Phone disconnected · 14′")
        assertThat(OrbitFaceLogic.statusSlot(input(bgAgeMin = 17, range = Range.STALE), false, de))
            .isEqualTo(OrbitFaceLogic.Slot(3, "Keine Daten · 17′", Tone.ALERT))
        assertThat(OrbitFaceLogic.statusSlot(input(link = PhoneLink.SILENT, dataAge = 13, bgAgeMin = 5), false, de)!!.rank).isEqualTo(3)
        assertThat(OrbitFaceLogic.statusSlot(input(noProfile = true, loopOff = true), false, de))
            .isEqualTo(OrbitFaceLogic.Slot(4, "Kein Profil", Tone.WARN))
        assertThat(OrbitFaceLogic.statusSlot(input(loopOff = true, loopAge = 30), false, de))
            .isEqualTo(OrbitFaceLogic.Slot(5, "Loop aus", Tone.WARN))
        assertThat(OrbitFaceLogic.statusSlot(input(loopAge = 23, resLevel = 2, res = "5U"), false, de))
            .isEqualTo(OrbitFaceLogic.Slot(6, "Loop 23′", Tone.ALERT))
        assertThat(OrbitFaceLogic.statusSlot(input(resLevel = 2, res = "5U", battLevel = 0, batt = "18"), false, de))
            .isEqualTo(OrbitFaceLogic.Slot(7, "Reservoir 5U", Tone.ALERT))
        assertThat(OrbitFaceLogic.statusSlot(input(battLevel = 0, batt = "18", resLevel = 1, res = "18U"), false, de))
            .isEqualTo(OrbitFaceLogic.Slot(8, "Tel 18 %", Tone.WARN))
        assertThat(OrbitFaceLogic.statusSlot(input(battLevel = 0, batt = "7"), false, de)!!.tone).isEqualTo(Tone.ALERT)
        assertThat(OrbitFaceLogic.statusSlot(input(resLevel = 1, res = "18U"), false, de))
            .isEqualTo(OrbitFaceLogic.Slot(9, "Reservoir 18U", Tone.WARN))
        assertThat(OrbitFaceLogic.statusSlot(input(link = PhoneLink.LOST_FRESH), false, de)!!.tone).isEqualTo(Tone.GLYPH)
    }

    @Test fun statusSlotNeverRedForUnknown() {
        // pump.res_unknown: level 2 with an empty string is meaningless
        assertThat(OrbitFaceLogic.statusSlot(input(resLevel = 2, res = ""), false, de)).isNull()
        assertThat(OrbitFaceLogic.statusSlot(input(resLevel = 2, res = "--"), false, de)).isNull()
        // battery "0" is unknown
        assertThat(OrbitFaceLogic.statusSlot(input(battLevel = 0, batt = "0"), false, de)).isNull()
        // loop.never is grey, not "Loop --"
        assertThat(OrbitFaceLogic.statusSlot(input(loopAge = null), false, de)).isNull()
    }

    @Test fun statusSlotSuppressionAndAmbient() {
        // a fresh low suppresses W7..W10
        assertThat(OrbitFaceLogic.statusSlot(input(range = Range.LOW, resLevel = 2, res = "5U"), false, de)).isNull()
        assertThat(OrbitFaceLogic.statusSlot(input(range = Range.URGENT_LOW, loopAge = 23), false, de)!!.rank).isEqualTo(6)
        // ambient keeps W1..W6 only
        assertThat(OrbitFaceLogic.statusSlot(input(resLevel = 2, res = "5U"), true, de)).isNull()
        assertThat(OrbitFaceLogic.statusSlot(input(loopOff = true), true, de)!!.rank).isEqualTo(5)
        assertThat(OrbitFaceLogic.slotReplacesDelta(OrbitFaceLogic.Slot(3, "x", Tone.ALERT))).isTrue()
        assertThat(OrbitFaceLogic.slotReplacesDelta(OrbitFaceLogic.Slot(6, "x", Tone.ALERT))).isFalse()
        assertThat(OrbitFaceLogic.slotReplacesDelta(null)).isFalse()
    }

    @Test fun firstStartNoProfileLoopOff() {
        assertThat(OrbitFaceLogic.firstStart(0L, -1L, "IOB")).isTrue()
        assertThat(OrbitFaceLogic.firstStart(now, -1L, "IOB")).isFalse()
        assertThat(OrbitFaceLogic.firstStart(0L, now, "1,25")).isFalse()
        // the phone sends a localised status text; without a profile it sends no IOB
        assertThat(OrbitFaceLogic.noProfile("Kein Profil gesetzt", "")).isTrue()
        assertThat(OrbitFaceLogic.loopOff("Kein Profil gesetzt", "")).isFalse()
        assertThat(OrbitFaceLogic.loopOff("Loop deaktiviert\n", "1,25")).isTrue()
        assertThat(OrbitFaceLogic.noProfile("Loop deaktiviert\n", "1,25")).isFalse()
        assertThat(OrbitFaceLogic.loopOff("", "1,25")).isFalse()
        assertThat(OrbitFaceLogic.loopOff("\n", "1,25")).isFalse()
    }

    // ------------------------------------------------------------ balls

    @Test fun loopBall() {
        assertThat(OrbitFaceLogic.loopBall(now, "", "1,25", now, de)).isEqualTo(OrbitFaceLogic.Ball("0′", "LOOP", Ring.GREEN))
        assertThat(OrbitFaceLogic.loopBall(now - min(5), "", "1,25", now, de).ring).isEqualTo(Ring.GREEN)
        assertThat(OrbitFaceLogic.loopBall(now - min(6), "", "1,25", now, de).ring).isEqualTo(Ring.AMBER)
        assertThat(OrbitFaceLogic.loopBall(now - min(14), "", "1,25", now, de).ring).isEqualTo(Ring.AMBER)
        val red = OrbitFaceLogic.loopBall(now - min(23), "", "1,25", now, de)
        assertThat(red.value).isEqualTo("23′")
        assertThat(red.ring).isEqualTo(Ring.RED)
        assertThat(OrbitFaceLogic.loopBall(now - min(130), "", "1,25", now, de).value).isEqualTo("2 Std")
        // loop.never: dashed grey "--", never red
        assertThat(OrbitFaceLogic.loopBall(-1L, "", "", now, de)).isEqualTo(OrbitFaceLogic.Ball("--", "LOOP", Ring.DIM, dashed = true))
        // loop.no_aps: pump data without a loop run
        assertThat(OrbitFaceLogic.loopBall(-1L, "", "1,25", now, de)).isEqualTo(OrbitFaceLogic.Ball("Pumpe", "", Ring.DIM))
        // loop.off: amber pause glyph + "AUS"
        assertThat(OrbitFaceLogic.loopBall(now, "Loop deaktiviert\n", "1,25", now, de))
            .isEqualTo(OrbitFaceLogic.Ball("", "AUS", Ring.AMBER, pause = true))
        assertThat(OrbitFaceLogic.loopBall(now, "Loop disabled\n", "1.25", now, en).label).isEqualTo("OFF")
    }

    @Test fun basalBall() {
        assertThat(OrbitFaceLogic.basalBall("0,85 U/h", tbrHint = false)).isEqualTo(OrbitFaceLogic.Ball("0,85", "BASAL", Ring.NEUTRAL))
        assertThat(OrbitFaceLogic.basalBall("0.85 U/h", tbrHint = false).value).isEqualTo("0.85")
        assertThat(OrbitFaceLogic.basalBall("0,00 U/h", tbrHint = true)).isEqualTo(OrbitFaceLogic.Ball("0,00", "TBR", Ring.AMBER))
        // basal.zero_abs without a known TBR still deserves attention
        assertThat(OrbitFaceLogic.basalBall("0,00 U/h", tbrHint = false)).isEqualTo(OrbitFaceLogic.Ball("0,00", "BASAL", Ring.AMBER))
        assertThat(OrbitFaceLogic.basalBall("1,20 U/h", tbrHint = true)).isEqualTo(OrbitFaceLogic.Ball("1,20", "TBR", Ring.TEAL))
        // basal.tbr_percent: a "%" rate is a TBR even without the treatment hint
        assertThat(OrbitFaceLogic.basalBall("120%", tbrHint = false)).isEqualTo(OrbitFaceLogic.Ball("120%", "TBR", Ring.TEAL))
        assertThat(OrbitFaceLogic.basalBall("0%", tbrHint = false).ring).isEqualTo(Ring.AMBER)
        assertThat(OrbitFaceLogic.basalBall("", tbrHint = false)).isEqualTo(OrbitFaceLogic.Ball("--", "BASAL", Ring.DIM))
        assertThat(OrbitFaceLogic.basalBall("-.--U/h", tbrHint = false).value).isEqualTo("--")
    }

    @Test fun tbrRunning() {
        val starts = longArrayOf(now - min(60), now - min(20))
        val ends = longArrayOf(now - min(30), now + min(5))
        assertThat(OrbitFaceLogic.tbrRunning(starts, ends, now)).isTrue()
        assertThat(OrbitFaceLogic.tbrRunning(starts, ends, now + min(6))).isFalse()
        assertThat(OrbitFaceLogic.tbrRunning(LongArray(0), LongArray(0), now)).isFalse()
    }

    @Test fun iobBall() {
        val iob = OrbitFaceLogic.iobBall("1,25")
        assertThat(iob.value).isEqualTo("1,25")   // phone locale kept
        assertThat(iob.ring).isEqualTo(Ring.CYAN)
        assertThat(iob.arc).isWithin(1e-4f).of(1.25f / 6f)
        assertThat(iob.dimValue).isFalse()
        // iob.zero: dim, no arc
        val zero = OrbitFaceLogic.iobBall("0,00")
        assertThat(zero.arc).isEqualTo(0f)
        assertThat(zero.dimValue).isTrue()
        // iob.negative: minus kept, no reverse arc, normal text
        val neg = OrbitFaceLogic.iobBall("-0,35")
        assertThat(neg.value).isEqualTo("-0,35")
        assertThat(neg.arc).isEqualTo(0f)
        assertThat(neg.dimValue).isFalse()
        // iob.large: capped
        assertThat(OrbitFaceLogic.iobBall("12,40").arc).isEqualTo(1f)
        // iob.unknown / placeholders
        assertThat(OrbitFaceLogic.iobBall("").value).isEqualTo("--")
        assertThat(OrbitFaceLogic.iobBall("IOB").value).isEqualTo("--")
        assertThat(OrbitFaceLogic.iobBall("IOB").dimValue).isTrue()
    }

    @Test fun cobAndTt() {
        assertThat(OrbitFaceLogic.cobBall("0g", de)).isEqualTo(OrbitFaceLogic.Ball("0g", "KH", Ring.DIM))
        assertThat(OrbitFaceLogic.cobBall("0g", en).label).isEqualTo("COB")
        assertThat(OrbitFaceLogic.cobBall("12g", de).ring).isEqualTo(Ring.AMBER)
        assertThat(OrbitFaceLogic.cobBall("20(30)g", de)).isEqualTo(OrbitFaceLogic.Ball("20(30)g", "KH", Ring.AMBER))
        assertThat(OrbitFaceLogic.cobBall("--g", de)).isEqualTo(OrbitFaceLogic.Ball("--", "KH", Ring.DIM))
        assertThat(OrbitFaceLogic.ttBall("8,0", 2)).isEqualTo(OrbitFaceLogic.Ball("8,0", "TT", Ring.VIOLET))
        assertThat(OrbitFaceLogic.ttBall("4,4 - 5,0", 2)!!.value).isEqualTo("4,4-5,0")
        assertThat(OrbitFaceLogic.ttBall("101", 2)!!.value).isEqualTo("101")
        // tt.profile, tt.aps_adjusted, tt.unknown: no ball
        assertThat(OrbitFaceLogic.ttBall("5,0 - 6,0", 0)).isNull()
        assertThat(OrbitFaceLogic.ttBall("6,0", 1)).isNull()
        assertThat(OrbitFaceLogic.ttBall("", 2)).isNull()
    }

    @Test fun displayFallbacks() {
        for (s in listOf("", " ", "IOB", "-.--", "--g", "-.--U/h", "no status", "??", "--")) assertThat(OrbitFaceLogic.display(s)).isEqualTo("--")
        assertThat(OrbitFaceLogic.display(" 1,25 ")).isEqualTo("1,25")
    }

    @Test fun bolusAmountUsesWatchLocale() {
        assertThat(OrbitFaceLogic.bolusAmountText(0.5, Locale.GERMANY)).isEqualTo("0,5 U")
        assertThat(OrbitFaceLogic.bolusAmountText(1.0, Locale.US)).isEqualTo("1.0 U")
    }

    // ------------------------------------------------------------ time and date

    @Test fun germanDateFromRendererTime() {
        assertThat(OrbitFaceLogic.dateString(2025, 10, 17)).isEqualTo("Fr 17. Okt")
        assertThat(OrbitFaceLogic.dateString(2026, 10, 17)).isEqualTo("Sa 17. Okt")
        assertThat(OrbitFaceLogic.dateString(2026, 5, 1)).isEqualTo("Fr 1. Mai")
        assertThat(OrbitFaceLogic.dateString(2026, 3, 2)).isEqualTo("Mo 2. März")
        assertThat(OrbitFaceLogic.dateString(2026, 9, 30)).startsWith("Mi 30. Sep")
        assertThat(OrbitFaceLogic.dateString(2026, 9, 30)).doesNotContain(".S")
        assertThat(OrbitFaceLogic.dateString(2026, 12, 31)).isEqualTo("Do 31. Dez")
    }

    @Test fun timeFormat() {
        assertThat(OrbitFaceLogic.timeString(0, 40, true)).isEqualTo("00:40")
        assertThat(OrbitFaceLogic.timeString(13, 5, true)).isEqualTo("13:05")
        assertThat(OrbitFaceLogic.timeString(0, 40, false)).isEqualTo("12:40")
        assertThat(OrbitFaceLogic.timeString(13, 5, false)).isEqualTo("1:05")
    }

    @Test fun burnInCycle() {
        val shifts = (0..4).map { OrbitFaceLogic.burnInShiftX(it) to OrbitFaceLogic.burnInShiftY(it) }
        assertThat(shifts).containsExactly(0f to 0f, 2f to 0f, 2f to 2f, 0f to 2f, 0f to 0f).inOrder()
    }

    // ------------------------------------------------------------ geometry, widths, moon

    @Test fun geometry() {
        assertThat(OrbitFaceLogic.pointX(168f, 0f)).isWithin(0.01f).of(225f)
        assertThat(OrbitFaceLogic.pointY(168f, 0f)).isWithin(0.01f).of(57f)
        assertThat(OrbitFaceLogic.pointX(168f, 45f)).isWithin(0.1f).of(343.8f)
        assertThat(OrbitFaceLogic.pointY(168f, 45f)).isWithin(0.1f).of(106.2f)
        assertThat(OrbitFaceLogic.pointX(168f, 270f)).isWithin(0.01f).of(57f)
        assertThat(OrbitFaceLogic.pointY(168f, 180f)).isWithin(0.01f).of(393f)
        // round-safe: the bolus button corner stays inside r 205
        val corner = kotlin.math.hypot(225f - (57f - 33f) - 16f * (1 - 0.7071f), 33f - 16f * (1 - 0.7071f))
        assertThat(corner).isLessThan(205f)
        // the moved sparkline (status pill shown) stays above the lower balls' tops and the date line
        assertThat(OrbitFaceLogic.SPARK_Y_BOTTOM_PILL).isLessThan(393f - 12f)
        assertThat(OrbitFaceLogic.SLOT_Y + 13f).isLessThan(OrbitFaceLogic.SPARK_Y_TOP_PILL)
    }

    @Test fun gapWidths() {
        // "00:40" at 30 px is about 80 px wide -> (80 + 20) / 2 / 168 rad = 17 deg
        assertThat(OrbitFaceLogic.gapHalfDeg(80f)).isWithin(0.1f).of(17.05f)
        assertThat(OrbitFaceLogic.gapHalfDeg(400f)).isEqualTo(OrbitFaceLogic.MAX_GAP_DEG)
        assertThat(180f - OrbitFaceLogic.MAX_GAP_DEG).isGreaterThan(OrbitFaceLogic.DEG_BASAL + OrbitFaceLogic.BALL_HALF_DEG)
    }

    @Test fun worstCaseWidths() {
        // ball value: 22 px up to 56 px, 17 px bold beyond, shrink when even that is too wide
        assertThat(OrbitFaceLogic.ballValueSize(50f)).isEqualTo(22f)
        assertThat(OrbitFaceLogic.ballValueSize(70f)).isEqualTo(17f)     // "12,40", "20(30)g"
        val tiny = OrbitFaceLogic.ballValueSize(110f)                   // "4,4-5,0"
        assertThat(tiny).isLessThan(17f)
        assertThat(110f * tiny / 22f).isWithin(0.01f).of(OrbitFaceLogic.BALL_VALUE_HARD_MAX)
        // hero "400+" / "22,2+" shrink to 200 px
        assertThat(OrbitFaceLogic.fitSize(190f, 108f, OrbitFaceLogic.HERO_MAX_WIDTH)).isEqualTo(108f)
        assertThat(OrbitFaceLogic.fitSize(250f, 108f, OrbitFaceLogic.HERO_MAX_WIDTH) * 250f / 108f).isWithin(0.01f).of(200f)
        // the hero plus arrow never reaches the IOB ball (left edge 359)
        val heroRight = 209f + OrbitFaceLogic.HERO_MAX_WIDTH / 2f
        assertThat(heroRight + 24f + 22f).isLessThan(OrbitFaceLogic.pointX(168f, 90f) - OrbitFaceLogic.BALL_R)
        // slot pill: the widest text is capped so the pill (+ padding and icon) stays between the lower balls
        val pillHalf = (OrbitFaceLogic.SLOT_MAX_WIDTH + 20f) / 2f + 14f
        assertThat(OrbitFaceLogic.CX + pillHalf).isLessThan(450f - 50f)
    }

    @Test fun moonPositions() {
        val gapT = 17f
        val gapD = 20f
        assertThat(OrbitFaceLogic.moon(0, gapT, gapD, false).kind).isEqualTo(MoonKind.HIDDEN)
        assertThat(OrbitFaceLogic.moon(57, gapT, gapD, false).kind).isEqualTo(MoonKind.HIDDEN)
        assertThat(OrbitFaceLogic.moon(30, gapT, gapD, false).kind).isEqualTo(MoonKind.HIDDEN)
        val dot = OrbitFaceLogic.moon(40, gapT, gapD, false)
        assertThat(dot.kind).isEqualTo(MoonKind.DOT)
        assertThat(dot.deg).isEqualTo(240f)
        // 9 o'clock: free without the button, hidden behind it
        assertThat(OrbitFaceLogic.moon(45, gapT, gapD, false).kind).isEqualTo(MoonKind.DOT)
        assertThat(OrbitFaceLogic.moon(45, gapT, gapD, true).kind).isEqualTo(MoonKind.HIDDEN)
        // behind the TT ball at 315 deg: 00:52 -> 312 deg, rim highlight on the trailing side
        val rim = OrbitFaceLogic.moon(52, gapT, gapD, true)
        assertThat(rim.kind).isEqualTo(MoonKind.RIM)
        assertThat(rim.ball).isEqualTo(OrbitFaceLogic.IDX_TT)
        assertThat(rim.rimDeg).isWithin(0.5f).of(315f - 3f / OrbitFaceLogic.BALL_OCCLUDE_DEG * 90f)
        // without a TT ball the moon is a plain dot there
        val shown = booleanArrayOf(true, true, true, true, false)
        assertThat(OrbitFaceLogic.moon(52, gapT, gapD, true, shown).kind).isEqualTo(MoonKind.DOT)
        val loop = OrbitFaceLogic.moon(7, gapT, gapD, false, shown)
        assertThat(loop.kind).isEqualTo(MoonKind.RIM)
        assertThat(loop.ball).isEqualTo(OrbitFaceLogic.IDX_LOOP)
        assertThat(OrbitFaceLogic.BALL_OCCLUDE_DEG).isWithin(0.1f).of(13.2f)
    }

    @Test fun angleDiffWraps() {
        assertThat(OrbitFaceLogic.angleDiff(350f, 10f)).isEqualTo(-20f)
        assertThat(OrbitFaceLogic.angleDiff(10f, 350f)).isEqualTo(20f)
        assertThat(OrbitFaceLogic.angleDiff(180f, 0f)).isEqualTo(180f)
    }

    // ------------------------------------------------------------ sparkline

    @Test fun sparkline() {
        assertThat(OrbitFaceLogic.sparkY(45.0)).isEqualTo(OrbitFaceLogic.SPARK_Y_BOTTOM)
        assertThat(OrbitFaceLogic.sparkY(252.0)).isEqualTo(OrbitFaceLogic.SPARK_Y_TOP)
        assertThat(OrbitFaceLogic.sparkY(400.0)).isEqualTo(OrbitFaceLogic.SPARK_Y_TOP)
        assertThat(OrbitFaceLogic.sparkY(20.0)).isEqualTo(OrbitFaceLogic.SPARK_Y_BOTTOM)
        assertThat(OrbitFaceLogic.sparkY(252.0, OrbitFaceLogic.SPARK_Y_TOP_PILL, OrbitFaceLogic.SPARK_Y_BOTTOM_PILL)).isEqualTo(OrbitFaceLogic.SPARK_Y_TOP_PILL)
        // the 10 mmol/L (180 mg/dL) high line sits at about 35 % from the top
        val f = (OrbitFaceLogic.sparkY(180.0) - OrbitFaceLogic.SPARK_Y_TOP) / (OrbitFaceLogic.SPARK_Y_BOTTOM - OrbitFaceLogic.SPARK_Y_TOP)
        assertThat(f).isWithin(0.02f).of(0.35f)
        assertThat(OrbitFaceLogic.sparkClamp(30.0)).isEqualTo(-1)
        assertThat(OrbitFaceLogic.sparkClamp(300.0)).isEqualTo(1)
        assertThat(OrbitFaceLogic.sparkClamp(120.0)).isEqualTo(0)
        assertThat(OrbitFaceLogic.sparkX(now, now)).isEqualTo(OrbitFaceLogic.SPARK_X_NOW)
        assertThat(OrbitFaceLogic.sparkX(now - OrbitFaceLogic.SPARK_HISTORY_MS, now)).isWithin(0.01f).of(OrbitFaceLogic.SPARK_X0)
        assertThat(OrbitFaceLogic.predictionX(5)).isWithin(0.01f).of(OrbitFaceLogic.SPARK_X1)
        // graph.gaps
        assertThat(OrbitFaceLogic.sparkBreak(now, now + min(5))).isFalse()
        assertThat(OrbitFaceLogic.sparkBreak(now, now + min(16))).isTrue()
    }
}
