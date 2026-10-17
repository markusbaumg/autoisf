package app.aaps.plugins.sync.nsclientV3

import androidx.annotation.OpenForTesting
import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.source.NSClientSource
import app.aaps.core.interfaces.sync.DataSyncSelector
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiQueue
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiStatus
import app.aaps.plugins.sync.nsShared.extensions.onlyNsIdAdded
import app.aaps.plugins.sync.nsclientV3.keys.NsclientBooleanKey
import app.aaps.plugins.sync.nsclientV3.keys.NsclientLongKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataSyncSelectorV3 @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val persistenceLayer: PersistenceLayer,
    private val rxBus: RxBus,
    private val storeDataForDb: StoreDataForDb,
    private val config: Config
) : DataSyncSelector {

    data class QueueCounter(
        var bolusesRemaining: Long = -1L,
        var carbsRemaining: Long = -1L,
        var bcrRemaining: Long = -1L,
        var ttsRemaining: Long = -1L,
// NSCv3 doesn't support food update
//        var foodsRemaining: Long = -1L,
        var gvsRemaining: Long = -1L,
        var tesRemaining: Long = -1L,
        var dssRemaining: Long = -1L,
        var tbrsRemaining: Long = -1L,
        var ebsRemaining: Long = -1L,
        var pssRemaining: Long = -1L,
        var epssRemaining: Long = -1L,
        var rmsRemaining: Long = -1L
    ) {

        fun size(): Long =
            bolusesRemaining +
                carbsRemaining +
                bcrRemaining +
                ttsRemaining +
// NSCv3 doesn't support food update
//                foodsRemaining +
                gvsRemaining +
                tesRemaining +
                dssRemaining +
                tbrsRemaining +
                ebsRemaining +
                pssRemaining +
                epssRemaining +
                rmsRemaining
    }

    private val queueCounter = QueueCounter()
    private val isPaused get() = preferences.get(NsclientBooleanKey.NsPaused)

    override fun queueSize(): Long = queueCounter.size()


    val bgUploadEnabled @OpenForTesting get() = preferences.get(BooleanKey.BgSourceUploadToNs) && activePlugin.activeBgSource !is NSClientSource

    /**
     * Sync pointers confirmed during the current walk and not yet written to the preferences.
     *
     * Every handled record used to be a preference write at once, also the records a walk only
     * skips locally ("Loaded from NS", "Only NS id changed"): after "Cleanup databases" the pointers
     * were above the tables' highest ids, every pointer was reset to 0 and 23,114 records were
     * walked in 26 s, all of them skips and all of them a write, and every write is forwarded as
     * EventPreferenceChange while a settings screen is open. The pointer now advances in memory:
     * a record that was uploaded (nsAdd/nsUpdate succeeded) still writes it at once, as before;
     * skipped records write it every [CONFIRM_EVERY] records and at the end of the walk (empty
     * result, failed upload, pause, exception). The reset to 0 is still written at once: it is
     * also upstream's retry path for failed uploads. Worst case after a process death mid-walk:
     * up to [CONFIRM_EVERY] - 1 already skipped records are skipped again, without a network call.
     * The confirmLast*IdIfGreater functions keep writing at once.
     *
     * Every pointer write of a walk keeps the old confirmLast*IdIfGreater semantics: it only moves
     * the stored pointer forward. And it is dropped when a Full sync happened since the walk read
     * the pointer ([fullSyncGeneration] changed) or the stored value moved below the value the
     * pending pointer was built on. Check and write run under [pointerLock], as does
     * [resetToNextFullSync], so a Full sync cannot slip in between and be overwritten.
     *
     * The walk state ([pendingPointers], [pendingConfirms], [walkUploaded], [readGenerations]) is
     * owned by one walk at a time: [doUpload] holds [walkMutex] for the whole walk.
     */
    private val pendingPointers = HashMap<NsclientLongKey, PendingPointer>()
    private var pendingConfirms = 0
    private var walkUploaded = false

    /** The [fullSyncGeneration] at which the walk last read each pointer (see lastSyncedId). */
    private val readGenerations = HashMap<NsclientLongKey, Long>()

    /** Guards the pointer check-and-write against [resetToNextFullSync] (called from the UI). */
    private val pointerLock = Any()

    /** Incremented by every [resetToNextFullSync]; a pending or uploaded pointer from an older generation is stale. */
    private var fullSyncGeneration = 0L

    /** One sync walk at a time: overlapping [doUpload] calls wait for the running one. */
    private val walkMutex = Mutex()

    /** A pointer that moved on in memory: [id], built on the stored value [storedBase] in Full sync [generation]. */
    private class PendingPointer(val storedBase: Long, val id: Long, val generation: Long)

    /** True if [pending] was overtaken by a Full sync or the stored value moved below its base. Under [pointerLock]. */
    private fun isStale(pending: PendingPointer, stored: Long) =
        pending.generation != fullSyncGeneration || stored < pending.storedBase

    /**
     * The id a walk continues from: the pending one, unless the stored value moved backwards since
     * it was set (a Full sync removed it, see resetToNextFullSync). Then the pending pointer is
     * stale, it is dropped and the walk restarts from the stored value, as it did when every
     * record was written at once.
     */
    private fun lastSyncedId(key: NsclientLongKey): Long {
        synchronized(pointerLock) {
            readGenerations[key] = fullSyncGeneration
            val stored = preferences.get(key)
            val pending = pendingPointers[key] ?: return stored
            if (isStale(pending, stored)) {
                pendingPointers.remove(key)
                return stored
            }
            return maxOf(pending.id, stored)
        }
    }

    private fun resetPointer(key: NsclientLongKey) {
        synchronized(pointerLock) {
            pendingPointers.remove(key)
            preferences.put(key, 0)
        }
    }

    /** The client for an upload; the record handled next was not a local skip. */
    private fun uploading(): NsClient? {
        walkUploaded = true
        return activePlugin.activeNsClient
    }

    private fun confirmInWalk(key: NsclientLongKey, lastSynced: Long) {
        synchronized(pointerLock) {
            val uploaded = walkUploaded
            walkUploaded = false
            // A Full sync since this record was read: drop it, the next round restarts from the stored value
            if (readGenerations[key] != fullSyncGeneration) {
                pendingPointers.remove(key)
                return
            }
            val stored = preferences.get(key)
            val pending = pendingPointers[key]?.takeUnless { isStale(it, stored) }
            if (pending == null) pendingPointers.remove(key)
            if (lastSynced <= maxOf(stored, pending?.id ?: stored)) return
            if (uploaded) {
                // written at once, as before (stored < lastSynced checked above); it covers the skipped records before it as well
                pendingPointers.remove(key)
                pendingConfirms = 0
                preferences.put(key, lastSynced)
                return
            }
            pendingPointers[key] = PendingPointer(pending?.storedBase ?: stored, lastSynced, fullSyncGeneration)
            if (++pendingConfirms >= CONFIRM_EVERY) flushPointers()
        }
    }

    /**
     * Writes the pending pointers to the preferences: only forward (id > stored), and not when a
     * Full sync happened or the stored value moved backwards meanwhile (see lastSyncedId).
     */
    @VisibleForTesting internal fun flushPointers() {
        synchronized(pointerLock) {
            pendingConfirms = 0
            walkUploaded = false
            if (pendingPointers.isEmpty()) return
            val pointers = pendingPointers.toMap()
            pendingPointers.clear()
            pointers.forEach { (key, pending) ->
                val stored = preferences.get(key)
                if (!isStale(pending, stored) && pending.id > stored) preferences.put(key, pending.id)
            }
        }
    }

    override suspend fun doUpload() {
        walkMutex.withLock {
            rxBus.send(EventNSClientUpdateGuiStatus())
            if ((config.AAPSCLIENT || preferences.get(BooleanKey.NsClientUploadData)) && !isPaused) {
                queueCounter.bolusesRemaining = (persistenceLayer.getLastBolusId() ?: 0L) - preferences.get(NsclientLongKey.BolusLastSyncedId)
                queueCounter.carbsRemaining = (persistenceLayer.getLastCarbsId() ?: 0L) - preferences.get(NsclientLongKey.CarbsLastSyncedId)
                queueCounter.bcrRemaining = (persistenceLayer.getLastBolusCalculatorResultId() ?: 0L) - preferences.get(NsclientLongKey.BolusCalculatorLastSyncedId)
                queueCounter.ttsRemaining = (persistenceLayer.getLastTemporaryTargetId() ?: 0L) - preferences.get(NsclientLongKey.TemporaryTargetLastSyncedId)
// NSCv3 doesn't support food update
//            queueCounter.foodsRemaining = (persistenceLayer.getLastFoodId() ?: 0L) - preferences.get(NsclientLongKey.FoodLastSyncedId)
                queueCounter.gvsRemaining = (persistenceLayer.getLastGlucoseValueId() ?: 0L) - preferences.get(NsclientLongKey.GlucoseValueLastSyncedId)
                queueCounter.tesRemaining = (persistenceLayer.getLastTherapyEventId() ?: 0L) - preferences.get(NsclientLongKey.TherapyEventLastSyncedId)
                queueCounter.dssRemaining = (persistenceLayer.getLastDeviceStatusId() ?: 0L) - preferences.get(NsclientLongKey.DeviceStatusLastSyncedId)
                queueCounter.tbrsRemaining = (persistenceLayer.getLastTemporaryBasalId() ?: 0L) - preferences.get(NsclientLongKey.TemporaryBasalLastSyncedId)
                queueCounter.ebsRemaining = (persistenceLayer.getLastExtendedBolusId() ?: 0L) - preferences.get(NsclientLongKey.ExtendedBolusLastSyncedId)
                queueCounter.pssRemaining = (persistenceLayer.getLastProfileSwitchId() ?: 0L) - preferences.get(NsclientLongKey.ProfileSwitchLastSyncedId)
                queueCounter.epssRemaining = (persistenceLayer.getLastEffectiveProfileSwitchId() ?: 0L) - preferences.get(NsclientLongKey.EffectiveProfileSwitchLastSyncedId)
                queueCounter.rmsRemaining = (persistenceLayer.getLastRunningModeId() ?: 0L) - preferences.get(NsclientLongKey.RunningModeLastSyncedId)
                rxBus.send(EventNSClientUpdateGuiQueue())
                try {
                    processChangedGlucoseValues()
                    processChangedBoluses()
                    processChangedCarbs()
                    processChangedBolusCalculatorResults()
                    processChangedTemporaryBasals()
                    processChangedExtendedBoluses()
                    processChangedProfileSwitches()
                    processChangedEffectiveProfileSwitches()
                    processChangedTempTargets()
// NSCv3 doesn't support food update
//            processChangedFoods()
                    processChangedTherapyEvents()
                    processChangedDeviceStatuses()
                    processChangedRunningModes()
                    processChangedProfileStore()
                } finally {
                    flushPointers()
                }
                storeDataForDb.updateNsIds()
                aapsLogger.debug(LTag.NSCLIENT, "Remaining: $queueCounter")
            }
            rxBus.send(EventNSClientUpdateGuiStatus())
        }
    }

    override fun resetToNextFullSync() {
        synchronized(pointerLock) {
            // a running walk sees the new generation and neither writes its pending nor its next uploaded pointer back
            fullSyncGeneration++
            pendingPointers.clear()
            preferences.remove(NsclientLongKey.GlucoseValueLastSyncedId)
            preferences.remove(NsclientLongKey.TemporaryBasalLastSyncedId)
            preferences.remove(NsclientLongKey.TemporaryTargetLastSyncedId)
            preferences.remove(NsclientLongKey.ExtendedBolusLastSyncedId)
            preferences.remove(NsclientLongKey.FoodLastSyncedId)
            preferences.remove(NsclientLongKey.BolusLastSyncedId)
            preferences.remove(NsclientLongKey.CarbsLastSyncedId)
            preferences.remove(NsclientLongKey.BolusCalculatorLastSyncedId)
            preferences.remove(NsclientLongKey.TherapyEventLastSyncedId)
            preferences.remove(NsclientLongKey.ProfileSwitchLastSyncedId)
            preferences.remove(NsclientLongKey.EffectiveProfileSwitchLastSyncedId)
            preferences.remove(NsclientLongKey.RunningModeLastSyncedId)
            preferences.remove(NsclientLongKey.ProfileStoreLastSyncedId)

            val lastDeviceStatusDbId = persistenceLayer.getLastDeviceStatusId()
            if (lastDeviceStatusDbId != null) preferences.put(NsclientLongKey.DeviceStatusLastSyncedId, lastDeviceStatusDbId)
            else preferences.remove(NsclientLongKey.DeviceStatusLastSyncedId)
        }
    }

    @OpenForTesting
    fun confirmLastBolusIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.BolusLastSyncedId)) {
            preferences.put(NsclientLongKey.BolusLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedBoluses() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastBolusId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.BolusLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.BolusLastSyncedId)
                startId = 0
            }
            queueCounter.bolusesRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementBolus(startId).blockingGet()?.let { bolus ->
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    bolus.first.id == bolus.second.id && bolus.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring Bolus. Loaded from NS: ${bolus.second.id} ")
                    // only NsId changed, no need to upload
                    bolus.first.onlyNsIdAdded(bolus.second)                                   ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring Bolus. Only NS id changed: ${bolus.second.id} ")
                    // without nsId = create new
                    bolus.first.ids.nightscoutId == null                                      ->
                        cont = uploading()?.nsAdd("treatments", DataSyncSelector.PairBolus(bolus.first, bolus.second.id), " $startId/$lastDbId") == true
                    // with nsId = update if it's modified record
                    bolus.first.ids.nightscoutId != null && bolus.first.id != bolus.second.id ->
                        cont = uploading()?.nsUpdate("treatments", DataSyncSelector.PairBolus(bolus.first, bolus.second.id), "$startId/$lastDbId") == true
                }
                if (cont) confirmInWalk(NsclientLongKey.BolusLastSyncedId, bolus.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastCarbsIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.CarbsLastSyncedId)) {
            preferences.put(NsclientLongKey.CarbsLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedCarbs() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastCarbsId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.CarbsLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.CarbsLastSyncedId)
                startId = 0
            }
            queueCounter.carbsRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementCarbs(startId).blockingGet()?.let { carb ->
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    carb.first.id == carb.second.id && carb.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring Carbs. Loaded from NS: ${carb.second.id} ")
                    // only NsId changed, no need to upload
                    carb.first.onlyNsIdAdded(carb.second)                                  ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring Carbs. Only NS id changed ID: ${carb.second.id} ")
                    // without nsId = create new
                    carb.first.ids.nightscoutId == null                                    ->
                        cont = uploading()?.nsAdd("treatments", DataSyncSelector.PairCarbs(carb.first, carb.second.id), "$startId/$lastDbId") == true
                    // with nsId = update if it's modified record
                    carb.first.ids.nightscoutId != null && carb.first.id != carb.second.id ->
                        cont = uploading()?.nsUpdate("treatments", DataSyncSelector.PairCarbs(carb.first, carb.second.id), "$startId/$lastDbId") == true
                }
                if (cont) confirmInWalk(NsclientLongKey.CarbsLastSyncedId, carb.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastBolusCalculatorResultsIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.BolusCalculatorLastSyncedId)) {
            preferences.put(NsclientLongKey.BolusCalculatorLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedBolusCalculatorResults() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastBolusCalculatorResultId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.BolusCalculatorLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.BolusCalculatorLastSyncedId)
                startId = 0
            }
            queueCounter.bcrRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementBolusCalculatorResult(startId).blockingGet()?.let { bolusCalculatorResult ->
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    bolusCalculatorResult.first.id == bolusCalculatorResult.second.id && bolusCalculatorResult.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring BolusCalculatorResult. Loaded from NS: ${bolusCalculatorResult.second.id} ")
                    // only NsId changed, no need to upload
                    bolusCalculatorResult.first.onlyNsIdAdded(bolusCalculatorResult.second)                                                   ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring BolusCalculatorResult. Only NS id changed ID: ${bolusCalculatorResult.second.id} ")
                    // without nsId = create new
                    bolusCalculatorResult.first.ids.nightscoutId == null                                                                      ->
                        cont = uploading()?.nsAdd(
                            "treatments",
                            DataSyncSelector.PairBolusCalculatorResult(bolusCalculatorResult.first, bolusCalculatorResult.second.id),
                            "$startId/$lastDbId"
                        ) == true
                    // with nsId = update if it's modified record
                    bolusCalculatorResult.first.ids.nightscoutId != null && bolusCalculatorResult.first.id != bolusCalculatorResult.second.id ->
                        cont = uploading()?.nsUpdate(
                            "treatments",
                            DataSyncSelector.PairBolusCalculatorResult(bolusCalculatorResult.first, bolusCalculatorResult.second.id),
                            "$startId/$lastDbId"
                        ) == true
                }
                if (cont) confirmInWalk(NsclientLongKey.BolusCalculatorLastSyncedId, bolusCalculatorResult.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastTempTargetsIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.TemporaryTargetLastSyncedId)) {
            preferences.put(NsclientLongKey.TemporaryTargetLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedTempTargets() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastTemporaryTargetId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.TemporaryTargetLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.TemporaryTargetLastSyncedId)
                startId = 0
            }
            queueCounter.ttsRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementTemporaryTarget(startId).blockingGet()?.let { tt ->
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    tt.first.id == tt.second.id && tt.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring TemporaryTarget. Loaded from NS: ${tt.second.id} ")
                    // only NsId changed, no need to upload
                    tt.first.onlyNsIdAdded(tt.second)                                ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring TemporaryTarget. Only NS id changed ID: ${tt.second.id} ")
                    // without nsId = create new
                    tt.first.ids.nightscoutId == null                                ->
                        cont = uploading()?.nsAdd("treatments", DataSyncSelector.PairTemporaryTarget(tt.first, tt.second.id), "$startId/$lastDbId") == true
                    // existing with nsId = update
                    tt.first.ids.nightscoutId != null                                ->
                        cont = uploading()?.nsUpdate("treatments", DataSyncSelector.PairTemporaryTarget(tt.first, tt.second.id), "$startId/$lastDbId") == true
                }
                if (cont) confirmInWalk(NsclientLongKey.TemporaryTargetLastSyncedId, tt.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }
