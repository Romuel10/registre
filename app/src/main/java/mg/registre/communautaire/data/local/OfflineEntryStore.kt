package mg.registre.communautaire.data.local

import android.content.Context
import java.time.OffsetDateTime
import mg.registre.communautaire.security.SecureStore
import org.json.JSONArray
import org.json.JSONObject

class OfflineEntryStore(context: Context) {
    private val dao = OfflineDatabase.get(context).entryDao()
    private val cipher = OfflinePayloadCipher()
    private val migrationPrefs = context.getSharedPreferences("offline_room_migration", Context.MODE_PRIVATE)
    private val legacyPending = SecureStore(context, "registre_pending_secure")

    suspend fun migrateLegacyPendingIfNeeded() {
        if (migrationPrefs.getBoolean(MIGRATION_V1_DONE, false)) return

        val legacy = runCatching {
            JSONArray(legacyPending.getString(LEGACY_PENDING_KEY, "[]"))
        }.getOrElse { JSONArray() }

        for (index in 0 until legacy.length()) {
            val item = legacy.optJSONObject(index) ?: continue
            val localId = item.optString("localId")
            val payload = item.optJSONObject("payload") ?: continue
            if (localId.isBlank()) continue
            savePending(localId, payload)
        }

        legacyPending.remove(LEGACY_PENDING_KEY)
        migrationPrefs.edit().putBoolean(MIGRATION_V1_DONE, true).apply()
    }

    suspend fun replaceRemoteCache(year: Int, registerType: String, array: JSONArray) {
        val entities = buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                val createdAtMillis = runCatching {
                    OffsetDateTime.parse(obj.optString("created_at")).toInstant().toEpochMilli()
                }.getOrDefault(0L)
                add(
                    CachedRegisterEntryEntity(
                        id = id,
                        year = year,
                        registerType = registerType,
                        officialNumber = if (obj.isNull("official_number")) null else obj.optLong("official_number"),
                        createdAtMillis = createdAtMillis,
                        encryptedPayload = cipher.encrypt(obj.toString()),
                    )
                )
            }
        }
        dao.replaceCached(year, registerType, entities)
    }

    suspend fun cachedRemote(year: Int, registerType: String): List<JSONObject> =
        dao.cachedEntries(year, registerType).mapNotNull { entity ->
            runCatching { JSONObject(cipher.decrypt(entity.encryptedPayload)) }.getOrNull()
        }

    suspend fun savePending(localId: String, payload: JSONObject) {
        dao.upsertPending(
            PendingRegisterEntryEntity(
                localId = localId,
                year = payload.optInt("year"),
                registerType = payload.optString("registerType"),
                createdAtMillis = System.currentTimeMillis(),
                encryptedPayload = cipher.encrypt(payload.toString()),
            )
        )
    }

    suspend fun pending(year: Int, registerType: String): List<PendingItem> =
        dao.pendingEntries(year, registerType).mapNotNull(::decodePending)

    suspend fun allPending(): List<PendingItem> =
        dao.allPendingEntries().mapNotNull(::decodePending)

    suspend fun markPendingClosed(localId: String): Boolean {
        val entity = dao.pendingById(localId) ?: return false
        val payload = runCatching { JSONObject(cipher.decrypt(entity.encryptedPayload)) }.getOrNull()
            ?: return false
        payload.put("movementClosed", true)
        dao.upsertPending(
            entity.copy(encryptedPayload = cipher.encrypt(payload.toString()))
        )
        return true
    }

    suspend fun removePending(localId: String): Boolean {
        val exists = dao.pendingById(localId) != null
        if (exists) dao.deletePending(localId)
        return exists
    }

    private fun decodePending(entity: PendingRegisterEntryEntity): PendingItem? = runCatching {
        PendingItem(
            localId = entity.localId,
            payload = JSONObject(cipher.decrypt(entity.encryptedPayload)),
        )
    }.getOrNull()

    data class PendingItem(
        val localId: String,
        val payload: JSONObject,
    )

    companion object {
        private const val MIGRATION_V1_DONE = "pending_secure_store_to_room_v1"
        private const val LEGACY_PENDING_KEY = "pending"
    }
}
