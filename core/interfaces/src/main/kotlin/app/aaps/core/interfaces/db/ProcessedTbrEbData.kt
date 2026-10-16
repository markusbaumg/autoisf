package app.aaps.core.interfaces.db

import app.aaps.core.data.model.TB

interface ProcessedTbrEbData {

    /**
     * Get running temporary basal at time
     *
     *  @return     running temporary basal or null if no tbr is running
     *              If pump is faking extended boluses as temporary basals
     *              return extended converted to temporary basal with type == FAKE_EXTENDED
     */
    fun getTempBasalIncludingConvertedExtended(timestamp: Long): TB?

    /**
     * Get running temporary basals for given time range, sliced by calculationStep.
     * For each step between given range it calculates equivalent of getTempBasalIncludingConvertedExtended
     *
     *  @param startTime start of calculated period, timestamp
     *  @param endTime end of calculated period, timestamp
     *  @param calculationStep calculation step, in millisecond
     *  @return map where for each step, its timestamp is a key and calculated optional temporary basal is a value
     */
    fun getTempBasalIncludingConvertedExtendedForRange(startTime: Long, endTime: Long, calculationStep: Long): Map<Long, TB?>

    /**
     * [getTempBasalIncludingConvertedExtended] for many times between [from] and [to], from one read
     * of the database. For code that walks a range step by step, like the overview basal graph or the
     * TDD calculation: one "active at" query per step was the cost, and for a step that no temporary
     * basal covers that query walks the whole timestamp index.
     *
     * Asking [TempBasalsInRange.at] for a time outside [from]..[to] is not supported.
     */
    fun getTempBasalsIncludingConvertedExtended(from: Long, to: Long): TempBasalsInRange

    /** The temporary basals of a range, see [getTempBasalsIncludingConvertedExtended]. */
    interface TempBasalsInRange {

        /** The same answer as [getTempBasalIncludingConvertedExtended] for [timestamp]. */
        fun at(timestamp: Long): TB?
    }
}
