package app.aaps.plugins.sync.wear

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.sync.tidepool.utils.RateLimit
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobileHelper
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock

class WearPluginTest : TestBaseWithProfile() {

    @Mock lateinit var dataHandlerMobile: DataHandlerMobile
    @Mock lateinit var dataLayerListenerServiceMobileHelper: DataLayerListenerServiceMobileHelper

    private lateinit var wearPlugin: WearPlugin
    private lateinit var rateLimit: RateLimit

    init {
        addInjector {
            if (it is AdaptiveSwitchPreference) {
                it.preferences = preferences
                it.config = config
            }
        }
    }

    @BeforeEach fun prepare() {
        rateLimit = RateLimit(dateUtil)
        wearPlugin = WearPlugin(aapsLogger, rh, aapsSchedulers, preferences, fabricPrivacy, rxBus, context, dataHandlerMobile, dataLayerListenerServiceMobileHelper, config)
    }

    @Test
    fun watchPreferencesStartAResend() {
        assertThat(WearPlugin.isWatchPreference(BooleanKey.WearControl.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(StringKey.GeneralUnits.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(IntKey.OverviewBolusPercentage.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(IntKey.SafetyMaxCarbs.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(DoubleKey.SafetyMaxBolus.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(DoubleKey.OverviewInsulinButtonIncrement2.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(IntKey.OverviewCarbsButtonIncrement1.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(UnitDoubleKey.OverviewHighMark.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(StringNonKey.QuickWizard.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(BooleanKey.WearCustomWatchfaceAuthorization.key)).isTrue()
        assertThat(WearPlugin.isWatchPreference(StringNonKey.WearCwfFileName.key)).isTrue()
    }

    @Test
    fun otherPreferencesDoNotStartAResend() {
        assertThat(WearPlugin.isWatchPreference(null)).isFalse()
        assertThat(WearPlugin.isWatchPreference("")).isFalse()
        assertThat(WearPlugin.isWatchPreference("ukf_persisted_parameters")).isFalse()
        assertThat(WearPlugin.isWatchPreference(IntNonKey.RangeToDisplay.key)).isFalse()
        assertThat(WearPlugin.isWatchPreference(BooleanKey.WearNotifyOnSmb.key)).isFalse()
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        wearPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }
}
