package mg.registre.communautaire.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import mg.registre.communautaire.data.RegistreRepository
import mg.registre.communautaire.data.SupabaseConfig

class CommunityNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!SupabaseConfig.isConfigured()) return Result.failure()

        return runCatching {
            val entries = RegistreRepository(applicationContext).checkCommunityNotifications()
            CommunityNotificationHelper.notifyNewEntries(applicationContext, entries)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
