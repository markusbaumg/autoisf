package app.aaps.implementation.db

/**
 * The entry of this list that is running at [time], picked the way the "active at" database queries
 * pick it (`getTemporaryBasalActiveAt`, `getExtendedBolusActiveAt`): it has started at or before [time],
 * has not ended yet (`timestamp + duration > time`), and of several such entries the one that started
 * last wins (`ORDER BY timestamp DESC LIMIT 1`).
 *
 * For code that walks a range step by step: read the entries of the range from the database once, then
 * ask this for every step, instead of one query per step. The list must be sorted by timestamp, oldest
 * first, as the "starting from time" queries return it with `ascending = true`.
 */
internal inline fun <T> List<T>.latestRunningAt(time: Long, timestamp: (T) -> Long, duration: (T) -> Long): T? =
    lastOrNull { timestamp(it) <= time && timestamp(it) + duration(it) > time }
