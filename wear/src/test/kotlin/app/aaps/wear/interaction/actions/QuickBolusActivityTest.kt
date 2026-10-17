package app.aaps.wear.interaction.actions

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.preference.PreferenceManager
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.impl.sharedPreferences.SPImpl
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import app.aaps.wear.watchfaces.common.PhoneReachability
import com.google.common.truth.Truth.assertThat
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Injects the test's fields into QuickBolusActivity (DaggerActivity needs a HasAndroidInjector application). */
class QuickBolusTestApp : Application(), HasAndroidInjector {

    var inject: (Any) -> Unit = {}
    override fun androidInjector(): AndroidInjector<Any> = AndroidInjector { inject(it) }
}

/**
 * QuickBolusActivity end to end on the real view size (Watch5 Pro, 450 px round): a real tap on each button position
 * (2 and 3 buttons, the view's grid offset and scale applied) sends exactly one
 * EventWearToMobile(ActionBolusPreCheck(that amount, 0)); a second tap sends nothing; a drag onto a button and a
 * press during the input lock send nothing; onPause, screen off and back finish the activity.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = QuickBolusTestApp::class, qualifiers = "de-rDE-w225dp-h225dp-round-xhdpi")
class QuickBolusActivityTest {

    private lateinit var sp: SP
    private lateinit var rxBus: RxBus
    private lateinit var controller: ActivityController<QuickBolusActivity>
    private val app: QuickBolusTestApp get() = RuntimeEnvironment.getApplication() as QuickBolusTestApp

    /** The wall clock, moved by the looper's time (System.currentTimeMillis is not shadowed). */
    private val base = 1_800_000_000_000L
    private fun now() = base + SystemClock.uptimeMillis()

    @Before fun setUp() {
        PhoneReachability.offlineAnswer = true
        QuickBolusActivity.testClock = { now() }
        val shared = PreferenceManager.getDefaultSharedPreferences(app)
        shared.edit().clear().commit()
        sp = SPImpl(shared, app)
        sp.putDouble(app.getString(R.string.key_treatments_safety_max_bolus), 3.0)
        sp.putBoolean(BolusRequestPrefs.KEY_ENABLED, true)
        sp.putString(BolusRequestPrefs.KEY_MODE, BolusRequestPrefs.MODE_PICKER)
        rxBus = mock()
    }

    @After fun tearDown() {
        PhoneReachability.offlineAnswer = null
        QuickBolusActivity.testClock = null
    }

    private fun data(now: Long): ComplicationData {
        val bg = EventData.SingleBg(
            dataset = 0, timeStamp = now - 60_000L, sgvString = "6,4", glucoseUnits = "mmol", slopeArrow = "→", delta = "+0,1",
            deltaDetailed = "+0,1", avgDelta = "+0,1", avgDeltaDetailed = "+0,1", sgvLevel = 0, sgv = 115.0, high = 180.0, low = 70.0,
            color = 0, deltaMgdl = 1.8, avgDeltaMgdl = 1.8
        )
        val st = EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "1,25", iobDetail = "", cob = "0g", currentBasal = "0,85 U/h", battery = "78",
            rigBattery = "", openApsStatus = now - 60_000L, bgi = "", batteryLevel = 1, patientName = "", tempTarget = "",
            tempTargetLevel = 0, reservoirString = "142U", reservoir = 142.0, reservoirLevel = 0, lastLoopRunAt = now - 60_000L
        )
        return ComplicationData(bgData = bg, statusData = st, lastUpdateTimestamp = now)
    }

    /** Opens the picker, then waits until the phone answered after opening (3 s re-ask) and the input lock is over. */
    private fun open(allow15: Boolean, ready: Boolean = true): QuickBolusActivity {
        sp.putBoolean(BolusRequestPrefs.KEY_ALLOW_1_5, allow15)
        val repo = mock<ComplicationDataRepository> { on { complicationData } doReturn MutableStateFlow(data(now())) }
        val prefs = mock<Preferences> { on { get(any<BooleanKey>()) } doReturn true }
        app.inject = { a ->
            (a as QuickBolusActivity).also {
                it.sp = sp
                it.preferences = prefs
                it.rxBus = rxBus
                it.aapsLogger = AAPSLoggerTest()
                it.complicationDataRepository = repo
            }
        }
        controller = Robolectric.buildActivity(QuickBolusActivity::class.java).setup()
        idle()
        if (ready) idle(3_400L)
        return controller.get()
    }

    private fun idle(ms: Long = 0L) {
        if (ms > 0L) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) else shadowOf(Looper.getMainLooper()).idle()
    }

    private fun view(a: QuickBolusActivity): QuickBolusView =
        (a.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as QuickBolusView

    /** View px of design point ([x], [y]) (450 grid, centred, scaled to the view). */
    private fun px(v: QuickBolusView, x: Float, y: Float): Pair<Float, Float> {
        val side = minOf(v.width, v.height).toFloat()
        val scale = side / 450f
        return Pair((v.width - side) / 2f + x * scale, (v.height - side) / 2f + y * scale)
    }

    private fun touch(v: QuickBolusView, action: Int, x: Float, y: Float, downTime: Long) {
        val (px, py) = px(v, x, y)
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, px, py, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun tap(v: QuickBolusView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t)
        idle(60L)
        touch(v, MotionEvent.ACTION_UP, x, y, t)
        idle()
    }

    private fun sentAmounts(): List<Double> {
        val c = argumentCaptor<EventWearToMobile>()
        verify(rxBus, org.mockito.Mockito.atLeast(0)).send(c.capture())
        return c.allValues.map { (it.payload as EventData.ActionBolusPreCheck).also { p -> assertThat(p.carbs).isEqualTo(0) }.insulin }
    }

    private fun tapEachButtonOnce(n: Int, amounts: List<Double>) {
        for (i in 0 until n) {
            sp.putLong(BolusRequestPrefs.KEY_LAST_SENT_AT, 0L)
            rxBus = mock()
            val a = open(allow15 = n == 3)
            val v = view(a)
            assertThat(v.width).isGreaterThan(0)
            assertThat(v.screen!!.buttons.map { it.amount }).isEqualTo(amounts)
            assertThat(v.screen!!.buttons.all { it.enabled }).isTrue()
            tap(v, v.buttonX(i, n), QuickBolusView.BUTTON_Y)
            assertThat(sentAmounts()).containsExactly(amounts[i])
            assertThat(sp.getLong(BolusRequestPrefs.KEY_LAST_SENT_AT, 0L)).isGreaterThan(0L)
            // a second tap (same or another button) sends nothing
            tap(v, v.buttonX(i, n), QuickBolusView.BUTTON_Y)
            tap(v, v.buttonX((i + 1) % n, n), QuickBolusView.BUTTON_Y)
            verify(rxBus, times(1)).send(any())
            controller.pause().stop().destroy()
        }
    }

    @Test fun eachOfTwoButtonsSendsExactlyItsAmountOnce() = tapEachButtonOnce(2, listOf(0.5, 1.0))

    @Test fun eachOfThreeButtonsSendsExactlyItsAmountOnce() = tapEachButtonOnce(3, listOf(0.5, 1.0, 1.5))

    @Test fun dragOntoAButtonSendsNothing() {
        val a = open(allow15 = true)
        val v = view(a)
        val t = SystemClock.uptimeMillis()
        // down on the header, drag down onto 1,5, lift there
        touch(v, MotionEvent.ACTION_DOWN, QuickBolusView.CX, QuickBolusView.HEADER_Y, t)
        touch(v, MotionEvent.ACTION_MOVE, 300f, 200f, t)
        touch(v, MotionEvent.ACTION_UP, v.buttonX(2, 3), QuickBolusView.BUTTON_Y, t)
        // down on 0,5, lift on 1,0
        touch(v, MotionEvent.ACTION_DOWN, v.buttonX(0, 3), QuickBolusView.BUTTON_Y, t)
        touch(v, MotionEvent.ACTION_UP, v.buttonX(1, 3), QuickBolusView.BUTTON_Y, t)
        idle()
        verify(rxBus, never()).send(any())
        assertThat(a.isFinishing).isFalse()
    }

    @Test fun pressDuringTheInputLockLiftedAfterItSendsNothing() {
        val a = open(allow15 = false, ready = false)
        val v = view(a)
        val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, v.buttonX(0, 2), QuickBolusView.BUTTON_Y, t)
        idle(3_400L) // the lock is long over and the phone has answered
        touch(v, MotionEvent.ACTION_UP, v.buttonX(0, 2), QuickBolusView.BUTTON_Y, t)
        idle()
        verify(rxBus, never()).send(any())
        // a clean tap afterwards works
        tap(v, v.buttonX(0, 2), QuickBolusView.BUTTON_Y)
        assertThat(sentAmounts()).containsExactly(0.5)
    }

    @Test fun closeButtonFinishes() {
        val a = open(allow15 = false)
        val v = view(a)
        tap(v, QuickBolusView.CX, QuickBolusView.CLOSE_Y + 40f) // inside the 24 dp target, outside the old 30 px one
        assertThat(a.isFinishing).isTrue()
        verify(rxBus, never()).send(any())
    }

    @Test fun onPauseFinishes() {
        val a = open(allow15 = false)
        controller.pause()
        assertThat(a.isFinishing).isTrue()
    }

    @Test fun screenOffFinishes() {
        val a = open(allow15 = false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle()
        assertThat(a.isFinishing).isTrue()
    }

    @Suppress("DEPRECATION")
    @Test fun backFinishes() {
        val a = open(allow15 = false)
        a.onBackPressed()
        assertThat(a.isFinishing).isTrue()
    }

    @Test fun idleCloseAfterTenSeconds() {
        val a = open(allow15 = false)
        assertThat(a.isFinishing).isFalse()
        idle(QuickBolusPicker.IDLE_MS)
        assertThat(a.isFinishing).isTrue()
        verify(rxBus, never()).send(any())
    }

    @Test fun noKeepScreenOnFlag() {
        val a = open(allow15 = false)
        val flags = a.window.attributes.flags
        assertThat(flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON).isEqualTo(0)
    }
}
