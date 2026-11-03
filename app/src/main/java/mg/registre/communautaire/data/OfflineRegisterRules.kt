package mg.registre.communautaire.data

import mg.registre.communautaire.domain.RegisterEntry

object OfflineRegisterRules {
    fun openMovements(entries: List<RegisterEntry>): List<RegisterEntry> {
        val completedMovementIds = entries
            .filter {
                it.messageKind == RegisterEntry.MESSAGE_AVAILABILITY &&
                    it.status != RegisterEntry.STATUS_CANCELLED &&
                    !it.isDeleted
            }
            .map { it.relatedMovementId }
            .filter { it.isNotBlank() }
            .toSet()

        return entries.filter {
            it.messageKind == RegisterEntry.MESSAGE_MOVEMENT &&
                it.status != RegisterEntry.STATUS_CANCELLED &&
                !it.isDeleted &&
                it.id !in completedMovementIds
        }.sortedByDescending { it.createdAtMillis }
    }

    fun availablePermissions(
        permissions: List<RegisterEntry>,
        movements: List<RegisterEntry>,
    ): List<RegisterEntry> {
        val usedPermissionIds = movements
            .filter {
                it.messageKind == RegisterEntry.MESSAGE_MOVEMENT &&
                    it.status != RegisterEntry.STATUS_CANCELLED &&
                    !it.isDeleted &&
                    it.relatedPermissionId.isNotBlank()
            }
            .map { it.relatedPermissionId }
            .toSet()

        return permissions
            .filter {
                it.status == RegisterEntry.STATUS_NUMBERED &&
                    !it.isDeleted &&
                    it.officialNumber != null &&
                    it.id !in usedPermissionIds
            }
            .sortedByDescending { it.createdAtMillis }
    }
}
