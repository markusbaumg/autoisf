package app.aaps.implementation.queue.commands

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.keys.IntKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CommandSMBBolusTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer

    init {
        addInjector {
            if (it is CommandSMBBolus) {
                it.aapsLogger = aapsLogger
                it.rh = rh
                it.dateUtil = dateUtil
                it.activePlugin = activePlugin
                it.persistenceLayer = persistenceLayer
                it.preferences = preferences
                it.pumpEnactResultProvider = pumpEnactResultProvider
            }
        }
    }

    @BeforeEach
    fun setup() {
        testPumpPlugin = spy(testPumpPlugin)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)
        whenever(dateUtil.now()).thenReturn(now)
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(1)
    }

    private fun execute(lastBolusAgeMs: Long): PumpEnactResult {
        whenever(persistenceLayer.getNewestBolus()).thenReturn(BS(timestamp = now - lastBolusAgeMs, type = BS.Type.SMB, amount = 0.1))
        val dbi = DetailedBolusInfo().also {
            it.insulin = 0.1
            it.bolusType = BS.Type.SMB
            it.deliverAtTheLatest = System.currentTimeMillis()
        }
        val callback = object : Callback() {
            override fun run() {}
        }
        CommandSMBBolus(injector, dbi, callback).execute()
        return callback.result
    }

    @Test
    fun `SMB 57 s after last bolus is delivered with 1 min interval`() {
        val r = execute(57_000)
        assertThat(r.success).isTrue()
        verify(testPumpPlugin, times(1)).deliverTreatment(any())
    }

    @Test
    fun `SMB 54 s or less after last bolus is rejected with 1 min interval`() {
        val r = execute(54_000)
        assertThat(r.success).isFalse()
        assertThat(r.enacted).isFalse()
        verify(testPumpPlugin, never()).deliverTreatment(any())
    }

    @Test
    fun `SMB 2 s after last bolus is rejected`() {
        val r = execute(2_000)
        assertThat(r.success).isFalse()
        verify(testPumpPlugin, never()).deliverTreatment(any())
    }

    @Test
    fun `3 min interval rejects at 170 s and delivers at 175 s`() {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        assertThat(execute(170_000).success).isFalse()
        verify(testPumpPlugin, never()).deliverTreatment(any())
        assertThat(execute(175_000).success).isTrue()
        verify(testPumpPlugin, times(1)).deliverTreatment(any())
    }
}
