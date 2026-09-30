package mg.registre.communautaire.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import mg.registre.communautaire.R
import mg.registre.communautaire.domain.RegisterEntry
import kotlin.math.absoluteValue

object CommunityNotificationHelper {
    const val CHANNEL_ID = "community_register_updates"

    fun notifyNewEntries(context: Context, entries: List<RegisterEntry>) {
        if (entries.isEmpty()) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Nouveaux enregistrements",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Informations sur les nouveaux numéros ajoutés dans les cahiers partagés"
                }
            )
        }

        entries.forEach { entry ->
            val detail = when {
                entry.fullName.isNotBlank() -> entry.fullName
                entry.label.isNotBlank() -> entry.label
                entry.origin.isNotBlank() -> entry.origin
                else -> "Nouvelle entrée enregistrée"
            }
            val display = entry.displayNumber.ifBlank { "numéro attribué" }
            val title = "Nouveau " + entry.registerType + " : " + display
            val body = detail + " · année " + entry.year

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            manager.notify(("community_" + entry.id).hashCode().absoluteValue, notification)
        }
    }
}
