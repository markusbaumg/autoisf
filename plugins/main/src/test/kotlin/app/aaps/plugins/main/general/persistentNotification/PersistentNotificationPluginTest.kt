package app.aaps.plugins.main.general.persistentNotification

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.shared.tests.TestBase
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit

class PersistentNotificationPluginTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var profileUtil: ProfileUtil
    @Mock lateinit var fabricPrivacy: FabricPrivacy
    @Mock lateinit var activePlugins: ActivePlugin
    @Mock lateinit var iobCobCalculator: IobCobCalculator
    @Mock lateinit var processedTbrEbData: ProcessedTbrEbData
    @Mock lateinit var context: Context
    @Mock lateinit var notificationHolder: NotificationHolder
    @Mock lateinit var dummyServiceHelper: DummyServiceHelper
    @Mock lateinit var iconsProvider: IconsProvider
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var config: Config
    @Mock lateinit var decimalFormatter: DecimalFormatter

    /** The plugin's io scheduler is virtual time, so the throttle window can be stepped. */
    private val io = TestScheduler()
    private val pluginSchedulers = object : AapsSchedulers {
        override val main: Scheduler = Schedulers.trampoline()
        override val io: Scheduler = this@PersistentNotificationPluginTest.io
        override val cpu: Scheduler = Schedulers.trampoline()
        override val newThread: Scheduler = Schedulers.trampoline()
    }

    private lateinit var sut: PersistentNotificationPlugin

    @BeforeEach fun setUp() {
        // Not initialized: a rebuild returns before it touches Android, only the foreground
        // service kick (one per rebuild) remains to be counted.
        whenever(config.appInitialized).thenReturn(false)
        sut = PersistentNotificationPlugin(
            aapsLogger, rh, pluginSchedulers, profileFunction, profileUtil, fabricPrivacy, activePlugins, iobCobCalculator,
            processedTbrEbData, rxBus, context, notificationHolder, dummyServiceHelper, iconsProvider, glucoseStatusProvider, config, decimalFormatter
        )
        sut.onStart()
    }

    @AfterEach fun tearDown() {
        sut.onStop()
    }

    private fun advance(ms: Long) = io.advanceTimeBy(ms, TimeUnit.MILLISECONDS)

    @Test fun `a burst of events gives one rebuild at once and one at the end of the window`() {
        // the preference storm: thousands of writes forwarded by the open settings screen
        repeat(200) { rxBus.send(EventPreferenceChange("key$it")) }
        rxBus.send(EventRefreshOverview("test"))
        io.triggerActions()
        verify(dummyServiceHelper, times(1)).startService(context)
        advance(PersistentNotificationPlugin.UPDATE_WINDOW_MS - 1)
        verify(dummyServiceHelper, times(1)).startService(context)
        advance(1)
        verify(dummyServiceHelper, times(2)).startService(context)
        // nothing more without new events
        advance(60_000)
        verify(dummyServiceHelper, times(2)).startService(context)
    }

    @Test fun `events further apart than the window are each handled at once`() {
        rxBus.send(EventPreferenceChange("a"))
        io.triggerActions()
        verify(dummyServiceHelper, times(1)).startService(context)
        advance(PersistentNotificationPlugin.UPDATE_WINDOW_MS)
        rxBus.send(EventPreferenceChange("b"))
        io.triggerActions()
        verify(dummyServiceHelper, times(2)).startService(context)
    }

    @Test fun `the last event of a burst is never lost`() {
        rxBus.send(EventPreferenceChange("first"))
        io.triggerActions()
        advance(PersistentNotificationPlugin.UPDATE_WINDOW_MS / 2)
        rxBus.send(EventPreferenceChange("last"))
        io.triggerActions()
        verify(dummyServiceHelper, times(1)).startService(context)
        advance(PersistentNotificationPlugin.UPDATE_WINDOW_MS / 2)
        verify(dummyServiceHelper, times(2)).startService(context)
    }
}
