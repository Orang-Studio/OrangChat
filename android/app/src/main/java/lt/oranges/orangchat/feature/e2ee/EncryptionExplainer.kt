package lt.oranges.orangchat.feature.e2ee
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * The plain-language explanation of end-to-end encryption, mirroring the web
 * client's `HowEncryptionWorks.tsx` sentence for sentence so the two platforms
 * do not teach people two different mental models.
 *
 * Locks, keys and a logbook, deliberately. The accurate words - identity key,
 * epoch, transparency log, safety number - mean nothing to the person this copy
 * exists for, and a reader who bounces off the first paragraph has learned less
 * than one who reads a slightly lossy version to the end.
 *
 * It must not oversell the default. docs/E2EE.md §6.4 is explicit that the
 * honest sentence is "cannot read your messages without being caught", and the
 * unverified default is the normal state rather than a chore left undone.
 */

@Composable
private fun ExplainerSection(icon: ImageVector, title: String, body: @Composable () -> Unit) {
    val c = OrangTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(c.surface3, RoundedCornerShape(OrangRadius.lg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(16.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            body()
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, color = OrangTheme.colors.inkSecondary, fontSize = 13.sp)
}

@Composable
fun EncryptionExplainerContent(modifier: Modifier = Modifier) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface1, RoundedCornerShape(OrangRadius.xl2))
                .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl2))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = c.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    AppStrings.get(context, R.string.catalog_your_messages_are_locked_before_they_leave_a431b93a),
                    color = c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                AppStrings.get(context, R.string.catalog_only_the_people_in_the_conversation_can_ae492280),
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
        }

        ExplainerSection(Icons.Default.Key, "Every device cuts its own key") {
            Body(
                AppStrings.get(context, R.string.catalog_your_phone_and_your_computer_each_make_ade9350c),
            )
            Body(
                AppStrings.get(context, R.string.catalog_that_is_also_why_adding_a_second_4bac217f),
            )
        }

        ExplainerSection(Icons.Default.Lock, "What happens when you press send") {
            Body(
                AppStrings.get(context, R.string.catalog_your_phone_puts_the_message_in_a_10625561),
            )
            Body(
                AppStrings.get(context, R.string.catalog_we_can_still_see_the_outside_of_c8bd071e),
            )
        }

        ExplainerSection(Icons.Default.MenuBook, "The hard part: is it really their lock?") {
            Body(
                AppStrings.get(context, R.string.catalog_to_lock_a_box_for_someone_your_a0458be1),
            )
            Body(AppStrings.get(context, R.string.catalog_two_answers_and_you_pick_how_much_c95f5a5c))
            Body(
                AppStrings.get(context, R.string.catalog_send_straight_away_a_swap_gets_caught_2e19b55a),
            )
            Body(
                AppStrings.get(context, R.string.catalog_check_them_first_a_swap_cannot_happen_82229615),
            )
            Body(
                AppStrings.get(context, R.string.catalog_sending_straight_away_is_what_every_conversation_68012f61),
            )
        }

        ExplainerSection(Icons.Default.QrCodeScanner, "How you check someone") {
            Body(
                AppStrings.get(context, R.string.catalog_both_of_you_open_the_conversation_tap_bbf94671),
            )
            Body(
                AppStrings.get(context, R.string.catalog_not_in_the_same_room_you_will_d53e345b),
            )
        }

        ExplainerSection(Icons.Default.Warning, "If something ever looks wrong") {
            Body(
                AppStrings.get(context, R.string.catalog_if_a_safety_code_changes_or_a_fdad80c2),
            )
            Body(
                AppStrings.get(context, R.string.catalog_it_is_not_always_an_attack_it_c9d44cb1),
            )
        }

        ExplainerSection(Icons.Default.Smartphone, "If you lose your only device") {
            Body(
                AppStrings.get(context, R.string.catalog_the_messages_only_that_device_could_open_dd92a9bc),
            )
            Body(
                AppStrings.get(context, R.string.catalog_adding_a_second_device_before_you_need_1f6a418c),
            )
        }

        ExplainerSection(Icons.Default.VisibilityOff, "What this does not hide") {
            Body(AppStrings.get(context, R.string.catalog_who_you_talk_to_when_and_how_96d03db2))
            Body(AppStrings.get(context, R.string.catalog_whatever_the_other_person_does_with_the_2d44e6c7))
            Body(AppStrings.get(context, R.string.catalog_anything_on_a_phone_that_is_already_7e329eae))
            Body(AppStrings.get(context, R.string.catalog_server_channels_those_have_shared_history_moderation_c68bcd1b))
        }
    }
}

@Composable
fun EncryptionExplainerDialog(onDismiss: () -> Unit) {
        val context = LocalContext.current
    OrangDialog(
        onDismiss = onDismiss,
        title = AppStrings.get(context, R.string.catalog_how_your_messages_are_protected_bb07e15c),
        description = AppStrings.get(context, R.string.catalog_in_plain_language_with_no_jargon_to_b56795a6),
    ) {
        EncryptionExplainerContent(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/** The way into the explainer from anywhere encryption is mentioned. */
@Composable
fun HowEncryptionWorksLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.HelpOutline,
            contentDescription = null,
            tint = c.inkMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            AppStrings.get(context, R.string.catalog_how_does_this_work_1ae14530),
            color = c.inkMuted,
            fontSize = 12.sp,
            textDecoration = TextDecoration.Underline,
        )
    }
}
