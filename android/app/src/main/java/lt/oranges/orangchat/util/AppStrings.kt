package lt.oranges.orangchat.util

import android.content.Context
import android.content.res.Resources
import java.util.concurrent.ConcurrentHashMap

/**
 * The single string-lookup entry point.
 *
 * Every `context.getString(R.string.x)` call in the app routes through
 * [get]: it first asks the server-fetched catalogue for the current language
 * (see [RemoteI18n]) and falls back to the bundled resource when there is no
 * override. That fallback is what makes the whole mechanism optional - a
 * device that never reaches the server behaves exactly as before, and a
 * server-added language (no `values-xx/` compiled into the APK) still gets
 * its strings with the English resource as the last resort.
 *
 * The id -> resource-name map is cached once per id; resource names are
 * compile-time constants, so the lookup is idempotent for the life of the
 * process.
 */
object AppStrings {

    private val resourceNames = ConcurrentHashMap<Int, String>()

    /** The string for [resId], with a server-served override when one exists. */
    fun get(context: Context, resId: Int): String {
        val catalog = RemoteI18n.catalogFor(context) ?: return context.getString(resId)
        val name = nameOf(context.resources, resId) ?: return context.getString(resId)
        return catalog[name] ?: context.getString(resId)
    }

    /**
     * Like [get], then formatted with [args] (positional `%1$s` etc.). If the
     * resolved string turns out to carry no format specifiers - e.g. a
     * community translation dropped them - the raw string is returned rather
     * than letting [String.format] throw.
     */
    fun get(context: Context, resId: Int, vararg args: Any): String {
        val template = get(context, resId)
        if (args.isEmpty()) return template
        return try {
            String.format(template, *args)
        } catch (_: IllegalArgumentException) {
            template
        }
    }

    private fun nameOf(resources: Resources, resId: Int): String? {
        resourceNames[resId]?.let { return it }
        val name = runCatching { resources.getResourceEntryName(resId) }.getOrNull() ?: return null
        resourceNames[resId] = name
        return name
    }
}
