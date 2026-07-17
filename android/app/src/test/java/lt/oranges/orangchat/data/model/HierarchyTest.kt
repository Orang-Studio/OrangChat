package lt.oranges.orangchat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the escalation cases covered by services/membership.rs's own tests —
 * these gates are what keeps a moderator from self-granting ADMINISTRATOR.
 */
class HierarchyTest {

    private fun user(id: String) = User(id = id, username = id, displayName = id)

    private fun role(id: String, position: Int, perms: Long, name: String = id) =
        Role(id = id, serverId = "s1", name = name, permissions = perms.toString(), position = position)

    private fun member(userId: String, vararg roleIds: String) = ServerMember(
        id = "m-$userId",
        serverId = "s1",
        userId = userId,
        roleIds = roleIds.toList(),
        user = user(userId),
    )

    private fun detail(roles: List<Role>, members: List<ServerMember>, ownerId: String = "owner") = ServerDetail(
        server = Server(id = "s1", name = "S", ownerId = ownerId),
        roles = roles,
        members = members,
    )

    private val everyone = role("everyone", 0, Permissions.VIEW_CHANNEL or Permissions.SEND_MESSAGES)

    @Test
    fun `owner outranks every real role`() {
        val top = role("top", 100, Permissions.ALL)
        val d = detail(listOf(everyone, top), listOf(member("owner"), member("mod", "top")))

        assertEquals(Permissions.OWNER_POSITION, Hierarchy.highestPosition(d, "owner"))
        assertTrue(Hierarchy.outranks(d, "owner", "mod"))
        assertFalse(Hierarchy.outranks(d, "mod", "owner"))
    }

    @Test
    fun `everyone permissions apply even when not in roleIds`() {
        val d = detail(listOf(everyone), listOf(member("bob")))
        val perms = Hierarchy.effectivePermissions(d, "bob")

        assertTrue(perms.hasPermission(Permissions.SEND_MESSAGES))
        assertFalse(perms.hasPermission(Permissions.BAN_MEMBERS))
    }

    @Test
    fun `non-member has no position and no permissions`() {
        val d = detail(listOf(everyone), listOf(member("bob")))

        assertEquals(null, Hierarchy.highestPosition(d, "ghost"))
        assertEquals(0L, Hierarchy.effectivePermissions(d, "ghost"))
    }

    @Test
    fun `holding a role does not let you edit it`() {
        val mods = role("mods", 5, Permissions.MANAGE_ROLES)
        val d = detail(listOf(everyone, mods), listOf(member("mod", "mods")))

        assertFalse(Hierarchy.canManageRole(d, "mod", mods))
    }

    @Test
    fun `you may edit a role below your own`() {
        val mods = role("mods", 5, Permissions.MANAGE_ROLES)
        val lower = role("lower", 1, 0L)
        val d = detail(listOf(everyone, mods, lower), listOf(member("mod", "mods")))

        assertTrue(Hierarchy.canManageRole(d, "mod", lower))
    }

    @Test
    fun `blocks self promotion to administrator`() {
        val mods = role("mods", 5, Permissions.MANAGE_ROLES)
        val lower = role("lower", 1, 0L)
        val d = detail(listOf(everyone, mods, lower), listOf(member("mod", "mods")))
        val actorPerms = Hierarchy.effectivePermissions(d, "mod")

        assertFalse(Hierarchy.canChangePermissions(actorPerms, 0L, Permissions.ADMINISTRATOR))
    }

    @Test
    fun `untouched bits the actor lacks are allowed`() {
        val actorPerms = Permissions.MANAGE_ROLES
        val old = Permissions.BAN_MEMBERS
        val new = Permissions.BAN_MEMBERS

        assertTrue(Hierarchy.canChangePermissions(actorPerms, old, new))
    }

    @Test
    fun `revoking a permission the actor lacks is blocked`() {
        val actorPerms = Permissions.MANAGE_ROLES
        val old = Permissions.BAN_MEMBERS
        val new = 0L

        assertFalse(Hierarchy.canChangePermissions(actorPerms, old, new))
    }

