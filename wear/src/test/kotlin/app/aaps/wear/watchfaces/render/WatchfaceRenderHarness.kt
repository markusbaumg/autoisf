package app.aaps.wear.watchfaces.render

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.wear.watchface.TapType
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
import app.aaps.wear.watchfaces.common.AodStyle
import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import app.aaps.wear.watchfaces.common.PhoneReachability
import app.aaps.wear.watchfaces.glass.GlassFaceLogic
import app.aaps.wear.watchfaces.glass.GlassWatchface
import app.aaps.wear.watchfaces.halo.HaloAod
import app.aaps.wear.watchfaces.halo.HaloLayout
import app.aaps.wear.watchfaces.orbit.OrbitAod
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic
import app.aaps.wear.watchfaces.orbit.OrbitWatchface
import app.aaps.wear.watchfaces.utils.SimpleUi
import app.aaps.wear.watchfaces.utils.WatchFace
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import java.io.File
import kotlin.math.roundToLong
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * OFFLINE RENDER HARNESS (test source set only, never shipped).
 *
 * Draws the REAL Halo, Glass and Orbit drawing code (WatchFace.renderOffline -> onDraw, the same path the renderer
 * takes on the watch) into a 450x450 software canvas with Robolectric's native graphics and writes one PNG per state
 * to `<out>/<face>/<state>.png` plus a contact sheet `<out>/<face>_sheet.png`.
 *
 * Arrows are the phone's TrendArrow symbols (DoubleUp is "\u21c8", not two arrows).
 * Watch: Galaxy Watch5 Pro, 450x450 round, xhdpi (density 2.0), 24 h clock. Data in mmol/L as the phone sends it, in
 * the phone's locale: a German phone sends "6,4", "+0,1", "1,25", "0,85 U/h" (decimal comma), an English one dots.
 * The bolus ball is driven by real taps (WatchFace.tapOffline) through the shared BolusRequestGuard.
 *
 * Runs only on request (excluded otherwise, see wear/build.gradle.kts):
 *   ./gradlew :wear:testFullDebugUnitTest --tests '*WatchfaceRenderHarness*' -PwatchfaceRenderOut=/abs/out/dir
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = Application::class, qualifiers = "de-rDE-w225dp-h225dp-round-xhdpi")
class WatchfaceRenderHarness {

    private val outDir: File? = System.getProperty("watchface.render.out")?.takeIf { it.isNotBlank() }?.let { File(it) }
    /** Optional filter: only the "<face>/<state>" ids this regex finds are rendered (-PwatchfaceRenderOnly). */
    private val only: Regex? = System.getProperty("watchface.render.only")?.takeIf { it.isNotBlank() }?.let { Regex(it) }

    enum class Face(val id: String) { HALO("halo"), GLASS("glass"), ORBIT("orbit") }

    enum class Ball { OFF, IDLE, ARMED, SENT, DISABLED }

    enum class Mode { INTERACTIVE, AMBIENT, LOW_BIT, BURN_IN }

    /** One render: data built relative to the real clock at draw time (the faces compute ages from it). */
    data class RenderState(
        val id: String,
        val lang: String = "de",
        val px: Int = 450,
        val mode: Mode = Mode.INTERACTIVE,
        val ball: Ball = Ball.OFF,
        val phoneReachable: Boolean = true,
        val clock: ZonedDateTime = SHOWN_TIME,
        /** The shared always-on style (face_aod_style), written to the prefs before the face is built. */
        val aod: AodStyle = AodStyle.DEFAULT,
        /** Orbit only (AOD exploration): draw this look in ambient regardless of [aod]; other faces skip the state. */
        val orbitLook: OrbitAod.Look? = null,
        /** Halo only (AOD exploration): draw this look in ambient regardless of [aod]; other faces skip the state. */
        val haloLook: HaloAod.Look? = null,
        val data: (now: Long) -> ComplicationData
    )

    // ------------------------------------------------------------------ data

    /** States in which Orbit drew no date (must stay empty). */
    private val dateMisses = ArrayList<String>()

    /** Orbit discs round 4: states in which Orbit's date glyphs came closer than 6 px to the ring's inner edge. */
    private val dateRingHits = ArrayList<String>()
    /** Glass polish round 7: the hero group (digits, chip) closer than [HERO_SIDE_MIN_PX] to the card's stroke. */
    private val heroSideHits = ArrayList<String>()

    /** Locale of the phone that "sent" the data of the state being rendered (German: decimal comma). */
    private var phoneGerman = true

    /** A phone string with the phone locale's decimal separator. */
    private fun loc(s: String): String = if (phoneGerman) s.replace('.', ',') else s

