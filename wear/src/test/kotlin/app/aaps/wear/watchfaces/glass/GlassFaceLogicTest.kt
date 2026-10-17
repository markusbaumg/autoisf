package app.aaps.wear.watchfaces.glass

import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.PhoneReach
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.AgeWords
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.BallState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.FooterInput
import app.aaps.wear.watchfaces.common.FaceState.Level
import app.aaps.wear.watchfaces.common.FaceState.LoopHealth
import app.aaps.wear.watchfaces.common.FaceState.LoopMode
import app.aaps.wear.watchfaces.common.FaceState.LoopRun
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.AmbTone
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.OutlineStyle
import app.aaps.wear.watchfaces.common.FaceState.PhoneLink
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Pills
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.RunKind
import app.aaps.wear.watchfaces.common.FaceState.Sentinel
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.SlotInput
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.SlotWords
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Tone
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Trend
import app.aaps.wear.watchfaces.common.FaceState.Warning
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

class GlassFaceLogicTest {

    private val now = 1_760_000_000_000L
    private val min = 60_000L
    private val de = AgeWords(hours = { "$it Std" }, days = { "$it T" })

    // ------------------------------------------------------------------ age / stale

    @Test fun bgAgeBuckets() {
        assertThat(GlassFaceLogic.bgAge(null)).isEqualTo(BgAge.NONE)
        assertThat(GlassFaceLogic.bgAge(5)).isEqualTo(BgAge.FRESH)
        assertThat(GlassFaceLogic.bgAge(6)).isEqualTo(BgAge.AGING)
        assertThat(GlassFaceLogic.bgAge(11)).isEqualTo(BgAge.AGING)
        assertThat(GlassFaceLogic.bgAge(12)).isEqualTo(BgAge.STALE)
        assertThat(GlassFaceLogic.bgAge(29)).isEqualTo(BgAge.STALE)
        assertThat(GlassFaceLogic.bgAge(30)).isEqualTo(BgAge.VERY_STALE)
        assertThat(GlassFaceLogic.isStale(BgAge.NONE)).isTrue()
        assertThat(GlassFaceLogic.isStale(BgAge.AGING)).isFalse()
        // strike only for a real but old reading
        assertThat(GlassFaceLogic.strikeThrough(BgAge.STALE)).isTrue()
        assertThat(GlassFaceLogic.strikeThrough(BgAge.NONE)).isFalse()
    }

    @Test fun ageMinutesClampsClockSkew() {
        assertThat(GlassFaceLogic.ageMinutes(now - 61_000, now)).isEqualTo(1L)
        assertThat(GlassFaceLogic.ageMinutes(now + 5_000, now)).isEqualTo(0L)
        assertThat(GlassFaceLogic.ageMinutes(0L, now)).isNull()
    }

    @Test fun ageTextMinutesHoursDays() {
        assertThat(GlassFaceLogic.ageText(1, de)).isEqualTo("1′")
        assertThat(GlassFaceLogic.ageText(59, de)).isEqualTo("59′")
        assertThat(GlassFaceLogic.ageText(60, de)).isEqualTo("1 Std")
        assertThat(GlassFaceLogic.ageText(185, de)).isEqualTo("3 Std")
        assertThat(GlassFaceLogic.ageText(48 * 60, de)).isEqualTo("2 T")
        assertThat(GlassFaceLogic.ageText(null, de)).isEqualTo("--")
    }

    @Test fun ageColourAndVisibility() {
        assertThat(GlassFaceLogic.ageLevel(5)).isEqualTo(Level.NORMAL)
        assertThat(GlassFaceLogic.ageLevel(6)).isEqualTo(Level.WARN)
        assertThat(GlassFaceLogic.ageLevel(12)).isEqualTo(Level.ALERT)
        assertThat(GlassFaceLogic.ageLevel(null)).isEqualTo(Level.ALERT)
        // key_show_ago off hides only a fresh age; the warning is not optional
        assertThat(GlassFaceLogic.showAge(false, BgAge.FRESH)).isFalse()
        assertThat(GlassFaceLogic.showAge(false, BgAge.AGING)).isTrue()
        assertThat(GlassFaceLogic.showAge(false, BgAge.STALE)).isTrue()
    }

    // ------------------------------------------------------------------ range

    @Test fun rangeUsesThePhoneMarksAndDerivedUrgency() {
        fun r(sgv: Double, lo: Double = 70.0, hi: Double = 180.0, age: BgAge = BgAge.FRESH, fall: Boolean = false) =
            GlassFaceLogic.range(sgv, lo, hi, age, fall)
        assertThat(r(169.0)).isEqualTo(Range.IN)
        assertThat(r(180.0)).isEqualTo(Range.IN)
        assertThat(r(212.0)).isEqualTo(Range.HIGH)
        assertThat(r(251.0)).isEqualTo(Range.VERY_HIGH)
        assertThat(r(61.0)).isEqualTo(Range.LOW)
        assertThat(r(53.0)).isEqualTo(Range.URGENT_LOW)
        assertThat(r(61.0, fall = true)).isEqualTo(Range.URGENT_LOW)  // low + fast fall
        assertThat(r(150.0, 80.0, 140.0)).isEqualTo(Range.HIGH)       // custom marks win
        assertThat(r(150.0, 0.0, 0.0)).isEqualTo(Range.IN)            // fallback 70/180
        assertThat(r(190.0, 0.0, 0.0)).isEqualTo(Range.HIGH)
        // stale beats range: an old low must not look current
        assertThat(r(50.0, age = BgAge.STALE)).isEqualTo(Range.STALE)
        assertThat(r(0.0, age = BgAge.NONE)).isEqualTo(Range.STALE)
        // aging keeps the range hue
        assertThat(r(212.0, age = BgAge.AGING)).isEqualTo(Range.HIGH)
    }

    @Test fun sentinelsAndBgText() {
        assertThat(GlassFaceLogic.sentinel(39.0, BgAge.FRESH)).isEqualTo(Sentinel.LOW)
        assertThat(GlassFaceLogic.sentinel(400.0, BgAge.FRESH)).isEqualTo(Sentinel.HIGH)
        assertThat(GlassFaceLogic.sentinel(400.0, BgAge.STALE)).isNull()
        assertThat(GlassFaceLogic.sentinel(120.0, BgAge.FRESH)).isNull()
        assertThat(GlassFaceLogic.bgText("400", 1, Sentinel.HIGH)).isEqualTo("400+")
        assertThat(GlassFaceLogic.bgText("22,2", 1, Sentinel.HIGH)).isEqualTo("22,2+")
        // Glass polish round 6: the low sentinel is marked symmetrically to the "+" ("the sensor reads at most this")
        assertThat(GlassFaceLogic.bgText("2,2", 1, Sentinel.LOW)).isEqualTo("<2,2")
        assertThat(GlassFaceLogic.bgText("39", 1, Sentinel.LOW)).isEqualTo("<39")
        assertThat(GlassFaceLogic.bgText("<2,2", 1, Sentinel.LOW)).isEqualTo("<2,2")
        assertThat(GlassFaceLogic.bgText("2,2", 60, Sentinel.LOW)).isEqualTo("---")
        assertThat(GlassFaceLogic.bgText("9,4", 42, null)).isEqualTo("9,4")   // very stale: still struck number
        assertThat(GlassFaceLogic.bgText("9,4", 60, null)).isEqualTo("---")   // from 60': digits replaced
        assertThat(GlassFaceLogic.bgText("---", null, null)).isEqualTo("---")
        assertThat(GlassFaceLogic.bgText("9,4", null, null)).isEqualTo("---") // no time stamp
    }

    /** Glass polish round 7: the sentinel marks are split off the number and drawn small beside it. */
    @Test fun sentinelMarksSplit() {
        assertThat(GlassFaceLogic.splitSentinel("<2,2")).isEqualTo(Triple("<", "2,2", ""))
        assertThat(GlassFaceLogic.splitSentinel("22,3+")).isEqualTo(Triple("", "22,3", "+"))
        assertThat(GlassFaceLogic.splitSentinel("<39")).isEqualTo(Triple("<", "39", ""))
        assertThat(GlassFaceLogic.splitSentinel("400+")).isEqualTo(Triple("", "400", "+"))
        assertThat(GlassFaceLogic.splitSentinel("6,4")).isEqualTo(Triple("", "6,4", ""))
        assertThat(GlassFaceLogic.splitSentinel("---")).isEqualTo(Triple("", "---", ""))
        assertThat(GlassFaceLogic.splitSentinel("+")).isEqualTo(Triple("", "+", ""))
        // the marks at 55-60 % of the digits, a 2-4 px ink gap
        assertThat(GlassFaceLogic.SENTINEL_MARK_SCALE).isAtLeast(0.55f)
        assertThat(GlassFaceLogic.SENTINEL_MARK_SCALE).isAtMost(0.60f)
        assertThat(GlassFaceLogic.SENTINEL_MARK_GAP).isAtLeast(2f)
        assertThat(GlassFaceLogic.SENTINEL_MARK_GAP).isAtMost(4f)
    }

    /** Glass polish round 7: the widest hero group keeps 16 px of glass to the card on both sides. */
    @Test fun widestHeroGroupPadding() {
        val l = GlassFaceLogic.heroGroup(GlassFaceLogic.heroLayout(showChip = true, metaEmpty = false), true, 400f)
        val left = l.bgX - GlassFaceLogic.BG_MAX_WIDTH / 2f - GlassFaceLogic.HERO_X0
        val right = GlassFaceLogic.HERO_X1 - (l.chipX + GlassFaceLogic.CHIP_R)
        assertThat(left).isWithin(0.01f).of(right)
        assertThat(right).isAtLeast(16f)
    }

    // ------------------------------------------------------------------ trend / delta

    @Test fun trendMapping() {
        assertThat(GlassFaceLogic.trend("→", null)).isEqualTo(Trend.FLAT)
        assertThat(GlassFaceLogic.trend("↗", null)).isEqualTo(Trend.UP45)
        assertThat(GlassFaceLogic.trend("⇈", null)).isEqualTo(Trend.UP2)
        assertThat(GlassFaceLogic.trend("⇊", null)).isEqualTo(Trend.DOWN2)
        assertThat(GlassFaceLogic.trend("X", -30.0)).isEqualTo(Trend.DOWN3)
        assertThat(GlassFaceLogic.trend("X", 30.0)).isEqualTo(Trend.UP3)
        assertThat(GlassFaceLogic.trend("X", null)).isEqualTo(Trend.NONE)
        assertThat(GlassFaceLogic.trend("??", null)).isEqualTo(Trend.NONE)
        assertThat(GlassFaceLogic.trend("--", null)).isEqualTo(Trend.NONE)
        assertThat(Trend.DOWN2.double).isTrue()
        assertThat(GlassFaceLogic.fastFall(Trend.DOWN, -10.0)).isTrue()
        assertThat(GlassFaceLogic.fastFall(Trend.DOWN, -9.0)).isFalse()
        assertThat(GlassFaceLogic.fastFall(Trend.DOWN3, null)).isTrue()
    }

    @Test fun deltaFormattingKeepsPhoneLocale() {
        assertThat(GlassFaceLogic.deltaText("+0,1")).isEqualTo("+0,1")
        assertThat(GlassFaceLogic.deltaText("-0,4")).isEqualTo("-0,4")
        assertThat(GlassFaceLogic.deltaText("+12")).isEqualTo("+12")
        assertThat(GlassFaceLogic.deltaText("0.0")).isEqualTo("±0.0")
        assertThat(GlassFaceLogic.deltaText("--")).isEqualTo("--")
        assertThat(GlassFaceLogic.deltaText("")).isEqualTo("--")
        assertThat(GlassFaceLogic.pickDelta("+1", "+2", "+1,1", "+2,2", detailed = false, avg = true)).isEqualTo("+2")
        assertThat(GlassFaceLogic.pickDelta("+1", "+2", "+1,1", "+2,2", detailed = true, avg = false)).isEqualTo("+1,1")
        assertThat(GlassFaceLogic.pickDelta("+1", "+2", "+1,1", "+2,2", detailed = true, avg = true)).isEqualTo("+2,2")
        assertThat(GlassFaceLogic.pickDelta("+1", "+2", "+1,1", "+2,2", detailed = false, avg = false)).isEqualTo("+1")
    }

