package app.aaps.core.utils

/**
 * Answers "which entry is running at this time" for many times, from entries read from the database
 * once, the way the "active at" database queries pick it (`getTemporaryBasalActiveAt`,
 * `getExtendedBolusActiveAt`, `getTemporaryTargetActiveAt`): it has started at or before the time, has
 * not ended yet (`timestamp + duration > time`), and of several such entries the one that started last
 * wins (`ORDER BY timestamp DESC LIMIT 1`). Of entries that started at the same time the last one in
 * [entries] wins; sort ties by id so that is the highest id, as a descending walk of the timestamp index
 * returns it.
 *
 * For code that walks a range step by step: read every entry that runs at some time of the range once
 * ("active between" queries), then ask [at] for every step instead of one query per step.
 *
 * [at] gives exactly what `entries.lastOrNull { timestamp(it) <= time && timestamp(it) + duration(it) > time }`
 * gives, but costs a binary search plus a walk back over the entries that can still be running, instead
 * of a walk over the whole list: a long range (the cannula usage sums the whole cannula life) has many
 * steps and many entries.
 *
 * @param entries sorted by timestamp, oldest first
 */
class RunningAt<T>(private val entries: List<T>, timestamp: (T) -> Long, duration: (T) -> Long) {

    private val starts = LongArray(entries.size) { timestamp(entries[it]) }
    private val ends = LongArray(entries.size) { starts[it] + duration(entries[it]) }

    /** The latest end of the entries 0..i: walking back from i, nothing earlier runs at or after it. */
    private val latestEndUpTo = LongArray(entries.size)

    init {
        for (i in entries.indices) {
            require(i == 0 || starts[i - 1] <= starts[i]) { "entries must be sorted by timestamp" }
            latestEndUpTo[i] = if (i == 0) ends[i] else maxOf(latestEndUpTo[i - 1], ends[i])
        }
    }

    fun at(time: Long): T? {
        // The first entry that starts after time. Every later one starts after it too.
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= time) low = mid + 1 else high = mid
        }
        // Back from the last entry started at or before time, while one of them can still be running
        var i = low - 1
        while (i >= 0 && latestEndUpTo[i] > time) {
            if (ends[i] > time) return entries[i]
            i--
        }
        return null
    }
}
