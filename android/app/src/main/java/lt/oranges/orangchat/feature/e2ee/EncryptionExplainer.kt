package lt.oranges.orangchat.feature.e2ee

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
                    "Your messages are locked before they leave this phone",
                    color = c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Only the people in the conversation can open them. Not OrangChat, not somebody who steals our database, not somebody who turns up with a court order - we do not hold a key we could hand over. This is on for every direct message and there is no way to switch it off.",
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
        }

        ExplainerSection(Icons.Default.Key, "Every device cuts its own key") {
            Body(
                "Your phone and your computer each make their own key, on the device itself, the first time you use encryption there. It goes into the part of the device built to guard keys, and it is made so that it cannot be read back out - not by OrangChat, not by another app, not by you. There is nothing to leak and nothing to hand over.",
            )
            Body(
                "That is also why adding a second device is a small ceremony instead of a copy and paste. Your old device has to see the new one - a code on the screen, a camera pointed at it - before anything moves.",
            )
        }

        ExplainerSection(Icons.Default.Lock, "What happens when you press send") {
            Body(
                "Your phone puts the message in a box and locks it. Every device in the conversation, and only those, holds a key to that box. Our servers store and pass along the locked box.",
            )
            Body(
                "We can still see the outside of it: who sent it, who it went to, how big it was and when. We cannot see what is inside.",
            )
        }

        ExplainerSection(Icons.Default.MenuBook, "The hard part: is it really their lock?") {
            Body(
                "To lock a box for someone, your phone needs their lock, and it asks our servers for it. So the fair question is: what if we handed you a lock we had made ourselves, kept the key, and passed your messages along afterwards?",
            )
            Body("Two answers, and you pick how much you want.")
            Body(
                "Send straight away - a swap gets caught. Every lock anyone publishes is written into a logbook that can only be added to, never edited or erased. Your own devices read the page about your account every time they start. If a lock is ever published in your name that your devices did not make, they tell you, and the entry stays in the book where anyone can point at it. A swap can be attempted; it cannot be attempted quietly.",
            )
            Body(
                "Check them first - a swap cannot happen. You check the other person's lock yourself, in person or on a call, before anything is sent to them. Until you have, messages you type stay on this phone, still locked, and go nowhere. There is nothing for a swapped lock to open.",
            )
            Body(
                "Sending straight away is what every conversation gets. Checking first is the extra step, and it is only worth turning on for people you can realistically meet or ring.",
            )
        }

        ExplainerSection(Icons.Default.QrCodeScanner, "How you check someone") {
            Body(
                "Both of you open the conversation, tap the lock at the top, and one of you scans the other's code. Then swap and do it the other way round - one scan only proves one direction. It takes about ten seconds while you are stood together.",
            )
            Body(
                "Not in the same room? You will each see the same short row of numbers, called a safety code. Have them read it out on a phone call, or send it over another app you already trust, and type it into the box under the code - the app compares all sixty digits, which is more than anyone manages by eye. If it matches, nobody is in the middle. The one thing that does not count is an OrangChat voice call - that audio goes through the same servers this check exists to test.",
            )
        }

        ExplainerSection(Icons.Default.Warning, "If something ever looks wrong") {
            Body(
                "If a safety code changes, or a device you did not add appears on your account, OrangChat stops and tells you outright instead of showing a notification you might swipe away.",
            )
            Body(
                "It is not always an attack - it is also what happens when somebody loses every device and has to start over. There is no way to tell those apart from inside the app, which is why it asks you to check with the person directly before you send anything else.",
            )
        }

        ExplainerSection(Icons.Default.Smartphone, "If you lose your only device") {
            Body(
                "The messages only that device could open stay locked, permanently. We cannot recover them for you - if we could, none of the above would be true.",
            )
            Body(
                "Adding a second device before you need one is the whole answer. That is also why two-factor authentication has to be on first: a spare device is a spare set of keys, and it should take more than a password to make one.",
            )
        }

        ExplainerSection(Icons.Default.VisibilityOff, "What this does not hide") {
            Body("• Who you talk to, when, and how often. Locked boxes still have to be addressed to somebody.")
            Body("• Whatever the other person does with the message. They can screenshot it, save it, or show someone. Encryption stops strangers, not recipients.")
            Body("• Anything on a phone that is already unlocked and in someone else's hands. At that point they are reading it the same way you do.")
            Body("• Server channels. Those have shared history, moderation and search, all of which need the server to read them. Only direct messages and group DMs are encrypted this way.")
        }
    }
}

@Composable
fun EncryptionExplainerDialog(onDismiss: () -> Unit) {
    OrangDialog(
        onDismiss = onDismiss,
        title = "How your messages are protected",
        description = "In plain language, with no jargon to get past first.",
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
            "How does this work?",
            color = c.inkMuted,
            fontSize = 12.sp,
            textDecoration = TextDecoration.Underline,
        )
    }
}
