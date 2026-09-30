package mg.registre.communautaire.data

data class CounterStatus(
    val initialized: Boolean,
    val lastNumber: Long,
    val nextNumber: Long,
)
