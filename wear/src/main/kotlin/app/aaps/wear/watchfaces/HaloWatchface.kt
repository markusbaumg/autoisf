package app.aaps.wear.watchfaces

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventUpdateSelectedWatchface
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.EventData.ActionResendData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.wear.R
import app.aaps.wear.comm.DataLayerListenerServiceWear
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.events.EventWearPreferenceChange
import app.aaps.wear.interaction.actions.QuickBolusActivity
import app.aaps.wear.interaction.menus.MainMenuActivity
import app.aaps.wear.watchfaces.common.MonoAod
import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.BolusRequestGuard.Reason
import app.aaps.wear.watchfaces.common.BolusRequestGuard.State
import app.aaps.wear.watchfaces.common.BolusRequestGuard.TapResult
import app.aaps.wear.watchfaces.common.LoopRunTime
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import app.aaps.wear.watchfaces.common.BolusTapGesture
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.PhoneReachability
import app.aaps.wear.watchfaces.halo.HaloAod
import app.aaps.wear.watchfaces.halo.HaloFaceLogic
import app.aaps.wear.watchfaces.halo.HaloLayout
import app.aaps.wear.watchfaces.utils.WatchFace
import app.aaps.wear.watchfaces.utils.WatchMode
import app.aaps.wear.watchfaces.utils.WatchfaceViewAdapter.Companion.SelectedWatchFace
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dagger.android.AndroidInjection
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "AAPS Halo": a calm, Canvas-drawn watch face. Hero BG in its range colour (with a "TIEF" / "HOCH" caption, so colour
 * is never the only carrier), one gradient ring (BG on a 2.2..16.7 mmol/L scale with the target band, a knob and a
 * 30 min trend tail), the time, arrow + delta + BG age (always shown), the loop pill with IOB (and COB while carbs are on
 * board) and one status line that is drawn only when it has something to say (the highest family warning W1..W9,
 * otherwise an active temp target and a temp basal that matters). Declutter round: no date, no outer IOB arc, no
 * "0 g", no routine basal.
 *
 * Always-on: the family's one black-and-white look ([MonoAod], via [HaloAod]): the BG and its arrow, at most one grey
 * line of words; never the bolus ball.
 *
 * Every state (BG age, range, loop health, phone link, warning order) comes from the family helper [FaceState]
 * (designsystem/states.md), shared with Glass and Orbit.
 *
 * Optional "+X U" ball (bottom centre in the loop pill's slot, with a loop badge so the loop state stays visible; off by default) sends a bolus REQUEST on double-tap, exactly like BolusActivity
 * (ActionBolusPreCheck). The phone checks everything and the watch shows the standard confirmation; nothing is
 * delivered without confirming there. In the shared "Auswahl" mode the ball shows "+ U" and the same double tap opens
 * QuickBolusActivity instead (no request yet, no pending lock), as Orbit. Interactive mode only: never in ambient, never in a headless (picker)
 * instance and never in a non-live render (screenshot / highlight layer). An editor preview drawn through the live
 * instance with default render parameters cannot be told apart; the face has no complications or styles, so the
 * system has no reason to open its editor.
 *
 * All sizes come from [HaloLayout] (fractions of the face diameter, with a text-size floor and a 48 dp tap target).
 * Strings, parsed values, gradients and font metrics are cached; per frame only the small [FaceState] snapshot is built.
 */
@SuppressLint("Deprecated")
class HaloWatchface : WatchFace() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var sp: SP
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var complicationDataRepository: ComplicationDataRepository

    private val disposable = CompositeDisposable()
    private val watchfaceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var data: ComplicationData = ComplicationData()

    /** Headless instance (picker / editor preview): no side effects, never a bolus ball. */
    @Volatile private var headless = true

    // Shared bolus request rules (watchfaces/common); persisted pending time, so a recreated service or another
    // face still knows about a request whose confirmation may be open.
    private val guard = BolusRequestGuard(clock = { System.currentTimeMillis() }, store = BolusRequestPrefs.lastSentStore { sp })
    /** While armed: redraw the countdown ring (~30 fps, at most the 800 ms window), then disarm silently. */
    private val armedTick = object : Runnable {
        override fun run() {
            invalidate()
            if (guard.gesture.expireIfDue() || !guard.isArmed) return
            handler.postDelayed(this, FRAME_MS)
        }
    }
    private val redraw = Runnable { invalidate() }

    // Phone reachability (the data layer drops messages silently without a reachable node); kept in the guard.
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }
    // info.nodes is not filtered by reachability: re-query with FILTER_REACHABLE instead of trusting it.
    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { refreshPhoneReachable() }
    private var capabilityListening = false

    /** Side effects of the active instance (last-selected pref, resend, phone tracking) run once per service. */
    private var activeSetupDone = false

    // Layout for the current face diameter (rebuilt only when the size changes), its offset in the canvas,
    // and the last drawn state, for tap handling.
    private var L = HaloLayout(450f)
    private var layoutD = 0f
    private var offX = 0f
    private var offY = 0f
    private val cx get() = L.c
    private val cy get() = L.c
    private var buttonShown = false
    private var bgTapTime = 0L
    private var lastMenuOpenTime = 0L

    // Paints, created once.
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val bounds = Rect()
    private val glyphPath = Path()
    /** Time the face last became interactive; a ball tap within [WAKE_IGNORE_MS] after it is ignored (sleeve, palm). */
    private var interactiveSince = 0L
    private val tf400 = Typeface.create(Typeface.DEFAULT, 400, false)
    private val tf300 = Typeface.create(Typeface.DEFAULT, 300, false)
    private val tf500 = Typeface.create(Typeface.DEFAULT, 500, false)
    private val tf600 = Typeface.create(Typeface.DEFAULT, 600, false)
    private val tf700 = Typeface.create(Typeface.DEFAULT, 700, false)

    // Vibration patterns: armed = one short tick, sent = two ticks, refused = one long buzz.
    private val vibArmed by lazy { VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE) }
    private val vibSent by lazy { VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), -1) }
    private val vibRefused by lazy { VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE) }

    // Cached labels.
    private val againLabel by lazy { getString(R.string.halo_bolus_again) }
    private val unitLabel by lazy { getString(R.string.face_bolus_unit) }
    private val loopOffLabel by lazy { getString(R.string.face_loop_off_short) }
    private val loopOpenLabel by lazy { getString(R.string.face_loop_open) }

    // Per-value caches (rebuilt only when the input changes).
    private var timeKey = -1
    private var timeStr = ""
    private var arrowSrc: String? = null
    private var arrowStr = ""
    private var ageKey = Long.MIN_VALUE
    private var ageStr = HaloFaceLogic.NO_DATA
    private var amountKey = Double.NaN
    private var amountStr = ""
    private var drawnAmount = Double.NaN
    /** Last valid loop time (LoopRunTime.of(status) > 0), persisted; an older phone sends -1 on runs without a new temp basal. */
    private var loopMemory = HaloFaceLogic.LoopTimeMemory({ 0L }, {})
    private val loopTime get() = loopMemory.time
    private val fm = Paint.FontMetrics()
    private var backdropColour = 0
    private var backdropShader: RadialGradient? = null
    private var sweepKey = Int.MIN_VALUE
    private var sweepShader: SweepGradient? = null
    private val sweepMatrix = Matrix()

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        // No preference writes or resend requests here: the service is also created for the picker preview.
        // Only a valid time received from the phone is stored (the same in every instance).
        loopMemory = HaloFaceLogic.LoopTimeMemory({ sp.getLong(KEY_LAST_LOOP_TIME, 0L) }, { sp.putLong(KEY_LAST_LOOP_TIME, it) })
        watchfaceScope.launch {
            complicationDataRepository.complicationData.collect { onData(it) }
        }
        disposable += rxBus
            .toObservable(EventWearPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                drawnAmount = Double.NaN
                if (bolusCheck().state != State.IDLE && guard.reset()) handler.removeCallbacks(armedTick)
                invalidate()
            }
        disposable += rxBus
            .toObservable(EventData.Preferences::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { invalidate() }
    }

    private fun onData(d: ComplicationData) {
        data = d
        loopMemory.onReceived(LoopRunTime.of(d.statusData))
        if (::sp.isInitialized) readLoopMode()
        invalidate()
    }

    /**
     * Offline render harness only (wear/src/test); never called on the watch. The state of the live instance after
     * onCreate + createWatchFace (loop memory, not headless) without injection or side effects, then [d] as received
     * data. The injected fields must be set before. Returns the bolus guard (phone answer, taps).
     */
    @VisibleForTesting
    internal fun prepareOffline(d: ComplicationData): BolusRequestGuard {
        headless = false
        loopMemory = HaloFaceLogic.LoopTimeMemory({ sp.getLong(KEY_LAST_LOOP_TIME, 0L) }, { sp.putLong(KEY_LAST_LOOP_TIME, it) })
        onData(d)
        return guard
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): androidx.wear.watchface.WatchFace {
        headless = watchState.isHeadless
        if (!headless && !activeSetupDone) {
            // Only the real, active instance becomes the "last selected" face and asks the phone for data,
            // once per service even if the system opens a second (non-headless) engine.
            activeSetupDone = true
            // Per-face settings of earlier iterations (kusi_ / halo_ / orbit_) move to the shared pair.
            BolusRequestPrefs.migrate(sp)
            sp.putInt(R.string.key_last_selected_watchface, SelectedWatchFace.HALO.ordinal)
            rxBus.send(EventUpdateSelectedWatchface())
            rxBus.send(EventWearToMobile(ActionResendData("HaloWatchface::createWatchFace")))
            startPhoneTracking()
        }
        return super.createWatchFace(surfaceHolder, watchState, complicationSlotsManager, currentUserStyleRepository)
    }

    override fun onDestroy() {
        handler.removeCallbacks(armedTick)
        disposable.clear()
        watchfaceScope.cancel()
        if (capabilityListening) {
            try {
                capabilityClient.removeListener(capabilityListener, DataLayerListenerServiceWear.PHONE_CAPABILITY)
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "HaloWatchface removeListener failed", e)
            }
        }
        super.onDestroy()
    }

    override fun onWatchModeChanged(watchMode: WatchMode) {
        if (watchMode != WatchMode.INTERACTIVE) {
            guard.reset()
            handler.removeCallbacks(armedTick)
        } else interactiveSince = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------- phone reachability

    private fun startPhoneTracking() {
        try {
            capabilityClient.addListener(capabilityListener, DataLayerListenerServiceWear.PHONE_CAPABILITY)
            capabilityListening = true
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "HaloWatchface addListener failed", e)
        }
        refreshPhoneReachable()
    }

    private fun refreshPhoneReachable() {
        if (headless) return
        PhoneReachability.refresh(this, guard, aapsLogger, "HaloWatchface") { invalidate() }
    }

    // ---------------------------------------------------------------- preferences

    private fun bolusAmount(): Double = BolusRequestPrefs.amount(sp)
    private fun maxBolus(): Double = BolusRequestPrefs.maxBolus(sp, getString(R.string.key_treatments_safety_max_bolus))

    /** "Feste Menge" (the double tap sends [bolusAmount]) or "Auswahl" (the same double tap opens QuickBolusActivity). */
    private fun pickerMode(): Boolean = BolusRequestPrefs.mode(sp) == BolusRequestPrefs.Mode.PICKER

    private fun bolusInput(): BolusRequestGuard.Input {
        val picker = pickerMode()
        // picker: the ball is usable when the smallest amount is (each amount is checked again in the picker)
        val pickerAmounts = if (picker) BolusRequestPrefs.pickerAmounts(sp) else BolusTapGesture.ALLOWED_AMOUNTS
        return BolusRequestGuard.Input(
            prefOn = BolusRequestPrefs.enabled(sp),
            ambient = isAmbient,
            wearControl = preferences.get(BooleanKey.WearControl),
            amount = if (picker) pickerAmounts.min() else bolusAmount(),
            maxBolus = maxBolus(),
            lastPhoneMessage = data.lastUpdateTimestamp,
            bgTimeStamp = data.bgData.timeStamp,
            headless = headless || !isLiveRender,
            firstStart = HaloFaceLogic.firstStart(data.bgData.timeStamp, LoopRunTime.of(data.statusData), data.statusData.iobSum),
            allowedAmounts = pickerAmounts
        )
    }

    /** The ball exists only with the setting on, Wear control allowed, a valid amount, interactive, not in a preview. */
    private fun bolusCheck(): BolusRequestGuard.Check = guard.check(bolusInput())

    // ---------------------------------------------------------------- state (family FaceState)

    /** Everything one frame needs to know, computed once per frame by the family helper [FaceState]. */
    private class S(
        val ageMin: Long?,
        val bgAge: FaceState.BgAge,
        val trend: FaceState.Trend,
        val range: FaceState.Range,
        val caption: FaceState.Caption,
        val bgText: String,
        val showValue: Boolean,
        val loopAge: Long?,
        val loop: FaceState.LoopHealth,
        val owner: FaceState.Warning?,
        val ownerText: FaceState.StatusText?,
        val phoneLostFresh: Boolean,
        val firstStart: Boolean
    )

    private fun state(now: Long, ambient: Boolean): S {
        val ageMin = FaceState.ageMinutes(bg.timeStamp, now)
        val bgAge = FaceState.bgAge(ageMin)
        val deltaMgdl = if (bg.timeStamp > 0L) bg.deltaMgdl else null
        val trend = FaceState.trend(bg.slopeArrow, deltaMgdl)
        val range = FaceState.range(bg.sgv, bg.sgvLevel, bgAge, FaceState.fastFall(trend, deltaMgdl))
        val bgText = FaceState.bgText(bg.sgvString, bg.sgv, ageMin)
        val firstStart = FaceState.firstStart(bg.timeStamp, loopTime, st.iobSum)
        val loopAge = HaloFaceLogic.loopAgeMinutes(loopTime, now)
        val run = FaceState.loopRun(st.externalStatus, st.iobSum)
        val loop = if (firstStart) FaceState.LoopHealth.NEVER
        else FaceState.loopHealth(loopAge, run, loopMode, realRun = st.lastLoopRunAt > 0L)
        val link = if (headless) FaceState.PhoneLink.OK else FaceState.phoneLink(guard.phoneReach, data.lastUpdateTimestamp, now)
        val warnings = FaceState.warnings(
            FaceState.WarningInput(
                firstStart = firstStart, link = link, bgAge = bgAge, range = range,
                noProfile = FaceState.noProfile(run, st.iobSum, firstStart), loop = loop,
                reservoirString = st.reservoirString, reservoirLevel = st.reservoirLevel,
                phoneBattery = st.battery, phoneBatteryLevel = st.batteryLevel
            )
        )
        val owner = FaceState.owner(warnings, ambient)
        val ownerText = owner?.let {
            FaceState.statusText(
                it, words(), FaceState.ageMinutes(data.lastUpdateTimestamp, now), ageMin, loopAge, st.reservoirString, st.battery
            )
        }
        return S(
            ageMin, bgAge, trend, range, FaceState.caption(range), bgText, bgText != FaceState.NO_BG, loopAge, loop, owner, ownerText,
            phoneLostFresh = link == FaceState.PhoneLink.LOST_FRESH && !ambient, firstStart = firstStart
        )
    }

    /** Loop mode from the LoopStatesList the phone sent (dataset 0, best effort), re-read only when the data changes. */
    private var loopMode = FaceState.LoopMode.UNKNOWN

    private fun readLoopMode() {
        loopMode = try {
            val list = EventData.deserialize(sp.getString(R.string.key_loop_states_data, "")) as? EventData.LoopStatesList
            if (list == null) FaceState.LoopMode.UNKNOWN
            else FaceState.loopMode(list.states.map { it.state.name }, list.timeStamp, data.lastUpdateTimestamp)
        } catch (_: Exception) {
            FaceState.LoopMode.UNKNOWN
        }
    }

    private var wordsCache: FaceState.Words? = null
    private var wordsLocale: Locale? = null

    /** Family words from the resources (German in every de locale), rebuilt when the locale changes. */
    private fun words(): FaceState.Words {
        val locale = resources.configuration.locales[0]
        wordsCache?.let { if (locale == wordsLocale) return it }
        return FaceState.Words(
            minutes = getString(R.string.face_minutes, 0).replace("0", "%d"),
            hours = getString(R.string.face_hours, 0).replace("0", "%d"),
            days = getString(R.string.face_days, 0).replace("0", "%d"),
            waiting = getString(R.string.face_waiting),
            phoneLost = getString(R.string.face_phone_lost, "%s"),
            noData = getString(R.string.face_no_data, "%s"),
            noProfile = getString(R.string.face_no_profile),
            loopOff = getString(R.string.face_loop_off),
            loopAge = getString(R.string.face_loop_age, "%s"),
            reservoir = getString(R.string.face_reservoir, "%s"),
            phoneBattery = getString(R.string.face_phone_batt, 0).replace("%", "%%").replace("0", "%d"),
            low = getString(R.string.face_low),
            high = getString(R.string.face_high)
        ).also {
            wordsCache = it
            wordsLocale = locale
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val d = min(w, h)
        if (d != layoutD) {
            layoutD = d
            L = HaloLayout(d, resources.displayMetrics.density)
            backdropShader = null
            sweepShader = null
        }
        offX = (w - d) / 2f
        offY = (h - d) / 2f
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate(offX, offY)
        try {
            if (isAmbient) drawAmbient(canvas) else drawInteractive(canvas)
        } catch (e: Exception) {
            // A headless (preview) instance may be created without injection.
            if (::aapsLogger.isInitialized) aapsLogger.error(LTag.WEAR, "HaloWatchface draw failed", e)
        }
        canvas.restore()
    }

    private val bg get() = data.bgData
    private val st get() = data.statusData

    /** Range hue (family tokens); stale / no data is the stale grey. */
    private fun rangeColour(r: FaceState.Range): Int = when (r) {
        FaceState.Range.IN                                -> GREEN
        FaceState.Range.HIGH, FaceState.Range.VERY_HIGH   -> AMBER
        FaceState.Range.LOW, FaceState.Range.URGENT_LOW   -> RED
        FaceState.Range.STALE                             -> STALE
    }

    private fun lowMgdl() = FaceState.lowLine(bg.low)
    private fun highMgdl() = FaceState.highLine(bg.low, bg.high)

    private fun resetPaint(antiAlias: Boolean = true): Paint = p.apply {
        reset()
        isAntiAlias = antiAlias
    }

    private fun arcRect(r: Float): RectF = rect.apply { set(cx - r, cy - r, cx + r, cy + r) }

    private fun pointX(angle: Float, r: Float) = cx + r * cos(Math.toRadians(angle.toDouble())).toFloat()
    private fun pointY(angle: Float, r: Float) = cy + r * sin(Math.toRadians(angle.toDouble())).toFloat()

    private fun levelColour(level: FaceState.Level, normal: Int): Int = when (level) {
        FaceState.Level.NORMAL -> normal
        FaceState.Level.WARN   -> AMBER
        FaceState.Level.ALERT  -> RED
    }

    private fun drawInteractive(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val s = state(now, ambient = false)
        val colour = rangeColour(s.range)
        val stale = FaceState.isStruck(s.bgAge)
        val emphasis = FaceState.emphasis(s.range)
        val bgAngle = HaloFaceLogic.angle(bg.sgv)
        val lowA = HaloFaceLogic.angle(lowMgdl())
        val highA = HaloFaceLogic.angle(highMgdl())
        val ringW = if (emphasis) L.ringWEmphasis else L.ringW

        // 1. soft backdrop in the range colour (cached shader); stronger for urgent low / very high
        if (s.showValue && !stale) {
            resetPaint().apply { shader = backdrop(colour, emphasis) }
            canvas.drawCircle(cx, cy, L.backdropR, p)
        }

        // 2. ring: track, target band, value fill with glow, threshold cuts
        val ring = arcRect(L.ringR)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = ringW; strokeCap = Paint.Cap.ROUND; color = TRACK }
        canvas.drawArc(ring, HaloFaceLogic.RING_START, HaloFaceLogic.RING_SWEEP, false, p)
        p.strokeCap = Paint.Cap.BUTT
        p.color = BAND
        canvas.drawArc(ring, lowA, highA - lowA, false, p)

        if (s.showValue) {
            val sweep = bgAngle - HaloFaceLogic.RING_START
            // Glow via shadow layer: BlurMaskFilter is ignored on the hardware canvas the renderer uses.
            resetPaint().apply {
                style = Paint.Style.STROKE; strokeWidth = ringW; strokeCap = Paint.Cap.ROUND
                shader = if (stale) null else sweep(colour, sweep)
                color = if (stale) withAlpha(STALE, 0x6E) else Color.WHITE
                if (!stale) setShadowLayer(L.ringGlow, 0f, 0f, withAlpha(colour, 0x88))
            }
            canvas.drawArc(ring, HaloFaceLogic.RING_START, sweep, false, p)
        }

        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.cutStroke; color = Color.BLACK }
        drawCut(canvas, lowA, L.cutIn, L.cutOut)
        drawCut(canvas, highA, L.cutIn, L.cutOut)

        // 3. trend tail inside the ring (fresh data only), then the knob (hollow when stale)
        if (s.showValue) {
            if (!stale) drawTrendTail(canvas, now, bgAngle)
            val kx = pointX(bgAngle, L.ringR)
            val ky = pointY(bgAngle, L.ringR)
            // Dark separation ring, so the knob does not merge into the ring gradient.
            resetPaint().apply { color = Color.BLACK }
            canvas.drawCircle(kx, ky, L.knobR + L.knobSeparation, p)
            if (stale) {
                resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ambStroke; color = STALE }
                canvas.drawCircle(kx, ky, L.knobR - L.ambStroke, p)
                resetPaint().apply { color = STALE }
                canvas.drawCircle(kx, ky, L.dotR * 0.6f, p)
            } else {
                resetPaint().apply { color = Color.WHITE; setShadowLayer(L.knobGlow, 0f, 0f, withAlpha(colour, 0x99)) }
                canvas.drawCircle(kx, ky, L.knobR, p)
                resetPaint().apply { color = colour }
                canvas.drawCircle(kx, ky, L.dotR, p)
            }
        }

        // 4. time (no date line: declutter round)
        drawText(canvas, timeString(), cx, L.timeY, L.timeSize, tf500, TIME)

        // 5. hero BG in range colour, with the TIEF / HOCH caption
        drawHeroBg(canvas, s, colour)

        // 6. meta line: arrow + delta + BG age (always), or the W2 / W3 warning with the age
        drawMetaLine(canvas, s, colour)

        // 7. IOB, and COB while carbs are on board (anchored at the drawn pill, so both gaps to it stay equal)
        val check = bolusCheck()
        buttonShown = check.state != State.HIDDEN
        val pill = if (buttonShown) null else measurePill(loopLabel(s))
        drawIobCob(canvas, pill)

        // 8. loop pill (the bolus ball takes its slot when enabled and then carries the loop state as a badge)
        if (pill != null) drawLoopPill(canvas, s, pill)

        // 9. status line, only when needed: the slot owner (W1, W4-W9), otherwise an active temp target and a temp
        //    basal that matters
        drawSecondaryLine(canvas, s)

        // 10. bolus ball (only when enabled; otherwise not drawn and not tappable)
        if (buttonShown) drawBolusButton(canvas, check.state, guard.isArmed, s, now)
        else if (guard.reset()) handler.removeCallbacks(armedTick)
        // The SENT look ends with the shared pending lock, also when the picker (not this face) started it.
        if (check.state == State.SENT) {
            handler.removeCallbacks(redraw)
            handler.postDelayed(redraw, guard.pendingLeftMs() + 10)
        }
    }

    /**
     * Always-on in the family's one black-and-white look ([MonoAod], owner: "simple and black and white only"): the BG
     * with the trend arrow and at most one grey line of words (TIEF / HOCH, old data, loop or phone). No time, ring,
     * tail, pill, IOB or bolus ball (never in ambient). Low-bit: pure white, no anti-aliasing. Burn-in protection:
     * everything shifts by up to 4 px every minute. Ages are computed at draw time (once a minute).
     */
    private fun drawAmbient(canvas: Canvas) {
        val now = System.currentTimeMillis()
        buttonShown = false
        val s = state(now, ambient = true)
        val model = HaloAod.model(
            hero = s.bgText, range = s.range, bgAge = s.bgAge, trend = s.trend, caption = s.caption, warning = s.ownerText,
            age = ageString(s.ageMin), agoFormat = getString(R.string.face_aod_age_ago).replace("%1\$s", "%s")
        )
        aodPainter.lowWord = words().low
        aodPainter.highWord = words().high
        canvas.save()
        try {
            if (burnInProtection) {
                val key = (if (latestEpochMillis > 0L) latestEpochMillis else now) / FaceState.MINUTE_MS
                canvas.translate(L.burnIn(HaloFaceLogic.burnInOffsetX(key)), L.burnIn(HaloFaceLogic.burnInOffsetY(key)))
            }
            canvas.scale(L.u, L.u)
            aodPainter.draw(canvas, model, lowBitAmbient, burnInProtection)
        } finally {
            canvas.restore()
        }
    }

    private val aodPainter = MonoAod.Painter()

    private fun drawCut(canvas: Canvas, angle: Float, rIn: Float, rOut: Float) =
        canvas.drawLine(pointX(angle, rIn), pointY(angle, rIn), pointX(angle, rOut), pointY(angle, rOut), p)

    /**
     * Short trend tail: one arc from the oldest reading of the last [TAIL_WINDOW_MS] (30 min) to the current BG,
     * fading in towards the knob. It shows the same recent direction as the arrow and delta; it is deliberately
     * not a history trace (a 3 h start-to-now arc hid swings like 100 -> 220 -> 100).
     */
    private fun drawTrendTail(canvas: Canvas, now: Long, bgAngle: Float) {
        val from = now - TAIL_WINDOW_MS
        var firstTs = Long.MAX_VALUE
        var firstSgv = 0.0
        val entries = data.graphData.entries
        for (i in 0 until entries.size) {
            val e = entries[i]
            if (e.timeStamp >= from && e.sgv > 0 && e.timeStamp < firstTs) {
                firstTs = e.timeStamp
                firstSgv = e.sgv
            }
        }
        if (firstTs == Long.MAX_VALUE) return
        val startA = HaloFaceLogic.angle(firstSgv)
        // A tail shorter than tailMinLen reads as a stray tick, not a trend (the arrow and delta carry it): skip it.
        if (Math.toRadians(abs(bgAngle - startA).toDouble()) * L.tailR < L.tailMinLen) return
        // Inside the ring (clear of the knob and of the bolus ball), butt caps so segments do not overlap into beads.
        // Faint (an echo of the trend) and with a gap at 12 o'clock, so it never points at the time or the date.
        val r = arcRect(L.tailR)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.tailW; strokeCap = Paint.Cap.BUTT }
        val segments = 24
        val step = (bgAngle - startA) / segments
        val gapLo = 270f - L.tailGapHalf
        val gapHi = 270f + L.tailGapHalf
        for (i in 0 until segments) {
            p.color = withAlpha(TAIL, 15 + (L.tailMaxAlpha - 15) * (i + 1) / segments)
            val a0 = min(startA + i * step, startA + (i + 1) * step)
            val a1 = a0 + abs(step)
            // Draw only the parts outside the gap.
            if (a0 < gapLo) canvas.drawArc(r, a0, min(a1, gapLo) - a0, false, p)
            if (a1 > gapHi) canvas.drawArc(r, max(a0, gapHi), a1 - max(a0, gapHi), false, p)
        }
    }

    /**
     * Hero BG (phone string, "---" without a value) and its caption: "TIEF" word (low), "TIEF" badge (urgent low),
     * "HOCH" word (very high). Digits and caption are centred as one group. Stale: grey + strike.
     */
    private fun drawHeroBg(canvas: Canvas, s: S, colour: Int) {
        val stale = FaceState.isStruck(s.bgAge) && s.showValue
        val value = s.bgText
        val captionWord = when (s.caption) {
            FaceState.Caption.LOW_WORD, FaceState.Caption.LOW_BADGE -> words().low
            FaceState.Caption.HIGH_WORD                             -> words().high
            FaceState.Caption.NONE                                  -> ""
        }
        val badge = s.caption == FaceState.Caption.LOW_BADGE
        // caption width at full size
        text.reset()
        text.typeface = tf700
        text.textSize = L.captionSize
        val capW = if (captionWord.isEmpty()) 0f else text.measureText(captionWord) + if (badge) 2f * L.captionPad else 0f
        val capBlock = if (capW > 0f) capW + L.captionGap else 0f

        text.reset()
        text.isAntiAlias = true
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = -0.02f
        text.fontFeatureSettings = "tnum"   // tabular figures: the width does not jump between values
        text.typeface = tf600
        text.textSize = L.heroSize
        val digitsW = text.measureText(value)
        // 4-char values like "10,4" stay inside the ring; with a caption the whole group is fitted.
        val scale = if (capBlock > 0f) HaloFaceLogic.fitScale(digitsW + capBlock, L.heroGroupMaxW) else HaloFaceLogic.fitScale(digitsW, L.heroMaxW)
        text.textSize = L.heroSize * scale
        val digitsX = cx - capBlock * scale / 2f
        val baseline = L.heroY + text.textSize * 0.36f
        when {
            stale         -> text.color = STALE
            !s.showValue  -> text.color = TXT3
            else          -> {
                text.color = colour
                text.setShadowLayer(L.heroGlow, 0f, 0f, withAlpha(colour, 0x55))
            }
        }
        canvas.drawText(value, digitsX, baseline, text)
        val shownW = text.measureText(value)
        val heroColour = text.color
        if (stale) {
            // Struck through: an old value must never look current.
            val half = shownW / 2f + L.heroStrike * 2f
            resetPaint().apply { color = heroColour; strokeWidth = L.heroStrike * 1.5f }
            canvas.drawLine(digitsX - half, L.heroY, digitsX + half, L.heroY, p)
        }
        if (captionWord.isEmpty()) return
        // Caption: left edge after the digits, top-aligned to the digits' cap height.
        val capLeft = digitsX + shownW / 2f + L.captionGap * scale
        val capTop = baseline - text.textSize * HaloLayout.CAP_HEIGHT
        val boxH = L.captionBadgeH
        val capCentreY = capTop + boxH / 2f
        val wordColour = when {
            badge                                      -> Color.WHITE
            s.caption == FaceState.Caption.HIGH_WORD   -> AMBER
            else                                       -> RED
        }
        if (badge) {
            rect.set(capLeft, capTop, capLeft + capW, capTop + boxH)
            resetPaint().apply { color = RED }
            canvas.drawRoundRect(rect, L.captionRadius, L.captionRadius, p)
        }
        text.reset()
        text.isAntiAlias = true
        text.textAlign = Paint.Align.CENTER
        text.typeface = tf700
        text.textSize = L.captionSize
        text.color = wordColour
        canvas.drawText(captionWord, capLeft + capW / 2f, baselineFor(capCentreY), text)
    }

    /**
     * Meta line under the BG ([HaloFaceLogic.meta]): "→ +0,1 · 2′" with the BG age always shown; "17′" alone for an old
     * reading; "Keine Daten · 17′" / "Telefon getrennt · 14′" in alert when W2 / W3 own the slot. The bold arrow is
     * centred on the delta's cap height (its own ink box, not the font box), so it does not sit low.
     */
    private fun drawMetaLine(canvas: Canvas, s: S, rangeCol: Int) {
        val y = L.deltaY + L.deltaSize * HaloLayout.CAP_HEIGHT / 2f
        when (HaloFaceLogic.meta(s.bgAge, s.owner)) {
            HaloFaceLogic.Meta.WARNING         -> {
                val t = s.ownerText ?: return
                drawFitText(canvas, t.text, cx, L.deltaY, L.deltaSize, tf600, levelColour(t.level, TXT2), maxWidth = L.metaMaxW)
                return
            }

            HaloFaceLogic.Meta.AGE_ONLY        -> {
                if (s.firstStart) return
                // no reading at all: "--" in grey (a missing value is never red)
                drawText(canvas, ageString(s.ageMin), cx, L.deltaY, L.deltaSize, tf500, TXT3)
                return
            }

            HaloFaceLogic.Meta.TREND_DELTA_AGE -> Unit
        }
        val arrow = arrowString()
        val delta = FaceState.deltaText(bg.delta)
        val age = ageString(s.ageMin)
        val ageColour = levelColour(FaceState.ageLevel(s.bgAge), DELTA)
        text.reset()
        text.isAntiAlias = true
        text.textAlign = Paint.Align.LEFT
        text.typeface = tf700
        text.textSize = L.arrowSize
        var arrowW = 0f
        var arrowBaseline = 0f
        if (arrow.isNotEmpty()) {
            text.getTextBounds(arrow, 0, arrow.length, bounds)
            arrowW = text.measureText(arrow)
            arrowBaseline = L.deltaY - (bounds.top + bounds.bottom) / 2f
        }
        text.typeface = tf500
        text.textSize = L.deltaSize
        val deltaW = text.measureText(delta)
        val sepW = text.measureText(SEP)
        val ageW = text.measureText(age)
        val glyphW = if (s.phoneLostFresh) L.arrowGap * 2f + L.phoneGlyphW else 0f
        val gap = if (arrow.isEmpty()) 0f else L.arrowGap
        var x = cx - (arrowW + gap + deltaW + sepW + ageW + glyphW) / 2f
        if (arrow.isNotEmpty()) {
            text.typeface = tf700
            text.textSize = L.arrowSize
            text.color = rangeCol
            canvas.drawText(arrow, x, arrowBaseline, text)
            x += arrowW + gap
            text.typeface = tf500
            text.textSize = L.deltaSize
        }
        text.color = if (delta == FaceState.NO_DATA) TXT3 else DELTA
        canvas.drawText(delta, x, y, text)
        x += deltaW
        text.color = TXT3
        canvas.drawText(SEP, x, y, text)
        x += sepW
        text.color = ageColour
        canvas.drawText(age, x, y, text)
        x += ageW
        if (s.phoneLostFresh) drawPhoneOff(canvas, x + L.arrowGap * 2f, L.deltaY, TXT2)
    }

    /** W10: a small phone outline with a slash (phone not reachable, data still fresh). */
    private fun drawPhoneOff(canvas: Canvas, left: Float, centreY: Float, colour: Int) {
        val w = L.phoneGlyphW
        val h = L.phoneGlyphH
        rect.set(left, centreY - h / 2f, left + w, centreY + h / 2f)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ambStroke; color = colour }
        canvas.drawRoundRect(rect, w * 0.25f, w * 0.25f, p)
        p.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(left - w * 0.3f, centreY + h * 0.55f, left + w * 1.3f, centreY - h * 0.55f, p)
    }

    /**
     * Status line between the meta line and the pill, drawn only when it has something to say: the slot owner
     * (W1 "Warte auf AAPS", W4-W9) in its level colour; otherwise "TT 5,6" (an active temp target, tt) and / or the
     * basal when a temp basal matters ([HaloFaceLogic.basalShown]: a TBR percentage, or zero during a low); otherwise
     * nothing (declutter round: no routine "0,00 U/h").
     */
    private fun drawSecondaryLine(canvas: Canvas, s: S) {
        val owner = HaloFaceLogic.secondaryOwner(s.owner)
        val t = s.ownerText
        if (owner != null && t != null) {
            drawFitText(canvas, t.text, cx, L.secondaryY, L.secondarySize, tf600, levelColour(t.level, TXT2), maxWidth = L.secondaryMaxW)
            return
        }
        if (s.firstStart) return
        val tt = FaceState.display(st.tempTarget)
        val ttText = if (st.tempTargetLevel == 2 && tt != FaceState.NO_DATA) "TT $tt" else ""
        val freshLow = FaceState.isLow(s.range)
        val basal = if (HaloFaceLogic.basalShown(st.currentBasal, freshLow)) FaceState.spacedUnit(st.currentBasal) else ""
        if (ttText.isEmpty() && basal.isEmpty()) return
        val basalColour = when (FaceState.basalTone(st.currentBasal, freshLow)) {
            FaceState.Tone.TBR  -> TBR
            FaceState.Tone.WARN -> AMBER
            FaceState.Tone.TXT3 -> TXT3
            else                -> SECONDARY
        }
        // One or two runs: "TT 5,6" in the tt hue, separator, basal in its tone; fitted together.
        val sep = if (ttText.isNotEmpty() && basal.isNotEmpty()) HaloFaceLogic.SEP else ""
        text.reset()
        text.isAntiAlias = true
        text.textAlign = Paint.Align.LEFT
        text.typeface = tf500
        text.textSize = L.secondarySize
        val w1 = text.measureText(ttText)
        val w2 = text.measureText(sep)
        val w3 = text.measureText(basal)
        text.textSize = L.secondarySize * HaloFaceLogic.fitScale(w1 + w2 + w3, L.secondaryMaxW)
        val k = text.textSize / L.secondarySize
        var x = cx - (w1 + w2 + w3) * k / 2f
        val baseline = baselineFor(L.secondaryY)
        text.color = TT
        canvas.drawText(ttText, x, baseline, text)
        x += w1 * k
        text.color = TXT3
        canvas.drawText(sep, x, baseline, text)
        x += w2 * k
        text.color = basalColour
        canvas.drawText(basal, x, baseline, text)
    }

    /**
     * Bottom row beside the pill / ball: IOB (right) as one number, COB (left) only while carbs are on board
     * ([HaloFaceLogic.cobShown]). [pill]: the drawn loop pill (its width sets the anchors); null when the ball is shown.
     */
    private fun drawIobCob(canvas: Canvas, pill: HaloLayout.Pill?) {
        // Unit and colour say which is which; the unit is the value's hue at ~65 %. Values as the phone sent them.
        if (HaloFaceLogic.cobShown(st.cob)) {
            val (cobValue, cobUnit) = HaloFaceLogic.valueUnit(st.cob, "g")
            drawValueUnit(canvas, cobValue, cobUnit, pill?.let { L.cobRightFor(it.halfW) } ?: L.cobRight, alignRight = true, COB_VALUE, withAlpha(COB_VALUE, L.rowUnitAlpha))
        }
        val (iobValue, iobUnit) = HaloFaceLogic.valueUnit(st.iobSum, "U")
        val iobColour = if (iobValue == FaceState.NO_DATA) TXT3 else IOB_VALUE
        drawValueUnit(canvas, iobValue, iobUnit, pill?.let { L.iobLeftFor(it.halfW) } ?: L.iobLeft, alignRight = false, iobColour, withAlpha(IOB_VALUE, L.rowUnitAlpha))
    }

    /**
     * "1,44" + smaller "U" on the same baseline in the bottom row, shrunk to [HaloLayout.rowMaxW]; anchored at [anchorX]
     * with its right edge ([alignRight], COB) or its left edge (IOB), so the gaps to the pill stay equal.
     */
    private fun drawValueUnit(canvas: Canvas, value: String, unit: String, anchorX: Float, alignRight: Boolean, valueColour: Int, unitColour: Int) {
        text.reset()
        text.isAntiAlias = true
        text.textAlign = Paint.Align.LEFT
        text.typeface = tf500
        text.textSize = L.rowValueSize
        val vw = text.measureText(value)
        text.typeface = tf600
        text.textSize = L.rowUnitSize
        val uw = if (unit.isEmpty()) 0f else text.measureText(unit) + L.rowUnitGap
        val scale = HaloFaceLogic.fitScale(vw + uw, L.rowMaxW)
        var x = if (alignRight) anchorX - (vw + uw) * scale else anchorX
        text.typeface = tf500
        text.textSize = L.rowValueSize * scale
        text.color = valueColour
        val baseline = baselineFor(L.rowY)
        canvas.drawText(value, x, baseline, text)
        if (unit.isEmpty()) return
        x += vw * scale + L.rowUnitGap * scale
        text.typeface = tf600
        text.textSize = L.rowUnitSize * scale
        text.color = unitColour
        canvas.drawText(unit, x, baseline, text)
    }

    /** How the loop pill / badge looks: by [FaceState.LoopHealth], neutral grey while the status line names the problem. */
    private enum class PillLook { OK, WARN, BAD, GREY, OFF, OPEN, NEUTRAL }

    private fun pillLook(s: S): PillLook = when {
        s.loop == FaceState.LoopHealth.OFF                                   -> PillLook.OFF
        s.loop == FaceState.LoopHealth.OPEN                                  -> PillLook.OPEN
        s.loop == FaceState.LoopHealth.NEVER || s.loop == FaceState.LoopHealth.NEUTRAL -> PillLook.GREY
        FaceState.loopNeutral(s.owner)                                       -> PillLook.NEUTRAL
        s.loop == FaceState.LoopHealth.BAD                                   -> PillLook.BAD
        s.loop == FaceState.LoopHealth.WARN                                  -> PillLook.WARN
        else                                                                 -> PillLook.OK
    }

    /**
     * Loop pill from the family loop health: the real loop run time (Status.lastLoopRunAt) or, on an older phone, the
     * remembered last temp basal enact ([loopTime], see [LoopRunTime]). Shapes, not only colours: loop off = pause bars
     * + "aus", open loop = hollow glyph + "Offen", never / no profile = hollow grey "--".
     */
    private fun drawLoopPill(canvas: Canvas, s: S, pill: HaloLayout.Pill) {
        val look = pillLook(s)
        val label = loopLabel(s)
        rect.set(cx - pill.halfW, L.pillY - L.pillHalfH, cx + pill.halfW, L.pillY + L.pillHalfH)
        val corner = L.pillHalfH
        val fill = when (look) {
            PillLook.OK                    -> 0xFF12281E.toInt()
            PillLook.WARN                  -> 0xFF1C1E24.toInt()        // neutral: WARN means "check", not an alarm
            PillLook.BAD                   -> 0xFF2E1218.toInt()
            else                           -> 0
        }
        if (fill != 0) {
            resetPaint().apply { color = fill }
            canvas.drawRoundRect(rect, corner, corner, p)
        }
        resetPaint().apply {
            style = Paint.Style.STROKE; strokeWidth = L.pillStroke
            color = when (look) {
                PillLook.OK, PillLook.OPEN -> 0xFF285A40.toInt()
                PillLook.WARN              -> 0xFF3A3E48.toInt()
                PillLook.BAD               -> 0xFF6A2632.toInt()
                PillLook.OFF               -> 0xFF5A4A20.toInt()
                PillLook.NEUTRAL           -> 0xFF4A5160.toInt()
                PillLook.GREY              -> 0xFF2C313C.toInt()
            }
        }
        canvas.drawRoundRect(rect, corner, corner, p)
        drawLoopGlyph(canvas, look, pill.glyphX, L.pillY, loopColour(look), aa = true, glow = look == PillLook.OK || look == PillLook.BAD)
        val textColour = when (look) {
            PillLook.OK      -> 0xFFD2F5E1.toInt()
            PillLook.WARN    -> AMBER_MUTED
            PillLook.BAD     -> 0xFFFFD2D8.toInt()
            PillLook.OFF     -> AMBER
            PillLook.OPEN, PillLook.NEUTRAL -> TXT2
            PillLook.GREY    -> TXT3
        }
        drawPillText(canvas, label, pill, textColour, aa = true)
    }

    /** Ink left edge of the last [measurePill] label at full size, relative to its drawing origin. */
    private var pillInkLeft = 0f

    /** Measures the loop label's ink and returns the content-sized pill for it. */
    private fun measurePill(label: String): HaloLayout.Pill {
        text.reset()
        text.typeface = tf600
        text.textSize = L.pillTextSize
        text.getTextBounds(label, 0, label.length, bounds)
        pillInkLeft = bounds.left.toFloat()
        return L.pill(bounds.width().toFloat())
    }

    /** The loop label with its ink starting at [HaloLayout.Pill.textLeft] (a fixed gap after the glyph). */
    private fun drawPillText(canvas: Canvas, label: String, pill: HaloLayout.Pill, colour: Int, aa: Boolean) {
        text.reset()
        text.isAntiAlias = aa
        text.textAlign = Paint.Align.LEFT
        text.typeface = tf600
        text.textSize = L.pillTextSize * pill.textScale
        text.color = colour
        canvas.drawText(label, pill.textLeft - pillInkLeft * pill.textScale, baselineFor(L.pillY), text)
    }

    private fun loopColour(look: PillLook): Int = when (look) {
        PillLook.OK, PillLook.OPEN -> GREEN
        PillLook.WARN              -> AMBER_MUTED
        PillLook.BAD               -> RED
        PillLook.OFF               -> AMBER
        PillLook.NEUTRAL, PillLook.GREY -> TXT3
    }

    /**
     * Loop glyph: an open circle with an arrow head (a circular arrow), so the pill reads as "loop", not as BG.
     * Loop off: two pause bars instead. Open loop, never and no profile: the circle without its head (hollow).
     */
    private fun drawLoopGlyph(canvas: Canvas, look: PillLook, x: Float, y: Float, colour: Int, aa: Boolean, glow: Boolean) {
        val r = L.pillGlyphR
        resetPaint(aa).apply {
            style = Paint.Style.STROKE; strokeWidth = L.pillGlyphStroke; strokeCap = Paint.Cap.ROUND; color = colour
            if (glow) setShadowLayer(L.pillGlyphGlow, 0f, 0f, withAlpha(colour, 0x99))
        }
        if (look == PillLook.OFF) {
            val dx = r * 0.45f
            canvas.drawLine(x - dx, y - r * 0.8f, x - dx, y + r * 0.8f, p)
            canvas.drawLine(x + dx, y - r * 0.8f, x + dx, y + r * 0.8f, p)
            return
        }
        rect.set(x - r, y - r, x + r, y + r)
        if (look == PillLook.OPEN || look == PillLook.GREY) {
            p.pathEffect = if (look == PillLook.GREY) DashPathEffect(floatArrayOf(r * 0.5f, r * 0.45f), 0f) else null
            canvas.drawCircle(x, y, r, p)
            p.pathEffect = null
            return
        }
        // Open at the top right; the head points clockwise at the gap's end (-60 deg).
        canvas.drawArc(rect, -30f, 300f, false, p)
        val hx = x + r * cos(Math.toRadians(-60.0)).toFloat()
        val hy = y + r * sin(Math.toRadians(-60.0)).toFloat()
        val h = L.pillGlyphHead
        glyphPath.reset()
        glyphPath.moveTo(hx - h, hy - h * 0.15f)
        glyphPath.lineTo(hx + h * 0.35f, hy + h * 0.15f)
        glyphPath.lineTo(hx - h * 0.25f, hy + h * 1.05f)
        glyphPath.close()
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeWidth = L.pillGlyphStroke * 0.4f
        canvas.drawPath(glyphPath, p)
    }

    /**
     * The ball in the pill's slot; the four states differ by SHAPE (family rule), not only by colour:
     * idle = indigo outline + "+0,5" / "U"; armed = solid indigo fill, white text "nochmal" ("2×" on small screens) and a
     * white countdown ring; sent = faint outline, "…" and a static partial ring until the request times out;
     * disabled = dashed faint outline with a diagonal slash (a tap shows the reason). The loop state stays visible as a
     * badge on the upper right.
     */
    private fun drawBolusButton(canvas: Canvas, state: State, armed: Boolean, s: S, now: Long) {
        val x = L.ballX
        val y = L.ballY
        val r = L.ballR
        val amount = amountString(drawnBolusAmount())
        // "Auswahl": a drawn plus over "U" instead of the amount (the amount is chosen in the picker)
        val picker = pickerMode()
        when {
            armed && state == State.IDLE -> {
                resetPaint().apply { color = BALL_ARMED; setShadowLayer(L.ballGlowArmed, 0f, 0f, withAlpha(BALL_ARMED, 0xAA)) }
                canvas.drawCircle(x, y, r, p)
                drawBallAmount(canvas, picker, amount, x, y + L.ballAmountDy, Color.WHITE)
                drawFitText(canvas, if (L.ballSmall) getString(R.string.face_bolus_again_short) else againLabel, x, y + L.ballSubDy, L.ballSubSize, tf600, Color.WHITE, maxWidth = L.ballSubMaxW)
                // countdown: white ring from 12 o'clock, shrinking over the double-tap window
                val left = (1f - (now - guard.armedAt).toFloat() / BolusTapGesture.WINDOW_MS).coerceIn(0f, 1f)
                resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ballCountdownW; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
                canvas.drawArc(arcRectAt(x, y, L.ballCountdownR), -90f, 360f * left, false, p)
            }

            state == State.SENT          -> {
                resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ballStroke; color = withAlpha(BALL_IDLE, 0x8C) }
                canvas.drawCircle(x, y, r, p)
                // static partial ring (300 deg, gap at the top): "waiting", no spinner
                resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ballCountdownW; strokeCap = Paint.Cap.ROUND; color = withAlpha(BALL_IDLE, 0x80) }
                canvas.drawArc(arcRectAt(x, y, L.ballCountdownR), -60f, 300f, false, p)
                drawText(canvas, "…", x, y, L.ballAmountSize * 1.2f, tf700, 0xFFC8CEF5.toInt())
            }

            state == State.DISABLED      -> {
                resetPaint().apply {
                    style = Paint.Style.STROKE; strokeWidth = L.ballStroke; color = withAlpha(BALL_IDLE, 0x66)
                    val dash = (2.0 * Math.PI * r / 20.0).toFloat()
                    pathEffect = DashPathEffect(floatArrayOf(dash * 0.55f, dash * 0.45f), 0f)
                }
                canvas.drawCircle(x, y, r, p)
                // diagonal slash from 7:30 to 1:30, then the text with a black knock-out over it
                val k = r * 0.7071f
                resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ballStroke; strokeCap = Paint.Cap.ROUND; color = TXT3 }
                canvas.drawLine(x - k, y + k, x + k, y - k, p)
                knockOut = L.ballStroke * 2f
                drawBallAmount(canvas, picker, amount, x, y + L.ballAmountDy, TXT3)
                drawFitText(canvas, unitLabel, x, y + L.ballSubDy, L.ballSubSize, tf600, TXT3, maxWidth = L.ballSubMaxW)
                knockOut = 0f
            }

            else                         -> {
                resetPaint().apply { color = 0xFF141628.toInt(); setShadowLayer(L.ballGlow, 0f, 0f, 0x66788CFF) }
                canvas.drawCircle(x, y, r, p)
                resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ballStroke; color = BALL_IDLE }
                canvas.drawCircle(x, y, r, p)
                drawBallAmount(canvas, picker, amount, x, y + L.ballAmountDy, 0xFFE4E7FF.toInt())
                drawFitText(canvas, unitLabel, x, y + L.ballSubDy, L.ballSubSize, tf600, 0xFF96A0DC.toInt(), maxWidth = L.ballSubMaxW)
            }
        }
        // Loop badge: black rim, then the loop colour (hollow when the loop is off / never / open).
        resetPaint().apply { color = Color.BLACK }
        canvas.drawCircle(L.badgeX, L.badgeY, L.badgeR + L.badgeRim, p)
        val look = pillLook(s)
        val c = loopColour(look)
        if (look == PillLook.OK || look == PillLook.WARN || look == PillLook.BAD) {
            resetPaint().apply { color = c; if (look != PillLook.WARN) setShadowLayer(L.pillGlyphGlow, 0f, 0f, withAlpha(c, 0x99)) }
            canvas.drawCircle(L.badgeX, L.badgeY, L.badgeR, p)
        } else {
            resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ballStroke; color = c }
            canvas.drawCircle(L.badgeX, L.badgeY, L.badgeR - L.ballStroke / 2f, p)
        }
    }

    /** The ball's amount line: "+0,5", or in "Auswahl" mode a drawn plus (a text "+" is thin and small), as Orbit. */
    private fun drawBallAmount(canvas: Canvas, picker: Boolean, amount: String, x: Float, cy: Float, colour: Int) {
        if (!picker) {
            drawText(canvas, amount, x, cy, L.ballAmountSize, tf700, colour)
            return
        }
        val k = L.ballPlusHalf
        if (knockOut > 0f) {
            resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ballPlusW + knockOut; strokeCap = Paint.Cap.ROUND; color = Color.BLACK }
            canvas.drawLine(x - k, cy, x + k, cy, p)
            canvas.drawLine(x, cy - k, x, cy + k, p)
        }
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ballPlusW; strokeCap = Paint.Cap.ROUND; color = colour }
        canvas.drawLine(x - k, cy, x + k, cy, p)
        canvas.drawLine(x, cy - k, x, cy + k, p)
    }

    private fun arcRectAt(x: Float, y: Float, r: Float): RectF = rect.apply { set(x - r, y - r, x + r, y + r) }

    // ---------------------------------------------------------------- cached values

    private fun backdrop(colour: Int, emphasis: Boolean): Shader {
        val key = colour xor if (emphasis) 1 else 0
        val cached = backdropShader
        if (cached != null && backdropColour == key) return cached
        backdropColour = key
        return RadialGradient(cx, cy, L.backdropR, withAlpha(colour, if (emphasis) 0x2A else 0x1A), Color.TRANSPARENT, Shader.TileMode.CLAMP).also { backdropShader = it }
    }

    private fun sweep(colour: Int, sweep: Float): Shader {
        val key = (colour * 31) xor sweep.toInt()
        val cached = sweepShader
        if (cached != null && sweepKey == key) return cached
        sweepKey = key
        val stop = ((sweep.toInt() + 6f) / 360f).coerceIn(0.01f, 1f)
        return SweepGradient(cx, cy, intArrayOf(FILL_START, colour, colour), floatArrayOf(0f, stop, 1f)).also {
            sweepMatrix.setRotate(HaloFaceLogic.RING_START - 6f, cx, cy)
            it.setLocalMatrix(sweepMatrix)
            sweepShader = it
        }
    }

    /** Time from the renderer (latestTime is set from the render ZonedDateTime), not from the system clock. */
    private fun timeString(): String {
        val key = latestTime.hour * 60 + latestTime.minute + if (is24HourFormat) 0 else 10_000
        if (key != timeKey) {
            timeKey = key
            timeStr = HaloFaceLogic.timeText(latestTime.hour, latestTime.minute, is24HourFormat)
        }
        return timeStr
    }

    private fun arrowString(): String {
        if (bg.timeStamp <= 0L) return ""
        val src = bg.slopeArrow
        if (src !== arrowSrc) {
            arrowSrc = src
            // U+FE0E: text presentation, so the arrow is drawn in the paint colour and not as an emoji.
            arrowStr = if (FaceState.trend(src, bg.deltaMgdl) == FaceState.Trend.NONE) "" else "${src.trim()}︎"
        }
        return arrowStr
    }

    private fun ageString(ageMin: Long?): String {
        val key = ageMin ?: -1L
        if (key != ageKey) {
            ageKey = key
            ageStr = FaceState.ageText(ageMin, words())
        }
        return ageStr
    }

    private var loopKey = Long.MIN_VALUE
    private var loopStr = HaloFaceLogic.NO_DATA

    /** Pill label: "2′", "aus" (loop off), "Offen" (open loop), "--" (never / no profile). */
    private fun loopLabel(s: S): String = when (s.loop) {
        FaceState.LoopHealth.OFF     -> loopOffLabel
        FaceState.LoopHealth.OPEN    -> loopOpenLabel
        FaceState.LoopHealth.NEVER,
        FaceState.LoopHealth.NEUTRAL -> HaloFaceLogic.NO_DATA
        else                         -> {
            val key = s.loopAge ?: -1L
            if (key != loopKey) {
                loopKey = key
                loopStr = FaceState.ageText(s.loopAge, words())
            }
            loopStr
        }
    }

    /** Amount shown on the ball, re-read only after a preference change. A tap always reads the preference itself. */
    private fun drawnBolusAmount(): Double {
        if (drawnAmount.isNaN()) drawnAmount = bolusAmount()
        return drawnAmount
    }

    /** "+0,5" in the watch locale (a number the watch generates). */
    private fun amountString(amount: Double): String {
        if (amount != amountKey) {
            amountKey = amount
            amountStr = FaceState.bolusAmountText(amount, Locale.getDefault())
        }
        return amountStr
    }

    // ---------------------------------------------------------------- text helpers

    private fun baselineFor(centreY: Float): Float {
        text.getFontMetrics(fm)
        return centreY - (fm.ascent + fm.descent) / 2f
    }

    private fun drawText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, tf: Typeface, colour: Int, aa: Boolean = true) {
        text.reset()
        text.isAntiAlias = aa
        text.textAlign = Paint.Align.CENTER
        text.typeface = tf
        text.textSize = size
        drawKnockOut(canvas, s, cx, baselineFor(cy))
        text.color = colour
        canvas.drawText(s, cx, baselineFor(cy), text)
    }

    /** Like [drawText], shrunk to [maxWidth] if needed (e.g. "12(5)g" COB, long translations). */
    private fun drawFitText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, tf: Typeface, colour: Int, aa: Boolean = true, maxWidth: Float) {
        text.reset()
        text.isAntiAlias = aa
        text.textAlign = Paint.Align.CENTER
        text.typeface = tf
        text.textSize = size
        text.textSize = size * HaloFaceLogic.fitScale(text.measureText(s), maxWidth)
        drawKnockOut(canvas, s, cx, baselineFor(cy))
        text.color = colour
        canvas.drawText(s, cx, baselineFor(cy), text)
    }

    /** Width of a black outline drawn under the next texts (0 = none), so a text stays legible over a line. */
    private var knockOut = 0f

    private fun drawKnockOut(canvas: Canvas, s: String, x: Float, baseline: Float) {
        if (knockOut <= 0f) return
        val style = text.style
        text.style = Paint.Style.STROKE
        text.strokeWidth = knockOut
        text.color = Color.BLACK
        canvas.drawText(s, x, baseline, text)
        text.style = style
    }

    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    // ---------------------------------------------------------------- taps

    override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
        if (tapType != TAP_UP) return
        // Tap in face coordinates (the same square the layout uses).
        val dx = x - offX
        val dy = y - offY

        // The 48 dp circle is cut off at the top, so a tap on the secondary (TT / basal) line can never arm a bolus.
        val inside = buttonShown && dy >= L.ballHitTop && BolusTapGesture.isInside(dx, dy, L.ballX, L.ballY, L.ballHitR)
        // A tap on the ball right after the face woke up is most likely a sleeve or palm: ignore it.
        if (inside && System.currentTimeMillis() - interactiveSince in 0 until WAKE_IGNORE_MS) return
        // The guard re-checks every rule and drops a duplicate delivery of the same tap event. In picker mode the
        // double tap only opens the picker: no request yet, so the 30 s pending lock does not start.
        val picker = pickerMode()
        val outcome = guard.onTap(eventTime, inside, bolusInput(), commitSend = !picker)
        when (outcome.result) {
            TapResult.DUPLICATE -> return
            TapResult.ARM       -> {
                handler.removeCallbacks(armedTick)
                // Fresh FILTER_REACHABLE query; SEND needs its "reachable" answer (arrived after this tap).
                refreshPhoneReachable()
                vibrate(vibArmed)
                handler.postDelayed(armedTick, FRAME_MS)
            }

            TapResult.SEND      -> if (picker) {
                handler.removeCallbacks(armedTick)
                openQuickBolus()
            } else {
                handler.removeCallbacks(armedTick)
                val amount = bolusAmount()
                aapsLogger.info(LTag.WEAR, "HaloWatchface: double-tap bolus request $amount U")
                // Exactly as BolusActivity: the phone checks and the watch shows the confirmation screen.
                rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(amount, 0)))
                vibrate(vibSent)
                toast(getString(R.string.halo_bolus_sent))
                // the SENT look ends with the pending time
                handler.removeCallbacks(redraw)
                handler.postDelayed(redraw, BolusRequestGuard.PENDING_MS + 10)
            }

            TapResult.REFUSED   -> {
                handler.removeCallbacks(armedTick)
                if (outcome.reason == Reason.NO_PHONE) refreshPhoneReachable()
                refused(outcome.reason)
            }

            TapResult.DISARM    -> handler.removeCallbacks(armedTick)
            TapResult.IGNORE    -> Unit
        }
        if (outcome.result != TapResult.IGNORE) invalidate()
        if (outcome.onButton) return

        // Double-tap on the hero BG opens the main menu, like the other AAPS faces.
        if (dx in L.heroTapLeft..L.heroTapRight && dy in L.heroTapTop..L.heroTapBottom) {
            if (bgTapTime != 0L && eventTime - bgTapTime < 800) {
                if (eventTime - lastMenuOpenTime > 2000) {
                    lastMenuOpenTime = eventTime
                    startActivity(Intent(this, MainMenuActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                }
                bgTapTime = 0
            } else bgTapTime = eventTime
        }
    }

    /** "Auswahl" mode: the double tap opens the quick-bolus picker (never in ambient or a preview), as Orbit. */
    private fun openQuickBolus() {
        if (isAmbient || headless) return
        aapsLogger.info(LTag.WEAR, "HaloWatchface: double tap opens the quick-bolus picker")
        try {
            // own task (taskAffinity ""), cleared: never stacked on a menu or wizard left in the app's task
            startActivity(Intent(this, QuickBolusActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "HaloWatchface: quick-bolus picker failed to open", e)
        }
    }

    private fun refused(reason: Reason) {
        aapsLogger.info(LTag.WEAR, "HaloWatchface: bolus request refused on the watch: $reason")
        vibrate(vibRefused)
        val message = when (reason) {
            Reason.OVER_MAX_BOLUS,
            Reason.NO_MAX_BOLUS    ->
                getString(R.string.halo_bolus_refused_max, String.format(Locale.getDefault(), "%.1f", bolusInput().amount), maxBolus().let { if (it.isNaN()) "?" else String.format(Locale.getDefault(), "%.1f", it) })

            Reason.PENDING         -> getString(R.string.halo_bolus_refused_pending)
            Reason.NO_PHONE        -> getString(R.string.halo_bolus_refused_phone)
            Reason.STALE_DATA      -> getString(R.string.halo_bolus_refused_stale)
            Reason.NO_WEAR_CONTROL -> getString(R.string.halo_bolus_refused_wear_control)
            else                   -> return
        }
        toast(message)
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "HaloWatchface toast failed", e)
        }
    }

    private fun vibrate(effect: VibrationEffect) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(effect)
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "HaloWatchface vibrate failed", e)
        }
    }

    companion object {

        private const val TAP_UP = 2
        private const val TAIL_WINDOW_MS = 30 * 60 * 1000L
        private const val WAKE_IGNORE_MS = 1000L
        private const val KEY_LAST_LOOP_TIME = "halo_last_loop_enact"
        private const val SEP = " · "
        private const val FRAME_MS = 33L

        private const val GREEN = 0xFF3DDC97.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        /** Loop WARN (13..19'): a desaturated amber, "check" rather than "alarm". */
        private const val AMBER_MUTED = 0xFFC9A465.toInt()
        private const val RED = 0xFFFF5A6E.toInt()
        private const val TRACK = 0xFF1E2228.toInt()
        private const val BAND = 0xFF1C3E30.toInt()
        private const val FILL_START = 0xFF286EBE.toInt()
        private const val TAIL = 0xFFAAB4C3.toInt()
        private const val TIME = 0xFFEBEBF0.toInt()
        private const val DELTA = 0xFFDCE0E6.toInt()
        /** range.stale: any BG >= 12 min (no range hue). */
        private const val STALE = 0xFF8A93A8.toInt()
        private const val TXT2 = 0xFF9AA6C4.toInt()
        private const val TXT3 = 0xFF6F7A99.toInt()
        private const val TT = 0xFFB388FF.toInt()
        private const val TBR = 0xFF3DDBD9.toInt()
        private const val BALL_IDLE = 0xFF7888FF.toInt()
        private const val BALL_ARMED = 0xFF5B63F5.toInt()
        private const val SECONDARY = 0xFFA0A6B0.toInt()
        private const val IOB_VALUE = 0xFFD7F0FF.toInt()
        private const val COB_VALUE = 0xFFFFE0B2.toInt()
    }
}