    @Test
    fun `administrator may change anything`() {
        assertTrue(Hierarchy.canChangePermissions(Permissions.ADMINISTRATOR, 0L, Permissions.ALL))
    }

    @Test
    fun `granting a role carrying permissions the actor lacks is blocked`() {
        val mods = role("mods", 5, Permissions.MANAGE_ROLES)
        val banner = role("banner", 1, Permissions.BAN_MEMBERS)
        val d = detail(listOf(everyone, mods, banner), listOf(member("mod", "mods"), member("bob")))

        assertFalse(Hierarchy.canChangeMemberRole(d, "mod", "bob", banner, granting = true))
    }

    @Test
    fun `revoking that same role is allowed`() {
        val mods = role("mods", 5, Permissions.MANAGE_ROLES)
        val banner = role("banner", 1, Permissions.BAN_MEMBERS)
        val d = detail(listOf(everyone, mods, banner), listOf(member("mod", "mods"), member("bob", "banner")))

        assertTrue(Hierarchy.canChangeMemberRole(d, "mod", "bob", banner, granting = false))
    }

    @Test
    fun `self assignment is allowed and not refused by outranks`() {
        val mods = role("mods", 5, Permissions.MANAGE_ROLES)
        val colour = role("colour", 1, 0L)
        val d = detail(listOf(everyone, mods, colour), listOf(member("mod", "mods")))

        assertTrue(Hierarchy.canChangeMemberRole(d, "mod", "mod", colour, granting = true))
    }

    @Test
    fun `everyone role can never be assigned`() {
        val mods = role("mods", 5, Permissions.MANAGE_ROLES)
        val d = detail(listOf(everyone, mods), listOf(member("mod", "mods"), member("bob")))

        assertFalse(Hierarchy.canChangeMemberRole(d, "mod", "bob", everyone, granting = true))
    }

    @Test
    fun `admins cannot be timed out`() {
        val admins = role("admins", 9, Permissions.ADMINISTRATOR)
        val mods = role("mods", 5, Permissions.MODERATE_MEMBERS)
        val d = detail(listOf(everyone, admins, mods), listOf(member("mod", "mods"), member("admin", "admins")))

        assertFalse(Hierarchy.canTimeout(d, "mod", "admin"))
    }

    @Test
    fun `owner cannot be kicked or banned`() {
        val mods = role("mods", 5, Permissions.KICK_MEMBERS or Permissions.BAN_MEMBERS)
        val d = detail(listOf(everyone, mods), listOf(member("mod", "mods"), member("owner")))

        assertFalse(Hierarchy.canKick(d, "mod", "owner"))
        assertFalse(Hierarchy.canBan(d, "mod", "owner"))
    }

    @Test
    fun `a mod cannot kick a peer at the same position`() {
        val mods = role("mods", 5, Permissions.KICK_MEMBERS)
        val d = detail(listOf(everyone, mods), listOf(member("a", "mods"), member("b", "mods")))

        assertFalse(Hierarchy.canKick(d, "a", "b"))
    }

    @Test
    fun `banning a non-member is allowed for pre-emptive bans`() {
        val mods = role("mods", 5, Permissions.BAN_MEMBERS)
        val d = detail(listOf(everyone, mods), listOf(member("mod", "mods")))

        assertTrue(Hierarchy.outranks(d, "mod", "ghost"))
    }

    @Test
    fun `permission bits match the shared contract`() {
        assertEquals(1L, Permissions.ADMINISTRATOR)
        assertEquals(1L shl 23, Permissions.MODERATE_MEMBERS)
        assertEquals(1L shl 24, Permissions.VIEW_AUDIT_LOG)
        assertEquals(1L shl 25, Permissions.MANAGE_EXPRESSIONS)
        assertEquals((1L shl 26) - 1, Permissions.ALL)
    }

    @Test
    fun `administrator short-circuits hasPermission`() {
        assertTrue(Permissions.ADMINISTRATOR.hasPermission(Permissions.BAN_MEMBERS))
        assertFalse(Permissions.SEND_MESSAGES.hasPermission(Permissions.BAN_MEMBERS))
    }
}
