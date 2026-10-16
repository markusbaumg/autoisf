package app.aaps.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ResFileMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for complication data using DataStore with Protocol Buffers
 *
 * Best practices implemented:
 * - Single source of truth for complication data
 * - Asynchronous reads/writes (non-blocking)
 * - Type-safe data access
 * - Corruption handling with recovery
 * - Reactive updates via Flow
 *
 */
@Singleton
class ComplicationDataRepository @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val dataStore: DataStore<ComplicationData> = DataStoreFactory.create(
        serializer = ComplicationDataSerializer(aapsLogger),
        produceFile = { context.dataStoreFile("complication_data.pb") }
    )

    companion object {

        /**
         * How often [ComplicationData.lastUpdateTimestamp] is refreshed while the phone keeps sending data
         * that equals the stored data. Well below the staleness limit of the complications (Constants.STALE_MS),
         * so an unchanged but live connection is never shown as "no sync".
         */
        const val HEARTBEAT_MS = 60_000L
    }

    /**
     * Reactive Flow of complication data
     * Complications should collect this Flow to get automatic updates
     */
    val complicationData: Flow<ComplicationData> = dataStore.data
        .catch { exception ->
            aapsLogger.error(LTag.WEAR, "Error reading complication data", exception)
            emit(ComplicationData()) // Emit default on error
        }

    /**
     * Update BG data from phone
     * Supports multiple datasets for AAPSClient mode (0=primary, 1=client1, 2=client2)
     *
     * @return true when the stored data changed (and the [complicationData] flow emitted)
     */
    suspend fun updateBgData(singleBg: EventData.SingleBg): Boolean =
        updateIfChanged("BG") { current ->
            when (singleBg.dataset) {
                0    -> if (current.bgData == singleBg) current else current.copy(bgData = singleBg)
                1    -> if (current.bgData1 == singleBg) current else current.copy(bgData1 = singleBg)
                2    -> if (current.bgData2 == singleBg) current else current.copy(bgData2 = singleBg)
                else -> {
                    aapsLogger.warn(LTag.WEAR, "Unknown BG dataset ${singleBg.dataset}, ignoring")
                    current
                }
            }
        }

    /**
     * Update Status data from phone
     * Supports multiple datasets for AAPSClient mode (0=primary, 1=client1, 2=client2)
     *
     * @return true when the stored data changed (and the [complicationData] flow emitted)
     */
    suspend fun updateStatusData(status: EventData.Status): Boolean =
        updateIfChanged("status") { current ->
            when (status.dataset) {
                0    -> if (current.statusData == status) current else current.copy(statusData = status)
                1    -> if (current.statusData1 == status) current else current.copy(statusData1 = status)
                2    -> if (current.statusData2 == status) current else current.copy(statusData2 = status)
                else -> {
                    aapsLogger.warn(LTag.WEAR, "Unknown Status dataset ${status.dataset}, ignoring")
                    current
                }
            }
        }

    /**
     * Update Graph data from phone
     *
     * @return true when the stored data changed (and the [complicationData] flow emitted)
     */
    suspend fun updateGraphData(graphData: EventData.GraphData): Boolean =
        updateIfChanged("graph") { current ->
            if (current.graphData == graphData) current else current.copy(graphData = graphData)
        }

    /**
     * Update Treatment data from phone
     *
     * @return true when the stored data changed (and the [complicationData] flow emitted)
     */
    suspend fun updateTreatmentData(treatmentData: EventData.TreatmentData): Boolean =
        updateIfChanged("treatment") { current ->
            if (current.treatmentData == treatmentData) current else current.copy(treatmentData = treatmentData)
        }

    /**
     * Apply [transform] to the stored data and persist the result only when it differs from what is stored.
     *
     * The phone resends its complete data set (BG, status, graph, treatments) on every loop run, and most
     * of these resends carry the same graph and treatments as the one before. DataStore already skips the
     * file write and the flow emission when the transformed value equals the current one, but a
     * [ComplicationData.lastUpdateTimestamp] refreshed on every call made every value different: each of
     * the ~8 messages per resend rewrote the whole file (graph + treatments + watchface) and woke every
     * collector (watchfaces rebuilt their charts, complications were refreshed). Now [transform] returns
     * `current` itself when nothing changed, and the timestamp is only refreshed with a real change or,
     * for the staleness check of the complications, at most once per [HEARTBEAT_MS] otherwise.
     *
     * @param what name of the part, for the error log
     * @param transform returns `current` (the same instance) when the incoming data equals the stored data
     * @return true when the data (not only the heartbeat timestamp) changed
     */
    private suspend fun updateIfChanged(what: String, transform: (ComplicationData) -> ComplicationData): Boolean {
        var changed = false
        try {
            dataStore.updateData { current ->
                val next = transform(current)
                val now = System.currentTimeMillis()
                if (next === current) {
                    if (now - current.lastUpdateTimestamp >= HEARTBEAT_MS) current.copy(lastUpdateTimestamp = now) else current
                } else {
                    changed = true
                    next.copy(lastUpdateTimestamp = now)
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to update $what data", e)
        }
        return changed
    }

    /**
     * Store custom watchface data
     * @param customWatchface Main watchface to store
     * @param customWatchfaceFull Optional full version with all resources
     * @param isDefault If true, store as default instead of current
     */
    suspend fun storeCustomWatchface(
        customWatchface: CwfData,
        customWatchfaceFull: CwfData? = null,
        isDefault: Boolean = false
    ) {
        try {
            dataStore.updateData { current ->
                if (isDefault) {
                    current.copy(
                        customWatchfaceDefault = customWatchface,
                        customWatchfaceDefaultFull = customWatchfaceFull
                    )
                } else {
                    current.copy(customWatchface = customWatchface)
                }
            }
            aapsLogger.debug(LTag.WEAR, "Stored custom watchface ${if (isDefault) "(default)" else ""}: ${customWatchface.metadata}")
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to store custom watchface", e)
        }
    }

    /**
     * Update custom watchface metadata only (preserves resources)
     * Used when syncing metadata changes without re-sending all resources
     */
    suspend fun updateCustomWatchfaceMetadata(newMetadata: Map<CwfMetadataKey, String>) {
        try {
            dataStore.updateData { current ->
                current.customWatchface?.let { savedCwf ->
                    // Check if name and version match before updating
                    if (newMetadata[CwfMetadataKey.CWF_NAME] == savedCwf.metadata[CwfMetadataKey.CWF_NAME] &&
                        newMetadata[CwfMetadataKey.CWF_AUTHOR_VERSION] == savedCwf.metadata[CwfMetadataKey.CWF_AUTHOR_VERSION]
                    ) {
                        val updatedCwf = CwfData(savedCwf.json, newMetadata.toMutableMap(), savedCwf.resData)
                        aapsLogger.debug(LTag.WEAR, "Updated custom watchface metadata: $newMetadata")
                        current.copy(customWatchface = updatedCwf)
                    } else {
                        current
                    }
                } ?: current
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to update custom watchface metadata", e)
        }
    }

    /**
     * Reset current watchface to default
     */
    suspend fun setDefaultWatchface() {
        try {
            dataStore.updateData { current ->
                current.customWatchfaceDefault?.let { default ->
                    aapsLogger.debug(LTag.WEAR, "Reset to default watchface")
                    current.copy(customWatchface = default)
                } ?: current
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to set default watchface", e)
        }
    }

    /**
     * Get custom watchface with fallback to default
     * @param isDefault If true, read default instead of current
     * @return CwfData or null if not set
     */
    suspend fun getCustomWatchface(isDefault: Boolean = false): CwfData? {
        return try {
            val current = complicationData.first()
            if (isDefault) {
                current.customWatchfaceDefault
            } else {
                current.customWatchface ?: current.customWatchfaceDefault
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to get custom watchface", e)
            null
        }
    }

    /**
     * Get simplified custom watchface (only main resource file)
     * Used for exporting without external resources
     * @param useDefault If true, read from default storage
     * @return Simplified CwfData or null
     */
    suspend fun getSimplifiedCustomWatchface(useDefault: Boolean = false): CwfData? {
        return try {
            val current = complicationData.first()
            val source = if (useDefault) {
                current.customWatchfaceDefaultFull ?: current.customWatchfaceDefault
            } else {
                current.customWatchface
            }

            source?.let { cwf ->
                // Simplify by keeping only the main CUSTOM_WATCHFACE resource
                cwf.resData[ResFileMap.CUSTOM_WATCHFACE.fileName]?.let { mainRes ->
                    val simplifiedResData = mutableMapOf(ResFileMap.CUSTOM_WATCHFACE.fileName to mainRes)
                    CwfData(cwf.json, cwf.metadata, simplifiedResData)
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to get simplified custom watchface", e)
            null
        }
    }
}

/**
 * Serializer for ComplicationData using Protocol Buffers
 * Handles corruption gracefully by returning default data
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private class ComplicationDataSerializer(
    private val aapsLogger: AAPSLogger
) : Serializer<ComplicationData> {

    override val defaultValue: ComplicationData = ComplicationData()

    override suspend fun readFrom(input: InputStream): ComplicationData {
        return try {
            ProtoBuf.decodeFromByteArray(
                ComplicationData.serializer(),
                input.readBytes()
            )
        } catch (e: SerializationException) {
            aapsLogger.error(LTag.WEAR, "Corrupted complication data, using default", e)
            defaultValue
        }
    }

    override suspend fun writeTo(t: ComplicationData, output: OutputStream) {
        output.write(
            ProtoBuf.encodeToByteArray(
                ComplicationData.serializer(),
                t
            )
        )
    }
}
