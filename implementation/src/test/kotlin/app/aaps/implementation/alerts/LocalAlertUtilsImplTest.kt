package app.aaps.implementation.alerts

import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.implementation.alerts.LocalAlertUtilsImpl.LoopAlertAction
import com.google.common.truth.Truth.assertThat
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.alerts.keys.LocalAlertLongKey
import app.aaps.shared.tests.TestBase
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LocalAlertUtilsImplTest : TestBase() {

    @Mock lateinit var preferences: Preferences
    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var activePlugin: ActivePlugin
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var smsCommunicator: SmsCommunicator
    @Mock lateinit var config: Config
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var pump: Pump
    @Mock lateinit var pumpDescription: PumpDescription

    private lateinit var localAlertUtils: LocalAlertUtilsImpl

    private val now = 100000000L

    @BeforeEach
    fun setup() {
        localAlertUtils = LocalAlertUtilsImpl(
            aapsLogger,
            preferences,
            rxBus,
            rh,
            activePlugin,
            profileFunction,
            smsCommunicator,
            config,
            persistenceLayer,
            dateUtil
        )
        whenever(dateUtil.now()).thenReturn(now)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.pumpDescription).thenReturn(pumpDescription)
        whenever(pumpDescription.hasCustomUnreachableAlertCheck).thenReturn(false)
        whenever(persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), any(), any()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult()))
    }

    @Test
    fun `preSnoozeAlarms sets next missed readings alarm when expired`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now - 1000)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + 5 * 60 * 1000)
    }

    @Test
    fun `preSnoozeAlarms does not update next missed readings alarm when not expired`() {
        val futureTime = now + 10000
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(futureTime)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextMissedReadingsAlarm), any())
    }

    @Test
    fun `preSnoozeAlarms sets next pump disconnected alarm when expired`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 1000)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + 5 * 60 * 1000)
    }

    @Test
    fun `preSnoozeAlarms does not update pump disconnected alarm when not expired`() {
        val futureTime = now + 10000
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(futureTime)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(futureTime)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextPumpDisconnectedAlarm), any())
    }

    @Test
    fun `preSnoozeAlarms updates both alarms when both expired`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now - 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 2000)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + 5 * 60 * 1000)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + 5 * 60 * 1000)
    }

    @Test
    fun `shortenSnoozeInterval limits missed readings alarm to threshold`() {
        val thresholdMinutes = 30
        val farFutureAlarm = now + T.hours(5).msecs()

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(farFutureAlarm)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(30)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
    }

    @Test
    fun `shortenSnoozeInterval does not change alarm if already within threshold`() {
        val thresholdMinutes = 30
        val alarmTime = now + T.mins(10).msecs()

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(alarmTime)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(30)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(alarmTime)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, alarmTime)
    }

    @Test
    fun `shortenSnoozeInterval limits pump disconnected alarm to threshold`() {
        val thresholdMinutes = 20
        val farFutureAlarm = now + T.hours(10).msecs()

        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(30)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(farFutureAlarm)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
    }

    @Test
    fun `shortenSnoozeInterval handles both alarms correctly`() {
        val missedReadingsThreshold = 25
        val pumpUnreachableThreshold = 35

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(missedReadingsThreshold)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(pumpUnreachableThreshold)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + T.hours(1).msecs())
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + T.hours(2).msecs())

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + T.mins(missedReadingsThreshold.toLong()).msecs())
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + T.mins(pumpUnreachableThreshold.toLong()).msecs())
    }

    @Test
    fun `reportPumpStatusRead updates alarm when profile is available`() {
        val lastDataTime = now - T.mins(5).msecs()
        val thresholdMinutes = 30
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.Profile>()

        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(pump.lastDataTime).thenReturn(lastDataTime)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 1000)

        localAlertUtils.reportPumpStatusRead()

        val expectedAlarmTime = lastDataTime + T.mins(thresholdMinutes.toLong()).msecs()
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, expectedAlarmTime)
    }

    @Test
    fun `reportPumpStatusRead does not update alarm when profile is null`() {
        whenever(profileFunction.getProfile()).thenReturn(null)

        localAlertUtils.reportPumpStatusRead()

        verify(preferences, never()).put(any<LocalAlertLongKey>(), any<Long>())
    }

    @Test
    fun `reportPumpStatusRead does not decrease alarm time`() {
        val lastDataTime = now - T.mins(5).msecs()
        val thresholdMinutes = 30
        val futureAlarmTime = now + T.hours(2).msecs()
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.Profile>()

        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(pump.lastDataTime).thenReturn(lastDataTime)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(futureAlarmTime)

        localAlertUtils.reportPumpStatusRead()

        // Should not update because futureAlarmTime is already later than earliestAlarmTime
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextPumpDisconnectedAlarm), any())
    }

    @Test
    fun `preSnoozeAlarms uses exact 5 minute snooze`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now - 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 1000)

        localAlertUtils.preSnoozeAlarms()

        val expectedSnooze = now + 5 * 60 * 1000
        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, expectedSnooze)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, expectedSnooze)
    }

    @Test
    fun `shortenSnoozeInterval handles edge case with zero threshold`() {
        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(0)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(0)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + 1000)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now)
    }

    @Test
    fun `shortenSnoozeInterval uses minimum of current alarm and threshold`() {
        val thresholdMinutes = 30
        val currentAlarmClose = now + T.mins(10).msecs()
        val currentAlarmFar = now + T.mins(50).msecs()

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)

        // Test with close alarm - should keep it
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(currentAlarmClose)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(currentAlarmClose)
        localAlertUtils.shortenSnoozeInterval()
        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, currentAlarmClose)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, currentAlarmClose)

        // Test with far alarm - should shorten it
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(currentAlarmFar)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(currentAlarmFar)
        localAlertUtils.shortenSnoozeInterval()
        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
    }

    @Test
    fun `reportPumpStatusRead calculates earliest alarm time correctly`() {
        val lastDataTime = now - T.mins(10).msecs()
        val thresholdMinutes = 40
        val currentAlarmTime = now + T.mins(5).msecs()
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.Profile>()

        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(pump.lastDataTime).thenReturn(lastDataTime)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(currentAlarmTime)

        localAlertUtils.reportPumpStatusRead()

        val expectedEarliestAlarm = lastDataTime + T.mins(thresholdMinutes.toLong()).msecs()
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, expectedEarliestAlarm)
    }

    // ---- loop not running ----

    private val threshold = T.mins(15).msecs()

    private fun evaluate(
        alertEnabled: Boolean = true,
        isLoopActive: Boolean = true,
        lastLoopDecision: Long = now - T.mins(20).msecs(),
        referenceTime: Long = lastLoopDecision,
        lastBgTimestamp: Long = now - T.mins(1).msecs(),
        nextAlarm: Long = 0L
    ) = LocalAlertUtilsImpl.evaluateLoopNotRunning(now, alertEnabled, isLoopActive, lastLoopDecision, referenceTime, lastBgTimestamp, threshold, nextAlarm)

    @Test
    fun `loop alarm raised when no decision for threshold and BG fresh`() {
        assertThat(evaluate()).isEqualTo(LoopAlertAction.RAISE)
        assertThat(evaluate(lastLoopDecision = now - threshold)).isEqualTo(LoopAlertAction.RAISE)
    }

    @Test
    fun `loop alarm not raised before threshold`() {
        assertThat(evaluate(lastLoopDecision = now - threshold + 1)).isEqualTo(LoopAlertAction.DISMISS)
        assertThat(evaluate(lastLoopDecision = now - T.mins(1).msecs(), lastBgTimestamp = now)).isEqualTo(LoopAlertAction.DISMISS)
    }

    @Test
    fun `loop alarm dismissed when disabled or loop not active`() {
        assertThat(evaluate(alertEnabled = false)).isEqualTo(LoopAlertAction.DISMISS)
        assertThat(evaluate(isLoopActive = false)).isEqualTo(LoopAlertAction.DISMISS)
    }

    @Test
    fun `loop alarm not raised without fresh BG`() {
        assertThat(evaluate(lastBgTimestamp = 0L)).isEqualTo(LoopAlertAction.NONE)
        assertThat(evaluate(lastBgTimestamp = now - T.mins(10).msecs())).isEqualTo(LoopAlertAction.NONE)
        assertThat(evaluate(lastBgTimestamp = now - T.mins(9).msecs())).isEqualTo(LoopAlertAction.RAISE)
        // newest BG already handled by the last decision -> BG not arriving, missed-BG alarm territory
        assertThat(evaluate(lastLoopDecision = now - T.mins(20).msecs(), lastBgTimestamp = now - T.mins(21).msecs())).isEqualTo(LoopAlertAction.NONE)
    }

    @Test
    fun `loop alarm respects startup and resume grace via reference time`() {
        // no decision since app start 10 min ago
        assertThat(evaluate(lastLoopDecision = 0L, referenceTime = now - T.mins(10).msecs())).isEqualTo(LoopAlertAction.NONE)
        // no decision since app start 15 min ago
        assertThat(evaluate(lastLoopDecision = 0L, referenceTime = now - threshold)).isEqualTo(LoopAlertAction.RAISE)
        // loop resumed (last seen inactive) 5 min ago, last decision 2 h ago
        assertThat(evaluate(lastLoopDecision = now - T.hours(2).msecs(), referenceTime = now - T.mins(5).msecs())).isEqualTo(LoopAlertAction.NONE)
    }

    @Test
    fun `loop alarm respects snooze`() {
        assertThat(evaluate(nextAlarm = now + 1)).isEqualTo(LoopAlertAction.NONE)
        assertThat(evaluate(nextAlarm = now)).isEqualTo(LoopAlertAction.RAISE)
    }

    private fun stubLoopAlertPrefs(next: Long = 0L) {
        whenever(config.APS).thenReturn(true)
        whenever(preferences.get(BooleanKey.AlertLoopNotRunning)).thenReturn(true)
        whenever(preferences.get(IntKey.AlertsLoopNotRunningThreshold)).thenReturn(15)
        whenever(preferences.get(LocalAlertLongKey.NextLoopNotRunningAlarm)).thenReturn(next)
        whenever(rh.gs(any(), any())).thenReturn("Loop not running")
    }

    @Test
    fun `checkLoopNotRunningAlert raises notification and snoozes for threshold`() {
        stubLoopAlertPrefs()
        val events = rxBus.toObservable(EventNewNotification::class.java).test()

        localAlertUtils.checkLoopNotRunningAlert(now - T.mins(20).msecs(), true, now - T.mins(1).msecs())

        verify(preferences).put(LocalAlertLongKey.NextLoopNotRunningAlarm, now + threshold)
        events.assertValueCount(1)
        assertThat(events.values()[0].notification.id).isEqualTo(app.aaps.core.interfaces.notifications.Notification.LOOP_NOT_RUNNING)
    }

    @Test
    fun `checkLoopNotRunningAlert dismisses when loop runs`() {
        stubLoopAlertPrefs()
        val dismiss = rxBus.toObservable(EventDismissNotification::class.java).test()

        localAlertUtils.checkLoopNotRunningAlert(now - T.mins(1).msecs(), true, now)

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextLoopNotRunningAlarm), any())
        dismiss.assertValueCount(1)
    }

    @Test
    fun `checkLoopNotRunningAlert does not fire right after resume from suspend`() {
        stubLoopAlertPrefs()
        val events = rxBus.toObservable(EventNewNotification::class.java).test()

        // suspended: seen inactive now
        localAlertUtils.checkLoopNotRunningAlert(now - T.hours(2).msecs(), false, now - T.mins(1).msecs())
        // resumed, still at same time (no decision yet)
        localAlertUtils.checkLoopNotRunningAlert(now - T.hours(2).msecs(), true, now - T.mins(1).msecs())

        events.assertValueCount(0)
    }

    @Test
    fun `checkLoopNotRunningAlert does not fire within startup grace`() {
        stubLoopAlertPrefs()
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + 1000)
        val events = rxBus.toObservable(EventNewNotification::class.java).test()
        localAlertUtils.preSnoozeAlarms()

        localAlertUtils.checkLoopNotRunningAlert(0L, true, now - T.mins(1).msecs())

        events.assertValueCount(0)
    }
}
