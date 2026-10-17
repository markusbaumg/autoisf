package app.aaps.wear.watchfaces.common

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import androidx.preference.PreferenceManager
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.impl.sharedPreferences.SPImpl
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.watchfaces.HaloWatchface
import app.aaps.wear.watchfaces.glass.GlassWatchface
import app.aaps.wear.watchfaces.orbit.OrbitWatchface
import app.aaps.wear.watchfaces.utils.SimpleUi
import app.aaps.wear.watchfaces.utils.WatchFace
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.ZonedDateTime

/**
 * The always-on look of all three faces is black and white only (owner, 17 Oct 2026): the REAL faces are drawn in
 * ambient (WatchFace.renderOffline, as on the watch) and every pixel of the frame must be a grey (R = G = B within
 * [TOLERANCE]), in range, at an urgent low, very high, with old data, with a loop warning, under burn-in protection
 * and in low-bit mode. Low-bit must be pure black and white.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = Application::class, qualifiers = "de-rDE-w225dp-h225dp-round-xhdpi")
class MonoAodRenderTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private enum class Mode { AMBIENT, BURN_IN, LOW_BIT }

    private class Case(val id: String, val mode: Mode, val data: (Long) -> ComplicationData)

    private val cases = listOf(
        Case("in_range", Mode.AMBIENT) { now -> data(now, "6,4", 115.0, "→", 0) },
        Case("urgent_low", Mode.AMBIENT) { now -> data(now, "2,6", 47.0, "⇊", -1) },
        Case("very_high", Mode.AMBIENT) { now -> data(now, "22,2", 400.0, "⇈", 1) },
        Case("stale", Mode.AMBIENT) { now -> data(now, "6,4", 115.0, "→", 0, ageMin = 17, loopAgeMin = 17) },
        Case("loop_23", Mode.AMBIENT) { now -> data(now, "6,4", 115.0, "→", 0, loopAgeMin = 23) },
        Case("burnin_urgent_low", Mode.BURN_IN) { now -> data(now, "2,6", 47.0, "⇊", -1) },
        Case("burnin_in_range", Mode.BURN_IN) { now -> data(now, "6,4", 115.0, "→", 0) },
        Case("lowbit_in_range", Mode.LOW_BIT) { now -> data(now, "6,4", 115.0, "→", 0) },
        Case("lowbit_stale", Mode.LOW_BIT) { now -> data(now, "6,4", 115.0, "→", 0, ageMin = 17, loopAgeMin = 17) }
    )

    @After fun tearDown() {
        PhoneReachability.offlineAnswer = null
    }

    @Test fun ambientIsGreyOnlyOnAllThreeFaces() {
        for (face in listOf("halo", "glass", "orbit")) for (c in cases) {
            val bmp = render(face, c)
            var lit = 0
            var colour: String? = null
            var lowBitGrey: String? = null
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (maxOf(r, g, b) > 20) lit++
                if (colour == null && maxOf(r, g, b) - minOf(r, g, b) > TOLERANCE) colour = "($x,$y) #%02x%02x%02x".format(r, g, b)
                if (lowBitGrey == null && c.mode == Mode.LOW_BIT && r != 0 && r != 255) lowBitGrey = "($x,$y) #%02x%02x%02x".format(r, g, b)
            }
            assertWithMessage("$face/${c.id}: colour").that(colour).isNull()
            assertWithMessage("$face/${c.id}: low-bit grey").that(lowBitGrey).isNull()
            // the BG is drawn (not an empty frame), and the look stays sparse
            val share = lit.toDouble() / (bmp.width * bmp.height)
            assertWithMessage("$face/${c.id}: lit share").that(share).isGreaterThan(0.02)
            assertWithMessage("$face/${c.id}: lit share").that(share).isLessThan(0.15)
        }
    }

    private fun render(face: String, c: Case): Bitmap {
        val shared = PreferenceManager.getDefaultSharedPreferences(context)
        shared.edit().clear().commit()
        val sp: SP = SPImpl(shared, context)
        sp.putDouble(context.getString(R.string.key_treatments_safety_max_bolus), 3.0)
        // the bolus ball enabled: it must never show in ambient
        sp.putBoolean(BolusRequestPrefs.KEY_ENABLED, true)
        sp.putString(BolusRequestPrefs.KEY_AMOUNT, "0.5")
        PhoneReachability.offlineAnswer = true
        val prefs = mock<Preferences> { on { get(any<BooleanKey>()) } doReturn true }
        val wf: WatchFace
        val now = System.currentTimeMillis()
        val d = c.data(now)
        val guard = when (face) {
            "halo"  -> Robolectric.buildService(HaloWatchface::class.java).get().let { f ->
                f.sp = sp; f.preferences = prefs; f.aapsLogger = AAPSLoggerTest(); f.rxBus = mock<RxBus>(); f.aapsSchedulers = mock<AapsSchedulers>()
                wf = f; f.prepareOffline(d)
            }

            "glass" -> Robolectric.buildService(GlassWatchface::class.java).get().let { f ->
                f.sp = sp; f.preferences = prefs; f.aapsLogger = AAPSLoggerTest(); f.rxBus = mock<RxBus>(); f.aapsSchedulers = mock<AapsSchedulers>()
                f.simpleUi = SimpleUi(context, sp, mock<DateUtil>())
                wf = f; f.prepareOffline(d)
            }

            else    -> Robolectric.buildService(OrbitWatchface::class.java).get().let { f ->
                f.sp = sp; f.preferences = prefs; f.aapsLogger = AAPSLoggerTest(); f.rxBus = mock<RxBus>(); f.aapsSchedulers = mock<AapsSchedulers>()
                f.simpleUi = SimpleUi(context, sp, mock<DateUtil>())
                wf = f; f.prepareOffline(d)
            }
        }
        guard.onReachAnswer(true)
        val bmp = Bitmap.createBitmap(450, 450, Bitmap.Config.ARGB_8888)
        repeat(2) {
            Canvas(bmp).let {
                it.drawColor(Color.BLACK)
                wf.renderOffline(
                    it, ZonedDateTime.now(), System.currentTimeMillis(), ambient = true, lowBit = c.mode == Mode.LOW_BIT,
                    burnIn = c.mode == Mode.BURN_IN, is24h = true
                )
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
        return bmp
    }

    private fun data(now: Long, sgvString: String, mgdl: Double, arrow: String, level: Long, ageMin: Int = 2, loopAgeMin: Int = 2): ComplicationData {
        val ts = now - ageMin * 60_000L
        val bg = EventData.SingleBg(
            dataset = 0, timeStamp = ts, sgvString = sgvString, glucoseUnits = "mmol", slopeArrow = arrow, delta = "+0,1",
            deltaDetailed = "+0,1", avgDelta = "+0,1", avgDeltaDetailed = "+0,1", sgvLevel = level, sgv = mgdl, high = 180.0, low = 70.0,
            color = 0, deltaMgdl = 1.8, avgDeltaMgdl = 1.8
        )
        val graph = EventData.GraphData(ArrayList(List(12) { k -> bg.copy(timeStamp = ts - (11 - k) * 5 * 60_000L) }))
        val loopAt = now - loopAgeMin * 60_000L
        val st = EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "1,25", iobDetail = "", cob = "0g", currentBasal = "0,85 U/h", battery = "78",
            rigBattery = "", openApsStatus = loopAt, bgi = "", batteryLevel = 1, patientName = "", tempTarget = "",
            tempTargetLevel = 0, reservoirString = "142U", reservoir = 142.0, reservoirLevel = 0, lastLoopRunAt = loopAt
        )
        return ComplicationData(bgData = bg, graphData = graph, statusData = st, lastUpdateTimestamp = now)
    }

    private companion object {

        /** Channel spread (0..255) still counted as grey. */
        const val TOLERANCE = 6
    }
}
