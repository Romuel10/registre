package mg.registre.communautaire.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

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
        if (!target.isAfter(now)) {
            target = now.plusMinutes(1)
        }

        val delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0L)
        val data = Data.Builder()
            .putString(AvailabilityReminderWorker.KEY_PERSON, person)
            .putString(AvailabilityReminderWorker.KEY_KIND, kind)
            .putString(AvailabilityReminderWorker.KEY_DATE, arrivalDate)
            .build()

        val request = OneTimeWorkRequestBuilder<AvailabilityReminderWorker>()
            .setInitialDelay(delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "availability_" + entryId,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
