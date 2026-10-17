package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmDetails
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmWarning
import kotlin.math.roundToInt

/**
 * Builds the display-only [ConfirmDetails] of a watch confirm screen from plain values the phone already has.
 * Pure Kotlin, unit tested.
 *
 * DISPLAY ONLY: nothing here is delivered, checked or sent back. The treatment the watch confirms is the
 * ConfirmAction's returnCommand alone, and the phone delivers only what it stored in PendingWearConfirm. The warnings
 * never block: the watch shows them and its confirm works exactly as without them. Effect and timing are rough
 * estimates (labelled "≈" on the watch): BG change = insulin × ISF, carbs covered = insulin × IC, no loop forecast.
 * ISF and IC are the profile's (the same the phone's bolus wizard uses), not AutoISF's adjusted ISF of the last loop
 * run; the watch names them as the source of the estimate.
 */
internal object WearConfirmDetails {

    /** A manual bolus less than this many minutes before is warned about. */
    const val RECENT_BOLUS_MIN = 15

    /** The last BG reading counts as stale from this age (minutes), as on the watch faces (FaceState.STALE_MIN). */
    const val STALE_MIN = 12

    /** Delta per 5 min (mg/dL) from which BG counts as falling, and as falling fast (FaceState.FAST_DELTA_MGDL). */
    const val FALLING_MGDL = -5.0
    const val FALLING_FAST_MGDL = -10.0

    /** A dose of at least this fraction of the max bolus is warned about. */
    const val NEAR_MAX_FRACTION = 0.8

    /** The last bolus is shown when it is younger than this (minutes). */
    const val LAST_BOLUS_SHOWN_MIN = 12 * 60

    /** Tolerance when comparing insulin amounts (U). */
    private const val EPS = 1e-6

    data class Inputs(
        val kind: String,
        /** Insulin and carbs as offered (after constraints). */
        val insulin: Double,
        val carbs: Int,
        /** Insulin and carbs as requested (before constraints); null when not applicable. */
        val requestedInsulin: Double? = null,
        val requestedCarbs: Int? = null,
        val label: String = "",
        val note: String = "",
        val unitsMgdl: Boolean = true,
        val now: Long,
        val bgMgdl: Double? = null,
        val bgTimestamp: Long? = null,
        /** BG delta per 5 min (mg/dL). */
        val deltaMgdl: Double? = null,
        val arrow: String = "",
        val lowMarkMgdl: Double? = null,
        val isfMgdl: Double? = null,
        val ic: Double? = null,
        val iobNow: Double? = null,
        val cobNow: Double? = null,
        /** Pump delivery speed (s per U), null when unknown for the pump. */
        val secondsPerUnit: Double? = null,
        val insulinPeakMin: Int? = null,
        val lastBolusAt: Long? = null,
        val lastBolusAmount: Double? = null,
        val maxBolus: Double? = null,
        val carbsStart: Long? = null,
        val carbsDurationH: Int? = null
    )

