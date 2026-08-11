package lt.oranges.orangchat.feature.settings
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.text.KeyboardOptions
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.feature.e2ee.EncryptionExplainerDialog
import lt.oranges.orangchat.feature.e2ee.HowEncryptionWorksLink
import lt.oranges.orangchat.feature.transfer.TransferQrScanner

/**
 * What this phone can say about its own encryption (docs/E2EE.md §6.6).
 *
 * Deliberately quiet: the default is secure, so this is a place to look rather
 * than a chore to complete. The loud states - an identity that changed, a device
 * nobody authorised - are surfaced where they happen, not here.
 */
@Composable
fun EncryptionScreen(onBack: () -> Unit, vm: EncryptionViewModel = hiltViewModel()) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    var revokeTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var explainerOpen by remember { mutableStateOf(false) }
    var eraseConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(c.surface1)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_encryption_0af149c2), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection(AppStrings.get(context, R.string.catalog_this_device_fa5a6dd9)) {
                Text(
                    when {
                        state.revokedHere ->
                            AppStrings.get(context, R.string.catalog_this_phone_was_removed_from_your_account_a066cfb1)
                        state.deviceId != null ->
                            AppStrings.get(context, R.string.catalog_your_direct_messages_are_locked_on_this_0bc36ae0)
                        else -> AppStrings.get(context, R.string.catalog_this_phone_has_not_made_its_encryption_0af536c0)
                    },
                    color = if (state.revokedHere) c.danger else c.inkSecondary,
                    fontSize = 13.sp,
                )
                HowEncryptionWorksLink(
                    onClick = { explainerOpen = true },
                    modifier = Modifier.padding(top = 4.dp),
                )
                state.deviceId?.let {
                    Text(
                        AppStrings.get(context, R.string.catalog_device_1_s_91ca155d, it),
                        color = c.inkMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // The dead key is what blocks a fresh one from being made, so
                // clearing it out is the first half of adding this phone back.
                if (state.revokedHere) {
                    OrangButton(
                        text = if (state.resetting) AppStrings.get(context, R.string.catalog_setting_up_cef9b69d) else AppStrings.get(context, R.string.catalog_set_up_this_phone_again_6f53f3fd),
                        onClick = vm::setUpThisPhoneAgain,
                        enabled = !state.resetting,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                if (state.deviceId == null && state.devices.any { it.revokedAt == null }) {
                    Text(
                        AppStrings.get(context, R.string.catalog_this_phone_is_not_authorized_yet_finish_1ca6df93),
                        color = c.danger,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_add_this_phone_94a65dcf),
                        onClick = vm::openAddThisDevice,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }

            SettingSection(AppStrings.get(context, R.string.catalog_authorized_encryption_devices_1f60a639)) {
                if (state.devices.isEmpty()) {
                    Text(AppStrings.get(context, R.string.catalog_no_devices_are_enrolled_on_this_account_025829c1), color = c.inkMuted, fontSize = 13.sp)
                } else {
                    state.devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.surface2, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    buildString {
                                        append(device.name)
                                        if (device.id == state.deviceId) append(AppStrings.get(context, R.string.catalog_this_phone_7bcf6179))
                                        if (device.revokedAt != null) append(AppStrings.get(context, R.string.catalog_revoked_d8230020))
                                    },
                                    color = if (device.revokedAt == null) c.ink else c.inkMuted,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    if (device.authorizedBy == null) {
                                        AppStrings.get(context, R.string.catalog_first_device_on_this_account_452cc989)
                                    } else {
                                        AppStrings.get(context, R.string.catalog_added_by_another_authorized_device_bab74dd1)
                                    },
                                    color = c.inkMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            if (
                                state.deviceId != null &&
                                !state.revokedHere &&
                                device.revokedAt == null &&
                                device.id != state.deviceId
                            ) {
                                OrangButton(
                                    text = if (state.revokingDeviceId == device.id) AppStrings.get(context, R.string.catalog_revoking_2c8a0ed4) else AppStrings.get(context, R.string.catalog_revoke_0be72075),
                                    onClick = { revokeTarget = device.id to device.name },
                                    enabled = state.revokingDeviceId == null,
                                    variant = ButtonVariant.Secondary,
                                )
                            }
                        }
                    }
                }
                state.notice?.let {
                    Text(it, color = c.success, fontSize = 13.sp)
                }
                state.error?.let {
                    Text(it, color = c.danger, fontSize = 13.sp)
                }
                if (state.deviceId != null && !state.revokedHere) {
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_add_another_device_67696610),
                        onClick = vm::openAddAnotherDevice,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }

            // Only offered to a phone that holds a key, because holding one is
            // what makes this instant: the erasure is signed here, and the
            // server's waiting period exists for requests that carry no such
            // proof. A phone without a key uses the slow path on the web.
            if (state.deviceId != null && !state.revokedHere) {
                SettingSection(AppStrings.get(context, R.string.catalog_start_over_with_new_keys_328f6a4a)) {
                    Text(
                        AppStrings.get(context, R.string.catalog_throw_away_the_encryption_identity_on_this_e4c09f9c),
                        color = c.inkSecondary,
                        fontSize = 13.sp,
                    )
                    Text(
                        AppStrings.get(context, R.string.catalog_every_message_already_in_your_encrypted_conversations_ae9912e3),
                        color = c.danger,
                        fontSize = 13.sp,
                    )
                    OrangButton(
                        text = if (state.erasing) AppStrings.get(context, R.string.catalog_erasing_fd30764f) else AppStrings.get(context, R.string.catalog_erase_my_encryption_keys_65f08bd6),
                        onClick = { eraseConfirm = true },
                        enabled = !state.erasing,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }

            SettingSection(AppStrings.get(context, R.string.catalog_the_logbook_0b3e99f4)) {
                Text(
                    AppStrings.get(context, R.string.catalog_every_device_added_or_removed_is_written_ec8b50d8),
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                state.head?.let {
                    Text(
                        if (it.first == 0) AppStrings.get(context, R.string.catalog_1_s_entry_head_2_s_8bef329a, it.first + 1, it.second.take(16))
                        else AppStrings.get(context, R.string.catalog_1_s_entries_head_2_s_f1d9bc39, it.first + 1, it.second.take(16)),
                        color = c.inkMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            SettingSection(AppStrings.get(context, R.string.catalog_checking_someone_in_person_437f86fe)) {
                Text(
                    AppStrings.get(context, R.string.catalog_have_them_open_their_code_in_orangchat_9428c129),
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                state.myCode?.let {
                    Text(
                        AppStrings.get(context, R.string.catalog_this_is_your_code_they_can_scan_f71a20fe),
                        color = c.inkMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    E2eeQrImage(it, AppStrings.get(context, R.string.catalog_my_contact_verification_qr_code_44a80ea4))
                }
            }

        }
    }

    if (explainerOpen) {
        EncryptionExplainerDialog(onDismiss = { explainerOpen = false })
    }

    if (state.transferRole != null) {
        DeviceTransferDialog(
            state = state,
            onDismiss = vm::cancelTransfer,
            onStartNew = vm::addThisDevice,
            onStartOld = vm::addAnotherDevice,
            onScannedTransfer = vm::handleScannedTransfer,
            onConfirmSas = vm::confirmSas,
            onSubmitTotp = vm::submitTotp,
            onRequestEmailCode = vm::requestTransferEmailCode,
        )
    }

    if (eraseConfirm) {
        AlertDialog(
            onDismissRequest = { eraseConfirm = false },
            title = { Text(AppStrings.get(context, R.string.catalog_erase_your_encryption_keys_e1ad1aa2), color = c.ink) },
            text = {
                Text(
                    AppStrings.get(context, R.string.catalog_this_happens_the_moment_you_confirm_there_62922ade),
                    color = c.inkSecondary,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        eraseConfirm = false
                        vm.eraseKeysNow()
                    },
                ) { Text(AppStrings.get(context, R.string.catalog_erase_them_now_9964fd63), color = c.danger) }
            },
            dismissButton = {
                TextButton(onClick = { eraseConfirm = false }) {
                    Text(AppStrings.get(context, R.string.catalog_cancel_77dfd213), color = c.inkSecondary)
                }
            },
        )
    }

    revokeTarget?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text(AppStrings.get(context, R.string.catalog_revoke_1_s_357dcc1d, name), color = c.ink) },
            text = {
                Text(
                    AppStrings.get(context, R.string.catalog_this_device_will_stop_receiving_new_conversation_45e9c7a1),
                    color = c.inkSecondary,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        revokeTarget = null
                        vm.revokeDevice(id)
                    },
                ) { Text(AppStrings.get(context, R.string.catalog_revoke_device_66468c17), color = c.danger) }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) {
                    Text(AppStrings.get(context, R.string.catalog_cancel_77dfd213), color = c.inkSecondary)
                }
            },
        )
    }
}

/** Raised globally when Android's Camera app opens a device-transfer deep link. */
@Composable
fun ScannedDeviceTransferDialog(
    raw: String,
    onDismiss: () -> Unit,
    vm: EncryptionViewModel = hiltViewModel(),
) {
        val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmed by remember(raw) { mutableStateOf(false) }
    if (!confirmed) {
        OrangDialog(
            onDismiss = onDismiss,
            title = AppStrings.get(context, R.string.catalog_transfer_code_scanned_fdb95e51),
        ) {
            ScannedTransferConfirmation(
                isNew = lt.oranges.orangchat.crypto.E2eeQr.isDeviceTransferInvite(raw),
                onContinue = {
                    confirmed = true
                    vm.handleScannedTransfer(raw)
                },
                onBack = onDismiss,
            )
        }
    } else if (state.transferRole != null) {
        DeviceTransferDialog(
            state = state,
            onDismiss = {
                vm.cancelTransfer()
                onDismiss()
            },
            onStartNew = vm::addThisDevice,
            onStartOld = vm::addAnotherDevice,
            onScannedTransfer = vm::handleScannedTransfer,
            onConfirmSas = vm::confirmSas,
            onSubmitTotp = vm::submitTotp,
            onRequestEmailCode = vm::requestTransferEmailCode,
        )
    }
}

@Composable
private fun DeviceTransferDialog(
    state: EncryptionViewModel.State,
    onDismiss: () -> Unit,
    onStartNew: () -> Unit,
    onStartOld: (String) -> Unit,
    onScannedTransfer: (String) -> Unit,
    onConfirmSas: () -> Unit,
    onSubmitTotp: (String) -> Unit,
    onRequestEmailCode: () -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    var pastedCode by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var scannerOpen by remember { mutableStateOf(false) }
    var pendingScannedCode by remember { mutableStateOf<String?>(null) }
    val isNew = state.transferRole == EncryptionViewModel.TransferRole.NEW
    val progressStep = when (state.transferStep) {
        EncryptionViewModel.TransferStep.SAS -> 2
        EncryptionViewModel.TransferStep.TOTP -> 3
        // A new device sitting in FINISHING is still on "Verify" - the step it
        // is waiting on belongs to the other device, not to this one.
        EncryptionViewModel.TransferStep.FINISHING -> if (isNew) 3 else 4
        EncryptionViewModel.TransferStep.DONE -> 4
        else -> 1
    }

    OrangDialog(
        onDismiss = onDismiss,
        title = if (isNew) AppStrings.get(context, R.string.catalog_add_this_phone_94a65dcf) else AppStrings.get(context, R.string.catalog_add_another_device_67696610),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            TransferProgress(progressStep)
            when (state.transferStep) {
                EncryptionViewModel.TransferStep.STARTING -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = c.primary,
                        )
                        Text(
                            AppStrings.get(context, R.string.catalog_creating_this_phone_s_protected_encryption_identity_f14e4807),
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                    }
                }
                EncryptionViewModel.TransferStep.QR -> {
                    Text(
                        AppStrings.get(context, R.string.catalog_fallback_mode_scan_this_code_from_an_45193b95),
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                    )
                    state.transferQr?.let { E2eeQrImage(it, AppStrings.get(context, R.string.catalog_device_transfer_qr_code_d3864e5b)) }
                    Text(
                        AppStrings.get(context, R.string.catalog_waiting_for_your_pc_b7329350),
                        color = c.inkMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EncryptionViewModel.TransferStep.WAITING -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = c.primary,
                        )
                        Column {
                            Text(
                                AppStrings.get(context, R.string.catalog_phone_connected_ac1141af),
                                color = c.ink,
                                fontSize = 14.sp,
                            )
                            Text(
                                AppStrings.get(context, R.string.catalog_waiting_for_the_pc_to_calculate_the_a6606f84),
                                color = c.inkMuted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                EncryptionViewModel.TransferStep.SAS -> {
                    Text(
                        AppStrings.get(context, R.string.catalog_both_screens_must_show_the_same_six_a112273d),
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                    )
                    Text(
                        state.transferSas.orEmpty(),
                        color = c.ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 30.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_the_digits_match_809c13bc),
                        onClick = onConfirmSas,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_they_do_not_match_a5d86ddf),
                        onClick = onDismiss,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EncryptionViewModel.TransferStep.TOTP -> {
                    val emailCode = !state.hasTwoFactor
                    if (emailCode && state.transferLoginToken == null) {
                        Text(
                            AppStrings.get(context, R.string.catalog_this_account_has_no_authenticator_app_set_3f1e6f47),
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                        OrangButton(
                            text = if (state.requestingEmailCode) AppStrings.get(context, R.string.catalog_sending_cf765512) else AppStrings.get(context, R.string.catalog_email_me_a_code_cc025d42),
                            onClick = onRequestEmailCode,
                            enabled = !state.requestingEmailCode,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        if (emailCode) {
                            Text(
                                AppStrings.get(context, R.string.catalog_a_one_time_code_is_on_its_395743b6),
                                color = c.inkSecondary,
                                fontSize = 14.sp,
                            )
                        } else {
                            Text(
                                AppStrings.get(context, R.string.catalog_enter_a_fresh_two_factor_authentication_code_9784e1c2),
                                color = c.inkSecondary,
                                fontSize = 14.sp,
                            )
                        }
                        OrangTextField(
                            value = totp,
                            onValueChange = { value -> totp = value.filter(Char::isDigit).take(if (emailCode) 6 else 8) },
                            label = if (emailCode) AppStrings.get(context, R.string.catalog_email_code_5cb76e8c) else AppStrings.get(context, R.string.catalog_authentication_code_b4f3ff1d),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OrangButton(
                            text = AppStrings.get(context, R.string.catalog_add_device_2d2367c4),
                            onClick = { onSubmitTotp(totp) },
                            enabled = totp.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (emailCode) {
                            OrangButton(
                                text = AppStrings.get(context, R.string.catalog_resend_email_code_9a8ba727),
                                onClick = onRequestEmailCode,
                                enabled = !state.requestingEmailCode,
                                variant = ButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                EncryptionViewModel.TransferStep.FINISHING -> {
                    // Nothing arrives here until a person finishes the second
                    // factor on the other device, and that can be a minute of
                    // looking for an email. Saying whose turn it is stops this
                    // from reading as a phone that has quietly stalled.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = c.primary,
                        )
                        Column {
                            Text(
                                if (isNew) AppStrings.get(context, R.string.catalog_digits_confirmed_4ecb4c0f) else AppStrings.get(context, R.string.catalog_authorizing_the_new_device_7d6e30db),
                                color = c.ink,
                                fontSize = 14.sp,
                            )
                            Text(
                                if (isNew) {
                                    AppStrings.get(context, R.string.catalog_confirm_the_digits_and_enter_the_security_cd1b28db)
                                } else {
                                    AppStrings.get(context, R.string.catalog_sending_encrypted_history_and_signing_the_new_6bfd242c)
                                },
                                color = c.inkMuted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                EncryptionViewModel.TransferStep.DONE -> {
                    Text(
                        if (isNew) AppStrings.get(context, R.string.catalog_this_phone_is_now_an_authorized_encryption_65fc8f05)
                        else AppStrings.get(context, R.string.catalog_the_new_device_was_authorized_8ec267ba),
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_done_e9b450d1),
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EncryptionViewModel.TransferStep.IDLE -> {
                    val scanned = pendingScannedCode
                    if (scanned != null) {
                        ScannedTransferConfirmation(
                            isNew = isNew,
                            onContinue = {
                                pendingScannedCode = null
                                onScannedTransfer(scanned)
                            },
                            onBack = { pendingScannedCode = null },
                        )
                    } else if (scannerOpen) {
                        TransferQrScanner(
                            expectInvitation = isNew,
                            onScanned = {
                                scannerOpen = false
                                pendingScannedCode = it
                            },
                            onCancel = { scannerOpen = false },
                        )
                    } else if (isNew) {
                        Text(
                            AppStrings.get(context, R.string.catalog_on_your_pc_open_settings_encryption_add_5a070e42),
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                        Text(
                            AppStrings.get(context, R.string.catalog_this_phone_creates_its_own_non_copyable_e6604888),
                            color = c.inkMuted,
                            fontSize = 12.sp,
                        )
                        OrangButton(
                            text = AppStrings.get(context, R.string.catalog_scan_code_from_pc_6c59b0aa),
                            onClick = { scannerOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            AppStrings.get(context, R.string.catalog_you_can_also_scan_with_the_system_35d7d733),
                            color = c.inkMuted,
                            fontSize = 12.sp,
                        )
                        OrangButton(
                            text = AppStrings.get(context, R.string.catalog_show_fallback_code_on_this_phone_90acc27c),
                            onClick = onStartNew,
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            AppStrings.get(context, R.string.catalog_scan_the_code_shown_by_the_new_953d3dac),
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                        OrangButton(
                            text = AppStrings.get(context, R.string.catalog_scan_device_code_45e4e1cb),
                            onClick = { scannerOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OrangTextField(
                            value = pastedCode,
                            onValueChange = { pastedCode = it },
                            label = AppStrings.get(context, R.string.catalog_device_transfer_code_10d0a8b9),
                            placeholder = AppStrings.get(context, R.string.catalog_orangchat_device_transfer_9d5395e9),
                        )
                        OrangButton(
                            text = AppStrings.get(context, R.string.catalog_connect_b65463cb),
                            onClick = { pendingScannedCode = pastedCode.trim() },
                            enabled = pastedCode.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            state.transferError?.let {
                Text(it, color = c.danger, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ScannedTransferConfirmation(
    isNew: Boolean,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(
            if (isNew) {
                AppStrings.get(context, R.string.catalog_the_one_time_code_from_your_pc_15a1fb2d)
            } else {
                AppStrings.get(context, R.string.catalog_the_new_device_s_code_was_recognized_7c702591)
            },
            color = c.inkSecondary,
            fontSize = 14.sp,
        )
        Text(AppStrings.get(context, R.string.catalog_what_happens_next_51ecc5b2), color = c.ink, fontSize = 15.sp)
        val steps = if (isNew) {
            listOf(
                AppStrings.get(context, R.string.catalog_this_phone_creates_a_protected_encryption_identity_c2865640),
                AppStrings.get(context, R.string.catalog_your_phone_and_pc_show_six_digits_07bd977d),
                AppStrings.get(context, R.string.catalog_confirm_2fa_on_the_authorized_pc_7bc4ca9d),
                AppStrings.get(context, R.string.catalog_the_pc_sends_encrypted_history_keys_and_e368a88b),
            )
        } else {
            listOf(
                AppStrings.get(context, R.string.catalog_this_phone_connects_to_the_new_device_9c190e97),
                AppStrings.get(context, R.string.catalog_both_devices_show_six_digits_compare_them_4427b9f0),
                AppStrings.get(context, R.string.catalog_you_enter_a_fresh_2fa_code_on_2c858a05),
                AppStrings.get(context, R.string.catalog_encrypted_history_keys_are_sent_and_the_aa611cee),
            )
        }
        steps.forEachIndexed { index, text ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("${index + 1}.", color = c.primary, fontSize = 13.sp)
                Text(text, color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        Text(
            AppStrings.get(context, R.string.catalog_only_continue_if_you_started_this_transfer_d563e585),
            color = c.inkMuted,
            fontSize = 12.sp,
        )
        OrangButton(
            text = AppStrings.get(context, R.string.catalog_continue_transfer_8ea86ccd),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
        OrangButton(
            text = AppStrings.get(context, R.string.catalog_cancel_77dfd213),
            onClick = onBack,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TransferProgress(step: Int) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    val labels = listOf(
        AppStrings.get(context, R.string.catalog_scan_28cba55d),
        AppStrings.get(context, R.string.catalog_compare_8d105cf4),
        AppStrings.get(context, R.string.catalog_2fa_e8442a3a),
        AppStrings.get(context, R.string.catalog_finish_b74bdee9),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val number = index + 1
            val complete = number < step
            val active = number == step
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(
                            when {
                                complete -> c.success.copy(alpha = 0.15f)
                                active -> c.primary.copy(alpha = 0.15f)
                                else -> c.surface2
                            },
                            RoundedCornerShape(13.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (complete) "✓" else number.toString(),
                        color = when {
                            complete -> c.success
                            active -> c.primary
                            else -> c.inkMuted
                        },
                        fontSize = 12.sp,
                    )
                }
                Text(
                    label,
                    color = if (active) c.primary else c.inkMuted,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
fun E2eeQrImage(value: String, contentDescription: String? = null) {
    val context = LocalContext.current
    val c = OrangTheme.colors
    val bitmap = remember(value) {
        val size = 720
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { image ->
            for (y in 0 until size) {
                for (x in 0 until size) {
                    image.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription ?: AppStrings.get(context, R.string.catalog_qr_code_abba02fc),
                modifier = Modifier.size(260.dp),
            )
        }
    }
}
