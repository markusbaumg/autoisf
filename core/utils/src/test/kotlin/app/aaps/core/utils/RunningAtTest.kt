package app.aaps.core.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class RunningAtTest {

    private data class Entry(val id: Long, val timestamp: Long, val duration: Long)

    private fun List<Entry>.index() = RunningAt(this, { it.timestamp }, { it.duration })

    /** What the "active at" query picks: the last started of the running ones (the list is sorted). */
    private fun List<Entry>.reference(time: Long) = lastOrNull { it.timestamp <= time && it.timestamp + it.duration > time }

    @Test
    fun `start is inclusive and end is exclusive`() {
        val index = listOf(Entry(1, 100, 50)).index()
        assertThat(index.at(99)).isNull()
        assertThat(index.at(100)?.id).isEqualTo(1)
        assertThat(index.at(149)?.id).isEqualTo(1)
        assertThat(index.at(150)).isNull()
    }

    @Test
    fun `of two running entries the later started one wins, and the older one again once the later has ended`() {
        val index = listOf(Entry(1, 0, 1000), Entry(2, 100, 50)).index()
        assertThat(index.at(50)?.id).isEqualTo(1)
        assertThat(index.at(120)?.id).isEqualTo(2)
        assertThat(index.at(200)?.id).isEqualTo(1)
    }

    @Test
    fun `of two started at the same time the last in the list wins`() {
        val index = listOf(Entry(1, 100, 1000), Entry(2, 100, 50)).index()
        assertThat(index.at(120)?.id).isEqualTo(2)
        assertThat(index.at(200)?.id).isEqualTo(1)
    }

    @Test
    fun `a zero duration entry never runs`() {
        val index = listOf(Entry(1, 100, 0)).index()
        assertThat(index.at(100)).isNull()
    }

    @Test
    fun `empty list gives null`() {
        assertThat(emptyList<Entry>().index().at(0)).isNull()
    }

    @Test
    fun `an unsorted list is refused`() {
        assertThrows<IllegalArgumentException> { listOf(Entry(1, 100, 10), Entry(2, 50, 10)).index() }
    }

    /** Random overlapping, nested, tied, zero duration entries: always the same as the plain scan. */
    @Test
    fun `same answer as the plain scan for random entries`() {
        val random = Random(42)
        repeat(200) {
            val list = List(random.nextInt(0, 30)) { i ->
                Entry(i.toLong(), random.nextLong(0, 500) / 10 * 10, random.nextLong(0, 300) / 10 * 10)
            }.sortedWith(compareBy({ it.timestamp }, { it.id }))
            val index = list.index()
            for (time in -10L..900L) assertThat(index.at(time)).isEqualTo(list.reference(time))
        }
    }
}
