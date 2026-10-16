package app.aaps.workflow

import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.workflow.CalculationWorkflowImpl.Companion.GRAPH_ONLY_STEPS
import app.aaps.workflow.CalculationWorkflowImpl.Companion.calculationSteps
import app.aaps.workflow.CalculationWorkflowImpl.Companion.graphSteps
import app.aaps.workflow.CalculationWorkflowImpl.Step
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The order and content of the calculation chains [CalculationWorkflowImpl] enqueues. */
class CalculationWorkflowStepsTest {

    @Test
    fun `main with graph runs the loop right after IOB COB and before every graph worker`() {
        val steps = calculationSteps(isMain = true, drawGraph = true)
        assertThat(steps.take(5)).containsExactly(
            Step.LOAD_BG_DATA, Step.IOB_COB, Step.UPDATE_IOB_COB_SENS, Step.INVOKE_LOOP, Step.UPDATE_WIDGET
        ).inOrder()
        val loop = steps.indexOf(Step.INVOKE_LOOP)
        steps.forEachIndexed { i, step -> if (step in GRAPH_ONLY_STEPS) assertThat(i).isGreaterThan(loop) }
        assertThat(steps.last()).isEqualTo(Step.DRAW_FINAL)
    }

    @Test
    fun `main with graph still has every worker of before, each once`() {
        val steps = calculationSteps(isMain = true, drawGraph = true)
        assertThat(steps).containsExactlyElementsIn(Step.entries)
    }

    @Test
    fun `predictions set the graph range before the graph data is built`() {
        val steps = calculationSteps(isMain = true, drawGraph = true)
        val predictions = steps.indexOf(Step.PREPARE_PREDICTIONS)
        (GRAPH_ONLY_STEPS - Step.PREPARE_PREDICTIONS).forEach { assertThat(steps.indexOf(it)).isGreaterThan(predictions) }
    }

    @Test
    fun `main with screen off skips only graph workers and keeps loop, widget and final draw`() {
        val steps = calculationSteps(isMain = true, drawGraph = false)
        assertThat(steps).containsExactly(
            Step.LOAD_BG_DATA, Step.IOB_COB, Step.UPDATE_IOB_COB_SENS, Step.INVOKE_LOOP, Step.UPDATE_WIDGET, Step.DRAW_FINAL
        ).inOrder()
        // The loop part is exactly the same as with the graph.
        assertThat(steps.filterNot { it in GRAPH_ONLY_STEPS })
            .isEqualTo(calculationSteps(isMain = true, drawGraph = true).filterNot { it in GRAPH_ONLY_STEPS })
    }

    @Test
    fun `history calculation never runs the loop, widget or predictions`() {
        val steps = calculationSteps(isMain = false, drawGraph = true)
        assertThat(steps).containsNoneOf(Step.INVOKE_LOOP, Step.UPDATE_WIDGET, Step.PREPARE_PREDICTIONS, Step.DRAW_IOB)
        assertThat(steps).containsAtLeast(
            Step.LOAD_BG_DATA, Step.IOB_COB, Step.UPDATE_IOB_COB_SENS, Step.PREPARE_BUCKETED_DATA, Step.PREPARE_BG_DATA,
            Step.PREPARE_TREATMENTS_DATA, Step.PREPARE_BASAL_DATA, Step.PREPARE_TEMPORARY_TARGET_DATA,
            Step.PREPARE_RUNNING_MODE_DATA, Step.PREPARE_IOB_AUTOSENS_DATA, Step.DRAW_FINAL
        ).inOrder()
    }

    @Test
    fun `graph rebuild has no IOB COB calculation and no loop`() {
        val steps = graphSteps(isMain = true)
        assertThat(steps).containsNoneOf(Step.LOAD_BG_DATA, Step.IOB_COB, Step.UPDATE_IOB_COB_SENS, Step.INVOKE_LOOP, Step.UPDATE_WIDGET)
        assertThat(steps).containsExactlyElementsIn(GRAPH_ONLY_STEPS + Step.DRAW_FINAL)
        assertThat(steps.first()).isEqualTo(Step.PREPARE_PREDICTIONS)
        assertThat(steps.last()).isEqualTo(Step.DRAW_FINAL)
    }

    @Test
    fun `progress passes follow the chain order and end at 100 percent`() {
        val passes = CalculationWorkflow.ProgressData.entries.sortedBy { it.pass }
        assertThat(passes.first()).isEqualTo(CalculationWorkflow.ProgressData.IOB_COB_OREF)
        assertThat(CalculationWorkflow.ProgressData.DRAW_FINAL.finalPercent(100)).isEqualTo(100)
        // Monotonic over the chain: each pass starts where the one before ended.
        var last = 0
        passes.forEach {
            assertThat(it.finalPercent(0)).isAtLeast(last)
            last = it.finalPercent(100)
        }
    }
}
