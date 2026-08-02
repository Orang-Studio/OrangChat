package lt.oranges.orangchat

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import lt.oranges.orangchat.util.AppForegroundState

/** Hilt application root. Also configures Coil for animated-GIF avatars and for
 *  decoding a video's own first frame as its poster. */
@HiltAndroidApp
class OrangChatApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Track process foreground/background so notifications only fire when the
        // app (or the focused chat) isn't visible.
        AppForegroundState.register()
    }

    /**
     * Coil's own defaults size the disk cache at 2% of the volume, which on a
     * nearly-full phone is a handful of megabytes - enough that avatars fall out
     * of it between one screen and the next and get fetched again. Stating both
     * caches here fixes that at something a chat client can actually work with,
     * and makes the number visible instead of implied.
     *
     * `cacheDir`, so Android may reclaim the lot when space runs short; every
     * byte in it can be fetched again.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
