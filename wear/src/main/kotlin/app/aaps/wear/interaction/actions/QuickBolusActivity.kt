package app.aaps.wear.interaction.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import app.aaps.wear.watchfaces.common.PhoneReachability
import dagger.android.DaggerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Quick-bolus picker ("Schnell-Bolus"), opened only by the "+ U" ball of Orbit, Halo or Glass in "Auswahl" mode (double tap, never in
 * ambient). Every decision lives in [QuickBolusPicker]; this activity draws it ([QuickBolusView]), re-asks the phone's
 * reachability on open and every few seconds, and sends the ONE request ([EventData.ActionBolusPreCheck]) exactly as
 * the face ball and BolusActivity do: the phone checks it again and the watch shows the unchanged confirm screen
 * (AcceptActivity). That screen pauses this one, which then finishes.
 *
 * Closes on screen off, onPause / onStop (wrist down, another screen), after 10 s without a touch, on ×, back or a
 * swipe. No rotary input. Nothing is remembered between two openings.
 *
 * The 600 ms input lock starts when the picker is really on screen (first drawn frame, window focus, end of the open
 * animation; [QuickBolusPicker.onShown]), not in onCreate: until then every touch is ignored.
 */
class QuickBolusActivity : DaggerActivity() {

    @Inject lateinit var sp: SP
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var complicationDataRepository: ComplicationDataRepository

    private val handler = Handler(Looper.getMainLooper())
    private val clock: () -> Long = testClock ?: { System.currentTimeMillis() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var guard: BolusRequestGuard
    private lateinit var picker: QuickBolusPicker
    private lateinit var view: QuickBolusView
    private var data = ComplicationData()
    private var finishing = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            close("screen off")
        }
    }
    private var screenOffRegistered = false

    private val ticker = object : Runnable {
        override fun run() {
            if (finishing) return
            when (picker.tick()) {
                QuickBolusPicker.Tick.CLOSE -> {
                    close("timeout")
                    return
                }

                else                        -> Unit
            }
            if (picker.phase == QuickBolusPicker.Phase.WAITING) view.spinnerDeg = (view.spinnerDeg + SPIN_STEP) % 360f
            redraw()
            handler.postDelayed(this, if (picker.phase == QuickBolusPicker.Phase.WAITING) SPIN_FRAME_MS else TICK_MS)
        }
    }

    private val reachTick = object : Runnable {
        override fun run() {
            if (finishing) return
            refreshReach()
            handler.postDelayed(this, REACH_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // no FLAG_KEEP_SCREEN_ON: screen timeout and wrist-down must be able to end the picker
        guard = BolusRequestGuard(clock = clock, store = BolusRequestPrefs.lastSentStore { sp })
        // the 600 ms input lock runs from the first frame on screen, not from here (a Wear launch takes a few hundred ms)
        picker = QuickBolusPicker(clock, guard, BolusRequestPrefs.pickerAmounts(sp), lockFromShown = true)
        view = QuickBolusView(this).apply {
            locale = Locale.getDefault()
            contentDescription = getString(R.string.quick_bolus_title)
            onAnyTouch = { picker.onTouch() }
            isInputLocked = { picker.inputLocked() }
            onAmount = { onAmountTap(it) }
            onClose = { if (picker.onClose()) close("close button") }
            onFirstDraw = { shown("first frame") }
        }
        setContentView(view)
        scope.launch {
            complicationDataRepository.complicationData.collect {
                data = it
                redraw()
            }
        }
        refreshReach()
        redraw()
        handler.postDelayed(ticker, TICK_MS)
        handler.postDelayed(reachTick, REACH_EVERY_MS)
        try {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            screenOffRegistered = true
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "QuickBolusActivity: screen-off receiver failed", e)
        }
        aapsLogger.info(LTag.WEAR, "QuickBolusActivity: opened, amounts ${picker.amounts}")
    }

    /** The input lock ends: redraw at full strength. */
    private val lockEnded = Runnable { redraw() }

    /** First frame, window focus, end of the open animation: the 600 ms input lock (re)starts now while it still runs. */
    private fun shown(why: String) {
        if (finishing || !picker.onShown()) return
        aapsLogger.debug(LTag.WEAR, "QuickBolusActivity: input lock from $why")
        handler.removeCallbacks(lockEnded)
        handler.postDelayed(lockEnded, QuickBolusPicker.INPUT_LOCK_MS + 10L)
        redraw()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) shown("window focus")
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        shown("open animation done")
    }

    private fun snapshot(): QuickBolusPicker.Snapshot = QuickBolusPicker.snapshot(
        data, clock(),
        prefOn = BolusRequestPrefs.enabled(sp) && BolusRequestPrefs.mode(sp) == BolusRequestPrefs.Mode.PICKER,
        wearControl = preferences.get(BooleanKey.WearControl),
        maxBolus = BolusRequestPrefs.maxBolus(sp, getString(R.string.key_treatments_safety_max_bolus)),
        amounts = picker.amounts
    )

    private fun redraw() {
        if (finishing) return
        val now = clock()
        view.header = QuickBolusView.header(data, now, view.labels, view.locale)
        view.screen = picker.screen(snapshot())
    }

    private fun refreshReach() {
        PhoneReachability.refresh(this, guard, aapsLogger, "QuickBolusActivity") { redraw() }
        // the guard notifies only a change; a repeated "reachable" is still a fresh answer
        handler.postDelayed({ redraw() }, 300L)
    }

    private fun onAmountTap(amount: Double) {
        when (val r = picker.onAmountTap(amount, snapshot())) {
            is QuickBolusPicker.TapResult.Send    -> {
                aapsLogger.info(LTag.WEAR, "QuickBolusActivity: bolus request ${r.amount} U")
                // the same request as the face ball and BolusActivity: the phone checks, the watch confirms
                rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(r.amount, 0)))
                vibrate()
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, SPIN_FRAME_MS)
            }

            is QuickBolusPicker.TapResult.Refused -> refreshReach()
            QuickBolusPicker.TapResult.Ignored    -> Unit
        }
        redraw()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        close("back")
    }

    override fun onPause() {
        super.onPause()
        // wrist down, screen off, or the confirm screen (AcceptActivity) on top
        close("paused")
    }

    override fun onStop() {
        super.onStop()
        close("stopped")
    }

    private fun close(why: String) {
        if (finishing) return
        finishing = true
        if (::picker.isInitialized) picker.close()
        aapsLogger.info(LTag.WEAR, "QuickBolusActivity: closed ($why)")
        handler.removeCallbacksAndMessages(null)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        if (screenOffRegistered) try {
            unregisterReceiver(screenOff)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun vibrate() {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "QuickBolusActivity vibrate failed", e)
        }
    }

    companion object {

        /** Robolectric only (wear/src/test): a clock that follows the test's looper time. Always null on the watch. */
        @VisibleForTesting
        @Volatile internal var testClock: (() -> Long)? = null

        private const val TICK_MS = 250L
        private const val SPIN_FRAME_MS = 50L
        private const val SPIN_STEP = 12f
        /** Fresh reachability answers while open (the picker needs one at most 10 s old to send). */
        private const val REACH_EVERY_MS = 3_000L
    }
}
