package mg.registre.communautaire.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import mg.registre.communautaire.data.FirebaseBootstrap
import mg.registre.communautaire.data.RegistreRepository

class NumberReservationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!FirebaseBootstrap.initialize(applicationContext)) return Result.failure()
        val entryId = inputData.getString(KEY_ENTRY_ID) ?: return Result.failure()

        return runCatching {
            RegistreRepository(applicationContext).reserveOfficialNumber(entryId)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val KEY_ENTRY_ID = "entry_id"
    }
}
