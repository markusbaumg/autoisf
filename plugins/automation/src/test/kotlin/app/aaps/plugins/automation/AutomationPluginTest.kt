package app.aaps.plugins.automation

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.validators.preferences.AdaptiveListPreference
import app.aaps.plugins.automation.services.LocationServiceHelper
import app.aaps.plugins.automation.ui.TimerUtil
import app.aaps.shared.tests.TestBaseWithProfile
import app.aaps.core.interfaces.rx.events.EventNetworkChange
import app.aaps.plugins.automation.keys.AutomationStringKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AutomationPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var loop: Loop
    @Mock lateinit var locationServiceHelper: LocationServiceHelper
    @Mock lateinit var timerUtil: TimerUtil
    private lateinit var automationPlugin: AutomationPlugin

    init {
        addInjector {
            if (it is AdaptiveListPreference) {
                it.preferences = preferences
            }
        }
    }

    @BeforeEach fun prepare() {
        automationPlugin = AutomationPlugin(
            injector, aapsLogger, rh, preferences, context, fabricPrivacy, loop, rxBus, constraintChecker,
            aapsSchedulers, config, locationServiceHelper, dateUtil, activePlugin, timerUtil
        )
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        automationPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    @Test
    fun storeToSpSkipsWriteWhenRulesUnchanged() {
        whenever(preferences.get(AutomationStringKey.AutomationEvents)).thenReturn("[]")
        automationPlugin.storeToSP()
        verify(preferences, never()).put(eq(AutomationStringKey.AutomationEvents), any<String>())
    }

    @Test
    fun storeToSpWritesWhenRulesChanged() {
        whenever(preferences.get(AutomationStringKey.AutomationEvents)).thenReturn("[{\"title\":\"old\"}]")
        automationPlugin.storeToSP()
        verify(preferences).put(AutomationStringKey.AutomationEvents, "[]")
    }

    @Test
    fun networkStateComparisonUsesTriggerRelevantFields() {
        val base = EventNetworkChange(mobileConnected = true, wifiConnected = true, ssid = "home")
        // fields no trigger reads: duplicates are dropped
        assertThat(AutomationPlugin.sameNetworkState(base, EventNetworkChange(mobileConnected = true, wifiConnected = true, ssid = "home", vpnConnected = true, roaming = true, metered = true))).isTrue()
        // SSID / wifi / mobile changes still run the automations
        assertThat(AutomationPlugin.sameNetworkState(base, EventNetworkChange(mobileConnected = true, wifiConnected = true, ssid = "work"))).isFalse()
        assertThat(AutomationPlugin.sameNetworkState(base, EventNetworkChange(mobileConnected = true, wifiConnected = false, ssid = "home"))).isFalse()
        assertThat(AutomationPlugin.sameNetworkState(base, EventNetworkChange(mobileConnected = false, wifiConnected = true, ssid = "home"))).isFalse()
    }
}
