package app.aaps.wear.watchfaces.halo

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.wear.watchface.TapType
import app.aaps.core.interfaces.rx.AapsSchedulers
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
import app.aaps.wear.interaction.actions.QuickBolusActivity
import app.aaps.wear.watchfaces.HaloWatchface
import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import app.aaps.wear.watchfaces.common.PhoneReachability
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.ZonedDateTime

/**
 * Halo's bolus ball in both modes, driven by real taps (as OrbitPickerModeTest): "Feste Menge" sends the request
 * (today's behaviour), "Auswahl" opens QuickBolusActivity without starting the pending lock, never in ambient, and a
 * ball tap in the first second after the screen wakes is ignored.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = Application::class, qualifiers = "de-rDE-w225dp-h225dp-round-xhdpi")
class HaloPickerModeTest {

    private lateinit var sp: SP
    private lateinit var rxBus: RxBus
    private lateinit var face: HaloWatchface
    private lateinit var guard: BolusRequestGuard
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val layout = HaloLayout(450f, 2f)

    @Before fun setUp() {
        PhoneReachability.offlineAnswer = true
        val shared = PreferenceManager.getDefaultSharedPreferences(context)
        shared.edit().clear().commit()
        sp = SPImpl(shared, context)
        sp.putDouble(context.getString(R.string.key_treatments_safety_max_bolus), 3.0)
        sp.putBoolean(BolusRequestPrefs.KEY_ENABLED, true)
        sp.putString(BolusRequestPrefs.KEY_AMOUNT, "0.5")
        rxBus = mock()
    }

    @After fun tearDown() {
        PhoneReachability.offlineAnswer = null
    }

    private fun build() {
        face = Robolectric.buildService(HaloWatchface::class.java).get()
        face.sp = sp
        face.preferences = mock<Preferences> { on { get(any<BooleanKey>()) } doReturn true }
        face.aapsLogger = AAPSLoggerTest()
        face.rxBus = rxBus
        face.aapsSchedulers = mock<AapsSchedulers>()
        val now = System.currentTimeMillis()
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
        guard = face.prepareOffline(ComplicationData(bgData = bg, statusData = st, lastUpdateTimestamp = now))
        guard.onReachAnswer(true)
        frame(ambient = false)
    }

    private fun frame(ambient: Boolean) {
        val bmp = Bitmap.createBitmap(450, 450, Bitmap.Config.ARGB_8888)
        face.renderOffline(Canvas(bmp), ZonedDateTime.now(), System.currentTimeMillis(), ambient, lowBit = false, burnIn = false, is24h = true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tapBall(eventTime: Long) {
        face.tapOffline(TapType.UP, layout.ballX.toInt(), layout.ballY.toInt(), eventTime)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun doubleTap() {
        tapBall(1L)
        Thread.sleep(5)
        guard.onReachAnswer(true) // the fresh answer SEND needs (strictly after the first tap)
        Thread.sleep(5)
        tapBall(2L)
    }

    private fun startedActivity() = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity

    @Test fun fixedModeSendsAsToday() {
        build()
        doubleTap()
        val sent = argumentCaptor<EventWearToMobile>()
        verify(rxBus).send(sent.capture())
        assertThat(sent.firstValue.payload).isEqualTo(EventData.ActionBolusPreCheck(0.5, 0))
        assertThat(sp.getLong(BolusRequestPrefs.KEY_LAST_SENT_AT, 0L)).isGreaterThan(0L)
        assertThat(startedActivity()).isNull()
    }

    @Test fun fixedModeIsTheDefaultWithoutAModeKey() {
        sp.putString(BolusRequestPrefs.KEY_AMOUNT, "1.0")
        build()
        doubleTap()
        val sent = argumentCaptor<EventWearToMobile>()
        verify(rxBus).send(sent.capture())
        assertThat(sent.firstValue.payload).isEqualTo(EventData.ActionBolusPreCheck(1.0, 0))
        assertThat(startedActivity()).isNull()
    }

    @Test fun pickerModeOpensThePickerAndSendsNothing() {
        sp.putString(BolusRequestPrefs.KEY_MODE, BolusRequestPrefs.MODE_PICKER)
        build()
        doubleTap()
        verify(rxBus, never()).send(any())
        // opening does not start the 30 s pending lock
        assertThat(sp.getLong(BolusRequestPrefs.KEY_LAST_SENT_AT, 0L)).isEqualTo(0L)
        val intent = startedActivity()
        assertThat(intent).isNotNull()
        assertThat(intent!!.component?.className).isEqualTo(QuickBolusActivity::class.java.name)
        // its own fresh task (with the manifest's taskAffinity=""): closing it lands on the face, never on a menu behind it
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isEqualTo(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(ComponentName(app, QuickBolusActivity::class.java), 0)
        assertThat(info.taskAffinity.orEmpty()).isNotEqualTo(app.packageName)
    }

    @Test fun pickerModeIgnoresTheFixedAmount() {
        // a fixed amount the picker never offers (1.0 above max 0.7) does not hide the picker ball: 0,5 is allowed
        sp.putString(BolusRequestPrefs.KEY_MODE, BolusRequestPrefs.MODE_PICKER)
        sp.putString(BolusRequestPrefs.KEY_AMOUNT, "1.0")
        sp.putDouble(context.getString(R.string.key_treatments_safety_max_bolus), 0.7)
        build()
        doubleTap()
        verify(rxBus, never()).send(any())
        assertThat(startedActivity()?.component?.className).isEqualTo(QuickBolusActivity::class.java.name)
    }

    @Test fun pickerModeSingleTapOpensNothing() {
        sp.putString(BolusRequestPrefs.KEY_MODE, BolusRequestPrefs.MODE_PICKER)
        build()
        tapBall(1L)
        assertThat(startedActivity()).isNull()
        assertThat(guard.isArmed).isTrue()
    }

    @Test fun pickerModeNotInAmbient() {
        sp.putString(BolusRequestPrefs.KEY_MODE, BolusRequestPrefs.MODE_PICKER)
        build()
        frame(ambient = true)
        doubleTap()
        assertThat(startedActivity()).isNull()
        assertThat(guard.isArmed).isFalse()
        verify(rxBus, never()).send(any())
    }

    @Test fun ballTapsIgnoredForOneSecondAfterWaking() {
        sp.putString(BolusRequestPrefs.KEY_MODE, BolusRequestPrefs.MODE_PICKER)
        build()
        frame(ambient = true)
        frame(ambient = false) // wakes: interactive again
        tapBall(1L)
        assertThat(guard.isArmed).isFalse()
        Thread.sleep(1_050)
        frame(ambient = false)
        tapBall(2L)
        assertThat(guard.isArmed).isTrue()
    }

    @Test fun pendingFromThePickerShowsOnTheBall() {
        sp.putString(BolusRequestPrefs.KEY_MODE, BolusRequestPrefs.MODE_PICKER)
        build()
        // the picker sent a request (shared store): the next ball tap only says "already running"
        sp.putLong(BolusRequestPrefs.KEY_LAST_SENT_AT, System.currentTimeMillis())
        frame(ambient = false)
        doubleTap()
        assertThat(startedActivity()).isNull()
        verify(rxBus, never()).send(any())
    }

    @Test fun configHasTheSharedModeAndOneAndAHalfSwitchAfterTheMasterSwitch() {
        val xml = File("src/main/res/xml/watch_face_configuration_halo.xml").readText()
        val master = xml.indexOf("android:key=\"@string/key_face_bolus_request_enabled\"")
        val mode = xml.indexOf("android:key=\"@string/key_face_bolus_request_mode\"")
        val allow15 = xml.indexOf("android:key=\"@string/key_face_bolus_request_allow_1_5\"")
        assertThat(master).isAtLeast(0)
        assertThat(mode).isGreaterThan(master)
        assertThat(allow15).isGreaterThan(mode)
        val modeBlock = xml.substring(xml.lastIndexOf("<ListPreference", mode), mode)
        assertThat(modeBlock).contains("android:defaultValue=\"fixed\"")
        assertThat(modeBlock).contains("android:dependency=\"@string/key_face_bolus_request_enabled\"")
        val allowBlock = xml.substring(xml.lastIndexOf("<CheckBoxPreference", allow15), allow15)
        assertThat(allowBlock).contains("android:defaultValue=\"false\"")
        assertThat(allowBlock).contains("android:dependency=\"@string/key_face_bolus_request_enabled\"")
    }
}
