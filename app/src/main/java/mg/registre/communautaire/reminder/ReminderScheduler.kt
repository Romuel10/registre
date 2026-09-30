package mg.registre.communautaire.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    private const val HOUR = 8

    fun scheduleAvailability(
        context: Context,
        entryId: String,
        person: String,
        kind: String,
        arrivalDate: String,
    ) {
        val arrival = LocalDate.parse(arrivalDate)
        val now = LocalDateTime.now()
        var target = arrival.atTime(HOUR, 0)
        if (!target.isAfter(now)) target = now.plusMinutes(1)

        val data = reminderData(
            entryId = entryId,
            person = person,
            kind = kind,
            date = arrivalDate,
            indefinite = false,
        )

        val request = OneTimeWorkRequestBuilder<AvailabilityReminderWorker>()
            .setInitialDelay(
                Duration.between(now, target).toMillis().coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(entryId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleIndefiniteAvailability(
        context: Context,
        entryId: String,
        person: String,
        kind: String,
        departureDate: String,
    ) {
        val departure = LocalDate.parse(departureDate)
        val now = LocalDateTime.now()
        var target = departure.plusDays(3).atTime(HOUR, 0)
        if (!target.isAfter(now)) target = now.plusMinutes(1)

        val data = reminderData(
            entryId = entryId,
            person = person,
            kind = kind,
            date = departureDate,
            indefinite = true,
        )

        val request = PeriodicWorkRequestBuilder<AvailabilityReminderWorker>(
            3,
            TimeUnit.DAYS,
        )
            .setInitialDelay(
                Duration.between(now, target).toMillis().coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName(entryId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelAvailability(context: Context, entryId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(entryId))
    }

    private fun reminderData(
        entryId: String,
        person: String,
        kind: String,
        date: String,
        indefinite: Boolean,
    ): Data = Data.Builder()
        .putString(AvailabilityReminderWorker.KEY_ENTRY_ID, entryId)
        .putString(AvailabilityReminderWorker.KEY_PERSON, person)
        .putString(AvailabilityReminderWorker.KEY_KIND, kind)
        .putString(AvailabilityReminderWorker.KEY_DATE, date)
        .putBoolean(AvailabilityReminderWorker.KEY_INDEFINITE, indefinite)
        .build()

    private fun workName(entryId: String) = "availability_$entryId"
}
