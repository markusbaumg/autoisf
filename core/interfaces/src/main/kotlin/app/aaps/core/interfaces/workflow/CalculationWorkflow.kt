package app.aaps.core.interfaces.workflow

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.HISTORY_CALCULATION
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION
import kotlin.time.Duration

interface CalculationWorkflow {
    companion object {

        const val MAIN_CALCULATION = "calculation"
        const val HISTORY_CALCULATION = "history_calculation"
        const val UPDATE_PREDICTIONS = "update_predictions"
        const val JOB = "job"
        const val PASS = "pass"
    }

    enum class ProgressData(val pass: Int, val percentOfTotal: Int) {
        DRAW_BG(0, 1),
        PREPARE_TREATMENTS_DATA(1, 2),
        PREPARE_BASAL_DATA(2, 6),
        PREPARE_TEMPORARY_TARGET_DATA(3, 5),
        PREPARE_RUNNING_MODE_DATA(4, 1),
        DRAW_TT(5, 1),
        IOB_COB_OREF(6, 77),
        PREPARE_IOB_AUTOSENS_DATA(7, 5),
        DRAW_IOB(8, 1),
        DRAW_FINAL(9, 1);

        fun finalPercent(progress: Int): Int {
            var total = 0
            for (i in entries) if (i.pass < pass) total += i.percentOfTotal
            total += (percentOfTotal.toDouble() * progress / 100.0).toInt()
            return total
        }
    }

    fun stopCalculation(job: String, from: String)

    /**
     * True while any work of [job] is enqueued, blocked or running. For [MAIN_CALCULATION] this
     * includes the final stage (InvokeLoopWorker, which runs the loop) of the chain.
     */
    fun isCalculationRunning(job: String): Boolean

    /**
     * Wait until no work of [job] is running or waiting to run, the loop stage included, or until [timeout].
     *
     * For a history change that arrives while a calculation runs: stopping that run throws away all
     * of its work, and when a full recalculation takes longer than the time between two glucose values
     * it then never finishes. Waiting for it and calculating the change afterwards always ends.
     *
     * Do not call it from a worker of [job] (e.g. the loop): it would wait for itself until [timeout].
     *
     * @param job [MAIN_CALCULATION] or [HISTORY_CALCULATION]
     * @return true when nothing is running, false when [timeout] ran out first
     */
    suspend fun awaitCalculationIdle(job: String, timeout: Duration): Boolean

    /**
     * Start calculation of data needed for displaying graphs
     *
     * @param job [MAIN_CALCULATION] or [HISTORY_CALCULATION]
     * @param iobCobCalculator different instance for [HistoryBrowseActivity]
     * @param overviewData different instance for [HistoryBrowseActivity]
     */
    fun runCalculation(
        job: String,
        iobCobCalculator: IobCobCalculator,
        overviewData: OverviewData,
        reason: String,
        end: Long,
        bgDataReload: Boolean,
        cause: Event?
    )

    /**
     * Update predictions in graph ofter new data from device status
     */
    fun runOnReceivedPredictions(overviewData: OverviewData)

    /**
     * Update treatments in graph ofter new therapy event
     */
    fun runOnEventTherapyEventChange(overviewData: OverviewData)

    /**
     * Update graph ofter scale change
     * There may be me necessary display larger time interval thus run new calculation
     */
    fun runOnScaleChanged(iobCobCalculator: IobCobCalculator, overviewData: OverviewData)
}