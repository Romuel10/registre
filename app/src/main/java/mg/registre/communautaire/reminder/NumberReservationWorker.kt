package mg.registre.communautaire.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import mg.registre.communautaire.data.RegistreRepository
import mg.registre.communautaire.data.SupabaseConfig
import mg.registre.communautaire.security.AuthSessionManager

class NumberReservationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!SupabaseConfig.isConfigured()) return Result.failure()
        if (!AuthSessionManager(applicationContext).hasStoredSession()) return Result.success()

        return runCatching {
            RegistreRepository(applicationContext).syncPendingEntries()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
