package app.aaps.core.interfaces.rx.weardata

import app.aaps.core.interfaces.rx.events.Event
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.Date
import java.util.Objects

@Serializable
sealed class EventData : Event() {

    var sourceNodeId = ""

    fun serialize() = Json.encodeToString(serializer(), this)

    @ExperimentalSerializationApi
    fun serializeByte() = ProtoBuf.encodeToByteArray(serializer(), this)

    companion object {

        fun deserialize(json: String) = try {
            Json.decodeFromString(serializer(), json)
        } catch (_: Exception) {
            Error(System.currentTimeMillis())
        }

        @ExperimentalSerializationApi
        fun deserializeByte(byteArray: ByteArray) = try {
            ProtoBuf.decodeFromByteArray(serializer(), byteArray)
        } catch (_: Exception) {
            Error(System.currentTimeMillis())
        }
    }

    // Mobile <- Wear
    @Serializable
    data class ActionPong(val timeStamp: Long, val apiLevel: Int) : EventData()

    @Serializable
    data class WearException(
        val timeStamp: Long,
        val exception: ByteArray,
        val board: String,
        val fingerprint: String,
        val sdk: String,
        val model: String,
        val manufacturer: String,
        val product: String
    ) : EventData() {

        override fun equals(other: Any?): Boolean =
            when (other) {
                !is WearException -> false
                else              -> timeStamp == other.timeStamp && fingerprint == other.fingerprint
            }

        override fun hashCode(): Int {
            return Objects.hash(timeStamp, fingerprint)
        }
    }

    @Serializable
    data class Error(val timeStamp: Long) : EventData() // ignored

    @Serializable
    data class CancelBolus(val timeStamp: Long) : EventData()

    @Serializable
    data class ActionResendData(val from: String) : EventData()

    @Serializable
    data class ActionPumpStatus(val timeStamp: Long) : EventData()

    @Serializable
    data class ActionLoopStatus(val timeStamp: Long) : EventData()

    @Serializable
    data class ActionLoopStatusDetailed(
        val timeStamp: Long
    ) : EventData()

    @Serializable
    data class LoopStatusResponse(
        val timeStamp: Long,
        val data: LoopStatusData
    ) : EventData()

    @Serializable
    data class ActionTddStatus(val timeStamp: Long) : EventData()

    @Serializable
    data class ActionECarbsPreCheck(val carbs: Int, val carbsTimeShift: Int, val duration: Int) : EventData()

    @Serializable
    data class ActionBolusPreCheck(val insulin: Double, val carbs: Int) : EventData()

    @Serializable
    data class ActionFillPreCheck(val insulin: Double) : EventData()

    @Serializable
    data class ActionFillPresetPreCheck(val button: Int) : EventData()

    @Serializable
    data class ActionProfileSwitchSendInitialData(val timeStamp: Long) : EventData()

    @Serializable
    data class ActionProfileSwitchPreCheck(val timeShift: Int, val percentage: Int, val duration: Int) : EventData()

    @Serializable
    data class ActionWizardPreCheck(val carbs: Int, val percentage: Int) : EventData()

    @Serializable
    data class ActionQuickWizardPreCheck(val guid: String) : EventData()

    @Serializable
    data class ActionWizardResult(
        val timestamp: Long,
        val totalInsulin: Double,
        val carbs: Int,
        val ic: Double,
        val sens: Double,
        val insulinFromCarbs: Double,
        val insulinFromBG: Double?,
        val insulinFromCOB: Double?,
        val insulinFromBolusIOB: Double?,
        val insulinFromBasalIOB: Double?,
        val insulinFromTrend: Double?,
        val insulinFromSuperBolus: Double?,
        val tempTarget: String?,
        val percentageCorrection: Int?,
        val totalBeforePercentage: Double?,
        val cob: Double
    ) : EventData()

    @Serializable
    data class ActionUserActionPreCheck(val id: Int, val title: String) : EventData()

    @Serializable
    data class ActionUserActionConfirmed(val id: Int, val title: String) : EventData()

    @Serializable
    data class LoopStatesRequest(val timeStamp: Long) : EventData()

    @Serializable
    data class LoopStateSelected(val timeStamp: Long, val index: Int, val duration: Int? = null) : EventData()

    @Serializable
    data class LoopStateConfirmed(val timeStamp: Long, val index: Int, val duration: Int? = null) : EventData()

