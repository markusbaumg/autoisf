package app.aaps.wear.complications

import android.content.Context

/**
 * Which of our complication data sources currently feed a complication on a watch face.
 *
 * DataHandlerWear pushes fresh BG/status to the complications with
 * ComplicationDataSourceUpdateRequester.requestUpdateAll(). Each call is a broadcast that
 * WearServices has to wake up for, even when the data source is not used on the current face.
 * Requesting all 16 sources on every BG and every status change (1-min Libre readings: 2 changes
 * per minute) produced 32 update intents per minute while the native faces (Halo, Glass, Orbit),
 * which read the data store directly and bind no complication, were active.
 *
 * The data sources record their bound instances here (activated/deactivated callbacks, plus every
 * update request so complications bound before this registry existed are picked up within one
 * UPDATE_PERIOD_SECONDS), and only sources with at least one bound instance are asked to update.
 */
object ActiveComplications {

    private const val PREFS = "wear_active_complications"
    private const val KEY_INSTANCES = "instances"
    private const val SEPARATOR = '#'

    fun key(provider: String, instanceId: Int): String = "$provider$SEPARATOR$instanceId"

    /** Keys after [provider]/[instanceId] became active. */
    fun withActivated(keys: Set<String>, provider: String, instanceId: Int): Set<String> = keys + key(provider, instanceId)

    /** Keys after [provider]/[instanceId] was removed from its face. */
    fun withDeactivated(keys: Set<String>, provider: String, instanceId: Int): Set<String> = keys - key(provider, instanceId)

    /** Providers (fully qualified class names) with at least one bound instance. */
    fun providersOf(keys: Set<String>): Set<String> = keys.mapTo(HashSet()) { it.substringBeforeLast(SEPARATOR) }

    /** The data source classes out of [candidates] that have a complication bound right now. */
    fun <T> boundOnly(candidates: List<Class<out T>>, keys: Set<String>): List<Class<out T>> {
        val providers = providersOf(keys)
        return candidates.filter { it.name in providers }
    }

    fun activated(context: Context, provider: String, instanceId: Int) = edit(context) { withActivated(it, provider, instanceId) }

    fun deactivated(context: Context, provider: String, instanceId: Int) = edit(context) { withDeactivated(it, provider, instanceId) }

    @Synchronized
    fun keys(context: Context): Set<String> =
        HashSet(prefs(context).getStringSet(KEY_INSTANCES, emptySet()) ?: emptySet())

    @Synchronized
    private fun edit(context: Context, change: (Set<String>) -> Set<String>) {
        val current = keys(context)
        val updated = change(current)
        if (updated != current) prefs(context).edit().putStringSet(KEY_INSTANCES, HashSet(updated)).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
