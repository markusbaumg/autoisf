package app.aaps.implementation.db

import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.objects.extensions.toTemporaryBasal
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
        // The one running at the start, and every one starting later: together every entry that can be
        // running inside the range. latestRunningAt then picks as the "active at" query does. The only
        // case that differs is two valid entries that overlap, which the database should not hold.
        // The "active at" query for the start is still one index walk when nothing runs at the start,
        // but it is one per range instead of one per step.
        val temporaryBasals = (listOfNotNull(persistenceLayer.getTemporaryBasalActiveAt(from)) +
            persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(from, to, true)).distinctBy { it.id }
        val extendedBoluses =
            if (activePlugin.activePump.isFakingTempsByExtendedBoluses)
                (listOfNotNull(persistenceLayer.getExtendedBolusActiveAt(from)) +
                    persistenceLayer.getExtendedBolusesStartingFromTimeToTime(from, to, true)).distinctBy { it.id }
            else emptyList()
        return object : ProcessedTbrEbData.TempBasalsInRange {
            override fun at(timestamp: Long): TB? =
                temporaryBasals.latestRunningAt(timestamp, { it.timestamp }, { it.duration })
                    ?: extendedBoluses.latestRunningAt(timestamp, { it.timestamp }, { it.duration })?.let { eb ->
                        // Same as getConvertedExtended: no profile, no converted extended bolus
                        profileFunction.getProfile(timestamp)?.let { eb.toTemporaryBasal(it) }
                    }
        }
    }

}