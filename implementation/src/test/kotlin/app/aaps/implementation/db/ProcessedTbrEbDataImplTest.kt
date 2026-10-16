package app.aaps.implementation.db

import app.aaps.core.data.model.EB
import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.Pump
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The range version reads the database once and must answer every minute of the range exactly as the
 * per-time version does, which asks the database ("active at") every time.
 *
 * The fake database below answers like the real queries: "active at" is the latest started valid entry
 * with `timestamp <= t < timestamp + duration` (of a tie the highest id, as a descending walk of the
 * timestamp index gives it), "active between" is every entry with `timestamp <= to` and
 * `timestamp + duration > from`, latest started first.
 */
class ProcessedTbrEbDataImplTest {

    private val persistenceLayer = mock<PersistenceLayer>()
    private val activePlugin = mock<ActivePlugin>()
    private val profileFunction = mock<ProfileFunction>()
    private val pump = mock<Pump>()
    private val profile = mock<Profile>()
    private lateinit var sut: ProcessedTbrEbDataImpl

    private var temporaryBasals = listOf<TB>()
    private var extendedBoluses = listOf<EB>()

    private fun tb(id: Long, startMinute: Long, minutes: Long, rate: Double, absolute: Boolean = true) =
        TB(id = id, timestamp = startMinute * MINUTE, utcOffset = 0, type = TB.Type.NORMAL, isAbsolute = absolute, rate = rate, duration = minutes * MINUTE)

    private fun eb(id: Long, startMinute: Long, minutes: Long, amount: Double) =
        EB(id = id, timestamp = startMinute * MINUTE, utcOffset = 0, duration = minutes * MINUTE, amount = amount)

    @BeforeEach
    fun setUp() {
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(profileFunction.getProfile(any())).thenReturn(profile)
        whenever(profile.getBasal(any())).thenReturn(1.0)
        whenever(persistenceLayer.getTemporaryBasalActiveAt(any())).thenAnswer { invocation ->
            val t = invocation.getArgument<Long>(0)
            temporaryBasals.filter { it.timestamp <= t && it.timestamp + it.duration > t }.maxWithOrNull(compareBy({ it.timestamp }, { it.id }))
        }
        whenever(persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(any(), any())).thenAnswer { invocation ->
            val from = invocation.getArgument<Long>(0)
            val to = invocation.getArgument<Long>(1)
            // ORDER BY timestamp DESC, ties in no particular order (here: lowest id first)
            temporaryBasals.filter { it.timestamp <= to && it.timestamp + it.duration > from }.sortedWith(compareByDescending<TB> { it.timestamp }.thenBy { it.id })
        }
        whenever(persistenceLayer.getExtendedBolusActiveAt(any())).thenAnswer { invocation ->
            val t = invocation.getArgument<Long>(0)
            extendedBoluses.filter { it.timestamp <= t && it.timestamp + it.duration > t }.maxWithOrNull(compareBy({ it.timestamp }, { it.id }))
        }
        whenever(persistenceLayer.getExtendedBolusesActiveBetweenTimeAndTime(any(), any())).thenAnswer { invocation ->
            val from = invocation.getArgument<Long>(0)
            val to = invocation.getArgument<Long>(1)
            extendedBoluses.filter { it.timestamp <= to && it.timestamp + it.duration > from }.sortedWith(compareByDescending<EB> { it.timestamp }.thenBy { it.id })
        }
        sut = ProcessedTbrEbDataImpl(persistenceLayer, activePlugin, profileFunction)
    }

    /** Every minute of the range, the range answer equals the per-time answer. */
    private fun assertSameEveryMinute(fromMinute: Long, toMinute: Long) {
        val range = sut.getTempBasalsIncludingConvertedExtended(fromMinute * MINUTE, toMinute * MINUTE)
        for (minute in fromMinute..toMinute) {
            val t = minute * MINUTE
            val expected = sut.getTempBasalIncludingConvertedExtended(t)
            val actual = range.at(t)
            assertThat(actual?.rate).isEqualTo(expected?.rate)
            assertThat(actual?.timestamp).isEqualTo(expected?.timestamp)
            assertThat(actual?.duration).isEqualTo(expected?.duration)
            assertThat(actual?.isAbsolute).isEqualTo(expected?.isAbsolute)
            assertThat(actual?.type).isEqualTo(expected?.type)
        }
    }

