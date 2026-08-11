package lt.oranges.orangchat.feature.profile

import lt.oranges.orangchat.R
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
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.buildImagePart
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
                _state.value = _state.value.copy(error = it.message ?: AppStrings.get(context, R.string.catalog_could_not_save_your_profile_e13d9374))
            }
            _state.value = _state.value.copy(saving = false)
        }
    }

    /** Upload a picked image and point the matching profile field at it. */
    fun uploadImage(uri: Uri, kind: ImageKind) {
        viewModelScope.launch {
            _state.value = _state.value.copy(uploading = kind, error = null)
            runCatching {
                val part = withContext(Dispatchers.IO) { buildImagePart(context, uri) }
                val uploaded = authRepository.uploadImage(part, kind.wire)
                when (kind) {
                    ImageKind.AVATAR -> authRepository.updateProfile(avatarUrl = uploaded.url)
                    ImageKind.BANNER -> authRepository.updateProfile(bannerUrl = uploaded.url)
                }
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: AppStrings.get(context, R.string.catalog_upload_failed_ad0d0603))
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
                _state.value = _state.value.copy(error = it.message ?: AppStrings.get(context, R.string.catalog_could_not_remove_the_image_a332686e))
            }
        }
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

}
