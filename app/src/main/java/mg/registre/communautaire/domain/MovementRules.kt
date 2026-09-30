package mg.registre.communautaire.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object MovementRules {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun arrivalDate(departureDate: LocalDate, durationDays: Int): LocalDate {
        require(durationDays >= 1) { "La durée doit être au moins de 1 jour." }
        return departureDate.plusDays((durationDays - 1).toLong())
    }

    fun arrivalDate(departureDate: String, durationDays: Int): String {
        val date = LocalDate.parse(departureDate, formatter)
        return arrivalDate(date, durationDays).format(formatter)
    }

    fun displayNumber(number: Long, type: RegisterType): String =
        number.toString() + type.code
}
