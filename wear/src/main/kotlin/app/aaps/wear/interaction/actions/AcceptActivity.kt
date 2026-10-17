package app.aaps.wear.interaction.actions

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.InputDeviceCompat
import androidx.core.view.MotionEventCompat
import androidx.core.view.ViewConfigurationCompat
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import app.aaps.wear.comm.DataLayerListenerServiceWear
import app.aaps.wear.comm.IntentCancelNotification
import app.aaps.wear.comm.IntentWearToMobile
import app.aaps.wear.widgets.PagerAdapter
import kotlin.math.roundToInt

class AcceptActivity : ViewSelectorActivity() {

    var message = ""
    var actionKey = ""
    private var confirmTitle = ""
    private var details: EventData.ConfirmDetails? = null
    private var dismissThread: DismissThread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dismissThread = DismissThread()
        dismissThread?.start()
        val extras = intent.extras
        message = extras?.getString(DataLayerListenerServiceWear.KEY_MESSAGE, "") ?: ""
        actionKey = extras?.getString(DataLayerListenerServiceWear.KEY_ACTION_DATA, "") ?: ""
        confirmTitle = extras?.getString(DataLayerListenerServiceWear.KEY_TITLE, "") ?: ""
        // the details are shown only when they name exactly what the confirm button sends (else the phone's text)
        details = try {
            ConfirmScreen.matchingDetails(
                ConfirmScreen.decode(extras?.getString(ConfirmScreen.KEY_DETAILS)),
                if (actionKey.isEmpty()) null else EventData.deserialize(actionKey)
            )
        } catch (_: Exception) {
            null
        }
        if (message.isEmpty()) {
            finish()
            return
        }
        // the pages show the title themselves (ConfirmScreen), large and straight instead of the small curved one
        findViewById<View>(R.id.title_curved)?.visibility = View.GONE
        findViewById<View>(R.id.title)?.visibility = View.GONE
        setAdapter(MyPagerAdapter())
        val vibrator = getSystemService(Vibrator::class.java)
        val vibratePattern = longArrayOf(0, 100, 50, 100, 50)
        val effect = VibrationEffect.createWaveform(vibratePattern, -1)
        vibrator?.vibrate(effect)
    }

    override fun onPause() {
        super.onPause()
        finish()
    }

    /** The phone's error screens come as a ConfirmAction whose returnCommand is an Error (ignored by the phone). */
    private val isError: Boolean
        get() = actionKey.isNotEmpty() && try {
            EventData.deserialize(actionKey) is EventData.Error
        } catch (_: Exception) {
            false
        }

    /** False for a status screen without a returnCommand: its button only closes it (display only). */
    private val hasAction: Boolean
        get() = actionKey.isNotEmpty()

    private inner class MyPagerAdapter : PagerAdapter() {

        override fun getPageCount(): Int = 2

        override fun instantiateItem(container: ViewGroup, position: Int): View = when (position) {
            0    -> {
                // Page 0: Message page
                LayoutInflater.from(applicationContext).inflate(R.layout.action_confirm_text, container, false).apply {
                    val scrollView = findViewById<View>(R.id.message_scroll)
                    try {
                        ConfirmScreen(this@AcceptActivity).bindInfoPage(this, confirmTitle, message, details, isError, hasAction)
                    } catch (e: Exception) {
                        // never lose the confirm over its looks: fall back to the phone's plain text
                        findViewById<TextView>(R.id.message)?.apply { visibility = View.VISIBLE; text = message }
                    }
                    scrollView.setOnGenericMotionListener { v: View, ev: MotionEvent ->
                        if (ev.action == MotionEvent.ACTION_SCROLL &&
                            ev.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)
                        ) {
                            val delta = -ev.getAxisValue(MotionEventCompat.AXIS_SCROLL) *
                                ViewConfigurationCompat.getScaledVerticalScrollFactor(
                                    ViewConfiguration.get(container.context),
                                    container.context
                                )
                            v.scrollBy(0, delta.roundToInt())
                            return@setOnGenericMotionListener true
                        }
                        false
                    }
                    scrollView.requestFocus()
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }

            else -> {
                // Page 1: Confirm page
                LayoutInflater.from(applicationContext).inflate(R.layout.action_confirm_ok, container, false).apply {
                    try {
                        ConfirmScreen(this@AcceptActivity).bindConfirmPage(this, details, isError, hasAction)
                    } catch (_: Exception) {
                        // display only: the button below works without it
                    }
                    val confirmButton = findViewById<ImageView>(R.id.confirmbutton)
                    confirmButton.setOnClickListener { view ->
                        // Visual feedback: scale animation
                        view.animate()
                            .scaleX(1.2f)
                            .scaleY(1.2f)
                            .setDuration(150)
                            .start()

                        // Haptic feedback
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)

                        if (actionKey.isNotEmpty()) startService(IntentWearToMobile(this@AcceptActivity, actionKey))
                        startForegroundService(IntentCancelNotification(this@AcceptActivity))
                        finishAffinity()
                    }
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }
        }
    }

    @Synchronized public override fun onDestroy() {
        dismissThread?.invalidate()
        super.onDestroy()
    }

    private inner class DismissThread : Thread() {

        private var valid = true
        @Synchronized fun invalidate() {
            valid = false
        }

        override fun run() {
            SystemClock.sleep((60 * 1000L))
            synchronized(this) { if (valid) finish() }
        }
    }

    @Synchronized override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dismissThread?.invalidate()
        intent.extras?.let {
            startActivity(Intent(this, AcceptActivity::class.java).apply { putExtras(it) })
            finish()
        }
    }
}
