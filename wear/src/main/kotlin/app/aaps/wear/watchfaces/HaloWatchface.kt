package app.aaps.wear.watchfaces

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
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
import app.aaps.wear.watchfaces.halo.BolusRequestGuard
import app.aaps.wear.watchfaces.halo.BolusTapGesture
import app.aaps.wear.watchfaces.halo.HaloFaceLogic
import app.aaps.wear.watchfaces.halo.HaloFaceLogic.AgeLevel
import app.aaps.wear.watchfaces.halo.HaloFaceLogic.AmbientHero
import app.aaps.wear.watchfaces.halo.HaloFaceLogic.LoopState
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
import kotlin.math.min
import kotlin.math.sin

/**
 * "AAPS Halo": a calm, Canvas-drawn watch face. Hero BG in its range colour, one gradient ring (BG on a
 * 2.2..16.7 mmol/L scale with the target band, a knob and a 30 min trend tail), time with a German date, arrow,
 * delta and BG age, an IOB arc with IOB and COB, a 3-state loop pill and one secondary line (temp target + basal).
 *
 * Optional "+X U" ball (left, off by default) sends a bolus REQUEST on double-tap, exactly like BolusActivity
 * (ActionBolusPreCheck). The phone checks everything and the watch shows the standard confirmation; nothing is
 * delivered without confirming there. Interactive mode only: never in ambient, never in a headless (picker)
 * instance and never in a non-live render (screenshot / highlight layer). An editor preview drawn through the live
 * instance with default render parameters cannot be told apart; the face has no complications or styles, so the
 * system has no reason to open its editor.
 *
 * Drawn on a 450x450 design grid scaled to the screen. The draw path does not allocate: strings, parsed values,
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

    private val gesture = BolusTapGesture(clock = { System.currentTimeMillis() })
    private val guard = BolusRequestGuard(
        clock = { System.currentTimeMillis() },
        // Persisted, so a recreated service still knows about a request whose confirmation may be open.
        store = object : BolusRequestGuard.LastSentStore {
            override var lastSentAt: Long
                get() = sp.getLong(KEY_LAST_SENT_AT, 0L)
                set(value) = sp.putLong(KEY_LAST_SENT_AT, value)
        }
    )
    private val disarmRunnable = Runnable { if (gesture.expireIfDue()) invalidate() }

    // Phone reachability (the data layer drops messages silently without a reachable node).
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }
    @Volatile private var phoneReachable = false
    /** Time the last FILTER_REACHABLE query answered; a SEND needs an answer newer than its first tap. */
    @Volatile private var phoneCheckedAt = 0L
    private var armedAt = 0L
    // info.nodes is not filtered by reachability: re-query with FILTER_REACHABLE instead of trusting it.
    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { refreshPhoneReachable() }
    private var capabilityListening = false

    /** Side effects of the active instance (last-selected pref, resend, phone tracking) run once per service. */
    private var activeSetupDone = false

    // Design-grid scale (450 px) and the last drawn state, for tap handling.
    private var scale = 1f
    private var buttonShown = false
    private var bgTapTime = 0L
    private var lastMenuOpenTime = 0L

    // Paints, created once.
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
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
    private val iobLabel by lazy { getString(R.string.activity_IOB) }
    private val cobLabel by lazy { getString(R.string.halo_cob) }
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
        watchfaceScope.launch {
            complicationDataRepository.complicationData.collect {
                data = it
                invalidate()
            }
        }
        disposable += rxBus
            .toObservable(EventWearPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                drawnAmount = Double.NaN
                if (!bolusButtonEnabled() && gesture.reset()) handler.removeCallbacks(disarmRunnable)
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
            // Settings of the iteration-1 "Kusi" face; the Halo face uses halo_* keys (ball starts off again).
            sp.remove(OLD_KEY_DOUBLE_TAP)
            sp.remove(OLD_KEY_AMOUNT)
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
            gesture.reset()
            handler.removeCallbacks(disarmRunnable)
        }
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
        try {
            capabilityClient.getCapability(DataLayerListenerServiceWear.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener {
                    phoneReachable = it.nodes.isNotEmpty()
                    phoneCheckedAt = System.currentTimeMillis()
                }
                .addOnFailureListener {
                    phoneReachable = false
                    phoneCheckedAt = System.currentTimeMillis()
                }
        } catch (e: Exception) {
            phoneReachable = false
            aapsLogger.error(LTag.WEAR, "HaloWatchface getCapability failed", e)
        }
    }

    // ---------------------------------------------------------------- preferences

    private fun bolusButtonPref(): Boolean = sp.getBoolean(R.string.key_halo_bolus_double_tap, false)
    private fun wearControl(): Boolean = preferences.get(BooleanKey.WearControl)
    private fun bolusAmount(): Double = BolusTapGesture.parseAmount(sp.getString(R.string.key_halo_bolus_amount, "0.5"))
    private fun maxBolus(): Double = sp.getDouble(R.string.key_treatments_safety_max_bolus, 3.0)

    /** The ball exists only with the setting on, Wear control allowed, interactive, and not in a preview. */
    private fun bolusButtonEnabled(): Boolean = !headless && isLiveRender && !isAmbient && bolusButtonPref() && wearControl()

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        scale = min(w, h) / 450f
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate((w - 450f * scale) / 2f, (h - 450f * scale) / 2f)
        canvas.scale(scale, scale)
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

    private fun arcRect(r: Float): RectF = rect.apply { set(CX - r, CY - r, CX + r, CY + r) }

    private fun pointX(angle: Float, r: Float) = CX + r * cos(Math.toRadians(angle.toDouble())).toFloat()
    private fun pointY(angle: Float, r: Float) = CY + r * sin(Math.toRadians(angle.toDouble())).toFloat()

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
            canvas.drawCircle(CX, CY, 147f, p)
        }

        // 2. ring: track, target band, value fill with glow, threshold cuts
        val ring = arcRect(RING_R)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = 13f; strokeCap = Paint.Cap.ROUND; color = TRACK }
        canvas.drawArc(ring, HaloFaceLogic.RING_START, HaloFaceLogic.RING_SWEEP, false, p)
        p.strokeCap = Paint.Cap.BUTT
        p.color = BAND
        canvas.drawArc(ring, lowA, highA - lowA, false, p)

        if (hasBg) {
            val sweep = bgAngle - HaloFaceLogic.RING_START
            // Glow via shadow layer: BlurMaskFilter is ignored on the hardware canvas the renderer uses.
            resetPaint().apply {
                style = Paint.Style.STROKE; strokeWidth = 13f; strokeCap = Paint.Cap.ROUND
                shader = if (stale) null else sweep(colour, sweep)
                color = if (stale) STALE else Color.WHITE
                if (!stale) setShadowLayer(8f, 0f, 0f, withAlpha(colour, 0x88))
            }
            canvas.drawArc(ring, HaloFaceLogic.RING_START, sweep, false, p)
        }

        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.BLACK }
        canvas.drawLine(pointX(lowA, 125f), pointY(lowA, 125f), pointX(lowA, 142f), pointY(lowA, 142f), p)
        canvas.drawLine(pointX(highA, 125f), pointY(highA, 125f), pointX(highA, 142f), pointY(highA, 142f), p)

        // 3. trend tail inside the ring, then the knob
        if (hasBg) {
            drawTrendTail(canvas, now, bgAngle)
            val kx = pointX(bgAngle, RING_R)
            val ky = pointY(bgAngle, RING_R)
            resetPaint().apply { color = Color.WHITE; if (!stale) setShadowLayer(6f, 0f, 0f, withAlpha(colour, 0x99)) }
            canvas.drawCircle(kx, ky, 12f, p)
            resetPaint().apply { color = if (stale) STALE else colour }
            canvas.drawCircle(kx, ky, 6f, p)
        }

        // 4. time and German date
        drawText(canvas, timeString(), CX, TIME_Y, 30f, tf500, TIME)
        drawText(canvas, dateString(), CX, DATE_Y, 15f, tf500, DATE)

        // 5. hero BG in range colour
        drawHeroBg(canvas, colour, level, ambient = false)

        // 6. arrow + delta · age
        drawDeltaLine(canvas, ageMin, if (stale) STALE else colour, DELTA, ageColour(level, DELTA), ambient = false)

        // 7. IOB arc, IOB and COB
        drawIobCob(canvas, ambient = false)

        // 8. loop pill
        drawLoopPill(canvas, now, ambient = false)

        // 9. secondary line: temp target (when active) and basal
        drawText(canvas, secondaryLine(), CX, 407f, 14f, tf500, SECONDARY)

        // 10. bolus ball (only when enabled; otherwise not drawn and not tappable)
        buttonShown = bolusButtonEnabled()
        if (buttonShown) drawBolusButton(canvas, gesture.isArmed)
        else if (gesture.reset()) handler.removeCallbacks(disarmRunnable)
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
            if (burnInProtection) canvas.translate(HaloFaceLogic.burnInOffsetX(latestTime.minute), HaloFaceLogic.burnInOffsetY(latestTime.minute))
            drawAmbientContent(canvas, now, lowBit, aa, ageMin, level)
        } finally {
            canvas.restore()
        }
    }

    private fun drawAmbientContent(canvas: Canvas, now: Long, lowBit: Boolean, aa: Boolean, ageMin: Long?, level: AgeLevel) {
        val ring = arcRect(RING_R)
        resetPaint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = amb(0xFF464646.toInt(), lowBit) }
        canvas.drawArc(ring, HaloFaceLogic.RING_START, HaloFaceLogic.RING_SWEEP, false, p)
        p.color = amb(0xFF6E6E6E.toInt(), lowBit)
        val lowA = HaloFaceLogic.angle(lowMgdl())
        val highA = HaloFaceLogic.angle(highMgdl())
        canvas.drawLine(pointX(lowA, 127f), pointY(lowA, 127f), pointX(lowA, 141f), pointY(lowA, 141f), p)
        canvas.drawLine(pointX(highA, 127f), pointY(highA, 127f), pointX(highA, 141f), pointY(highA, 141f), p)
        if (hasBg) {
            val a = HaloFaceLogic.angle(bg.sgv)
            p.color = amb(0xFFDCDCDC.toInt(), lowBit)
            canvas.drawCircle(pointX(a, RING_R), pointY(a, RING_R), 7f, p)
        }
        drawText(canvas, timeString(), CX, TIME_Y, 30f, tf400, amb(0xFFC8C8C8.toInt(), lowBit), aa)
        drawText(canvas, dateString(), CX, DATE_Y, 15f, tf400, amb(0xFF8C8C8C.toInt(), lowBit), aa)
        drawHeroBg(canvas, Color.WHITE, level, ambient = true)
        val grey = amb(AMB_GREY, lowBit)
        // BG age stays visible in ambient; amber/red when old/stale (low-bit: white, the struck-through BG marks stale).
        drawDeltaLine(canvas, ageMin, grey, grey, if (lowBit) Color.WHITE else ageColour(level, grey), ambient = true)
        drawIobCob(canvas, ambient = true)
        drawLoopPill(canvas, now, ambient = true)
        if (st.tempTargetLevel == 2) drawText(canvas, secondaryLine(), CX, 407f, 14f, tf400, amb(0xFF6E6E6E.toInt(), lowBit), aa)
    }

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
        val r = arcRect(TAIL_R)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.BUTT }
        val segments = 24
        val step = (bgAngle - startA) / segments
        for (i in 0 until segments) {
            p.color = withAlpha(TAIL, 30 + 225 * (i + 1) / segments)
            canvas.drawArc(r, startA + i * step, step, false, p)
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
        text.typeface = if (ambient) tf300 else tf600
        text.textSize = HERO_SIZE
        // 4-char values like "10.4" or "15.0" must stay inside the ring: shrink to fit.
        text.textSize = HERO_SIZE * HaloFaceLogic.fitScale(text.measureText(value), HERO_MAX_W)
        val baseline = HERO_Y + text.textSize * 0.36f
        if (ambient) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = if (burnInProtection) 1.5f else 2f
            text.color = when {
                lowBit                                       -> Color.WHITE
                HaloFaceLogic.ambientHero(level) == AmbientHero.STRUCK -> 0xFF8C8C8C.toInt()
                else                                         -> 0xFFE6E6E6.toInt()
            }
        } else if (stale) {
            text.color = STALE
        } else {
            text.color = colour
            text.setShadowLayer(10f, 0f, 0f, withAlpha(colour, 0x55))
        }
        canvas.drawText(value, CX, baseline, text)
        if (stale && hasBg) {
            // Struck through (interactive and ambient): an old value must never look current.
            val half = text.measureText(value) / 2f + 4f
            resetPaint(!lowBit).apply { color = if (ambient) text.color else STALE; strokeWidth = 2f }
            canvas.drawLine(CX - half, HERO_Y, CX + half, HERO_Y, p)
        }
    }

    private fun drawDeltaLine(canvas: Canvas, ageMin: Long?, arrowColour: Int, deltaColour: Int, ageTextColour: Int, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val arrow = arrowString()
        val delta = if (hasBg) HaloFaceLogic.orDash(bg.delta) else HaloFaceLogic.NO_DATA
        val age = ageString(ageMin)
        text.reset()
        text.isAntiAlias = aa
        text.textAlign = Paint.Align.LEFT
        text.typeface = tf600
        text.textSize = 28f
        val arrowW = if (arrow.isEmpty()) 0f else text.measureText(arrow)
        text.typeface = tf500
        text.textSize = 22f
        val deltaW = text.measureText(delta)
        val sepW = text.measureText(SEP)
        val ageW = text.measureText(age)
        val gap = if (arrow.isEmpty()) 0f else 10f
        var x = CX - (arrowW + gap + deltaW + sepW + ageW) / 2f
        val y = baselineFor(DELTA_Y)
        if (arrow.isNotEmpty()) {
            text.typeface = tf600
            text.textSize = 28f
            text.color = arrowColour
            canvas.drawText(arrow, x, y, text)
            x += arrowW + gap
            text.typeface = tf500
            text.textSize = 22f
        }
        text.color = deltaColour
        canvas.drawText(delta, x, y, text)
        x += deltaW
        canvas.drawText(SEP, x, y, text)
        x += sepW
        text.color = ageTextColour
        canvas.drawText(age, x, y, text)
    }

    private fun drawIobCob(canvas: Canvas, ambient: Boolean) {
        val lowBit = ambient && lowBitAmbient
        val aa = !lowBit
        val r = arcRect(207f)
        val fraction = iobFraction()
        resetPaint(aa).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            strokeWidth = if (ambient) 1f else 5f
            color = if (ambient) amb(0xFF323232.toInt(), lowBit) else IOB_TRACK
        }
        if (!lowBit) canvas.drawArc(r, -50f, 100f, false, p)
        if (fraction > 0f) {
            val sweep = -100f * fraction
            if (ambient) {
                p.strokeWidth = 2f
                p.color = amb(0xFF8C8C8C.toInt(), lowBit)
            } else {
                p.color = CYAN
                p.setShadowLayer(5f, 0f, 0f, withAlpha(CYAN, 0x99))
            }
            canvas.drawArc(r, 50f, sweep, false, p)
        }
        val labelColour = if (ambient) amb(0xFF6E6E6E.toInt(), lowBit) else IOB_LABEL
        drawText(canvas, iobLabel, SIDE_X, 194f, 13f, tf600, labelColour, aa)
        drawFitText(canvas, HaloFaceLogic.orDash(st.iobSum), SIDE_X, 215f, 22f, tf500, if (ambient) amb(0xFFBEBEBE.toInt(), lowBit) else IOB_VALUE, aa)
        drawText(canvas, cobLabel, SIDE_X, 245f, 13f, tf600, labelColour, aa)
        drawFitText(canvas, HaloFaceLogic.orDash(st.cob), SIDE_X, 265f, 19f, tf500, if (ambient) amb(0xFFAAAAAA.toInt(), lowBit) else COB_VALUE, aa)
    }

    /**
     * Pill colour from the loop age. The watch only receives EventData.Status.openApsStatus, which the phone fills
     * with loop.lastRun.lastTBREnact (not the loop run time); see [HaloFaceLogic.loopAgeMinutes].
     */
    private fun drawLoopPill(canvas: Canvas, now: Long, ambient: Boolean) {
        val lowBit = ambient && lowBitAmbient
        val aa = !lowBit
        val age = HaloFaceLogic.loopAgeMinutes(st.openApsStatus, now)
        val state = HaloFaceLogic.loopState(age)
        val label = loopLabel(age)
        rect.set(CX - 37f, PILL_Y - 14f, CX + 37f, PILL_Y + 14f)
        if (ambient) {
            resetPaint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = amb(0xFF6E6E6E.toInt(), lowBit) }
            canvas.drawRoundRect(rect, 14f, 14f, p)
            p.color = when {
                lowBit                  -> Color.WHITE
                state == LoopState.OK   -> 0xFFC8C8C8.toInt()
                state == LoopState.WARN -> AMBER
                else                    -> RED
            }
            // A stale loop gets a filled dot even in ambient (tiny area, so fine for burn-in).
            if (state == LoopState.STALE) p.style = Paint.Style.FILL
            canvas.drawCircle(205f, PILL_Y, 4f, p)
            drawText(canvas, label, 233f, PILL_Y, 17f, tf600, amb(0xFFC8C8C8.toInt(), lowBit), aa)
            return
        }
        resetPaint().apply {
            color = when (state) {
                LoopState.OK    -> 0xFF12281E.toInt()
                LoopState.WARN  -> 0xFF2A2210.toInt()
                LoopState.STALE -> 0xFF2E1218.toInt()
            }
        }
        canvas.drawRoundRect(rect, 14f, 14f, p)
        resetPaint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1f
            color = when (state) {
                LoopState.OK    -> 0xFF285A40.toInt()
                LoopState.WARN  -> 0xFF5A4A20.toInt()
                LoopState.STALE -> 0xFF6A2632.toInt()
            }
        }
        canvas.drawRoundRect(rect, 14f, 14f, p)
        val dot = when (state) {
            LoopState.OK    -> GREEN
            LoopState.WARN  -> AMBER
            LoopState.STALE -> RED
        }
        resetPaint().apply { color = dot; setShadowLayer(4f, 0f, 0f, dot) }
        canvas.drawCircle(205f, PILL_Y, 5f, p)
        val textColour = when (state) {
            LoopState.OK    -> 0xFFD2F5E1.toInt()
            LoopState.WARN  -> 0xFFFFE6AA.toInt()
            LoopState.STALE -> 0xFFFFD2D8.toInt()
        }
        drawText(canvas, label, 233f, PILL_Y, 17f, tf600, textColour)
    }

    private fun drawBolusButton(canvas: Canvas, armed: Boolean) {
        resetPaint().apply {
            color = if (armed) 0xFF3C2A0A.toInt() else 0xFF141628.toInt()
            setShadowLayer(if (armed) 9f else 5f, 0f, 0f, if (armed) 0xAAFFC85A.toInt() else 0x66788CFF)
        }
        canvas.drawCircle(BTN_X, BTN_Y, BTN_R, p)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = if (armed) 0xFFFFC85A.toInt() else INDIGO }
        canvas.drawCircle(BTN_X, BTN_Y, BTN_R, p)
        drawText(canvas, amountString(drawnBolusAmount()), BTN_X, BTN_Y - 5f, 14f, tf700, if (armed) 0xFFFFE6AA.toInt() else 0xFFF0F0FF.toInt())
        if (armed) drawFitText(canvas, againLabel, BTN_X, BTN_Y + 11f, 10f, tf600, 0xFFFFC85A.toInt(), maxWidth = 42f)
        else drawFitText(canvas, subLabel, BTN_X, BTN_Y + 11f, 10f, tf600, 0xFF96A0DC.toInt(), maxWidth = 42f)
    }

    // ---------------------------------------------------------------- cached values

    private fun backdrop(colour: Int): Shader {
        val cached = backdropShader
        if (cached != null && backdropColour == colour) return cached
        backdropColour = colour
        return RadialGradient(CX, CY, 147f, withAlpha(colour, 0x1A), Color.TRANSPARENT, Shader.TileMode.CLAMP).also { backdropShader = it }
    }

    private fun sweep(colour: Int, sweep: Float): Shader {
        val key = (colour * 31) xor sweep.toInt()
        val cached = sweepShader
        if (cached != null && sweepKey == key) return cached
        sweepKey = key
        val stop = ((sweep.toInt() + 6f) / 360f).coerceIn(0.01f, 1f)
        return SweepGradient(CX, CY, intArrayOf(FILL_START, colour, colour), floatArrayOf(0f, stop, 1f)).also {
            sweepMatrix.setRotate(HaloFaceLogic.RING_START - 6f, CX, CY)
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
    private fun drawFitText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, tf: Typeface, colour: Int, aa: Boolean = true, maxWidth: Float = SIDE_MAX_W) {
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
        val left = (faceRect.width() - 450f * scale) / 2f
        val top = (faceRect.height() - 450f * scale) / 2f
        val dx = (x - left) / scale
        val dy = (y - top) / scale

        val enabled = buttonShown && bolusButtonEnabled()
        val inside = BolusTapGesture.isInside(dx, dy, BTN_X, BTN_Y, BTN_HIT_R)
        when (gesture.onTap(inside, enabled)) {
            BolusTapGesture.Decision.ARM    -> {
                handler.removeCallbacks(disarmRunnable)
                // Fresh FILTER_REACHABLE query; its answer must arrive before the second tap (see SEND).
                // The quick check below still uses the cached value, so an obviously lost phone refuses at once.
                armedAt = System.currentTimeMillis()
                refreshPhoneReachable()
                val verdict = evaluateRequest()
                if (verdict != BolusRequestGuard.Verdict.SEND) {
                    // Refuse right away instead of arming a request that cannot be sent.
                    gesture.reset()
                    refused(verdict)
                } else {
                    vibrate(vibArmed)
                    handler.postDelayed(disarmRunnable, gesture.windowMs + 50)
                }
                invalidate()
                return
            }

            BolusTapGesture.Decision.SEND   -> {
                handler.removeCallbacks(disarmRunnable)
                val amount = bolusAmount()
                // Only a reachability answer that arrived after the first tap counts (the query is asynchronous).
                val phoneOk = phoneReachable && phoneCheckedAt >= armedAt
                val verdict = guard.checkAndRecord(amount, maxBolus(), wearControl(), phoneOk, data.lastUpdateTimestamp)
                if (verdict == BolusRequestGuard.Verdict.SEND) {
                    aapsLogger.info(LTag.WEAR, "HaloWatchface: double-tap bolus request $amount U")
                    // Exactly as BolusActivity: the phone checks and the watch shows the confirmation screen.
                    rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(amount, 0)))
                    vibrate(vibSent)
                    toast(getString(R.string.halo_bolus_sent))
                } else refused(verdict)
                invalidate()
                return
            }

            BolusTapGesture.Decision.DISARM -> {
                handler.removeCallbacks(disarmRunnable)
                invalidate()
            }

            BolusTapGesture.Decision.IGNORE -> Unit
        }
        if (inside && enabled) return

        // Double-tap on the hero BG opens the main menu, like the other AAPS faces.
        if (dx in 110f..340f && dy in 175f..275f) {
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

    private fun evaluateRequest(): BolusRequestGuard.Verdict =
        guard.evaluate(bolusAmount(), maxBolus(), wearControl(), phoneReachable, data.lastUpdateTimestamp)

    private fun refused(verdict: BolusRequestGuard.Verdict) {
        aapsLogger.info(LTag.WEAR, "HaloWatchface: bolus request refused on the watch: $verdict")
        vibrate(vibRefused)
        val message = when (verdict) {
            BolusRequestGuard.Verdict.REFUSED_MAX_BOLUS,
            BolusRequestGuard.Verdict.REFUSED_AMOUNT       ->
                getString(R.string.halo_bolus_refused_max, String.format(Locale.US, "%.1f", bolusAmount()), String.format(Locale.US, "%.1f", maxBolus()))

            BolusRequestGuard.Verdict.REFUSED_PENDING      -> getString(R.string.halo_bolus_refused_pending)
            BolusRequestGuard.Verdict.REFUSED_NO_PHONE     -> getString(R.string.halo_bolus_refused_phone)
            BolusRequestGuard.Verdict.REFUSED_STALE_DATA   -> getString(R.string.halo_bolus_refused_stale)
            BolusRequestGuard.Verdict.REFUSED_WEAR_CONTROL -> getString(R.string.halo_bolus_refused_wear_control)
            BolusRequestGuard.Verdict.SEND                 -> return
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
        private const val CX = 225f
        private const val CY = 225f
        private const val RING_R = 133.5f       // ring stroke 13: 127 .. 140
        private const val TAIL_R = 120f         // trend tail inside the ring
        private const val TAIL_WINDOW_MS = 30 * 60 * 1000L
        private const val KEY_LAST_SENT_AT = "halo_bolus_last_sent_at"
        private const val OLD_KEY_DOUBLE_TAP = "kusi_bolus_double_tap"
        private const val OLD_KEY_AMOUNT = "kusi_bolus_amount"
        private const val TIME_Y = 138f
        private const val DATE_Y = 168f
        private const val HERO_Y = 229f
        private const val HERO_SIZE = 104f
        private const val HERO_MAX_W = 196f     // keeps "10.4" inside the trend tail
        private const val DELTA_Y = 291f
        private const val PILL_Y = 375f
        private const val SIDE_X = 393f         // IOB / COB column inside the IOB arc
        private const val SIDE_MAX_W = 50f
        private const val SEP = " · "

        // Bolus ball at 9 o'clock with a clear zone to the ring (ring outer edge at x = 85).
        private const val BTN_X = 46f
        private const val BTN_Y = 225f
        private const val BTN_R = 27f
        private const val BTN_HIT_R = 34f

        private const val GREEN = 0xFF3DDC97.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        private const val RED = 0xFFFF5A6E.toInt()
        private const val GREY = 0xFF8A909C.toInt()
        private const val CYAN = 0xFF5AC8FA.toInt()
        private const val INDIGO = 0xFF788CFF.toInt()
        private const val TRACK = 0xFF1E2228.toInt()
        private const val BAND = 0xFF1C3E30.toInt()
        private const val FILL_START = 0xFF286EBE.toInt()
        private const val TAIL = 0xFFAAB4C3.toInt()
        private const val TIME = 0xFFEBEBF0.toInt()
        private const val DATE = 0xFF8A909C.toInt()
        private const val DELTA = 0xFFB4BAC4.toInt()
        private const val STALE = 0xFF6E7480.toInt()
        private const val SECONDARY = 0xFF6E7480.toInt()
        private const val IOB_TRACK = 0xFF1A1C24.toInt()
        private const val IOB_LABEL = 0xFF5A606E.toInt()
        private const val IOB_VALUE = 0xFFD7F0FF.toInt()
        private const val COB_VALUE = 0xFFFFE0B2.toInt()
        private const val AMB_GREY = 0xFFAAAAAA.toInt()
    }
}
