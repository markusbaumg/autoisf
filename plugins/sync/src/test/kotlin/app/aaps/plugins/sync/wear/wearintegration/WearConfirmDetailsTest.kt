package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmDetails
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmWarning
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WearConfirmDetailsTest {

    private val now = 1_700_000_000_000L
    private fun minAgo(m: Int) = now - m * 60_000L

    /** A calm situation: 6.4 mmol/L (115 mg/dL) flat and fresh, ISF 2.5 mmol/L = 45 mg/dL, IC 13, Omnipod. */
    private fun inputs(
        kind: String = ConfirmDetails.KIND_BOLUS, insulin: Double = 0.5, carbs: Int = 0
    ) = WearConfirmDetails.Inputs(
        kind = kind, insulin = insulin, carbs = carbs, requestedInsulin = insulin, requestedCarbs = carbs, unitsMgdl = false, now = now,
        bgMgdl = 115.0, bgTimestamp = minAgo(2), deltaMgdl = 0.0, arrow = "→", lowMarkMgdl = 70.0,
        isfMgdl = 45.0, ic = 13.0, iobNow = 1.2, cobNow = 12.0, secondsPerUnit = 40.0, insulinPeakMin = 55,
        lastBolusAt = minAgo(42), lastBolusAmount = 1.5, maxBolus = 5.0
    )

    @Test fun `bolus shows its effect and timing and no warnings when calm`() {
        val d = WearConfirmDetails.build(inputs())
        assertThat(d.kind).isEqualTo(ConfirmDetails.KIND_BOLUS)
        assertThat(d.insulin).isEqualTo(0.5)
        assertThat(d.bgEffectMgdl!!).isWithin(1e-9).of(-22.5)
        assertThat(d.carbsCovered!!).isWithin(1e-9).of(6.5)
        assertThat(d.iobNow).isEqualTo(1.2)
        assertThat(d.iobAfter!!).isWithin(1e-9).of(1.7)
        assertThat(d.deliverySeconds).isEqualTo(20)
        assertThat(d.insulinPeakMin).isEqualTo(55)
        assertThat(d.lastBolusMinAgo).isEqualTo(42)
        assertThat(d.lastBolusAmount).isEqualTo(1.5)
        assertThat(d.bgAgeMin).isEqualTo(2)
        assertThat(d.unitsMgdl).isFalse()
        // the profile values the estimate was made with travel along, as its source
        assertThat(d.isfMgdl).isEqualTo(45.0)
        assertThat(d.ic).isEqualTo(13.0)
        assertThat(d.cobAfter).isNull()
        assertThat(d.warnings).isEmpty()
    }

    @Test fun `carbs only show the rise and COB, never an insulin need`() {
        val d = WearConfirmDetails.build(inputs(kind = ConfirmDetails.KIND_CARBS, insulin = 0.0, carbs = 26))
        assertThat(d.bgEffectMgdl!!).isWithin(1e-9).of(90.0)
        assertThat(d.isfMgdl).isEqualTo(45.0)
        assertThat(d.ic).isEqualTo(13.0)
        assertThat(d.cobNow).isEqualTo(12.0)
        assertThat(d.cobAfter).isEqualTo(38.0)
        assertThat(d.carbsCovered).isNull()
        assertThat(d.iobAfter).isNull()
        assertThat(d.deliverySeconds).isNull()
        assertThat(d.insulinPeakMin).isNull()
    }

    @Test fun `carbs never get BG warnings, they may be the treatment of a low`() {
        val d = WearConfirmDetails.build(inputs(kind = ConfirmDetails.KIND_CARBS, insulin = 0.0, carbs = 15).copy(bgMgdl = 60.0, deltaMgdl = -12.0))
        assertThat(d.warnings).isEmpty()
    }

    @Test fun `bolus with carbs shows the covered carbs and both IOB and COB`() {
        val d = WearConfirmDetails.build(inputs(insulin = 1.0, carbs = 20))
        assertThat(d.bgEffectMgdl!!).isWithin(1e-9).of(-45.0)
        assertThat(d.carbsCovered!!).isWithin(1e-9).of(13.0)
        assertThat(d.cobAfter).isEqualTo(32.0)
        assertThat(d.iobAfter!!).isWithin(1e-9).of(2.2)
    }

    @Test fun `low and falling is danger first, falling alone is caution`() {
        val low = WearConfirmDetails.build(inputs().copy(bgMgdl = 65.0, deltaMgdl = -6.0))
        assertThat(low.warnings.map { it.code }).containsExactly(ConfirmWarning.W_BG_LOW, ConfirmWarning.W_BG_FALLING).inOrder()
        assertThat(low.warnings.map { it.level }).containsExactly(ConfirmWarning.LEVEL_DANGER, ConfirmWarning.LEVEL_DANGER)
        assertThat(low.warnings[0].value).isEqualTo(65.0)

        val falling = WearConfirmDetails.build(inputs().copy(deltaMgdl = -5.0))
        assertThat(falling.warnings).containsExactly(ConfirmWarning(ConfirmWarning.W_BG_FALLING, ConfirmWarning.LEVEL_CAUTION, -5.0))

        val fast = WearConfirmDetails.build(inputs().copy(deltaMgdl = -10.0))
        assertThat(fast.warnings.single().level).isEqualTo(ConfirmWarning.LEVEL_DANGER)

        assertThat(WearConfirmDetails.build(inputs().copy(deltaMgdl = -4.9)).warnings).isEmpty()
    }

    @Test fun `stale data warns with its age and drops the falling warning`() {
        val d = WearConfirmDetails.build(inputs().copy(bgTimestamp = minAgo(18), deltaMgdl = -12.0))
        assertThat(d.warnings).containsExactly(ConfirmWarning(ConfirmWarning.W_BG_STALE, ConfirmWarning.LEVEL_CAUTION, 18.0))
        assertThat(WearConfirmDetails.build(inputs().copy(bgTimestamp = minAgo(11))).warnings).isEmpty()
    }

    @Test fun `no BG at all warns`() {
        val d = WearConfirmDetails.build(inputs().copy(bgMgdl = null, bgTimestamp = null, deltaMgdl = null))
        assertThat(d.warnings.map { it.code }).containsExactly(ConfirmWarning.W_NO_BG)
    }

    @Test fun `a manual bolus within 15 minutes warns`() {
        val d = WearConfirmDetails.build(inputs().copy(lastBolusAt = minAgo(6), lastBolusAmount = 1.0))
        assertThat(d.warnings).containsExactly(ConfirmWarning(ConfirmWarning.W_RECENT_BOLUS, ConfirmWarning.LEVEL_CAUTION, 6.0, 1.0))
        assertThat(WearConfirmDetails.build(inputs().copy(lastBolusAt = minAgo(15))).warnings).isEmpty()
    }

    @Test fun `close to the max bolus warns from 80 percent`() {
        assertThat(WearConfirmDetails.build(inputs(insulin = 4.0)).warnings)
            .containsExactly(ConfirmWarning(ConfirmWarning.W_NEAR_MAX_BOLUS, ConfirmWarning.LEVEL_CAUTION, 5.0))
        assertThat(WearConfirmDetails.build(inputs(insulin = 3.95)).warnings).isEmpty()
    }

    @Test fun `constraints applied warn with the requested amounts`() {
        val d = WearConfirmDetails.build(inputs(insulin = 3.0, carbs = 20).copy(requestedInsulin = 6.0, requestedCarbs = 20, maxBolus = 10.0))
        assertThat(d.warnings).containsExactly(ConfirmWarning(ConfirmWarning.W_CONSTRAINT, ConfirmWarning.LEVEL_CAUTION, 6.0, null))
        val carbs = WearConfirmDetails.build(inputs(kind = ConfirmDetails.KIND_CARBS, insulin = 0.0, carbs = 100).copy(requestedCarbs = 150))
        assertThat(carbs.warnings).containsExactly(ConfirmWarning(ConfirmWarning.W_CONSTRAINT, ConfirmWarning.LEVEL_CAUTION, null, 150.0))
    }

    @Test fun `fill has no effect and no BG warnings, only its delivery time`() {
        val d = WearConfirmDetails.build(inputs(kind = ConfirmDetails.KIND_FILL, insulin = 0.7).copy(bgMgdl = 60.0, lastBolusAt = minAgo(3)))
        assertThat(d.bgEffectMgdl).isNull()
        assertThat(d.carbsCovered).isNull()
        assertThat(d.iobAfter).isNull()
        assertThat(d.insulinPeakMin).isNull()
        assertThat(d.lastBolusMinAgo).isNull()
        assertThat(d.deliverySeconds).isEqualTo(28)
        assertThat(d.warnings).isEmpty()
    }

    @Test fun `extended carbs carry start and duration but no immediate COB`() {
        val d = WearConfirmDetails.build(
            inputs(kind = ConfirmDetails.KIND_ECARBS, insulin = 0.0, carbs = 30).copy(carbsStart = now + 30 * 60_000L, carbsDurationH = 3)
        )
        assertThat(d.carbsStart).isEqualTo(now + 30 * 60_000L)
        assertThat(d.carbsDurationH).isEqualTo(3)
        assertThat(d.cobAfter).isNull()
        assertThat(d.bgEffectMgdl!!).isWithin(1e-9).of(30 * 45.0 / 13.0)
    }

    @Test fun `quick wizard carries its carb delay and extended carbs, and delayed carbs are not on board yet`() {
        val d = WearConfirmDetails.build(
            inputs(kind = ConfirmDetails.KIND_WIZARD, insulin = 3.85, carbs = 45).copy(
                carbsDelayMin = 15, carbsAlarm = true, eCarbs = 20, eCarbsDurationH = 2, eCarbsOffsetMin = 30
            )
        )
        assertThat(d.carbsDelayMin).isEqualTo(15)
        assertThat(d.carbsAlarm).isTrue()
        assertThat(d.eCarbs).isEqualTo(20)
        assertThat(d.eCarbsDurationH).isEqualTo(2)
        assertThat(d.eCarbsOffsetMin).isEqualTo(30)
        // the carbs come in 15 min: no "COB now -> after" as if they were on board right away
        assertThat(d.cobAfter).isNull()
        assertThat(d.cobNow).isNull()
        // the effect is the insulin's, as without a delay
        assertThat(d.bgEffectMgdl!!).isWithin(1e-9).of(-3.85 * 45.0)

        // no delay, no extended carbs: nothing of it is sent
        val plain = WearConfirmDetails.build(inputs(kind = ConfirmDetails.KIND_WIZARD, insulin = 3.85, carbs = 45).copy(carbsDelayMin = 0, carbsAlarm = true, eCarbs = 0, eCarbsDurationH = 2))
        assertThat(plain.carbsDelayMin).isNull()
        assertThat(plain.carbsAlarm).isFalse()
        assertThat(plain.eCarbs).isNull()
        assertThat(plain.eCarbsDurationH).isNull()
        assertThat(plain.cobAfter).isEqualTo(57.0)
    }

    @Test fun `missing profile values leave the effect out`() {
        val d = WearConfirmDetails.build(inputs().copy(isfMgdl = null, ic = 0.0, secondsPerUnit = null))
        assertThat(d.bgEffectMgdl).isNull()
        assertThat(d.carbsCovered).isNull()
        assertThat(d.deliverySeconds).isNull()
        assertThat(d.iobAfter).isNotNull()
        assertThat(d.isfMgdl).isNull()
        assertThat(d.ic).isNull()
    }

    @Test fun `an old last bolus is not shown`() {
        val d = WearConfirmDetails.build(inputs().copy(lastBolusAt = minAgo(13 * 60)))
        assertThat(d.lastBolusMinAgo).isNull()
        assertThat(d.lastBolusAmount).isNull()
    }

    @Test fun `delivery speed is known for Omnipod only among the common pumps`() {
        assertThat(wearBolusSecondsPerUnit(PumpType.OMNIPOD_DASH)).isEqualTo(40.0)
        assertThat(wearBolusSecondsPerUnit(PumpType.OMNIPOD_EROS)).isEqualTo(40.0)
        assertThat(wearBolusSecondsPerUnit(PumpType.DANA_RS)).isNull()
        assertThat(wearBolusSecondsPerUnit(null)).isNull()
    }

    @Test fun `details survive both wire formats`() {
        val d = WearConfirmDetails.build(inputs(insulin = 1.0, carbs = 20).copy(bgMgdl = 65.0, deltaMgdl = -6.0))
        val action = EventData.ConfirmAction("t", "m", EventData.ActionBolusConfirmed(1.0, 20, 42L), details = d)
        assertThat(EventData.deserialize(action.serialize())).isEqualTo(action)
        assertThat(EventData.deserializeByte(action.serializeByte())).isEqualTo(action)
    }
}
