package lt.oranges.orangchat.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.feature.voice.RingtonePlayer
import javax.inject.Inject

/**
 * The custom call ringtone. Entirely device-local: the picked file is never
 * uploaded and never leaves the phone - only its content:// URI is kept, in the
 * app's encrypted prefs, so the server knows nothing about it.
 */
@HiltViewModel
class RingtoneViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: TokenStore,
    private val ringtonePlayer: RingtonePlayer,
) : ViewModel() {

    private val _ringtoneName = MutableStateFlow(tokenStore.ringtoneName)
    /** Display name of the chosen tone, or null when using the device default. */
    val ringtoneName: StateFlow<String?> = _ringtoneName.asStateFlow()

    fun setRingtone(uri: Uri) {
        // The picker grants read access for this process only; persist it or the
        // tone stops working after a reboot.
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess

        val name = displayName(uri) ?: uri.lastPathSegment ?: "Custom ringtone"
        tokenStore.ringtoneUri = uri.toString()
        tokenStore.ringtoneName = if (persisted) name else "$name (may not survive a restart)"
        _ringtoneName.value = tokenStore.ringtoneName
    }

    fun useDefaultRingtone() {
        tokenStore.ringtoneUri?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        tokenStore.ringtoneUri = null
        tokenStore.ringtoneName = null
        _ringtoneName.value = null
    }

    fun previewRingtone() = ringtonePlayer.preview()
    fun stopPreview() = ringtonePlayer.stop()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}
