package app.aaps.plugins.smoothing

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.tests.TestBase
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * ConfigBuilderPlugin.exitApp() calls exitProcess(0) right after sending EventAppExit, so the UKF must
 * have its learned parameters on disk before rxBus.send() returns.
 */
class UkfSaveOnExitTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var sp: SP

    /** The plugin's io scheduler never runs: anything the plugin moves to io would not happen before exit. */
    private val pluginSchedulers = object : AapsSchedulers {
        val never = TestScheduler()
        override val main: Scheduler = never
        override val io: Scheduler = never
        override val cpu: Scheduler = never
        override val newThread: Scheduler = never
    }

    private lateinit var sut: UnscentedKalmanFilterPlugin

    @BeforeEach fun setup() {
        whenever(persistenceLayer.getTherapyEventDataFromTime(any(), eq(false))).thenReturn(Single.just(emptyList()))
        sut = UnscentedKalmanFilterPlugin(aapsLogger, rh, rxBus, pluginSchedulers, persistenceLayer, sp)
    }

    private fun setMeasurementsSinceSave(value: Int) {
        UnscentedKalmanFilterPlugin::class.java.getDeclaredField("measurementsSinceSave").apply { isAccessible = true }.setInt(sut, value)
    }

    @Test fun `unsaved parameters are committed inside rxBus send on exit`() {
        setMeasurementsSinceSave(37)
        rxBus.send(EventAppExit())
        inOrder(sp) {
            verify(sp).putDouble(eq("ukf_learned_r"), any<Double>())
            verify(sp).edit(eq(true), anyOrNull())
        }
    }

    @Test fun `nothing is written on exit when everything is already saved`() {
        setMeasurementsSinceSave(0)
        rxBus.send(EventAppExit())
        verify(sp, never()).edit(any(), anyOrNull())
        verify(sp, never()).putDouble(eq("ukf_learned_r"), any<Double>())
    }
}