    @Serializable
    data class ActionHeartRate(
        val duration: Long,
        val timestamp: Long,
        val beatsPerMinute: Double,
        val device: String
    ) : EventData() {

        override fun toString() =
            "HR ${beatsPerMinute.toInt()} at ${Date(timestamp)} for ${duration / 1000.0}sec $device"
    }

    @Serializable
    data class ActionStepsRate(
        val duration: Long,
        val timestamp: Long,
        val steps5min: Int,
        val steps10min: Int,
        val steps15min: Int,
        val steps30min: Int,
        val steps60min: Int,
        val steps180min: Int,
        val device: String
    ) : EventData() {

        override fun toString() =
            "STEPS 5min: $steps5min, 10min: $steps10min, 15min: $steps15min, 30min: $steps30min, 60min: $steps60min, 180min: $steps180min at ${Date(timestamp)} for ${duration / 1000.0}sec $device"
    }

    @Serializable
    data class ActionTempTargetPreCheck(
        val command: TempTargetCommand,
        val isMgdl: Boolean = true, val duration: Int = 0, val low: Double = 0.0, val high: Double = 0.0 // manual
    ) : EventData() {

        @Serializable
        enum class TempTargetCommand {

            PRESET_ACTIVITY, PRESET_HYPO, PRESET_EATING, CANCEL, MANUAL
        }

    }

    // Mobile <- Wear return

    @Serializable
    data class ActionWizardConfirmed(val timeStamp: Long) : EventData()

    @Serializable
    data class ActionTempTargetConfirmed(val isMgdl: Boolean = true, val duration: Int = 0, val low: Double = 0.0, val high: Double = 0.0) : EventData()

    /** [requestId] echoes the id of the phone's precheck (0 = none, from an old phone: refused). */
    @Serializable
    data class ActionBolusConfirmed(val insulin: Double, val carbs: Int, val requestId: Long = 0L) : EventData()

    @Serializable
    data class ActionECarbsConfirmed(val carbs: Int, val carbsTime: Long, val duration: Int) : EventData()

    /** [requestId] echoes the id of the phone's precheck (0 = none, from an old phone: refused). */
    @Serializable
    data class ActionFillConfirmed(val insulin: Double, val requestId: Long = 0L) : EventData()

    @Serializable
    data class ActionProfileSwitchConfirmed(val timeShift: Int, val percentage: Int, val duration: Int) : EventData()

    @Serializable
    data class OpenLoopRequestConfirmed(val timeStamp: Long) : EventData()

    @Serializable
    data class LoopStatesList(val timeStamp: Long, val states: List<AvailableLoopState>) : EventData() {
        @Serializable
        data class AvailableLoopState(
            val state: LoopState,
            val durations: List<Int>? = null,
            val title: String? = null, // used for FAKE_DIVIDER
        ) {
            @Serializable
            enum class LoopState {
                // See LoopDialog
                LOOP_OPEN,
                LOOP_LGS,
                LOOP_CLOSED,

                LOOP_DISABLE,

                LOOP_USER_SUSPEND, // 1h, 2h, 3h, 10h
                LOOP_PUMP_SUSPEND,
                LOOP_RESUME,

                PUMP_DISCONNECT, // 15m, 30m, 1h, 2h, 3h

                // Returned current statuses
                LOOP_UNKNOWN,
                SUPERBOLUS,
            }
        }
    }

    // Mobile -> Wear
    @Serializable
    data class CancelNotification(val timeStamp: Long) : EventData()

    @Serializable
    data class ActionGetCustomWatchface(
        val customWatchface: ActionSetCustomWatchface,
        val exportFile: Boolean = false,
        val withDate: Boolean = true
    ) : EventData()

    @Serializable
    data class ActionPing(val timeStamp: Long) : EventData()

    @Serializable
    data class OpenSettings(val timeStamp: Long) : EventData()

    @Serializable
    data class BolusProgress(val percent: Int, val status: String) : EventData()

    interface EventDataSet {

        var dataset: Int
    }

    @Serializable
    data class SingleBg(
        override var dataset: Int,
        var timeStamp: Long,
        val sgvString: String = "---",
        val glucoseUnits: String = "-",
        val slopeArrow: String = "--",
        val delta: String = "--",
        val deltaDetailed: String = "--",
        val avgDelta: String = "--",
        val avgDeltaDetailed: String = "--",
        val sgvLevel: Long = 0,
        val sgv: Double,
        val high: Double, // highLine
        val low: Double, // lowLine
        val color: Int = 0,
        val deltaMgdl: Double? = null,
        val avgDeltaMgdl: Double? = null,
        val id: Int = 0
    ) : EventDataSet, EventData(), Comparable<SingleBg> {

        override fun equals(other: Any?): Boolean =
            when {
                other !is SingleBg   -> false
                color != other.color -> false
                else                 -> timeStamp == other.timeStamp
            }

        override fun hashCode(): Int {
            return Objects.hash(timeStamp, color)
        }

        override fun compareTo(other: SingleBg): Int {
            // reverse order endTime get latest first
            if (this.timeStamp < other.timeStamp) return 1
            return if (this.timeStamp > other.timeStamp) -1 else 0
        }
    }

