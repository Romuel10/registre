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
) {
    companion object {
        const val STATUS_PENDING = "PENDING_NUMBER"
        const val STATUS_NUMBERED = "NUMBERED"
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