    private fun bg(
        now: Long, mmol: Double, arrow: String, delta: String, level: Long, ageMin: Int = 2, mgdlUnits: Boolean = false, history: List<Double> = emptyList()
    ): Pair<EventData.SingleBg, EventData.GraphData> {
        val ts = now - ageMin * 60_000L
        val mgdl = mmol * 18.0
        val sgvString = if (mgdlUnits) "%.0f".format(Locale.US, mgdl) else loc("%.1f".format(Locale.US, mmol))
        val deltaMmol = delta.toDoubleOrNull() ?: 0.0
        val single = EventData.SingleBg(
            dataset = 0, timeStamp = ts, sgvString = sgvString, glucoseUnits = if (mgdlUnits) "mg/dl" else "mmol",
            slopeArrow = arrow, delta = if (mgdlUnits) "%+.0f".format(Locale.US, deltaMmol * 18) else loc(delta),
            deltaDetailed = loc(delta), avgDelta = if (mgdlUnits) "%+.0f".format(Locale.US, deltaMmol * 18) else loc(delta), avgDeltaDetailed = loc(delta),
            sgvLevel = level, sgv = mgdl, high = 180.0, low = 70.0, color = 0, deltaMgdl = deltaMmol * 18, avgDeltaMgdl = deltaMmol * 18
        )
        // 3 h of history ending at the current value (oldest first), 5 min apart.
        val pts = ArrayList<EventData.SingleBg>()
        val n = 36
        val hist = history.ifEmpty { List(n) { k -> mmol - deltaMmol * (n - 1 - k) * 0.6 } }
        hist.forEachIndexed { k, v ->
            val t = ts - (hist.size - 1 - k) * 5 * 60_000L
            pts.add(single.copy(timeStamp = t, sgv = v.coerceAtLeast(2.2) * 18.0, sgvString = loc("%.1f".format(Locale.US, v))))
        }
        return single to EventData.GraphData(pts)
    }

    private fun status(
        now: Long, loopAgeMin: Int? = 2, iob: String = "1.25", cob: String = "12g", basal: String = "0.85 U/h",
        tt: String = "5.5", ttLevel: Int = 0, external: String = "", reservoir: String = "142U", reservoirLevel: Int = 0,
        battery: String = "78", batteryLevel: Int = 1
    ): EventData.Status {
        val loopAt = loopAgeMin?.let { now - it * 60_000L } ?: -1L
        return EventData.Status(
            dataset = 0, externalStatus = external, iobSum = loc(iob), iobDetail = loc("(0.80|0.45)"), cob = cob, currentBasal = loc(basal),
            battery = battery, rigBattery = "64%", openApsStatus = loopAt, bgi = loc("-0.4"), batteryLevel = batteryLevel, patientName = "",
            tempTarget = loc(tt), tempTargetLevel = ttLevel, reservoirString = reservoir, reservoir = 142.0, reservoirLevel = reservoirLevel,
            lastLoopRunAt = if (loopAt > 0) loopAt else 0L
        )
    }

    private fun data(
        now: Long, mmol: Double, arrow: String, delta: String, level: Long, ageMin: Int = 2, phoneAgeMin: Int = 0,
        mgdl: Boolean = false, st: EventData.Status = status(now), history: List<Double> = emptyList()
    ): ComplicationData {
        val (b, g) = bg(now, mmol, arrow, delta, level, ageMin, mgdl, history)
        return ComplicationData(bgData = b, graphData = g, statusData = st, lastUpdateTimestamp = now - phoneAgeMin * 60_000L)
    }

    private val inRange: (Long) -> ComplicationData = { now -> data(now, 6.4, "→", "+0.1", 0) }

    /**
     * Glass polish round 1: a realistic trace with a forecast (the phone's main prediction series), as on the watch:
     * [history] in mmol/l (oldest first, 5' apart, ending at the current value), [forecast] for the next hour.
     */
    private fun traced(now: Long, history: List<Double>, forecast: List<Double>, arrow: String, delta: String, level: Long, st: EventData.Status = status(now)): ComplicationData {
        val (b, g) = bg(now, history.last(), arrow, delta, level, ageMin = 0, history = history)
        val preds = ArrayList(forecast.mapIndexed { k, v -> b.copy(timeStamp = b.timeStamp + (k + 1) * 5 * 60_000L, sgv = v * 18.0, color = 0xFFAA82FF.toInt()) })
        return ComplicationData(
            bgData = b, graphData = g, statusData = st, lastUpdateTimestamp = now,
            treatmentData = EventData.TreatmentData(ArrayList(), ArrayList(), ArrayList(), preds)
        )
    }

