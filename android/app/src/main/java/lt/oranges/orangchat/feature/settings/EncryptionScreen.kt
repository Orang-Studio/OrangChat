package lt.oranges.orangchat.feature.settings

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
    val c = OrangTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    var revokeTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var explainerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(c.surface1)) {
        SettingsTopBar("Encryption", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection("This device") {
                Text(
                    when {
                        state.revokedHere ->
                            "This phone was removed from your account from another device. Its key no longer opens anything here, and it cannot read new encrypted messages until you add it again."
                        state.deviceId != null ->
                            "Your direct messages are locked on this phone before they are sent. The key was made here, lives in the phone's secure hardware, and cannot be copied off it - not by OrangChat, and not by anyone holding a database backup."
                        else -> "This phone has not made its encryption key yet."
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
                        "Device $it",
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
                        text = if (state.resetting) "Setting up…" else "Set up this phone again",
                        onClick = vm::setUpThisPhoneAgain,
                        enabled = !state.resetting,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                if (state.deviceId == null && state.devices.any { it.revokedAt == null }) {
                    Text(
                        "This phone is not authorized yet. Finish “Add this phone” before it can read encrypted history or revoke another encryption device.",
                        color = c.danger,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    OrangButton(
                        text = "Add this phone",
                        onClick = vm::openAddThisDevice,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }

            SettingSection("Authorized encryption devices") {
                if (state.devices.isEmpty()) {
                    Text("No devices are enrolled on this account.", color = c.inkMuted, fontSize = 13.sp)
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
                                        if (device.id == state.deviceId) append("  ·  this phone")
                                        if (device.revokedAt != null) append("  ·  revoked")
                                    },
                                    color = if (device.revokedAt == null) c.ink else c.inkMuted,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    if (device.authorizedBy == null) {
                                        "First device on this account"
                                    } else {
                                        "Added by another authorized device"
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
                                    text = if (state.revokingDeviceId == device.id) "Revoking…" else "Revoke",
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
                        text = "Add another device",
                        onClick = vm::openAddAnotherDevice,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }

            SettingSection("The logbook") {
                Text(
                    "Every device added or removed is written into a logbook that can only be added to, never edited or erased. Your devices read it on every start, so a device you did not add cannot appear here quietly - you get told, and the entry stays in the book.",
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                state.head?.let {
                    Text(
                        "${it.first + 1} ${if (it.first == 0) "entry" else "entries"} · head ${it.second.take(16)}…",
                        color = c.inkMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            SettingSection("Checking someone in person") {
                Text(
                    "Have them open their code in OrangChat and point this phone's camera at it. Your phone then remembers the lock you actually saw, so a swapped one no longer matches. One scan proves one direction - show them yours too.",
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                state.myCode?.let {
                    Text(
                        "This is your code. They can scan it from the lock at the top of your conversation, or from this screen on their own phone.",
                        color = c.inkMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    E2eeQrImage(it, "My contact verification QR code")
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

    revokeTarget?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke $name?", color = c.ink) },
            text = {
                Text(
                    "This device will stop receiving new conversation keys. It remains in the append-only device log as revoked, so the change cannot be hidden.",
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
                ) { Text("Revoke device", color = c.danger) }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) {
                    Text("Cancel", color = c.inkSecondary)
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
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmed by remember(raw) { mutableStateOf(false) }
    if (!confirmed) {
        OrangDialog(
            onDismiss = onDismiss,
            title = "Transfer code scanned",
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
    val c = OrangTheme.colors
    var pastedCode by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var scannerOpen by remember { mutableStateOf(false) }
    var pendingScannedCode by remember { mutableStateOf<String?>(null) }
    val isNew = state.transferRole == EncryptionViewModel.TransferRole.NEW
    val progressStep = when (state.transferStep) {
        EncryptionViewModel.TransferStep.SAS -> 2
        EncryptionViewModel.TransferStep.TOTP -> 3
        EncryptionViewModel.TransferStep.FINISHING,
        EncryptionViewModel.TransferStep.DONE -> 4
        else -> 1
    }

    OrangDialog(
        onDismiss = onDismiss,
        title = if (isNew) "Add this phone" else "Add another device",
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
                            "Creating this phone’s protected encryption identity…",
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                    }
                }
                EncryptionViewModel.TransferStep.QR -> {
                    Text(
                        "Fallback mode: scan this code from an already-authorized phone, or paste it into an authorized computer.",
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                    )
                    state.transferQr?.let { E2eeQrImage(it, "Device transfer QR code") }
                    Text(
                        "Waiting for your PC…",
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
                                "Phone connected",
                                color = c.ink,
                                fontSize = 14.sp,
                            )
                            Text(
                                "Waiting for the PC to calculate the comparison digits…",
                                color = c.inkMuted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                EncryptionViewModel.TransferStep.SAS -> {
                    Text(
                        "Both screens must show the same six digits. If they differ, cancel.",
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
                        text = "The digits match",
                        onClick = onConfirmSas,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OrangButton(
                        text = "They do not match",
                        onClick = onDismiss,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EncryptionViewModel.TransferStep.TOTP -> {
                    val emailCode = !state.hasTwoFactor
                    if (emailCode && state.transferLoginToken == null) {
                        Text(
                            "This account has no authenticator app set up, so OrangChat will email a one-time code to approve the new device.",
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                        OrangButton(
                            text = if (state.requestingEmailCode) "Sending…" else "Email me a code",
                            onClick = onRequestEmailCode,
                            enabled = !state.requestingEmailCode,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        if (emailCode) {
                            Text(
                                "A one-time code is on its way to your email - it expires in 10 minutes.",
                                color = c.inkSecondary,
                                fontSize = 14.sp,
                            )
                        } else {
                            Text(
                                "Enter a fresh two-factor authentication code. The new device will be signed into your append-only device log.",
                                color = c.inkSecondary,
                                fontSize = 14.sp,
                            )
                        }
                        OrangTextField(
                            value = totp,
                            onValueChange = { value -> totp = value.filter(Char::isDigit).take(if (emailCode) 6 else 8) },
                            label = if (emailCode) "Email code" else "Authentication code",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OrangButton(
                            text = "Add device",
                            onClick = { onSubmitTotp(totp) },
                            enabled = totp.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (emailCode) {
                            OrangButton(
                                text = "Resend email code",
                                onClick = onRequestEmailCode,
                                enabled = !state.requestingEmailCode,
                                variant = ButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                EncryptionViewModel.TransferStep.FINISHING -> {
                    Text(
                        if (isNew) "Receiving and securing your encrypted history…"
                        else "Sending encrypted history and authorizing the new device…",
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                    )
                }
                EncryptionViewModel.TransferStep.DONE -> {
                    Text(
                        if (isNew) "This phone is now an authorized encryption device."
                        else "The new device was authorized.",
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                    )
                    OrangButton(
                        text = "Done",
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
                            "On your PC, open Settings → Encryption → Add another device. Your PC will show a one-time QR code.",
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                        Text(
                            "This phone creates its own non-copyable identity. Only encrypted conversation-history keys move from the PC.",
                            color = c.inkMuted,
                            fontSize = 12.sp,
                        )
                        OrangButton(
                            text = "Scan code from PC",
                            onClick = { scannerOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "You can also scan with the system Camera app and tap “Open OrangChat”.",
                            color = c.inkMuted,
                            fontSize = 12.sp,
                        )
                        OrangButton(
                            text = "Show fallback code on this phone",
                            onClick = onStartNew,
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "Scan the code shown by the new device, or paste the complete orangchat://device-transfer code here.",
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                        OrangButton(
                            text = "Scan device code",
                            onClick = { scannerOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OrangTextField(
                            value = pastedCode,
                            onValueChange = { pastedCode = it },
                            label = "Device transfer code",
                            placeholder = "orangchat://device-transfer?…",
                        )
                        OrangButton(
                            text = "Connect",
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
    val c = OrangTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(
            if (isNew) {
                "The one-time code from your PC was recognized. Nothing has been transferred yet."
            } else {
                "The new device’s code was recognized. It is not authorized yet."
            },
            color = c.inkSecondary,
            fontSize = 14.sp,
        )
        Text("What happens next", color = c.ink, fontSize = 15.sp)
        val steps = if (isNew) {
            listOf(
                "This phone creates a protected encryption identity in Android Keystore.",
                "Your phone and PC show six digits. Compare them and cancel if they differ.",
                "Confirm 2FA on the authorized PC.",
                "The PC sends encrypted history keys and records this phone as authorized.",
            )
        } else {
            listOf(
                "This phone connects to the new device without authorizing it yet.",
                "Both devices show six digits. Compare them and cancel if they differ.",
                "You enter a fresh 2FA code on this authorized phone.",
                "Encrypted history keys are sent and the new device is recorded as authorized.",
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
            "Only continue if you started this transfer and can see both devices.",
            color = c.inkMuted,
            fontSize = 12.sp,
        )
        OrangButton(
            text = "Continue transfer",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
        OrangButton(
            text = "Cancel",
            onClick = onBack,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TransferProgress(step: Int) {
    val c = OrangTheme.colors
    val labels = listOf("Scan", "Compare", "2FA", "Finish")
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
fun E2eeQrImage(value: String, contentDescription: String = "QR code") {
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
                contentDescription = contentDescription,
                modifier = Modifier.size(260.dp),
            )
        }
    }
}
