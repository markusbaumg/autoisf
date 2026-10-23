package app.aaps.pump.omnipod.dash

import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.state.OmnipodDashPodStateManager
import app.aaps.pump.omnipod.dash.driver.OmnipodDashManager
import app.aaps.pump.omnipod.dash.history.DashHistory
import app.aaps.pump.omnipod.dash.history.database.DashHistoryDatabase
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The pod activation talks to the pod outside the command queue. While it runs, the queue must neither
 * run its commands nor close the BLE link, or the pairing breaks (upstream #5219).
 */
class OmnipodDashPumpPluginActivationTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var omnipodManager: OmnipodDashManager
    @Mock lateinit var podStateManager: OmnipodDashPodStateManager
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var history: DashHistory
    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var bus: RxBus
    @Mock lateinit var fabricPrivacy: FabricPrivacy
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var pumpEnactResult: PumpEnactResult
    @Mock lateinit var dashHistoryDatabase: DashHistoryDatabase

    private lateinit var sut: OmnipodDashPumpPlugin

    @BeforeEach
    fun setUp() {
        sut = OmnipodDashPumpPlugin(
            aapsLogger, rh, preferences, commandQueue, omnipodManager, podStateManager, profileFunction, history,
            pumpSync, bus, aapsSchedulers, fabricPrivacy, uiInteraction, { pumpEnactResult }, dashHistoryDatabase
        )
    }

    /** Pairing runs while the progress is still NOT_STARTED; the queue then ran and disconnected the pod. */
    @Test
    fun `the pump is busy while an activation step is running, even before any progress`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.NOT_STARTED)
        whenever(omnipodManager.isActivationRunning).thenReturn(true)

        assertThat(sut.isBusy()).isTrue()
    }

    @Test
    fun `the pump is busy between the activation steps`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.PHASE_1_COMPLETED)
        whenever(omnipodManager.isActivationRunning).thenReturn(false)

        assertThat(sut.isBusy()).isTrue()
    }

    @Test
    fun `the pump is not busy with an activated pod and no activation running`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        whenever(omnipodManager.isActivationRunning).thenReturn(false)

        assertThat(sut.isBusy()).isFalse()
    }

    @Test
    fun `disconnect does not close the link while an activation step is running`() {
        whenever(omnipodManager.isActivationRunning).thenReturn(true)

        sut.disconnect("Queue empty")

        verify(omnipodManager, never()).disconnect(any<Boolean>())
    }

    @Test
    fun `disconnect closes the link when no activation is running`() {
        whenever(omnipodManager.isActivationRunning).thenReturn(false)

        sut.disconnect("Queue empty")

        verify(omnipodManager).disconnect(false)
    }
}
