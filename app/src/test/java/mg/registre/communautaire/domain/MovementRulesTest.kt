package mg.registre.communautaire.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MovementRulesTest {
    @Test
    fun dayOneIsDepartureDay() {
        val start = LocalDate.of(2026, 9, 30)
        assertEquals(LocalDate.of(2026, 10, 1), MovementRules.arrivalDate(start, 2))
    }

    @Test
    fun oneDayEndsSameDay() {
        val start = LocalDate.of(2026, 9, 30)
        assertEquals(start, MovementRules.arrivalDate(start, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun durationCannotBeZero() {
        MovementRules.arrivalDate(LocalDate.of(2026, 9, 30), 0)
    }

    @Test
    fun officialNumberUsesRegisterSuffix() {
        assertEquals("12/3.S", MovementRules.displayNumber(12, RegisterType.R3S))
    }

    @Test
    fun datesAreDisplayedDayMonthYear() {
        assertEquals("30-09-2026", DateFormats.display("2026-09-30"))
        assertEquals("04-10-2026", DateFormats.display("2026-10-04"))
    }

    @Test
    fun blankDateStaysBlank() {
        assertEquals("", DateFormats.display(""))
    }
}
