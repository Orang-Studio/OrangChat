package lt.oranges.orangchat.feature.home
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.AuditLogEntry
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * Human wording for each action the server records. An unknown action is shown
 * verbatim rather than hidden: the log is read when something has gone wrong,
 * and an entry nobody wrote a phrasing for still matters.
 */
private val ACTION_LABELS = mapOf(
    "server.update" to "updated the server",
    "channel.create" to "created a channel",
    "channel.update" to "updated a channel",
    "channel.delete" to "deleted a channel",
    "role.create" to "created a role",
    "role.update" to "updated a role",
    "role.delete" to "deleted a role",
    "member.kick" to "kicked a member",
    "member.ban" to "banned a member",
    "member.unban" to "unbanned a member",
    "member.timeout" to "timed out a member",
    "member.role_update" to "changed a member's roles",
)

@Composable
fun AuditLogScreen(
    entries: List<AuditLogEntry>,
    loading: Boolean,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    LaunchedEffect(Unit) { onLoad() }

    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = c.inkSecondary,
                modifier = Modifier.clickable(onClick = onBack).padding(4.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(AppStrings.get(context, R.string.catalog_audit_log_3cfc5f1c), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))

        when {
            loading && entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = c.primary) }

            entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text(AppStrings.get(context, R.string.catalog_nothing_has_been_logged_yet_e4080778), color = c.inkMuted, fontSize = 14.sp) }

            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(entries, key = { it.id }) { entry -> AuditRow(entry) }
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditLogEntry) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val changed = remember(entry.id) { entry.changes.keys.toList() }
    // A deleted account keeps its entries - the action still happened, and
    // dropping it would make the log lie by omission.
    val actor = entry.actor?.displayName ?: AppStrings.get(context, R.string.catalog_a_deleted_account_b1c40a9e)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "$actor ${ACTION_LABELS[entry.action] ?: entry.action}",
            color = c.ink,
            fontSize = 14.sp,
        )
        if (changed.isNotEmpty()) {
            Text("Changed: ${changed.joinToString(", ")}", color = c.inkMuted, fontSize = 12.sp)
        }
        entry.reason?.let { Text("“$it”", color = c.inkSecondary, fontSize = 12.sp) }
        Text(entry.createdAt.take(10), color = c.inkMuted, fontSize = 11.sp)
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))
}
