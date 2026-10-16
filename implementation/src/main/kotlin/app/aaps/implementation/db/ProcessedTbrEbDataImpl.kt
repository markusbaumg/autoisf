package app.aaps.implementation.db

import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.objects.extensions.toTemporaryBasal
import app.aaps.core.utils.RunningAt
import dagger.Reusable
import javax.inject.Inject

@Reusable
class ProcessedTbrEbDataImpl @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction
) : ProcessedTbrEbData {

    private fun getConvertedExtended(timestamp: Long): TB? {
        if (activePlugin.activePump.isFakingTempsByExtendedBoluses) {
            val eb = persistenceLayer.getExtendedBolusActiveAt(timestamp)
            val profile = profileFunction.getProfile(timestamp) ?: return null
            return eb?.toTemporaryBasal(profile)
        }
        return null
    }

    override fun getTempBasalIncludingConvertedExtended(timestamp: Long): TB? =
        persistenceLayer.getTemporaryBasalActiveAt(timestamp) ?: getConvertedExtended(timestamp)

    override fun getTempBasalIncludingConvertedExtendedForRange(startTime: Long, endTime: Long, calculationStep: Long): Map<Long, TB?> {
        val tempBasals = HashMap<Long, TB?>()
        val tbs = persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(startTime, endTime)
        for (t in startTime until endTime step calculationStep) {
            val tb = tbs.firstOrNull { basal -> basal.timestamp <= t && (basal.timestamp + basal.duration) > t }
            tempBasals[t] = tb ?: getConvertedExtended(t)
        }
        return tempBasals
    }

    override fun getTempBasalsIncludingConvertedExtended(from: Long, to: Long): ProcessedTbrEbData.TempBasalsInRange {
        // Every valid entry that runs at some time of the range, including ones that overlap: an older
        // entry still running after a later started, shorter one has ended is picked again for the steps
        // after it, as the per-step "active at" query picks it. Sorted oldest first and ties by id, so the
        // highest id wins a tie as a descending walk of the timestamp index returns it.
        // One query per range instead of one "active at" query per step.
        val temporaryBasals = RunningAt(
            persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(from, to).sortedWith(compareBy({ it.timestamp }, { it.id })),
            { it.timestamp }, { it.duration }
        )
        val extendedBoluses =
            if (activePlugin.activePump.isFakingTempsByExtendedBoluses)
                RunningAt(
                    persistenceLayer.getExtendedBolusesActiveBetweenTimeAndTime(from, to).sortedWith(compareBy({ it.timestamp }, { it.id })),
                    { it.timestamp }, { it.duration }
                )
            else null
        return object : ProcessedTbrEbData.TempBasalsInRange {
            override fun at(timestamp: Long): TB? =
                temporaryBasals.at(timestamp)
                    ?: extendedBoluses?.at(timestamp)?.let { eb ->
                        // Same as getConvertedExtended: no profile, no converted extended bolus
                        profileFunction.getProfile(timestamp)?.let { eb.toTemporaryBasal(it) }
                    }
        }
    }

}

