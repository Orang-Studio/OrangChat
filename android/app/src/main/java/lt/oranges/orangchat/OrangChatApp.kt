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
import lt.oranges.orangchat.util.RemoteI18n

@HiltAndroidApp
class OrangChatApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        RemoteI18n.init(applicationContext)
        RemoteI18n.start()
        AppForegroundState.register()
    }

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
