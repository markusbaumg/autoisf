package app.aaps.wear.interaction.actions

import app.aaps.wear.interaction.actions.QuickBolusPicker.Block
import app.aaps.wear.interaction.actions.QuickBolusPicker.Phase
import app.aaps.wear.interaction.actions.QuickBolusPicker.TapResult
import app.aaps.wear.interaction.actions.QuickBolusPicker.Tick
import app.aaps.wear.interaction.actions.QuickBolusPicker.Why
import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.BolusRequestGuard.Reason
import app.aaps.wear.watchfaces.common.BolusRequestGuard.State
import app.aaps.wear.watchfaces.common.BolusRequestGuard.TapResult as FaceTap
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/** The quick-bolus picker's state machine (Orbit "Auswahl" mode). */
class QuickBolusPickerTest {

    private var now = 1_000_000_000_000L
    private lateinit var store: BolusRequestGuard.MemoryStore
    private lateinit var guard: BolusRequestGuard
    private val two = BolusRequestPrefs.pickerAmounts(false)
    private val three = BolusRequestPrefs.pickerAmounts(true)

    @BeforeEach fun setUp() {
        now = 1_000_000_000_000L
        store = BolusRequestGuard.MemoryStore()
        guard = BolusRequestGuard(clock = { now }, store = store)
    }

    private fun input(
        amounts: List<Double> = three, maxBolus: Double = 2.0, prefOn: Boolean = true, wearControl: Boolean = true,
        lastPhone: Long = now - 30_000L, bgTime: Long = now - 60_000L, firstStart: Boolean = false
    ) = BolusRequestGuard.Input(
        prefOn = prefOn, ambient = false, wearControl = wearControl, amount = amounts.min(), maxBolus = maxBolus,
        lastPhoneMessage = lastPhone, bgTimeStamp = bgTime, firstStart = firstStart, allowedAmounts = amounts
    )

    private fun snap(block: Block = Block.NONE, amounts: List<Double> = three, maxBolus: Double = 2.0, bgTime: Long = now - 60_000L) =
        QuickBolusPicker.Snapshot(input(amounts, maxBolus, bgTime = bgTime), block)

    /** Opened, the phone answered "reachable" after opening, input lock over. */
    private fun ready(amounts: List<Double> = three): QuickBolusPicker {
        val p = QuickBolusPicker({ now }, guard, amounts)
        now += 50
        guard.onReachAnswer(true)
        now += QuickBolusPicker.INPUT_LOCK_MS
        return p
    }

    @Test fun constants() {
        assertThat(QuickBolusPicker.INPUT_LOCK_MS).isEqualTo(600L)
        assertThat(QuickBolusPicker.IDLE_MS).isEqualTo(10_000L)
        assertThat(QuickBolusPicker.ANSWER_TIMEOUT_MS).isEqualTo(10_000L)
    }

    @Test fun twoOrThreeAmountsNothingPreselected() {
        val p2 = ready(two)
        val s2 = p2.screen(snap(amounts = two))
        assertThat(s2.buttons.map { it.amount }).containsExactly(0.5, 1.0).inOrder()
        assertThat(s2.buttons.all { it.enabled }).isTrue()
        assertThat(s2.sentAmount).isNull()
        assertThat(s2.phase).isEqualTo(Phase.CHOOSING)
        val s3 = ready(three).screen(snap())
        assertThat(s3.buttons.map { it.amount }).containsExactly(0.5, 1.0, 1.5).inOrder()
        assertThat(s3.why).isEqualTo(Why.NONE)
    }