    @Serializable
    data class GraphData(
        val entries: ArrayList<SingleBg>
    ) : EventData()

    @Serializable
    data class TreatmentData(
        val temps: ArrayList<TempBasal>,
        val basals: ArrayList<Basal>,
        val boluses: ArrayList<Treatment>,
        val predictions: ArrayList<SingleBg>
    ) : EventData() {

        @Serializable
        data class TempBasal(
            val startTime: Long,
            val startBasal: Double,
            val endTime: Long,
            val endBasal: Double,
            val amount: Double
        )

        @Serializable
        data class Basal(
            val startTime: Long,
            val endTime: Long,
            val amount: Double
        )

        @Serializable
        data class Treatment(
            val date: Long,
            val bolus: Double,
            val carbs: Double,
            val isSMB: Boolean,
            val isValid: Boolean,
        )
    }

    @Serializable
    data class Status(
        override var dataset: Int,
        val externalStatus: String,
        val iobSum: String,
        val iobDetail: String,
        val cob: String,
        val currentBasal: String,
        val battery: String,
        val rigBattery: String,
        val openApsStatus: Long,
        val bgi: String,
        val batteryLevel: Int,
        val patientName: String = "",
        val tempTarget: String,
        val tempTargetLevel: Int,
        val reservoirString: String,
        val reservoir: Double,
        val reservoirLevel: Int,
        /**
         * Time of the last loop run (APS decision, Loop.lastRun.lastAPSRun; on AAPSClient the last suggested time),
         * 0 = unknown (no run since app start, or an older phone app). Unlike [openApsStatus] (last temp basal enact,
         * -1 on runs that enact nothing) it moves on every loop run. Kept last with a default for older data.
         */
        val lastLoopRunAt: Long = 0
    ) : EventData(), EventDataSet

    @Serializable
    data class Preferences(
        val timeStamp: Long,
        val wearControl: Boolean,
        val unitsMgdl: Boolean,
        val bolusPercentage: Int,
        val maxCarbs: Int,
        val maxBolus: Double,
        val insulinButtonIncrement1: Double,
        val insulinButtonIncrement2: Double,
        val carbsButtonIncrement1: Int,
        val carbsButtonIncrement2: Int
    ) : EventData()

    @Serializable
    data class QuickWizard(
        val entries: ArrayList<QuickWizardEntry>
    ) : EventData() {

        @Serializable
        data class QuickWizardEntry(
            val guid: String,
            val buttonText: String,
            val carbs: Int,
            val validFrom: Int,
            val validTo: Int
        ) : EventData()
    }

    @Serializable
    data class UserAction(
        val entries: ArrayList<UserActionEntry>
    ) : EventData() {

        @Serializable
        data class UserActionEntry(
            val timeStamp: Long,
            val id: Int,
            val title: String
        ) : EventData()
    }

    @Serializable
    data class ActionSetCustomWatchface(val customWatchfaceData: CwfData) : EventData()

    @Serializable
    data class ActionUpdateCustomWatchface(val customWatchfaceData: CwfData) : EventData()

    @Serializable
    data class ActionrequestCustomWatchface(val exportFile: Boolean) : EventData()

    @Serializable
    data class ActionrequestSetDefaultWatchface(val timeStamp: Long) : EventData()

    @Serializable
    data class ActionProfileSwitchOpenActivity(val timeShift: Int, val percentage: Int, val duration: Int) : EventData()

    @Serializable
    data class OpenLoopRequest(val title: String, val message: String, val returnCommand: EventData?) : EventData()

    /**
     * returnCommand is sent back to Mobile after confirmation. [details] (optional, newer phones) only feeds the watch's
     * confirm screen: what the treatment is, the estimated effect and timing and warnings. It is display-only: the watch
     * never sends it back and the phone never reads it, the treatment is defined by [returnCommand] alone.
     * A watch without this field cannot decode a ConfirmAction that carries it (strict JSON): phone and watch apps that
     * send / show [details] are installed as a matched pair.
     */
    @Serializable
    data class ConfirmAction(val title: String, val message: String, val returnCommand: EventData?, val details: ConfirmDetails? = null) : EventData()