    @Test
    fun `temporary basals - one running at the start, gaps, back to back, running past the end`() {
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(false)
        temporaryBasals = listOf(
            tb(1, startMinute = 0, minutes = 60, rate = 0.5),                     // running when the range starts at 30
            tb(2, startMinute = 70, minutes = 30, rate = 120.0, absolute = false), // after a gap, percent
            tb(3, startMinute = 100, minutes = 30, rate = 0.0),                   // right after the previous one
            tb(4, startMinute = 200, minutes = 120, rate = 1.5)                   // still running at the end of the range
        )
        extendedBoluses = listOf(eb(9, 140, 30, 1.0)) // ignored: the pump does not fake temps with them

        assertSameEveryMinute(30, 260)
    }

    @Test
    fun `a later started temporary basal wins over an older one still running, as ORDER BY timestamp DESC does`() {
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(false)
        temporaryBasals = listOf(
            tb(1, startMinute = 0, minutes = 120, rate = 0.5),
            tb(2, startMinute = 40, minutes = 20, rate = 3.0)
        )

        assertSameEveryMinute(10, 130)
        assertThat(sut.getTempBasalsIncludingConvertedExtended(10 * MINUTE, 130 * MINUTE).at(50 * MINUTE)?.rate).isEqualTo(3.0)
    }

    @Test
    fun `nothing running anywhere`() {
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(false)
        assertSameEveryMinute(0, 120)
    }

    /** A pump that fakes temporary basals with extended boluses: those count where no TBR runs. */
    @Test
    fun `extended boluses are converted where no temporary basal runs`() {
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(true)
        temporaryBasals = listOf(tb(1, startMinute = 50, minutes = 20, rate = 0.0))
        extendedBoluses = listOf(
            eb(5, startMinute = 0, minutes = 60, amount = 1.0),   // running when the range starts, partly under the TBR
            eb(6, startMinute = 90, minutes = 30, amount = 0.5)
        )

        assertSameEveryMinute(10, 130)
    }

    @Test
    fun `the range is read with one query per kind, not one per minute`() {
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(false)
        temporaryBasals = listOf(tb(1, startMinute = 0, minutes = 60, rate = 0.5))

        val range = sut.getTempBasalsIncludingConvertedExtended(0, 420 * MINUTE)
        for (minute in 0L..420L) range.at(minute * MINUTE)

        verify(persistenceLayer, never()).getTemporaryBasalActiveAt(any())
        verify(persistenceLayer, times(1)).getTemporaryBasalsActiveBetweenTimeAndTime(any(), any())
    }

    /**
     * Overlapping valid entries can be in the database (drivers that do not cut the running TBR, out of
     * order pump history): a later started, shorter TBR covers the start of the range inside an older,
     * longer one. After the short one ends the older one is still running and must be picked again.
     */
    @Test
    fun `an older temporary basal still running after a nested later one covering the start has ended`() {
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(false)
        temporaryBasals = listOf(
            tb(1, startMinute = 0, minutes = 120, rate = 0.0),
            tb(2, startMinute = 40, minutes = 30, rate = 200.0, absolute = false)
        )

        assertSameEveryMinute(50, 130)
        assertThat(sut.getTempBasalsIncludingConvertedExtended(50 * MINUTE, 130 * MINUTE).at(80 * MINUTE)?.rate).isEqualTo(0.0)
    }

    @Test
    fun `two temporary basals started at the same time as the range - the highest id wins, then the longer one`() {
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(false)
        temporaryBasals = listOf(
            tb(7, startMinute = 30, minutes = 20, rate = 2.0),
            tb(3, startMinute = 30, minutes = 90, rate = 0.5)
        )

        assertSameEveryMinute(30, 130)
        val range = sut.getTempBasalsIncludingConvertedExtended(30 * MINUTE, 130 * MINUTE)
        assertThat(range.at(30 * MINUTE)?.rate).isEqualTo(2.0)
        assertThat(range.at(60 * MINUTE)?.rate).isEqualTo(0.5)
    }

    @Test
    fun `extended boluses nested and converted - the older one is picked again after the later one ends`() {
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(true)
        extendedBoluses = listOf(
            eb(5, startMinute = 0, minutes = 120, amount = 2.0),
            eb(6, startMinute = 20, minutes = 30, amount = 1.0)
        )

        assertSameEveryMinute(30, 130)
    }

    private companion object {

        const val MINUTE = 60_000L
    }
}