    @Test fun inputLockedFor600ms() {
        val p = QuickBolusPicker({ now }, guard, three)
        now += 1
        guard.onReachAnswer(true)
        assertThat(p.screen(snap()).inputLocked).isTrue()
        now += 400
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Ignored)
        assertThat(p.onClose()).isFalse()
        assertThat(store.lastSentAt).isEqualTo(0L)
        now = p.openedAt + 599
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Ignored)
        now = p.openedAt + 600
        assertThat(p.inputLocked()).isFalse()
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Send(0.5))
    }

    @Test fun clockSetBackKeepsTheLock() {
        val p = QuickBolusPicker({ now }, guard, three)
        now -= 5_000
        assertThat(p.inputLocked()).isTrue()
    }

    @Test fun recheckOnEveryTap() {
        val p = ready()
        assertThat(p.screen(snap()).buttons.all { it.enabled }).isTrue()
        // data went stale while the picker was open: the tap re-checks and refuses
        val stale = snap(bgTime = now - 13 * 60_000L)
        assertThat(p.screen(stale).why).isEqualTo(Why.STALE)
        assertThat(p.onAmountTap(1.0, stale)).isEqualTo(TapResult.Refused(Why.STALE))
        // phone lost
        guard.onReachAnswer(false)
        assertThat(p.onAmountTap(1.0, snap())).isEqualTo(TapResult.Refused(Why.NO_PHONE))
        assertThat(store.lastSentAt).isEqualTo(0L)
        assertThat(p.phase).isEqualTo(Phase.CHOOSING)
    }

    @Test fun needsAReachableAnswerSinceOpening() {
        guard.onReachAnswer(true) // an answer from before the picker opened
        now += 10
        val p = QuickBolusPicker({ now }, guard, three)
        now += 700
        assertThat(p.screen(snap()).why).isEqualTo(Why.CHECKING_PHONE)
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Refused(Why.CHECKING_PHONE))
        guard.onReachAnswer(true)
        now += 1
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Send(0.5))
    }

    @Test fun unknownReachabilityIsChecking() {
        val p = QuickBolusPicker({ now }, guard, three)
        now += 700
        assertThat(p.screen(snap()).why).isEqualTo(Why.CHECKING_PHONE)
        guard.onReachAnswer(false)
        assertThat(p.screen(snap()).why).isEqualTo(Why.NO_PHONE)
    }

    @Test fun reachAnswerOlderThan10sNoLongerCounts() {
        val p = ready()
        now += BolusRequestGuard.REACH_ANSWER_FRESH_MS + 1
        p.onTouch()
        assertThat(p.screen(snap()).why).isEqualTo(Why.CHECKING_PHONE)
        assertThat(p.onAmountTap(0.5, snap())).isInstanceOf(TapResult.Refused::class.java)
    }

    @Test fun amountsAboveMaxBolusAreGreyedEachOnItsOwn() {
        val p = ready()
        val s = p.screen(snap(maxBolus = 1.0))
        assertThat(s.buttons.map { it.enabled }).containsExactly(true, true, false).inOrder()
        assertThat(s.buttons.map { it.overMax }).containsExactly(false, false, true).inOrder()
        assertThat(s.overMaxAmount).isEqualTo(1.5)
        assertThat(s.why).isEqualTo(Why.NONE)
        assertThat(p.onAmountTap(1.5, snap(maxBolus = 1.0))).isEqualTo(TapResult.Refused(Why.NONE, overMax = true))
        assertThat(store.lastSentAt).isEqualTo(0L)
        assertThat(p.onAmountTap(1.0, snap(maxBolus = 1.0))).isEqualTo(TapResult.Send(1.0))
    }

    @Test fun maxBolusBelowEveryAmount() {
        val s = ready().screen(snap(maxBolus = 0.3))
        assertThat(s.buttons.none { it.enabled }).isTrue()
        assertThat(s.buttons.all { it.overMax }).isTrue()
    }

    @Test fun unknownMaxBolusTurnsAllOff() {
        val p = ready()
        val s = p.screen(snap(maxBolus = Double.NaN))
        assertThat(s.why).isEqualTo(Why.NO_MAX_BOLUS)
        assertThat(s.buttons.none { it.enabled }).isTrue()
        assertThat(s.maxBolus.isNaN()).isTrue()
    }

    @Test fun urgentLowAndFastFallBlockEverything() {
        val p = ready()
        for ((block, why) in listOf(Block.LOW to Why.LOW, Block.FALLING_FAST to Why.FALLING_FAST)) {
            val s = p.screen(snap(block = block))
            assertThat(s.why).isEqualTo(why)
            assertThat(s.buttons.none { it.enabled }).isTrue()
            assertThat(p.onAmountTap(0.5, snap(block = block))).isEqualTo(TapResult.Refused(why))
        }
        assertThat(store.lastSentAt).isEqualTo(0L)
    }

    @Test fun blockFromBg() {
        val arrowFlat = "→"
        // urgent low (< 54 mg/dL)
        assertThat(QuickBolusPicker.block(50.0, -1, arrowFlat, -2.0, 1)).isEqualTo(Block.LOW)
        // below the low mark
        assertThat(QuickBolusPicker.block(65.0, -1, arrowFlat, -1.0, 1)).isEqualTo(Block.LOW)
        // sensor LOW sentinel
        assertThat(QuickBolusPicker.block(39.0, -1, "", 0.0, 1)).isEqualTo(Block.LOW)
        // falling fast at any level
        assertThat(QuickBolusPicker.block(150.0, 0, "⇊", -12.0, 1)).isEqualTo(Block.FALLING_FAST)
        assertThat(QuickBolusPicker.block(150.0, 0, "↘", -20.0, 1)).isEqualTo(Block.FALLING_FAST)
        // in range, high
        assertThat(QuickBolusPicker.block(150.0, 0, arrowFlat, 1.0, 1)).isEqualTo(Block.NONE)
        assertThat(QuickBolusPicker.block(250.0, 1, "↗", 5.0, 1)).isEqualTo(Block.NONE)
        // the low / fall block never depends on the age: at the 12-min stale boundary and on old data it still holds
        // (the guard's STALE wins in why(), so this is only stricter)
        assertThat(QuickBolusPicker.block(50.0, -1, arrowFlat, 0.0, 11)).isEqualTo(Block.LOW)
        assertThat(QuickBolusPicker.block(50.0, -1, arrowFlat, 0.0, 12)).isEqualTo(Block.LOW)
        assertThat(QuickBolusPicker.block(65.0, -1, arrowFlat, 0.0, 20)).isEqualTo(Block.LOW)
        assertThat(QuickBolusPicker.block(39.0, -1, "", 0.0, 13)).isEqualTo(Block.LOW)
        assertThat(QuickBolusPicker.block(150.0, 0, "⇊", -12.0, 12)).isEqualTo(Block.FALLING_FAST)
        // no reading at all: nothing to block on (the guard's STALE)
        assertThat(QuickBolusPicker.block(0.0, 0, "", null, null)).isEqualTo(Block.NONE)
    }

    @Test fun lowAtTheStaleBoundaryIsBlockedThroughSnapshot() {
        val p = ready()
        // exactly 12:00.000 old: the guard still accepts the reading for this millisecond, the low must block
        val bgTime = now - BolusRequestGuard.DATA_FRESH_MS
        val s = p.screen(QuickBolusPicker.Snapshot(input(bgTime = bgTime), QuickBolusPicker.block(50.0, -1, "→", 0.0, 12)))
        assertThat(s.why).isEqualTo(Why.LOW)
        assertThat(s.buttons.none { it.enabled }).isTrue()
    }

    @Test fun maxBolusTextNeverRoundsUp() {
        assertThat(QuickBolusPicker.maxBolusText(1.45, Locale.GERMANY)).isEqualTo("1,45")
        assertThat(QuickBolusPicker.maxBolusText(1.499, Locale.GERMANY)).isEqualTo("1,49")
        assertThat(QuickBolusPicker.maxBolusText(2.0, Locale.GERMANY)).isEqualTo("2,0")
        assertThat(QuickBolusPicker.maxBolusText(1.5, Locale.GERMANY)).isEqualTo("1,5")
        assertThat(QuickBolusPicker.maxBolusText(10.0, Locale.US)).isEqualTo("10.0")
        assertThat(QuickBolusPicker.maxBolusText(0.3, Locale.GERMANY)).isEqualTo("0,3")
    }

    @Test fun singleRequestThenNothing() {
        val p = ready()
        assertThat(p.onAmountTap(1.0, snap())).isEqualTo(TapResult.Send(1.0))
        assertThat(p.phase).isEqualTo(Phase.WAITING)
        assertThat(p.sentAmount).isEqualTo(1.0)
        assertThat(store.lastSentAt).isEqualTo(now)
        now += 100
        for (a in three) assertThat(p.onAmountTap(a, snap())).isEqualTo(TapResult.Ignored)
        assertThat(p.screen(snap()).buttons.none { it.enabled }).isTrue()
        assertThat(store.lastSentAt).isEqualTo(now - 100)
    }

    @Test fun timeoutShowsNoAnswerAndNeverResends() {
        val p = ready()
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Send(0.5))
        val sentAt = store.lastSentAt
        now += QuickBolusPicker.ANSWER_TIMEOUT_MS - 1
        assertThat(p.tick()).isEqualTo(Tick.NONE)
        now += 1
        assertThat(p.tick()).isEqualTo(Tick.REDRAW)
        assertThat(p.phase).isEqualTo(Phase.NO_ANSWER)
        // no re-send: taps are ignored, the stored time stays the one of the single request
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Ignored)
        assertThat(store.lastSentAt).isEqualTo(sentAt)
        now += QuickBolusPicker.NO_ANSWER_SHOW_MS
        assertThat(p.tick()).isEqualTo(Tick.CLOSE)
        assertThat(p.phase).isEqualTo(Phase.CLOSED)
    }

    @Test fun idleCloseAfter10s() {
        val p = ready()
        now = p.openedAt + 9_000
        p.onTouch()
        now += QuickBolusPicker.IDLE_MS - 1
        assertThat(p.tick()).isEqualTo(Tick.NONE)
        now += 1
        assertThat(p.tick()).isEqualTo(Tick.CLOSE)
    }

    @Test fun closeButtonAfterTheLock() {
        val p = ready()
        assertThat(p.onClose()).isTrue()
        assertThat(p.phase).isEqualTo(Phase.CLOSED)
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Ignored)
    }

    @Test fun pickerRequestLocksTheFaceBall() {
        val p = ready()
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Send(0.5))
        // the face (same store) is now SENT for 30 s
        val face = BolusRequestGuard(clock = { now }, store = store)
        face.onReachAnswer(true)
        now += 1_000
        assertThat(face.check(input(amounts = BolusRequestPrefs.pickerAmounts(false)).copy(amount = 0.5)).state).isEqualTo(State.SENT)
        now += BolusRequestGuard.PENDING_MS
        assertThat(face.check(input().copy(amount = 0.5)).state).isEqualTo(State.IDLE)
    }

    @Test fun faceBallRequestLocksThePicker() {
        store.lastSentAt = now - 5_000
        val p = ready()
        val s = p.screen(snap())
        assertThat(s.why).isEqualTo(Why.PENDING)
        assertThat(s.buttons.none { it.enabled }).isTrue()
        assertThat(p.onAmountTap(0.5, snap())).isEqualTo(TapResult.Refused(Why.PENDING))
        assertThat(store.lastSentAt).isEqualTo(now - 5_000 - 50 - QuickBolusPicker.INPUT_LOCK_MS)
    }

    @Test fun openingFromTheFaceDoesNotLock() {
        // the face's double tap in picker mode: SEND without storing the send time
        val face = BolusRequestGuard(clock = { now }, store = store)
        face.onReachAnswer(true)
        val i = input(amounts = three)
        assertThat(face.onTap(1L, true, i, commitSend = false).result).isEqualTo(FaceTap.ARM)
        now += 200
        face.onReachAnswer(true)
        now += 200
        assertThat(face.onTap(2L, true, i, commitSend = false).result).isEqualTo(FaceTap.SEND)
        assertThat(store.lastSentAt).isEqualTo(0L)
        assertThat(face.isPending).isFalse()
        // ... and the picker it opened is free to send
        val p = ready()
        assertThat(p.onAmountTap(1.5, snap())).isEqualTo(TapResult.Send(1.5))
    }

    @Test fun offWhenSwitchedOffOrWearControlOff() {
        val p = ready()
        assertThat(p.screen(QuickBolusPicker.Snapshot(input().copy(prefOn = false), Block.NONE)).why).isEqualTo(Why.OFF)
        assertThat(p.screen(QuickBolusPicker.Snapshot(input().copy(wearControl = false), Block.NONE)).why).isEqualTo(Why.NO_WEAR_CONTROL)
        assertThat(p.screen(QuickBolusPicker.Snapshot(input().copy(firstStart = true), Block.NONE)).why).isEqualTo(Why.OFF)
    }

    @Test fun amountNotOfferedIsIgnored() {
        val p = ready(two)
        assertThat(p.onAmountTap(1.5, snap(amounts = two))).isEqualTo(TapResult.Ignored)
        assertThat(p.onAmountTap(2.0, snap(amounts = two))).isEqualTo(TapResult.Ignored)
        assertThat(store.lastSentAt).isEqualTo(0L)
    }

    @Test fun guardSendSingleNeverAboveTheHardCeiling() {
        guard.onReachAnswer(true)
        now += 1
        val above = input(amounts = listOf(0.5, 1.0, 1.5, 2.0), maxBolus = 5.0).copy(amount = 2.0)
        assertThat(guard.sendSingle(above, reachSince = 0L)).isEqualTo(Reason.AMOUNT_INVALID)
        assertThat(store.lastSentAt).isEqualTo(0L)
    }

    @Test fun germanDecimalCommas() {
        assertThat(QuickBolusPicker.amountText(0.5, Locale.GERMANY)).isEqualTo("0,5")
        assertThat(QuickBolusPicker.amountText(1.5, Locale.GERMANY)).isEqualTo("1,5")
        assertThat(QuickBolusPicker.amountText(1.0, Locale.US)).isEqualTo("1.0")
    }
}
