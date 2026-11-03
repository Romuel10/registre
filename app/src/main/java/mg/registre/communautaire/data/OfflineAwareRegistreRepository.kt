package mg.registre.communautaire.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import mg.registre.communautaire.data.local.RegisterEntryCacheStore
import mg.registre.communautaire.domain.PermissionEntryInput
import mg.registre.communautaire.domain.RegisterEntry
import mg.registre.communautaire.domain.RegisterType
import mg.registre.communautaire.domain.StandardEntryInput

/**
 * Façade v1.7 : Realtime déclenche les rafraîchissements, Room sert de cache chiffré,
 * et le repository v1.6 reste la source unique de logique métier et de mutations.
 */
class OfflineAwareRegistreRepository(private val context: Context) {
    private val base = RegistreRepository(context)
    private val cache = RegisterEntryCacheStore(context)
    private val realtime = RealtimeInvalidationClient(context)

    val deviceId: String get() = base.deviceId

    fun observeEntries(year: Int, type: RegisterType): Flow<List<RegisterEntry>> =
        refreshSignals().mapLatest { snapshot(year, type) }

    fun observeOpenMovements(year: Int, type: RegisterType): Flow<List<RegisterEntry>> =
        refreshSignals().mapLatest {
            if (type != RegisterType.R2 && type != RegisterType.R4) return@mapLatest emptyList()
            OfflineRegisterRules.openMovements(snapshot(year, type))
        }

    fun observeAvailablePermissions(year: Int): Flow<List<RegisterEntry>> =
        refreshSignals().mapLatest {
            val permissions = snapshot(year, RegisterType.R3PERM)
            val movements = listOf(RegisterType.R2, RegisterType.R4)
                .flatMap { snapshot(year, it) }
            OfflineRegisterRules.availablePermissions(permissions, movements)
        }

    suspend fun getCounterStatus(year: Int, type: RegisterType): CounterStatus =
        base.getCounterStatus(year, type)

    suspend fun initializeCounter(
        year: Int,
        type: RegisterType,
        lastUsedNumber: Long,
    ): CounterStatus = base.initializeCounter(year, type, lastUsedNumber)

    suspend fun checkIntegrity(year: Int, type: RegisterType): Boolean =
        base.checkIntegrity(year, type)

    suspend fun createStandard(year: Int, type: RegisterType, input: StandardEntryInput): String =
        base.createStandard(year, type, input)

    suspend fun createPermission(year: Int, input: PermissionEntryInput): String =
        base.createPermission(year, input)

    suspend fun cancelEntry(entryId: String, reason: String) = base.cancelEntry(entryId, reason)

    suspend fun deleteEntry(entryId: String) = base.deleteEntry(entryId)

    suspend fun closeMovement(entryId: String) = base.closeMovement(entryId)

    suspend fun syncPendingEntries() = base.syncPendingEntries()

    suspend fun checkCommunityNotifications(): List<RegisterEntry> =
        base.checkCommunityNotifications()

    private suspend fun snapshot(year: Int, type: RegisterType): List<RegisterEntry> {
        val online = isOnline()
        val current = runCatching {
            base.observeEntries(year, type).first()
        }.getOrElse { emptyList() }

        val pending = current.filter { it.status == RegisterEntry.STATUS_PENDING }
        val numbered = current.filter { it.status != RegisterEntry.STATUS_PENDING }

        if (online) {
            // Même un registre vide doit remplacer l'ancien cache afin d'éviter des lignes fantômes.
            cache.replace(year, type.code, numbered)
            return current
        }

        val cached = cache.read(year, type.code)
        return (cached + pending).sortedWith(
            compareBy<RegisterEntry> { it.officialNumber == null }
                .thenBy { it.officialNumber ?: Long.MAX_VALUE }
                .thenBy { it.createdAtMillis }
        )
    }

    private fun refreshSignals(): Flow<Unit> = merge(
        flowOf(Unit),
        realtime.registerChanges().catch { /* Le ticker reste le secours réseau. */ },
        flow {
            while (true) {
                delay(FALLBACK_REFRESH_MS)
                emit(Unit)
            }
        },
    )

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val FALLBACK_REFRESH_MS = 5 * 60_000L
    }
}
