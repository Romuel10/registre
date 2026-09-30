package mg.registre.communautaire

import android.app.Application
import mg.registre.communautaire.reminder.CommunityNotificationScheduler

class RegistreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CommunityNotificationScheduler.ensureScheduled(this)
    }
}
