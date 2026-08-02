package lt.oranges.orangchat.feature.chat

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * On-disk cache for streamed video and audio.
 *
 * A stock `ExoPlayer` keeps nothing: scrolling back to a clip, or just replaying
 * one, pulled the whole file down again. On a phone that is the user's data plan
 * paying for the same video twice, and a spinner where there should have been an
 * instant start.
 *
 * Only the clips that stream are affected. A sealed attachment is decrypted to
 * `opened-<id>` in the same cache directory and played from there, so it was
 * never re-fetched to begin with (AttachmentDownload.kt).
 */
@UnstableApi
object MediaCache {

    /**
     * Deliberately in `cacheDir`. These are copies of bytes the server still
     * has, so if Android is short on space it should be free to delete them
     * without asking - the alternative is an app that grows without limit and
     * gets uninstalled for it.
     */
    private const val MAX_BYTES = 512L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    /**
     * `SimpleCache` locks its directory: a second instance over the same folder
     * throws, and ExoPlayer is built from more than one place. Hence the
     * process-wide singleton rather than one per player.
     */
    private fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "media"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }

    /**
     * Reads from disk where it can and fills the cache as it streams.
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` is what keeps a corrupt or truncated entry
     * from becoming a video that can never play again: on a read failure it
     * falls through to the network instead of failing the load.
     *
     * Writing is left on for range requests too. Seeking produces exactly the
     * partial spans this cache is built to hold, and dropping them would mean a
     * scrubbed-through video caches nothing at all.
     */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        return CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
