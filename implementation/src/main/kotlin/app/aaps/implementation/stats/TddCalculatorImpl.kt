package app.aaps.implementation.stats

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.collection.LongSparseArray
import app.aaps.core.data.aps.AverageTDD
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TDD
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.utils.RunningAt
import dagger.Reusable
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

@Reusable
class TddCalculatorImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer
) : TddCalculator {

    override fun calculate(days: Long, allowMissingDays: Boolean): LongSparseArray<TDD>? =
        calculate(dateUtil.now(), days, allowMissingDays)

    override fun calculate(timestamp: Long, days: Long, allowMissingDays: Boolean): LongSparseArray<TDD>? {
        var startTime = MidnightTime.calcDaysBack(timestamp, days)
        val endTime = Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault())
            .plusDays(days)
            .toInstant().toEpochMilli()

        aapsLogger.debug(LTag.APS, "Calculating TotalDailyDose from ${dateUtil.dateString(startTime)} to ${dateUtil.dateString(endTime)}")

        val result = LongSparseArray<TDD>()
        // Try to load cached values
        while (startTime < endTime) {
            aapsLogger.debug(LTag.APS, "Looking for cached TotalDailyDose for ${dateUtil.dateString(startTime)}")
            persistenceLayer.getCalculatedTotalDailyDose(startTime)?.let {
                result.put(startTime, it)
                aapsLogger.debug(LTag.APS, "Loaded cached TotalDailyDose for ${dateUtil.dateString(it.timestamp)} $it")
            } ?: break
            startTime = MidnightTime.calc(startTime + T.hours(27).msecs()) // be sure we find correct midnight during DST change
        }

        if (endTime > startTime) {
            var midnight = startTime
            while (midnight < endTime) {
                val tdd = calculateInterval(midnight, midnight + T.hours(24).msecs(), allowMissingData = false)
                aapsLogger.debug(LTag.APS, "Calculated TotalDailyDose for ${dateUtil.dateString(midnight)} $tdd")
                if (tdd != null) result.put(midnight, tdd)
                midnight = MidnightTime.calc(midnight + T.hours(27).msecs()) // be sure we find correct midnight
            }
        }
        for (i in 0 until result.size()) {
            val tdd = result.valueAt(i)
            if (tdd.ids.pumpType != PumpType.CACHE) {
                tdd.ids.pumpType = PumpType.CACHE
                persistenceLayer.insertOrUpdateCachedTotalDailyDose(tdd).subscribe()
            } else {
                aapsLogger.debug(LTag.APS, "Skipping storing TotalDailyDose for ${dateUtil.dateString(tdd.timestamp)}")
            }
        }
        if (result.size().toLong() == days || allowMissingDays) return result
        return null
    }

    override fun calculateToday(): TDD? {
        val startTime = MidnightTime.calc(dateUtil.now())
        val endTime = dateUtil.now()
        return calculateInterval(startTime, endTime, allowMissingData = true)
    }

    override fun calculateDaily(startHours: Long, endHours: Long): TDD? =
        calculateDaily(dateUtil.now(), startHours, endHours)

    override fun calculateDaily(timestamp: Long, startHours: Long, endHours: Long): TDD? {
        assert(startHours < 0)
        assert(endHours <= 0)
        val startTime = timestamp + T.hours(hour = startHours).msecs()
        val endTime = timestamp + T.hours(hour = endHours).msecs()
        return calculateInterval(startTime, endTime, allowMissingData = false)
    }

    override fun calculateInterval(startTime: Long, endTime: Long, allowMissingData: Boolean): TDD? {
        val startTimeAligned = startTime - startTime % (5 * 60 * 1000)
        val endTimeAligned = endTime - endTime % (5 * 60 * 1000)
        val tdd = TDD(timestamp = startTimeAligned)
        var tbrFound = false
        persistenceLayer.getBolusesFromTimeToTime(startTime, endTime, true)
            .filter { it.type != BS.Type.PRIMING }
            .forEach { t ->
                tdd.bolusAmount += t.amount
            }
        persistenceLayer.getCarbsFromTimeToTimeExpanded(startTime, endTime, true).forEach { t ->
            tdd.carbs += t.amount
        }
        // The temporary basals and extended boluses of the whole interval are read once. This used to be
        // one "active at" query per 5-minute step for each (getBasalData and getExtendedBolusActiveAt),
        // and for a step that nothing covers such a query walks the whole timestamp index. The cannula
        // usage on the Actions screen sums the whole cannula life (864 steps for 3 days) on every refresh,
        // and dynamic ISF sums 24 h on every loop run.
        //
        // Every valid entry that runs at some time of the interval is read, including overlapping ones, and
        // each step then picks the latest started one that is still running, as the "active at" queries
        // do (ties: highest id), so every step gets the same entry and the sums are the same.
        val temporaryBasals = processedTbrEbData.getTempBasalsIncludingConvertedExtended(startTimeAligned, endTimeAligned)
        val extendedBoluses =
            if (activePlugin.activePump.isFakingTempsByExtendedBoluses) null
            else RunningAt(
                persistenceLayer.getExtendedBolusesActiveBetweenTimeAndTime(startTimeAligned, endTimeAligned).sortedWith(compareBy({ it.timestamp }, { it.id })),
                { it.timestamp }, { it.duration }
            )
        val calculationStep = T.mins(5).msecs()
        for (t in startTimeAligned until endTimeAligned step calculationStep) {

            val profile = profileFunction.getProfile(t) ?: if (allowMissingData) continue else return null
            // What getBasalData(profile, t) returned. t is on a 5-minute boundary, so the full minute
            // getBasalData rounded it up to is t itself.
            val tbr = temporaryBasals.at(t)
            if (tbr != null) tbrFound = true
            val absoluteRate = tbr?.convertedToAbsolute(t, profile) ?: profile.getBasal(t)
            tdd.basalAmount += absoluteRate / 60.0 * 5.0

            val eb = extendedBoluses?.at(t)
            val absoluteEbRate = eb?.rate ?: 0.0
            tdd.bolusAmount += absoluteEbRate / 60.0 * 5.0
        }
        tdd.totalAmount = tdd.bolusAmount + tdd.basalAmount
        //aapsLogger.debug(LTag.CORE, tdd.toString())
        if (tdd.bolusAmount > 0 || tdd.basalAmount > 0 || tbrFound) return tdd
        return null
    }

    override fun averageTDD(tdds: LongSparseArray<TDD>?): AverageTDD? {
        val totalTdd = TDD(timestamp = dateUtil.now())
        tdds ?: return null
        if (tdds.size() == 0) return null
        var hasCarbs = true
        for (i in 0 until tdds.size()) {
            val tdd = tdds.valueAt(i)
            totalTdd.basalAmount += tdd.basalAmount
            totalTdd.bolusAmount += tdd.bolusAmount
            totalTdd.totalAmount += tdd.totalAmount
            totalTdd.carbs += tdd.carbs
            if (tdd.carbs == 0.0) hasCarbs = false
        }
        totalTdd.basalAmount /= tdds.size().toDouble()
        totalTdd.bolusAmount /= tdds.size().toDouble()
        totalTdd.totalAmount /= tdds.size().toDouble()
        totalTdd.carbs /= tdds.size().toDouble()
        return AverageTDD(data = totalTdd, allDaysHaveCarbs = hasCarbs)
    }

    override fun stats(context: Context): TableLayout {
        val tdds = calculate(7, allowMissingDays = true) ?: return TableLayout(context)
        val averageTdd = averageTDD(tdds)
        val todayTdd = calculateToday()
        val lp = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        return TableLayout(context).also { layout ->
            layout.layoutParams = TableLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layout.addView(TextView(context).apply {
                text = rh.gs(app.aaps.core.ui.R.string.tdd)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
                setTextAppearance(android.R.style.TextAppearance_Material_Medium)
            })
            layout.addView(TDD.toTableRowHeader(context, rh, includeCarbs = true))
            for (i in 0 until tdds.size()) layout.addView(tdds.valueAt(i).toTableRow(context, rh, dateUtil, includeCarbs = true))
            averageTdd?.let { averageTdd ->
                layout.addView(TextView(context).apply {
                    layoutParams = lp
                    text = rh.gs(app.aaps.core.ui.R.string.average)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextAppearance(android.R.style.TextAppearance_Material_Medium)
                })
                layout.addView(averageTdd.data.toTableRow(context, rh, tdds.size(), includeCarbs = true))
            }
            todayTdd?.let {
                layout.addView(TextView(context).apply {
                    text = rh.gs(app.aaps.core.interfaces.R.string.today)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextAppearance(android.R.style.TextAppearance_Material_Medium)
                })
                layout.addView(todayTdd.toTableRow(context, rh, dateUtil, includeCarbs = true))
            }
        }
    }
}
