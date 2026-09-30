package mg.registre.communautaire.domain

enum class RegisterType(
    val code: String,
    val title: String,
    val description: String,
) {
    R2("/2", "Cahier /2", "Enregistrement des pièces du cahier /2"),
    R3("/3", "Cahier /3", "Pièces et messages, dont déplacements"),
    R4("/4", "Cahier /4", "Enregistrement des pièces du cahier /4"),
    R3S("/3.S", "Cahier /3.S", "Enregistrement des pièces du cahier /3.S"),
    R3PERM("/3.PERM", "Cahier /3.PERM", "Permissions et suivi des droits");

    companion object {
        fun fromCode(code: String): RegisterType =
            entries.firstOrNull { it.code == code } ?: R2
    }
}
