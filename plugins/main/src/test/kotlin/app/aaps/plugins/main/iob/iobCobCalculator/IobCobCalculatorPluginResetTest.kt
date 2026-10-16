package app.aaps.plugins.main.iob.iobCobCalculator

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * A full recalculation (preference or plugin change) drops the history change that is still waiting.
 * When that change was a new BG, the full recalculation has to carry `EventNewBG` as its cause, because
 * InvokeLoopWorker runs the loop only for a calculation caused by a new BG. Otherwise the loop would not
 * run for that BG and would wait for the next one.
 */
class IobCobCalculatorPluginResetTest {

    private val calculationWorkflow = mock<CalculationWorkflow>()
    private lateinit var sut: IobCobCalculatorPlugin
    private lateinit var scope: CoroutineScope

    private val bg = 1_700_000_000_000L
    private val preferenceChange = EventPreferenceChange("AutosensMax")

    @BeforeEach
    fun setUp() {
        sut = IobCobCalculatorPlugin(
            AAPSLoggerTest(), mock<AapsSchedulers>(), mock<RxBus>(), mock<Preferences>(), mock<ResourceHelper>(), mock<ProfileFunction>(),
            mock<ActivePlugin>(), mock<FabricPrivacy>(), mock<DateUtil>(), mock<PersistenceLayer>(), mock<OverviewData>(), calculationWorkflow,
            mock<DecimalFormatter>(), mock<ProcessedTbrEbData>()
        )
        // as after onStart. The change waits in the 5 s debounce, the test resets well within it.
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        sut.historyScope = scope
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun causeOfFullRecalculation(): Event? {
        val cause = argumentCaptor<Event>()
        verify(calculationWorkflow).runCalculation(eq(CalculationWorkflow.MAIN_CALCULATION), any(), any(), any(), any(), eq(true), cause.capture())
        return cause.firstValue
    }

    @Test
    fun `a dropped change for a new BG makes the full recalculation run the loop for it`() {
        sut.scheduleHistoryDataChange(bg - 60_000, reloadBgData = true, newestGlucoseValueTimestamp = bg)

        sut.resetDataAndRunCalculation("onEventPreferenceChange", preferenceChange)

        val cause = causeOfFullRecalculation()
        assertThat(cause).isInstanceOf(EventNewBG::class.java)
        assertThat((cause as EventNewBG).glucoseValueTimestamp).isEqualTo(bg)
    }

    @Test
    fun `a dropped change without a new BG keeps the cause of the full recalculation`() {
        sut.scheduleHistoryDataChange(bg - 60_000, reloadBgData = false, newestGlucoseValueTimestamp = null)

        sut.resetDataAndRunCalculation("onEventPreferenceChange", preferenceChange)

        assertThat(causeOfFullRecalculation()).isSameInstanceAs(preferenceChange)
    }

    @Test
    fun `with nothing waiting the cause of the full recalculation is kept`() {
        sut.resetDataAndRunCalculation("onEventPreferenceChange", preferenceChange)

        assertThat(causeOfFullRecalculation()).isSameInstanceAs(preferenceChange)
    }

    @Test
    fun `the dropped change does not run after the full recalculation`() {
        sut.scheduleHistoryDataChange(bg - 60_000, reloadBgData = true, newestGlucoseValueTimestamp = bg)
        sut.resetDataAndRunCalculation("onEventPreferenceChange", preferenceChange)

        // a second reset finds nothing waiting any more
        sut.resetDataAndRunCalculation("onEventPreferenceChange", preferenceChange)
        val causes = argumentCaptor<Event>()
        verify(calculationWorkflow, times(2))
            .runCalculation(any(), any(), any(), any(), any(), any(), causes.capture())
        assertThat(causes.secondValue).isSameInstanceAs(preferenceChange)
    }
}
