package app.aaps.core.interfaces.rx.weardata

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@ExperimentalSerializationApi
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventDataTest {

    @BeforeEach
    fun setUp() {
    }

    @Test
    fun serializationTest() {
        EventData.ActionPong(1, 2).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.WearException(1, byteArrayOf(0xAA.toByte()), "board", "fingerprint", "sdk", "model", "manufacturer", "product").let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.Error(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.CancelBolus(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionResendData("data").let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionPumpStatus(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionLoopStatus(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionTddStatus(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionECarbsPreCheck(1, 2, 3).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionBolusPreCheck(1.0, 2).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionFillPreCheck(1.0).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionFillPresetPreCheck(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionProfileSwitchSendInitialData(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionProfileSwitchPreCheck(1, 2, 3).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionWizardPreCheck(1, 2).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionQuickWizardPreCheck("guid").let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionHeartRate(1, 2, 3.0, "device").let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionTempTargetPreCheck(EventData.ActionTempTargetPreCheck.TempTargetCommand.CANCEL).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionWizardConfirmed(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionTempTargetConfirmed(false).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionBolusConfirmed(1.0, 2).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionECarbsConfirmed(1, 2, 3).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionFillConfirmed(1.0).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionBolusConfirmed(1.0, 2, 1234567890123L).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionFillConfirmed(1.0, 42L).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionProfileSwitchConfirmed(1, 2, 3).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.OpenLoopRequestConfirmed(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.CancelNotification(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        // EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(CwfData())).let {
        //     assertThat( EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
        //     assertThat( EventData.deserialize(it.serialize())).isEqualTo(it)
        // }
        EventData.ActionPing(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.OpenSettings(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.BolusProgress(1, "status").let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.SingleBg(dataset = 0, 1, sgv = 2.0, high = 3.0, low = 4.0).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.GraphData(arrayListOf(EventData.SingleBg(dataset = 0, 1, sgv = 2.0, high = 3.0, low = 4.0))).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.TreatmentData(
            arrayListOf(EventData.TreatmentData.TempBasal(1, 2.0, 3, 4.0, 5.0)),
            arrayListOf(EventData.TreatmentData.Basal(1, 2, 3.0)),
            arrayListOf(EventData.TreatmentData.Treatment(1, 2.0, 3.0, true, isValid = true)),
            arrayListOf(EventData.SingleBg(dataset = 0, 1, sgv = 2.0, high = 3.0, low = 4.0))
        ).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.Preferences(1, wearControl = true, true, 2, 3, 4.0, 5.0, 6.0, 7, 8).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.QuickWizard(arrayListOf(EventData.QuickWizard.QuickWizardEntry("1", "2", 3, 4, 5))).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        // EventData.ActionSetCustomWatchface().let {
        //     assertThat( EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
        //     assertThat( EventData.deserialize(it.serialize())).isEqualTo(it)
        // }
        EventData.ActionrequestCustomWatchface(true).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionrequestSetDefaultWatchface(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ActionProfileSwitchOpenActivity(1, 2, 3).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.OpenLoopRequest("1", "2", null).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.ConfirmAction("1", "2", null).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
        EventData.SnoozeAlert(1).let {
            assertThat(EventData.deserializeByte(it.serializeByte())).isEqualTo(it)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
    }

    @Test
    fun confirmPayloadsWithoutRequestIdStillParse() {
        // payloads from before requestId existed decode with requestId = 0 (which the phone refuses)
        assertThat(EventData.deserialize("""{"type":"app.aaps.core.interfaces.rx.weardata.EventData.ActionBolusConfirmed","insulin":1.5,"carbs":10}"""))
            .isEqualTo(EventData.ActionBolusConfirmed(1.5, 10, 0L))
        assertThat(EventData.deserialize("""{"type":"app.aaps.core.interfaces.rx.weardata.EventData.ActionFillConfirmed","insulin":0.5}"""))
            .isEqualTo(EventData.ActionFillConfirmed(0.5, 0L))
    }

    @Test
    fun requestIdSurvivesTheWatchRoundTrip() {
        // the watch decodes ConfirmAction, re-serializes returnCommand and sends that string back unchanged
        val bolus = EventData.ActionBolusConfirmed(1.5, 10, 987654321L)
        val fill = EventData.ActionFillConfirmed(0.5, 123456789L)
        for (returnCommand in listOf(bolus, fill)) {
            val onWatch = EventData.deserialize(EventData.ConfirmAction("t", "m", returnCommand).serialize()) as EventData.ConfirmAction
            assertThat(EventData.deserialize(onWatch.returnCommand!!.serialize())).isEqualTo(returnCommand)
            val onWatchByte = EventData.deserializeByte(EventData.ConfirmAction("t", "m", returnCommand).serializeByte()) as EventData.ConfirmAction
            assertThat(EventData.deserialize(onWatchByte.returnCommand!!.serialize())).isEqualTo(returnCommand)
        }
    }

    private fun status(lastLoopRunAt: Long) = EventData.Status(
        dataset = 0, externalStatus = "ok", iobSum = "1.00", iobDetail = "(1|0)", cob = "10g", currentBasal = "0.5U/h", battery = "80",
        rigBattery = "", openApsStatus = -1L, bgi = "-1", batteryLevel = 1, patientName = "", tempTarget = "--", tempTargetLevel = 0,
        reservoirString = "100U", reservoir = 100.0, reservoirLevel = 0, lastLoopRunAt = lastLoopRunAt
    )

    @Test
    fun statusLastLoopRunAtRoundTrips() {
        status(1_700_000_000_000L).let {
            assertThat((EventData.deserialize(it.serialize()) as EventData.Status).lastLoopRunAt).isEqualTo(1_700_000_000_000L)
            assertThat((EventData.deserializeByte(it.serializeByte()) as EventData.Status).lastLoopRunAt).isEqualTo(1_700_000_000_000L)
            assertThat(EventData.deserialize(it.serialize())).isEqualTo(it)
        }
    }

    @Test
    fun statusFromOlderPhoneWithoutLastLoopRunAtDecodesToZero() {
        // the default (0) is not encoded, so this is exactly what an older phone app sends
        val json = status(0L).serialize()
        assertThat(json).doesNotContain("lastLoopRunAt")
        val decoded = EventData.deserialize(json) as EventData.Status
        assertThat(decoded.lastLoopRunAt).isEqualTo(0L)
        assertThat(decoded.openApsStatus).isEqualTo(-1L)
        assertThat((EventData.deserializeByte(status(0L).serializeByte()) as EventData.Status).lastLoopRunAt).isEqualTo(0L)
    }
}