    // ------------------------------------------------------------------ units: mmol / mg/dL

    /**
     * Advance widths (em) of the DemiBold hero face approximating the mockup font, from which the spec's measured
     * sizes follow: "9,4" 84, "11,8" 80, "400+" 68, "22,2+" 60, "254" 84.
     */
    private fun heroWidth(s: String, size: Float): Float = size * s.sumOf {
        when (it) {
            ',', '.' -> 0.36
            '+'      -> 0.70
            '-'      -> 0.40
            else     -> 0.58
        }
    }.toFloat()

    private fun bgFit(s: String, max: Float = GlassFaceLogic.BG_MAX_WIDTH) = GlassFaceLogic.bgFitSize({ heroWidth(s, it) }, max)

    @Test fun bgSizeForMmolAndMgdlByMeasuredWidth() {
        // Glass rework: 134 px start (Halo's ~95 px cap), floor 80; Glass/Orbit UX round 4: 232 px next to the chip
        // Glass polish round 1: 112 px start (an 80 px cap, about 52 % of the card), 230 px next to the chip
        assertThat(bgFit("9,4")).isEqualTo(112f)
        // Glass polish round 7: 224 px next to the chip (16 px of glass to the card each side), "11,8" 108 -> 106
        assertThat(bgFit("11,8")).isEqualTo(106f)
        assertThat(bgFit("254")).isEqualTo(112f) // three mg/dL digits keep the full size next to the chip
        assertThat(bgFit("---")).isEqualTo(112f)
        assertThat(bgFit("22,2+")).isAtLeast(GlassFaceLogic.BG_MIN_SIZE)
        // the result fits, in 2 px steps from the start size
        for (v in listOf("9,4", "11,8", "400+", "254", "39")) {
            val size = bgFit(v)
            assertThat(heroWidth(v, size)).isAtMost(GlassFaceLogic.BG_MAX_WIDTH)
            assertThat((GlassFaceLogic.BG_SIZE - size) % 2f).isEqualTo(0f)
        }
        // chip off: 300 px (the card less 12 px padding each side), "22,2+" keeps a larger size
        assertThat(bgFit("22,2+", GlassFaceLogic.BG_MAX_WIDTH_NO_CHIP)).isAtLeast(bgFit("22,2+"))
        // floor, even when it still does not fit
        assertThat(bgFit("888888888")).isEqualTo(GlassFaceLogic.BG_MIN_SIZE)
    }

    @Test fun valuesShownAsSentPlaceholdersDashed() {
        assertThat(GlassFaceLogic.display("2,53")).isEqualTo("2,53")
        assertThat(GlassFaceLogic.display("IOB")).isEqualTo("--")
        assertThat(GlassFaceLogic.display("-.--")).isEqualTo("--")
        assertThat(GlassFaceLogic.display("--g")).isEqualTo("--")
        assertThat(GlassFaceLogic.display("no status")).isEqualTo("--")
        assertThat(GlassFaceLogic.display("??")).isEqualTo("--")
        assertThat(GlassFaceLogic.basalText("0,85U/h")).isEqualTo("0,85 U/h")
        assertThat(GlassFaceLogic.basalText("0,85 U/h")).isEqualTo("0,85 U/h")
        assertThat(GlassFaceLogic.basalText("-.--U/h")).isEqualTo("--")
        assertThat(GlassFaceLogic.basalText("120%")).isEqualTo("120%")
        assertThat(GlassFaceLogic.parseNumber("-0,35")).isEqualTo(-0.35)
        assertThat(GlassFaceLogic.parseNumber("12.40")).isEqualTo(12.4)
        assertThat(GlassFaceLogic.phoneBatteryPercent("18")).isEqualTo(18)
        assertThat(GlassFaceLogic.phoneBatteryPercent("0")).isNull() // unknown, never a warning
    }

    @Test fun valueTones() {
        assertThat(GlassFaceLogic.iobTone("2,53")).isEqualTo(Tone.INS)
        assertThat(GlassFaceLogic.iobTone("0,00")).isEqualTo(Tone.TXT3)
        assertThat(GlassFaceLogic.iobTone("-0,35")).isEqualTo(Tone.TXT1)
        assertThat(GlassFaceLogic.iobTone("IOB")).isEqualTo(Tone.TXT3)
        assertThat(GlassFaceLogic.basalTone("0,85U/h")).isEqualTo(Tone.TXT2)
        assertThat(GlassFaceLogic.basalTone("120%")).isEqualTo(Tone.TBR)
        assertThat(GlassFaceLogic.basalTone("0%")).isEqualTo(Tone.WARN)
        assertThat(GlassFaceLogic.basalTone("0,00U/h")).isEqualTo(Tone.WARN)
        assertThat(GlassFaceLogic.basalTone("0.00U/h")).isEqualTo(Tone.WARN)
        assertThat(GlassFaceLogic.basalTone("-.--U/h")).isEqualTo(Tone.TXT3)
    }

    // ------------------------------------------------------------------ loop

    @Test fun loopRunFromExternalStatus() {
        assertThat(GlassFaceLogic.loopRun("")).isEqualTo(LoopRun.RUNNING)
        assertThat(GlassFaceLogic.loopRun("no status")).isEqualTo(LoopRun.RUNNING)
        assertThat(GlassFaceLogic.loopRun("Loop deaktiviert\n")).isEqualTo(LoopRun.OFF)
        assertThat(GlassFaceLogic.loopRun("Kein Profil")).isEqualTo(LoopRun.NO_PROFILE)
    }

    @Test fun loopHealthStates() {
        fun h(age: Long?, run: LoopRun = LoopRun.RUNNING, mode: LoopMode = LoopMode.CLOSED) = GlassFaceLogic.loopHealth(age, run, mode)
        assertThat(h(0)).isEqualTo(LoopHealth.OK)
        assertThat(h(5)).isEqualTo(LoopHealth.OK)
        assertThat(h(6)).isEqualTo(LoopHealth.WARN)
        assertThat(h(14)).isEqualTo(LoopHealth.WARN)
        assertThat(h(15)).isEqualTo(LoopHealth.BAD)
        assertThat(h(null)).isEqualTo(LoopHealth.NEVER)                    // never ran: grey, never red
        assertThat(h(3, LoopRun.OFF)).isEqualTo(LoopHealth.OFF)
        assertThat(h(3, LoopRun.NO_PROFILE)).isEqualTo(LoopHealth.NEUTRAL)
        assertThat(h(34, mode = LoopMode.OPEN)).isEqualTo(LoopHealth.OPEN) // open loop: age is no alarm
        assertThat(h(23, mode = LoopMode.LGS)).isEqualTo(LoopHealth.BAD)   // LGS: age rules apply
        assertThat(GlassFaceLogic.loopAgeMinutes(-1L, now)).isNull()
        assertThat(GlassFaceLogic.loopAgeMinutes(now - 3 * min, now)).isEqualTo(3L)
    }

    @Test fun loopModeFromAvailableStates() {
        val t = now - min
        assertThat(GlassFaceLogic.loopMode(setOf("LOOP_OPEN", "LOOP_LGS", "LOOP_DISABLE"), t, now)).isEqualTo(LoopMode.CLOSED)
        assertThat(GlassFaceLogic.loopMode(setOf("LOOP_CLOSED", "LOOP_LGS"), t, now)).isEqualTo(LoopMode.OPEN)
        assertThat(GlassFaceLogic.loopMode(setOf("LOOP_CLOSED", "LOOP_OPEN"), t, now)).isEqualTo(LoopMode.LGS)
        assertThat(GlassFaceLogic.loopMode(setOf("LOOP_RESUME"), t, now)).isEqualTo(LoopMode.UNKNOWN) // ambiguous
        assertThat(GlassFaceLogic.loopMode(emptySet(), t, now)).isEqualTo(LoopMode.UNKNOWN)
        assertThat(GlassFaceLogic.loopMode(setOf("LOOP_OPEN", "LOOP_LGS"), now - 16 * min, now)).isEqualTo(LoopMode.UNKNOWN) // old list
    }

    @Test fun loopPillText() {
        assertThat(GlassFaceLogic.loopText(2, "alt")).isEqualTo("2′")
        assertThat(GlassFaceLogic.loopText(null, "alt")).isEqualTo("--")
        assertThat(GlassFaceLogic.loopText(100, "alt")).isEqualTo("alt")
        // no bare "!" as a carrier (UX round 1), also not for a bad loop
        assertThat(GlassFaceLogic.loopText(17, "alt")).isEqualTo("17′")
        // the LOOP label is never dropped for width (spec F14), only for the open loop
        assertThat(GlassFaceLogic.loopLabelAllowed(LoopHealth.BAD)).isTrue()
    }

    @Test fun firstStartAndNoProfile() {
        assertThat(GlassFaceLogic.firstStart(0L, -1L, "IOB")).isTrue()
        assertThat(GlassFaceLogic.firstStart(now, -1L, "IOB")).isFalse()
        assertThat(GlassFaceLogic.noProfile(LoopRun.NO_PROFILE, "1,20", false)).isTrue()
        assertThat(GlassFaceLogic.noProfile(LoopRun.RUNNING, "", false)).isTrue()
        assertThat(GlassFaceLogic.noProfile(LoopRun.RUNNING, "", true)).isFalse()
    }

    // ------------------------------------------------------------------ phone link

    @Test fun phoneLinkStates() {
        assertThat(GlassFaceLogic.phoneLink(true, now - 3 * min, now)).isEqualTo(PhoneLink.OK)
        assertThat(GlassFaceLogic.phoneLink(false, now - 3 * min, now)).isEqualTo(PhoneLink.LOST_FRESH)
        assertThat(GlassFaceLogic.phoneLink(false, now - 14 * min, now)).isEqualTo(PhoneLink.LOST_STALE)
        assertThat(GlassFaceLogic.phoneLink(true, now - 17 * min, now)).isEqualTo(PhoneLink.SILENT)
        assertThat(GlassFaceLogic.phoneLink(true, 0L, now)).isEqualTo(PhoneLink.SILENT)
        assertThat(GlassFaceLogic.dataFresh(now - 12 * min + 1, now)).isTrue()
        assertThat(GlassFaceLogic.dataFresh(now - 12 * min, now)).isFalse()
    }

    // ------------------------------------------------------------------ status slot (W1-W10)

    private fun slot(
        first: Boolean = false, link: PhoneLink = PhoneLink.OK, age: BgAge = BgAge.FRESH, range: Range = Range.IN, noProfile: Boolean = false,
        loop: LoopHealth = LoopHealth.OK, res: String = "120U", resLevel: Int = 0, batt: String = "80", battLevel: Int = 1
    ) = GlassFaceLogic.warnings(SlotInput(first, link, age, range, noProfile, loop, res, resLevel, batt, battLevel))

