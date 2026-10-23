package app.aaps.ui.widget

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.stats.DexcomTirCalculator
import app.aaps.core.interfaces.stats.TIR
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import androidx.collection.LongSparseArray
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Time in range, TDD, 14 day mean glucose and HbA1c for the widget, computed off the main thread and cached.
 * Read only: uses TirCalculator.calculate, DexcomTirCalculator.calculate (statistics screen HbA1c, no writes)
 * and TddCalculator.calculateDaily (calculateInterval, no writes).
 * Nothing here feeds the loop.
 */
@Singleton
class WidgetStatsCache @Inject constructor(
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val tirCalculator: TirCalculator,
    private val dexcomTirCalculator: DexcomTirCalculator,
    private val tddCalculator: TddCalculator,
    private val dateUtil: DateUtil,
    private val aapsSchedulers: AapsSchedulers,
    private val aapsLogger: AAPSLogger
) {

    data class Stats(
        val tir24h: Int? = null,
        val tir7d: Int? = null,
        val tir30d: Int? = null,
        val tdd24h: Double? = null,
        /** whole days from the first day with readings to the last midnight, null without data (meter labels "15 T") */
        val spanDays: Int? = null,
        /** statistics screen HbA1c window: 14 whole days excluding today */
        val meanMgdl: Double? = null,
        val hbA1c: Double? = null,
        /** days with readings within those 14 days */
        val longTermDays: Int = 0
    )

    companion object {

        const val RECOMPUTE_MS = 15 * 60 * 1000L
    }

    @Volatile var stats = Stats()
        private set

    @Volatile private var computedAt = 0L
    @Volatile private var daysComputedFor = 0L
    private val running = AtomicBoolean(false)

    /** Called on every widget refresh. Cheap when nothing is due; recomputes every 15 min and right after midnight. */
    fun refreshIfNeeded() {
        val now = dateUtil.now()
        val today = MidnightTime.calc(now)
        val dayDue = daysComputedFor != today
        val rollingDue = now - computedAt >= RECOMPUTE_MS || now < computedAt
        if (!dayDue && !rollingDue) return
        if (!running.compareAndSet(false, true)) return
        aapsSchedulers.io.scheduleDirect {
            try {
                compute(now, today)
            } catch (e: Exception) {
                aapsLogger.error(LTag.WIDGET, "Widget stats failed", e)
                // keep the last values, try again at the next 15 min cadence (not at every widget update)
                computedAt = now
                daysComputedFor = today
            } finally {
                running.set(false)
            }
        }
    }

    private fun compute(now: Long, today: Long) {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(WidgetLogic.rolling24hStart(now), now, true)
        val tir24 = WidgetLogic.countTir(readings.map { it.value }).displayPct()
        val tdd24 = tddCalculator.calculateDaily(-24, 0)?.totalAmount
        // 7 / 30 days are recomputed every cycle too, so backfilled or invalidated past readings show up like on the statistics screen.
        // One 30 day query; the 7 day meter uses its last 7 days (same days TirCalculator.calculate(7) returns).
        val perDay = tirCalculator.calculate(30, WidgetLogic.TIR_LOW_MGDL, WidgetLogic.TIR_HIGH_MGDL)
        val tir7 = pooled(perDay, MidnightTime.calcDaysBack(7))
        val tir30 = pooled(perDay, MidnightTime.calcDaysBack(30))
        val firstDay = (0 until perDay.size()).minOfOrNull { perDay.keyAt(it) }
        val spanDays = WidgetLogic.dataSpanDays(firstDay, today)
        // Mean glucose and HbA1c exactly as the statistics screen computes them (DexcomTirCalculator, 14 whole days)
        val dex = dexcomTirCalculator.calculate()
        val longTermStart = MidnightTime.calcDaysBack(14)
        val longTermDays = (0 until perDay.size()).count { perDay.keyAt(it) >= longTermStart }
        val new = Stats(tir24, tir7, tir30, tdd24, spanDays, dex.meanMgdl(), dex.hbA1cPercent(), longTermDays)
        val changed = new != stats
        stats = new
        computedAt = now
        daysComputedFor = today
        aapsLogger.debug(LTag.WIDGET, "Widget stats $new")
        if (changed) Widget.updateWidget(context, "WidgetStatsCache")
    }

    /** Same numbers as the statistics screen: whole calendar days excluding today, readings pooled */
    private fun pooled(perDay: LongSparseArray<TIR>, fromMidnight: Long): Int? {
        var total = WidgetLogic.TirCounts()
        for (i in 0 until perDay.size()) {
            if (perDay.keyAt(i) < fromMidnight) continue
            val t = perDay.valueAt(i)
            total += WidgetLogic.TirCounts(t.below, t.inRange, t.above)
        }
        return total.displayPct()
    }
}
