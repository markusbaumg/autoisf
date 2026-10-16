package app.aaps.implementation.db

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RunningAtTest {

    private data class Entry(val timestamp: Long, val duration: Long)

    private fun List<Entry>.at(time: Long) = latestRunningAt(time, { it.timestamp }, { it.duration })

    @Test
    fun `start is inclusive and end is exclusive`() {
        val list = listOf(Entry(100, 50))
        assertThat(list.at(99)).isNull()
        assertThat(list.at(100)).isEqualTo(list[0])
        assertThat(list.at(149)).isEqualTo(list[0])
        assertThat(list.at(150)).isNull()
    }

    @Test
    fun `of two running entries the later started one wins`() {
        val list = listOf(Entry(0, 1000), Entry(100, 50))
        assertThat(list.at(120)).isEqualTo(list[1])
        assertThat(list.at(200)).isEqualTo(list[0])
    }

    @Test
    fun `empty list gives null`() {
        assertThat(emptyList<Entry>().at(0)).isNull()
    }
}
