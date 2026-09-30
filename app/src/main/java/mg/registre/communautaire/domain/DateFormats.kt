package mg.registre.communautaire.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DateFormats {
    private val displayFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun display(isoDate: String): String {
        if (isoDate.isBlank()) return ""
        return runCatching {
            LocalDate.parse(isoDate).format(displayFormatter)
        }.getOrDefault(isoDate)
    }
}
