package lt.oranges.orangchat

import android.content.Context
import androidx.activity.ComponentActivity
import lt.oranges.orangchat.util.LocalePreferences

/** Common entry point so every activity receives the same locale override. */
abstract class LocalizedActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalePreferences.localizedContext(newBase))
    }
}
