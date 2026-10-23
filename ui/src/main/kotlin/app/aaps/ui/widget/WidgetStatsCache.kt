package app.aaps.ui.widget

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Time in range and TDD for the widget, computed off the main thread and cached.
 * Read only: uses TirCalculator.calculate (no writes) and TddCalculator.calculateDaily (calculateInterval, no writes).
 * Nothing here feeds the loop.
 */
@Singleton
class WidgetStatsCache @Inject constructor(
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val tirCalculator: TirCalculator,
    private val tddCalculator: TddCalculator,
    private val dateUtil: DateUtil,
    private val aapsSchedulers: AapsSchedulers,
    private val aapsLogger: AAPSLogger
) {

    data class Stats(
        val tir24h: Int? = null,
        val tir7d: Int? = null,
        val tir30d: Int? = null,
        val tdd24h: Double? = null
    )

    companion object {

        const val RECOMPUTE_MS = 15 * 60 * 1000L
    }

    @Volatile var stats = Stats()
        private set

    @Volatile private var computedAt = 0L
    @Volatile private var daysComputedFor = 0L
    private val running = AtomicBoolean(false)

    /** Called on every widget refresh. Cheap when nothing is due. */
    fun refreshIfNeeded() {
        val now = dateUtil.now()
        val today = MidnightTime.calc(now)
        val dayDue = daysComputedFor != today
        val rollingDue = now - computedAt >= RECOMPUTE_MS || now < computedAt
        if (!dayDue && !rollingDue) return
        if (!running.compareAndSet(false, true)) return
        aapsSchedulers.io.scheduleDirect {
            try {
                compute(now, today, dayDue)
            } catch (e: Exception) {
                aapsLogger.error(LTag.WIDGET, "Widget stats failed", e)
                // keep the last values, try again at the next cadence
                computedAt = now
            } finally {
                running.set(false)
            }
        }
    }

    private fun compute(now: Long, today: Long, dayDue: Boolean) {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(WidgetLogic.rolling24hStart(now), now, true)
        val tir24 = WidgetLogic.countTir(readings.map { it.value }).displayPct()
        val tdd24 = tddCalculator.calculateDaily(-24, 0)?.totalAmount
        var tir7 = stats.tir7d
        var tir30 = stats.tir30d
        if (dayDue) {
            tir7 = pooled(7)
            tir30 = pooled(30)
        }
        val new = Stats(tir24, tir7, tir30, tdd24)
        val changed = new != stats
        stats = new
        computedAt = now
        if (dayDue) daysComputedFor = today
        aapsLogger.debug(LTag.WIDGET, "Widget stats $new")
        if (changed) Widget.updateWidget(context, "WidgetStatsCache")
    }

    /** Same numbers as the statistics screen: whole calendar days excluding today, readings pooled */
    private fun pooled(days: Long): Int? {
        val perDay = tirCalculator.calculate(days, WidgetLogic.TIR_LOW_MGDL, WidgetLogic.TIR_HIGH_MGDL)
        var total = WidgetLogic.TirCounts()
        for (i in 0 until perDay.size()) {
            val t = perDay.valueAt(i)
            total += WidgetLogic.TirCounts(t.below, t.inRange, t.above)
        }
        return total.displayPct()
    }
}
