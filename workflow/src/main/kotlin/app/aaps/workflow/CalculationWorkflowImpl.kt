package app.aaps.workflow

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.workflow.CalculationWakeLock
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.JOB
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.PASS
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.UPDATE_PREDICTIONS
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.workflow.iob.IobCobOref1Worker
import app.aaps.workflow.iob.IobCobOrefWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Singleton
class CalculationWorkflowImpl @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val dataWorkerStorage: DataWorkerStorage,
    private val activePlugin: ActivePlugin
) : CalculationWorkflow {

    init {
        // Verify definition
        var sumPercent = 0
        for (pass in CalculationWorkflow.ProgressData.entries) sumPercent += pass.percentOfTotal
        require(sumPercent == 100)
    }

    /** What a graph rebuild needs, kept while the main calculation skips the graph (screen off). */
    private class StaleGraphInput(val iobCobCalculator: IobCobCalculator, val overviewData: OverviewData)

    private val staleGraphLock = Any()
    private var staleGraphInput: StaleGraphInput? = null // guarded by staleGraphLock
    private var screenOnReceiverRegistered = false // guarded by staleGraphLock

    private val wakeLock: CalculationWakeLock = AndroidCalculationWakeLock(context)

    /** The lock of the newest main calculation run, until its loop worker has ended. */
    private var loopWakeLock: LoopWakeLock? = null // guarded by staleGraphLock

    override fun stopCalculation(job: String, from: String) {
        aapsLogger.debug(LTag.WORKER, "Stopping calculation thread: $from")
        WorkManager.getInstance(context).cancelUniqueWork(job)
        val workStatus = WorkManager.getInstance(context).getWorkInfosForUniqueWork(job).get()
        while (workStatus.isNotEmpty() && workStatus[0].state == WorkInfo.State.RUNNING)
            SystemClock.sleep(100)
        aapsLogger.debug(LTag.WORKER, "Calculation thread stopped: $from")
    }

    override fun isCalculationRunning(job: String): Boolean =
        // The whole unique work, so the loop worker (still BLOCKED behind the IOB/COB calculation, or
        // running the loop) and the graph workers after it count as busy too. Finished works stay listed until
        // WorkManager prunes them, so only unfinished states count.
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(job).get().any { !it.state.isFinished }

    override suspend fun awaitCalculationIdle(job: String, timeout: Duration): Boolean {
        if (!isCalculationRunning(job)) return true
        aapsLogger.debug(LTag.WORKER, "Waiting for $job to finish")
        return awaitIdle(timeout, IDLE_POLL_MS) { isCalculationRunning(job) }
    }

    override fun keepAwake(timeout: Duration): CalculationWakeLock.Held? =
        try {
            wakeLock.acquire(timeout.inWholeMilliseconds)
        } catch (e: Exception) {
            // Without the lock the run can only come late, as before; it must not fail because of it.
            aapsLogger.error(LTag.WORKER, "Wake lock not taken", e)
            null
        }

    override fun runCalculation(
        job: String,
        iobCobCalculator: IobCobCalculator,
        overviewData: OverviewData,
        reason: String,
        end: Long,
        bgDataReload: Boolean,
        cause: Event?
    ) {
        aapsLogger.debug(LTag.WORKER, "Starting calculation worker: $reason to ${dateUtil.dateAndTimeAndSecondsString(end)}")

        val isMain = job == MAIN_CALCULATION
        synchronized(staleGraphLock) {
            // Decided and enqueued under the lock the screen-on receiver takes too, so a screen that
            // turns on in between cannot leave the graph stale: either this run draws it, or the
            // receiver sees the mark set here and appends a rebuild.
            // Without the screen-on receiver nothing would rebuild the graph: then it is drawn as before.
            val drawGraph = !isMain || isScreenOn() || !registerScreenOnReceiver()
            if (isMain) {
                staleGraphInput = if (drawGraph) null else StaleGraphInput(iobCobCalculator, overviewData)
                if (!drawGraph) aapsLogger.debug(LTag.WORKER, "Screen off: overview graph data skipped, rebuilt when the screen turns on")
            }
            // The system holds a wake lock for a worker only while it runs, not in the gaps between the
            // workers of the chain. Keep the device awake from here until the loop worker has ended, so
            // a new BG is looped promptly with the screen off. REPLACE below drops the previous run: a
            // loop worker of it that has not started never will, so its lock is given back here.
            val awake = if (isMain) keepAwake(LOOP_WAKE_LOCK_TIMEOUT)?.let { LoopWakeLock(it) } else null
            if (isMain) {
                loopWakeLock?.releaseIfUnclaimed()
                loopWakeLock = awake
            }
            val steps = calculationSteps(isMain, drawGraph)
            val requests = steps.map { request(it, job, iobCobCalculator, overviewData, reason, end, bgDataReload, cause, awake) }
            WorkManager.getInstance(context)
                .beginUniqueWork(job, ExistingWorkPolicy.REPLACE, requests.first())
                .thenEach(requests.drop(1))
                .enqueue()
        }
    }

    /**
     * Builds the overview graph data the main calculation skipped while the screen was off.
     * Graph workers only: no IOB/COB calculation, no loop. Appended to the main calculation, so it
     * waits for a running one; a new main calculation replaces it and, with the screen on, draws the
     * graph itself.
     */
    private fun rebuildStaleGraph() {
        synchronized(staleGraphLock) {
            val input = staleGraphInput ?: return
            staleGraphInput = null
            aapsLogger.debug(LTag.WORKER, "Screen on: rebuilding overview graph data")
            val requests = graphSteps(isMain = true).map {
                request(it, MAIN_CALCULATION, input.iobCobCalculator, input.overviewData, "graph rebuild", 0, false, null)
            }
            WorkManager.getInstance(context)
                .beginUniqueWork(MAIN_CALCULATION, ExistingWorkPolicy.APPEND_OR_REPLACE, requests.first())
                .thenEach(requests.drop(1))
                .enqueue()
        }
    }

    private fun isScreenOn(): Boolean =
        // Unknown counts as on: the graph is then drawn as before.
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    /** Registers the screen-on receiver once. True when it is registered. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenOnReceiver(): Boolean {
        if (screenOnReceiverRegistered) return true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) rebuildStaleGraph()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        try {
            val appContext = context.applicationContext ?: context
            // SCREEN_ON is a protected system broadcast: delivered to a not exported receiver too.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                appContext.registerReceiver(receiver, filter)
            screenOnReceiverRegistered = true
        } catch (e: Exception) {
            aapsLogger.error(LTag.WORKER, "Screen-on receiver not registered", e)
        }
        return screenOnReceiverRegistered
    }

    private fun request(
        step: Step,
        job: String,
        iobCobCalculator: IobCobCalculator,
        overviewData: OverviewData,
        reason: String,
        end: Long,
        bgDataReload: Boolean,
        cause: Event?,
        awake: LoopWakeLock? = null
    ): OneTimeWorkRequest = when (step) {
        Step.LOAD_BG_DATA                ->
            if (bgDataReload) OneTimeWorkRequest.Builder(LoadBgDataWorker::class.java).setInputData(dataWorkerStorage.storeInputData(LoadBgDataWorker.LoadBgData(iobCobCalculator, end))).build()
            else OneTimeWorkRequest.Builder(DummyWorker::class.java).build()

        Step.IOB_COB                     ->
            if (activePlugin.activeSensitivity.isOref1)
                OneTimeWorkRequest.Builder(IobCobOref1Worker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(IobCobOref1Worker.IobCobOref1WorkerData(iobCobCalculator, reason, end, job == MAIN_CALCULATION, cause)))
                    .build()
            else
                OneTimeWorkRequest.Builder(IobCobOrefWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(IobCobOrefWorker.IobCobOrefWorkerData(iobCobCalculator, reason, end, job == MAIN_CALCULATION, cause)))
                    .build()

        Step.UPDATE_IOB_COB_SENS         -> OneTimeWorkRequest.Builder(UpdateIobCobSensWorker::class.java).build()

        Step.INVOKE_LOOP                 ->
            OneTimeWorkRequest.Builder(InvokeLoopWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(InvokeLoopWorker.InvokeLoopData(cause, awake)))
                .build()

        Step.UPDATE_WIDGET               -> OneTimeWorkRequest.Builder(UpdateWidgetWorker::class.java).build()

        Step.PREPARE_PREDICTIONS         ->
            OneTimeWorkRequest.Builder(PreparePredictionsWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(PreparePredictionsWorker.PreparePredictionsData(overviewData)))
                .build()

        Step.PREPARE_BUCKETED_DATA       ->
            OneTimeWorkRequest.Builder(PrepareBucketedDataWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(PrepareBucketedDataWorker.PrepareBucketedData(iobCobCalculator, overviewData)))
                .build()

        Step.PREPARE_BG_DATA             ->
            OneTimeWorkRequest.Builder(PrepareBgDataWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(PrepareBgDataWorker.PrepareBgData(iobCobCalculator, overviewData)))
                .build()

        Step.PREPARE_TREATMENTS_DATA     ->
            OneTimeWorkRequest.Builder(PrepareTreatmentsDataWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(PrepareTreatmentsDataWorker.PrepareTreatmentsData(overviewData)))
                .build()

        Step.PREPARE_BASAL_DATA          ->
            OneTimeWorkRequest.Builder(PrepareBasalDataWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(PrepareBasalDataWorker.PrepareBasalData(iobCobCalculator, overviewData)))
                .build()

        Step.PREPARE_TEMPORARY_TARGET_DATA ->
            OneTimeWorkRequest.Builder(PrepareTemporaryTargetDataWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(PrepareTemporaryTargetDataWorker.PrepareTemporaryTargetData(overviewData)))
                .build()

        Step.PREPARE_RUNNING_MODE_DATA   ->
            OneTimeWorkRequest.Builder(PrepareRunningModeDataWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(PrepareRunningModeDataWorker.PrepareRunningModeData(overviewData)))
                .build()

        Step.PREPARE_IOB_AUTOSENS_DATA   ->
            OneTimeWorkRequest.Builder(PrepareIobAutosensGraphDataWorker::class.java)
                .setInputData(dataWorkerStorage.storeInputData(PrepareIobAutosensGraphDataWorker.PrepareIobAutosensData(iobCobCalculator, overviewData)))
                .build()

        Step.DRAW_BG                     -> drawRequest(job, CalculationWorkflow.ProgressData.DRAW_BG)
        Step.DRAW_TT                     -> drawRequest(job, CalculationWorkflow.ProgressData.DRAW_TT)
        Step.DRAW_IOB                    -> drawRequest(job, CalculationWorkflow.ProgressData.DRAW_IOB)
        Step.DRAW_FINAL                  -> drawRequest(job, CalculationWorkflow.ProgressData.DRAW_FINAL)
    }

    private fun drawRequest(job: String, pass: CalculationWorkflow.ProgressData): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
            .setInputData(Data.Builder().putString(JOB, job).putInt(PASS, pass.pass).build())
            .build()

    override fun runOnReceivedPredictions(
        overviewData: OverviewData
    ) {
        aapsLogger.debug(LTag.WORKER, "Starting updateReceivedPredictions worker")

        WorkManager.getInstance(context)
            .beginUniqueWork(
                UPDATE_PREDICTIONS, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(PreparePredictionsWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PreparePredictionsWorker.PreparePredictionsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, UPDATE_PREDICTIONS).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()
    }

    override fun runOnEventTherapyEventChange(overviewData: OverviewData) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                MAIN_CALCULATION, ExistingWorkPolicy.APPEND,
                OneTimeWorkRequest.Builder(PrepareTreatmentsDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareTreatmentsDataWorker.PrepareTreatmentsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()

    }

    override fun runOnScaleChanged(iobCobCalculator: IobCobCalculator, overviewData: OverviewData) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                MAIN_CALCULATION, ExistingWorkPolicy.APPEND,
                OneTimeWorkRequest.Builder(PrepareBucketedDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBucketedDataWorker.PrepareBucketedData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareBgDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBgDataWorker.PrepareBgData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()
    }

    /** One stage of a calculation chain; each maps to one worker. */
    internal enum class Step {
        LOAD_BG_DATA,
        IOB_COB,
        UPDATE_IOB_COB_SENS,
        INVOKE_LOOP,
        UPDATE_WIDGET,
        PREPARE_PREDICTIONS,
        PREPARE_BUCKETED_DATA,
        PREPARE_BG_DATA,
        DRAW_BG,
        PREPARE_TREATMENTS_DATA,
        PREPARE_BASAL_DATA,
        PREPARE_TEMPORARY_TARGET_DATA,
        PREPARE_RUNNING_MODE_DATA,
        DRAW_TT,
        PREPARE_IOB_AUTOSENS_DATA,
        DRAW_IOB,
        DRAW_FINAL
    }

    companion object {

        /** The workers that only build overview graph data. Nothing the loop reads comes from them. */
        internal val GRAPH_ONLY_STEPS = setOf(
            Step.PREPARE_PREDICTIONS, Step.PREPARE_BUCKETED_DATA, Step.PREPARE_BG_DATA, Step.DRAW_BG,
            Step.PREPARE_TREATMENTS_DATA, Step.PREPARE_BASAL_DATA, Step.PREPARE_TEMPORARY_TARGET_DATA,
            Step.PREPARE_RUNNING_MODE_DATA, Step.DRAW_TT, Step.PREPARE_IOB_AUTOSENS_DATA, Step.DRAW_IOB
        )

        /**
         * The chain of one calculation run, in order.
         *
         * The loop runs right after the IOB/COB/autosens calculation: the graph workers only read the
         * database and the autosens data and write [OverviewData], which the loop does not read, so it
         * does not have to wait for them. (Before, it waited for all of them, a few seconds per BG.)
         *
         * [drawGraph] false (main calculation, screen off) leaves the graph-only workers out. The final
         * draw stays: it ends the progress bar at 100 %.
         */
        internal fun calculationSteps(isMain: Boolean, drawGraph: Boolean): List<Step> = buildList {
            add(Step.LOAD_BG_DATA)
            add(Step.IOB_COB)
            add(Step.UPDATE_IOB_COB_SENS)
            if (isMain) {
                add(Step.INVOKE_LOOP)
                add(Step.UPDATE_WIDGET)
            }
            if (drawGraph) addAll(graphSteps(isMain).dropLast(1))
            add(Step.DRAW_FINAL)
        }

        /**
         * The overview graph workers, ending with the final draw. Predictions go first: they set the
         * time range the others fill (after the loop, so with its newest predictions).
         */
        internal fun graphSteps(isMain: Boolean): List<Step> = buildList {
            if (isMain) add(Step.PREPARE_PREDICTIONS)
            add(Step.PREPARE_BUCKETED_DATA)
            add(Step.PREPARE_BG_DATA)
            add(Step.DRAW_BG)
            add(Step.PREPARE_TREATMENTS_DATA)
            add(Step.PREPARE_BASAL_DATA)
            add(Step.PREPARE_TEMPORARY_TARGET_DATA)
            add(Step.PREPARE_RUNNING_MODE_DATA)
            add(Step.DRAW_TT)
            add(Step.PREPARE_IOB_AUTOSENS_DATA)
            if (isMain) add(Step.DRAW_IOB)
            add(Step.DRAW_FINAL)
        }

        /** Chains [works] one after the other (WorkContinuation.then(list) would run them in parallel). */
        private fun WorkContinuation.thenEach(works: List<OneTimeWorkRequest>): WorkContinuation =
            works.fold(this) { continuation, work -> continuation.then(work) }

        /** awaitCalculationIdle waits for minutes, not seconds, so it asks WorkManager only twice a second. */
        private const val IDLE_POLL_MS = 500L

        /**
         * Upper bound of the lock from enqueueing a main calculation until its loop worker ended. A
         * normal run takes seconds; the timeout only matters for a chain that fails or hangs.
         */
        private val LOOP_WAKE_LOCK_TIMEOUT = 3.minutes

        /**
         * Polls [busy] every [pollMs] until it answers false (true is returned) or [timeout] runs out
         * (false is returned).
         */
        internal suspend fun awaitIdle(timeout: Duration, pollMs: Long, busy: () -> Boolean): Boolean =
            withTimeoutOrNull(timeout) {
                while (busy()) delay(pollMs)
                true
            } ?: false
    }
}