    /**
     * Display-only content of a treatment confirm screen on the watch (see [ConfirmAction.details]). Glucose values are mg/dL
     * ([unitsMgdl] says how to show them); effect and timing values are estimates the watch labels with "≈". Null = unknown,
     * not shown.
     *
     * @param kind one of the KIND_ constants
     * @param insulin insulin of the treatment as offered (U); [carbs] carbs as offered (g)
     * @param label extra name, e.g. the quick wizard button text
     * @param bgEffectMgdl ≈ BG change of this treatment: -insulin × ISF (+ carbs × ISF / IC when there is no insulin)
     * @param carbsCovered ≈ carbs this insulin covers (insulin × IC)
     * @param isfMgdl / [ic] the profile ISF (mg/dL per U) and IC (g per U) the estimates were made with, shown as their source
     * @param iobNow / [iobAfter] IOB now and right after this bolus (U); [cobNow] / [cobAfter] the same for COB (g)
     * @param deliverySeconds ≈ time the pump needs to deliver [insulin]; [insulinPeakMin] peak time of the active insulin
     * @param lastBolusMinAgo / [lastBolusAmount] the last manual (normal) bolus
     * @param carbsStart / [carbsDurationH] start time and duration of extended carbs
     * @param note extra lines, e.g. the wizard's calculation
     */
    @Serializable
    data class ConfirmDetails(
        val kind: String = KIND_OTHER,
        val insulin: Double = 0.0,
        val carbs: Int = 0,
        val label: String = "",
        val unitsMgdl: Boolean = true,
        val bgMgdl: Double? = null,
        val bgAgeMin: Int? = null,
        val deltaMgdl: Double? = null,
        val arrow: String = "",
        val bgEffectMgdl: Double? = null,
        val carbsCovered: Double? = null,
        val isfMgdl: Double? = null,
        val ic: Double? = null,
        val iobNow: Double? = null,
        val iobAfter: Double? = null,
        val cobNow: Double? = null,
        val cobAfter: Double? = null,
        val deliverySeconds: Int? = null,
        val insulinPeakMin: Int? = null,
        val lastBolusMinAgo: Int? = null,
        val lastBolusAmount: Double? = null,
        val carbsStart: Long? = null,
        val carbsDurationH: Int? = null,
        val note: String = "",
        val warnings: List<ConfirmWarning> = emptyList()
    ) {

        companion object {

            const val KIND_BOLUS = "bolus"
            const val KIND_CARBS = "carbs"
            const val KIND_WIZARD = "wizard"
            const val KIND_ECARBS = "ecarbs"
            const val KIND_FILL = "fill"
            const val KIND_OTHER = "other"
        }
    }

    /**
     * A warning on the confirm screen (display only, never blocks). [code] is one of the W_ constants (an unknown code is
     * shown generically), [level] 1 = caution (amber), 2 = danger (red); [value] / [value2] its numbers (see the codes).
     */
    @Serializable
    data class ConfirmWarning(val code: String, val level: Int = LEVEL_CAUTION, val value: Double? = null, val value2: Double? = null) {

        companion object {

            const val LEVEL_CAUTION = 1
            const val LEVEL_DANGER = 2

            /** BG below the low mark. value = BG mg/dL */
            const val W_BG_LOW = "bg_low"
            /** BG falling. value = delta mg/dL per 5 min */
            const val W_BG_FALLING = "bg_falling"
            /** Last BG reading old. value = age in minutes */
            const val W_BG_STALE = "bg_stale"
            /** No BG reading at all. */
            const val W_NO_BG = "no_bg"
            /** A manual bolus shortly before. value = minutes ago, value2 = its amount U */
            const val W_RECENT_BOLUS = "recent_bolus"
            /** Close to the max bolus. value = max bolus U */
            const val W_NEAR_MAX_BOLUS = "near_max_bolus"
            /** Constraints changed the request. value = requested insulin U (or null), value2 = requested carbs g (or null) */
            const val W_CONSTRAINT = "constraint"
        }
    }

    @Serializable
    data class SnoozeAlert(val timeStamp: Long) : EventData()

    // Wear -> Wear (workaround)
    @Serializable
    data class LoopStatePreSelect(
        val timeStamp: Long,
        val stateIndex: Int,
        val durations: List<Int>
    ) : EventData()
}
