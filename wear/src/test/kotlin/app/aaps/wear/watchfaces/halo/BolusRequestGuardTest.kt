package app.aaps.wear.watchfaces.halo

import app.aaps.wear.watchfaces.halo.BolusRequestGuard.Verdict
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BolusRequestGuardTest {

    private var now = 100_000_000L
    private lateinit var guard: BolusRequestGuard

    @BeforeEach fun setUp() {
        now = 100_000_000L
        guard = BolusRequestGuard(clock = { now })
    }

    private fun check(amount: Double = 0.5, maxBolus: Double = 3.0, wearControl: Boolean = true, phone: Boolean = true, lastData: Long = now - 60_000L) =
        guard.checkAndRecord(amount, maxBolus, wearControl, phone, lastData)

    @Test fun sendsWhenEverythingIsFine() {
        assertThat(check()).isEqualTo(Verdict.SEND)
        assertThat(guard.isPending).isTrue()
    }

    @Test fun refusesAboveMaxBolus() {
        assertThat(check(amount = 1.0, maxBolus = 0.8)).isEqualTo(Verdict.REFUSED_MAX_BOLUS)
        assertThat(check(amount = 0.5, maxBolus = 0.0)).isEqualTo(Verdict.REFUSED_MAX_BOLUS)
        assertThat(guard.isPending).isFalse()
        assertThat(check(amount = 1.0, maxBolus = 1.0)).isEqualTo(Verdict.SEND)
    }

    @Test fun refusesOtherAmounts() {
        assertThat(check(amount = 2.0, maxBolus = 10.0)).isEqualTo(Verdict.REFUSED_AMOUNT)
        assertThat(check(amount = 0.3)).isEqualTo(Verdict.REFUSED_AMOUNT)
    }

    @Test fun noSendWhenPhoneDisconnected() {
        assertThat(check(phone = false)).isEqualTo(Verdict.REFUSED_NO_PHONE)
        assertThat(guard.isPending).isFalse()
    }

    @Test fun noSendWithoutFreshPhoneData() {
        assertThat(check(lastData = 0L)).isEqualTo(Verdict.REFUSED_STALE_DATA)
        assertThat(check(lastData = now - BolusRequestGuard.DATA_FRESH_MS - 1)).isEqualTo(Verdict.REFUSED_STALE_DATA)
        assertThat(check(lastData = now - BolusRequestGuard.DATA_FRESH_MS)).isEqualTo(Verdict.SEND)
    }

    @Test fun noSendWithoutWearControl() {
        assertThat(check(wearControl = false)).isEqualTo(Verdict.REFUSED_WEAR_CONTROL)
    }

    @Test fun dedupeWhileConfirmationPending() {
        assertThat(check()).isEqualTo(Verdict.SEND)
        now += 10_000
        assertThat(check()).isEqualTo(Verdict.REFUSED_PENDING)
        now += BolusRequestGuard.PENDING_MS - 10_001
        assertThat(check()).isEqualTo(Verdict.REFUSED_PENDING)
        now += 1
        assertThat(guard.isPending).isFalse()
        assertThat(check()).isEqualTo(Verdict.SEND)
    }

    @Test fun evaluateDoesNotRecord() {
        assertThat(guard.evaluate(0.5, 3.0, true, true, now)).isEqualTo(Verdict.SEND)
        assertThat(guard.isPending).isFalse()
        assertThat(guard.evaluate(0.5, 3.0, true, true, now)).isEqualTo(Verdict.SEND)
    }

    @Test fun pendingWindowSurvivesANewGuardWithTheSameStore() {
        val store = BolusRequestGuard.MemoryStore()
        val first = BolusRequestGuard(clock = { now }, store = store)
        assertThat(first.checkAndRecord(0.5, 3.0, true, true, now)).isEqualTo(Verdict.SEND)
        now += 5_000
        // e.g. the watch face service was recreated while the confirmation is open
        val second = BolusRequestGuard(clock = { now }, store = store)
        assertThat(second.checkAndRecord(0.5, 3.0, true, true, now)).isEqualTo(Verdict.REFUSED_PENDING)
        now += BolusRequestGuard.PENDING_MS
        assertThat(second.checkAndRecord(0.5, 3.0, true, true, now)).isEqualTo(Verdict.SEND)
    }

    @Test fun clockSetBackKeepsThePendingWindow() {
        assertThat(check()).isEqualTo(Verdict.SEND)
        now -= 10_000
        assertThat(check()).isEqualTo(Verdict.REFUSED_PENDING)
        now -= BolusRequestGuard.PENDING_MS
        assertThat(check()).isEqualTo(Verdict.SEND)
    }
}
