package mg.registre.communautaire.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import mg.registre.communautaire.domain.PermissionEntryInput
import mg.registre.communautaire.domain.RegisterEntry
import mg.registre.communautaire.domain.RegisterType
import mg.registre.communautaire.domain.StandardEntryInput
import mg.registre.communautaire.reminder.NumberReservationWorker
import mg.registre.communautaire.reminder.ReminderScheduler
import org.json.JSONArray
import org.json.JSONObject

class RegistreRepository(private val context: Context) {
    private val pendingPrefs = context.getSharedPreferences("supabase_pending_entries", Context.MODE_PRIVATE)

    fun observeEntries(year: Int, type: RegisterType): Flow<List<RegisterEntry>> = flow {
        var lastRemote = emptyList<RegisterEntry>()

        while (currentCoroutineContext().isActive) {
            val remote = runCatching {
                withContext(Dispatchers.IO) { fetchRemoteEntries(year, type) }
            }.getOrElse { lastRemote }

            if (remote.isNotEmpty() || lastRemote.isEmpty()) {
                lastRemote = remote
            }

            val pending = loadPendingEntries(year, type)
            val combined = (lastRemote + pending).sortedWith(
                compareBy<RegisterEntry> { it.officialNumber == null }
                    .thenBy { it.officialNumber ?: Long.MAX_VALUE }
                    .thenBy { it.createdAtMillis }
            )
            emit(combined)
            delay(4_000)
        }
    }

    suspend fun createStandard(year: Int, type: RegisterType, input: StandardEntryInput): String {
        val payload = JSONObject()
            .put("year", year)
            .put("registerType", type.code)
            .put("pieceNumber", input.pieceNumber.trim())
            .put("pieceDate", input.pieceDate)
            .put("origin", input.origin.trim())
            .put("label", input.label.trim())
            .put("observation", input.observation.trim())
            .put("movementKind", input.movementKind)
            .put("durationDays", input.durationDays)
            .put("beneficiary", input.beneficiary.trim())
            .put("departureDate", input.departureDate)
            .put("arrivalDate", input.arrivalDate)

        val entryId = createOrQueue(payload)

        if (input.durationDays > 0 && input.arrivalDate.isNotBlank()) {
            ReminderScheduler.scheduleAvailability(
                context,
                entryId,
                input.beneficiary.ifBlank { input.label },
                input.movementKind.ifBlank { "déplacement" },
                input.arrivalDate,
            )
        }
        return entryId
    }

    suspend fun createPermission(year: Int, input: PermissionEntryInput): String {
        val payload = JSONObject()
            .put("year", year)
            .put("registerType", RegisterType.R3PERM.code)
            .put("grade", input.grade.trim())
            .put("fullName", input.fullName.trim())
            .put("matricule", input.matricule.trim())
            .put("numberR3", input.numberR3.trim())
            .put("departureDate", input.departureDate)
            .put("arrivalDate", input.arrivalDate)
            .put("annualRight", input.annualRight)
            .put("consumedRight", input.consumedRight)
            .put("durationDays", input.durationDays)
            .put("movementKind", "permission")
            .put("beneficiary", input.fullName.trim())

        val entryId = createOrQueue(payload)

        ReminderScheduler.scheduleAvailability(
            context,
            entryId,
            input.fullName,
            "permission",
            input.arrivalDate,
        )
        return entryId
    }

