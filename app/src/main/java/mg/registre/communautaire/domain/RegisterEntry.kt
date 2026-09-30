package mg.registre.communautaire.domain

data class RegisterEntry(
    val id: String = "",
    val year: Int = 0,
    val registerType: String = "",
    val officialNumber: Long? = null,
    val displayNumber: String = "",
    val status: String = STATUS_PENDING,
    val createdAtMillis: Long = 0L,
    val createdBy: String = "",

    val pieceNumber: String = "",
    val pieceDate: String = "",
    val origin: String = "",
    val label: String = "",
    val observation: String = "",

    val grade: String = "",
    val fullName: String = "",
    val matricule: String = "",
    val numberR3: String = "",
    val departureDate: String = "",
    val arrivalDate: String = "",
    val annualRightYear: Int = 0,
    val consumedRightDetail: String = "",

    val movementKind: String = "",
    val durationDays: Int = 0,
    val durationIndefinite: Boolean = false,
    val movementClosedAt: String = "",
    val beneficiary: String = "",
    val creatorDeviceId: String = "",

    val messageKind: String = MESSAGE_ORDINARY,
    val relatedMovementId: String = "",
    val relatedPermissionId: String = "",
    val cancelledAt: String = "",
    val cancelledReason: String = "",
    val deletedAt: String = "",
) {
    val isCancelled: Boolean get() = status == STATUS_CANCELLED
    val isDeleted: Boolean get() = status == STATUS_DELETED
    val isActive: Boolean get() = status == STATUS_NUMBERED && deletedAt.isBlank()

    companion object {
        const val STATUS_PENDING = "PENDING_NUMBER"
        const val STATUS_NUMBERED = "NUMBERED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_DELETED = "DELETED"

        const val MESSAGE_ORDINARY = "ordinary"
        const val MESSAGE_MOVEMENT = "movement"
        const val MESSAGE_AVAILABILITY = "availability"
        const val MESSAGE_PERMISSION = "permission"
    }
}

data class StandardEntryInput(
    val pieceNumber: String,
    val pieceDate: String,
    val origin: String,
    val label: String,
    val observation: String,
    val movementKind: String = "",
    val durationDays: Int = 0,
    val durationIndefinite: Boolean = false,
    val beneficiary: String = "",
    val departureDate: String = "",
    val arrivalDate: String = "",
    val messageKind: String = RegisterEntry.MESSAGE_ORDINARY,
    val relatedMovementId: String = "",
    val relatedPermissionId: String = "",
)

data class PermissionEntryInput(
    val grade: String,
    val fullName: String,
    val matricule: String,
    val numberR3: String,
    val departureDate: String,
    val arrivalDate: String,
    val annualRightYear: Int,
    val consumedRightDetail: String,
    val durationDays: Int,
    val durationIndefinite: Boolean,
)
