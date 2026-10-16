package app.aaps.wear.comm

import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.EventData.LoopStatesList.AvailableLoopState
import app.aaps.core.interfaces.rx.weardata.EventData.LoopStatesList.AvailableLoopState.LoopState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DataHandlerWearLoopStatesTest {

    private val states = arrayListOf(
        AvailableLoopState(LoopState.LOOP_CLOSED),
        AvailableLoopState(LoopState.PUMP_DISCONNECT, listOf(15, 30, 60)),
    )

    @Test
    fun `same states with a new token are not a change`() {
        val stored = EventData.LoopStatesList(1000L, states).serialize()
        assertThat(DataHandlerWear.loopStatesChanged(stored, EventData.LoopStatesList(2000L, states))).isFalse()
    }

    @Test
    fun `different states are a change`() {
        val stored = EventData.LoopStatesList(1000L, states).serialize()
        val other = arrayListOf(AvailableLoopState(LoopState.LOOP_OPEN))
        assertThat(DataHandlerWear.loopStatesChanged(stored, EventData.LoopStatesList(2000L, other))).isTrue()
    }

    @Test
    fun `nothing stored or unreadable stored value is a change`() {
        val received = EventData.LoopStatesList(2000L, states)
        assertThat(DataHandlerWear.loopStatesChanged("", received)).isTrue()
        assertThat(DataHandlerWear.loopStatesChanged("not json", received)).isTrue()
        assertThat(DataHandlerWear.loopStatesChanged(EventData.ActionPing(0L).serialize(), received)).isTrue()
    }
}
