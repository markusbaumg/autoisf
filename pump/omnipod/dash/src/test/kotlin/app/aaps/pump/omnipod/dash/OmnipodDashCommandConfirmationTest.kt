package app.aaps.pump.omnipod.dash

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventProfileSwitchChanged
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BasalProgram
import app.aaps.pump.omnipod.common.bledriver.pod.state.CommandConfirmed
import app.aaps.pump.omnipod.common.bledriver.pod.state.OmnipodDashPodStateManager
import app.aaps.pump.omnipod.common.definition.OmnipodCommandType
import app.aaps.pump.omnipod.common.queue.command.CommandDisableSuspendAlerts
import app.aaps.pump.omnipod.dash.driver.OmnipodDashManager
import app.aaps.pump.omnipod.dash.history.DashHistory
import app.aaps.pump.omnipod.dash.history.data.HistoryRecord
import app.aaps.pump.omnipod.dash.history.data.InitialResult
import app.aaps.pump.omnipod.dash.history.database.DashHistoryDatabase
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * What the plugin does when the pod confirms a command.
 *
 * Setting the time (after a time zone or DST change) is sent to the pod as suspend + new basal program, the
 * same as a profile change. The suspend starts a "pump suspended" temp basal in AAPS and the pod's "delivery
 * suspended" alert, which beeps after 20 minutes. The confirmation must end both, or the pod beeps while
 * basal is running and AAPS still shows the pump as suspended (upstream #2982).
 */
class OmnipodDashCommandConfirmationTest : TestBase() {

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

    private val basalProgram = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100)))

    @BeforeEach
    fun setUp() {
        sut = OmnipodDashPumpPlugin(
            aapsLogger, rh, preferences, commandQueue, omnipodManager, podStateManager, profileFunction, history,
            pumpSync, bus, aapsSchedulers, fabricPrivacy, uiInteraction, { pumpEnactResult }, dashHistoryDatabase
        )
    }

    @Test
    fun `a confirmed time change ends the suspend and turns the suspend alert off`() {
        givenHistoryEntry(OmnipodCommandType.SET_TIME)

        sut.handleCommandConfirmation(confirmation(success = true))

        verify(pumpSync).syncStopTemporaryBasalWithPumpId(eq(CREATED_AT), eq(HISTORY_ID), eq(PumpType.OMNIPOD_DASH), any(), any())
        verify(commandQueue).customCommand(any<CommandDisableSuspendAlerts>(), anyOrNull())
        verify(podStateManager).basalProgram = basalProgram
        assertThat(dismissedNotifications()).containsAtLeast(Notification.OMNIPOD_POD_SUSPENDED, Notification.OMNIPOD_TIME_OUT_OF_SYNC)
    }

    /** A profile change asked again by a late confirmation is only for a real profile change. */
    @Test
    fun `a confirmed time change does not ask for a profile change`() {
        givenHistoryEntry(OmnipodCommandType.SET_TIME)

        sut.handleCommandConfirmation(confirmation(success = true))

        assertThat(sentEvents().filterIsInstance<EventProfileSwitchChanged>()).isEmpty()
    }

    @Test
    fun `a failed time change changes nothing`() {
        givenHistoryEntry(OmnipodCommandType.SET_TIME)

        sut.handleCommandConfirmation(confirmation(success = false))

        verify(pumpSync, never()).syncStopTemporaryBasalWithPumpId(any(), any(), any(), any(), any())
        verify(commandQueue, never()).customCommand(any(), anyOrNull())
    }

    /** The shared branch must keep the profile change behaviour as it was. */
    @Test
    fun `a late confirmed profile change still asks for the profile change again`() {
        givenHistoryEntry(OmnipodCommandType.SET_BASAL_PROFILE)

        sut.handleCommandConfirmation(confirmation(success = true))

        assertThat(sentEvents().filterIsInstance<EventProfileSwitchChanged>()).hasSize(1)
        verify(pumpSync).syncStopTemporaryBasalWithPumpId(eq(CREATED_AT), eq(HISTORY_ID), eq(PumpType.OMNIPOD_DASH), any(), any())
        verify(commandQueue).customCommand(any<CommandDisableSuspendAlerts>(), anyOrNull())
    }

    private fun sentEvents(): List<Event> {
        val captor = argumentCaptor<Event>()
        verify(bus, atLeast(0)).send(captor.capture())
        return captor.allValues
    }

    private fun dismissedNotifications(): List<Int> = sentEvents().filterIsInstance<EventDismissNotification>().map { it.id }

    private fun givenHistoryEntry(commandType: OmnipodCommandType) {
        whenever(history.getById(HISTORY_ID)).thenReturn(
            HistoryRecord(
                id = HISTORY_ID, createdAt = CREATED_AT, date = CREATED_AT, commandType = commandType, initialResult = InitialResult.SENT,
                record = null, totalAmountDelivered = null, resolvedResult = null, resolvedAt = null
            )
        )
    }

    private fun confirmation(success: Boolean) = CommandConfirmed(
        OmnipodDashPodStateManager.ActiveCommand(
            sequence = 1, createdRealtime = 0L, historyId = HISTORY_ID, sendError = null, basalProgram = basalProgram, tempBasal = null, requestedBolus = null
        ),
        success
    )

    private companion object {

        const val HISTORY_ID = 7L
        const val CREATED_AT = 1_000L
    }
}
