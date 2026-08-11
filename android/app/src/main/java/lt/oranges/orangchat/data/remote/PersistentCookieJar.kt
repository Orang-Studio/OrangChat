package lt.oranges.orangchat.data.remote

import lt.oranges.orangchat.data.local.TokenStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersistentCookieJar @Inject constructor(
    private val tokenStore: TokenStore,
) : CookieJar {

    private val prefs get() = tokenStore.cookiePrefs
    private val cache = LinkedHashMap<String, Cookie>()

    init {
        load()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        var changed = false
        for (cookie in cookies) {
            if (cookie.expiresAt < System.currentTimeMillis()) {
                cache.remove(cookie.name)
            } else {
                cache[cookie.name] = cookie
            }
            changed = true
        }
        if (changed) persist(url)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val valid = ArrayList<Cookie>()
        val it = cache.values.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (c.expiresAt < now) it.remove()
            else if (c.matches(url)) valid.add(c)
        }
        return valid
    }

    @Synchronized
    private fun persist(url: HttpUrl) {
        val serialized = cache.values.map { it.toString() }.toSet()
        prefs.edit().putStringSet(TokenStore.KEY_COOKIES, serialized).apply()
        lastUrl = url
    }

    @Synchronized
    private fun load() {
        val url = lastUrl ?: DEFAULT_URL
        prefs.getStringSet(TokenStore.KEY_COOKIES, emptySet())?.forEach { raw ->
            Cookie.parse(url, raw)?.let { cache[it.name] = it }
        }
    }

    companion object {
        private var lastUrl: HttpUrl? = null
        private val DEFAULT_URL: HttpUrl = HttpUrl.Builder()
            .scheme("https").host("chat.oranges.lt").build()
    }
}
