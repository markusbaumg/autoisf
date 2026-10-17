package app.aaps.wear.watchfaces

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
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
import app.aaps.wear.interaction.menus.MainMenuActivity
import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.BolusRequestGuard.Reason
import app.aaps.wear.watchfaces.common.BolusRequestGuard.State
import app.aaps.wear.watchfaces.common.BolusRequestGuard.TapResult
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import app.aaps.wear.watchfaces.common.BolusTapGesture
import app.aaps.wear.watchfaces.common.PhoneReachability
import app.aaps.wear.watchfaces.halo.HaloFaceLogic
import app.aaps.wear.watchfaces.halo.HaloFaceLogic.AgeLevel
import app.aaps.wear.watchfaces.halo.HaloFaceLogic.AmbientHero
import app.aaps.wear.watchfaces.halo.HaloFaceLogic.LoopState
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
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "AAPS Halo": a calm, Canvas-drawn watch face. Hero BG in its range colour, one gradient ring (BG on a
 * 2.2..16.7 mmol/L scale with the target band, a knob and a 30 min trend tail), time with a German date, arrow,
 * delta and BG age, an IOB arc with IOB and COB, a 3-state loop pill and one secondary line (temp target + basal).
 *
 * Optional "+X U" ball (bottom centre in the loop pill's slot, with a loop badge so the loop state stays visible; off by default) sends a bolus REQUEST on double-tap, exactly like BolusActivity
 * (ActionBolusPreCheck). The phone checks everything and the watch shows the standard confirmation; nothing is
 * delivered without confirming there. Interactive mode only: never in ambient, never in a headless (picker)
 * instance and never in a non-live render (screenshot / highlight layer). An editor preview drawn through the live
 * instance with default render parameters cannot be told apart; the face has no complications or styles, so the
 * system has no reason to open its editor.
 *
 * All sizes come from [HaloLayout] (fractions of the face diameter, with a text-size floor and a 48 dp tap target). The draw path does not allocate: strings, parsed values,
 * gradients, font metrics and vibration effects are cached and only rebuilt when their input changes.
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
    private val disarmRunnable = Runnable { if (guard.gesture.expireIfDue()) invalidate() }

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
    private val subLabel by lazy { getString(R.string.halo_bolus_sub) }

    // Per-value caches (rebuilt only when the input changes).
    private var timeKey = -1
    private var timeStr = ""
    private var dateKey = -1
    private var dateStr = ""
    private var arrowSrc: String? = null
    private var arrowStr = ""
    private var ageKey = Long.MIN_VALUE
    private var ageStr = HaloFaceLogic.NO_DATA
    private var secondarySrc: EventData.Status? = null
    private var secondaryStr = ""
    private var amountKey = Double.NaN
    private var amountStr = ""
    private var drawnAmount = Double.NaN
    private var iobSrc: EventData.Status? = null
    private var iobFrac = 0f
    /** Last valid loop time (openApsStatus > 0), persisted; the phone sends -1 on runs without a new temp basal. */
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
            complicationDataRepository.complicationData.collect {
                data = it
                loopMemory.onReceived(it.statusData.openApsStatus)
                invalidate()
            }
        }
        disposable += rxBus
            .toObservable(EventWearPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                drawnAmount = Double.NaN
                if (bolusCheck().state != State.IDLE && guard.reset()) handler.removeCallbacks(disarmRunnable)
                invalidate()
            }
        disposable += rxBus
            .toObservable(EventData.Preferences::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { invalidate() }
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
        handler.removeCallbacks(disarmRunnable)
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
            handler.removeCallbacks(disarmRunnable)
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

    private fun bolusInput() = BolusRequestGuard.Input(
        prefOn = BolusRequestPrefs.enabled(sp),
        ambient = isAmbient,
        wearControl = preferences.get(BooleanKey.WearControl),
        amount = bolusAmount(),
        maxBolus = maxBolus(),
        lastPhoneMessage = data.lastUpdateTimestamp,
        bgTimeStamp = data.bgData.timeStamp,
        headless = headless || !isLiveRender
    )

    /** The ball exists only with the setting on, Wear control allowed, a valid amount, interactive, not in a preview. */
    private fun bolusCheck(): BolusRequestGuard.Check = guard.check(bolusInput())

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
    private val hasBg get() = bg.timeStamp > 0L

    private fun rangeColour(): Int = when {
        !hasBg               -> GREY
        bg.sgvLevel == -1L   -> RED
        bg.sgvLevel == 1L    -> AMBER
        else                 -> GREEN
    }

    private fun lowMgdl() = bg.low.takeIf { it > 0 } ?: HaloFaceLogic.DEFAULT_LOW_MGDL
    private fun highMgdl() = bg.high.takeIf { it > 0 } ?: HaloFaceLogic.DEFAULT_HIGH_MGDL

    private fun resetPaint(antiAlias: Boolean = true): Paint = p.apply {
        reset()
        isAntiAlias = antiAlias
    }

    private fun arcRect(r: Float): RectF = rect.apply { set(cx - r, cy - r, cx + r, cy + r) }

    private fun pointX(angle: Float, r: Float) = cx + r * cos(Math.toRadians(angle.toDouble())).toFloat()
    private fun pointY(angle: Float, r: Float) = cy + r * sin(Math.toRadians(angle.toDouble())).toFloat()

    private fun ageColour(level: AgeLevel, fresh: Int): Int = when (level) {
        AgeLevel.FRESH -> fresh
        AgeLevel.OLD   -> AMBER
        AgeLevel.STALE -> RED
    }

    private fun drawInteractive(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val colour = rangeColour()
        val ageMin = HaloFaceLogic.bgAgeMinutes(bg.timeStamp, now)
        val level = HaloFaceLogic.bgAgeLevel(ageMin)
        val stale = level == AgeLevel.STALE
        val bgAngle = HaloFaceLogic.angle(bg.sgv)
        val lowA = HaloFaceLogic.angle(lowMgdl())
        val highA = HaloFaceLogic.angle(highMgdl())

        // 1. soft backdrop in the range colour (cached shader)
        if (hasBg && !stale) {
            resetPaint().apply { shader = backdrop(colour) }
            canvas.drawCircle(cx, cy, L.backdropR, p)
        }

        // 2. ring: track, target band, value fill with glow, threshold cuts
        val ring = arcRect(L.ringR)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ringW; strokeCap = Paint.Cap.ROUND; color = TRACK }
        canvas.drawArc(ring, HaloFaceLogic.RING_START, HaloFaceLogic.RING_SWEEP, false, p)
        p.strokeCap = Paint.Cap.BUTT
        p.color = BAND
        canvas.drawArc(ring, lowA, highA - lowA, false, p)

        if (hasBg) {
            val sweep = bgAngle - HaloFaceLogic.RING_START
            // Glow via shadow layer: BlurMaskFilter is ignored on the hardware canvas the renderer uses.
            resetPaint().apply {
                style = Paint.Style.STROKE; strokeWidth = L.ringW; strokeCap = Paint.Cap.ROUND
                shader = if (stale) null else sweep(colour, sweep)
                color = if (stale) STALE else Color.WHITE
                if (!stale) setShadowLayer(L.ringGlow, 0f, 0f, withAlpha(colour, 0x88))
            }
            canvas.drawArc(ring, HaloFaceLogic.RING_START, sweep, false, p)
        }

        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.cutStroke; color = Color.BLACK }
        drawCut(canvas, lowA, L.cutIn, L.cutOut)
        drawCut(canvas, highA, L.cutIn, L.cutOut)

        // 3. trend tail inside the ring, then the knob
        if (hasBg) {
            drawTrendTail(canvas, now, bgAngle)
            val kx = pointX(bgAngle, L.ringR)
            val ky = pointY(bgAngle, L.ringR)
            // Dark separation ring, so the knob does not merge into the ring gradient.
            resetPaint().apply { color = Color.BLACK }
            canvas.drawCircle(kx, ky, L.knobR + L.knobSeparation, p)
            resetPaint().apply { color = Color.WHITE; if (!stale) setShadowLayer(L.knobGlow, 0f, 0f, withAlpha(colour, 0x99)) }
            canvas.drawCircle(kx, ky, L.knobR, p)
            resetPaint().apply { color = if (stale) STALE else colour }
            canvas.drawCircle(kx, ky, L.dotR, p)
        }

        // 4. time and German date
        drawText(canvas, timeString(), cx, L.timeY, L.timeSize, tf500, TIME)
        drawText(canvas, dateString(), cx, L.dateY, L.dateSize, tf500, DATE)

        // 5. hero BG in range colour
        drawHeroBg(canvas, colour, level, ambient = false)

        // 6. arrow (BG colour) + delta, and the BG age once it is getting old
        drawDeltaLine(canvas, ageMin, if (stale) STALE else colour, DELTA, ageColour(level, DELTA), ambient = false)

        // 7. IOB arc, IOB and COB
        drawIobCob(canvas, ambient = false)

        // 8. loop pill (the bolus ball takes its slot when enabled and then carries the loop state as a badge)
        buttonShown = bolusCheck().state != State.HIDDEN
        if (!buttonShown) drawLoopPill(canvas, now, ambient = false)

        // 9. secondary line: temp target (when active) and basal
        drawFitText(canvas, secondaryLine(), cx, L.secondaryY, L.secondarySize, tf500, SECONDARY, maxWidth = L.secondaryMaxW)

        // 10. bolus ball (only when enabled; otherwise not drawn and not tappable)
        if (buttonShown) drawBolusButton(canvas, guard.isArmed, HaloFaceLogic.loopState(HaloFaceLogic.loopAgeMinutes(loopTime, now)))
        else if (guard.reset()) handler.removeCallbacks(disarmRunnable)
    }

    /**
     * Ambient: outlines only, no fills, glow or tap targets. Low-bit: white only, no anti-aliasing.
     * Burn-in protection: everything shifts by a few px every minute and nothing is a solid area.
     */
    private fun drawAmbient(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val lowBit = lowBitAmbient
        val aa = !lowBit
        buttonShown = false
        val ageMin = HaloFaceLogic.bgAgeMinutes(bg.timeStamp, now)
        val level = HaloFaceLogic.bgAgeLevel(ageMin)

        canvas.save()
        try {
            if (burnInProtection) canvas.translate(L.burnIn(HaloFaceLogic.burnInOffsetX(latestTime.minute)), L.burnIn(HaloFaceLogic.burnInOffsetY(latestTime.minute)))
            drawAmbientContent(canvas, now, lowBit, aa, ageMin, level)
        } finally {
            canvas.restore()
        }
    }

    private fun drawAmbientContent(canvas: Canvas, now: Long, lowBit: Boolean, aa: Boolean, ageMin: Long?, level: AgeLevel) {
        val ring = arcRect(L.ringR)
        resetPaint(aa).apply { style = Paint.Style.STROKE; strokeWidth = L.ambStroke; color = amb(0xFF464646.toInt(), lowBit) }
        canvas.drawArc(ring, HaloFaceLogic.RING_START, HaloFaceLogic.RING_SWEEP, false, p)
        p.color = amb(0xFF6E6E6E.toInt(), lowBit)
        val lowA = HaloFaceLogic.angle(lowMgdl())
        val highA = HaloFaceLogic.angle(highMgdl())
        drawCut(canvas, lowA, L.ambCutIn, L.ambCutOut)
        drawCut(canvas, highA, L.ambCutIn, L.ambCutOut)
        if (hasBg) {
            val a = HaloFaceLogic.angle(bg.sgv)
            p.color = amb(0xFFDCDCDC.toInt(), lowBit)
            canvas.drawCircle(pointX(a, L.ringR), pointY(a, L.ringR), L.ambKnobR, p)
        }
        drawText(canvas, timeString(), cx, L.timeY, L.timeSize, tf400, amb(0xFFC8C8C8.toInt(), lowBit), aa)
        drawText(canvas, dateString(), cx, L.dateY, L.dateSize, tf400, amb(AMB_DIM, lowBit), aa)
        drawHeroBg(canvas, Color.WHITE, level, ambient = true)
        val grey = amb(AMB_GREY, lowBit)
        // BG age stays visible in ambient; amber/red when old/stale (low-bit: white, the struck-through BG marks stale).
        drawDeltaLine(canvas, ageMin, grey, grey, if (lowBit) Color.WHITE else ageColour(level, grey), ambient = true)
        drawIobCob(canvas, ambient = true)
        drawLoopPill(canvas, now, ambient = true)
        if (st.tempTargetLevel == 2) drawFitText(canvas, secondaryLine(), cx, L.secondaryY, L.secondarySize, tf400, amb(AMB_DIM, lowBit), aa, L.secondaryMaxW)
    }

    private fun drawCut(canvas: Canvas, angle: Float, rIn: Float, rOut: Float) =
        canvas.drawLine(pointX(angle, rIn), pointY(angle, rIn), pointX(angle, rOut), pointY(angle, rOut), p)

    private fun amb(colour: Int, lowBit: Boolean): Int = if (lowBit) Color.WHITE else colour

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
        if (abs(bgAngle - startA) < 1f) return
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

    private fun drawHeroBg(canvas: Canvas, colour: Int, level: AgeLevel, ambient: Boolean) {
        val lowBit = ambient && lowBitAmbient
        val stale = level == AgeLevel.STALE
        val value = if (hasBg) HaloFaceLogic.orDash(bg.sgvString) else HaloFaceLogic.NO_DATA
        text.reset()
        text.isAntiAlias = !lowBit
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = -0.02f
        text.fontFeatureSettings = "tnum"   // tabular figures: the width does not jump between values
        text.typeface = if (ambient) tf300 else tf600
        text.textSize = L.heroSize
        // 4-char values like "10.4" or "15.0" must stay inside the ring: shrink to fit.
        text.textSize = L.heroSize * HaloFaceLogic.fitScale(text.measureText(value), L.heroMaxW)
        val baseline = L.heroY + text.textSize * 0.36f
        if (ambient) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = if (burnInProtection) L.heroAmbStrokeBurnIn else L.heroAmbStroke
            text.color = when {
                lowBit                                       -> Color.WHITE
                HaloFaceLogic.ambientHero(level) == AmbientHero.STRUCK -> 0xFF8C8C8C.toInt()
                else                                         -> 0xFFE6E6E6.toInt()
            }
        } else if (stale) {
            text.color = STALE
        } else {
            text.color = colour
            text.setShadowLayer(L.heroGlow, 0f, 0f, withAlpha(colour, 0x55))
        }
        canvas.drawText(value, cx, baseline, text)
        if (stale && hasBg) {
            // Struck through (interactive and ambient): an old value must never look current.
            val half = text.measureText(value) / 2f + L.heroStrike * 2f
            resetPaint(!lowBit).apply { color = if (ambient) text.color else STALE; strokeWidth = L.heroStrike }
            canvas.drawLine(cx - half, L.heroY, cx + half, L.heroY, p)
        }
    }

    /**
     * Arrow + delta, and " · age" once the BG age is [HaloFaceLogic.BG_SHOW_AGE_MINUTES] or older. The bold arrow is
     * centred on the delta's cap height (its own ink box, not the font box), so it does not sit low.
     */
    private fun drawDeltaLine(canvas: Canvas, ageMin: Long?, arrowColour: Int, deltaColour: Int, ageTextColour: Int, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val arrow = arrowString()
        val delta = if (hasBg) HaloFaceLogic.orDash(bg.delta) else HaloFaceLogic.NO_DATA
        val showAge = HaloFaceLogic.showBgAge(ageMin)
        val age = if (showAge) ageString(ageMin) else ""
        text.reset()
        text.isAntiAlias = aa
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
        val sepW = if (showAge) text.measureText(SEP) else 0f
        val ageW = if (showAge) text.measureText(age) else 0f
        val gap = if (arrow.isEmpty()) 0f else L.arrowGap
        var x = cx - (arrowW + gap + deltaW + sepW + ageW) / 2f
        val y = L.deltaY + L.deltaSize * HaloLayout.CAP_HEIGHT / 2f
        if (arrow.isNotEmpty()) {
            text.typeface = tf700
            text.textSize = L.arrowSize
            text.color = arrowColour
            canvas.drawText(arrow, x, arrowBaseline, text)
            x += arrowW + gap
            text.typeface = tf500
            text.textSize = L.deltaSize
        }
        text.color = deltaColour
        canvas.drawText(delta, x, y, text)
        if (!showAge) return
        x += deltaW
        canvas.drawText(SEP, x, y, text)
        x += sepW
        text.color = ageTextColour
        canvas.drawText(age, x, y, text)
    }

    private fun drawIobCob(canvas: Canvas, ambient: Boolean) {
        val lowBit = ambient && lowBitAmbient
        val aa = !lowBit
        val r = arcRect(L.iobR)
        val half = L.iobHalfSweep
        val fraction = iobFraction()
        resetPaint(aa).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            strokeWidth = if (ambient) L.ambIobW else L.iobW
            color = if (ambient) amb(0xFF323232.toInt(), lowBit) else IOB_TRACK
        }
        if (!lowBit) canvas.drawArc(r, -half, 2f * half, false, p)
        if (fraction > 0f) {
            val sweep = -2f * half * fraction
            if (ambient) {
                p.strokeWidth = L.ambIobFillW
                p.color = amb(0xFF8C8C8C.toInt(), lowBit)
            } else {
                p.color = CYAN
                p.setShadowLayer(L.iobGlow, 0f, 0f, withAlpha(CYAN, 0x99))
            }
            canvas.drawArc(r, half, sweep, false, p)
        }
        // Bottom row: COB left, IOB right (next to the arc end it fills from), at the same gap to the pill.
        // Unit and colour say which is which; the unit is the value's hue at ~65 %.
        val cobColour = if (ambient) amb(0xFFAAAAAA.toInt(), lowBit) else COB_VALUE
        val iobColour = if (ambient) amb(0xFFBEBEBE.toInt(), lowBit) else IOB_VALUE
        val (cobValue, cobUnit) = HaloFaceLogic.valueUnit(st.cob, "g")
        drawValueUnit(canvas, cobValue, cobUnit, L.cobRight, alignRight = true, cobColour, if (ambient) amb(AMB_DIM, lowBit) else withAlpha(COB_VALUE, L.rowUnitAlpha), aa)
        val (iobValue, iobUnit) = HaloFaceLogic.valueUnit(st.iobSum, "U")
        drawValueUnit(canvas, iobValue, iobUnit, L.iobLeft, alignRight = false, iobColour, if (ambient) amb(AMB_DIM, lowBit) else withAlpha(IOB_VALUE, L.rowUnitAlpha), aa)
    }

    /**
     * "1.44" + smaller "U" on the same baseline in the bottom row, shrunk to [HaloLayout.rowMaxW]; anchored at [anchorX]
     * with its right edge ([alignRight], COB) or its left edge (IOB), so the gaps to the pill stay equal.
     */
    private fun drawValueUnit(canvas: Canvas, value: String, unit: String, anchorX: Float, alignRight: Boolean, valueColour: Int, unitColour: Int, aa: Boolean) {
        text.reset()
        text.isAntiAlias = aa
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

    /**
     * Pill colour from the loop age. The watch only receives EventData.Status.openApsStatus, which the phone fills
     * with loop.lastRun.lastTBREnact (not the loop run time); see [HaloFaceLogic.loopAgeMinutes]. -1 (a run without
     * a new temp basal, or a phone restart) keeps the last valid time ([loopTime]); never received is UNKNOWN (grey).
     */
    private fun drawLoopPill(canvas: Canvas, now: Long, ambient: Boolean) {
        val lowBit = ambient && lowBitAmbient
        val aa = !lowBit
        val age = HaloFaceLogic.loopAgeMinutes(loopTime, now)
        val state = HaloFaceLogic.loopState(age)
        val label = loopLabel(age)
        rect.set(cx - L.pillHalfW, L.pillY - L.pillHalfH, cx + L.pillHalfW, L.pillY + L.pillHalfH)
        val corner = L.pillHalfH
        if (ambient) {
            resetPaint(aa).apply { style = Paint.Style.STROKE; strokeWidth = L.ambStroke; color = amb(0xFF6E6E6E.toInt(), lowBit) }
            canvas.drawRoundRect(rect, corner, corner, p)
            val glyph = when {
                lowBit                  -> Color.WHITE
                state == LoopState.OK   -> 0xFFC8C8C8.toInt()
                state == LoopState.WARN -> AMBER_MUTED
                state == LoopState.STALE -> RED
                else                    -> 0xFF8C8C8C.toInt()
            }
            drawLoopGlyph(canvas, L.pillGlyphX, L.pillY, glyph, aa, glow = false)
            drawText(canvas, label, L.pillTextX, L.pillY, L.pillTextSize, tf600, amb(0xFFC8C8C8.toInt(), lowBit), aa)
            return
        }
        resetPaint().apply {
            color = when (state) {
                LoopState.OK    -> 0xFF12281E.toInt()
                LoopState.WARN  -> 0xFF1C1E24.toInt()        // neutral: WARN means "check", not an alarm
                LoopState.STALE -> 0xFF2E1218.toInt()
                LoopState.UNKNOWN -> 0xFF1C1E24.toInt()
            }
        }
        canvas.drawRoundRect(rect, corner, corner, p)
        resetPaint().apply {
            style = Paint.Style.STROKE; strokeWidth = L.pillStroke
            color = when (state) {
                LoopState.OK    -> 0xFF285A40.toInt()
                LoopState.WARN  -> 0xFF3A3E48.toInt()
                LoopState.STALE -> 0xFF6A2632.toInt()
                LoopState.UNKNOWN -> 0xFF3A3E48.toInt()
            }
        }
        canvas.drawRoundRect(rect, corner, corner, p)
        // WARN: muted amber glyph and text only (no glow, no tinted fill or border), so it reads as "check".
        val glyphColour = if (state == LoopState.WARN) AMBER_MUTED else loopColour(state)
        drawLoopGlyph(canvas, L.pillGlyphX, L.pillY, glyphColour, aa = true, glow = state == LoopState.OK || state == LoopState.STALE)
        val textColour = when (state) {
            LoopState.OK    -> 0xFFD2F5E1.toInt()
            LoopState.WARN  -> AMBER_MUTED
            LoopState.STALE -> 0xFFFFD2D8.toInt()
            LoopState.UNKNOWN -> GREY
        }
        drawText(canvas, label, L.pillTextX, L.pillY, L.pillTextSize, tf600, textColour)
    }

    private fun loopColour(state: LoopState): Int = when (state) {
        LoopState.OK      -> GREEN
        LoopState.WARN    -> AMBER_MUTED
        LoopState.STALE   -> RED
        LoopState.UNKNOWN -> GREY
    }

    /** Loop glyph: an open circle with an arrow head (a circular arrow), so the pill reads as "loop", not as BG. */
    private fun drawLoopGlyph(canvas: Canvas, x: Float, y: Float, colour: Int, aa: Boolean, glow: Boolean) {
        val r = L.pillGlyphR
        resetPaint(aa).apply {
            style = Paint.Style.STROKE; strokeWidth = L.pillGlyphStroke; strokeCap = Paint.Cap.ROUND; color = colour
            if (glow) setShadowLayer(L.pillGlyphGlow, 0f, 0f, withAlpha(colour, 0x99))
        }
        rect.set(x - r, y - r, x + r, y + r)
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

    /** The ball in the pill's slot; the loop state stays visible as a badge (its colour) on the ball's upper right. */
    private fun drawBolusButton(canvas: Canvas, armed: Boolean, loop: LoopState) {
        resetPaint().apply {
            color = if (armed) 0xFF3C2A0A.toInt() else 0xFF141628.toInt()
            setShadowLayer(if (armed) L.ballGlowArmed else L.ballGlow, 0f, 0f, if (armed) 0xAAFFC85A.toInt() else 0x66788CFF)
        }
        canvas.drawCircle(L.ballX, L.ballY, L.ballR, p)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = L.ballStroke; color = if (armed) 0xFFFFC85A.toInt() else INDIGO }
        canvas.drawCircle(L.ballX, L.ballY, L.ballR, p)
        drawText(canvas, amountString(drawnBolusAmount()), L.ballX, L.ballY + L.ballAmountDy, L.ballAmountSize, tf700, if (armed) 0xFFFFE6AA.toInt() else 0xFFF0F0FF.toInt())
        if (armed) drawFitText(canvas, againLabel, L.ballX, L.ballY + L.ballSubDy, L.ballSubSize, tf600, 0xFFFFC85A.toInt(), maxWidth = L.ballSubMaxW)
        else drawFitText(canvas, subLabel, L.ballX, L.ballY + L.ballSubDy, L.ballSubSize, tf600, 0xFF96A0DC.toInt(), maxWidth = L.ballSubMaxW)
        // Loop badge: black rim, then the loop colour.
        resetPaint().apply { color = Color.BLACK }
        canvas.drawCircle(L.badgeX, L.badgeY, L.badgeR + L.badgeRim, p)
        val c = loopColour(loop)
        resetPaint().apply { color = c; if (loop == LoopState.OK || loop == LoopState.STALE) setShadowLayer(L.pillGlyphGlow, 0f, 0f, withAlpha(c, 0x99)) }
        canvas.drawCircle(L.badgeX, L.badgeY, L.badgeR, p)
    }

    // ---------------------------------------------------------------- cached values

    private fun backdrop(colour: Int): Shader {
        val cached = backdropShader
        if (cached != null && backdropColour == colour) return cached
        backdropColour = colour
        return RadialGradient(cx, cy, L.backdropR, withAlpha(colour, 0x1A), Color.TRANSPARENT, Shader.TileMode.CLAMP).also { backdropShader = it }
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

    /** German date ("Fr 17. Okt") from the renderer's date. */
    private fun dateString(): String {
        val key = latestTime.year * 400 + latestTime.month * 32 + latestTime.monthDay
        if (key != dateKey) {
            dateKey = key
            dateStr = try {
                val date = LocalDate.of(latestTime.year, latestTime.month + 1, latestTime.monthDay)
                HaloFaceLogic.germanDate(date.dayOfWeek.value, date.dayOfMonth, date.monthValue)
            } catch (_: Exception) {
                ""
            }
        }
        return dateStr
    }

    private fun arrowString(): String {
        if (!hasBg) return ""
        val src = bg.slopeArrow
        if (src !== arrowSrc) {
            arrowSrc = src
            // U+FE0E: text presentation, so the arrow is drawn in the paint colour and not as an emoji.
            arrowStr = if (HaloFaceLogic.orDash(src) == HaloFaceLogic.NO_DATA) "" else "$src︎"
        }
        return arrowStr
    }

    private fun ageString(ageMin: Long?): String {
        val key = ageMin ?: -1L
        if (key != ageKey) {
            ageKey = key
            ageStr = HaloFaceLogic.ageText(ageMin)
        }
        return ageStr
    }

    private var loopKey = Long.MIN_VALUE
    private var loopStr = HaloFaceLogic.NO_DATA
    private fun loopLabel(age: Long?): String {
        val key = age ?: -1L
        if (key != loopKey) {
            loopKey = key
            loopStr = HaloFaceLogic.ageText(age)
        }
        return loopStr
    }

    private fun secondaryLine(): String {
        val s = st
        if (s !== secondarySrc) {
            secondarySrc = s
            secondaryStr = HaloFaceLogic.secondaryLine(s.tempTarget, s.tempTargetLevel, s.currentBasal)
        }
        return secondaryStr
    }

    private fun iobFraction(): Float {
        val s = st
        if (s !== iobSrc) {
            iobSrc = s
            iobFrac = HaloFaceLogic.iobFraction(HaloFaceLogic.parseIob(s.iobSum))
        }
        return iobFrac
    }

    /** Amount shown on the ball, re-read only after a preference change. A tap always reads the preference itself. */
    private fun drawnBolusAmount(): Double {
        if (drawnAmount.isNaN()) drawnAmount = bolusAmount()
        return drawnAmount
    }

    private fun amountString(amount: Double): String {
        if (amount != amountKey) {
            amountKey = amount
            amountStr = String.format(Locale.US, "+%.1f", amount)
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
        text.color = colour
        canvas.drawText(s, cx, baselineFor(cy), text)
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
        val outcome = guard.onTap(eventTime, inside, bolusInput())
        when (outcome.result) {
            TapResult.DUPLICATE -> return
            TapResult.ARM       -> {
                handler.removeCallbacks(disarmRunnable)
                // Fresh FILTER_REACHABLE query; SEND needs its "reachable" answer (arrived after this tap).
                refreshPhoneReachable()
                vibrate(vibArmed)
                handler.postDelayed(disarmRunnable, BolusTapGesture.WINDOW_MS + 50)
            }

            TapResult.SEND      -> {
                handler.removeCallbacks(disarmRunnable)
                val amount = bolusAmount()
                aapsLogger.info(LTag.WEAR, "HaloWatchface: double-tap bolus request $amount U")
                // Exactly as BolusActivity: the phone checks and the watch shows the confirmation screen.
                rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(amount, 0)))
                vibrate(vibSent)
                toast(getString(R.string.halo_bolus_sent))
            }

            TapResult.REFUSED   -> {
                handler.removeCallbacks(disarmRunnable)
                if (outcome.reason == Reason.NO_PHONE) refreshPhoneReachable()
                refused(outcome.reason)
            }

            TapResult.DISARM    -> handler.removeCallbacks(disarmRunnable)
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

    private fun refused(reason: Reason) {
        aapsLogger.info(LTag.WEAR, "HaloWatchface: bolus request refused on the watch: $reason")
        vibrate(vibRefused)
        val message = when (reason) {
            Reason.OVER_MAX_BOLUS,
            Reason.NO_MAX_BOLUS    ->
                getString(R.string.halo_bolus_refused_max, String.format(Locale.US, "%.1f", bolusAmount()), maxBolus().let { if (it.isNaN()) "?" else String.format(Locale.US, "%.1f", it) })

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

        private const val GREEN = 0xFF3DDC97.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        /** Loop WARN (13..19'): a desaturated amber, "check" rather than "alarm". */
        private const val AMBER_MUTED = 0xFFC9A465.toInt()
        private const val RED = 0xFFFF5A6E.toInt()
        private const val GREY = 0xFF8A909C.toInt()
        private const val CYAN = 0xFF5AC8FA.toInt()
        private const val INDIGO = 0xFF788CFF.toInt()
        private const val TRACK = 0xFF1E2228.toInt()
        private const val BAND = 0xFF1C3E30.toInt()
        private const val FILL_START = 0xFF286EBE.toInt()
        private const val TAIL = 0xFFAAB4C3.toInt()
        private const val TIME = 0xFFEBEBF0.toInt()
        private const val DATE = 0xFFA0A6B0.toInt()
        private const val DELTA = 0xFFDCE0E6.toInt()
        private const val STALE = 0xFF6E7480.toInt()
        private const val SECONDARY = 0xFFA0A6B0.toInt()
        private const val IOB_TRACK = 0xFF1C1F25.toInt()  // dimmer than the ring track: only the blue fill should read
        private const val IOB_VALUE = 0xFFD7F0FF.toInt()
        private const val COB_VALUE = 0xFFFFE0B2.toInt()
        private const val AMB_GREY = 0xFFAAAAAA.toInt()
        /** Dimmest ambient text (never below #8C8C8C). */
        private const val AMB_DIM = 0xFF8C8C8C.toInt()
    }
}
