package app.aaps.wear.watchfaces.common

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.comm.DataLayerListenerServiceWear
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

/**
 * Asynchronous CapabilityClient FILTER_REACHABLE lookup of the AAPS phone (the data layer drops messages silently
 * without a reachable node). Every answer, a failure included (= not reachable), goes to [BolusRequestGuard.onReachAnswer];
 * [onChanged] runs when the answer differs from the previous one.
 */
object PhoneReachability {

    /** Offline render harness only (wear/src/test): answers instead of the CapabilityClient. Always null on the watch. */
    @VisibleForTesting
    @Volatile internal var offlineAnswer: Boolean? = null

    fun refresh(context: Context, guard: BolusRequestGuard, logger: AAPSLogger, tag: String, onChanged: () -> Unit) {
        val answer: (Boolean) -> Unit = { if (guard.onReachAnswer(it)) onChanged() }
        offlineAnswer?.let {
            answer(it)
            return
        }
        try {
            Wearable.getCapabilityClient(context)
                .getCapability(DataLayerListenerServiceWear.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { answer(it.nodes.isNotEmpty()) }
                .addOnFailureListener {
                    logger.error(LTag.WEAR, "$tag capability lookup failed", it)
                    answer(false)
                }
        } catch (e: Exception) {
            logger.error(LTag.WEAR, "$tag capability lookup failed", e)
            answer(false)
        }
    }
}
