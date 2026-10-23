package app.aaps.ui.widget

import app.aaps.ui.widget.WidgetLogic.BgRange
import app.aaps.ui.widget.WidgetLogic.Block
import app.aaps.ui.widget.WidgetLogic.BolusInput
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
        assertThat(WidgetLogic.glassTintAlpha(0)).isEqualTo(133)
        assertThat(WidgetLogic.glassTintAlpha(255)).isEqualTo(255)
        assertThat(WidgetLogic.glassTintAlpha(-5)).isEqualTo(133)
        // default setting = recipe "smoke": 44 % at the top of the 78 % -> 100 % drawable
        assertThat(WidgetLogic.glassTintAlpha(25)).isEqualTo(145)
        // bright wallpaper: at least "dense"
        assertThat(WidgetLogic.glassTintAlpha(0, brightWallpaper = true)).isEqualTo(204)
        assertThat(WidgetLogic.glassTintAlpha(25, brightWallpaper = true)).isEqualTo(204)
        assertThat(WidgetLogic.glassTintAlpha(255, brightWallpaper = true)).isEqualTo(255)
    }

    @Test
    fun `BG size grows with the owner's 406 x 206 widget and fits its middle row`() {
        val size = WidgetLogic.bgTextSizeDp(406f, 206f)
        assertThat(size).isGreaterThan(WidgetLogic.BG_BASE_DP * 1.3f)
        assertThat(size).isAtMost(WidgetLogic.BG_MAX_DP)
        // text box (~1.17 em, no font padding) fits the middle row: 206 - padding 24 - header 17 - bottom row 48
        assertThat(size * 1.17f).isAtMost(206f - 89f)
        // "14,4" (~2 em) + gap + scaled age column fit next to the 142 dp TIR column
        assertThat(size * 2.0f + 8f + 9 * 0.52f * 12f * size / WidgetLogic.BG_BASE_DP).isAtMost(406f - 44f - 142f)
    }

    @Test
    fun `BG size shrinks for smaller widgets and never gets tiny`() {
        val big = WidgetLogic.bgTextSizeDp(406f, 206f)
        val min = WidgetLogic.bgTextSizeDp(360f, 170f)
        assertThat(min).isLessThan(big)
        assertThat(min * 1.17f).isAtMost(170f - 89f)
        assertThat(WidgetLogic.bgTextSizeDp(360f, 120f)).isEqualTo(WidgetLogic.BG_MIN_DP)
        assertThat(WidgetLogic.bgTextSizeDp(800f, 600f)).isEqualTo(WidgetLogic.BG_MAX_DP)
        // height limited, width limited
        assertThat(WidgetLogic.bgTextSizeDp(800f, 150f)).isLessThan(WidgetLogic.bgTextSizeDp(800f, 180f))
        assertThat(WidgetLogic.bgTextSizeDp(380f, 300f)).isLessThan(WidgetLogic.bgTextSizeDp(400f, 300f))
    }

    @Test
    fun `carbs pill only where the bottom row has room`() {
        assertThat(WidgetLogic.showCarbsPill(406f, 2)).isTrue()
        assertThat(WidgetLogic.showCarbsPill(396f, 2)).isTrue()
        assertThat(WidgetLogic.showCarbsPill(395f, 2)).isFalse()
        assertThat(WidgetLogic.showCarbsPill(360f, 2)).isFalse()
        // "Carbs" needs more room than "KH"
        assertThat(WidgetLogic.showCarbsPill(406f, 5)).isFalse()
        assertThat(WidgetLogic.showCarbsPill(420f, 5)).isTrue()
    }

    @Test
    fun `wizard follows the overview calculator button, not the BG`() {
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
}
