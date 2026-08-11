package lt.oranges.orangchat.util

import android.content.Context
import android.content.res.Resources
import java.util.concurrent.ConcurrentHashMap

object AppStrings {

    private val resourceNames = ConcurrentHashMap<Int, String>()

    fun get(context: Context, resId: Int): String {
        val catalog = RemoteI18n.catalogFor(context) ?: return context.getString(resId)
        val name = nameOf(context.resources, resId) ?: return context.getString(resId)
        return catalog[name] ?: context.getString(resId)
    }

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
