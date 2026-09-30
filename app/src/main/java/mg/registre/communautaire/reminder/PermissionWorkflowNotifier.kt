package mg.registre.communautaire.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import mg.registre.communautaire.R
import kotlin.math.absoluteValue

object PermissionWorkflowNotifier {
    private const val CHANNEL_ID = "permission_workflow"

    fun notifyMovementRequired(
        context: Context,
        permissionId: String,
        person: String,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Suivi des permissions",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Rappels des messages /2 liés aux permissions /3.PERM"
                }
            )
        }

        val body = "La permission de $person est enregistrée. Créez maintenant dans /2 le message de déplacement « déplacement perm »."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Message de déplacement /2 à faire")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(("permission_movement_" + permissionId).hashCode().absoluteValue, notification)
    }
}