/*
    @OpenForTesting
    fun confirmLastFoodIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.FoodLastSyncedId)) {
            preferences.put(NsclientLongKey.FoodLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedFoods() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastFoodId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.FoodLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.FoodLastSyncedId)
                startId = 0
            }
            queueCounter.foodsRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementFood(startId).blockingGet()?.let { food ->
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    food.first.id == food.second.id && food.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring Food. Loaded from NS: ${food.second.id} ")
                    // only NsId changed, no need to upload
                    food.first.onlyNsIdAdded(food.second)                                  ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring Food. Only NS id changed ID: ${food.second.id} ")
                    // without nsId = create new
                    food.first.ids.nightscoutId == null                                    ->
                        cont = uploading()?.nsAdd("food", DataSyncSelector.PairFood(food.first, food.second.id), "$startId/$lastDbId") == true
                    // with nsId = update
                    food.first.ids.nightscoutId != null                                    ->
                        cont = uploading()?.nsUpdate("food", DataSyncSelector.PairFood(food.first, food.second.id), "$startId/$lastDbId") == true
                }
                if (cont) confirmInWalk(NsclientLongKey.FoodLastSyncedId, food.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }
*/
    @OpenForTesting
    fun confirmLastGlucoseValueIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.GlucoseValueLastSyncedId)) {
            preferences.put(NsclientLongKey.GlucoseValueLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedGlucoseValues() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastGlucoseValueId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.GlucoseValueLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.GlucoseValueLastSyncedId)
                startId = 0
            }
            queueCounter.gvsRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementGlucoseValue(startId).blockingGet()?.let { gv ->
                if (bgUploadEnabled) {
                    when {
                        // new record with existing NS id => must be coming from NS => ignore
                        gv.first.id == gv.second.id && gv.first.ids.nightscoutId != null ->
                            aapsLogger.info(LTag.NSCLIENT, "Ignoring GlucoseValue. Loaded from NS: ${gv.second.id} ")
                        // only NsId changed, no need to upload
                        gv.first.onlyNsIdAdded(gv.second)                                ->
                            aapsLogger.info(LTag.NSCLIENT, "Ignoring GlucoseValue. Only NS id changed ID: ${gv.second.id} ")
                        // without nsId = create new
                        gv.first.ids.nightscoutId == null                                ->
                            cont = uploading()?.nsAdd("entries", DataSyncSelector.PairGlucoseValue(gv.first, gv.second.id), "$startId/$lastDbId") == true
                        // with nsId = update
                        else                                                             ->  //  gv.first.interfaceIDs.nightscoutId != null
                            cont = uploading()?.nsUpdate("entries", DataSyncSelector.PairGlucoseValue(gv.first, gv.second.id), "$startId/$lastDbId") == true
                    }
                }
                if (cont) confirmInWalk(NsclientLongKey.GlucoseValueLastSyncedId, gv.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastTherapyEventIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.TherapyEventLastSyncedId)) {
            preferences.put(NsclientLongKey.TherapyEventLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedTherapyEvents() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastTherapyEventId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.TherapyEventLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.TherapyEventLastSyncedId)
                startId = 0
            }
            queueCounter.tesRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementTherapyEvent(startId).blockingGet()?.let { te ->
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    te.first.id == te.second.id && te.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring TherapyEvent. Loaded from NS: ${te.second.id} ")
                    // only NsId changed, no need to upload
                    te.first.onlyNsIdAdded(te.second)                                ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring TherapyEvent. Only NS id changed ID: ${te.second.id} ")
                    // without nsId = create new
                    te.first.ids.nightscoutId == null                                ->
                        cont = uploading()?.nsAdd("treatments", DataSyncSelector.PairTherapyEvent(te.first, te.second.id), "$startId/$lastDbId") == true
                    // nsId = update
                    te.first.ids.nightscoutId != null                                ->
                        cont = uploading()?.nsUpdate("treatments", DataSyncSelector.PairTherapyEvent(te.first, te.second.id), "$startId/$lastDbId") == true
                }
                if (cont) confirmInWalk(NsclientLongKey.TherapyEventLastSyncedId, te.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastDeviceStatusIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.DeviceStatusLastSyncedId)) {
            preferences.put(NsclientLongKey.DeviceStatusLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedDeviceStatuses() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastDeviceStatusId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.DeviceStatusLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.DeviceStatusLastSyncedId)
                startId = 0
            }
            queueCounter.dssRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementDeviceStatus(startId).blockingGet()?.let { deviceStatus ->
                cont = uploading()?.nsAdd("devicestatus", DataSyncSelector.PairDeviceStatus(deviceStatus, lastDbId), "$startId/$lastDbId") == true
                if (cont) confirmInWalk(NsclientLongKey.DeviceStatusLastSyncedId, deviceStatus.id)
                // with nsId = ignore
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastTemporaryBasalIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.TemporaryBasalLastSyncedId)) {
            preferences.put(NsclientLongKey.TemporaryBasalLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedTemporaryBasals() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastTemporaryBasalId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.TemporaryBasalLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.TemporaryBasalLastSyncedId)
                startId = 0
            }
            queueCounter.tbrsRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementTemporaryBasal(startId).blockingGet()?.let { tb ->
                val profile = profileFunction.getProfile(tb.first.timestamp)
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    tb.first.id == tb.second.id && tb.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring TemporaryBasal. Loaded from NS: ${tb.second.id} ")
                    // only NsId changed, no need to upload
                    tb.first.onlyNsIdAdded(tb.second)                                ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring TemporaryBasal. Only NS id changed ID: ${tb.second.id} ")
                    // without nsId = create new
                    tb.first.ids.nightscoutId == null                                ->
                        cont = uploading()?.nsAdd("treatments", DataSyncSelector.PairTemporaryBasal(tb.first, tb.second.id), "$startId/$lastDbId", profile) == true
                    // with nsId = update
                    tb.first.ids.nightscoutId != null                                ->
                        cont = uploading()?.nsUpdate("treatments", DataSyncSelector.PairTemporaryBasal(tb.first, tb.second.id), "$startId/$lastDbId", profile) == true
                }
                if (cont) confirmInWalk(NsclientLongKey.TemporaryBasalLastSyncedId, tb.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastExtendedBolusIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.ExtendedBolusLastSyncedId)) {
            preferences.put(NsclientLongKey.ExtendedBolusLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedExtendedBoluses() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastExtendedBolusId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.ExtendedBolusLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.ExtendedBolusLastSyncedId)
                startId = 0
            }
            queueCounter.ebsRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementExtendedBolus(startId).blockingGet()?.let { eb ->
                val profile = profileFunction.getProfile(eb.first.timestamp)
                if (profile != null) {
                    when {
                        // new record with existing NS id => must be coming from NS => ignore
                        eb.first.id == eb.second.id && eb.first.ids.nightscoutId != null ->
                            aapsLogger.info(LTag.NSCLIENT, "Ignoring ExtendedBolus. Loaded from NS: ${eb.second.id} ")
                        // only NsId changed, no need to upload
                        eb.first.onlyNsIdAdded(eb.second)                                ->
                            aapsLogger.info(LTag.NSCLIENT, "Ignoring ExtendedBolus. Only NS id changed ID: ${eb.second.id} ")
                        // without nsId = create new
                        eb.first.ids.nightscoutId == null                                ->
                            cont = uploading()?.nsAdd("treatments", DataSyncSelector.PairExtendedBolus(eb.first, eb.second.id), "$startId/$lastDbId", profile) == true
                        // with nsId = update
                        eb.first.ids.nightscoutId != null                                ->
                            cont = uploading()?.nsUpdate("treatments", DataSyncSelector.PairExtendedBolus(eb.first, eb.second.id), "$startId/$lastDbId", profile) == true
                    }
                } else aapsLogger.info(LTag.NSCLIENT, "Ignoring ExtendedBolus. No profile: ${eb.second.id} ")
                if (cont) confirmInWalk(NsclientLongKey.ExtendedBolusLastSyncedId, eb.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastProfileSwitchIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.ProfileSwitchLastSyncedId)) {
            preferences.put(NsclientLongKey.ProfileSwitchLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedProfileSwitches() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastProfileSwitchId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.ProfileSwitchLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.ProfileSwitchLastSyncedId)
                startId = 0
            }
            queueCounter.pssRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementProfileSwitch(startId).blockingGet()?.let { ps ->
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    ps.first.id == ps.second.id && ps.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring ProfileSwitch. Loaded from NS: ${ps.second.id} ")
                    // only NsId changed, no need to upload
                    ps.first.onlyNsIdAdded(ps.second)                                ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring ProfileSwitch. Only NS id changed ID: ${ps.second.id} ")
                    // without nsId = create new
                    ps.first.ids.nightscoutId == null                                ->
                        cont = uploading()?.nsAdd("treatments", DataSyncSelector.PairProfileSwitch(ps.first, ps.second.id), "$startId/$lastDbId") == true
                    // with nsId = update
                    ps.first.ids.nightscoutId != null                                ->
                        cont = uploading()?.nsUpdate("treatments", DataSyncSelector.PairProfileSwitch(ps.first, ps.second.id), "$startId/$lastDbId") == true
                }
                if (cont) confirmInWalk(NsclientLongKey.ProfileSwitchLastSyncedId, ps.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastEffectiveProfileSwitchIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.EffectiveProfileSwitchLastSyncedId)) {
            preferences.put(NsclientLongKey.EffectiveProfileSwitchLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedEffectiveProfileSwitches() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastEffectiveProfileSwitchId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.EffectiveProfileSwitchLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.EffectiveProfileSwitchLastSyncedId)
                startId = 0
            }
            queueCounter.epssRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementEffectiveProfileSwitch(startId).blockingGet()?.let { ps ->
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    ps.first.id == ps.second.id && ps.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring EffectiveProfileSwitch. Loaded from NS: ${ps.second.id} ")
                    // only NsId changed, no need to upload
                    ps.first.onlyNsIdAdded(ps.second)                                ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring EffectiveProfileSwitch. Only NS id changed ID: ${ps.second.id} ")
                    // without nsId = create new
                    ps.first.ids.nightscoutId == null                                ->
                        cont = uploading()?.nsAdd("treatments", DataSyncSelector.PairEffectiveProfileSwitch(ps.first, ps.second.id), "$startId/$lastDbId") == true
                    // with nsId = update
                    ps.first.ids.nightscoutId != null                                ->
                        cont = uploading()?.nsUpdate("treatments", DataSyncSelector.PairEffectiveProfileSwitch(ps.first, ps.second.id), "$startId/$lastDbId") == true
                }
                if (cont) confirmInWalk(NsclientLongKey.EffectiveProfileSwitchLastSyncedId, ps.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastRunningModeIdIfGreater(lastSynced: Long) {
        if (lastSynced > preferences.get(NsclientLongKey.RunningModeLastSyncedId)) {
            preferences.put(NsclientLongKey.RunningModeLastSyncedId, lastSynced)
        }
    }

    @OpenForTesting
    suspend fun processChangedRunningModes() {
        var cont = true
        while (cont) {
            if (isPaused) {
                flushPointers()
                return
            }
            val lastDbId = persistenceLayer.getLastRunningModeId() ?: 0L
            var startId = lastSyncedId(NsclientLongKey.RunningModeLastSyncedId)
            if (startId > lastDbId) {
                aapsLogger.info(LTag.NSCLIENT, "Resetting startId: $startId lastDbId: $lastDbId")
                resetPointer(NsclientLongKey.RunningModeLastSyncedId)
                startId = 0
            }
            queueCounter.rmsRemaining = lastDbId - startId
            rxBus.send(EventNSClientUpdateGuiQueue())
            persistenceLayer.getNextSyncElementRunningMode(startId).blockingGet()?.let { rm ->
                when {
                    // new record with existing NS id => must be coming from NS => ignore
                    rm.first.id == rm.second.id && rm.first.ids.nightscoutId != null ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring RunningMode. Loaded from NS: ${rm.second.id} ")
                    // only NsId changed, no need to upload
                    rm.first.onlyNsIdAdded(rm.second)                                ->
                        aapsLogger.info(LTag.NSCLIENT, "Ignoring RunningMode. Only NS id changed ID: ${rm.second.id} ")
                    // without nsId = create new
                    rm.first.ids.nightscoutId == null                                ->
                        cont = uploading()?.nsAdd("treatments", DataSyncSelector.PairRunningMode(rm.first, rm.second.id), "$startId/$lastDbId") == true
                    // existing with nsId = update
                    rm.first.ids.nightscoutId != null                                ->
                        cont = uploading()?.nsUpdate("treatments", DataSyncSelector.PairRunningMode(rm.first, rm.second.id), "$startId/$lastDbId") == true
                }
                if (cont) confirmInWalk(NsclientLongKey.RunningModeLastSyncedId, rm.second.id)
            } ?: run {
                cont = false
            }
        }
        flushPointers()
    }

    @OpenForTesting
    fun confirmLastProfileStore(lastSynced: Long) {
        preferences.put(NsclientLongKey.ProfileStoreLastSyncedId, lastSynced)
    }

    override fun profileReceived(timestamp: Long) {
        preferences.put(NsclientLongKey.ProfileStoreLastSyncedId, timestamp)
    }

    @OpenForTesting
    suspend fun processChangedProfileStore() {
        if (isPaused) return
        val lastSync = preferences.get(NsclientLongKey.ProfileStoreLastSyncedId)
        val lastChange = preferences.get(LongNonKey.LocalProfileLastChange)
        if (lastChange == 0L) return
        if (lastChange > lastSync) {
            if (activePlugin.activeProfileSource.profile?.allProfilesValid != true) return
            val profileStore = activePlugin.activeProfileSource.profile
            val profileJson = profileStore?.getData() ?: return
            // add for v3
            if (JsonHelper.safeGetLongAllowNull(profileJson, "date") == null)
                profileJson.put("date", profileStore.getStartDate())
            val now = dateUtil.now()
            if (activePlugin.activeNsClient?.nsAdd("profile", DataSyncSelector.PairProfileStore(profileJson, now), "") == true)
                confirmLastProfileStore(now)
        }
    }

    companion object {

        /** During a walk the sync pointer is written to the preferences every this many locally skipped records. */
        const val CONFIRM_EVERY = 50
    }
}
