package app.aaps.database.persistence

import android.content.Context
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDeviceStatusChange
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventFoodDatabaseChanged
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.rx.events.EventProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.events.EventTherapyEventChange
import app.aaps.core.interfaces.rx.events.EventTreatmentChange
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.database.AppRepository
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.BolusCalculatorResult
import app.aaps.database.entities.Carbs
import app.aaps.database.entities.DeviceStatus
import app.aaps.database.entities.EffectiveProfileSwitch
import app.aaps.database.entities.ExtendedBolus
import app.aaps.database.entities.Food
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.ProfileSwitch
import app.aaps.database.entities.RunningMode
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.entities.TherapyEvent
import app.aaps.database.persistence.converters.fromDb
import dagger.Lazy
import io.reactivex.rxjava3.disposables.Disposable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompatDBHelper @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val repository: AppRepository,
    private val rxBus: RxBus,
    private val context: Context,
    private val uiInteraction: UiInteraction,
    private val iobCobCalculator: Lazy<IobCobCalculator>
) {

    /**
     * True only when the calculation already holds [gv] with identical data.
     * Any failure answers false (= recalculate), so an error here can never suppress a recalculation
     * nor break this subscription.
     */
    private fun holdsSameData(gv: GlucoseValue): Boolean =
        try {
            iobCobCalculator.get().ads.holdsSameData(gv.fromDb())
        } catch (e: Exception) {
            aapsLogger.error(LTag.DATABASE, "holdsSameData failed", e)
            false
        }

    fun dbChangeDisposable(): Disposable = repository
        .changeObservable()
        .doOnSubscribe {
            rxBus.send(EventNewBG(null))
            uiInteraction.updateWidget(context, "OnStart")
        }
        .subscribe {
            /**
             * GlucoseValues can come in batch
             * oldest one should be used for invalidation, newest one for for triggering Loop.
             * Thus we need to collect both
             *
             */
            var newestGlucoseValue: GlucoseValue? = null
            it.filterIsInstance<GlucoseValue>().maxByOrNull { gv -> gv.timestamp }?.let { gv ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventNewBG $gv")
                rxBus.send(EventNewBG(gv.timestamp))
                newestGlucoseValue = gv
            }
            // Do not recalculate for a glucose change that carries no new data (e.g. Nightscout id write-back).
            // Filter row by row, and before the reduction below. One change list can carry a new
            // reading together with an id only update of an older row (CgmSourceTransaction does
            // exactly that), and reducing first would hide the new reading behind the unchanged one.
            val glucoseValues = it.filterIsInstance<GlucoseValue>()
            val changedGlucoseValues = glucoseValues.filterNot { gv -> holdsSameData(gv) }
            if (changedGlucoseValues.size < glucoseValues.size)
                aapsLogger.debug(LTag.DATABASE) { "Ignoring ${glucoseValues.size - changedGlucoseValues.size} of ${glucoseValues.size} glucose changes, nothing the calculation reads is different" }
            changedGlucoseValues.minOfOrNull { gv -> gv.timestamp }?.let { timestamp ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventNewHistoryData $timestamp $newestGlucoseValue")
                rxBus.send(EventNewHistoryData(timestamp, true, newestGlucoseValue?.timestamp))
            }
            it.filterIsInstance<Carbs>().minOfOrNull { t -> t.timestamp }?.let { timestamp ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventTreatmentChange $timestamp")
                rxBus.send(EventTreatmentChange())
                rxBus.send(EventNewHistoryData(timestamp, false))
            }
            it.filterIsInstance<Bolus>().minOfOrNull { t -> t.timestamp }?.let { timestamp ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventTreatmentChange $timestamp")
                rxBus.send(EventTreatmentChange())
                rxBus.send(EventNewHistoryData(timestamp, false))
            }
            it.filterIsInstance<BolusCalculatorResult>().minOfOrNull { t -> t.timestamp }?.let { timestamp ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventTreatmentChange $timestamp")
                rxBus.send(EventTreatmentChange())
                rxBus.send(EventNewHistoryData(timestamp, false))
            }
            it.filterIsInstance<TemporaryBasal>().minOfOrNull { t -> t.timestamp }?.let { timestamp ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventTempBasalChange $timestamp")
                rxBus.send(EventTempBasalChange())
                rxBus.send(EventNewHistoryData(timestamp, false))
            }
            it.filterIsInstance<ExtendedBolus>().minOfOrNull { t -> t.timestamp }?.let { timestamp ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventExtendedBolusChange $timestamp")
                rxBus.send(EventExtendedBolusChange())
                rxBus.send(EventNewHistoryData(timestamp, false))
            }
            // The oldest one, as for the other types: invalidation must start at the earliest changed switch.
            // With the first of a batch a newer one listed first left the calculation (kept across BGs)
            // and the profile cache for the older one's time stale.
            it.filterIsInstance<EffectiveProfileSwitch>().minOfOrNull { eps -> eps.timestamp }?.let { timestamp ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventEffectiveProfileSwitchChanged $timestamp")
                rxBus.send(EventEffectiveProfileSwitchChanged(timestamp))
                rxBus.send(EventNewHistoryData(timestamp, false))
            }
            it.filterIsInstance<TemporaryTarget>().firstOrNull()?.let { tt ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventTempTargetChange $tt")
                rxBus.send(EventTempTargetChange())
            }
            it.filterIsInstance<TherapyEvent>().firstOrNull()?.let { te ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventTherapyEventChange $te")
                rxBus.send(EventTherapyEventChange())
            }
            it.filterIsInstance<Food>().firstOrNull()?.let { food ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventFoodDatabaseChanged $food")
                rxBus.send(EventFoodDatabaseChanged())
            }
            it.filterIsInstance<ProfileSwitch>().firstOrNull()?.let { ps ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventProfileSwitchChanged $ps")
                rxBus.send(EventProfileSwitchChanged())
            }
            it.filterIsInstance<RunningMode>().firstOrNull()?.let { rm ->
                aapsLogger.debug(LTag.DATABASE, "Firing RunningModeChange $rm")
                rxBus.send(EventRunningModeChange())
            }
            it.filterIsInstance<DeviceStatus>().firstOrNull()?.let { ds ->
                aapsLogger.debug(LTag.DATABASE, "Firing EventDeviceStatusChange $ds")
                rxBus.send(EventDeviceStatusChange())
            }
        }
}