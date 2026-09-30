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
    val annualRight: Int = 0,
    val consumedRight: Int = 0,

    val movementKind: String = "",
    val durationDays: Int = 0,
    val beneficiary: String = "",
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
    val annualRight: Int,
    val consumedRight: Int,
    val durationDays: Int,
)
