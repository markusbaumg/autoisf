package app.aaps.database.persistence

import android.content.Context
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.database.AppRepository
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.interfaces.DBEntry
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CompatDBHelperTest {

    private val now = 1656358822000L
    private val changes = PublishSubject.create<List<DBEntry>>()
    private val sent = mutableListOf<Event>()

    private val repository: AppRepository = mock()
    private val ads: AutosensDataStore = mock()
    private val iobCobCalculator: IobCobCalculator = mock()
    private val rxBus: RxBus = mock()
    private lateinit var disposable: Disposable

    @BeforeEach
    fun setup() {
        whenever(repository.changeObservable()).thenReturn(changes)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        doAnswer { sent.add(it.getArgument(0)); Unit }.whenever(rxBus).send(any())
        val sut = CompatDBHelper(mock<AAPSLogger>(), repository, rxBus, mock<Context>(), mock<UiInteraction>()) { iobCobCalculator }
        disposable = sut.dbChangeDisposable()
        sent.clear()
    }

    @AfterEach
    fun tearDown() {
        disposable.dispose()
    }

    private fun reading(id: Long, timestamp: Long) =
        GlucoseValue(
            id = id, timestamp = timestamp, value = 100.0, raw = 0.0, noise = 0.0,
            trendArrow = GlucoseValue.TrendArrow.FLAT, sourceSensor = GlucoseValue.SourceSensor.UNKNOWN
        )

    private fun holds(vararg ids: Long) {
        whenever(ads.holdsSameData(any())).thenReturn(false)
        ids.forEach { id -> whenever(ads.holdsSameData(argThat<GV> { this.id == id })).thenReturn(true) }
    }

    @Test
    fun newReadingRecalculates() {
        holds()
        changes.onNext(listOf(reading(1, now)))

        val history = sent.filterIsInstance<EventNewHistoryData>().single()
        assertThat(history.oldDataTimestamp).isEqualTo(now)
        assertThat(history.reloadBgData).isTrue()
        assertThat(history.newestGlucoseValueTimestamp).isEqualTo(now)
    }

    @Test
    fun idOnlyUpdateDoesNotRecalculate() {
        holds(1)
        changes.onNext(listOf(reading(1, now)))

        assertThat(sent.filterIsInstance<EventNewHistoryData>()).isEmpty()
        // UI notification is still sent
        assertThat(sent.filterIsInstance<EventNewBG>()).hasSize(1)
    }

    @Test
    fun newReadingIsNotHiddenBehindUnchangedOlderOne() {
        // older reading only got its NS id, newer one is new: invalidation must start at the new one
        holds(1)
        changes.onNext(listOf(reading(1, now - 300_000), reading(2, now)))

        val history = sent.filterIsInstance<EventNewHistoryData>().single()
        assertThat(history.oldDataTimestamp).isEqualTo(now)
    }

    @Test
    fun failureInLookupRecalculates() {
        whenever(ads.holdsSameData(any())).thenThrow(RuntimeException("boom"))
        changes.onNext(listOf(reading(1, now)))

        assertThat(sent.filterIsInstance<EventNewHistoryData>()).hasSize(1)
        // subscription survives
        sent.clear()
        changes.onNext(listOf(reading(2, now + 60_000)))
        assertThat(sent.filterIsInstance<EventNewHistoryData>()).hasSize(1)
    }
}
