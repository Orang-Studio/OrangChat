package lt.oranges.orangchat

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
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

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
