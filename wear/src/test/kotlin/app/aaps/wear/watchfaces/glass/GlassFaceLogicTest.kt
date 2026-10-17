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
        assertThat(GlassFaceLogic.bgText("2,2", 1, Sentinel.LOW)).isEqualTo("2,2")
        assertThat(GlassFaceLogic.bgText("9,4", 42, null)).isEqualTo("9,4")   // very stale: still struck number
        assertThat(GlassFaceLogic.bgText("9,4", 60, null)).isEqualTo("---")   // from 60': digits replaced
        assertThat(GlassFaceLogic.bgText("---", null, null)).isEqualTo("---")
        assertThat(GlassFaceLogic.bgText("9,4", null, null)).isEqualTo("---") // no time stamp
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
        assertThat(GlassFaceLogic.deltaText("0.0")).isEqualTo("+0.0")
        assertThat(GlassFaceLogic.deltaText("--")).isEqualTo("--")
        assertThat(GlassFaceLogic.deltaText("")).isEqualTo("--")
        assertThat(GlassFaceLogic.pickDelta("d", "a", "dd", "ad", detailed = false, avg = true)).isEqualTo("a")
        assertThat(GlassFaceLogic.pickDelta("d", "a", "dd", "ad", detailed = true, avg = false)).isEqualTo("dd")
        assertThat(GlassFaceLogic.pickDelta("d", "a", "dd", "ad", detailed = true, avg = true)).isEqualTo("ad")
        assertThat(GlassFaceLogic.pickDelta("d", "a", "dd", "ad", detailed = false, avg = false)).isEqualTo("d")
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
        // UX round 1: 116 px start (Halo-like scale), 196 px next to the chip, floor 80
        assertThat(bgFit("9,4")).isEqualTo(116f)
        assertThat(bgFit("11,8")).isEqualTo(92f)
        assertThat(bgFit("400+")).isEqualTo(80f)
        assertThat(bgFit("22,2+")).isEqualTo(GlassFaceLogic.BG_MIN_SIZE)
        assertThat(bgFit("254")).isEqualTo(112f) // three mg/dL digits step down slightly next to the chip
        assertThat(bgFit("---")).isEqualTo(116f)
        // the result fits, in 2 px steps from the start size
        for (v in listOf("9,4", "11,8", "400+", "254", "39")) {
            val size = bgFit(v)
            assertThat(heroWidth(v, size)).isAtMost(GlassFaceLogic.BG_MAX_WIDTH)
            assertThat((GlassFaceLogic.BG_SIZE - size) % 2f).isEqualTo(0f)
        }
        // chip off: 270 px, "22,2+" keeps a larger size
        assertThat(bgFit("22,2+", GlassFaceLogic.BG_MAX_WIDTH_NO_CHIP)).isEqualTo(96f)
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
        assertThat(tt.map { it.text }).containsExactly("TT 5,6", "KH 24g", "0,85 U/h").inOrder()
        assertThat(tt[0].tone).isEqualTo(Tone.TT)
        assertThat(footer(cob = "20(30)g").map { it.text }).contains("KH 20(30)g")       // shown as sent
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
        assertThat(res.map { it.text }).containsExactly("Reservoir 5U", "0,85 U/h").inOrder()
        assertThat(res[0].tone).isEqualTo(Tone.ALERT)
        val batt = footer(warnings = listOf(Warning.PHONE_BATTERY), batt = "18", battLevel = 0)
        assertThat(batt[0].text).isEqualTo("Tel 18 %")
        assertThat(batt[0].tone).isEqualTo(Tone.WARN)
        assertThat(footer(warnings = listOf(Warning.PHONE_BATTERY), batt = "7", battLevel = 0)[0].tone).isEqualTo(Tone.ALERT)
        assertThat(footer(warnings = listOf(Warning.RES_LOW), res = "18U", resLevel = 1)[0].tone).isEqualTo(Tone.WARN)
        // W10 is a glyph only
        assertThat(footer(warnings = listOf(Warning.PHONE_LOST_FRESH)).map { it.text }).containsExactly("0,85 U/h")
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
        assertThat(roboto.map { it.kind }).containsExactly(RunKind.WARNING, RunKind.TT).inOrder() // TT is the last to go
        assertThat(GlassFaceLogic.lineWidth(roboto, sep, this.roboto)).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH)
        // at a wider worst-case font even the TT goes, the warning stays
        val wide = runs(measure)
        assertThat(wide.map { it.kind }).containsExactly(RunKind.WARNING)
        assertThat(GlassFaceLogic.lineWidth(wide, sep, measure)).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH)
    }

    @Test fun footerMaxWidthIsTheChordAt093R() {
        assertThat(GlassFaceLogic.FOOTER_MAX_WIDTH).isEqualTo(257f)
    }

    /** Fits [text] as the single W1-W6 owner run and returns the size and the drawn width at that size. */
    private fun fitOwner(text: String, m: (String) -> Float = measure): Triple<Float, String, Float> {
        val fit = GlassFaceLogic.fitFooter(listOf(GlassFaceLogic.Run(text, RunKind.WARNING, Tone.ALERT, 100)), sep, m)
        val drawn = fit.runs.single().text
        return Triple(fit.size, drawn, m(drawn) * fit.size / GlassFaceLogic.FOOTER_SIZE)
    }

    @Test fun longOwnerTextShrinksToTheFloorThenEllipsises() {
        // real German / French status texts at a worst-case 10 px per character (at FOOTER_SIZE)
        for (s in listOf("Telefon getrennt · 23 Std", "Keine Daten · 3 Std", "Téléphone déconnecté · 23 h", "Pas de données · 23 h", "Boucle désactivée")) {
            val (size, drawn, width) = fitOwner(s)
            assertThat(size).isAtLeast(GlassFaceLogic.FOOTER_MIN_SIZE)
            assertThat(width).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH + 0.01f)
            assertThat(drawn).isEqualTo(s) // these fit without cutting
        }
        // "Telefon getrennt · 23 Std" = 250 px: fits at full size
        assertThat(fitOwner("Telefon getrennt · 23 Std").first).isEqualTo(GlassFaceLogic.FOOTER_SIZE)
        // "Téléphone déconnecté · 23 h" = 270 px: shrinks, above the floor
        assertThat(fitOwner("Téléphone déconnecté · 23 h").first).isWithin(0.01f).of(GlassFaceLogic.FOOTER_SIZE * 257f / 270f)
        // a translation far too long: the floor, then "…"
        val long = "Téléphone déconnecté depuis longtemps · 23 h"
        val (size, drawn, width) = fitOwner(long)
        assertThat(size).isEqualTo(GlassFaceLogic.FOOTER_MIN_SIZE)
        assertThat(drawn).endsWith("…")
        assertThat(drawn.length).isLessThan(long.length)
        assertThat(width).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH)
        assertThat(GlassFaceLogic.fitFooter(emptyList(), sep, measure).runs).isEmpty()
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
        // German worst cases at 18 px (8.5 px/char) must fit the 257 px slot without scaling
        for (s in listOf("Telefon getrennt · 59′", "Keine Daten · 3 Std", "Warte auf AAPS", "Reservoir 120U  ·  12,35 U/h"))
            assertThat(roboto(s)).isAtMost(GlassFaceLogic.FOOTER_MAX_WIDTH)
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

    @Test fun sparklineGeometry() {
        assertThat(GlassFaceLogic.nowX()).isWithin(0.5f).of(296.1f)
        assertThat(GlassFaceLogic.sparkX(0f)).isEqualTo(80f)
        assertThat(GlassFaceLogic.sparkX(47f)).isEqualTo(370f)
        // without a forecast the plot ends at now (no empty quarter); with the ball it starts right of it
        assertThat(GlassFaceLogic.nowX(forecast = false)).isEqualTo(370f)
        assertThat(GlassFaceLogic.sparkX(0f, GlassFaceLogic.SPARK_X0_BALL, forecast = false)).isEqualTo(132f)
        assertThat(GlassFaceLogic.SPARK_X0_BALL).isGreaterThan(GlassFaceLogic.BALL_X + GlassFaceLogic.BALL_R + 8f)
        assertThat(GlassFaceLogic.sparkY(60.0)).isEqualTo(330f)
        assertThat(GlassFaceLogic.sparkY(220.0)).isEqualTo(270f)
        assertThat(GlassFaceLogic.sparkY(20.0)).isEqualTo(330f)
        assertThat(GlassFaceLogic.sparkY(400.0)).isEqualTo(270f)
        // the 70-180 target band is about 1.5x the height of round 0 (28 px)
        assertThat(GlassFaceLogic.sparkY(70.0) - GlassFaceLogic.sparkY(180.0)).isAtLeast(41f)
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
        assertThat(GlassFaceLogic.slotOf(bgTs + 12 * slot, bgTs)).isEqualTo(47f)
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
        assertThat(GlassFaceLogic.ballHit(bx + 48f, by)).isTrue()   // 24 dp at xhdpi
        assertThat(GlassFaceLogic.ballHit(bx + 49f, by)).isFalse()
        assertThat(GlassFaceLogic.ballHit(bx - 47f, by)).isTrue()
        assertThat(GlassFaceLogic.ballHit(10f, by)).isFalse()       // off the round screen
        assertThat(GlassFaceLogic.ballHit(200f, 170f)).isFalse()    // hero card, BG digits
        assertThat(GlassFaceLogic.ballHit(250f, 300f)).isFalse()    // graph zone
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
        assertThat(chip.bgX).isEqualTo(186f)
        assertThat(chip.bgY).isEqualTo(162f)
        assertThat(chip.chipY).isEqualTo(150f)
        // the delta line sits under the digits at full card width
        assertThat(chip.metaCentreX).isEqualTo(225f)
        assertThat(chip.metaY).isEqualTo(GlassFaceLogic.META_Y)
        assertThat(chip.bgMaxWidth).isEqualTo(GlassFaceLogic.BG_MAX_WIDTH)
        // empty meta line: digits and chip centre vertically
        val chipNoMeta = GlassFaceLogic.heroLayout(showChip = true, metaEmpty = true)
        assertThat(chipNoMeta.bgY to chipNoMeta.chipY).isEqualTo(182f to 164f)
        // the digits' right edge (half the budget) stays clear of the TIEF/HOCH pill under the chip (left edge ~297)
        assertThat(chip.bgX + GlassFaceLogic.BG_MAX_WIDTH / 2f).isAtMost(290f)
        // trend.hidden_pref: BG (225,162), meta under it, 270 px for the digits
        val noChip = GlassFaceLogic.heroLayout(showChip = false, metaEmpty = false)
        assertThat(listOf(noChip.bgX, noChip.bgY, noChip.metaCentreX, noChip.metaY, noChip.bgMaxWidth))
            .containsExactly(225f, 162f, 225f, GlassFaceLogic.META_Y, GlassFaceLogic.BG_MAX_WIDTH_NO_CHIP).inOrder()
        val noChipNoMeta = GlassFaceLogic.heroLayout(showChip = false, metaEmpty = true)
        assertThat(noChipNoMeta.bgX to noChipNoMeta.bgY).isEqualTo(225f to 182f)
    }

    @Test fun metaLineEmptyWithoutAReading() {
        assertThat(GlassFaceLogic.metaVisible(BgAge.NONE, prefShowAgo = true, prefShowDelta = true)).isEqualTo(false to false)
        assertThat(GlassFaceLogic.metaVisible(BgAge.FRESH, prefShowAgo = true, prefShowDelta = true)).isEqualTo(true to true)
        assertThat(GlassFaceLogic.metaVisible(BgAge.FRESH, prefShowAgo = false, prefShowDelta = true)).isEqualTo(true to false)
        assertThat(GlassFaceLogic.metaVisible(BgAge.AGING, prefShowAgo = false, prefShowDelta = false)).isEqualTo(false to true)
        assertThat(GlassFaceLogic.metaVisible(BgAge.STALE, prefShowAgo = true, prefShowDelta = true)).isEqualTo(false to true)
    }

    @Test fun pillLayout() {
        assertThat(GlassFaceLogic.pills(showIob = true, pumpControl = false)).isEqualTo(Pills.BOTH)
        assertThat(GlassFaceLogic.pills(showIob = false, pumpControl = false)).isEqualTo(Pills.LOOP_ONLY)
        assertThat(GlassFaceLogic.pills(showIob = true, pumpControl = true)).isEqualTo(Pills.IOB_ONLY)
        assertThat(GlassFaceLogic.pills(showIob = false, pumpControl = true)).isEqualTo(Pills.IOB_ONLY)
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

    @Test fun loopDotAndLabel() {
        assertThat(GlassFaceLogic.loopDotFilled(LoopHealth.OK, null)).isTrue()
        assertThat(GlassFaceLogic.loopDotFilled(LoopHealth.WARN, null)).isTrue()
        assertThat(GlassFaceLogic.loopDotFilled(LoopHealth.WARN, Warning.RES_LOW)).isTrue()
        assertThat(GlassFaceLogic.loopDotFilled(LoopHealth.WARN, Warning.PHONE_LOST)).isFalse() // W2 owns the slot
        assertThat(GlassFaceLogic.loopDotFilled(LoopHealth.WARN, Warning.NO_DATA)).isFalse()    // W3 owns the slot
        assertThat(GlassFaceLogic.loopDotFilled(LoopHealth.BAD, null)).isFalse()
        assertThat(GlassFaceLogic.loopDotFilled(LoopHealth.OFF, null)).isFalse()
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
        assertThat(o(Range.URGENT_LOW)).isEqualTo(GlassFaceLogic.AmbientOutline(OutlineStyle.DOUBLE, 1f, 1f, AmbTone.A3, true))
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
}
