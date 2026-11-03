package mg.registre.communautaire.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessRoleTest {
    @Test
    fun adminCanWriteAndManageMembers() {
        assertTrue(AccessRole.ADMIN.canWrite)
        assertTrue(AccessRole.ADMIN.canManageMembers)
    }

    @Test
    fun agentCanWriteButCannotManageMembers() {
        assertTrue(AccessRole.AGENT.canWrite)
        assertFalse(AccessRole.AGENT.canManageMembers)
    }

    @Test
    fun viewerIsReadOnly() {
        assertFalse(AccessRole.VIEWER.canWrite)
        assertFalse(AccessRole.VIEWER.canManageMembers)
    }

    @Test
    fun rolesAreParsedFromServerValues() {
        assertEquals(AccessRole.ADMIN, AccessRole.fromWire("admin"))
        assertEquals(AccessRole.AGENT, AccessRole.fromWire("AGENT"))
        assertEquals(AccessRole.VIEWER, AccessRole.fromWire("viewer"))
        assertNull(AccessRole.fromWire("unknown"))
    }
}
