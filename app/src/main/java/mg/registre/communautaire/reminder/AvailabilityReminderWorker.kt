package mg.registre.communautaire.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import mg.registre.communautaire.R
import mg.registre.communautaire.domain.DateFormats
import kotlin.math.absoluteValue

class AvailabilityReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID).orEmpty()
        val person = inputData.getString(KEY_PERSON).orEmpty().ifBlank { "la personne concernée" }
        val kind = inputData.getString(KEY_KIND).orEmpty().ifBlank { "déplacement / permission" }
        val date = inputData.getString(KEY_DATE).orEmpty()
        val indefinite = inputData.getBoolean(KEY_INDEFINITE, false)

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rappels de disponibilité",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Rappels à la fin des déplacements et permissions"
            }
            manager.createNotificationChannel(channel)
        }

        val isPermissionFlow =
            kind.equals("permission", ignoreCase = true) ||
                kind.equals("déplacement perm", ignoreCase = true)

        val datePart = if (date.isNotBlank())
            " (" + DateFormats.display(date) + ")."
        else
            "."

        val message = when {
            isPermissionFlow && indefinite ->
                "Vérifier si $person est revenu(e). Dès son retour, créez dans /2 ou /4 le message de disponibilité « disponibilité perm »."
            isPermissionFlow ->
                "Le déplacement lié à la permission de $person arrive à son terme" +
                    datePart +
                    " Créez dans /2 ou /4 le message de disponibilité « disponibilité perm »."
            indefinite ->
                "Le déplacement de $person est à durée indéterminée. Vérifier s'il/elle est revenu(e) et préparer le message de disponibilité si nécessaire."
            else ->
                "Préparer le message de disponibilité de $person à la fin de son $kind" +
                    datePart
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                when {
                    isPermissionFlow && indefinite -> "Permission : vérifier le retour"
                    isPermissionFlow -> "Message de disponibilité perm à faire"
                    indefinite -> "Vérifier le retour de la personne"
                    else -> "Message de disponibilité à préparer"
                }
            )
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(("availability_" + entryId + person).hashCode().absoluteValue, notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "availability_reminders"
        const val KEY_ENTRY_ID = "entry_id"
        const val KEY_PERSON = "person"
        const val KEY_KIND = "kind"
        const val KEY_DATE = "date"
        const val KEY_INDEFINITE = "indefinite"
    }
}
