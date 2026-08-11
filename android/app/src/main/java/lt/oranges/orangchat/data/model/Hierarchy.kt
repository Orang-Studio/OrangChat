package lt.oranges.orangchat.data.model

import lt.oranges.orangchat.util.parseInstant
import java.time.Instant

object Hierarchy {

    fun highestPosition(detail: ServerDetail, userId: String): Int? {
        if (detail.server.ownerId == userId) return Permissions.OWNER_POSITION
        val member = detail.members.firstOrNull { it.userId == userId } ?: return null
        return detail.roles
            .filter { it.id in member.roleIds }
            .maxOfOrNull { it.position }
            ?: Permissions.EVERYONE_POSITION
    }

    fun everyoneRole(detail: ServerDetail): Role? =
        detail.roles.firstOrNull { it.position == Permissions.EVERYONE_POSITION }

    fun effectivePermissions(detail: ServerDetail, userId: String): Long {
        if (detail.server.ownerId == userId) return Permissions.ALL
        val member = detail.members.firstOrNull { it.userId == userId } ?: return 0L
        val assigned = detail.roles.filter { it.id in member.roleIds }.map { it.permissions.toPermissionBits() }
        val everyone = everyoneRole(detail)?.permissions?.toPermissionBits() ?: 0L
        return combinePermissions(listOf(everyone) + assigned)
    }

    fun isOwner(detail: ServerDetail, userId: String): Boolean = detail.server.ownerId == userId

    fun canManageRole(detail: ServerDetail, selfId: String, role: Role): Boolean {
        if (!effectivePermissions(detail, selfId).hasPermission(Permissions.MANAGE_ROLES)) return false
        val actor = highestPosition(detail, selfId) ?: return false
        return actor > role.position
    }

    fun outranks(detail: ServerDetail, selfId: String, targetUserId: String): Boolean {
        val actor = highestPosition(detail, selfId) ?: return false
        if (actor == Permissions.OWNER_POSITION) return true
        val target = highestPosition(detail, targetUserId) ?: return true
        return actor > target
    }

    fun canChangePermissions(actorPerms: Long, oldPerms: Long, newPerms: Long): Boolean {
        if (actorPerms.hasPermission(Permissions.ADMINISTRATOR)) return true
        val changed = oldPerms xor newPerms
        return changed and actorPerms.inv() == 0L
    }

    fun togglableBits(detail: ServerDetail, selfId: String): Long {
        val perms = effectivePermissions(detail, selfId)
        return if (perms.hasPermission(Permissions.ADMINISTRATOR)) Permissions.ALL else perms
    }

    fun canChangeMemberRole(
        detail: ServerDetail,
        selfId: String,
        targetUserId: String,
        role: Role,
        granting: Boolean,
    ): Boolean {
        val actorPerms = effectivePermissions(detail, selfId)
        if (!actorPerms.hasPermission(Permissions.MANAGE_ROLES)) return false
        if (targetUserId != selfId && !outranks(detail, selfId, targetUserId)) return false
        val actor = highestPosition(detail, selfId) ?: return false
        if (actor <= role.position) return false
        if (role.position == Permissions.EVERYONE_POSITION) return false
        if (granting && !canChangePermissions(actorPerms, 0L, role.permissions.toPermissionBits())) return false
        return true
    }

    fun canKick(detail: ServerDetail, selfId: String, targetUserId: String): Boolean =
        targetUserId != selfId &&
            !isOwner(detail, targetUserId) &&
            effectivePermissions(detail, selfId).hasPermission(Permissions.KICK_MEMBERS) &&
            outranks(detail, selfId, targetUserId)

    fun canBan(detail: ServerDetail, selfId: String, targetUserId: String): Boolean =
        targetUserId != selfId &&
            !isOwner(detail, targetUserId) &&
            effectivePermissions(detail, selfId).hasPermission(Permissions.BAN_MEMBERS) &&
            outranks(detail, selfId, targetUserId)

    fun canTimeout(detail: ServerDetail, selfId: String, targetUserId: String): Boolean {
        if (targetUserId == selfId || isOwner(detail, targetUserId)) return false
        if (!effectivePermissions(detail, selfId).hasPermission(Permissions.MODERATE_MEMBERS)) return false
        if (effectivePermissions(detail, targetUserId).hasPermission(Permissions.ADMINISTRATOR)) return false
        return outranks(detail, selfId, targetUserId)
    }

    fun canManageNickname(detail: ServerDetail, selfId: String, targetUserId: String): Boolean {
        val perms = effectivePermissions(detail, selfId)
        if (targetUserId == selfId) return true
        return perms.hasPermission(Permissions.MANAGE_NICKNAMES) && outranks(detail, selfId, targetUserId)
    }
}

fun ServerMember.isTimedOut(now: Instant = Instant.now()): Boolean {
    val until = parseInstant(timedOutUntil) ?: return false
    return until.isAfter(now)
}
