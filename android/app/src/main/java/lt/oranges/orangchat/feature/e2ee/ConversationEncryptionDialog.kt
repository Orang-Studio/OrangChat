package lt.oranges.orangchat.feature.e2ee
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import lt.oranges.orangchat.crypto.E2ee
import lt.oranges.orangchat.feature.home.AppViewModel
import lt.oranges.orangchat.feature.settings.E2eeQrImage
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * What the lock in a conversation header opens onto, rewritten as something a
 * person can read (docs/E2EE.md §6.6).
 *
 * It used to be an AlertDialog holding a sentence about safety numbers, a wall
 * of digits, a scan button, an inline QR code, an unlabelled switch and a bare
 * "Reset encryption key" link, in that order, with nothing saying what any of it
 * was for. Someone who tapped a padlock out of curiosity met six controls and no
 * explanation.
 *
 * The order here is the order the questions arrive in: what state am I in, how
 * does this work, what is the choice, how do I check this person, and only then
 * the digits - which mean nothing without the sentence above them.
 */
@Composable
fun ConversationEncryptionDialog(
    info: AppViewModel.ConversationEncryptionInfo,
    peerName: String?,
    /** False when there is nobody scannable here - a group, or no contact route. */
    canScan: Boolean,
    onScan: () -> Unit,
    onSetStrict: ((Boolean) -> Unit)?,
    /** Turning verification off needs the screen lock first (§6.5). */
    onRelaxStrict: () -> Unit,
    onResetEncryption: (() -> Unit)?,
    /** Compares a code read out over some other channel (§6.6); see below. */
    onCompareSafetyNumber: ((String, (AppViewModel.SafetyNumberVerdict) -> Unit) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val clipboard = LocalClipboardManager.current
    var explainerOpen by remember { mutableStateOf(false) }
    var showMyCode by remember { mutableStateOf(false) }
    var confirmingReset by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    val who = if (info.group) AppStrings.get(context, R.string.catalog_everyone_here_02cadf2a) else (peerName ?: "them")
    val title = if (info.verified) {
        "Encrypted, and you have checked who you're talking to"
    } else {
        "This conversation is encrypted"
    }

    OrangDialog(onDismiss = onDismiss, title = title) {
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StateCard(
                icon = if (info.verified) Icons.Default.VerifiedUser else Icons.Default.Lock,
                tint = if (info.verified) c.success else c.inkMuted,
                body = if (info.verified) {
                    "You have seen ${who}'s code with your own eyes, so nothing sent from here can be redirected to a lock somebody else made."
                } else {
                    "Messages are locked on this phone and only $who can open them. OrangChat stores them locked and cannot read them."
                },
                onExplain = { explainerOpen = true },
            )

            if (info.group) {
                GroupModeNote()
            } else if (onSetStrict != null) {
                ModeChoice(
                    strict = info.strictHere,
                    onStandard = onRelaxStrict,
                    onStrict = { onSetStrict(true) },
                )
            }

            if (!info.group && canScan) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (info.verified) AppStrings.get(context, R.string.catalog_check_them_again_feb54286) else "Check that it is really $who",
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        AppStrings.get(context, R.string.catalog_standing_together_scan_each_other_s_codes_faf20092),
                        color = c.inkMuted,
                        fontSize = 12.sp,
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_scan_their_code_b273ef3b),
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (info.myCode != null) {
                        OrangButton(
                            text = if (showMyCode) AppStrings.get(context, R.string.catalog_hide_my_code_835d9e98) else AppStrings.get(context, R.string.catalog_show_my_code_342b7a4e),
                            onClick = { showMyCode = !showMyCode },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (showMyCode) {
                            E2eeQrImage(info.myCode, "My verification code")
                            Text(
                                AppStrings.get(context, R.string.catalog_this_code_holds_nothing_secret_it_is_373de7f3),
                                color = c.inkMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            OutlinedCard {
                CardHeading(Icons.Default.Phone, "Not in the same room?")
                Text(
                    if (info.group) {
                        "Everyone in this group sees the same numbers, and only while they are all in the same group with the same people. Read them out to each other to confirm it."
                    } else {
                        "Read these numbers to each other on a phone call, or send them over another app you already trust. If they match, nobody is in the middle. An OrangChat call does not count - its audio goes through the servers this check is testing."
                    },
                    color = c.inkMuted,
                    fontSize = 12.sp,
                )
                if (info.safetyNumber != null) {
                    Text(
                        info.safetyNumber,
                        color = c.ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
                            .padding(10.dp),
                    )
                    OrangButton(
                        text = if (copied) "Copied" else "Copy",
                        onClick = {
                            clipboard.setText(AnnotatedString(info.safetyNumber))
                            copied = true
                        },
                        variant = ButtonVariant.Ghost,
                    )
                    if (onCompareSafetyNumber != null) {
                        TypedSafetyNumberCheck(
                            group = info.group,
                            who = who,
                            onCompare = onCompareSafetyNumber,
                        )
                    }
                } else {
                    Text(
                        AppStrings.get(context, R.string.catalog_these_numbers_appear_once_both_accounts_have_288c556c),
                        color = c.inkMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            if (onResetEncryption != null) {
                OutlinedCard {
                    CardHeading(Icons.Default.Refresh, "Start a fresh key")
                    Text(
                        AppStrings.get(context, R.string.catalog_replaces_the_key_used_from_now_on_c57ccaed),
                        color = c.inkMuted,
                        fontSize = 12.sp,
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_new_key_for_this_conversation_651d7e16),
                        onClick = { confirmingReset = true },
                        variant = ButtonVariant.Secondary,
                    )
                }
            }

            info.error?.let {
                Text(it, color = c.danger, fontSize = 12.sp)
            }

            OrangButton(
                text = "Done",
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (explainerOpen) {
        EncryptionExplainerDialog(onDismiss = { explainerOpen = false })
    }

    if (confirmingReset && onResetEncryption != null) {
        OrangDialog(
            onDismiss = { confirmingReset = false },
            title = AppStrings.get(context, R.string.catalog_start_a_fresh_key_here_11c5421d),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    AppStrings.get(context, R.string.catalog_everything_sent_from_now_on_uses_a_4d8b70ec),
                    color = c.inkSecondary,
                    fontSize = 14.sp,
                )
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_make_a_new_key_cde9afba),
                    onClick = {
                        confirmingReset = false
                        onResetEncryption()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OrangButton(
                    text = "Cancel",
                    onClick = { confirmingReset = false },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StateCard(
    icon: ImageVector,
    tint: Color,
    body: String,
    onExplain: () -> Unit,
) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface1, RoundedCornerShape(OrangRadius.xl2))
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl2))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(body, color = c.inkSecondary, fontSize = 13.sp)
            HowEncryptionWorksLink(onClick = onExplain)
        }
    }
}

/**
 * The two modes of §6, as the choice they are. A lone switch reads as
 * "encryption: off/on" to anybody who has not read the design doc - the exact
 * misreading §6 forbids - so both options say plainly that they encrypt, and
 * neither is drawn as the deficient one.
 */
@Composable
private fun ModeChoice(strict: Boolean, onStandard: () -> Unit, onStrict: () -> Unit) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            AppStrings.get(context, R.string.catalog_before_a_message_leaves_this_phone_74f6bb9b),
            color = c.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            AppStrings.get(context, R.string.catalog_both_options_encrypt_everything_the_difference_is_098d8758),
            color = c.inkMuted,
            fontSize = 12.sp,
        )
        ModeCard(
            icon = Icons.Default.Lock,
            title = AppStrings.get(context, R.string.catalog_send_straight_away_19f3932b),
            body = AppStrings.get(context, R.string.catalog_messages_go_as_soon_as_you_send_e76569f5),
            selected = !strict,
            onClick = { if (strict) onStandard() },
        )
        ModeCard(
            icon = Icons.Default.Shield,
            title = AppStrings.get(context, R.string.catalog_check_them_first_27eeb4a8),
            body = AppStrings.get(context, R.string.catalog_nothing_is_sent_until_you_have_checked_6db8aee8),
            selected = strict,
            onClick = { if (!strict) onStrict() },
        )
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) c.primarySoft else c.surface3,
                RoundedCornerShape(OrangRadius.xl2),
            )
            .border(
                1.dp,
                if (selected) c.primary else c.border,
                RoundedCornerShape(OrangRadius.xl2),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) c.primary else c.inkMuted,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(body, color = c.inkSecondary, fontSize = 12.sp)
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = c.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Groups are standard-only in v1 (§6.3), and the reason has to be visible rather
 * than looking like a missing feature.
 */
@Composable
private fun GroupModeNote() {
        val context = LocalContext.current
    val c = OrangTheme.colors
    OutlinedCard {
        CardHeading(Icons.Default.Group, "Group conversations send straight away")
        Text(
            AppStrings.get(context, R.string.catalog_checking_someone_s_code_is_a_one_b6951560),
            color = c.inkMuted,
            fontSize = 12.sp,
        )
    }
}

/**
 * Somewhere to type the code the other person just read out, and a machine to
 * compare it (docs/E2EE.md §6.6).
 *
 * Printing the digits and leaving it there asked the user to compare sixty of
 * them by eye and then told the app nothing about the answer - so this phone
 * never learned it had checked anybody, and verify-first mode stayed out of
 * reach for anyone not stood next to their contact. Typing is no weaker than
 * scanning: the digits still had to travel over a channel the server does not
 * control, and it is the user's ear that authenticates the voice reading them.
 */
@Composable
private fun TypedSafetyNumberCheck(
    group: Boolean,
    who: String,
    onCompare: (String, (AppViewModel.SafetyNumberVerdict) -> Unit) -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    var typed by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<AppViewModel.SafetyNumberVerdict?>(null) }
    var comparing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OrangTextField(
            value = typed,
            onValueChange = {
                typed = it
                verdict = null
            },
            label = if (group) {
                "Type the numbers somebody read out"
            } else {
                "Type the numbers $who read out"
            },
            placeholder = "00000 00000 00000 …",
            hint = AppStrings.get(context, R.string.catalog_checking_them_here_is_safer_than_reading_faa9c2e1),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OrangButton(
            text = if (comparing) AppStrings.get(context, R.string.catalog_comparing_ad916050) else "Compare",
            onClick = {
                comparing = true
                onCompare(typed) {
                    verdict = it
                    comparing = false
                }
            },
            enabled = !comparing && typed.isNotBlank(),
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        when (verdict) {
            AppViewModel.SafetyNumberVerdict.INCOMPLETE -> Text(
                "That is ${typed.count { it.isDigit() }} of ${E2ee.SAFETY_NUMBER_DIGITS} digits. Nothing is compared until the whole code is there, so a partial one is never called a mismatch.",
                color = c.inkMuted,
                fontSize = 12.sp,
            )
            AppViewModel.SafetyNumberVerdict.MISMATCH -> Text(
                AppStrings.get(context, R.string.catalog_these_do_not_match_most_often_that_0139df50),
                color = c.danger,
                fontSize = 12.sp,
            )
            AppViewModel.SafetyNumberVerdict.MATCH -> Text(
                if (group) {
                    "Identical. Everyone here is in the same group with the same people, and nothing has been swapped underneath it."
                } else {
                    "Identical, so nothing has been swapped underneath this conversation. This phone has now checked $who - have them compare it on their side too, or their app still has nothing written down."
                },
                color = c.success,
                fontSize = 12.sp,
            )
            AppViewModel.SafetyNumberVerdict.UNAVAILABLE -> Text(
                AppStrings.get(context, R.string.catalog_there_is_no_code_to_compare_yet_5ce7929c),
                color = c.inkMuted,
                fontSize = 12.sp,
            )
            null -> Unit
        }
    }
}

@Composable
private fun OutlinedCard(content: @Composable () -> Unit) {
    val c = OrangTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl2))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun CardHeading(icon: ImageVector, title: String) {
    val c = OrangTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.inkMuted, modifier = Modifier.size(16.dp))
        Text(title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
