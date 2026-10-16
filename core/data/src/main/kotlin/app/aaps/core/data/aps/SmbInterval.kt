package app.aaps.core.data.aps

/**
 * Minimum time between two SMBs ("smbinterval" preference), shared by every place that
 * enforces it so they all agree with the algorithm.
 *
 * DetermineBasalSMB / DetermineBasalAutoISF (oref) allow the next SMB when
 * `lastBolusAge > SMBInterval - 6 s`. The 6 s tolerance absorbs loop timing jitter
 * (CGM readings and loop runs do not arrive exactly every N minutes). The loop and the
 * command queue used to require the full interval, so with smbinterval = 1 and 1-minute
 * CGM data an SMB computed 54-59 s after the previous bolus was silently dropped.
 *
 * Duplicate delivery is still prevented independently of this check: the command queue
 * rejects an SMB while any bolus is queued or running, drops it if another bolus was
 * recorded after the request was made (lastKnownBolusTime), and drops it once it is
 * older than deliverAt + 1 min.
 */
object SmbInterval {

    /** Same tolerance as oref's `lastBolusAge > SMBInterval - 6` (seconds). */
    const val TOLERANCE_MS = 6_000L

    /** Clamp as the algorithm does: allowed SMB intervals are 1 to 10 minutes. */
    fun intervalMs(intervalMinutes: Int): Long = intervalMinutes.coerceIn(1, 10) * 60_000L

    /**
     * @return true if an SMB must NOT be delivered yet because the last bolus at [lastBolusTime]
     * is too recent. [lastBolusTime] 0 means "no bolus known". A bolus timestamp in the future
     * (clock skew) always blocks.
     */
    fun isTooSoon(lastBolusTime: Long, now: Long, intervalMinutes: Int): Boolean {
        if (lastBolusTime == 0L) return false
        // mirror the algorithm exactly: it allows only when age > interval - 6 s
        return now - lastBolusTime <= intervalMs(intervalMinutes) - TOLERANCE_MS
    }
}
