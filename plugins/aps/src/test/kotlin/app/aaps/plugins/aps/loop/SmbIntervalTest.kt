package app.aaps.plugins.aps.loop

import app.aaps.core.data.aps.SmbInterval
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min

class SmbIntervalTest {

    private val now = 1_760_000_000_000L

    /** Copy of the gate in DetermineBasalSMB / DetermineBasalAutoISF. */
    private fun algorithmAllowsSmb(lastBolusTime: Long, systemTime: Long, smbInterval: Int): Boolean {
        val lastBolusAge = (systemTime - lastBolusTime) / 1000.0
        val SMBInterval = min(10, max(1, smbInterval)) * 60.0
        return lastBolusAge > SMBInterval - 6.0
    }

    @Test
    fun `no previous bolus never blocks`() {
        assertThat(SmbInterval.isTooSoon(0L, now, 1)).isFalse()
    }

    @Test
    fun `one minute interval allows SMB after 54 s but not at or before`() {
        assertThat(SmbInterval.isTooSoon(now - 53_000, now, 1)).isTrue()
        assertThat(SmbInterval.isTooSoon(now - 54_000, now, 1)).isTrue()
        assertThat(SmbInterval.isTooSoon(now - 54_001, now, 1)).isFalse()
        assertThat(SmbInterval.isTooSoon(now - 57_000, now, 1)).isFalse() // dropped before this fix
        assertThat(SmbInterval.isTooSoon(now - 60_000, now, 1)).isFalse()
    }

    @Test
    fun `three minute interval allows SMB after 174 s`() {
        assertThat(SmbInterval.isTooSoon(now - 174_000, now, 3)).isTrue()
        assertThat(SmbInterval.isTooSoon(now - 175_000, now, 3)).isFalse()
    }

    @Test
    fun `bolus a few seconds ago always blocks`() {
        for (interval in 1..10) {
            assertThat(SmbInterval.isTooSoon(now - 1_000, now, interval)).isTrue()
            assertThat(SmbInterval.isTooSoon(now, now, interval)).isTrue()
        }
    }

    @Test
    fun `bolus timestamp in the future (clock skew) blocks`() {
        assertThat(SmbInterval.isTooSoon(now + 30_000, now, 1)).isTrue()
        assertThat(SmbInterval.isTooSoon(now + 10 * 60_000, now, 10)).isTrue()
    }

    @Test
    fun `interval is clamped to 1-10 minutes like the algorithm`() {
        assertThat(SmbInterval.isTooSoon(now - 30_000, now, 0)).isTrue()
        assertThat(SmbInterval.isTooSoon(now - 55_000, now, 0)).isFalse()
        assertThat(SmbInterval.isTooSoon(now - 11 * 60_000, now, 15)).isFalse()
        assertThat(SmbInterval.isTooSoon(now - 9 * 60_000, now, 15)).isTrue()
    }

    @Test
    fun `gate agrees with the algorithm for every age and interval`() {
        for (interval in 0..12) {
            var ageMs = -5_000L
            while (ageMs <= 11 * 60_000L) {
                val last = now - ageMs
                assertThat(SmbInterval.isTooSoon(last, now, interval))
                    .isEqualTo(!algorithmAllowsSmb(last, now, interval))
                ageMs += 250
            }
        }
    }

    @Test
    fun `gate is never stricter than the algorithm when enacted later than calculated`() {
        // Loop/queue check at enact time, which is at or after the algorithm's systemTime.
        for (interval in 1..10) {
            var ageMs = 0L
            while (ageMs <= 11 * 60_000L) {
                val last = now - ageMs
                if (algorithmAllowsSmb(last, now, interval))
                    for (delay in longArrayOf(0, 500, 5_000, 30_000))
                        assertThat(SmbInterval.isTooSoon(last, now + delay, interval)).isFalse()
                ageMs += 1_000
            }
        }
    }
}
