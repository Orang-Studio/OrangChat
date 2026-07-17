package lt.oranges.orangchat.feature.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.oranges.orangchat.data.repository.AuthRepository
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/** Which image slot an upload is filling. */
enum class ImageKind(val wire: String) { AVATAR("avatar"), BANNER("banner") }

data class ProfileEditState(
    val saving: Boolean = false,
    val uploading: ImageKind? = null,
    val error: String? = null,
)

/**
 * Profile editing: bio, pronouns, accent colour, and avatar/banner uploads.
 * Mirrors the web client's UserSettingsDialog Profile tab. The upload endpoint
 * re-encodes and resizes server-side, so we send the raw bytes as picked.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileEditState())
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()

    fun save(
        username: String? = null,
        displayName: String? = null,
        bio: String? = null,
        pronouns: String? = null,
        accentColor: Int? = null,
        profileCss: String? = null,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null)
            runCatching {
                authRepository.updateProfile(
                    username = username,
                    displayName = displayName,
                    bio = bio,
                    pronouns = pronouns,
                    accentColor = accentColor,
                    profileCss = profileCss,
                )
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "Could not save your profile")
            }
            _state.value = _state.value.copy(saving = false)
        }
    }

    /** Upload a picked image and point the matching profile field at it. */
    fun uploadImage(uri: Uri, kind: ImageKind) {
        viewModelScope.launch {
            _state.value = _state.value.copy(uploading = kind, error = null)
            runCatching {
                val part = withContext(Dispatchers.IO) { buildPart(uri) }
                val uploaded = authRepository.uploadImage(part, kind.wire)
                when (kind) {
                    ImageKind.AVATAR -> authRepository.updateProfile(avatarUrl = uploaded.url)
                    ImageKind.BANNER -> authRepository.updateProfile(bannerUrl = uploaded.url)
                }
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "Upload failed")
            }
            _state.value = _state.value.copy(uploading = null)
        }
    }

    fun removeImage(kind: ImageKind) {
        viewModelScope.launch {
            runCatching {
                // "" clears the field; null would mean "leave unchanged".
                when (kind) {
                    ImageKind.AVATAR -> authRepository.updateProfile(avatarUrl = "")
                    ImageKind.BANNER -> authRepository.updateProfile(bannerUrl = "")
                }
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "Could not remove the image")
            }
        }
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    /** Read the picked content into a multipart part. */
    private fun buildPart(uri: Uri): MultipartBody.Part {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not read that image")
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
        // The server derives the real format from the bytes; the name is cosmetic.
        return MultipartBody.Part.createFormData("file", "upload", body)
    }
}
