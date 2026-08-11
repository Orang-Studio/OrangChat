package lt.oranges.orangchat.feature.updates

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.R
import lt.oranges.orangchat.util.AppStrings
import javax.inject.Inject

/** Where the About screen's update row is in its little lifecycle. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    /** Checked, and this build is the newest published one. */
    data object UpToDate : UpdateUiState
    data class Available(val manifest: UpdateManifest) : UpdateUiState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateUiState
    /** Downloaded and handed to the installer; the system takes it from here. */
    data class ReadyToInstall(val manifest: UpdateManifest) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateManager: UpdateManager,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** True once the user has allowed OrangChat to install APKs. */
    fun canInstall(): Boolean = updateManager.canInstall()

    fun installPermissionIntent() = updateManager.installPermissionIntent()

    fun check() {
        if (_state.value is UpdateUiState.Checking) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            _state.value = runCatching { updateManager.check() }
                .fold(
                    onSuccess = { manifest ->
                        if (manifest == null) UpdateUiState.UpToDate
                        else UpdateUiState.Available(manifest)
                    },
                    onFailure = { e ->
                        Log.w(TAG, "update check failed", e)
                        UpdateUiState.Failed(e.message ?: AppStrings.get(context, R.string.catalog_could_not_reach_the_update_server_58cd6971))
                    },
                )
        }
    }

    fun download(manifest: UpdateManifest) {
        if (_state.value is UpdateUiState.Downloading) return
        _state.value = UpdateUiState.Downloading(manifest, 0f)
        viewModelScope.launch {
            runCatching {
                updateManager.download(manifest) { progress ->
                    _state.value = UpdateUiState.Downloading(manifest, progress)
                }
            }.fold(
                onSuccess = { apk ->
                    _state.value = UpdateUiState.ReadyToInstall(manifest)
                    // Straight into the installer: having just watched a
                    // progress bar fill, a second "install?" tap is friction.
                    // The system still shows its own confirmation.
                    runCatching { updateManager.install(apk) }.onFailure { e ->
                        Log.w(TAG, "install failed", e)
                        _state.value = UpdateUiState.Failed(e.message ?: AppStrings.get(context, R.string.catalog_could_not_open_the_installer_a59d8717))
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "update download failed", e)
                    _state.value = UpdateUiState.Failed(e.message ?: AppStrings.get(context, R.string.catalog_download_failed_01125e1b))
                },
            )
        }
    }

    private companion object {
        const val TAG = "UpdateViewModel"
    }
}
