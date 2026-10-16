package app.aaps.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSSMB.PhoneMovementDetector
import app.aaps.plugins.aps.openAPSSMB.StepService
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PhoneSensorRegistrarTest : TestBase() {

    @Mock lateinit var context: Context
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var fabricPrivacy: FabricPrivacy
    @Mock lateinit var sensorManager: SensorManager
    @Mock lateinit var stepSensor: Sensor
    @Mock lateinit var accelerometer: Sensor

    private lateinit var sut: PhoneSensorRegistrar

    @BeforeEach
    fun setup() {
        whenever(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        whenever(sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)).thenReturn(stepSensor)
        whenever(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accelerometer)
        whenever(sensorManager.registerListener(any(), any<Sensor>(), any<Int>())).thenReturn(true)
        sut = PhoneSensorRegistrar(context, preferences, rxBus, aapsSchedulers, aapsLogger, fabricPrivacy)
    }

    private fun prefs(stepsFromPhone: Boolean, activityDetection: Boolean) {
        whenever(preferences.get(BooleanKey.ActivityMonitorShowStepsFromSmartphone)).thenReturn(stepsFromPhone)
        whenever(preferences.get(BooleanKey.ApsActivityDetection)).thenReturn(activityDetection)
    }

    @Test
    fun neededMatrix() {
        assertThat(PhoneSensorRegistrar.stepCounterNeeded(stepsFromPhone = false, activityDetection = false)).isFalse()
        assertThat(PhoneSensorRegistrar.stepCounterNeeded(stepsFromPhone = true, activityDetection = false)).isTrue()
        assertThat(PhoneSensorRegistrar.stepCounterNeeded(stepsFromPhone = false, activityDetection = true)).isTrue()
        assertThat(PhoneSensorRegistrar.stepCounterNeeded(stepsFromPhone = true, activityDetection = true)).isTrue()
        assertThat(PhoneSensorRegistrar.accelerometerNeeded(activityDetection = false)).isFalse()
        assertThat(PhoneSensorRegistrar.accelerometerNeeded(activityDetection = true)).isTrue()
    }

    @Test
    fun bothOnRegistersBoth() {
        prefs(stepsFromPhone = true, activityDetection = true)
        sut.start()
        verify(sensorManager).registerListener(StepService, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        verify(sensorManager).registerListener(PhoneMovementDetector, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        assertThat(sut.stepCounterRegistered).isTrue()
        assertThat(sut.accelerometerRegistered).isTrue()
    }

    @Test
    fun bothOffRegistersNothing() {
        prefs(stepsFromPhone = false, activityDetection = false)
        sut.start()
        verify(sensorManager, never()).registerListener(any(), anyOrNull<Sensor>(), any<Int>())
        assertThat(sut.stepCounterRegistered).isFalse()
        assertThat(sut.accelerometerRegistered).isFalse()
    }

    @Test
    fun stepsGraphOnlyRegistersStepCounterOnly() {
        prefs(stepsFromPhone = true, activityDetection = false)
        sut.start()
        verify(sensorManager).registerListener(StepService, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        verify(sensorManager, never()).registerListener(eq(PhoneMovementDetector), anyOrNull<Sensor>(), any<Int>())
    }

    @Test
    fun preferenceChangeRegistersAndUnregisters() {
        prefs(stepsFromPhone = false, activityDetection = false)
        sut.start()

        prefs(stepsFromPhone = false, activityDetection = true)
        rxBus.send(EventPreferenceChange(BooleanKey.ApsActivityDetection.key))
        verify(sensorManager).registerListener(StepService, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        verify(sensorManager).registerListener(PhoneMovementDetector, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)

        // unrelated change: nothing re-registered
        rxBus.send(EventPreferenceChange("some_other_key"))
        verify(sensorManager, times(1)).registerListener(StepService, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)

        prefs(stepsFromPhone = false, activityDetection = false)
        rxBus.send(EventPreferenceChange(BooleanKey.ApsActivityDetection.key))
        verify(sensorManager).unregisterListener(StepService)
        verify(sensorManager).unregisterListener(PhoneMovementDetector)
        assertThat(sut.stepCounterRegistered).isFalse()
        assertThat(sut.accelerometerRegistered).isFalse()
    }

    @Test
    fun turningActivityDetectionOffKeepsStepCounterForGraph() {
        prefs(stepsFromPhone = true, activityDetection = true)
        sut.start()
        prefs(stepsFromPhone = true, activityDetection = false)
        rxBus.send(EventPreferenceChange(BooleanKey.ApsActivityDetection.key))
        verify(sensorManager).unregisterListener(PhoneMovementDetector)
        verify(sensorManager, never()).unregisterListener(StepService)
        assertThat(sut.stepCounterRegistered).isTrue()
        assertThat(sut.accelerometerRegistered).isFalse()
    }

    @Test
    fun missingSensorIsNotMarkedRegistered() {
        whenever(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(null)
        prefs(stepsFromPhone = true, activityDetection = true)
        sut.start()
        assertThat(sut.stepCounterRegistered).isTrue()
        assertThat(sut.accelerometerRegistered).isFalse()
    }
}
