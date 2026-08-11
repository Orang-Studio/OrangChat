package lt.oranges.orangchat.data.model

object Permissions {
    const val ADMINISTRATOR = 1L shl 0

    const val MANAGE_SERVER = 1L shl 1
    const val MANAGE_ROLES = 1L shl 2
    const val MANAGE_CHANNELS = 1L shl 3
    const val MANAGE_INVITES = 1L shl 4

    const val KICK_MEMBERS = 1L shl 5
    const val BAN_MEMBERS = 1L shl 6
    const val MANAGE_NICKNAMES = 1L shl 7
    const val MANAGE_MESSAGES = 1L shl 8

    const val VIEW_CHANNEL = 1L shl 9
    const val SEND_MESSAGES = 1L shl 10
    const val EMBED_LINKS = 1L shl 11
    const val ATTACH_FILES = 1L shl 12
    const val ADD_REACTIONS = 1L shl 13
    const val MENTION_EVERYONE = 1L shl 14
    const val READ_MESSAGE_HISTORY = 1L shl 15

    const val CONNECT = 1L shl 16
    const val SPEAK = 1L shl 17
    const val VIDEO = 1L shl 18
    const val SCREEN_SHARE = 1L shl 19
    const val MUTE_MEMBERS = 1L shl 20
    const val DEAFEN_MEMBERS = 1L shl 21
    const val MOVE_MEMBERS = 1L shl 22

    const val MODERATE_MEMBERS = 1L shl 23
    const val VIEW_AUDIT_LOG = 1L shl 24

    const val MANAGE_EXPRESSIONS = 1L shl 25

    const val ALL = (1L shl 26) - 1

    const val EVERYONE_POSITION = 0

    const val OWNER_POSITION = Int.MAX_VALUE
}

data class PermissionInfo(val bit: Long, val label: String, val description: String)

data class PermissionGroup(val title: String, val permissions: List<PermissionInfo>)

val PERMISSION_GROUPS: List<PermissionGroup> = listOf(
    PermissionGroup(
        "General",
        listOf(
            PermissionInfo(
                Permissions.ADMINISTRATOR,
                "Administrator",
                "Bypasses every permission check. Grant with care.",
            ),
            PermissionInfo(Permissions.MANAGE_SERVER, "Manage Server", "Rename the server and change its settings."),
            PermissionInfo(Permissions.MANAGE_ROLES, "Manage Roles", "Create, edit and delete roles below their own."),
            PermissionInfo(Permissions.MANAGE_CHANNELS, "Manage Channels", "Create, edit and delete channels."),
            PermissionInfo(Permissions.MANAGE_INVITES, "Manage Invites", "Create and revoke invite links."),
            PermissionInfo(Permissions.VIEW_AUDIT_LOG, "View Audit Log", "See the record of server changes."),
        ),
    ),
    PermissionGroup(
        "Membership",
        listOf(
            PermissionInfo(Permissions.KICK_MEMBERS, "Kick Members", "Remove members, who may rejoin with an invite."),
            PermissionInfo(Permissions.BAN_MEMBERS, "Ban Members", "Remove members permanently."),
            PermissionInfo(Permissions.MODERATE_MEMBERS, "Timeout Members", "Temporarily block sending, reacting and speaking."),
            PermissionInfo(Permissions.MANAGE_NICKNAMES, "Manage Nicknames", "Change other members' nicknames."),
            PermissionInfo(Permissions.MANAGE_MESSAGES, "Manage Messages", "Delete and pin any message; bypass slowmode."),
        ),
    ),
    PermissionGroup(
        "Text",
        listOf(
            PermissionInfo(Permissions.VIEW_CHANNEL, "View Channels", "See channels and read their history."),
            PermissionInfo(Permissions.SEND_MESSAGES, "Send Messages", "Post in text channels."),
            PermissionInfo(Permissions.EMBED_LINKS, "Embed Links", "Show link previews."),
            PermissionInfo(Permissions.ATTACH_FILES, "Attach Files", "Upload images and files."),
            PermissionInfo(Permissions.ADD_REACTIONS, "Add Reactions", "React to messages."),
            PermissionInfo(Permissions.MENTION_EVERYONE, "Mention Everyone", "Use @everyone and @here."),
            PermissionInfo(Permissions.READ_MESSAGE_HISTORY, "Read History", "Read messages sent before joining."),
        ),
    ),
    PermissionGroup(
        "Voice",
        listOf(
            PermissionInfo(Permissions.CONNECT, "Connect", "Join voice channels."),
            PermissionInfo(Permissions.SPEAK, "Speak", "Transmit audio."),
            PermissionInfo(Permissions.VIDEO, "Video", "Turn on the camera."),
            PermissionInfo(Permissions.SCREEN_SHARE, "Screen Share", "Share the screen."),
            PermissionInfo(Permissions.MUTE_MEMBERS, "Mute Members", "Server-mute others."),
            PermissionInfo(Permissions.DEAFEN_MEMBERS, "Deafen Members", "Server-deafen others."),
            PermissionInfo(Permissions.MOVE_MEMBERS, "Move Members", "Move others between voice channels."),
        ),
    ),
    PermissionGroup(
        "Expression",
        listOf(
            PermissionInfo(
                Permissions.MANAGE_EXPRESSIONS,
                "Manage Expressions",
                "Upload, rename and delete custom emoji and sounds.",
            ),
        ),
    ),
)

fun String.toPermissionBits(): Long = toLongOrNull() ?: 0L

fun Long.toPermissionString(): String = toString()

fun Long.hasPermission(required: Long): Boolean =
    if (this and Permissions.ADMINISTRATOR != 0L) true else (this and required) == required

fun combinePermissions(bitfields: List<Long>): Long = bitfields.fold(0L) { acc, b -> acc or b }
