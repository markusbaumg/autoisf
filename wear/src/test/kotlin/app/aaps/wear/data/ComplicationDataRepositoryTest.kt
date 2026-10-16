package app.aaps.wear.data

import android.content.Context
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import java.io.File

class ComplicationDataRepositoryTest {

    @Mock lateinit var context: Context
    @TempDir lateinit var filesDir: File

    private lateinit var repository: ComplicationDataRepository

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(filesDir)
        repository = ComplicationDataRepository(context, AAPSLoggerTest())
    }

    private fun bg(sgv: Double, timeStamp: Long = 1_000L, dataset: Int = 0) = EventData.SingleBg(
        dataset = dataset, timeStamp = timeStamp, sgvString = sgv.toString(), glucoseUnits = "mg/dl", slopeArrow = "→",
        delta = "+1", deltaDetailed = "+1.0", avgDelta = "+1", avgDeltaDetailed = "+1.0", sgvLevel = 0L, sgv = sgv, high = 180.0, low = 70.0, color = 0
    )

    private fun status(iob: String, dataset: Int = 0) = EventData.Status(
        dataset = dataset, externalStatus = "ok", iobSum = iob, iobDetail = iob, cob = "10g", currentBasal = "0.5U/h", battery = "80", rigBattery = "90",
        openApsStatus = 5_000L, bgi = "-1", batteryLevel = 1, patientName = "", tempTarget = "--", tempTargetLevel = 0, reservoirString = "100U", reservoir = 100.0, reservoirLevel = 1
    )

    private fun graph(vararg sgv: Double) = EventData.GraphData(ArrayList(sgv.mapIndexed { i, v -> bg(v, timeStamp = 1_000L + i) }))

    @Test
    fun `an unchanged payload is not stored again`() = runBlocking {
        assertThat(repository.updateGraphData(graph(100.0, 110.0))).isTrue()
        val stored = repository.complicationData.first()

        // equal content, new instance
        assertThat(repository.updateGraphData(graph(100.0, 110.0))).isFalse()
        val again = repository.complicationData.first()
        assertThat(again).isEqualTo(stored)
        // no write happened: the heartbeat timestamp (refreshed at most once a minute) is untouched
        assertThat(again.lastUpdateTimestamp).isEqualTo(stored.lastUpdateTimestamp)

        assertThat(repository.updateGraphData(graph(100.0, 110.0, 120.0))).isTrue()
        assertThat(repository.complicationData.first().graphData.entries).hasSize(3)
    }

    @Test
    fun `each part is compared on its own and leaves the other parts alone`() = runBlocking {
        repository.updateGraphData(graph(100.0))
        repository.updateTreatmentData(EventData.TreatmentData(arrayListOf(), arrayListOf(), arrayListOf(), arrayListOf()))
        val before = repository.complicationData.first()

        assertThat(repository.updateBgData(bg(120.0))).isTrue()
        assertThat(repository.updateBgData(bg(120.0))).isFalse()
        assertThat(repository.updateStatusData(status("1.2U"))).isTrue()
        assertThat(repository.updateStatusData(status("1.2U"))).isFalse()
        assertThat(repository.updateStatusData(status("1.3U"))).isTrue()
        assertThat(repository.updateTreatmentData(EventData.TreatmentData(arrayListOf(), arrayListOf(), arrayListOf(), arrayListOf()))).isFalse()

        val after = repository.complicationData.first()
        assertThat(after.graphData).isEqualTo(before.graphData)
        assertThat(after.treatmentData).isEqualTo(before.treatmentData)
        assertThat(after.bgData.sgv).isEqualTo(120.0)
        assertThat(after.statusData.iobSum).isEqualTo("1.3U")
        assertThat(after.lastUpdateTimestamp).isAtLeast(before.lastUpdateTimestamp)
    }

    @Test
    fun `datasets are stored separately`() = runBlocking {
        assertThat(repository.updateBgData(bg(100.0, dataset = 0))).isTrue()
        assertThat(repository.updateBgData(bg(200.0, dataset = 1))).isTrue()
        assertThat(repository.updateBgData(bg(200.0, dataset = 1))).isFalse()
        assertThat(repository.updateStatusData(status("2U", dataset = 2))).isTrue()
        assertThat(repository.updateBgData(bg(100.0, dataset = 7))).isFalse()
        val data = repository.complicationData.first()
        assertThat(data.bgData.sgv).isEqualTo(100.0)
        assertThat(data.bgData1.sgv).isEqualTo(200.0)
        assertThat(data.statusData2.iobSum).isEqualTo("2U")
    }

    @Test
    fun `collectors are only woken for a change`() = runBlocking {
        repository.updateGraphData(graph(100.0))
        val emissions = ArrayList<ComplicationData>()
        val collector = launch(Dispatchers.Default) { repository.complicationData.collect { emissions.add(it) } }
        delay(200)
        val initial = emissions.size

        repeat(5) { repository.updateGraphData(graph(100.0)) }
        repeat(5) { repository.updateStatusData(status("1U")) }
        delay(200)
        // one emission for the first status, none for the four identical statuses and the five identical graphs
        assertThat(emissions.size - initial).isEqualTo(1)

        repository.updateGraphData(graph(100.0, 101.0))
        delay(200)
        assertThat(emissions.size - initial).isEqualTo(2)
        collector.cancel()
    }
}
