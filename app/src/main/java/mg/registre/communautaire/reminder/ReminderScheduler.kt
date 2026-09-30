package mg.registre.communautaire.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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

        enqueue(
            context = context,
            entryId = entryId,
            person = person,
            kind = kind,
            date = arrivalDate,
            indefinite = false,
            delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0L),
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

        enqueue(
            context = context,
            entryId = entryId,
            person = person,
            kind = kind,
            date = departureDate,
            indefinite = true,
            delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0L),
        )
    }

    fun scheduleNextIndefinite(
        context: Context,
        entryId: String,
        person: String,
        kind: String,
    ) {
        enqueue(
            context = context,
            entryId = entryId,
            person = person,
            kind = kind,
            date = "",
            indefinite = true,
            delayMillis = TimeUnit.DAYS.toMillis(3),
        )
    }

    fun cancelAvailability(context: Context, entryId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(entryId))
    }

    private fun enqueue(
        context: Context,
        entryId: String,
        person: String,
        kind: String,
        date: String,
        indefinite: Boolean,
        delayMillis: Long,
    ) {
        val data = Data.Builder()
            .putString(AvailabilityReminderWorker.KEY_ENTRY_ID, entryId)
            .putString(AvailabilityReminderWorker.KEY_PERSON, person)
            .putString(AvailabilityReminderWorker.KEY_KIND, kind)
            .putString(AvailabilityReminderWorker.KEY_DATE, date)
            .putBoolean(AvailabilityReminderWorker.KEY_INDEFINITE, indefinite)
            .build()

        val request = OneTimeWorkRequestBuilder<AvailabilityReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(entryId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun workName(entryId: String) = "availability_$entryId"
}
