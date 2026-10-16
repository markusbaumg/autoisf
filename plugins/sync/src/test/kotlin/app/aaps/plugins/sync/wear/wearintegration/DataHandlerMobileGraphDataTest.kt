package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.pump.PumpStatusProvider
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.inject.Provider

/**
 * [DataHandlerMobile.buildGraphData]: the watch gets only the BG history it can draw.
 *
 * The bucketed table holds 24 h plus the longest DIA; no watch surface draws more than 5 h. The whole
 * table (~120 KB) went out on every resend, so the cut is what this locks in.
 */
class DataHandlerMobileGraphDataTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock lateinit var receiverStatusStore: ReceiverStatusStore
    @Mock lateinit var quickWizard: QuickWizard
    @Mock lateinit var trendCalculator: TrendCalculator
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var importExportPrefs: ImportExportPrefs
    @Mock lateinit var pumpStatusProvider: PumpStatusProvider
    @Mock lateinit var automation: Automation

    private lateinit var sut: DataHandlerMobile
    private val ads = mock<AutosensDataStore>()

    @BeforeEach fun prepare() {
        sut = DataHandlerMobile(
            aapsSchedulers, context, rxBus, aapsLogger, rh, preferences, config,
            iobCobCalculator, processedTbrEbData, smbGlucoseStatusProvider, profileFunction, profileUtil,
            loop, processedDeviceStatusData, receiverStatusStore, quickWizard, trendCalculator, dateUtil,
            constraintsChecker, uel, activePlugin, commandQueue, fabricPrivacy, uiInteraction,
            persistenceLayer, importExportPrefs, decimalFormatter, Provider<BolusWizard> { mock() }, pumpStatusProvider
        )
        sut.automation = automation
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
    }

    /** One bucket per 5 min from now back [hours] hours, newest first like the real table. */
    private fun bucketedTable(hours: Long): MutableList<InMemoryGlucoseValue> {
        val now = dateUtil.now()
        val step = T.mins(5).msecs()
        return (0 until T.hours(hours).msecs() / step).map { i ->
            InMemoryGlucoseValue(timestamp = now - i * step, value = 100.0 + i)
        }.toMutableList()
    }

    // The table is built before the stubbing line: bucketedTable() reads dateUtil, and a call on a spy
    // inside whenever(...).thenReturn(...) is an "unfinished stubbing" to Mockito.
    @Test fun `graph data holds only the last 6 h of a 34 h table`() {
        val table = bucketedTable(hours = 34)
        whenever(ads.getBucketedDataTableCopy()).thenReturn(table)

        val graph = sut.buildGraphData()!!

        val oldest = dateUtil.now() - T.hours(6).msecs()
        assertThat(graph.entries.all { it.timeStamp >= oldest }).isTrue()
        // 6 h of 5 min buckets, give or take the bucket on the boundary
        assertThat(graph.entries.size).isAtMost(6 * 12 + 1)
        assertThat(graph.entries.size).isAtLeast(6 * 12 - 1)
        // newest point unchanged, order kept
        assertThat(graph.entries.first().timeStamp).isEqualTo(table.first().timestamp)
        assertThat(graph.entries.first().sgv).isEqualTo(100.0)
    }

    @Test fun `a table shorter than 6 h goes out whole`() {
        val table = bucketedTable(hours = 2)
        whenever(ads.getBucketedDataTableCopy()).thenReturn(table)

        val graph = sut.buildGraphData()!!

        assertThat(graph.entries.size).isEqualTo(2 * 12)
    }

    @Test fun `no bucketed table means no graph message`() {
        whenever(ads.getBucketedDataTableCopy()).thenReturn(null)

        assertThat(sut.buildGraphData()).isNull()
    }
}