    @Test fun warningHierarchy() {
        assertThat(slot()).isEmpty()
        assertThat(slot(first = true, age = BgAge.NONE, loop = LoopHealth.NEVER, link = PhoneLink.SILENT).first()).isEqualTo(Warning.WAITING)
        assertThat(slot(link = PhoneLink.LOST_STALE, age = BgAge.STALE).first()).isEqualTo(Warning.PHONE_LOST)
        assertThat(slot(link = PhoneLink.LOST_STALE, age = BgAge.STALE)).doesNotContain(Warning.NO_DATA) // no second red word
        assertThat(slot(link = PhoneLink.SILENT, age = BgAge.STALE).first()).isEqualTo(Warning.NO_DATA)
        assertThat(slot(age = BgAge.STALE).first()).isEqualTo(Warning.NO_DATA)
        assertThat(slot(noProfile = true, loop = LoopHealth.NEUTRAL).first()).isEqualTo(Warning.NO_PROFILE)
        assertThat(slot(loop = LoopHealth.OFF).first()).isEqualTo(Warning.LOOP_OFF)
        assertThat(slot(loop = LoopHealth.BAD).first()).isEqualTo(Warning.LOOP_BAD)
        assertThat(slot(loop = LoopHealth.NEVER)).isEmpty()                      // never red for "never ran"
        assertThat(slot(loop = LoopHealth.OPEN)).isEmpty()
        assertThat(slot(res = "5U", resLevel = 2).first()).isEqualTo(Warning.RES_CRITICAL)
        assertThat(slot(res = "", resLevel = 2)).isEmpty()                       // unknown reservoir never red
        assertThat(slot(batt = "18", battLevel = 0).first()).isEqualTo(Warning.PHONE_BATTERY)
        assertThat(slot(batt = "0", battLevel = 0)).isEmpty()                    // "0" = unknown
        assertThat(slot(res = "18U", resLevel = 1).first()).isEqualTo(Warning.RES_LOW)
        assertThat(slot(link = PhoneLink.LOST_FRESH).first()).isEqualTo(Warning.PHONE_LOST_FRESH)
        // stack: loop 23' + res 5U + Tel 7 % -> W6 owns
        assertThat(slot(loop = LoopHealth.BAD, res = "5U", resLevel = 2, batt = "7", battLevel = 0).first()).isEqualTo(Warning.LOOP_BAD)
    }

    @Test fun freshLowSuppressesLowerWarnings() {
        assertThat(slot(range = Range.LOW, res = "5U", resLevel = 2, batt = "7", battLevel = 0)).isEmpty()
        assertThat(slot(range = Range.URGENT_LOW, loop = LoopHealth.BAD)).containsExactly(Warning.LOOP_BAD)
        assertThat(slot(range = Range.HIGH, res = "5U", resLevel = 2)).containsExactly(Warning.RES_CRITICAL)
    }

    private val words = SlotWords(
        waiting = "Warte auf AAPS",
        phoneLost = { "Telefon getrennt · $it" },
        noData = { "Keine Daten · $it" },
        noProfile = "Kein Profil",
        loopOff = "Loop aus",
        loopAge = { "Loop $it" },
        reservoir = { "Reservoir $it" },
        phoneBattery = { "Tel $it %" },
        cob = { "KH $it" },
        bgi = { "BGI $it" },
        age = de
    )

    /** Rough width of the 18 px Medium face: 10 px per character (wider than Roboto, a worst case). */
    private val measure: (String) -> Float = { it.length * 10f }
    private val roboto: (String) -> Float = { it.length * 8.5f }
    private val sep = "  ·  "

    private fun footer(
        warnings: List<Warning> = emptyList(), dataAge: Long? = 1, bgAge: Long? = 1, loopAge: Long? = 2,
        tt: String = "5,6", ttLevel: Int = 0, cob: String = "0g", basal: String = "0,85U/h", bgi: String = "-1,2",
        res: String = "120U", resLevel: Int = 0, batt: String = "80", battLevel: Int = 1,
        showBgi: Boolean = false, showRes: Boolean = false, showBatt: Boolean = false, ambient: Boolean = false, m: (String) -> Float = roboto
    ) = GlassFaceLogic.footerRuns(
        FooterInput(
            warnings, dataAge, bgAge, loopAge, tt, ttLevel, cob, basal, bgi, res, resLevel, batt, battLevel,
            showBgi = showBgi, showReservoir = showRes, showPhoneBattery = showBatt, ambient = ambient
        ), words, sep, m
    )

    @Test fun footerShowsOnlyActiveRuns() {
        assertThat(footer().map { it.text }).containsExactly("0,85 U/h")
        val tt = footer(ttLevel = 2, cob = "24g")
        // UX round 3 (26 px footer): three runs no longer fit, KH (keep 40) goes before the basal (50) and the TT (90)
        assertThat(tt.map { it.text }).containsExactly("TT 5,6", "0,85 U/h").inOrder()
        assertThat(tt[0].tone).isEqualTo(Tone.TT)
        assertThat(footer(cob = "20(30)g", basal = "").map { it.text }).contains("KH 20(30)g")       // shown as sent
        assertThat(footer(cob = "--g").map { it.text }).contains("KH --")
        assertThat(footer(ttLevel = 1).map { it.kind }).doesNotContain(RunKind.TT)          // APS-adjusted target: hidden
        assertThat(footer(basal = "0%").first().tone).isEqualTo(Tone.WARN)
    }

    @Test fun footerOwnersTakeTheLine() {
        val lost = footer(warnings = listOf(Warning.PHONE_LOST), dataAge = 14, ttLevel = 2)
        assertThat(lost.map { it.text }).containsExactly("Telefon getrennt · 14′")
        assertThat(lost[0].tone).isEqualTo(Tone.ALERT)
        assertThat(footer(warnings = listOf(Warning.NO_DATA), dataAge = 3, bgAge = 17).single().text).isEqualTo("Keine Daten · 17′")
        assertThat(footer(warnings = listOf(Warning.PHONE_LOST), dataAge = 300).single().text).isEqualTo("Telefon getrennt · 5 Std")
        assertThat(footer(warnings = listOf(Warning.WAITING)).single().tone).isEqualTo(Tone.TXT2)
        assertThat(footer(warnings = listOf(Warning.NO_PROFILE)).single().text).isEqualTo("Kein Profil")
        assertThat(footer(warnings = listOf(Warning.LOOP_OFF)).single().tone).isEqualTo(Tone.WARN)
        assertThat(footer(warnings = listOf(Warning.LOOP_BAD), loopAge = 23).single().text).isEqualTo("Loop 23′")
    }

    @Test fun footerLowerWarningsLeadTheRuns() {
        val res = footer(warnings = listOf(Warning.RES_CRITICAL), res = "5U", resLevel = 2)
        // Glass/Orbit UX round 3: the footer sits lower (188 px chord), the basal run goes before the warning shrinks
        assertThat(res.map { it.text }).containsExactly("Reservoir 5U")
        assertThat(res[0].tone).isEqualTo(Tone.ALERT)
        val batt = footer(warnings = listOf(Warning.PHONE_BATTERY), batt = "18", battLevel = 0)
        assertThat(batt[0].text).isEqualTo("Tel 18 %")
        assertThat(batt[0].tone).isEqualTo(Tone.WARN)
        assertThat(footer(warnings = listOf(Warning.PHONE_BATTERY), batt = "7", battLevel = 0)[0].tone).isEqualTo(Tone.ALERT)
        assertThat(footer(warnings = listOf(Warning.RES_LOW), res = "18U", resLevel = 1)[0].tone).isEqualTo(Tone.WARN)
        // W10 is a glyph only
        assertThat(footer(warnings = listOf(Warning.PHONE_LOST_FRESH)).map { it.text }).containsExactly("0,85 U/h")
    }

    @Test fun badLoopIsSaidOnceAndBasalOnlyWhenItMatters() {
        // Glass/Orbit UX round 3: interactive, the red loop glyph + age own a bad loop; the next warning takes the footer
        assertThat(GlassFaceLogic.footerWarnings(listOf(Warning.LOOP_BAD, Warning.RES_CRITICAL), ambient = false))
            .containsExactly(Warning.RES_CRITICAL)
        assertThat(GlassFaceLogic.footerWarnings(listOf(Warning.LOOP_OFF), ambient = false)).containsExactly(Warning.LOOP_OFF)
        assertThat(GlassFaceLogic.footerWarnings(listOf(Warning.LOOP_BAD), ambient = true)).containsExactly(Warning.LOOP_BAD)
        assertThat(GlassFaceLogic.footerWarnings(listOf(Warning.NO_DATA, Warning.LOOP_BAD), ambient = false)).isEmpty()
        assertThat(GlassFaceLogic.basalInFooter(true, "0,85U/h")).isFalse()
        assertThat(GlassFaceLogic.basalInFooter(true, "120%")).isTrue()
        assertThat(GlassFaceLogic.basalInFooter(true, "0,00U/h")).isTrue()
        assertThat(GlassFaceLogic.basalInFooter(false, "120%")).isFalse()
    }

    @Test fun ambientKeepsW1ToW6AndTtOnly() {
        assertThat(footer(warnings = listOf(Warning.RES_CRITICAL), res = "5U", resLevel = 2, ttLevel = 2, ambient = true).map { it.text })
            .containsExactly("TT 5,6")
        assertThat(footer(warnings = listOf(Warning.LOOP_BAD), loopAge = 23, ambient = true).single().text).isEqualTo("Loop 23′")
        assertThat(footer(ambient = true)).isEmpty()
    }

