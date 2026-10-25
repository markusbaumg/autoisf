package app.aaps.ui.widget

import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TrendArrow
import app.aaps.ui.R
import app.aaps.ui.widget.WidgetLogic.BasalInput
import app.aaps.ui.widget.WidgetLogic.BasalState
import app.aaps.ui.widget.WidgetLogic.BgRange
import app.aaps.ui.widget.WidgetLogic.Block
import app.aaps.ui.widget.WidgetLogic.BolusInput
import app.aaps.ui.widget.WidgetLogic.CalcPill
import app.aaps.ui.widget.WidgetLogic.MeterLevel
import app.aaps.ui.widget.WidgetLogic.Refusal
import app.aaps.ui.widget.WidgetLogic.TirCounts
import app.aaps.ui.widget.WidgetLogic.WizardBlock
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class WidgetLogicTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L

    private val ok = BolusInput(
        aps = true, appInitialized = true, pumpInitialized = true, suspended = false, bolusInQueue = false,
        bgMgdl = 117.0, bgTimestamp = now - min, now = now,
        screenLocked = false, lastBolusAt = now - 30 * min, amount = 0.5, amountAfterConstraints = 0.5
    )

    @Test
    fun `meter colour thresholds`() {
        assertThat(WidgetLogic.meterLevel(null)).isEqualTo(MeterLevel.NONE)
        assertThat(WidgetLogic.meterLevel(0)).isEqualTo(MeterLevel.RED)
        assertThat(WidgetLogic.meterLevel(69)).isEqualTo(MeterLevel.RED)
        assertThat(WidgetLogic.meterLevel(70)).isEqualTo(MeterLevel.ORANGE)
        assertThat(WidgetLogic.meterLevel(89)).isEqualTo(MeterLevel.ORANGE)
        assertThat(WidgetLogic.meterLevel(90)).isEqualTo(MeterLevel.GREEN)
        assertThat(WidgetLogic.meterLevel(100)).isEqualTo(MeterLevel.GREEN)
    }

    @Test
    fun `statistics range constants`() {
        assertThat(WidgetLogic.TIR_LOW_MGDL).isWithin(1e-9).of(70.2)
        assertThat(WidgetLogic.TIR_HIGH_MGDL).isWithin(1e-9).of(180.0)
    }

    @Test
    fun `tir counting follows TirCalculatorImpl`() {
        // 38 error, 39 and 70 below (70 < 70.2), 70.2..180 in range, 180.1 above
        val c = WidgetLogic.countTir(listOf(38.0, 39.0, 70.0, 70.2, 120.0, 180.0, 180.1, 250.0))
        assertThat(c).isEqualTo(TirCounts(below = 2, inRange = 3, above = 2))
        assertThat(c.count).isEqualTo(7)
    }

    @Test
    fun `tir percentage is 100 minus below minus above and rounds like the statistics screen`() {
        val c = TirCounts(below = 1, inRange = 4, above = 1)
        val expected = 100.0 - 100.0 / 6 - 100.0 / 6
        assertThat(c.inRangePct()!!).isWithin(1e-9).of(expected)
        assertThat(c.displayPct()).isEqualTo(String.format(Locale.US, "%.0f", expected).toInt())
        assertThat(TirCounts().inRangePct()).isNull()
        assertThat(TirCounts().displayPct()).isNull()
        assertThat(TirCounts(inRange = 10).displayPct()).isEqualTo(100)
        // 89.5 -> 90 (green), as the statistics screen would show it
        assertThat(TirCounts(below = 5, inRange = 179, above = 16).displayPct()).isEqualTo(90)
    }

    @Test
    fun `pooled days equal one count over all readings`() {
        val day1 = listOf(60.0, 100.0, 120.0, 200.0)
        val day2 = listOf(110.0, 130.0, 190.0)
        val pooled = WidgetLogic.countTir(day1) + WidgetLogic.countTir(day2)
        assertThat(pooled).isEqualTo(WidgetLogic.countTir(day1 + day2))
        assertThat(pooled.displayPct()).isEqualTo(57) // 4 of 7
    }

    @Test
    fun `rolling 24 h window`() {
        assertThat(now - WidgetLogic.rolling24hStart(now)).isEqualTo(24 * 60 * min)
    }

    @Test
    fun `bg range and stale`() {
        assertThat(WidgetLogic.bgRange(null, null, now)).isEqualTo(BgRange.NONE)
        assertThat(WidgetLogic.bgRange(117.0, now - min, now)).isEqualTo(BgRange.IN)
        assertThat(WidgetLogic.bgRange(70.0, now - min, now)).isEqualTo(BgRange.LOW)
        assertThat(WidgetLogic.bgRange(70.2, now - min, now)).isEqualTo(BgRange.IN)
        assertThat(WidgetLogic.bgRange(180.0, now - min, now)).isEqualTo(BgRange.IN)
        assertThat(WidgetLogic.bgRange(181.0, now - min, now)).isEqualTo(BgRange.HIGH)
        assertThat(WidgetLogic.bgRange(117.0, now - 12 * min + 1, now)).isEqualTo(BgRange.IN)
        assertThat(WidgetLogic.bgRange(117.0, now - 12 * min, now)).isEqualTo(BgRange.STALE)
        assertThat(WidgetLogic.bgRange(50.0, now - 20 * min, now)).isEqualTo(BgRange.STALE)
        assertThat(WidgetLogic.bgRange(117.0, now + 61_000, now)).isEqualTo(BgRange.STALE)
    }

    @Test
    fun `bolus allowed in the normal case`() {
        assertThat(WidgetLogic.bolusBlock(ok)).isNull()
        // widget refresh: amounts and DB values unknown
        assertThat(WidgetLogic.bolusBlock(ok.copy(lastBolusAt = null, amount = null, amountAfterConstraints = null))).isNull()
    }

    @Test
    fun `bolus locks`() {
        assertThat(WidgetLogic.bolusBlock(ok.copy(aps = false))).isEqualTo(Block.NOT_SUPPORTED)
        assertThat(WidgetLogic.bolusBlock(ok.copy(appInitialized = false))).isEqualTo(Block.NOT_INITIALIZED)
        assertThat(WidgetLogic.bolusBlock(ok.copy(screenLocked = true))).isEqualTo(Block.SCREEN_LOCKED)
        assertThat(WidgetLogic.bolusBlock(ok.copy(pumpInitialized = false))).isEqualTo(Block.PUMP_NOT_READY)
        assertThat(WidgetLogic.bolusBlock(ok.copy(suspended = true))).isEqualTo(Block.SUSPENDED)
        assertThat(WidgetLogic.bolusBlock(ok.copy(bolusInQueue = true))).isEqualTo(Block.BOLUS_RUNNING)
        assertThat(WidgetLogic.bolusBlock(ok.copy(bgMgdl = null))).isEqualTo(Block.NO_BG)
        assertThat(WidgetLogic.bolusBlock(ok.copy(bgTimestamp = null))).isEqualTo(Block.NO_BG)
        assertThat(WidgetLogic.bolusBlock(ok.copy(bgTimestamp = now - 12 * min))).isEqualTo(Block.STALE)
        assertThat(WidgetLogic.bolusBlock(ok.copy(bgTimestamp = now - 12 * min + 1))).isNull()
        assertThat(WidgetLogic.bolusBlock(ok.copy(bgTimestamp = now + 61_000))).isEqualTo(Block.STALE)
        assertThat(WidgetLogic.bolusBlock(ok.copy(bgMgdl = 70.1))).isEqualTo(Block.LOW)
        assertThat(WidgetLogic.bolusBlock(ok.copy(bgMgdl = 70.2))).isNull()
        assertThat(WidgetLogic.bolusBlock(ok.copy(lastBolusAt = now - 59_999))).isEqualTo(Block.RECENT_BOLUS)
        assertThat(WidgetLogic.bolusBlock(ok.copy(lastBolusAt = now + 30_000))).isEqualTo(Block.RECENT_BOLUS)
        assertThat(WidgetLogic.bolusBlock(ok.copy(lastBolusAt = now - 60_000))).isNull()
        assertThat(WidgetLogic.bolusBlock(ok.copy(amount = Double.NaN))).isEqualTo(Block.INVALID_AMOUNT)
        assertThat(WidgetLogic.bolusBlock(ok.copy(amount = 0.0))).isEqualTo(Block.INVALID_AMOUNT)
        assertThat(WidgetLogic.bolusBlock(ok.copy(amount = 3.5))).isEqualTo(Block.INVALID_AMOUNT)
        assertThat(WidgetLogic.bolusBlock(ok.copy(amountAfterConstraints = 0.0))).isEqualTo(Block.CONSTRAINED_TO_ZERO)
        // a reduced amount is shown in the confirmation (as InsulinDialog does), not blocked
        assertThat(WidgetLogic.bolusBlock(ok.copy(amount = 1.0, amountAfterConstraints = 0.4))).isNull()
    }

    @Test
    fun `stale and low lock even when everything else is fine, stale wins over low`() {
        assertThat(WidgetLogic.bolusBlock(ok.copy(bgMgdl = 50.0, bgTimestamp = now - 30 * min))).isEqualTo(Block.STALE)
    }

    @Test
    fun `accept at confirm time`() {
        val shownAt = 1_000_000L
        assertThat(WidgetLogic.acceptRefusal(shownAt, shownAt + 60_000, 0.5, 0.5, Pair(1L, 2L), Pair(1L, 2L))).isNull()
        assertThat(WidgetLogic.acceptRefusal(shownAt, shownAt + 60_001, 0.5, 0.5, null, null)).isEqualTo(Refusal.EXPIRED)
        assertThat(WidgetLogic.acceptRefusal(shownAt, shownAt - 1, 0.5, 0.5, null, null)).isEqualTo(Refusal.EXPIRED)
        assertThat(WidgetLogic.acceptRefusal(shownAt, shownAt + 1000, 0.5, 0.45, null, null)).isEqualTo(Refusal.CHANGED)
        assertThat(WidgetLogic.acceptRefusal(shownAt, shownAt + 1000, 0.5, 0.4999999, null, null)).isNull()
        assertThat(WidgetLogic.acceptRefusal(shownAt, shownAt + 1000, 0.5, 0.5, Pair(1L, 2L), Pair(3L, 4L))).isEqualTo(Refusal.CHANGED)
        assertThat(WidgetLogic.acceptRefusal(shownAt, shownAt + 1000, 0.5, 0.5, null, Pair(3L, 4L))).isEqualTo(Refusal.CHANGED)
    }

    @Test
    fun `formatting uses the German decimal comma`() {
        assertThat(WidgetLogic.decimal(3.854, 2)).isEqualTo("3,85")
        assertThat(WidgetLogic.decimal(38.44, 1)).isEqualTo("38,4")
        assertThat(WidgetLogic.decimal(25.0, 0)).isEqualTo("25")
        assertThat(WidgetLogic.decimal(-0.001, 2)).isEqualTo("0,00")
        assertThat(WidgetLogic.decimal(-0.12, 2)).isEqualTo("-0,12")
        assertThat(WidgetLogic.glucose(117.0, mmol = true)).isEqualTo("6,5")
        assertThat(WidgetLogic.glucose(117.0, mmol = false)).isEqualTo("117")
        assertThat(WidgetLogic.amountText(0.5)).isEqualTo("0,5")
        assertThat(WidgetLogic.amountText(1.0)).isEqualTo("1,0")
        assertThat(WidgetLogic.amountText(0.05)).isEqualTo("0,05")
        assertThat(WidgetLogic.amountText(0.05 + 9 * 0.05)).isEqualTo("0,5")
    }

    @Test
    fun `age is floored minutes and never negative`() {
        assertThat(WidgetLogic.ageMinutes(now - 59_999, now)).isEqualTo(0)
        assertThat(WidgetLogic.ageMinutes(now - 60_000, now)).isEqualTo(1)
        assertThat(WidgetLogic.ageMinutes(now - 17 * min - 30_000, now)).isEqualTo(17)
        assertThat(WidgetLogic.ageMinutes(now + 30_000, now)).isEqualTo(0)
    }

    @Test
    fun `amount conversions`() {
        assertThat(WidgetLogic.unitsToCentiUnits(0.05 + 9 * 0.05)).isEqualTo(50)
        assertThat(WidgetLogic.centiUnitsToUnits(100)).isWithin(1e-9).of(1.0)
        assertThat(WidgetLogic.clampAmount(0.0)).isWithin(1e-9).of(WidgetLogic.MIN_AMOUNT)
        assertThat(WidgetLogic.clampAmount(10.0)).isWithin(1e-9).of(WidgetLogic.MAX_AMOUNT)
    }

    @Test
    fun `glass tint alpha keeps a floor and reaches full`() {
        // floor 62 %: 59 % at the top of the 95 % -> 100 % drawable
        assertThat(WidgetLogic.glassTintAlpha(0)).isEqualTo(158)
        assertThat(WidgetLogic.glassTintAlpha(255)).isEqualTo(255)
        assertThat(WidgetLogic.glassTintAlpha(-5)).isEqualTo(158)
        assertThat(WidgetLogic.glassTintAlpha(300)).isEqualTo(255)
        // default setting: the R2 density (0.74)
        assertThat(WidgetLogic.glassTintAlpha(25)).isEqualTo(189)
        // bright wallpaper: at least "dense"
        assertThat(WidgetLogic.glassTintAlpha(0, brightWallpaper = true)).isEqualTo(219)
        assertThat(WidgetLogic.glassTintAlpha(25, brightWallpaper = true)).isEqualTo(219)
        assertThat(WidgetLogic.glassTintAlpha(255, brightWallpaper = true)).isEqualTo(255)
    }

    @Test
    fun `glass tint percent shown at the slider`() {
        assertThat(WidgetLogic.glassTintPercent(0)).isEqualTo(62)
        assertThat(WidgetLogic.glassTintPercent(12)).isEqualTo(68)
        assertThat(WidgetLogic.glassTintPercent(25)).isEqualTo(74)
        assertThat(WidgetLogic.glassTintPercent(140)).isEqualTo(87)
        assertThat(WidgetLogic.glassTintPercent(255)).isEqualTo(100)
    }

    @Test
    fun `trend arrow uses the calculated arrow and falls back to the glucose value`() {
        // calculated (overview) arrow wins
        assertThat(WidgetLogic.trendArrow(TrendArrow.FLAT, TrendArrow.FORTY_FIVE_DOWN)).isEqualTo(TrendArrow.FLAT)
        // no bucketed data yet: arrow of the newest glucose value (the 4,6 FORTY_FIVE_DOWN case)
        assertThat(WidgetLogic.trendArrow(null, TrendArrow.FORTY_FIVE_DOWN)).isEqualTo(TrendArrow.FORTY_FIVE_DOWN)
        assertThat(WidgetLogic.trendArrow(TrendArrow.NONE, TrendArrow.FORTY_FIVE_DOWN)).isEqualTo(TrendArrow.FORTY_FIVE_DOWN)
        assertThat(WidgetLogic.trendArrow(TrendArrow.TRIPLE_UP, TrendArrow.DOUBLE_UP)).isEqualTo(TrendArrow.DOUBLE_UP)
        // nothing usable
        assertThat(WidgetLogic.trendArrow(null, null)).isNull()
        assertThat(WidgetLogic.trendArrow(TrendArrow.NONE, TrendArrow.NONE)).isNull()
    }

    @Test
    fun `every real trend has a widget arrow icon`() {
        val shown = TrendArrow.entries.filter { WidgetLogic.arrowIcon(it) != null }
        assertThat(shown).containsExactly(
            TrendArrow.DOUBLE_DOWN, TrendArrow.SINGLE_DOWN, TrendArrow.FORTY_FIVE_DOWN, TrendArrow.FLAT,
            TrendArrow.FORTY_FIVE_UP, TrendArrow.SINGLE_UP, TrendArrow.DOUBLE_UP
        )
        assertThat(WidgetLogic.arrowIcon(TrendArrow.FORTY_FIVE_DOWN)).isEqualTo(app.aaps.ui.R.drawable.widget_arrow_forty_five_down)
        assertThat(shown.map { WidgetLogic.arrowIcon(it) }.toSet()).hasSize(7)
    }

    @Test
    fun `BG size grows with the owner's 406 x 206 widget and fits its middle row`() {
        val size = WidgetLogic.bgTextSizeDp(406f, 206f)
        assertThat(size).isGreaterThan(WidgetLogic.BG_BASE_DP * 1.3f)
        assertThat(size).isAtMost(WidgetLogic.BG_MAX_DP)
        // text box (~1.17 em, no font padding) fits the middle row: 206 - padding 16 - header 17 - bottom row 48
        assertThat(size * 1.17f).isAtMost(206f - 81f)
        // "14,4" (~2 em) + gap + scaled age column fit next to the 142 dp TIR column
        assertThat(size * 2.0f + 8f + 9 * 0.52f * 12f * size / WidgetLogic.BG_BASE_DP).isAtMost(406f - 44f - 142f)
    }

    @Test
    fun `BG size shrinks for smaller widgets and never gets tiny`() {
        val big = WidgetLogic.bgTextSizeDp(406f, 206f)
        val min = WidgetLogic.bgTextSizeDp(360f, 170f)
        assertThat(min).isLessThan(big)
        assertThat(min * 1.17f).isAtMost(170f - 81f)
        assertThat(WidgetLogic.bgTextSizeDp(360f, 110f)).isEqualTo(WidgetLogic.BG_MIN_DP)
        assertThat(WidgetLogic.bgTextSizeDp(800f, 600f)).isEqualTo(WidgetLogic.BG_MAX_DP)
        // height limited, width limited
        assertThat(WidgetLogic.bgTextSizeDp(800f, 150f)).isLessThan(WidgetLogic.bgTextSizeDp(800f, 180f))
        assertThat(WidgetLogic.bgTextSizeDp(380f, 300f)).isLessThan(WidgetLogic.bgTextSizeDp(400f, 300f))
    }

    @Test
    fun `carbs pill only where the bottom row has room, in any language`() {
        assertThat(WidgetLogic.showCarbsPill(406f)).isTrue()
        assertThat(WidgetLogic.showCarbsPill(396f)).isTrue()
        assertThat(WidgetLogic.showCarbsPill(395f)).isFalse()
        assertThat(WidgetLogic.showCarbsPill(360f)).isFalse()
    }

    @Test
    fun `meter labels show the real day count`() {
        val zone = java.time.ZoneId.of("Europe/Zurich")
        fun midnight(d: String) = java.time.LocalDate.parse(d).atStartOfDay(zone).toInstant().toEpochMilli()
        val today = midnight("2026-10-24")
        // first data 15 days ago -> "15 T" on the 30 d meter, "7 T" on the 7 d meter
        val span = WidgetLogic.dataSpanDays(midnight("2026-10-09"), today, zone)
        assertThat(span).isEqualTo(15)
        assertThat(WidgetLogic.meterDays(span, 30)).isEqualTo(15)
        assertThat(WidgetLogic.meterDays(span, 7)).isEqualTo(7)
        // across the DST change (25 Oct 2026): still whole days
        assertThat(WidgetLogic.dataSpanDays(midnight("2026-10-24"), midnight("2026-10-27"), zone)).isEqualTo(3)
        // long history is capped
        assertThat(WidgetLogic.meterDays(WidgetLogic.dataSpanDays(midnight("2026-08-01"), today, zone), 30)).isEqualTo(30)
        // yesterday only
        assertThat(WidgetLogic.meterDays(WidgetLogic.dataSpanDays(midnight("2026-10-23"), today, zone), 7)).isEqualTo(1)
        // no data -> the nominal label
        assertThat(WidgetLogic.dataSpanDays(null, today, zone)).isNull()
        assertThat(WidgetLogic.meterDays(null, 30)).isEqualTo(30)
        assertThat(WidgetLogic.meterDays(null, 7)).isEqualTo(7)
    }

    @Test
    fun `long-term rows need mean, HbA1c and 7 days, both or none`() {
        assertThat(WidgetLogic.showLongTerm(128.4, 6.1, 14)).isTrue()
        assertThat(WidgetLogic.showLongTerm(128.4, 6.1, 7)).isTrue()
        assertThat(WidgetLogic.showLongTerm(128.4, 6.1, 6)).isFalse()
        assertThat(WidgetLogic.showLongTerm(null, 6.1, 14)).isFalse()
        assertThat(WidgetLogic.showLongTerm(128.4, null, 14)).isFalse()
        assertThat(WidgetLogic.longTermFits(206f)).isTrue()
        assertThat(WidgetLogic.longTermFits(170f)).isFalse()
    }

    @Test
    fun `mean glucose and HbA1c texts`() {
        // HbA1c 6,1 <-> mean 128,4 mg/dL: 7,1 mmol/L or 128 mg/dL
        val mean = 6.1 * 28.7 - 46.7
        assertThat(WidgetLogic.glucose(mean, true)).isEqualTo("7,1")
        assertThat(WidgetLogic.glucose(mean, false)).isEqualTo("128")
        // AAPS converts with 1/18 (Constants.MGDL_TO_MMOLL) like the BG itself: mean 134,1 mg/dL -> 7,45 -> "7,5"
        assertThat(WidgetLogic.glucose(6.3 * 28.7 - 46.7, true)).isEqualTo("7,5")
        assertThat(WidgetLogic.decimal(6.1, 1)).isEqualTo("6,1")
    }

    @Test
    fun `wizard conditions follow the overview calculator button`() {
        assertThat(WidgetLogic.wizardBlock(aps = true, appInitialized = true, profileAvailable = true, pumpInitialized = true, pumpSuspendedOrDisconnected = false)).isNull()
        assertThat(WidgetLogic.wizardBlock(aps = false, appInitialized = true, profileAvailable = true, pumpInitialized = true, pumpSuspendedOrDisconnected = false))
            .isEqualTo(WizardBlock.NOT_SUPPORTED)
        assertThat(WidgetLogic.wizardBlock(aps = true, appInitialized = false, profileAvailable = false, pumpInitialized = false, pumpSuspendedOrDisconnected = true))
            .isEqualTo(WizardBlock.NOT_INITIALIZED)
        assertThat(WidgetLogic.wizardBlock(aps = true, appInitialized = true, profileAvailable = false, pumpInitialized = true, pumpSuspendedOrDisconnected = false))
            .isEqualTo(WizardBlock.NO_PROFILE)
        assertThat(WidgetLogic.wizardBlock(aps = true, appInitialized = true, profileAvailable = true, pumpInitialized = false, pumpSuspendedOrDisconnected = false))
            .isEqualTo(WizardBlock.PUMP_NOT_READY)
        assertThat(WidgetLogic.wizardBlock(aps = true, appInitialized = true, profileAvailable = true, pumpInitialized = true, pumpSuspendedOrDisconnected = true))
            .isEqualTo(WizardBlock.PUMP_SUSPENDED)
    }

    @Test
    fun `wizard request is valid once armed and only for a minute`() {
        val t = 5_000_000L
        assertThat(WidgetLogic.wizardRequestValid(null, t)).isFalse()
        assertThat(WidgetLogic.wizardRequestValid(t, t)).isTrue()
        assertThat(WidgetLogic.wizardRequestValid(t, t + WidgetLogic.WIZARD_REQUEST_TTL_MS)).isTrue()
        assertThat(WidgetLogic.wizardRequestValid(t, t + WidgetLogic.WIZARD_REQUEST_TTL_MS + 1)).isFalse()
        assertThat(WidgetLogic.wizardRequestValid(t, t - 1)).isFalse()
    }

    // ---------------------------------------------------------------- header: basal slot

    private val idle = BasalInput(now = now, mode = RM.Mode.CLOSED_LOOP, pumpSuspended = false, suspendMinutesLeft = 0, tempBasal = false, profileBasal = 0.62)

    @Test
    fun `basal slot is empty when the running basal equals the profile`() {
        assertThat(WidgetLogic.basalState(idle)).isEqualTo(BasalState.None)
        // a TBR at 100 % or with the profile rate is no deviation
        assertThat(WidgetLogic.basalState(idle.copy(tempBasal = true, rate = 0.62, pct = 100))).isEqualTo(BasalState.None)
        assertThat(WidgetLogic.basalState(idle.copy(tempBasal = true, rate = 0.62, pct = 99))).isEqualTo(BasalState.None)
        // rate / pct without a running TBR are ignored
        assertThat(WidgetLogic.basalState(idle.copy(rate = 0.0, pct = 0))).isEqualTo(BasalState.None)
    }

    @Test
    fun `zero temp chip counts from the start of the streak`() {
        val s = WidgetLogic.basalState(idle.copy(tempBasal = true, rate = 0.0, pct = 0, zeroSince = now - 25 * min - 30_000))
        assertThat(s).isEqualTo(BasalState.Zero(25))
        assertThat(s.chip).isTrue()
        // 0.04 U/h is shown as zero, 0.05 is not
        assertThat(WidgetLogic.basalState(idle.copy(tempBasal = true, rate = 0.04, pct = 6))).isEqualTo(BasalState.Zero(0))
        assertThat(WidgetLogic.basalState(idle.copy(tempBasal = true, rate = 0.05, pct = 8))).isEqualTo(BasalState.Quiet(0.05, 8, lower = true))
    }

    @Test
    fun `high temp chip from 120 percent, below it quiet`() {
        val high = WidgetLogic.basalState(idle.copy(tempBasal = true, rate = 1.85, pct = 250))
        assertThat(high).isEqualTo(BasalState.High(1.85, 250))
        assertThat(high.chip).isTrue()
        assertThat(WidgetLogic.basalState(idle.copy(tempBasal = true, rate = 0.74, pct = 120))).isEqualTo(BasalState.High(0.74, 120))
        val q = WidgetLogic.basalState(idle.copy(tempBasal = true, rate = 0.73, pct = 119))
        assertThat(q).isEqualTo(BasalState.Quiet(0.73, 119, lower = false))
        assertThat(q.chip).isFalse()
        val low = WidgetLogic.basalState(idle.copy(tempBasal = true, rate = 0.35, pct = 56))
        assertThat(low).isEqualTo(BasalState.Quiet(0.35, 56, lower = true))
        assertThat(low.chip).isFalse()
    }

    @Test
    fun `pump and loop pauses win over every temp`() {
        val zero = idle.copy(tempBasal = true, rate = 0.0, pct = 0)
        assertThat(WidgetLogic.basalState(zero.copy(pumpSuspended = true))).isEqualTo(BasalState.PumpStopped)
        assertThat(WidgetLogic.basalState(zero.copy(mode = RM.Mode.SUSPENDED_BY_PUMP))).isEqualTo(BasalState.PumpStopped)
        assertThat(WidgetLogic.basalState(zero.copy(mode = RM.Mode.DISCONNECTED_PUMP, suspendMinutesLeft = 40))).isEqualTo(BasalState.Disconnected(40))
        assertThat(WidgetLogic.basalState(zero.copy(mode = RM.Mode.SUSPENDED_BY_USER, suspendMinutesLeft = 20))).isEqualTo(BasalState.Paused(20))
        assertThat(WidgetLogic.basalState(zero.copy(mode = RM.Mode.SUSPENDED_BY_DST, suspendMinutesLeft = -3))).isEqualTo(BasalState.Paused(0))
        assertThat(WidgetLogic.basalState(zero.copy(mode = RM.Mode.SUPER_BOLUS, suspendMinutesLeft = 5))).isEqualTo(BasalState.Paused(5))
        assertThat(WidgetLogic.basalState(zero.copy(mode = null))).isEqualTo(BasalState.Zero(0))
    }

    @Test
    fun `smb hint shows for 5 minutes, a basal chip wins`() {
        val smb = idle.copy(smbAmount = 0.35, smbAt = now - 3 * min - 10_000)
        assertThat(WidgetLogic.basalState(smb)).isEqualTo(BasalState.Smb(0.35, 3))
        assertThat(WidgetLogic.basalState(smb).chip).isFalse()
        assertThat(WidgetLogic.basalState(smb.copy(smbAt = now - 5 * min))).isEqualTo(BasalState.Smb(0.35, 5))
        assertThat(WidgetLogic.basalState(smb.copy(smbAt = now - 5 * min - 1))).isEqualTo(BasalState.None)
        assertThat(WidgetLogic.basalState(smb.copy(smbAt = now + 30_000))).isEqualTo(BasalState.Smb(0.35, 0))
        assertThat(WidgetLogic.basalState(smb.copy(smbAt = now + 2 * min))).isEqualTo(BasalState.None)
        assertThat(WidgetLogic.basalState(smb.copy(smbAmount = 0.0))).isEqualTo(BasalState.None)
        // chips win, the SMB hint wins over a quiet temp
        assertThat(WidgetLogic.basalState(smb.copy(tempBasal = true, rate = 1.85, pct = 250))).isEqualTo(BasalState.High(1.85, 250))
        assertThat(WidgetLogic.basalState(smb.copy(tempBasal = true, rate = 0.0, pct = 0))).isEqualTo(BasalState.Zero(0))
        assertThat(WidgetLogic.basalState(smb.copy(tempBasal = true, rate = 0.35, pct = 56))).isEqualTo(BasalState.Smb(0.35, 3))
        assertThat(WidgetLogic.smbRecent(null, now)).isFalse()
    }

    @Test
    fun `tbr percent is rounded and safe without a profile basal`() {
        // 0,60 / 0,50 = 119,99.. % truncated before: must reach the 120 % chip
        assertThat(WidgetLogic.tbrPercent(true, 0.6, 0.5)).isEqualTo(120)
        assertThat(WidgetLogic.tbrPercent(false, 250.0, 0.5)).isEqualTo(250)
        assertThat(WidgetLogic.tbrPercent(true, 0.5, 0.0)).isNull()
        // a zero temp while the profile basal itself is 0 equals the profile: nothing shown
        assertThat(WidgetLogic.basalState(idle.copy(profileBasal = 0.0, tempBasal = true, rate = 0.0, pct = null))).isEqualTo(BasalState.None)
    }

    @Test
    fun `zero streak walks back over contiguous zero TBRs`() {
        // zero TBRs re-issued every 5 min since now - 25 min, a normal basal before
        val starts = (0..5).map { now - 25 * min + it * 5 * min }
        val lookup: (Long) -> Long? = { t -> if (t < now - 25 * min) null else starts.last { it <= t } }
        assertThat(WidgetLogic.zeroStreakStart(now, lookup)).isEqualTo(now - 25 * min)
        assertThat(WidgetLogic.zeroStreakStart(now) { null }).isNull()
        // endless zero: capped at 4 h
        assertThat(WidgetLogic.zeroStreakStart(now) { t -> t - t % (5 * min) }).isEqualTo(now - 4 * 60 * min)
        // cancel + set (DASH): the old zero TBR ends some seconds before the new one starts, still one streak
        val gapLookup: (Long) -> Long? = { t ->
            when {
                t >= now - 10 * min           -> now - 10 * min
                t in (now - 20 * min) until (now - 10 * min - 15_000L) -> now - 20 * min
                else                          -> null
            }
        }
        assertThat(WidgetLogic.zeroStreakStart(now, gapLookup)).isEqualTo(now - 20 * min)
        // a lookup that does not move back ends the walk
        assertThat(WidgetLogic.zeroStreakStart(now) { now - min }).isEqualTo(now - min)
    }

    // ---------------------------------------------------------------- header: temp target, ISF

    @Test
    fun `temp target minutes left round up and end`() {
        assertThat(WidgetLogic.ttMinutesLeft(now - 25 * min, 60 * min, now)).isEqualTo(35)
        assertThat(WidgetLogic.ttMinutesLeft(now - 25 * min - 1, 60 * min, now)).isEqualTo(35)
        assertThat(WidgetLogic.ttMinutesLeft(now - 60 * min + 1000, 60 * min, now)).isEqualTo(1)
        assertThat(WidgetLogic.ttMinutesLeft(now - 60 * min, 60 * min, now)).isNull()
        assertThat(WidgetLogic.ttMinutesLeft(now, 0, now)).isNull()
    }

    @Test
    fun `durations read as hours and minutes, never like a clock time`() {
        assertThat(WidgetLogic.durationShort(0)).isEqualTo("0′")
        assertThat(WidgetLogic.durationShort(35)).isEqualTo("35′")
        assertThat(WidgetLogic.durationShort(59)).isEqualTo("59′")
        assertThat(WidgetLogic.durationShort(60)).isEqualTo("1 h")
        assertThat(WidgetLogic.durationShort(80)).isEqualTo("1 h 20")
        assertThat(WidgetLogic.durationShort(100)).isEqualTo("1 h 40")
        assertThat(WidgetLogic.durationShort(120)).isEqualTo("2 h")
        assertThat(WidgetLogic.durationShort(125)).isEqualTo("2 h 05")
        assertThat(WidgetLogic.durationShort(240)).isEqualTo("4 h")
        assertThat(WidgetLogic.durationShort(240, capped = true)).isEqualTo("> 4 h")
    }

    @Test
    fun `zero streak at the 4 h cap is flagged capped`() {
        val zeroTb = BasalInput(now = now, mode = RM.Mode.CLOSED_LOOP, pumpSuspended = false, suspendMinutesLeft = 0, tempBasal = true, rate = 0.0, pct = 0, profileBasal = 0.8)
        assertThat(WidgetLogic.basalState(zeroTb.copy(zeroSince = now - WidgetLogic.ZERO_STREAK_MAX_MS))).isEqualTo(BasalState.Zero(240, capped = true))
        assertThat(WidgetLogic.basalState(zeroTb.copy(zeroSince = now - WidgetLogic.ZERO_STREAK_MAX_MS + min))).isEqualTo(BasalState.Zero(239, capped = false))
        assertThat(WidgetLogic.basalState(zeroTb.copy(zeroSince = null))).isEqualTo(BasalState.Zero(0, capped = false))
    }

    // ---------------------------------------------------------------- stale / no data

    @Test
    fun `freshness thresholds 12 and 30 min`() {
        assertThat(WidgetLogic.freshness(null, now)).isEqualTo(WidgetLogic.Freshness.NO_DATA)
        assertThat(WidgetLogic.freshness(now - 12 * min + 1, now)).isEqualTo(WidgetLogic.Freshness.FRESH)
        assertThat(WidgetLogic.freshness(now - 12 * min, now)).isEqualTo(WidgetLogic.Freshness.STALE)
        assertThat(WidgetLogic.freshness(now - 30 * min + 1, now)).isEqualTo(WidgetLogic.Freshness.STALE)
        assertThat(WidgetLogic.freshness(now - 30 * min, now)).isEqualTo(WidgetLogic.Freshness.NO_DATA)
        assertThat(WidgetLogic.freshness(now + 61_000, now)).isEqualTo(WidgetLogic.Freshness.STALE)
        assertThat(WidgetLogic.freshness(now + 30_000, now)).isEqualTo(WidgetLogic.Freshness.FRESH)
    }

    @Test
    fun `last range of a stale value keeps low and high`() {
        assertThat(WidgetLogic.lastRange(68.4)).isEqualTo(BgRange.LOW)   // 3,8 mmol/L
        assertThat(WidgetLogic.lastRange(70.2)).isEqualTo(BgRange.IN)
        assertThat(WidgetLogic.lastRange(180.0)).isEqualTo(BgRange.IN)
        assertThat(WidgetLogic.lastRange(227.0)).isEqualTo(BgRange.HIGH) // 12,6 mmol/L
    }

    @Test
    fun `stale lock reason - low wins over old data, no data from 30 min`() {
        assertThat(WidgetLogic.lockReasonShort(Block.STALE, 117.0, now - 23 * min, now)).isEqualTo(R.string.widget_lock_stale)
        assertThat(WidgetLogic.lockReasonShort(Block.STALE, 68.4, now - 23 * min, now)).isEqualTo(R.string.widget_lock_low)
        assertThat(WidgetLogic.lockReasonShort(Block.STALE, 117.0, now - 45 * min, now)).isEqualTo(R.string.widget_lock_no_bg)
        assertThat(WidgetLogic.lockReasonShort(Block.STALE, 68.4, now - 45 * min, now)).isEqualTo(R.string.widget_lock_low)
        assertThat(WidgetLogic.lockReasonShort(Block.STALE, 250.0, now - 45 * min, now)).isEqualTo(R.string.widget_lock_no_bg)
        // the block itself is unchanged: stale data with a low value is still STALE first
        assertThat(WidgetLogic.bolusBlock(ok.copy(bgMgdl = 68.4, bgTimestamp = now - 23 * min))).isEqualTo(Block.STALE)
    }

    @Test
    fun `isf only from a recent loop result`() {
        assertThat(WidgetLogic.currentIsf(74.0, now - 5 * min, now)).isEqualTo(74.0)
        assertThat(WidgetLogic.currentIsf(74.0, now - 15 * min, now)).isEqualTo(74.0)
        assertThat(WidgetLogic.currentIsf(74.0, now - 15 * min - 1, now)).isNull()
        assertThat(WidgetLogic.currentIsf(74.0, now + 2 * min, now)).isNull()
        assertThat(WidgetLogic.currentIsf(null, now, now)).isNull()
        assertThat(WidgetLogic.currentIsf(74.0, null, now)).isNull()
        assertThat(WidgetLogic.currentIsf(0.0, now, now)).isNull()
        assertThat(WidgetLogic.currentIsf(Double.NaN, now, now)).isNull()
    }

    @Test
    fun `autoisf factor and its text`() {
        assertThat(WidgetLogic.autoIsfFactor(85.0, 74.0)).isEqualTo(1.15)
        assertThat(WidgetLogic.autoIsfFactor(74.0, 74.0)).isNull()
        assertThat(WidgetLogic.autoIsfFactor(74.2, 74.0)).isNull()
        assertThat(WidgetLogic.autoIsfFactor(null, 74.0)).isNull()
        assertThat(WidgetLogic.autoIsfFactor(85.0, 0.0)).isNull()
        assertThat(WidgetLogic.factorText(1.15)).isEqualTo("×1,15")
        assertThat(WidgetLogic.factorText(1.4)).isEqualTo("×1,4")
        assertThat(WidgetLogic.factorText(2.0)).isEqualTo("×2")
        assertThat(WidgetLogic.factorText(0.89)).isEqualTo("×0,89")
        assertThat(WidgetLogic.rateText(1.85)).isEqualTo("1,85")
        assertThat(WidgetLogic.rateText(0.04)).isEqualTo("0")
    }

    @Test
    fun `header width guard drops the factor first, then the temp target time`() {
        val all = WidgetLogic.headerFit(362f, "Lyumjev FCL", "5,0", null, "4,1", "×1,15", null, false)
        assertThat(all).isEqualTo(WidgetLogic.HeaderFit(showFactor = true, showTtSuffix = false))
        val busy = WidgetLogic.headerFit(362f, "Lyumjev FCL 80 %", "8,3", "noch 35′", "4,6", "×0,89", "0 U/h seit 25′", true)
        assertThat(busy.showFactor).isFalse()
        val narrow = WidgetLogic.headerFit(200f, "Lyumjev FCL 80 %", "8,3", "noch 35′", "4,6", "×0,89", "Pausiert noch 20′", true)
        assertThat(narrow).isEqualTo(WidgetLogic.HeaderFit(showFactor = false, showTtSuffix = false, showIsf = false))
        // long profile + temp target + pause chip at 406 dp (362 dp inside): the ISF gives way, the chip stays whole
        val paused = WidgetLogic.headerFit(362f, "Lyumjev FCL Sport 80 %", "8,3", "noch 1:40", "4,6", "×0,89", "Pausiert noch 1:40", true)
        assertThat(paused.showIsf).isFalse()
        // a larger font scale drops parts earlier
        assertThat(WidgetLogic.headerFit(362f, "Lyumjev FCL", "5,0", null, "4,1", "×1,15", null, false, fontScale = 1f).showFactor).isTrue()
        assertThat(WidgetLogic.headerFit(362f, "Lyumjev FCL", "5,0", null, "4,1", "×1,15", "0 U/h seit 25′", true, fontScale = 1.3f).showFactor).isFalse()
        // no factor without an ISF
        assertThat(WidgetLogic.headerFit(362f, "A", "5,0", null, null, "×1,15", null, false).showFactor).isFalse()
    }

    // ---------------------------------------------------------------- beta quick wins

    @Test
    fun `fresh bg without trend gets a neutral dash, stale none`() {
        assertThat(WidgetLogic.arrowSlotIcon(BgRange.IN, null)).isEqualTo(R.drawable.widget_arrow_unknown)
        assertThat(WidgetLogic.arrowSlotIcon(BgRange.LOW, TrendArrow.NONE)).isEqualTo(R.drawable.widget_arrow_unknown)
        assertThat(WidgetLogic.arrowSlotIcon(BgRange.HIGH, TrendArrow.FLAT)).isEqualTo(R.drawable.widget_arrow_flat)
        assertThat(WidgetLogic.arrowSlotIcon(BgRange.STALE, TrendArrow.FLAT)).isNull()
        assertThat(WidgetLogic.arrowSlotIcon(BgRange.NONE, null)).isNull()
    }

    @Test
    fun `locked pills name a short reason`() {
        assertThat(WidgetLogic.lockReasonShort(Block.LOW)).isEqualTo(R.string.widget_lock_low)
        assertThat(WidgetLogic.lockReasonShort(Block.STALE, 117.0, now - 15 * min, now)).isEqualTo(R.string.widget_lock_stale)
        assertThat(WidgetLogic.lockReasonShort(Block.NO_BG)).isEqualTo(R.string.widget_lock_no_bg)
        assertThat(WidgetLogic.lockReasonShort(Block.SUSPENDED)).isEqualTo(R.string.widget_lock_paused)
        assertThat(WidgetLogic.lockReasonShort(Block.CONSTRAINED_TO_ZERO)).isNull()
    }

    @Test
    fun `calculator pill is disabled by every quick-bolus lock`() {
        // every lock the widget can render (refresh-level input) disables the pill, before any wizard condition
        Block.entries.forEach { block ->
            assertThat(WidgetLogic.calcPill(block, null)).isEqualTo(CalcPill.BOLUS_LOCKED)
            assertThat(WidgetLogic.calcPill(block, WizardBlock.NO_PROFILE)).isEqualTo(CalcPill.BOLUS_LOCKED)
        }
        assertThat(WidgetLogic.calcPill(null, WizardBlock.NO_PROFILE)).isEqualTo(CalcPill.WIZARD_BLOCKED)
        assertThat(WidgetLogic.calcPill(null, null)).isEqualTo(CalcPill.OPEN)
        // the rendered states: low, stale, no data, paused, pump, bolus running, starting all lock the pill
        val refresh = ok.copy(lastBolusAt = null, amount = null, amountAfterConstraints = null)
        listOf(
            refresh.copy(bgMgdl = 60.0),
            refresh.copy(bgTimestamp = now - 15 * min),
            refresh.copy(bgMgdl = null, bgTimestamp = null),
            refresh.copy(suspended = true),
            refresh.copy(pumpInitialized = false),
            refresh.copy(bolusInQueue = true),
            refresh.copy(appInitialized = false)
        ).forEach { assertThat(WidgetLogic.calcPill(WidgetLogic.bolusBlock(it), null)).isEqualTo(CalcPill.BOLUS_LOCKED) }
        assertThat(WidgetLogic.calcPill(WidgetLogic.bolusBlock(refresh), null)).isEqualTo(CalcPill.OPEN)
    }

    @Test
    fun `running bolus renders again every 10 s for at most 3 min, nothing while unlocked`() {
        assertThat(WidgetLogic.lockFollowUpDelay(null, null, now)).isNull()
        listOf(Block.LOW, Block.STALE, Block.NO_BG, Block.SUSPENDED, Block.PUMP_NOT_READY, Block.NOT_INITIALIZED)
            .forEach { assertThat(WidgetLogic.lockFollowUpDelay(it, now - min, now)).isNull() }
        assertThat(WidgetLogic.lockFollowUpDelay(Block.BOLUS_RUNNING, null, now)).isEqualTo(WidgetLogic.LOCK_FOLLOW_UP_MS)
        assertThat(WidgetLogic.lockFollowUpDelay(Block.BOLUS_RUNNING, now, now)).isEqualTo(10_000L)
        assertThat(WidgetLogic.lockFollowUpDelay(Block.BOLUS_RUNNING, now - 3 * min + 1, now)).isEqualTo(10_000L)
        assertThat(WidgetLogic.lockFollowUpDelay(Block.BOLUS_RUNNING, now - 3 * min, now)).isNull()
    }

    @Test
    fun `just-bolused lock renders again right after its 60 s`() {
        // bolus 20 s ago: the 60 s lock ends in 40 s, render 1 s after that
        assertThat(WidgetLogic.lockFollowUpDelay(Block.RECENT_BOLUS, now, now, lastBolusAt = now - 20_000L)).isEqualTo(41_000L)
        // already over (clock): soon, at least 1 s
        assertThat(WidgetLogic.lockFollowUpDelay(Block.RECENT_BOLUS, now, now, lastBolusAt = now - 90_000L)).isEqualTo(1_000L)
        // bolus timestamp in the future: never more than the lock itself + 1 s
        assertThat(WidgetLogic.lockFollowUpDelay(Block.RECENT_BOLUS, now, now, lastBolusAt = now + 10 * min)).isEqualTo(61_000L)
        // bolus time unknown: like a running bolus
        assertThat(WidgetLogic.lockFollowUpDelay(Block.RECENT_BOLUS, now, now)).isEqualTo(10_000L)
        assertThat(WidgetLogic.lockFollowUpDelay(Block.RECENT_BOLUS, now - 3 * min, now)).isNull()
    }
}
