package mg.registre.communautaire.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import mg.registre.communautaire.R
import kotlin.math.absoluteValue

class AvailabilityReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val person = inputData.getString(KEY_PERSON).orEmpty().ifBlank { "la personne concernée" }
        val kind = inputData.getString(KEY_KIND).orEmpty().ifBlank { "déplacement / permission" }
        val date = inputData.getString(KEY_DATE).orEmpty()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rappels de disponibilité",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Rappels à la fin des déplacements et permissions"
        }
        manager.createNotificationChannel(channel)

        val message = "Préparer le message de disponibilité de " + person +
            " à la fin de son " + kind + if (date.isNotBlank()) " (" + date + ")." else "."

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Message de disponibilité à préparer")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(("availability_" + person + date).hashCode().absoluteValue, notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "availability_reminders"
        const val KEY_PERSON = "person"
        const val KEY_KIND = "kind"
        const val KEY_DATE = "date"
    }
}