    suspend fun syncPendingEntries() {
        val pending = readPendingArray()
        if (pending.length() == 0) return

        val completed = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            for (index in 0 until pending.length()) {
                val item = pending.getJSONObject(index)
                val localId = item.getString("localId")
                val payload = item.getJSONObject("payload")
                postEntry(payload)
                completed += localId
            }
        }
        completed.forEach(::removePending)
    }

    private suspend fun createOrQueue(payload: JSONObject): String {
        return try {
            withContext(Dispatchers.IO) { postEntry(payload).id }
        } catch (error: SupabaseHttpException) {
            throw error
        } catch (error: IOException) {
            val localId = "local-" + UUID.randomUUID().toString()
            savePending(localId, payload)
            enqueuePendingSync()
            localId
        }
    }

    private fun fetchRemoteEntries(year: Int, type: RegisterType): List<RegisterEntry> {
        check(SupabaseConfig.isConfigured()) { "Configuration Supabase absente." }
        val encodedType = URLEncoder.encode(type.code, Charsets.UTF_8.name())
        val path = "/rest/v1/registre_entries" +
            "?select=*" +
            "&year=eq.$year" +
            "&register_type=eq.$encodedType" +
            "&order=official_number.asc"

        val response = request("GET", path, null)
        val array = JSONArray(response)
        return buildList {
            for (index in 0 until array.length()) {
                add(fromRemoteJson(array.getJSONObject(index)))
            }
        }
    }

    private fun postEntry(payload: JSONObject): RegisterEntry {
        check(SupabaseConfig.isConfigured()) { "Configuration Supabase absente." }
        val body = JSONObject().put("p_payload", payload).toString()
        val response = request("POST", "/rest/v1/rpc/registre_create_entry", body).trim()

        val obj = when {
            response.startsWith("[") -> JSONArray(response).getJSONObject(0)
            else -> JSONObject(response)
        }
        return fromRemoteJson(obj)
    }

    private fun request(method: String, path: String, body: String?): String {
        val connection = (URL(SupabaseConfig.url + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("apikey", SupabaseConfig.publishableKey)
            connection.setRequestProperty("Accept", "application/json")

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (status !in 200..299) {
                throw SupabaseHttpException(status, response.ifBlank { "Erreur Supabase HTTP $status" })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun fromRemoteJson(obj: JSONObject): RegisterEntry {
        val createdAt = obj.optString("created_at")
        val createdAtMillis = runCatching {
            OffsetDateTime.parse(createdAt).toInstant().toEpochMilli()
        }.getOrDefault(0L)

        return RegisterEntry(
            id = obj.optString("id"),
            year = obj.optInt("year"),
            registerType = obj.optString("register_type"),
            officialNumber = if (obj.isNull("official_number")) null else obj.optLong("official_number"),
            displayNumber = obj.optString("display_number"),
            status = obj.optString("status", RegisterEntry.STATUS_NUMBERED),
            createdAtMillis = createdAtMillis,
            createdBy = nullableString(obj, "created_by"),
            pieceNumber = obj.optString("piece_number"),
            pieceDate = nullableString(obj, "piece_date"),
            origin = obj.optString("origin"),
            label = obj.optString("label"),
            observation = obj.optString("observation"),
            grade = obj.optString("grade"),
            fullName = obj.optString("full_name"),
            matricule = obj.optString("matricule"),
            numberR3 = obj.optString("number_r3"),
            departureDate = nullableString(obj, "departure_date"),
            arrivalDate = nullableString(obj, "arrival_date"),
            annualRight = obj.optInt("annual_right"),
            consumedRight = obj.optInt("consumed_right"),
            movementKind = obj.optString("movement_kind"),
            durationDays = obj.optInt("duration_days"),
            beneficiary = obj.optString("beneficiary"),
        )
    }

    private fun loadPendingEntries(year: Int, type: RegisterType): List<RegisterEntry> {
        val array = readPendingArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val payload = item.getJSONObject("payload")
                if (payload.optInt("year") == year && payload.optString("registerType") == type.code) {
                    add(fromPendingJson(item.getString("localId"), payload))
                }
            }
        }
    }

    private fun fromPendingJson(localId: String, payload: JSONObject): RegisterEntry =
        RegisterEntry(
            id = localId,
            year = payload.optInt("year"),
            registerType = payload.optString("registerType"),
            officialNumber = null,
            displayNumber = "",
            status = RegisterEntry.STATUS_PENDING,
            createdAtMillis = System.currentTimeMillis(),
            pieceNumber = payload.optString("pieceNumber"),
            pieceDate = payload.optString("pieceDate"),
            origin = payload.optString("origin"),
            label = payload.optString("label"),
            observation = payload.optString("observation"),
            grade = payload.optString("grade"),
            fullName = payload.optString("fullName"),
            matricule = payload.optString("matricule"),
            numberR3 = payload.optString("numberR3"),
            departureDate = payload.optString("departureDate"),
            arrivalDate = payload.optString("arrivalDate"),
            annualRight = payload.optInt("annualRight"),
            consumedRight = payload.optInt("consumedRight"),
            movementKind = payload.optString("movementKind"),
            durationDays = payload.optInt("durationDays"),
            beneficiary = payload.optString("beneficiary"),
        )

    @Synchronized
    private fun savePending(localId: String, payload: JSONObject) {
        val array = readPendingArray()
        array.put(
            JSONObject()
                .put("localId", localId)
                .put("payload", JSONObject(payload.toString()))
        )
        pendingPrefs.edit().putString(PENDING_KEY, array.toString()).apply()
    }

    @Synchronized
    private fun removePending(localId: String) {
        val source = readPendingArray()
        val target = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.getJSONObject(index)
            if (item.optString("localId") != localId) target.put(item)
        }
        pendingPrefs.edit().putString(PENDING_KEY, target.toString()).apply()
    }

    @Synchronized
    private fun readPendingArray(): JSONArray =
        runCatching { JSONArray(pendingPrefs.getString(PENDING_KEY, "[]") ?: "[]") }
            .getOrElse { JSONArray() }

    private fun enqueuePendingSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<NumberReservationWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "supabase_pending_sync",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun nullableString(obj: JSONObject, key: String): String =
        if (obj.isNull(key)) "" else obj.optString(key)

    companion object {
        private const val PENDING_KEY = "pending"
    }
}

private class SupabaseHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)
