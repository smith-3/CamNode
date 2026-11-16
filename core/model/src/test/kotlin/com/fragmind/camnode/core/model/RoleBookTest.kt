package com.fragmind.camnode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleBookTest {
    private val owner = NodeKeys.generate()
    private val adminKeys = NodeKeys.generate()
    private val phoneKeys = NodeKeys.generate()
    private val attacker = NodeKeys.generate()
    private val admin = NodeId("admin")
    private val phone = NodeId("phone")

    private fun grant(node: NodeId, key: NodeKeyPair, role: AccessRole, at: Long, by: NodeKeyPair) =
        RoleGrant(node, key.publicKey, role, at, issuerKey = by.publicKey).signedBy(by)

    private fun book(vararg grants: RoleGrant) = RoleBook().merge(RoleBook(grants.toList()), owner.publicKey)

    private fun RoleBook.roleOf(node: NodeId, key: NodeKeyPair) = roleOf(node, key.publicKey, owner.publicKey)

    @Test
    fun ownerIsAdminAndNewcomersAreCameras() {
        val empty = RoleBook()
        assertEquals(AccessRole.ADMIN, empty.roleOf(NodeId("owner"), owner))
        assertEquals(AccessRole.CAMERA, empty.roleOf(phone, phoneKeys))
    }

    @Test
    fun ownerCanRaiseARole() {
        assertEquals(AccessRole.VIEWER, book(grant(phone, phoneKeys, AccessRole.VIEWER, at = 1, by = owner)).roleOf(phone, phoneKeys))
    }

    @Test
    fun forgedGrantsAreDroppedEvenWhenNewer() {
        val real = grant(phone, phoneKeys, AccessRole.VIEWER, at = 1, by = owner)
        val selfPromotion = grant(phone, phoneKeys, AccessRole.ADMIN, at = 99, by = phoneKeys)
        val tampered = real.copy(role = AccessRole.ADMIN, issuedAtMillis = 99)
        val outsider = grant(phone, phoneKeys, AccessRole.REVOKED, at = 99, by = attacker)

        val merged = book(real, selfPromotion, tampered, outsider)

        assertEquals(listOf(real), merged.grants)
        assertEquals(AccessRole.VIEWER, merged.roleOf(phone, phoneKeys))
    }

    @Test
    fun appointedAdminsManageEveryoneButAdmins() {
        val appointed = grant(admin, adminKeys, AccessRole.ADMIN, at = 1, by = owner)
        val viewerByAdmin = grant(phone, phoneKeys, AccessRole.VIEWER, at = 2, by = adminKeys)
        assertEquals(AccessRole.VIEWER, book(appointed, viewerByAdmin).roleOf(phone, phoneKeys))

        val otherAdmin = NodeId("other")
        val otherKeys = NodeKeys.generate()
        val promotedByOwner = grant(otherAdmin, otherKeys, AccessRole.ADMIN, at = 1, by = owner)
        val demotion = grant(otherAdmin, otherKeys, AccessRole.REVOKED, at = 5, by = adminKeys)
        val newAdminByAdmin = grant(phone, phoneKeys, AccessRole.ADMIN, at = 6, by = adminKeys)
        val merged = book(appointed, promotedByOwner, demotion, newAdminByAdmin)

        assertEquals(AccessRole.ADMIN, merged.roleOf(otherAdmin, otherKeys))
        assertEquals(AccessRole.CAMERA, merged.roleOf(phone, phoneKeys))
    }

    @Test
    fun revocationSticksEvenWithANewKey() {
        val revoked = book(grant(phone, phoneKeys, AccessRole.REVOKED, at = 3, by = owner))
        assertEquals(AccessRole.REVOKED, revoked.roleOf(phone, phoneKeys))
        assertEquals(AccessRole.REVOKED, revoked.roleOf(phone, NodeKeys.generate()))
    }

    @Test
    fun aRoleIsBoundToTheKeyItWasGrantedTo() {
        val viewer = book(grant(phone, phoneKeys, AccessRole.VIEWER, at = 1, by = owner))
        assertEquals(AccessRole.CAMERA, viewer.roleOf(phone, attacker))
    }

    @Test
    fun mergeIsCommutativeAndIdempotent() {
        val a = RoleBook(listOf(grant(phone, phoneKeys, AccessRole.VIEWER, at = 1, by = owner)))
        val b = RoleBook(listOf(grant(phone, phoneKeys, AccessRole.RECORDER, at = 2, by = owner), grant(admin, adminKeys, AccessRole.ADMIN, at = 1, by = owner)))
        assertEquals(a.merge(b, owner.publicKey), b.merge(a, owner.publicKey))
        val once = a.merge(b, owner.publicKey)
        assertEquals(once, once.merge(b, owner.publicKey))
    }

    @Test
    fun legacyHouseholdsKeepSharedSecretAccess() {
        assertEquals(AccessRole.ADMIN, RoleBook().roleOf(phone, phoneKeys.publicKey, ownerKey = null))
        assertTrue(RoleBook(listOf(grant(phone, phoneKeys, AccessRole.REVOKED, 1, owner))).merge(RoleBook(), ownerKey = null).grants.isEmpty())
    }

    @Test
    fun signaturesDetectTampering() {
        val data = "hola".toByteArray()
        val signature = NodeKeys.sign(owner.privateKey, data)
        assertTrue(NodeKeys.verify(owner.publicKey, data, signature))
        assertFalse(NodeKeys.verify(owner.publicKey, "hola!".toByteArray(), signature))
        assertFalse(NodeKeys.verify("no-es-una-llave", data, signature))
    }
}