    fun build(i: Inputs): ConfirmDetails {
        val fill = i.kind == ConfirmDetails.KIND_FILL
        // insulin that goes into the body (priming / filling does not)
        val dosing = !fill && i.insulin > EPS
        val isf = i.isfMgdl?.takeIf { it.isFinite() && it > 0.0 }
        val ic = i.ic?.takeIf { it.isFinite() && it > 0.0 }
        val positiveCarbs = !fill && i.carbs > 0

        val bgEffect = when {
            dosing && isf != null                     -> -i.insulin * isf
            positiveCarbs && isf != null && ic != null -> i.carbs * isf / ic
            else                                      -> null
        }
        val carbsCovered = if (dosing && ic != null) i.insulin * ic else null
        // no "insulin these carbs need" for carbs alone: carbs from the watch are often the treatment of a low, and
        // such a number would read like advice to bolus
        val iobNow = i.iobNow?.takeIf { it.isFinite() }
        val iobAfter = if (dosing && iobNow != null) iobNow + i.insulin else null
        val cobNow = i.cobNow?.takeIf { it.isFinite() && it >= 0.0 }
        val immediateCarbs = !fill && i.kind != ConfirmDetails.KIND_ECARBS && i.carbs != 0
        val cobAfter = if (immediateCarbs && cobNow != null) (cobNow + i.carbs).coerceAtLeast(0.0) else null

        val deliverySeconds = i.secondsPerUnit?.takeIf { it > 0.0 && i.insulin > EPS }?.let { (i.insulin * it).roundToInt().coerceAtLeast(1) }

        val bgAgeMin = i.bgTimestamp?.let { ((i.now - it) / 60_000L).coerceAtLeast(0L).toInt() }
        val lastBolusMinAgo = i.lastBolusAt?.let { ((i.now - it) / 60_000L).toInt() }?.takeIf { it in 0 until LAST_BOLUS_SHOWN_MIN }

        val warnings = ArrayList<ConfirmWarning>()
        if (dosing) {
            val bg = i.bgMgdl?.takeIf { it > 0.0 && bgAgeMin != null }
            val stale = bgAgeMin != null && bgAgeMin >= STALE_MIN
            if (bg == null) warnings += ConfirmWarning(ConfirmWarning.W_NO_BG, ConfirmWarning.LEVEL_CAUTION)
            else {
                val low = i.lowMarkMgdl?.let { bg < it } == true
                if (low) warnings += ConfirmWarning(ConfirmWarning.W_BG_LOW, ConfirmWarning.LEVEL_DANGER, bg)
                val delta = i.deltaMgdl?.takeIf { it.isFinite() && !stale }
                if (delta != null && delta <= FALLING_MGDL) {
                    val level = if (low || delta <= FALLING_FAST_MGDL) ConfirmWarning.LEVEL_DANGER else ConfirmWarning.LEVEL_CAUTION
                    warnings += ConfirmWarning(ConfirmWarning.W_BG_FALLING, level, delta)
                }
                if (stale) warnings += ConfirmWarning(ConfirmWarning.W_BG_STALE, ConfirmWarning.LEVEL_CAUTION, bgAgeMin.toDouble())
            }
            if (lastBolusMinAgo != null && lastBolusMinAgo < RECENT_BOLUS_MIN)
                warnings += ConfirmWarning(ConfirmWarning.W_RECENT_BOLUS, ConfirmWarning.LEVEL_CAUTION, lastBolusMinAgo.toDouble(), i.lastBolusAmount)
        }
        val max = i.maxBolus?.takeIf { it > 0.0 }
        if (i.insulin > EPS && max != null && i.insulin >= max * NEAR_MAX_FRACTION - EPS)
            warnings += ConfirmWarning(ConfirmWarning.W_NEAR_MAX_BOLUS, ConfirmWarning.LEVEL_CAUTION, max)
        val insulinChanged = i.requestedInsulin != null && kotlin.math.abs(i.requestedInsulin - i.insulin) > EPS
        val carbsChanged = i.requestedCarbs != null && i.requestedCarbs != i.carbs
        if (insulinChanged || carbsChanged)
            warnings += ConfirmWarning(
                ConfirmWarning.W_CONSTRAINT, ConfirmWarning.LEVEL_CAUTION,
                if (insulinChanged) i.requestedInsulin else null, if (carbsChanged) i.requestedCarbs?.toDouble() else null
            )
        // danger first, then in the order added
        warnings.sortByDescending { it.level }

        return ConfirmDetails(
            kind = i.kind,
            insulin = i.insulin,
            carbs = i.carbs,
            label = i.label,
            unitsMgdl = i.unitsMgdl,
            bgMgdl = i.bgMgdl,
            bgAgeMin = bgAgeMin,
            deltaMgdl = i.deltaMgdl,
            arrow = i.arrow,
            bgEffectMgdl = bgEffect,
            carbsCovered = carbsCovered,
            // the profile values the estimates come from, shown as their source
            isfMgdl = if (bgEffect != null) isf else null,
            ic = if (carbsCovered != null || (bgEffect != null && !dosing)) ic else null,
            iobNow = if (dosing) iobNow else null,
            iobAfter = iobAfter,
            cobNow = if (cobAfter != null) cobNow else null,
            cobAfter = cobAfter,
            deliverySeconds = deliverySeconds,
            insulinPeakMin = if (dosing) i.insulinPeakMin?.takeIf { it > 0 } else null,
            lastBolusMinAgo = if (fill) null else lastBolusMinAgo,
            lastBolusAmount = if (fill || lastBolusMinAgo == null) null else i.lastBolusAmount,
            carbsStart = i.carbsStart,
            carbsDurationH = i.carbsDurationH,
            note = i.note,
            warnings = warnings
        )
    }
}

/**
 * Approximate bolus delivery speed of [pumpType] in seconds per unit, display only (≈ duration on the watch confirm
 * screen). Omnipod Eros / DASH: one 0.05 U pulse every 2 s (OmnipodDashPumpPlugin.estimateBolusDeliverySeconds);
 * Medtronic: 1.5 U/min. Null for pumps whose speed is configurable or unknown here (nothing is shown then).
 */
internal fun wearBolusSecondsPerUnit(pumpType: PumpType?): Double? = when (pumpType) {
    PumpType.OMNIPOD_EROS, PumpType.OMNIPOD_DASH -> 40.0
    null                                         -> null
    else                                         -> if (pumpType.name.startsWith("MEDTRONIC")) 40.0 else null
}
