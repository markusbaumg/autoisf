package app.aaps.ui.activityMonitor

import android.app.Activity
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

class ActivityMonitorTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var activity: Activity

    private lateinit var sut: ActivityMonitor
    private lateinit var name: String

    @BeforeEach
    fun setup() {
        sut = ActivityMonitor(aapsLogger, rh, preferences, dateUtil)
        name = activity.javaClass.simpleName
    }

    @Test
    fun resumeDoesNotWritePreferences() {
        whenever(dateUtil.now()).thenReturn(1000L)
        sut.onActivityResumed(activity)
        verifyNoInteractions(preferences)
    }

    @Test
    fun pauseAddsElapsedTimeSinceResume() {
        whenever(preferences.get(LongComposedKey.ActivityMonitorTotal, name)).thenReturn(500L)
        whenever(dateUtil.now()).thenReturn(1000L)
        sut.onActivityResumed(activity)
        whenever(dateUtil.now()).thenReturn(4000L)
        sut.onActivityPaused(activity)
        verify(preferences).put(LongComposedKey.ActivityMonitorTotal, name, value = 3500L)
        verify(preferences).get(LongComposedKey.ActivityMonitorTotal, name)
        verifyNoMoreInteractions(preferences)
    }

    @Test
    fun pauseWithoutResumeIsIgnored() {
        whenever(dateUtil.now()).thenReturn(4000L)
        sut.onActivityPaused(activity)
        verifyNoInteractions(preferences)
    }

    @Test
    fun firstPauseSetsStart() {
        whenever(preferences.get(LongComposedKey.ActivityMonitorTotal, name)).thenReturn(0L)
        whenever(dateUtil.now()).thenReturn(1000L)
        sut.onActivityResumed(activity)
        whenever(dateUtil.now()).thenReturn(2000L)
        sut.onActivityPaused(activity)
        verify(preferences).put(LongComposedKey.ActivityMonitorStart, name, value = 2000L)
        verify(preferences).put(LongComposedKey.ActivityMonitorTotal, name, value = 1000L)
    }
}
