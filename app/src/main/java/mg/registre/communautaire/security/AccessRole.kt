package mg.registre.communautaire.security

enum class AccessRole(val wireValue: String) {
    ADMIN("admin"),
    AGENT("agent"),
    VIEWER("viewer");

    val canWrite: Boolean get() = this == ADMIN || this == AGENT
    val canManageMembers: Boolean get() = this == ADMIN

    companion object {
        fun fromWire(value: String): AccessRole? =
            entries.firstOrNull { it.wireValue == value.lowercase() }
    }
}
