package app.aaps.core.interfaces.alerts

interface LocalAlertUtils {

    /**
     * Check for unreachable pump (there was no connection with pump for some time).
     * Raise alarm if needed.
     * Overview notification with sound, Therapy event, SMS
     */
    fun checkPumpUnreachableAlarm(lastConnection: Long, isStatusOutdated: Boolean, isDisconnected: Boolean)

    /**
     * Preset next alarm at least 5 min after start of app
     * Call only at startup!
     */
    fun preSnoozeAlarms()

    /**
     * Shortens alarm times in case of setting changes or future data
     */
    fun shortenSnoozeInterval()

    /**
     * Report pump status has been read.
     * Shifts threshold for next alarm to now + preset_interval
     */
    fun reportPumpStatusRead()

    /**
     * Check for missing BGs.
     * Raise alarm if needed.
     * Overview notification with sound, Therapy event
     */
    fun checkStaleBGAlert()

    /**
     * Check that the loop keeps making decisions while it is supposed to run.
     * Raise alarm if needed. Does not influence dosing in any way.
     * Overview notification with sound, Therapy event
     *
     * @param lastLoopDecision timestamp of the last loop decision (Loop.lastRun.lastAPSRun), 0 if none since app start
     * @param isLoopActive loop plugin enabled, running mode open/closed/LGS (not suspended, disconnected, disabled) and a profile is set
     * @param lastBgTimestamp timestamp of the newest BG reading, 0 if none
     */
    fun checkLoopNotRunningAlert(lastLoopDecision: Long, isLoopActive: Boolean, lastBgTimestamp: Long)
}