    /**
     * 3 h of a calm BG around 5,8 as in the owner's live screenshot. Glass polish round 2: an irregular CGM-like walk
     * (0,1 mmol/l steps, a slow drift), not a regular sine that read as an artificial wave.
     */
    private val flatHistory = listOf(
        6.0, 6.0, 6.1, 6.1, 6.0, 5.9, 5.9, 6.0, 6.0, 5.9, 5.8, 5.8, 5.9, 5.9, 5.9, 5.8, 5.7, 5.7,
        5.8, 5.8, 5.9, 5.9, 5.8, 5.8, 5.7, 5.7, 5.7, 5.8, 5.8, 5.9, 5.8, 5.8, 5.7, 5.8, 5.8, 5.8
    )

    /** The same calm walk shifted to [level] mmol/l (near the low or the high edge of the band). */
    private fun flatAt(level: Double) = flatHistory.map { ((it - 5.8 + level) * 10).roundToLong() / 10.0 }

    /** A meal: 5,6 rising to 10,2 and back down to 6,4. */
    private val mealHistory = List(36) { k ->
        val t = k / 35.0
        5.6 + 4.6 * kotlin.math.exp(-((t - 0.45) * (t - 0.45)) / 0.03) + 1.0 * t
    }

    private fun states(): List<RenderState> = listOf(
        RenderState("reference_final4", clock = ZonedDateTime.of(2026, 10, 17, 2, 28, 0, 0, ZONE)) { now ->
            val (b, g) = bg(now, 5.5, "→", "-0.1", 0, ageMin = 1)
            ComplicationData(
                bgData = b, graphData = g, lastUpdateTimestamp = now,
                statusData = status(now, loopAgeMin = 0, iob = "0.44", cob = "0g", basal = "0.00 U/h", tt = "5.5", ttLevel = 2)
            )
        },
        RenderState("in_range", data = inRange),
        RenderState("live_flat_pred", ball = Ball.IDLE, clock = ZonedDateTime.of(2026, 10, 17, 9, 40, 0, 0, ZONE)) { now ->
            traced(now, flatHistory, List(12) { 5.8 - 0.03 * it }, "→", "-0.0", 0,
                st = status(now, loopAgeMin = 0, iob = "0.40", cob = "0g", basal = "0.00 U/h"))
        },
        RenderState("flat_4_3_pred", ball = Ball.IDLE) { now -> traced(now, flatAt(4.3), List(12) { 4.3 - 0.02 * it }, "→", "+0.0", 0) },
        RenderState("flat_9_5_pred", ball = Ball.IDLE) { now -> traced(now, flatAt(9.5), List(12) { 9.5 + 0.03 * it }, "→", "+0.1", 0) },
        RenderState("meal_pred") { now -> traced(now, mealHistory, List(12) { 6.4 - 0.06 * it }, "↘", "-0.2", 0) },
        RenderState("high_pred", ball = Ball.IDLE) { now -> traced(now, List(36) { k -> 8.0 + 4.4 * k / 35.0 }, List(12) { 12.4 + 0.1 * it - 0.02 * it * it }, "↗", "+0.4", 1) },
        RenderState("high") { now -> data(now, 12.4, "↗", "+0.4", 1) },
        // Glass polish round 2: bounded ramps (the default history ran below 2,2 and lay flat on the plot floor)
        RenderState("very_high") { now -> data(now, 22.2, "\u21c8", "+1.2", 1, history = List(36) { k -> 12.0 + 10.2 * (k / 35.0) * (k / 35.0) }) },
        RenderState("low") { now -> data(now, 3.4, "↓", "-0.3", -1) },
        RenderState("low_3_5_fall") { now -> data(now, 3.5, "↘", "-0.2", -1, history = List(36) { k -> 6.0 - 2.5 * (k / 35.0) }) },
        RenderState("urgent_low") { now -> data(now, 2.6, "\u21ca", "-0.6", -1, history = List(36) { k -> 7.0 - 4.4 * (k / 35.0) * (k / 35.0) }) },
        // Glass polish round 6 (sparkline safety): a rebound high after a low (2,8 for an hour, then up to 16,7), a
        // forecast-only dip (in range now, the forecast under the low line), a low NOW after a meal peak 2 h ago
        RenderState("rebound_high") { now ->
            data(now, 16.7, "\u21c8", "+1.0", 1, history = List(36) { k -> if (k < 14) 2.8 + 0.05 * (k % 3) else 2.8 + 13.9 * ((k - 13) / 22.0) * ((k - 13) / 22.0) })
        },
        RenderState("forecast_dip", ball = Ball.IDLE) { now ->
            traced(now, List(36) { k -> 7.5 - 2.3 * (k / 35.0) }, List(12) { k -> (5.0 - 0.25 * (k + 1)).coerceAtLeast(3.3) }, "↘", "-0.2", 0)
        },
        RenderState("meal_then_low") { now ->
            traced(now, List(36) { k -> 5.5 + 6.5 * kotlin.math.exp(-((k - 10.0) * (k - 10.0)) / 30.0) - (if (k > 10) 2.0 * (k - 10) / 25.0 else 0.0) }
                .mapIndexed { k, v -> if (k == 35) 3.5 else v }, List(12) { k -> 3.5 + 0.05 * (k + 1) }, "↘", "-0.2", -1)
        },
        RenderState("mgdl_224") { now -> data(now, 224 / 18.0, "↗", "+0.4", 1, mgdl = true) },
        RenderState("stale_17") { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        RenderState("very_stale_45") { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 45, st = status(now, loopAgeMin = 45)) },
        RenderState("no_data_ever") { _ -> ComplicationData() },
        RenderState("phone_disconnected_14", phoneReachable = false) { now ->
            data(now, 6.4, "→", "+0.1", 0, ageMin = 14, phoneAgeMin = 14, st = status(now, loopAgeMin = 14))
        },
        RenderState("loop_23") { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, loopAgeMin = 23)) },
        RenderState("loop_15") { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, loopAgeMin = 15)) },
        RenderState("loop_off") { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, loopAgeMin = 8, external = "Loop disabled\n")) },
        RenderState("aging_8") { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 8) },
        RenderState("sentinel_low_39") { now -> data(now, 2.15, "↓", "-0.3", -1) },
        RenderState("sentinel_high_400") { now -> data(now, 22.3, "↗", "+0.4", 1) },
        RenderState("no_profile") { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, iob = "", cob = "", basal = "", external = "Kein Profil")) },
        RenderState("loop_never_pump") { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, loopAgeMin = null)) },
        RenderState("reservoir_critical") { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, reservoir = "5U", reservoirLevel = 2)) },
        RenderState("phone_battery_low") { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, battery = "8", batteryLevel = 0)) },
        RenderState("tt_active") { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, tt = "8.0", ttLevel = 2, basal = "120%")) },
        RenderState("phone_lost_fresh", phoneReachable = false, data = inRange),
        RenderState("low_loop_23") { now -> data(now, 3.2, "↘", "-0.2", -1, st = status(now, loopAgeMin = 23)) },
        RenderState("ball_idle", ball = Ball.IDLE, data = inRange),
        RenderState("ball_armed", ball = Ball.ARMED, data = inRange),
        RenderState("ball_sent", ball = Ball.SENT, data = inRange),
        RenderState("ball_disabled", ball = Ball.DISABLED, data = inRange),
        RenderState("ambient", mode = Mode.AMBIENT, data = inRange),
        RenderState("ambient_lowbit", mode = Mode.LOW_BIT, data = inRange),
        RenderState("ambient_burnin", mode = Mode.BURN_IN, data = inRange),
        RenderState("ambient_stale", mode = Mode.AMBIENT) { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        RenderState("ambient_high", mode = Mode.AMBIENT) { now -> data(now, 12.4, "↗", "+0.4", 1) },
        RenderState("ambient_low", mode = Mode.AMBIENT) { now -> data(now, 3.4, "↓", "-0.3", -1) },
        RenderState("ambient_urgent_low", mode = Mode.AMBIENT) { now -> data(now, 2.6, "\u21ca", "-0.6", -1) },
        RenderState("lowbit_urgent_low", mode = Mode.LOW_BIT) { now -> data(now, 2.6, "\u21ca", "-0.6", -1) },
        RenderState("lowbit_stale", mode = Mode.LOW_BIT) { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        RenderState("burnin_loop_off", mode = Mode.BURN_IN) { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, loopAgeMin = 8, external = "Loop disabled\n")) },
        RenderState("round_384", px = 384, data = inRange),
        RenderState("round_480", px = 480, data = inRange),
        RenderState("en_in_range", lang = "en", data = inRange),
        RenderState("en_loop_23", lang = "en") { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, loopAgeMin = 23)) },
        RenderState("en_phone_disconnected_14", lang = "en", phoneReachable = false) { now ->
            data(now, 6.4, "→", "+0.1", 0, ageMin = 14, phoneAgeMin = 14, st = status(now, loopAgeMin = 14))
        },
        RenderState("en_no_data_ever", lang = "en") { _ -> ComplicationData() },
        RenderState("en_ball_idle", lang = "en", ball = Ball.IDLE, data = inRange),
        // Orbit discs round 3: worst cases of the squeezed hero, between the bolus button at 9 and the TT disc at 3
        RenderState("ball_tt_very_high", ball = Ball.IDLE) { now -> data(now, 22.2, "\u21c8", "+1.2", 1, st = status(now, tt = "8.0", ttLevel = 2)) },
        RenderState("ball_tt_10_5", ball = Ball.IDLE) { now -> data(now, 10.5, "↗", "+0.3", 1, st = status(now, tt = "8.0", ttLevel = 2)) },
        RenderState("ball_tt_mgdl_224", ball = Ball.IDLE) { now -> data(now, 224 / 18.0, "↗", "+0.4", 1, mgdl = true, st = status(now, tt = "144", ttLevel = 2)) },
        // Halo declutter round: the owner's live screenshot (9,8, zero temp, 0 g, IOB 3,65, ball on), and carbs on board
        RenderState("live_halo_9_8", ball = Ball.IDLE, clock = ZonedDateTime.of(2026, 10, 17, 14, 26, 0, 0, ZONE)) { now ->
            data(now, 9.8, "→", "-0.0", 0, ageMin = 0, st = status(now, loopAgeMin = 0, iob = "3.65", cob = "0g", basal = "0.00 U/h"))
        },
        RenderState("cob_tt_low_zero_temp") { now ->
            data(now, 3.6, "↘", "-0.2", -1, st = status(now, iob = "0.20", cob = "24g", basal = "0.00 U/h", tt = "7.0", ttLevel = 2))
        },
        RenderState("tbr_percent") { now -> data(now, 11.2, "↗", "+0.3", 1, st = status(now, iob = "2.10", cob = "0g", basal = "150%")) }
    ) + aodStates() + exploreStates()

    /** AOD round 2: the cases every always-on style must handle, as (id, mode, ball-free data). */
    private fun aodCases(): List<Triple<String, Mode, (Long) -> ComplicationData>> = listOf(
        Triple("in_range", Mode.AMBIENT, inRange),
        Triple("aging_8", Mode.AMBIENT) { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 8) },
        Triple("stale", Mode.AMBIENT) { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        Triple("urgent_low", Mode.AMBIENT) { now -> data(now, 2.6, "\u21ca", "-0.6", -1, history = List(36) { k -> 7.0 - 4.4 * (k / 35.0) * (k / 35.0) }) },
        Triple("low", Mode.AMBIENT) { now -> data(now, 3.4, "↓", "-0.3", -1) },
        Triple("high", Mode.AMBIENT) { now -> data(now, 12.4, "↗", "+0.4", 1) },
        Triple("very_high", Mode.AMBIENT) { now -> data(now, 22.2, "\u21c8", "+1.2", 1, history = List(36) { k -> 12.0 + 10.2 * (k / 35.0) * (k / 35.0) }) },
        Triple("loop_off", Mode.AMBIENT) { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, loopAgeMin = 8, external = "Loop disabled\n")) },
        Triple("loop_23", Mode.AMBIENT) { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, loopAgeMin = 23)) },
        Triple("urgent_low_loop_23", Mode.AMBIENT) { now -> data(now, 2.6, "\u21ca", "-0.6", -1, st = status(now, loopAgeMin = 23)) },
        Triple("no_phone_14", Mode.AMBIENT) { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 14, phoneAgeMin = 14, st = status(now, loopAgeMin = 14)) },
        Triple("mgdl", Mode.AMBIENT) { now -> data(now, 224 / 18.0, "↗", "+0.4", 1, mgdl = true) },
        Triple("no_data_ever", Mode.AMBIENT) { _ -> ComplicationData() },
        Triple("lowbit", Mode.LOW_BIT, inRange),
        Triple("lowbit_urgent_low", Mode.LOW_BIT) { now -> data(now, 2.6, "\u21ca", "-0.6", -1) },
        Triple("lowbit_stale", Mode.LOW_BIT) { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        Triple("burnin", Mode.BURN_IN, inRange),
        Triple("burnin_urgent_low", Mode.BURN_IN) { now -> data(now, 2.6, "\u21ca", "-0.6", -1) },
        Triple("burnin_loop_off", Mode.BURN_IN) { now -> data(now, 6.4, "→", "+0.1", 0, st = status(now, loopAgeMin = 8, external = "Loop disabled\n")) },
        // the Watch5 Pro most likely runs with burn-in protection: these are what the owner sees
        Triple("burnin_stale", Mode.BURN_IN) { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        // panel round 3: the struck old value must stay readable (8 / 0 / 6 / 9 differ at the digits' waist)
        Triple("stale_8_0", Mode.AMBIENT) { now -> data(now, 8.0, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        Triple("stale_6_8", Mode.AMBIENT) { now -> data(now, 6.8, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        Triple("stale_9_8", Mode.AMBIENT) { now -> data(now, 9.8, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        Triple("stale_mgdl_180", Mode.AMBIENT) { now -> data(now, 10.0, "→", "+0.1", 0, ageMin = 17, mgdl = true, st = status(now, loopAgeMin = 17)) },
        Triple("lowbit_stale_8_0", Mode.LOW_BIT) { now -> data(now, 8.0, "→", "+0.1", 0, ageMin = 17, st = status(now, loopAgeMin = 17)) },
        Triple("burnin_no_phone_14", Mode.BURN_IN) { now -> data(now, 6.4, "→", "+0.1", 0, ageMin = 14, phoneAgeMin = 14, st = status(now, loopAgeMin = 14)) }
    )

    /** aod_<style>_<case> for each value of the shared always-on setting. */
    private fun aodStates(): List<RenderState> = AodStyle.entries.flatMap { style ->
        aodCases().map { (id, mode, d) ->
            RenderState("aod_${style.prefValue}_$id", mode = mode, aod = style, phoneReachable = !id.endsWith("no_phone_14"), data = d)
        }
    }

    /** AOD exploration: Orbit's and Halo's looks in in_range, urgent_low and stale (each face renders only its own). */
    private fun exploreStates(): List<RenderState> {
        val cases = aodCases().filter { it.first in setOf("in_range", "urgent_low", "stale") }
        return OrbitAod.Look.entries.flatMap { look ->
            cases.map { (id, mode, d) -> RenderState("explore_${look.name.lowercase()}_$id", mode = mode, orbitLook = look, data = d) }
        } + HaloAod.Look.entries.flatMap { look ->
            cases.map { (id, mode, d) -> RenderState("explore_${look.name.lowercase()}_$id", mode = mode, haloLook = look, data = d) }
        }
    }

    // ------------------------------------------------------------------ test

    @After
    fun tearDown() {
        PhoneReachability.offlineAnswer = null
    }

    @Test
    fun renderAllFaces() {
        val out = outDir
        assumeTrue("set -PwatchfaceRenderOut=<dir> to render", out != null)
        out!!
        val states = states()
        val edgeHits = ArrayList<String>()
        for (face in Face.entries) {
            val dir = File(out, face.id).apply { mkdirs() }
            val rendered = ArrayList<Pair<String, Bitmap>>()
            val lit = StringBuilder("state\tlit_pct\n")
            for (s in states) {
                if (s.orbitLook != null && face != Face.ORBIT) continue
                if (s.haloLook != null && face != Face.HALO) continue
                if (only?.containsMatchIn("${face.id}/${s.id}") == false) continue
                val bmp = render(face, s)
                FileOutputStream(File(dir, "${s.id}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                rendered += s.id to bmp
                edgeHit(bmp)?.let { edgeHits += "${face.id}/${s.id}: $it" }
                lit.append(s.id).append('\t').append("%.2f".format(Locale.US, 100.0 * litRatio(bmp))).append('\n')
            }
            if (rendered.isEmpty()) continue
            File(dir, "lit.tsv").writeText(lit.toString())
            writeSheet(face, rendered, File(out, "${face.id}_sheet.png"))
        }
        // Glass/Orbit UX round 1: no drawn content (text, glyphs) within 14 px of the round edge (450 grid), where the
        // Watch5 Pro's raised bezel shades it. Checked after all renders are written, so a failure still leaves them.
        assertTrue("content within $EDGE_MARGIN_PX px of the bezel:\n" + edgeHits.joinToString("\n"), edgeHits.isEmpty())
        assertTrue("no date drawn in:\n" + dateMisses.joinToString("\n"), dateMisses.isEmpty())
        assertTrue("date closer than ${OrbitFaceLogic.DATE_RING_CLEAR} px to the ring:\n" + dateRingHits.joinToString("\n"), dateRingHits.isEmpty())
        assertTrue("hero group closer than $HERO_SIDE_MIN_PX px to the card:\n" + heroSideHits.joinToString("\n"), heroSideHits.isEmpty())
    }

    /**
     * First bright pixel (any channel > [EDGE_LEVEL]) closer than [EDGE_MARGIN_PX] (scaled from the 450 grid) to the
     * round edge, as "(x,y) d px", or null. The faint background glow stays below the level.
     */
    private fun edgeHit(bmp: Bitmap): String? {
        val r = bmp.width / 2f
        val margin = EDGE_MARGIN_PX * bmp.width / 450f
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val d = kotlin.math.hypot(x + 0.5f - r, y + 0.5f - r)
            if (d < r - margin || d > r - 1f) continue
            val c = bmp.getPixel(x, y)
            if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) > EDGE_LEVEL) return "($x,$y) ${"%.1f".format(r - d)} px from the edge"
        }
        return null
    }

    /** AOD round 2: share of the round dial's pixels with a luminance above 20 (0..255), the AMOLED lit share. */
    private fun litRatio(bmp: Bitmap): Double {
        val r = bmp.width / 2.0
        var inside = 0
        var lit = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            if (kotlin.math.hypot(x + 0.5 - r, y + 0.5 - r) > r) continue
            inside++
            val c = bmp.getPixel(x, y)
            if (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c) > 20.0) lit++
        }
        return if (inside == 0) 0.0 else lit.toDouble() / inside
    }

    // ------------------------------------------------------------------ rendering

    private fun render(face: Face, s: RenderState): Bitmap {
        val dp = s.px / 2
        RuntimeEnvironment.setQualifiers("${if (s.lang == "de") "de-rDE" else "en-rUS"}-w${dp}dp-h${dp}dp-round-xhdpi")
        Locale.setDefault(if (s.lang == "de") Locale.GERMANY else Locale.US)
        val context: Context = RuntimeEnvironment.getApplication()
        val shared = PreferenceManager.getDefaultSharedPreferences(context)
        shared.edit().clear().commit()
        val sp: SP = SPImpl(shared, context)
        prefs(context, sp, s)
        PhoneReachability.offlineAnswer = s.phoneReachable

        val wf: WatchFace = when (face) {
            Face.HALO  -> Robolectric.buildService(HaloWatchface::class.java).get().also { inject(it, sp, context) }
            Face.GLASS -> Robolectric.buildService(GlassWatchface::class.java).get().also { inject(it, sp, context) }
            Face.ORBIT -> Robolectric.buildService(OrbitWatchface::class.java).get().also { inject(it, sp, context) }
        }
        val now = System.currentTimeMillis()
        phoneGerman = s.lang == "de"
        val d = s.data(now)
        val guard: BolusRequestGuard = when (wf) {
            is HaloWatchface  -> { wf.aodLookOverride = s.haloLook; wf.prepareOffline(d) }
            is GlassWatchface -> wf.prepareOffline(d)
            is OrbitWatchface -> { wf.aodLookOverride = s.orbitLook; wf.prepareOffline(d) }
            else              -> error("unknown face")
        }
        // The phone's (asynchronous) reachability answer, as after the face's first lookup.
        guard.onReachAnswer(s.phoneReachable)

        val ambient = s.mode != Mode.INTERACTIVE
        val lowBit = s.mode == Mode.LOW_BIT
        val burnIn = s.mode == Mode.BURN_IN
        val bmp = Bitmap.createBitmap(s.px, s.px, Bitmap.Config.ARGB_8888)
        val frame = { Canvas(bmp).let { wf.renderOffline(it, s.clock, System.currentTimeMillis(), ambient, lowBit, burnIn, is24h = true) } }

        // Warm-up frame (layout, caches; Glass bakes its backgrounds off the render thread), then settle.
        frame()
        settle()
        // Ball taps run on the real clock (armed window 800 ms): no settling between the taps and the final frame.
        when (s.ball) {
            Ball.ARMED -> tapBall(face, wf, s.px)
            Ball.SENT  -> {
                tapBall(face, wf, s.px)
                // The fresh "reachable" answer arrives asynchronously after the first tap (strictly later).
                Thread.sleep(5)
                guard.onReachAnswer(s.phoneReachable)
                Thread.sleep(5)
                tapBall(face, wf, s.px, eventTime = 2L)
                // the steady SENT state: after the 150 ms send flash (white rim), as the user sees it for 30 s
                Thread.sleep(220)
            }

            else       -> settle()
        }
        frame()
        frame()
        // Glass/Orbit UX round 5 (time/date in every state): Orbit draws the date line in every state
        // AOD round 2: the always-on looks have no date (time and BG only)
        if (wf is OrbitWatchface && s.mode == Mode.INTERACTIVE && wf.drawnDateText.isBlank()) dateMisses += "orbit/${s.id}"
        if (wf is OrbitWatchface && wf.drawnDateText.isNotBlank()) {
            val b = wf.drawnDateBox
            val clear = listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)
                .minOf { (x, y) -> OrbitFaceLogic.ringClear(x, y) }
            if (clear < OrbitFaceLogic.DATE_RING_CLEAR) dateRingHits += "orbit/${s.id}: '${wf.drawnDateText}' ${"%.1f".format(clear)} px"
        }
        if (wf is GlassWatchface && s.mode == Mode.INTERACTIVE && s.px == 450) {
            val (left, right) = wf.heroSidePadding
            if (min(left, right) < HERO_SIDE_MIN_PX) heroSideHits += "glass/${s.id}: left ${"%.1f".format(left)} px, right ${"%.1f".format(right)} px"
        }
        roundMask(bmp)
        return bmp
    }

    private fun settle() {
        // Run main-looper work (coroutine resumes, posted redraws) for a while; Glass' bake runs on Dispatchers.Default.
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tapBall(face: Face, wf: WatchFace, px: Int, eventTime: Long = 1L) {
        val scale = px / 450f
        val (x, y) = when (face) {
            Face.HALO  -> HaloLayout(px.toFloat(), 2f).let { it.ballX to it.ballY }
            Face.GLASS -> GlassFaceLogic.BALL_X * scale to GlassFaceLogic.BALL_Y * scale
            Face.ORBIT -> OrbitFaceLogic.BOLUS_X * scale to OrbitFaceLogic.BOLUS_Y * scale
        }
        wf.tapOffline(TapType.UP, x.toInt(), y.toInt(), eventTime)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun prefs(context: Context, sp: SP, s: RenderState) {
        val maxBolusKey = context.getString(R.string.key_treatments_safety_max_bolus)
        sp.putDouble(maxBolusKey, 3.0)
        sp.putString(AodStyle.KEY, s.aod.prefValue)
        when (s.ball) {
            Ball.OFF      -> sp.putBoolean(BolusRequestPrefs.KEY_ENABLED, false)
            Ball.DISABLED -> {
                // Enabled, but the amount is above the phone's max bolus: drawn dimmed, a tap only explains why.
                sp.putBoolean(BolusRequestPrefs.KEY_ENABLED, true)
                sp.putString(BolusRequestPrefs.KEY_AMOUNT, "1.0")
                sp.putDouble(maxBolusKey, 0.5)
            }

            else          -> {
                sp.putBoolean(BolusRequestPrefs.KEY_ENABLED, true)
                sp.putString(BolusRequestPrefs.KEY_AMOUNT, "0.5")
            }
        }
    }

    private fun inject(wf: HaloWatchface, sp: SP, context: Context) {
        wf.sp = sp
        wf.preferences = preferences()
        wf.aapsLogger = AAPSLoggerTest()
        wf.rxBus = mock<RxBus>()
        wf.aapsSchedulers = mock<AapsSchedulers>()
    }

    private fun inject(wf: GlassWatchface, sp: SP, context: Context) {
        wf.sp = sp
        wf.preferences = preferences()
        wf.aapsLogger = AAPSLoggerTest()
        wf.rxBus = mock<RxBus>()
        wf.aapsSchedulers = mock<AapsSchedulers>()
        wf.simpleUi = SimpleUi(context, sp, mock<DateUtil>())
    }

    private fun inject(wf: OrbitWatchface, sp: SP, context: Context) {
        wf.sp = sp
        wf.preferences = preferences()
        wf.aapsLogger = AAPSLoggerTest()
        wf.rxBus = mock<RxBus>()
        wf.aapsSchedulers = mock<AapsSchedulers>()
        wf.simpleUi = SimpleUi(context, sp, mock<DateUtil>())
    }

    private fun preferences(): Preferences = mock<Preferences> {
        on { get(any<BooleanKey>()) } doReturn true
    }

    /** The round display: everything outside the inscribed circle is black (as on the watch). */
    private fun roundMask(bmp: Bitmap) {
        val c = Canvas(bmp)
        val r = bmp.width / 2f
        val path = Path().apply {
            addCircle(r, r, r, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
    }

    // ------------------------------------------------------------------ contact sheet

    private fun writeSheet(face: Face, items: List<Pair<String, Bitmap>>, file: File) {
        val cols = 6
        val cell = 300
        val pad = 16
        val label = 30
        val header = 64
        val rows = ceil(items.size / cols.toFloat()).toInt()
        val w = cols * (cell + pad) + pad
        val h = header + rows * (cell + label + pad) + pad
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(0xFF15171C.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6E8EE.toInt(); textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
        c.drawText("${face.id}  ·  ${items.size} states  ·  480 px = 300 px here, all to scale", pad.toFloat(), 44f, text)
        text.textSize = 19f
        text.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        items.forEachIndexed { i, (id, bmp) ->
            val col = i % cols
            val row = i / cols
            val x = pad + col * (cell + pad)
            val y = header + row * (cell + label + pad)
            val size = bmp.width * cell / 480f
            val off = (cell - min(size, cell.toFloat())) / 2f
            val dst = android.graphics.RectF(x + off, y + off, x + off + size, y + off + size)
            c.drawBitmap(bmp, null, dst, paint)
            c.drawText(id, x.toFloat(), (y + cell + 22).toFloat(), text)
        }
        FileOutputStream(file).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {

        private val ZONE: ZoneId = ZoneId.of("Europe/Vienna")

        /** Shown clock: Sat 17 Oct 2026, 14:27 (ages use the real clock). */
        private val SHOWN_TIME: ZonedDateTime = ZonedDateTime.of(2026, 10, 17, 14, 27, 0, 0, ZONE)
    }
}

private const val EDGE_MARGIN_PX = 14f
/** Glass polish round 7: glass between the hero group (digits, chip) and the card's stroke, each side (450 grid). */
private const val HERO_SIDE_MIN_PX = 12f
private const val EDGE_LEVEL = 110
