package app.aaps.wear.watchfaces.common

import app.aaps.core.interfaces.rx.weardata.EventData

/**
 * Loop time for the Halo, Glass and Orbit loop pill / ball age.
 *
 * The phone sends the real loop run time in [EventData.Status.lastLoopRunAt]. When it is 0 (older phone app, or no
 * loop run since the phone app started) the faces fall back to [EventData.Status.openApsStatus], the last temp basal
 * enact (-1 when there is none), exactly as before.
 */
object LoopRunTime {

    fun of(lastLoopRunAt: Long, openApsStatus: Long): Long = if (lastLoopRunAt > 0L) lastLoopRunAt else openApsStatus

    fun of(status: EventData.Status): Long = of(status.lastLoopRunAt, status.openApsStatus)
}
