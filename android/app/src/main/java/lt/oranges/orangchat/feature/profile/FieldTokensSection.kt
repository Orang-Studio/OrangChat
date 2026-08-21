package lt.oranges.orangchat.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.R
import lt.oranges.orangchat.data.model.ProfileFieldTokenInfo
import lt.oranges.orangchat.data.repository.AuthRepository
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.ConfirmDialog
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.BACKEND_ORIGIN
import lt.oranges.orangchat.util.formatFullTime
import javax.inject.Inject

data class FieldTokensState(
    val tokens: List<ProfileFieldTokenInfo> = emptyList(),
    val minted: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class FieldTokensViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FieldTokensState())
    val state: StateFlow<FieldTokensState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            runCatching { authRepository.fieldTokens() }
                .onSuccess { _state.value = _state.value.copy(tokens = it) }
        }
    }

    fun mint(label: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { authRepository.mintFieldToken(label) }
                .onSuccess { _state.value = _state.value.copy(minted = it.token) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
            _state.value = _state.value.copy(busy = false)
            refresh()
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { authRepository.revokeFieldToken(id) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
            _state.value = _state.value.copy(busy = false)
            refresh()
        }
    }

    fun dismissMinted() { _state.value = _state.value.copy(minted = null) }
}

/**
 * Mint and revoke the tokens a user's own service uses to push `{field.…}`
 * values. The secret is shown once, at mint: only its hash is stored.
 */
@Composable
fun FieldTokensSection(
    fields: Map<String, String>,
    modifier: Modifier = Modifier,
    vm: FieldTokensViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val c = OrangTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf("") }
    var revoking by remember { mutableStateOf<ProfileFieldTokenInfo?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = AppStrings.get(context, R.string.field_tokens_intro),
            color = c.inkMuted,
            fontSize = 12.sp,
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = AppStrings.get(context, R.string.field_tokens_how_to_push),
                color = c.inkSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "POST $BACKEND_ORIGIN/api/profile/fields\n" +
                    "Authorization: Widget <token>\n" +
                    "Content-Type: application/json\n\n" +
                    """{"field": "status", "value": "shipping things"}""",
                color = c.inkSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface2, RoundedCornerShape(OrangRadius.lg))
                    .border(1.dp, c.border, RoundedCornerShape(OrangRadius.lg))
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
            Text(
                text = AppStrings.get(context, R.string.field_tokens_then_use),
                color = c.inkMuted,
                fontSize = 12.sp,
            )
        }

        if (fields.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = AppStrings.get(context, R.string.field_tokens_current_values),
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface2, RoundedCornerShape(OrangRadius.lg))
                        .border(1.dp, c.border, RoundedCornerShape(OrangRadius.lg))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    fields.forEach { (key, value) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "{field.$key}",
                                color = c.inkMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(text = value, color = c.ink, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        state.minted?.let { secret ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.primarySoft, RoundedCornerShape(OrangRadius.lg))
                    .border(1.dp, c.primary, RoundedCornerShape(OrangRadius.lg))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = AppStrings.get(context, R.string.field_tokens_copy_it_now),
                    color = c.ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = secret,
                    color = c.ink,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface2, RoundedCornerShape(OrangRadius.md))
                        .padding(8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrangButton(
                        onClick = { clipboard.setText(AnnotatedString(secret)) },
                        variant = ButtonVariant.Secondary,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(AppStrings.get(context, R.string.catalog_copy_af74f7c5))
                        }
                    }
                    OrangButton(
                        text = AppStrings.get(context, R.string.field_tokens_dismiss),
                        onClick = vm::dismissMinted,
                        variant = ButtonVariant.Ghost,
                    )
                }
            }
        }

        state.tokens.forEach { token ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
                    .border(1.dp, c.border, RoundedCornerShape(OrangRadius.lg))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = c.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = token.label,
                        color = c.ink,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "••••${token.hint} · " + (
                            token.lastUsedAt?.let {
                                AppStrings.get(context, R.string.field_tokens_last_used)
                                    .format(formatFullTime(it))
                            } ?: AppStrings.get(context, R.string.field_tokens_never_used)
                            ),
                        color = c.inkMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = AppStrings.get(context, R.string.field_tokens_revoke),
                    tint = c.inkMuted,
                    modifier = Modifier.size(18.dp).clickable { revoking = token },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OrangTextField(
                value = label,
                onValueChange = { label = it.take(60) },
                label = AppStrings.get(context, R.string.field_tokens_token_name),
                placeholder = AppStrings.get(context, R.string.field_tokens_token_name_placeholder),
                modifier = Modifier.weight(1f),
            )
            OrangButton(
                onClick = {
                    val name = label.trim().ifBlank {
                        AppStrings.get(context, R.string.field_tokens_untitled)
                    }
                    label = ""
                    vm.mint(name)
                },
                variant = ButtonVariant.Secondary,
                loading = state.busy,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(AppStrings.get(context, R.string.field_tokens_create))
                }
            }
        }

        state.error?.let { Text(it, color = c.danger, fontSize = 12.sp) }
    }

    revoking?.let { token ->
        ConfirmDialog(
            onDismiss = { revoking = null },
            onConfirm = {
                vm.revoke(token.id)
                revoking = null
            },
            title = AppStrings.get(context, R.string.field_tokens_revoke),
            message = AppStrings.get(context, R.string.field_tokens_revoke_description)
                .format(token.label),
            confirmText = AppStrings.get(context, R.string.field_tokens_revoke),
            destructive = true,
            loading = state.busy,
        )
    }
}
