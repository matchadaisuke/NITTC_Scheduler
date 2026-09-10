package jp.linkserver.nittcsc.sync

import android.content.Context

/** Device-local preference because Nearby permissions and standby state are device-specific. */
object NearbySyncPreferences {
    private const val PREFERENCES = "nearby_sync_preferences"
    private const val KEY_SUPPRESS_AUTOMATIC_PROMPTS = "suppress_automatic_prompts"

    const val DEFAULT_SUPPRESS_AUTOMATIC_PROMPTS = true

    fun suppressAutomaticPrompts(context: Context): Boolean =
        preferences(context).getBoolean(
            KEY_SUPPRESS_AUTOMATIC_PROMPTS,
            DEFAULT_SUPPRESS_AUTOMATIC_PROMPTS
        )

    fun setSuppressAutomaticPrompts(context: Context, suppress: Boolean): Boolean =
        preferences(context).edit()
            .putBoolean(KEY_SUPPRESS_AUTOMATIC_PROMPTS, suppress)
            .commit()

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