    @Test fun worstCaseFooterDropsLowPriorityFirst() {
        // Reservoir 5U · TT 4,4 - 5,0 · KH 20(30)g · 12,35 U/h · BGI -1,2 · Res 120U · Tel 64 % is far wider than 257 px
        fun runs(m: (String) -> Float) = footer(
            warnings = listOf(Warning.RES_CRITICAL), res = "5U", resLevel = 2, tt = "4,4 - 5,0", ttLevel = 2, cob = "20(30)g", basal = "12,35U/h",
            showBgi = true, showRes = true, showBatt = true, m = m
        )
        val roboto = runs(roboto)
        assertThat(roboto.first().kind).isEqualTo(RunKind.WARNING)          // warnings are never dropped
        // UX round 3 (26 px footer): every info run goes before the warning would shrink, TT last
        assertThat(roboto.map { it.kind }).containsExactly(RunKind.WARNING)
        assertThat(GlassFaceLogic.lineWidth(roboto, sep, this.roboto)).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH)
        val ttOnly = footer(warnings = listOf(Warning.RES_CRITICAL), res = "5U", resLevel = 2, tt = "6", ttLevel = 2, cob = "20(30)g", basal = "12,35U/h", m = this.roboto)
        assertThat(ttOnly.map { it.kind }).containsExactly(RunKind.WARNING, RunKind.TT).inOrder()
        // at a wider worst-case font even the TT goes, the warning stays
        val wide = runs(measure)
        assertThat(wide.map { it.kind }).containsExactly(RunKind.WARNING)
        assertThat(GlassFaceLogic.lineWidth(wide, sep, measure)).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH)
    }

    @Test fun footerMaxWidthFitsTheChordAtTheFooter() {
        // Glass/Orbit UX round 1: footer centre y 394, 26 px (cap bottom about y 403): the chord there less 16 px to the
        // round edge on each side is about 217 px (at 236 px the "%" ended 6 px from the edge)
        val capBottom = GlassFaceLogic.FOOTER_Y + 0.36f * GlassFaceLogic.FOOTER_SIZE
        assertThat(GlassFaceLogic.FOOTER_MAX_WIDTH).isAtMost(GlassFaceLogic.chordWidth(capBottom, 16f))
        assertThat(GlassFaceLogic.chordWidth(225f, 16f)).isEqualTo(418f)
        // warnings stay 22 px or larger (a 1.2 mm cap height on the Watch5 Pro)
        assertThat(GlassFaceLogic.FOOTER_MIN_SIZE).isAtLeast(22f)
        assertThat(GlassFaceLogic.FOOTER_SIZE).isAtLeast(26f)
    }

    /** Fits [text] as the single W1-W6 owner run and returns the size and the drawn width at that size. */
    private fun fitOwner(text: String, m: (String) -> Float = measure): Triple<Float, String, Float> {
        val fit = GlassFaceLogic.fitFooter(listOf(GlassFaceLogic.Run(text, RunKind.WARNING, Tone.ALERT, 100)), sep, m)
        val drawn = fit.runs.single().text
        return Triple(fit.size, drawn, m(drawn) * fit.size / GlassFaceLogic.FOOTER_SIZE)
    }

    @Test fun longOwnerTextShrinksToTheFloorThenEllipsises() {
        // real German / French status texts at a worst-case 10 px per character (at FOOTER_SIZE)
        // Glass/Orbit UX round 1: the footer budget is the bezel-safe chord (216 px); the French "Téléphone déconnecté ·
        // 23 h" (270 px) is now cut at the 22 px floor, its age kept (see below)
        // Glass/Orbit UX round 3: the lower footer's chord is 188 px; at this worst-case font the 25-character texts are
        // cut at the floor, their age always kept (W2/W3 sit in the card's META row on the face anyway)
        for (s in listOf("Telefon getrennt · 23 Std", "Keine Daten · 3 Std", "Pas de données · 23 h", "Boucle désactivée")) {
            val (size, drawn, width) = fitOwner(s)
            assertThat(size).isAtLeast(GlassFaceLogic.FOOTER_MIN_SIZE)
            assertThat(width).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH + 0.01f)
            if (s.contains(" · ")) assertThat(drawn).endsWith(" · " + s.substringAfterLast(" · ")) else assertThat(drawn).isEqualTo(s)
        }
        // "Kein Profil" = 110 px: full size
        assertThat(fitOwner("Kein Profil").first).isEqualTo(GlassFaceLogic.FOOTER_SIZE)
        // "Keine Daten · 3 Std" = 190 px: shrinks, above the floor
        assertThat(fitOwner("Keine Daten · 3 Std").first)
            .isWithin(0.01f).of(GlassFaceLogic.FOOTER_SIZE * GlassFaceLogic.FOOTER_MAX_WIDTH / 190f)
        val fr = fitOwner("Téléphone déconnecté · 23 h")
        assertThat(fr.second).endsWith(" · 23 h")
        assertThat(fr.first).isEqualTo(GlassFaceLogic.FOOTER_MIN_SIZE)
        // a translation far too long: the floor, then "…"
        val long = "Téléphone déconnecté depuis longtemps · 23 h"
        val (size, drawn, width) = fitOwner(long)
        assertThat(size).isEqualTo(GlassFaceLogic.FOOTER_MIN_SIZE)
        // UX round 4: the label is cut, the " · 23 h" age is kept
        assertThat(drawn).contains("…")
        assertThat(drawn).endsWith(" · 23 h")
        assertThat(drawn.length).isLessThan(long.length)
        assertThat(width).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH)
        assertThat(GlassFaceLogic.fitFooter(emptyList(), sep, measure).runs).isEmpty()
    }

    @Test fun ellipsisNeverDropsTheAge() {
        // UX round 4 (safety): the " · <age>" token of W2/W3 always stays; only the label is shortened
        assertThat(GlassFaceLogic.ellipsizeKeepTail("Phone disconnected · 14′", 160f, measure)).isEqualTo("Phone dis… · 14′")
        assertThat(GlassFaceLogic.ellipsizeKeepTail("Kein Profil", 60f, measure)).isEqualTo("Kein…")
        assertThat(GlassFaceLogic.ellipsizeKeepTail("Keine Daten · 3 Std", 400f, measure)).isEqualTo("Keine Daten · 3 Std")
        // the longest W1-W9 texts in EN and DE at the footer floor, also on a 384 px screen (same 450 grid budget):
        // the age token is never lost and the line fits
        val ages = listOf("14′", "59′", "23 Std", "23 h", "2 T", "2 d")
        val texts = listOf(
            "Waiting for AAPS", "Warte auf AAPS", "No profile", "Kein Profil", "Loop off", "Loop aus",
            "Reservoir 120U", "Phone 9 %", "Tel 9 %"
        ) + ages.flatMap { a ->
            listOf("Phone lost · $a", "Telefon getrennt · $a", "No data · $a", "Keine Daten · $a", "Loop $a", "Phone disconnected · $a")
        }
        for (m in listOf(measure, roboto)) for (t in texts) {
            val (_, drawn, width) = fitOwner(t, m)
            assertThat(width).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH + 0.01f)
            val tail = t.substringAfterLast(" · ", "")
            if (t.contains(" · ")) assertThat(drawn).endsWith(" · $tail")
        }
    }

    @Test fun ellipsize() {
        assertThat(GlassFaceLogic.ellipsize("Kein Profil", 200f, measure)).isEqualTo("Kein Profil")
        assertThat(GlassFaceLogic.ellipsize("Telefon getrennt", 80f, measure)).isEqualTo("Telefon…")
        assertThat(GlassFaceLogic.ellipsize("abc", 5f, measure)).isEqualTo("…")
    }

    @Test fun allOnFooterDropsInfoRunsFirst() {
        val runs = footer(cob = "24g", showRes = true, showBatt = true, battLevel = 1, batt = "64")
        // pref.all_on (spec §8): "Res 120U" (keep 20) goes first, then "Tel 64 %" (25); KH and basal stay
        assertThat(runs.map { it.text }).containsExactly("KH 24g", "0,85 U/h").inOrder()
        assertThat(GlassFaceLogic.lineWidth(runs, sep, roboto)).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH)
    }

    @Test fun worstCaseStatusStringsFit() {
        // German worst cases fit the slot at the floor or above, never cut
        for (s in listOf("Telefon getrennt · 59′", "Keine Daten · 3 Std", "Warte auf AAPS", "Reservoir 120U  ·  120%")) {
            val fit = GlassFaceLogic.fitFooter(listOf(GlassFaceLogic.Run(s, RunKind.WARNING, Tone.ALERT, 100)), sep, roboto)
            assertThat(fit.size).isAtLeast(GlassFaceLogic.FOOTER_MIN_SIZE)
            assertThat(fit.runs.single().text).isEqualTo(s)
        }
    }

    @Test fun fitSize() {
        assertThat(GlassFaceLogic.fitSize(84f, 150f, 176f)).isEqualTo(84f)
        assertThat(GlassFaceLogic.fitSize(84f, 352f, 176f)).isEqualTo(42f)
        assertThat(GlassFaceLogic.fitSize(18f, 0f, 84f)).isEqualTo(18f)
    }

    // ------------------------------------------------------------------ time / date

    @Test fun germanDateFromRendererTime() {
        assertThat(GlassFaceLogic.dateText(LocalDate.of(2025, 10, 17), Locale.GERMANY)).isEqualTo("Fr 17. Okt")
        assertThat(GlassFaceLogic.dateText(LocalDate.of(2026, 10, 17), Locale.GERMANY)).isEqualTo("Sa 17. Okt")
        assertThat(GlassFaceLogic.dateText(LocalDate.of(2026, 5, 1), Locale.GERMANY)).isEqualTo("Fr 1. Mai")
        assertThat(GlassFaceLogic.dateText(LocalDate.of(2026, 3, 2), Locale.GERMANY)).isEqualTo("Mo 2. März")
    }

    @Test fun amPmSuffix() {
        assertThat(GlassFaceLogic.amPm(14, true)).isNull()
        assertThat(GlassFaceLogic.amPm(0, false)).isEqualTo("AM")
        assertThat(GlassFaceLogic.amPm(11, false)).isEqualTo("AM")
        assertThat(GlassFaceLogic.amPm(12, false)).isEqualTo("PM")
        assertThat(GlassFaceLogic.amPm(23, false)).isEqualTo("PM")
    }

    @Test fun timeText() {
        assertThat(GlassFaceLogic.timeText(0, 40, true)).isEqualTo("00:40")
        assertThat(GlassFaceLogic.timeText(14, 7, true)).isEqualTo("14:07")
        assertThat(GlassFaceLogic.timeText(0, 40, false)).isEqualTo("12:40")
        assertThat(GlassFaceLogic.timeText(13, 7, false)).isEqualTo("1:07")
    }

    // ------------------------------------------------------------------ sparkline

    @Test fun forecastDotsStartClearOfTheNowDotAndFollowThePath() {
        // a straight 45 px forecast from the now dot: dots at 9, 15,5, 22, ... px, never on the dot itself
        val xy = floatArrayOf(100f, 50f, 145f, 50f)
        val out = FloatArray(48)
        val n = GlassFaceLogic.forecastDots(xy, 2, 9f, 6.5f, out)
        assertThat(n).isEqualTo(6)
        assertThat(out[0]).isWithin(0.01f).of(109f)
        assertThat(out[2]).isWithin(0.01f).of(115.5f)
        assertThat(out[2 * (n - 1)]).isAtMost(145.01f)
        for (i in 0 until n) assertThat(out[2 * i + 1]).isEqualTo(50f)
        // a bend: the spacing is measured along the path
        val bent = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)
        val m = GlassFaceLogic.forecastDots(bent, 3, 5f, 10f, out)
        assertThat(m).isEqualTo(2)
        assertThat(out[2]).isWithin(0.01f).of(10f)
        assertThat(out[3]).isWithin(0.01f).of(5f)
        // the output buffer bounds the count
        assertThat(GlassFaceLogic.forecastDots(xy, 2, 0f, 1f, FloatArray(6))).isEqualTo(3)
    }

    @Test fun sparklineGeometry() {
        // Glass polish round 3: a 45 min forecast at the history's time scale
        assertThat(GlassFaceLogic.nowX()).isWithin(0.5f).of(313.8f)
        assertThat(GlassFaceLogic.sparkX(0f)).isEqualTo(72f)
        assertThat(GlassFaceLogic.sparkX(44f)).isEqualTo(376f)
        val pitchHistory = GlassFaceLogic.sparkX(1f) - GlassFaceLogic.sparkX(0f)
        assertThat(GlassFaceLogic.sparkX(36f) - GlassFaceLogic.sparkX(35f)).isWithin(0.01f).of(pitchHistory)
        // with the ball: at least 180 px of history, at most 50 px of forecast
        val nowBall = GlassFaceLogic.nowX(GlassFaceLogic.SPARK_X0_BALL)
        assertThat(nowBall - GlassFaceLogic.SPARK_X0_BALL).isAtLeast(180f)
        assertThat(GlassFaceLogic.SPARK_X1 - nowBall).isAtMost(50f)
        // without a forecast the plot ends at now (no empty quarter), 19 px left of the card's corner
        assertThat(GlassFaceLogic.nowX(forecast = false)).isEqualTo(368f)
        assertThat(GlassFaceLogic.HERO_X1 - GlassFaceLogic.nowX(forecast = false)).isAtLeast(16f)
        assertThat(GlassFaceLogic.sparkX(0f, GlassFaceLogic.SPARK_X0_BALL, forecast = false)).isEqualTo(144f)
        assertThat(GlassFaceLogic.SPARK_X0_BALL).isGreaterThan(GlassFaceLogic.BALL_X + GlassFaceLogic.BALL_R + 12f)
        // values stay 8 px inside the plot (the line and the now dot's glow never meet the card or the pills)
        val top = GlassFaceLogic.SPARK_Y0 + GlassFaceLogic.SPARK_INSET
        val bottom = GlassFaceLogic.SPARK_Y1 - GlassFaceLogic.SPARK_INSET
        assertThat(GlassFaceLogic.sparkY(60.0)).isEqualTo(bottom)
        assertThat(GlassFaceLogic.sparkY(190.0)).isEqualTo(top)
        assertThat(GlassFaceLogic.sparkY(20.0)).isEqualTo(bottom)
        assertThat(GlassFaceLogic.sparkY(400.0)).isEqualTo(top)
        // Glass polish round 2/3: a linear range over the visible values AND the target band (+-15 mg/dL)
        assertThat(GlassFaceLogic.sparkRange(Double.NaN, Double.NaN)).isEqualTo(60.0 to 190.0)
        assertThat(GlassFaceLogic.sparkRange(100.0, 110.0)).isEqualTo(55.0 to 195.0)
        assertThat(GlassFaceLogic.sparkRange(90.0, 200.0)).isEqualTo(55.0 to 200.0)
        // round 6: without the current reading / keep values nothing is clamped (the safe default): floor 40, top 400
        assertThat(GlassFaceLogic.sparkRange(39.0, 420.0)).isEqualTo(40.0 to 400.0)
        // a low NOW with an older 420 peak: the window is fixed (the peak is clamped, drawn faded), the low stays readable
        assertThat(GlassFaceLogic.sparkRange(39.0, 420.0, nowMgdl = 39.0, keepMaxMgdl = 60.0)).isEqualTo(40.0 to 120.0)
        assertThat(GlassFaceLogic.sparkRange(395.0, 400.0)).isEqualTo(55.0 to 400.0)
        // round 3: anything below the low line puts the floor at 40 mg/dL, so hypo depth is on a fixed scale
        // round 5/6: with the current reading below the low line the top is the low line + 50 unless the current
        // reading, the last 30 min or the forecast (keepMax) need more (+10 mg/dL)
        assertThat(GlassFaceLogic.sparkRange(45.0, 50.0, nowMgdl = 45.0, keepMaxMgdl = 50.0)).isEqualTo(40.0 to 120.0)
        assertThat(GlassFaceLogic.sparkRange(63.0, 120.0, nowMgdl = 63.0, keepMaxMgdl = 90.0)).isEqualTo(40.0 to 120.0)
        assertThat(GlassFaceLogic.sparkRange(63.0, 108.0, nowMgdl = 63.0, keepMaxMgdl = 108.0)).isEqualTo(40.0 to 120.0)
        assertThat(GlassFaceLogic.sparkRange(50.0, 395.0, nowMgdl = 50.0, keepMaxMgdl = 80.0)).isEqualTo(40.0 to 120.0)
        assertThat(GlassFaceLogic.sparkRange(60.0, 70.0, 80.0, 140.0, nowMgdl = 60.0)).isEqualTo(40.0 to 130.0)
        // a forecast rising out of the low raises the top (the forecast is never clamped)
        assertThat(GlassFaceLogic.sparkRange(50.0, 300.0, nowMgdl = 50.0, keepMaxMgdl = 150.0)).isEqualTo(40.0 to 160.0)
        assertThat(GlassFaceLogic.sparkRange(70.0, 120.0)).isEqualTo(55.0 to 195.0)
        val (llo, lhi) = GlassFaceLogic.sparkRange(47.0, 126.0, nowMgdl = 47.0)
        assertThat(GlassFaceLogic.sparkY(47.0, llo, lhi) - GlassFaceLogic.sparkY(63.0, llo, lhi)).isAtLeast(4f)
        // a narrow band still gets the 5 mmol/l minimum span
        assertThat(GlassFaceLogic.sparkRange(100.0, 110.0, 90.0, 120.0)).isEqualTo(60.0 to 150.0)
        // calm: 0,2 mmol/l (3.6 mg/dL) of noise on a flat BG moves the line by about 1 px
        val (flo, fhi) = GlassFaceLogic.sparkRange(104.0, 104.0)
        assertThat(GlassFaceLogic.sparkY(107.6, flo, fhi) - GlassFaceLogic.sparkY(104.0, flo, fhi)).isWithin(0.2f).of(-1.0f)
        // both default edges sit about 5 px inside the values' area, so the trace has room above and below the band
        assertThat(GlassFaceLogic.sparkYRaw(180.0, flo, fhi) - (GlassFaceLogic.SPARK_Y0 + GlassFaceLogic.SPARK_INSET)).isWithin(0.5f).of(4.7f)
        // a flat 5,8 is well clear of the low edge (readable as "in range, not near low")
        assertThat(GlassFaceLogic.sparkYRaw(70.0, flo, fhi) - GlassFaceLogic.sparkY(104.0, flo, fhi)).isAtLeast(8f)
        // safety: the lowest visible value is never clamped; without a low every visible value is inside the range.
        // Round 6: with the current reading below the low line the window is 40 .. max(low + 50, keep + 10) and only an
        // OLDER peak runs off the top (the depth of the low wins over the height of an old peak); the current reading
        // and the keep values (last 30 min, forecast) are never clamped. Otherwise every visible value is inside.
        for ((a, b) in listOf(40.0 to 60.0, 50.0 to 300.0, 100.0 to 400.0, 180.0 to 181.0, 41.0 to 41.0, 400.0 to 400.0)) {
            for ((now, keep) in listOf(Double.NaN to Double.NaN, a to a, b to b, a to (a + b) / 2.0)) {
                val (lo, hi) = GlassFaceLogic.sparkRange(a, b, nowMgdl = now, keepMaxMgdl = keep)
                assertThat(lo).isAtMost(a.coerceAtLeast(40.0))
                assertThat(GlassFaceLogic.sparkY(a, lo, hi)).isEqualTo(GlassFaceLogic.sparkYRaw(a, lo, hi))
                if (!now.isNaN()) assertThat(GlassFaceLogic.sparkY(now, lo, hi)).isEqualTo(GlassFaceLogic.sparkYRaw(now, lo, hi))
                if (!keep.isNaN()) assertThat(hi).isAtLeast(keep.coerceAtMost(400.0))
                if (a < GlassFaceLogic.DEFAULT_LOW_MGDL && now < GlassFaceLogic.DEFAULT_LOW_MGDL) {
                    assertThat(hi).isEqualTo(max(GlassFaceLogic.DEFAULT_LOW_MGDL + GlassFaceLogic.SPARK_HYPO_LOW_HEADROOM_MGDL, keep + 10.0).coerceAtMost(400.0))
                } else {
                    assertThat(hi).isAtLeast(b.coerceAtMost(400.0))
                    assertThat(GlassFaceLogic.sparkY(b, lo, hi)).isEqualTo(GlassFaceLogic.sparkYRaw(b, lo, hi))
                }
            }
        }
        // the low edge is always inside the plot; the high edge too unless the current reading is below the low line
        // (round 4: the hypo scale ends above the low line, the band fades out upward)
        for ((a, b) in listOf(40.0 to 40.0, 100.0 to 110.0, 104.0 to 104.0, 171.0 to 171.0, 40.0 to 400.0, 400.0 to 400.0, 62.0 to 62.0))
            for ((l, h) in listOf(70.0 to 180.0, 80.0 to 140.0, 63.0 to 250.0)) {
                val (lo, hi) = GlassFaceLogic.sparkRange(a, b, l, h, nowMgdl = b)
                assertThat(GlassFaceLogic.bandEdgeVisible(GlassFaceLogic.sparkYRaw(l, lo, hi))).isTrue()
                if (b >= l) assertThat(GlassFaceLogic.bandEdgeVisible(GlassFaceLogic.sparkYRaw(h, lo, hi))).isTrue()
            }
        assertThat(GlassFaceLogic.SPARK_Y1 - GlassFaceLogic.SPARK_Y0).isAtLeast(48f)
        assertThat(GlassFaceLogic.SPARK_Y0 - GlassFaceLogic.HERO_Y1).isAtLeast(8f)
        assertThat(GlassFaceLogic.PILL_Y0 - GlassFaceLogic.SPARK_Y1).isAtLeast(8f)
        // the docked ball: 8 px or more under the card; the pill row and the graph start right of its hit area
        assertThat(GlassFaceLogic.BALL_Y - GlassFaceLogic.BALL_R - GlassFaceLogic.HERO_Y1).isAtLeast(8f)
        assertThat(GlassFaceLogic.PILL_ROW_BALL_X0 - GlassFaceLogic.BALL_HIT_RIGHT).isAtLeast(8f)
        assertThat(GlassFaceLogic.SPARK_X0_BALL - GlassFaceLogic.BALL_HIT_RIGHT).isAtLeast(8f)
        // Glass polish round 2: the card ends 14 px or more above the drawn ball
        assertThat(GlassFaceLogic.BALL_Y - GlassFaceLogic.BALL_R - GlassFaceLogic.HERO_Y1).isAtLeast(14f)
        assertThat(GlassFaceLogic.lowLine(0.0)).isEqualTo(70.0)
        assertThat(GlassFaceLogic.highLine(0.0, 0.0)).isEqualTo(180.0)
        assertThat(GlassFaceLogic.highLine(80.0, 160.0)).isEqualTo(160.0)
    }

    @Test fun sparklineAnchorMovesOnNewBgOrFiveMinuteBoundary() {
        val slot = GlassFaceLogic.SLOT_MS
        val base = (now / slot) * slot
        val bgTs = base + 60_000
        assertThat(GlassFaceLogic.sparkAnchor(bgTs, bgTs + 3 * min)).isEqualTo(bgTs)
        assertThat(GlassFaceLogic.sparkAnchor(bgTs, base + 7 * min)).isEqualTo(base + slot)
        assertThat(GlassFaceLogic.sparkAnchor(bgTs, base + 9 * min)).isEqualTo(base + slot)
        assertThat(GlassFaceLogic.sparkAnchor(0L, base + 11 * min)).isEqualTo(base + 2 * slot)
        assertThat(GlassFaceLogic.slotOf(bgTs, bgTs)).isEqualTo(35f)
        assertThat(GlassFaceLogic.slotOf(bgTs - 35 * slot, bgTs)).isEqualTo(0f)
        assertThat(GlassFaceLogic.slotOf(bgTs - 36 * slot, bgTs)).isNull()
        assertThat(GlassFaceLogic.slotOf(bgTs + 9 * slot, bgTs)).isEqualTo(44f)
        assertThat(GlassFaceLogic.slotOf(bgTs + 10 * slot, bgTs)).isNull()
    }

    // ------------------------------------------------------------------ bolus ball states

    @Test fun phoneLinkDoesNotWarnBeforeTheFirstAnswer() {
        // reachability not yet known (first lookup pending): no warning; the shared guard still refuses a bolus
        assertThat(GlassFaceLogic.phoneLink(PhoneReach.UNKNOWN, now - min, now)).isEqualTo(PhoneLink.OK)
        assertThat(GlassFaceLogic.phoneLink(PhoneReach.UNREACHABLE, now - min, now)).isEqualTo(PhoneLink.LOST_FRESH)
    }

    @Test fun ballStateFollowsTheSharedGuard() {
        fun s(state: BolusRequestGuard.State, armed: Boolean = false) =
            GlassFaceLogic.ballState(BolusRequestGuard.Check(state, BolusRequestGuard.Reason.NONE), armed)
        assertThat(s(BolusRequestGuard.State.IDLE)).isEqualTo(BallState.IDLE)
        assertThat(s(BolusRequestGuard.State.IDLE, armed = true)).isEqualTo(BallState.ARMED)
        assertThat(s(BolusRequestGuard.State.HIDDEN, armed = true)).isEqualTo(BallState.HIDDEN)
        assertThat(s(BolusRequestGuard.State.DISABLED, armed = true)).isEqualTo(BallState.DISABLED)
        assertThat(s(BolusRequestGuard.State.SENT, armed = true)).isEqualTo(BallState.SENT)
    }

    @Test fun ballHitRingAndAmount() {
        val bx = GlassFaceLogic.BALL_X
        val by = GlassFaceLogic.BALL_Y
        assertThat(GlassFaceLogic.ballHit(bx, by)).isTrue()
        assertThat(GlassFaceLogic.ballHit(bx - 47f, by)).isTrue()
        assertThat(GlassFaceLogic.ballHit(10f, by)).isFalse()       // off the round screen
        assertThat(GlassFaceLogic.ballHit(200f, 170f)).isFalse()    // hero card, BG digits
        assertThat(GlassFaceLogic.ballHit(250f, 300f)).isFalse()    // graph zone
        // UX round 4: clipped 8 px left of the graph (which starts at SPARK_X0_BALL) and 2 px under the card
        // the dosing target's clips are fixed values (round 1 moved the right edge 132 -> 136 by accident)
        assertThat(GlassFaceLogic.BALL_HIT_RIGHT).isEqualTo(132f)
        assertThat(GlassFaceLogic.BALL_HIT_TOP).isEqualTo(288f)
        assertThat(GlassFaceLogic.ballHit(GlassFaceLogic.BALL_HIT_RIGHT, by)).isTrue()
        assertThat(GlassFaceLogic.ballHit(GlassFaceLogic.BALL_HIT_RIGHT + 1f, by)).isFalse()
        assertThat(GlassFaceLogic.ballHit(GlassFaceLogic.SPARK_X0_BALL - 8f + 0.5f, by)).isFalse()
        assertThat(GlassFaceLogic.ballHit(bx, GlassFaceLogic.HERO_Y1)).isFalse()
        assertThat(GlassFaceLogic.ballHit(bx, GlassFaceLogic.BALL_HIT_TOP)).isTrue()
        // no pill is ever under the hit area (the row starts right of it), so the circle reaches down to the bezel:
        // 82 px of vertical target (Glass/Orbit UX round 2: the ball moved 6 px up)
        assertThat(GlassFaceLogic.ballHit(bx, by + 48f)).isTrue()
        assertThat(by + 48f - GlassFaceLogic.BALL_HIT_TOP).isAtLeast(80f)
        // the drawn ball keeps 30 px or more to the bezel
        val d = kotlin.math.hypot(bx - 225f, by - 225f) + GlassFaceLogic.BALL_R
        assertThat(225f - d).isAtLeast(30f)
        assertThat(GlassFaceLogic.armProgress(1000, 1000, 800)).isEqualTo(1f)
        assertThat(GlassFaceLogic.armProgress(1000, 1400, 800)).isEqualTo(0.5f)
        assertThat(GlassFaceLogic.armProgress(1000, 2000, 800)).isEqualTo(0f)
        assertThat(GlassFaceLogic.bolusAmountText(0.5, Locale.GERMANY)).isEqualTo("+0,5")
        assertThat(GlassFaceLogic.bolusAmountText(1.0, Locale.GERMANY)).isEqualTo("+1,0")
        assertThat(GlassFaceLogic.bolusAmountText(0.5, Locale.US)).isEqualTo("+0.5")
    }

    @Test fun burnInOffsetRange() {
        assertThat(GlassFaceLogic.burnInOffset(0.0)).isEqualTo(-4f)
        assertThat(GlassFaceLogic.burnInOffset(1.0)).isEqualTo(4f)
        assertThat(GlassFaceLogic.burnInOffset(0.5)).isEqualTo(0f)
    }

    // ------------------------------------------------------------------ layout variants / prefs

    @Test fun heroReflowsWithoutTheChip() {
        val chip = GlassFaceLogic.heroLayout(showChip = true, metaEmpty = false)
        assertThat(chip.bgX).isEqualTo(GlassFaceLogic.BG_X_CHIP)
        assertThat(chip.bgY).isEqualTo(GlassFaceLogic.BG_Y)
        assertThat(chip.chipY).isEqualTo(GlassFaceLogic.BG_Y - 2f) // the chip on the cap
        assertThat(chip.chipX).isEqualTo(GlassFaceLogic.CHIP_X)
        // the delta line sits under the digits at full card width
        assertThat(chip.metaCentreX).isEqualTo(225f)
        assertThat(chip.metaY).isEqualTo(GlassFaceLogic.META_Y)
        assertThat(chip.bgMaxWidth).isEqualTo(GlassFaceLogic.BG_MAX_WIDTH)
        // empty meta line: digits and chip centre vertically
        val chipNoMeta = GlassFaceLogic.heroLayout(showChip = true, metaEmpty = true)
        assertThat(chipNoMeta.bgY to chipNoMeta.chipY).isEqualTo(208f to 206f)
        // the digits' left and right edges (the full budget) keep 12 px card padding and 8 px to the chip
        assertThat(chip.bgX - GlassFaceLogic.BG_MAX_WIDTH / 2f).isAtLeast(GlassFaceLogic.HERO_X0 + 12f)
        assertThat(chip.bgX + GlassFaceLogic.BG_MAX_WIDTH / 2f).isAtMost(GlassFaceLogic.CHIP_X - GlassFaceLogic.CHIP_R - 8f)
        assertThat(GlassFaceLogic.CHIP_X + GlassFaceLogic.CHIP_R).isAtMost(GlassFaceLogic.HERO_X1 - 8f)
        // Glass polish round 1: digits + chip are one group centred on the card; the widest group is the default
        assertThat(GlassFaceLogic.heroGroup(chip, true, GlassFaceLogic.BG_MAX_WIDTH)).isEqualTo(chip)
        val narrow = GlassFaceLogic.heroGroup(chip, true, 140f)
        assertThat(narrow.bgX - 70f + (narrow.chipX + GlassFaceLogic.CHIP_R)).isWithin(0.01f).of(450f)
        assertThat(narrow.chipX - GlassFaceLogic.CHIP_R - (narrow.bgX + 70f)).isWithin(0.01f).of(GlassFaceLogic.CHIP_GAP)
        assertThat(GlassFaceLogic.heroGroup(chip, false, 140f)).isEqualTo(chip)
        // trend.hidden_pref: BG centred, meta under it, 300 px for the digits
        val noChip = GlassFaceLogic.heroLayout(showChip = false, metaEmpty = false)
        assertThat(listOf(noChip.bgX, noChip.bgY, noChip.metaCentreX, noChip.metaY, noChip.bgMaxWidth))
            .containsExactly(225f, GlassFaceLogic.BG_Y, 225f, GlassFaceLogic.META_Y, GlassFaceLogic.BG_MAX_WIDTH_NO_CHIP).inOrder()
        assertThat(GlassFaceLogic.BG_MAX_WIDTH_NO_CHIP).isAtMost(GlassFaceLogic.HERO_X1 - GlassFaceLogic.HERO_X0 - 24f)
        val noChipNoMeta = GlassFaceLogic.heroLayout(showChip = false, metaEmpty = true)
        assertThat(noChipNoMeta.bgX to noChipNoMeta.bgY).isEqualTo(225f to 208f)
        // the card stays inside the round screen at its corners (r 28)
        for ((x, y) in listOf(GlassFaceLogic.HERO_X0 + 8f to GlassFaceLogic.HERO_Y0 + 8f, GlassFaceLogic.HERO_X1 - 8f to GlassFaceLogic.HERO_Y1 - 8f))
            assertThat(kotlin.math.hypot(x - 225f, y - 225f)).isLessThan(215f)
        // the delta line stays inside the card (28 px text, 8 px padding)
        assertThat(GlassFaceLogic.META_Y + 14f).isAtMost(GlassFaceLogic.HERO_Y1 - 4f)
    }

    @Test fun dataOldWarningMovesIntoTheMetaRow() {
        // Glass/Orbit UX round 2 (as Halo): "Keine Daten · 17′" is said once, in the card; the footer keeps its line
        assertThat(GlassFaceLogic.metaWarning(Warning.NO_DATA)).isEqualTo(Warning.NO_DATA)
        assertThat(GlassFaceLogic.metaWarning(Warning.PHONE_LOST)).isEqualTo(Warning.PHONE_LOST)
        assertThat(GlassFaceLogic.metaWarning(Warning.LOOP_BAD)).isNull()
        assertThat(GlassFaceLogic.metaWarning(null)).isNull()
        assertThat(GlassFaceLogic.footerWarnings(listOf(Warning.NO_DATA, Warning.LOOP_BAD))).isEmpty()
        assertThat(GlassFaceLogic.footerWarnings(listOf(Warning.LOOP_BAD, Warning.RES_LOW))).containsExactly(Warning.LOOP_BAD, Warning.RES_LOW).inOrder()
    }

    @Test fun chipOnlyForACurrentReading() {
        // UX round 3: a struck reading and "---" have no chip (no lone age in the corner, no empty hollow circle)
        assertThat(GlassFaceLogic.chipShown(pref = true, struck = false, bgText = "6,4")).isTrue()
        assertThat(GlassFaceLogic.chipShown(pref = true, struck = true, bgText = "6,4")).isFalse()
        assertThat(GlassFaceLogic.chipShown(pref = true, struck = false, bgText = "---")).isFalse()
        assertThat(GlassFaceLogic.chipShown(pref = false, struck = false, bgText = "6,4")).isFalse()
    }

    @Test fun lowDigitsAreRedNeverWhite() {
        // spec F1 / UX round 3: a fresh low reading (3,4) draws red digits like urgent low
        assertThat(GlassFaceLogic.digitTone(Range.LOW, stale = false)).isEqualTo(GlassFaceLogic.DigitTone.LOW)
        assertThat(GlassFaceLogic.digitTone(Range.URGENT_LOW, stale = false)).isEqualTo(GlassFaceLogic.DigitTone.LOW)
        // Glass/Orbit UX round 2 (sunlight): high and very high are amber digits, in range its own (mint) tone
        assertThat(GlassFaceLogic.digitTone(Range.IN, stale = false)).isEqualTo(GlassFaceLogic.DigitTone.IN)
        assertThat(GlassFaceLogic.digitTone(Range.HIGH, stale = false)).isEqualTo(GlassFaceLogic.DigitTone.HIGH)
        assertThat(GlassFaceLogic.digitTone(Range.VERY_HIGH, stale = false)).isEqualTo(GlassFaceLogic.DigitTone.HIGH)
        assertThat(GlassFaceLogic.digitTone(Range.STALE, stale = true)).isEqualTo(GlassFaceLogic.DigitTone.STALE)
        assertThat(GlassFaceLogic.digitTone(Range.LOW, stale = true)).isEqualTo(GlassFaceLogic.DigitTone.STALE)
        // Glass/Orbit UX round 6: from 30' a quieter tone
        assertThat(GlassFaceLogic.digitTone(Range.STALE, stale = true, veryStale = true)).isEqualTo(GlassFaceLogic.DigitTone.VERY_STALE)
    }

    @Test fun decimalSeparatorSplit() {
        assertThat(GlassFaceLogic.splitDecimal("6,4")).isEqualTo(Triple("6", ",", "4"))
        assertThat(GlassFaceLogic.splitDecimal("12.4")).isEqualTo(Triple("12", ".", "4"))
        assertThat(GlassFaceLogic.splitDecimal("115")).isNull()
        assertThat(GlassFaceLogic.splitDecimal("---")).isNull()
        assertThat(GlassFaceLogic.splitDecimal(",4")).isNull()
    }

    @Test fun metaLineEmptyWithoutAReading() {
        assertThat(GlassFaceLogic.metaVisible(BgAge.NONE, prefShowAgo = true)).isEqualTo(false to false)
        assertThat(GlassFaceLogic.metaVisible(BgAge.FRESH, prefShowAgo = true)).isEqualTo(true to true)
        assertThat(GlassFaceLogic.metaVisible(BgAge.FRESH, prefShowAgo = false)).isEqualTo(true to false)
        // Glass live round 1: an aging reading keeps its delta (only stale data drops it)
        assertThat(GlassFaceLogic.metaVisible(BgAge.AGING, prefShowAgo = false)).isEqualTo(true to true)
        assertThat(GlassFaceLogic.metaVisible(BgAge.STALE, prefShowAgo = true)).isEqualTo(false to true)
    }

    @Test fun zeroDeltaIsAlwaysShown() {
        // Glass live round 1: delta 0 left only "0′" under the digits on the watch; a steady BG shows "±0,0"
        assertThat(GlassFaceLogic.deltaText("0,0")).isEqualTo("±0,0")
        assertThat(GlassFaceLogic.deltaText("+0,0")).isEqualTo("±0,0")
        assertThat(GlassFaceLogic.deltaText("+0.0")).isEqualTo("±0.0")
        assertThat(GlassFaceLogic.deltaText("0")).isEqualTo("±0")
        assertThat(GlassFaceLogic.deltaText("+0")).isEqualTo("±0")
        assertThat(GlassFaceLogic.deltaText("±0,0")).isEqualTo("±0,0")
        // a rounded falling value keeps the phone's sign
        assertThat(GlassFaceLogic.deltaText("-0,0")).isEqualTo("-0,0")
        assertThat(GlassFaceLogic.deltaText("-0.0")).isEqualTo("-0.0")
        // non-zero values are untouched
        assertThat(GlassFaceLogic.deltaText("+0,1")).isEqualTo("+0,1")
        assertThat(GlassFaceLogic.deltaText("10")).isEqualTo("+10")
        // a picked variant the phone left empty falls back to the plain delta
        assertThat(GlassFaceLogic.pickDelta("+0,0", "--", "", "", detailed = false, avg = true)).isEqualTo("+0,0")
        assertThat(GlassFaceLogic.pickDelta("+0,0", "-0,1", "", "", detailed = false, avg = true)).isEqualTo("-0,1")
        assertThat(GlassFaceLogic.deltaText(GlassFaceLogic.pickDelta("0.0", "", "", "", detailed = false, avg = true))).isEqualTo("±0.0")
        // the meta line ("±0,0 · 0′") fits the card at the 28 px size: HERO width minus padding
        assertThat(GlassFaceLogic.META_Y + 14f).isAtMost(GlassFaceLogic.HERO_Y1 - 4f)
    }

    @Test fun timeAndCardClearTheSystemStatusIcon() {
        // Glass live round 1: the Samsung status icon spans y 23..49 (plus its dark disc to about 55); polish round 1
        // moved the time block 5 px further down and the card's top with it
        assertThat(GlassFaceLogic.HERO_Y0).isAtLeast(130f)
        // Glass polish round 2: 148 px (the bottom 6 px up for the ball), still room for the 112 px digits and the row
        assertThat(GlassFaceLogic.HERO_Y1 - GlassFaceLogic.HERO_Y0).isAtLeast(145f)
    }

    @Test fun pillLayout() {
        assertThat(GlassFaceLogic.pills(showIob = true, pumpControl = false)).isEqualTo(Pills.BOTH)
        assertThat(GlassFaceLogic.pills(showIob = false, pumpControl = false)).isEqualTo(Pills.LOOP_ONLY)
        assertThat(GlassFaceLogic.pills(showIob = true, pumpControl = true)).isEqualTo(Pills.IOB_ONLY)
        assertThat(GlassFaceLogic.pills(showIob = false, pumpControl = true)).isEqualTo(Pills.IOB_ONLY)
        // UX round 2: content-sized pills centred as a group; a hidden pill (width 0) takes no gap
        val x = GlassFaceLogic.pillRow(floatArrayOf(100f, 90f, 60f), 8f)
        assertThat(x[0]).isEqualTo(225f - 133f)
        assertThat(x[1]).isEqualTo(225f - 133f + 108f)
        assertThat(x[2]).isEqualTo(225f - 133f + 206f)
        val two = GlassFaceLogic.pillRow(floatArrayOf(100f, 0f, 60f), 8f)
        assertThat(two[1].isNaN()).isTrue()
        assertThat(two[2] + 60f - 225f).isEqualTo(225f - two[0])
    }

    @Test fun pillRowMovesRightOfTheBall() {
        // UX round 4: without the ball centred at 225 (320 px); with it right of the hit area, to the bezel-safe chord
        assertThat(GlassFaceLogic.pillRowFrame(ballShown = false)).isEqualTo(225f to GlassFaceLogic.PILL_ROW_MAX_WIDTH)
        val (c, w) = GlassFaceLogic.pillRowFrame(ballShown = true)
        assertThat(c - w / 2f).isAtLeast(GlassFaceLogic.BALL_HIT_RIGHT + 8f)
        // Glass/Orbit UX round 3: both ends 16 px or more inside the round edge at the row's lower edge (y 384)
        val half = GlassFaceLogic.chordWidth(GlassFaceLogic.PILL_Y + 12f, 16f) / 2f
        assertThat(c + w / 2f).isAtMost(225f + half)
        assertThat(GlassFaceLogic.PILL_ROW_MAX_WIDTH / 2f).isAtMost(half)
    }

    @Test fun predictionsSuppressed() {
        fun p(pref: Boolean = true, stale: Boolean = false, mode: LoopMode = LoopMode.CLOSED, client: Boolean = false, pc: Boolean = false) =
            GlassFaceLogic.showPredictions(pref, stale, mode, client, pc)
        assertThat(p()).isTrue()
        assertThat(p(mode = LoopMode.UNKNOWN)).isTrue()
        assertThat(p(pref = false)).isFalse()
        assertThat(p(stale = true)).isFalse()
        assertThat(p(mode = LoopMode.OPEN)).isFalse()
        assertThat(p(client = true)).isFalse()
        assertThat(p(pc = true)).isFalse()
    }

    @Test fun loopGlyphAndLabel() {
        // Glass/Orbit UX round 2: the loop glyph greys while W2/W3 own the slot (the age only repeats the lost link)
        assertThat(GlassFaceLogic.loopGlyphMuted(null)).isFalse()
        assertThat(GlassFaceLogic.loopGlyphMuted(Warning.RES_LOW)).isFalse()
        assertThat(GlassFaceLogic.loopGlyphMuted(Warning.LOOP_BAD)).isFalse()
        assertThat(GlassFaceLogic.loopGlyphMuted(Warning.PHONE_LOST)).isTrue()
        assertThat(GlassFaceLogic.loopGlyphMuted(Warning.NO_DATA)).isTrue()
        assertThat(GlassFaceLogic.loopLabelAllowed(LoopHealth.OFF)).isTrue() // loop.off keeps "LOOP"
        assertThat(GlassFaceLogic.loopLabelAllowed(LoopHealth.OPEN)).isFalse()
    }

    // ------------------------------------------------------------------ ambient range shape

    @Test fun ambientOutlineCarriesTheRange() {
        fun o(r: Range, lowBit: Boolean = false) = GlassFaceLogic.ambientOutline(r, lowBit)
        assertThat(o(Range.IN).style).isEqualTo(OutlineStyle.SOLID)
        assertThat(o(Range.IN).tone).isEqualTo(AmbTone.LINE)
        assertThat(o(Range.IN, lowBit = true).style).isEqualTo(OutlineStyle.NONE)
        assertThat(o(Range.STALE).style).isEqualTo(OutlineStyle.SOLID)
        assertThat(o(Range.STALE, lowBit = true).style).isEqualTo(OutlineStyle.NONE)
        // UX round 1: quiet 1 px lines (never brighter than amb3), the shape carries the range
        assertThat(o(Range.HIGH)).isEqualTo(GlassFaceLogic.AmbientOutline(OutlineStyle.DASHED, 1f, 0f, AmbTone.LINE, false))
        assertThat(o(Range.VERY_HIGH)).isEqualTo(GlassFaceLogic.AmbientOutline(OutlineStyle.DASHED, 1f, 0f, AmbTone.A3, false))
        assertThat(o(Range.LOW)).isEqualTo(GlassFaceLogic.AmbientOutline(OutlineStyle.DOUBLE, 1f, 1f, AmbTone.LINE, false))
        // UX round 3: urgent low is one heavy line (low: a double thin line), a different SHAPE; Glass/Orbit UX round 6: 3 px
        assertThat(o(Range.URGENT_LOW)).isEqualTo(GlassFaceLogic.AmbientOutline(OutlineStyle.SOLID, 3f, 0f, AmbTone.A3, true))
        // Glass/Orbit UX round 6: burn-in protection drops the static outline, only the (temporary) lows keep theirs
        for (r in listOf(Range.IN, Range.STALE, Range.HIGH, Range.VERY_HIGH))
            assertThat(GlassFaceLogic.ambientOutline(r, lowBit = false, burnIn = true).style).isEqualTo(OutlineStyle.NONE)
        assertThat(GlassFaceLogic.ambientOutline(Range.LOW, lowBit = false, burnIn = true)).isEqualTo(o(Range.LOW))
        assertThat(GlassFaceLogic.ambientOutline(Range.URGENT_LOW, lowBit = false, burnIn = true)).isEqualTo(o(Range.URGENT_LOW))
        assertThat(o(Range.URGENT_LOW).style).isNotEqualTo(o(Range.LOW).style)
        for (r in Range.entries) assertThat(o(r).tone).isNotIn(listOf(AmbTone.A1, AmbTone.A2))
        // low-bit keeps every non-in shape (drawn white, AA off)
        for (r in listOf(Range.HIGH, Range.VERY_HIGH, Range.LOW, Range.URGENT_LOW))
            assertThat(o(r, lowBit = true).style).isEqualTo(o(r).style)
        // urgent low never looks like in range
        assertThat(o(Range.URGENT_LOW)).isNotEqualTo(o(Range.IN))
    }

    @Test fun refusalHasOneMessagePerReason() {
        assertThat(GlassFaceLogic.refusal(BolusRequestGuard.Reason.NO_MAX_BOLUS)).isEqualTo(GlassFaceLogic.Refusal.NO_MAX_BOLUS)
        assertThat(GlassFaceLogic.refusal(BolusRequestGuard.Reason.OVER_MAX_BOLUS)).isEqualTo(GlassFaceLogic.Refusal.OVER_MAX_BOLUS)
        assertThat(GlassFaceLogic.refusal(BolusRequestGuard.Reason.NO_WEAR_CONTROL)).isEqualTo(GlassFaceLogic.Refusal.NO_WEAR_CONTROL)
        assertThat(GlassFaceLogic.refusal(BolusRequestGuard.Reason.NO_PHONE)).isEqualTo(GlassFaceLogic.Refusal.NO_PHONE)
        assertThat(GlassFaceLogic.refusal(BolusRequestGuard.Reason.STALE_DATA)).isEqualTo(GlassFaceLogic.Refusal.STALE_DATA)
        assertThat(GlassFaceLogic.refusal(BolusRequestGuard.Reason.PENDING)).isEqualTo(GlassFaceLogic.Refusal.PENDING)
        assertThat(GlassFaceLogic.refusal(BolusRequestGuard.Reason.NONE)).isNull()
        assertThat(GlassFaceLogic.refusal(BolusRequestGuard.Reason.FIRST_START)).isNull()
    }

    /**
     * Glass polish round 4: at a hypo the sparkline separates 3,5 / 2,6 / 2,2 clearly (a fall from 6,0 to 3,5 as in
     * the low_3_5_fall render): low edge at about y 330, 3,5 a few px under it, 2,6 10 px or more and 2,2 15 px or more.
     */
    @Test
    fun sparkHypoScaleSeparatesLowValues() {
        val low = GlassFaceLogic.DEFAULT_LOW_MGDL
        fun mg(mmol: Double) = mmol * 18.0
        // the low_3_5_fall render: the last 30 min run from about 4,0 down to 3,5
        val (lo, hi) = GlassFaceLogic.sparkRange(mg(3.5), mg(6.0), nowMgdl = mg(3.5), keepMaxMgdl = mg(4.0))
        assertThat(lo).isEqualTo(40.0)
        assertThat(hi).isWithin(0.01).of(120.0)
        val edge = GlassFaceLogic.sparkYRaw(low, lo, hi)
        assertThat(edge).isWithin(1.5f).of(330f)
        assertThat(GlassFaceLogic.bandEdgeVisible(edge)).isTrue()
        val y35 = GlassFaceLogic.sparkY(mg(3.5), lo, hi)
        val y26 = GlassFaceLogic.sparkY(mg(2.6), lo, hi)
        val y22 = GlassFaceLogic.sparkY(mg(2.2), lo, hi)
        assertThat(y35).isWithin(1.5f).of(333f)
        assertThat(y35 - edge).isAtLeast(3f) // under the edge, not on it
        assertThat(y26 - edge).isAtLeast(10f)
        assertThat(y22 - edge).isAtLeast(15f)
        assertThat(y26 - y35).isAtLeast(7f)
        assertThat(y22 - y26).isAtLeast(3f)
        // every value stays inside the plot (never clamped above the top)
        assertThat(GlassFaceLogic.sparkY(mg(6.0), lo, hi)).isEqualTo(GlassFaceLogic.sparkYRaw(mg(6.0), lo, hi))
        // the urgent low render (7,0 falling to 2,6): still 10 px or more between 2,6 and the low edge
        val (ulo, uhi) = GlassFaceLogic.sparkRange(mg(2.6), mg(7.0), nowMgdl = mg(2.6), keepMaxMgdl = mg(3.9))
        assertThat(GlassFaceLogic.sparkY(mg(2.6), ulo, uhi) - GlassFaceLogic.sparkYRaw(low, ulo, uhi)).isAtLeast(10f)
        // a low after a meal peak 2 h ago (180 / 250 then down to 61 / 47 / 39 mg/dL now, the last 30 min at most 100):
        // same fixed window, same distances; the old peak is clamped (drawn as off-scale chevrons; round 8: far beyond
        // the tolerance, so not included)
        for (peak in listOf(180.0, 250.0)) {
            val (plo, phi) = GlassFaceLogic.sparkRange(39.0, peak, nowMgdl = 39.0, keepMaxMgdl = 100.0)
            assertThat(plo to phi).isEqualTo(lo to hi)
            val pedge = GlassFaceLogic.sparkYRaw(low, plo, phi)
            assertThat(GlassFaceLogic.sparkY(61.0, plo, phi) - pedge).isAtLeast(3f)
            assertThat(GlassFaceLogic.sparkY(47.0, plo, phi) - pedge).isAtLeast(10f)
            assertThat(GlassFaceLogic.sparkY(39.0, plo, phi) - pedge).isAtLeast(15f)
            assertThat(GlassFaceLogic.bandEdgeVisible(GlassFaceLogic.sparkYRaw(GlassFaceLogic.DEFAULT_HIGH_MGDL, plo, phi))).isFalse()
        }
        // in range and high: unchanged
        assertThat(GlassFaceLogic.sparkRange(100.0, 110.0)).isEqualTo(55.0 to 195.0)
        assertThat(GlassFaceLogic.sparkRange(150.0, 300.0)).isEqualTo(55.0 to 300.0)
    }

    /**
     * Glass polish round 7: a clamped run is a row of up-chevrons, at least one, as many as fit at the pitch.
     * Glass polish round 8: a 28 px pitch (the gap wider than the 10 px chevron) and at most 5 per run, spread over it.
     */
    @Test fun offChevronCount() {
        assertThat(GlassFaceLogic.offChevronCount(2f, 10f, 28f)).isEqualTo(1)   // a lone point off the top
        assertThat(GlassFaceLogic.offChevronCount(10f, 10f, 28f)).isEqualTo(1)
        assertThat(GlassFaceLogic.offChevronCount(37f, 10f, 28f)).isEqualTo(1)
        assertThat(GlassFaceLogic.offChevronCount(38f, 10f, 28f)).isEqualTo(2)
        assertThat(GlassFaceLogic.offChevronCount(94f, 10f, 28f)).isEqualTo(4)
        assertThat(GlassFaceLogic.offChevronCount(200f, 10f, 28f)).isEqualTo(5) // capped (7 would fit)
        assertThat(GlassFaceLogic.offChevronCount(200f, 10f, 28f, maxCount = 3)).isEqualTo(3)
        assertThat(GlassFaceLogic.offChevronCount(200f, 10f, 28f, maxCount = 0)).isEqualTo(1) // never none
        // the step: the pitch while uncapped, spread over the run when capped; the gap is always >= the chevron width
        assertThat(GlassFaceLogic.offChevronStep(94f, 10f, 28f, 4)).isEqualTo(28f)
        assertThat(GlassFaceLogic.offChevronStep(200f, 10f, 28f, 5)).isWithin(0.01f).of(47.5f)
        for (w in listOf(38f, 60f, 120f, 200f, 300f)) {
            val n = GlassFaceLogic.offChevronCount(w, 10f, 28f)
            assertThat(GlassFaceLogic.offChevronStep(w, 10f, 28f, n) - 10f).isAtLeast(10f)
            assertThat((n - 1) * GlassFaceLogic.offChevronStep(w, 10f, 28f, n) + 10f).isAtMost(w + 0.01f) // inside the run
        }
    }

    /**
     * Glass polish round 6 (safety, strict panel): a rebound high after a low (ball_tt_very_high: 2,2 an hour ago,
     * 22,2 now) was drawn as a flat green line at the plot top. Now the top covers the current reading, the high edge
     * is drawn and the current dot is never clamped.
     */
    @Test
    fun sparkReboundHighIsNeverClamped() {
        val low = GlassFaceLogic.DEFAULT_LOW_MGDL
        val high = GlassFaceLogic.DEFAULT_HIGH_MGDL
        val (lo, hi) = GlassFaceLogic.sparkRange(50.0, 300.0, nowMgdl = 300.0, keepMaxMgdl = 300.0)
        assertThat(lo).isEqualTo(40.0) // the earlier low on the fixed hypo scale
        assertThat(hi).isAtLeast(300.0)
        assertThat(GlassFaceLogic.sparkY(300.0, lo, hi)).isEqualTo(GlassFaceLogic.sparkYRaw(300.0, lo, hi))
        assertThat(GlassFaceLogic.bandEdgeVisible(GlassFaceLogic.sparkYRaw(high, lo, hi))).isTrue()
        assertThat(GlassFaceLogic.bandEdgeVisible(GlassFaceLogic.sparkYRaw(low, lo, hi))).isTrue()
        // the current reading clearly above the high edge (not on the plot top as a "flat line")
        assertThat(GlassFaceLogic.sparkYRaw(high, lo, hi) - GlassFaceLogic.sparkY(300.0, lo, hi)).isAtLeast(10f)
        // the very high sentinel after a low: up to the 400 cap
        val (slo, shi) = GlassFaceLogic.sparkRange(39.0, 400.0, nowMgdl = 400.0, keepMaxMgdl = 400.0)
        assertThat(slo to shi).isEqualTo(40.0 to 400.0)
        // nothing to clamp: no off-scale runs
        val xs = floatArrayOf(0f, 10f, 20f)
        assertThat(GlassFaceLogic.offScaleRuns(xs, doubleArrayOf(50.0, 200.0, 300.0), 3, hi, FloatArray(9))).isEqualTo(0)
    }

    /** Glass polish round 6: an in-range reading with only the forecast dipping under the low line. */
    @Test
    fun sparkForecastOnlyDipKeepsTheTraceAndTheBand() {
        val low = GlassFaceLogic.DEFAULT_LOW_MGDL
        val high = GlassFaceLogic.DEFAULT_HIGH_MGDL
        // history 120..160, now 110, forecast down to 60
        val (lo, hi) = GlassFaceLogic.sparkRange(60.0, 160.0, nowMgdl = 110.0, keepMaxMgdl = 130.0)
        assertThat(lo).isEqualTo(40.0)
        assertThat(hi).isAtLeast(160.0) // the history above the forecast's dip is not clamped
        for (v in listOf(60.0, 110.0, 160.0)) assertThat(GlassFaceLogic.sparkY(v, lo, hi)).isEqualTo(GlassFaceLogic.sparkYRaw(v, lo, hi))
        assertThat(GlassFaceLogic.bandEdgeVisible(GlassFaceLogic.sparkYRaw(low, lo, hi))).isTrue()
        assertThat(GlassFaceLogic.bandEdgeVisible(GlassFaceLogic.sparkYRaw(high, lo, hi))).isTrue()
        // the dip reads under the low edge
        assertThat(GlassFaceLogic.sparkY(60.0, lo, hi) - GlassFaceLogic.sparkYRaw(low, lo, hi)).isAtLeast(2f)
        // and a high reading with a forecast dip: also nothing clamped
        val (hlo, hhi) = GlassFaceLogic.sparkRange(65.0, 260.0, nowMgdl = 250.0, keepMaxMgdl = 260.0)
        assertThat(hlo to hhi).isEqualTo(40.0 to 260.0)
    }

    /**
     * Glass polish round 8: a trivial overshoot (urgent_low: 7,0 = 126 mg/dL an hour ago against a 120 top) is not
     * "far off the scale": the top rises to include it while 3,5 / 2,6 / 2,2 keep 3 / 10 / 15 px under the low edge.
     * A larger overshoot is still clamped (the round-5 meal-peak case).
     */
    @Test
    fun sparkSmallOvershootIsIncludedWhenTheHypoSpacingHolds() {
        val low = GlassFaceLogic.DEFAULT_LOW_MGDL
        val (lo, hi) = GlassFaceLogic.sparkRange(47.0, 126.0, nowMgdl = 47.0, keepMaxMgdl = 70.0)
        assertThat(lo).isEqualTo(40.0)
        assertThat(hi).isWithin(0.01).of(126.0)
        // no off-scale run
        val xs = floatArrayOf(0f, 10f, 20f, 30f)
        assertThat(GlassFaceLogic.offScaleRuns(xs, doubleArrayOf(110.0, 126.0, 90.0, 47.0), 4, hi, FloatArray(9))).isEqualTo(0)
        val edge = GlassFaceLogic.sparkYRaw(low, lo, hi)
        assertThat(GlassFaceLogic.sparkY(63.0, lo, hi) - edge).isAtLeast(3f)
        assertThat(GlassFaceLogic.sparkY(47.0, lo, hi) - edge).isAtLeast(10f)
        assertThat(GlassFaceLogic.sparkY(39.0, lo, hi) - edge).isAtLeast(15f)
        assertThat(GlassFaceLogic.hypoSeparationsHold(lo, hi, low)).isTrue()
        // the base window holds the spacing; a 135 top (within the tolerance) would not, so 135 is clamped
        assertThat(GlassFaceLogic.hypoSeparationsHold(40.0, 120.0, low)).isTrue()
        assertThat(GlassFaceLogic.hypoSeparationsHold(40.0, 135.0, low)).isFalse()
        assertThat(GlassFaceLogic.sparkRange(47.0, 135.0, nowMgdl = 47.0, keepMaxMgdl = 70.0)).isEqualTo(40.0 to 120.0)
        // beyond the tolerance: clamped as before
        assertThat(GlassFaceLogic.sparkRange(47.0, 180.0, nowMgdl = 47.0, keepMaxMgdl = 70.0)).isEqualTo(40.0 to 120.0)
    }

    /** Glass polish round 6: only OLDER history is clamped; its runs are found with linear crossings. */
    @Test
    fun sparkOffScaleRuns() {
        val xs = floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f)
        val vs = doubleArrayOf(100.0, 140.0, 200.0, 160.0, 100.0, 80.0)
        val out = FloatArray(9)
        assertThat(GlassFaceLogic.offScaleRuns(xs, vs, 6, 120.0, out)).isEqualTo(1)
        assertThat(out[0]).isWithin(0.01f).of(5f)   // 100 -> 140 crosses 120 halfway
        assertThat(out[1]).isWithin(0.01f).of(36.667f) // 160 -> 100 crosses 120 two thirds of the way
        assertThat(out[2]).isWithin(0.01f).of(200f) // the real peak (its colour: amber above the high line)
        // a run that starts or ends off the scale; a lone point
        assertThat(GlassFaceLogic.offScaleRuns(xs, doubleArrayOf(200.0, 100.0, 90.0, 80.0, 70.0, 130.0), 6, 120.0, out)).isEqualTo(2)
        assertThat(out[0]).isWithin(0.01f).of(-2f)
        assertThat(out[1]).isWithin(0.01f).of(8f)      // 200 -> 100 crosses 120 at 4/5
        assertThat(out[3]).isWithin(0.01f).of(48.333f) // 70 -> 130 crosses 120 at 5/6
        assertThat(out[4]).isWithin(0.01f).of(52f)     // the run ends off the scale: + 2 px
        assertThat(GlassFaceLogic.offScaleRuns(xs, vs, 6, 400.0, out)).isEqualTo(0)
    }
}
