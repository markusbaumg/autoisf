package app.aaps.plugins.main.iob.iobCobCalculator

import app.aaps.core.data.iob.Iob
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The cached IOB of the past is kept when the BG data is loaded again: a new BG changes nothing in it.
 * Only values older than [IobCobCalculatorPlugin.CACHED_IOB_HOURS] are dropped, and [IobCobCalculatorPlugin.clearCache]
 * still drops everything.
 *
 * Each calculation reads the boluses once, so the number of those reads shows whether a value was
 * calculated or taken from the cache.
 *
 * 3.4 adaptations: the whole cache is still dropped on a BG reload when the DIA of the current profile,
 * the bolus snooze divisor or the time zone changed (they are inputs of the cached values here), and an
 * instance that does not receive history changes (history browser) still clears on every BG reload.
 */
class IobCobCalculatorPluginCacheTest {

    private val persistenceLayer = mock<PersistenceLayer>()
    private val activePlugin = mock<ActivePlugin>()
    private val dateUtil = mock<DateUtil>()
    private val preferences = mock<Preferences>()
    private val profileFunction = mock<ProfileFunction>()
    private val profile = mock<Profile>()
    private lateinit var sut: IobCobCalculatorPlugin
    private lateinit var originalTimeZone: TimeZone

    // calculateFromTreatmentsAndTemps compares with System.currentTimeMillis()
    private val now = System.currentTimeMillis()
    private val hour = 60 * 60 * 1000L

    @BeforeEach
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        whenever(dateUtil.now()).thenReturn(now)
        whenever(preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)).thenReturn(2.0)
        whenever(profile.dia).thenReturn(5.0)
        whenever(profileFunction.getProfile()).thenReturn(profile)
        val pump = mock<Pump>()
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(false)
        whenever(activePlugin.activePump).thenReturn(pump)
        // the future gets the expected zero temp added, which needs an insulin
        val insulin = mock<Insulin>()
        whenever(insulin.iobCalcForTreatment(any(), any(), any())).thenReturn(Iob())
        whenever(activePlugin.activeInsulin).thenReturn(insulin)
        whenever(persistenceLayer.getBolusesFromTime(any(), any())).thenReturn(Single.just(emptyList()))
        whenever(persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getExtendedBolusesStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        sut = IobCobCalculatorPlugin(
            AAPSLoggerTest(), mock<AapsSchedulers>(), mock<RxBus>(), preferences, mock<ResourceHelper>(), profileFunction, activePlugin,
            mock<FabricPrivacy>(), dateUtil, persistenceLayer, mock<OverviewData>(), mock<CalculationWorkflow>(), mock<DecimalFormatter>(),
            mock<ProcessedTbrEbData>()
        )
        // as after onStart: this instance receives the history changes that invalidate the cache
        sut.receivesHistoryChanges = true
        // first reload records what the cache is built with
        sut.bgDataReloaded()
    }

    @AfterEach
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun calculateAt(time: Long) = sut.calculateFromTreatmentsAndTemps(time, profile)

    private fun verifyCalculations(count: Int) = verify(persistenceLayer, times(count)).getBolusesFromTime(any(), any())

    @Test
    fun `a value of the past is calculated once and then taken from the cache`() {
        calculateAt(now - hour)
        calculateAt(now - hour)

        verifyCalculations(1)
    }

    @Test
    fun `a new BG keeps the cached values`() {
        calculateAt(now - hour)

        sut.bgDataReloaded()
        calculateAt(now - hour)

        verifyCalculations(1)
    }

    @Test
    fun `a new BG drops only the values older than the time the calculation looks back`() {
        val old = now - (IobCobCalculatorPlugin.CACHED_IOB_HOURS + 1) * hour
        val recent = now - hour
        calculateAt(old)
        calculateAt(recent)
        verifyCalculations(2)

        sut.bgDataReloaded()
        calculateAt(old)
        calculateAt(recent)

        // Only the old one again
        verifyCalculations(3)
    }

    @Test
    fun `clearCache still drops everything`() {
        calculateAt(now - hour)

        sut.clearCache()
        calculateAt(now - hour)

        verifyCalculations(2)
    }

    /**
     * A cached read must wait for a writer that holds the lock (upstream #5211). The read used to skip the
     * lock, so on a cache hit it went straight through while another thread was changing the table.
     */
    @Test
    fun `a cached read waits for a writer holding the lock`() {
        calculateAt(now - hour) // now cached
        val readerStarted = CountDownLatch(1)
        val readerDone = AtomicBoolean(false)
        val reader = Thread {
            readerStarted.countDown()
            calculateAt(now - hour)
            readerDone.set(true)
        }

        synchronized(sut.dataLock) {
            reader.start()
            assertThat(readerStarted.await(5, TimeUnit.SECONDS)).isTrue()
            // Give it every chance to get through; it cannot while the lock is held here.
            Thread.sleep(200)
            assertThat(readerDone.get()).isFalse()
        }

        reader.join(5_000)
        assertThat(readerDone.get()).isTrue()
        verifyCalculations(1) // and it was still a cache hit
    }

    /** "Now" and the future are never stored: the running insulin is not complete yet. */
    @Test
    fun `now is calculated every time`() {
        val future = now + hour
        calculateAt(future)
        sut.bgDataReloaded()
        calculateAt(future)

        verifyCalculations(2)
    }

    @Test
    fun `a new BG after a DIA change drops everything`() {
        calculateAt(now - hour)

        whenever(profile.dia).thenReturn(6.0)
        sut.bgDataReloaded()
        calculateAt(now - hour)

        verifyCalculations(2)
    }

    @Test
    fun `a new BG after a bolus snooze divisor change drops everything`() {
        calculateAt(now - hour)

        whenever(preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)).thenReturn(3.0)
        sut.bgDataReloaded()
        calculateAt(now - hour)

        verifyCalculations(2)
    }

    @Test
    fun `a new BG after a time zone change drops everything`() {
        calculateAt(now - hour)

        TimeZone.setDefault(TimeZone.getTimeZone(if (originalTimeZone.id == "Asia/Tokyo") "Europe/Vienna" else "Asia/Tokyo"))
        sut.bgDataReloaded()
        calculateAt(now - hour)

        verifyCalculations(2)
    }

    @Test
    fun `an instance without history change events still clears on every BG`() {
        sut.receivesHistoryChanges = false
        calculateAt(now - hour)

        sut.bgDataReloaded()
        calculateAt(now - hour)

        verifyCalculations(2)
    }
}
