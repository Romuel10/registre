package mg.registre.communautaire.data

import mg.registre.communautaire.domain.RegisterEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineRegisterRulesTest {
    @Test
    fun `availability closes its linked movement`() {
        val movement = RegisterEntry(
            id = "m1",
            officialNumber = 10,
            status = RegisterEntry.STATUS_NUMBERED,
            messageKind = RegisterEntry.MESSAGE_MOVEMENT,
            createdAtMillis = 10,
        )
        val availability = RegisterEntry(
            id = "a1",
            officialNumber = 11,
            status = RegisterEntry.STATUS_NUMBERED,
            messageKind = RegisterEntry.MESSAGE_AVAILABILITY,
            relatedMovementId = "m1",
            createdAtMillis = 20,
        )

        assertEquals(emptyList<RegisterEntry>(), OfflineRegisterRules.openMovements(listOf(movement, availability)))
    }

    @Test
    fun `cancelled availability does not close movement`() {
        val movement = RegisterEntry(
            id = "m1",
            officialNumber = 10,
            status = RegisterEntry.STATUS_NUMBERED,
            messageKind = RegisterEntry.MESSAGE_MOVEMENT,
        )
        val cancelledAvailability = RegisterEntry(
            id = "a1",
            officialNumber = 11,
            status = RegisterEntry.STATUS_CANCELLED,
            messageKind = RegisterEntry.MESSAGE_AVAILABILITY,
            relatedMovementId = "m1",
        )

        assertEquals(listOf(movement), OfflineRegisterRules.openMovements(listOf(movement, cancelledAvailability)))
    }

    @Test
    fun `permission already linked to movement is excluded`() {
        val p1 = RegisterEntry(
            id = "p1",
            officialNumber = 1,
            status = RegisterEntry.STATUS_NUMBERED,
            messageKind = RegisterEntry.MESSAGE_PERMISSION,
        )
        val p2 = p1.copy(id = "p2", officialNumber = 2)
        val movement = RegisterEntry(
            id = "m1",
            officialNumber = 3,
            status = RegisterEntry.STATUS_NUMBERED,
            messageKind = RegisterEntry.MESSAGE_MOVEMENT,
            relatedPermissionId = "p1",
        )

        assertEquals(listOf(p2), OfflineRegisterRules.availablePermissions(listOf(p1, p2), listOf(movement)))
    }

    @Test
    fun `cancelled movement releases permission`() {
        val permission = RegisterEntry(
            id = "p1",
            officialNumber = 1,
            status = RegisterEntry.STATUS_NUMBERED,
            messageKind = RegisterEntry.MESSAGE_PERMISSION,
        )
        val cancelledMovement = RegisterEntry(
            id = "m1",
            officialNumber = 3,
            status = RegisterEntry.STATUS_CANCELLED,
            messageKind = RegisterEntry.MESSAGE_MOVEMENT,
            relatedPermissionId = "p1",
        )

        assertEquals(
            listOf(permission),
            OfflineRegisterRules.availablePermissions(listOf(permission), listOf(cancelledMovement)),
        )
    }
}
