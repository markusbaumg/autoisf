package app.aaps.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSSMB.PhoneMovementDetector
import app.aaps.plugins.aps.openAPSSMB.StepService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers the phone's step counter and accelerometer only while something uses them.
 *
 * Consumers:
 * - [StepService] (step counter): the activity monitor in AutoISF/SMB (activity_detection) and the
 *   steps graph that stores smartphone step counts (steps_graph_from_smartphone).
 * - [PhoneMovementDetector] (accelerometer, ~5 Hz at SENSOR_DELAY_NORMAL): only the activity
 *   monitor, which skips (in)activity adjustments when the phone was not moved for 15 min.
 *
 * When neither preference is on, both listeners stay unregistered. Preference changes are picked up
 * through [EventPreferenceChange], so toggling works without restarting AAPS.
 */
@Singleton
class PhoneSensorRegistrar @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val aapsSchedulers: AapsSchedulers,
    private val aapsLogger: AAPSLogger,
    private val fabricPrivacy: FabricPrivacy
) {

    private val disposable = CompositeDisposable()
    private val sensorManager: SensorManager? by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager? }

    @VisibleForTesting var stepCounterRegistered = false
        private set
    @VisibleForTesting var accelerometerRegistered = false
        private set

    fun start() {
        sync()
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            // sync() is idempotent and cheap; reacting to every change also covers keys sent by the setup wizard
            .subscribe({ sync() }, fabricPrivacy::logException)
    }

    @Synchronized
    fun sync() {
        val activityDetection = preferences.get(BooleanKey.ApsActivityDetection)
        val stepsFromPhone = preferences.get(BooleanKey.ActivityMonitorShowStepsFromSmartphone)
        val wantSteps = stepCounterNeeded(stepsFromPhone, activityDetection)
        val wantAccelerometer = accelerometerNeeded(activityDetection)

        if (wantSteps != stepCounterRegistered) {
            // The step counter reports steps since boot. Drop the old baseline before registering so steps
            // taken while unregistered are not booked into the current 5 min block. Done here and not at
            // unregistration: an event still delivered after unregisterListener() would set it again.
            if (wantSteps) StepService.resetBaseline()
            stepCounterRegistered = update(StepService, Sensor.TYPE_STEP_COUNTER, wantSteps, "step counter")
        }
        if (wantAccelerometer != accelerometerRegistered)
            accelerometerRegistered = update(PhoneMovementDetector, Sensor.TYPE_ACCELEROMETER, wantAccelerometer, "accelerometer")
    }

    /** @return the new registration state */
    private fun update(listener: SensorEventListener, type: Int, register: Boolean, name: String): Boolean {
        val sm = sensorManager ?: return false
        return if (register) {
            val sensor = sm.getDefaultSensor(type)
            if (sensor == null) {
                aapsLogger.debug(LTag.CORE, "Phone $name not available")
                false
            } else {
                val ok = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                aapsLogger.debug(LTag.CORE, "Phone $name listener registered: $ok")
                ok
            }
        } else {
            sm.unregisterListener(listener)
            aapsLogger.debug(LTag.CORE, "Phone $name listener unregistered")
            false
        }
    }

    companion object {

        fun stepCounterNeeded(stepsFromPhone: Boolean, activityDetection: Boolean): Boolean = stepsFromPhone || activityDetection
        fun accelerometerNeeded(activityDetection: Boolean): Boolean = activityDetection
    }
}
