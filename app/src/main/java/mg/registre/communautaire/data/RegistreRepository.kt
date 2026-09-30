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
import java.time.ZoneOffset
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
import mg.registre.communautaire.reminder.PermissionWorkflowNotifier
import mg.registre.communautaire.reminder.ReminderScheduler
import org.json.JSONArray
import org.json.JSONObject

class RegistreRepository(private val context: Context) {
    private val pendingPrefs = context.getSharedPreferences("supabase_pending_entries", Context.MODE_PRIVATE)
    private val devicePrefs = context.getSharedPreferences("registre_device", Context.MODE_PRIVATE)
    private val notificationPrefs = context.getSharedPreferences("community_notifications", Context.MODE_PRIVATE)

    val deviceId: String by lazy {
        devicePrefs.getString(DEVICE_ID_KEY, null)
            ?: UUID.randomUUID().toString().also {
                devicePrefs.edit().putString(DEVICE_ID_KEY, it).apply()
            }
    }

    fun observeEntries(year: Int, type: RegisterType): Flow<List<RegisterEntry>> = flow {
        var lastRemote = emptyList<RegisterEntry>()

        while (currentCoroutineContext().isActive) {
            val remote = runCatching {
                withContext(Dispatchers.IO) { fetchRemoteEntries(year, type) }
            }.getOrElse { lastRemote }

            if (remote.isNotEmpty() || lastRemote.isEmpty()) lastRemote = remote

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

    fun observeOpenMovements(year: Int): Flow<List<RegisterEntry>> = flow {
        var last = emptyList<RegisterEntry>()

        while (currentCoroutineContext().isActive) {
            val current = runCatching {
                withContext(Dispatchers.IO) {
                    val remote = fetchRemoteEntries(year, RegisterType.R2)
                    val pending = loadPendingEntries(year, RegisterType.R2)
                    val all = remote + pending
                    val completedMovementIds = all
                        .filter {
                            it.messageKind == RegisterEntry.MESSAGE_AVAILABILITY &&
                                it.status != RegisterEntry.STATUS_CANCELLED &&
                                !it.isDeleted
                        }
                        .map { it.relatedMovementId }
                        .filter { it.isNotBlank() }
                        .toSet()

                    all.filter {
                        it.messageKind == RegisterEntry.MESSAGE_MOVEMENT &&
                            it.status != RegisterEntry.STATUS_CANCELLED &&
                            !it.isDeleted &&
                            it.id !in completedMovementIds
                    }.sortedByDescending { it.createdAtMillis }
                }
            }.getOrElse { last }

            last = current
            emit(current)
            delay(4_000)
        }
    }

    suspend fun checkIntegrity(year: Int, type: RegisterType): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("p_year", year)
            .put("p_register_type", type.code)
            .toString()
        val response = request("POST", "/rest/v1/rpc/registre_integrity_check", body)
        val obj = JSONObject(response)
        obj.optBoolean("ok", false)
    }

    suspend fun createStandard(year: Int, type: RegisterType, input: StandardEntryInput): String {
        val entryId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("entryId", entryId)
            .put("creatorDeviceId", deviceId)
            .put("year", year)
            .put("registerType", type.code)
            .put("pieceNumber", input.pieceNumber.trim())
            .put("pieceDate", input.pieceDate)
            .put("origin", input.origin.trim())
            .put("label", input.label.trim())
            .put("observation", input.observation.trim())
            .put("movementKind", input.movementKind)
            .put("durationDays", input.durationDays)
            .put("durationIndefinite", input.durationIndefinite)
            .put("beneficiary", input.beneficiary.trim())
            .put("departureDate", input.departureDate)
            .put("arrivalDate", input.arrivalDate)
            .put("messageKind", input.messageKind)
            .put("relatedMovementId", input.relatedMovementId)
            .put("relatedPermissionId", input.relatedPermissionId)

        if (input.messageKind == RegisterEntry.MESSAGE_AVAILABILITY) {
            withContext(Dispatchers.IO) { postEntry(payload) }
            if (input.relatedMovementId.isNotBlank()) {
                ReminderScheduler.cancelAvailability(context, input.relatedMovementId)
            }
        } else {
            createOrQueue(entryId, payload)
        }

        if (input.messageKind == RegisterEntry.MESSAGE_MOVEMENT || input.movementKind.isNotBlank()) {
            scheduleMovementReminder(
                entryId = entryId,
                person = input.beneficiary.ifBlank { input.label },
                kind = input.movementKind.ifBlank { "déplacement" },
                departureDate = input.departureDate,
                arrivalDate = input.arrivalDate,
                durationDays = input.durationDays,
                indefinite = input.durationIndefinite,
            )
        }
        return entryId
    }

    suspend fun createPermission(year: Int, input: PermissionEntryInput): String {
        val entryId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("entryId", entryId)
            .put("creatorDeviceId", deviceId)
            .put("year", year)
            .put("registerType", RegisterType.R3PERM.code)
            .put("grade", input.grade.trim())
            .put("fullName", input.fullName.trim())
            .put("matricule", input.matricule.trim())
            .put("numberR3", input.numberR3.trim())
            .put("departureDate", input.departureDate)
            .put("arrivalDate", input.arrivalDate)
            .put("annualRightYear", input.annualRightYear)
            .put("consumedRightDetail", input.consumedRightDetail.trim())
            .put("durationDays", input.durationDays)
            .put("durationIndefinite", input.durationIndefinite)
            .put("movementKind", "permission")
            .put("messageKind", RegisterEntry.MESSAGE_PERMISSION)
            .put("beneficiary", input.fullName.trim())

        createOrQueue(entryId, payload)

        scheduleMovementReminder(
            entryId = entryId,
            person = input.fullName,
            kind = "permission",
            departureDate = input.departureDate,
            arrivalDate = input.arrivalDate,
            durationDays = input.durationDays,
            indefinite = input.durationIndefinite,
        )

        PermissionWorkflowNotifier.notifyMovementRequired(
            context = context,
            permissionId = entryId,
            person = input.fullName,
        )
        return entryId
    }

    suspend fun cancelEntry(entryId: String, reason: String) {
        if (removePendingIfPresent(entryId)) {
            ReminderScheduler.cancelAvailability(context, entryId)
            return
        }

        val before = withContext(Dispatchers.IO) { fetchEntryById(entryId) }
        val body = JSONObject()
            .put("p_entry_id", entryId)
            .put("p_device_id", deviceId)
            .put("p_reason", reason.trim())
            .toString()

        withContext(Dispatchers.IO) {
            request("POST", "/rest/v1/rpc/registre_cancel_entry", body)
        }

        ReminderScheduler.cancelAvailability(context, entryId)
        if (before?.messageKind == RegisterEntry.MESSAGE_AVAILABILITY) {
            restoreMovementReminder(before.relatedMovementId)
        }
    }

    suspend fun deleteEntry(entryId: String) {
        if (removePendingIfPresent(entryId)) {
            ReminderScheduler.cancelAvailability(context, entryId)
            return
        }

        val before = withContext(Dispatchers.IO) { fetchEntryById(entryId) }
        val body = JSONObject()
            .put("p_entry_id", entryId)
            .put("p_device_id", deviceId)
            .toString()

        withContext(Dispatchers.IO) {
            request("POST", "/rest/v1/rpc/registre_delete_entry", body)
        }

        ReminderScheduler.cancelAvailability(context, entryId)
        if (before?.messageKind == RegisterEntry.MESSAGE_AVAILABILITY) {
            restoreMovementReminder(before.relatedMovementId)
        }
    }

    suspend fun closeMovement(entryId: String) {
        if (markPendingClosed(entryId)) {
            ReminderScheduler.cancelAvailability(context, entryId)
            return
        }

        addLocallyClosed(entryId)
        ReminderScheduler.cancelAvailability(context, entryId)

        try {
            withContext(Dispatchers.IO) { closeMovementRemote(entryId) }
            removeLocallyClosed(entryId)
        } catch (error: SupabaseHttpException) {
            removeLocallyClosed(entryId)
            throw error
        } catch (_: IOException) {
            queueClosure(entryId)
            enqueuePendingSync()
        }
    }

    suspend fun syncPendingEntries() {
        val pending = readPendingArray()
        if (pending.length() > 0) {
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

        syncQueuedClosures()
    }

    suspend fun checkCommunityNotifications(): List<RegisterEntry> {
        val cursor = notificationPrefs.getString(LAST_COMMUNITY_CURSOR_KEY, null)
        if (cursor == null) {
            notificationPrefs.edit()
                .putString(LAST_COMMUNITY_CURSOR_KEY, OffsetDateTime.now(ZoneOffset.UTC).toString())
                .apply()
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            val encodedCursor = URLEncoder.encode(cursor, Charsets.UTF_8.name())
            val encodedDevice = URLEncoder.encode(deviceId, Charsets.UTF_8.name())
            val path = "/rest/v1/registre_entries" +
                "?select=*" +
                "&created_at=gt.$encodedCursor" +
                "&creator_device_id=neq.$encodedDevice" +
                "&deleted_at=is.null" +
                "&order=created_at.asc"

            val response = request("GET", path, null)
            val array = JSONArray(response)
            if (array.length() == 0) return@withContext emptyList()

            val result = buildList {
                for (index in 0 until array.length()) {
                    add(fromRemoteJson(array.getJSONObject(index)))
                }
            }
            val lastCreatedAt = array.getJSONObject(array.length() - 1).optString("created_at")
            if (lastCreatedAt.isNotBlank()) {
                notificationPrefs.edit().putString(LAST_COMMUNITY_CURSOR_KEY, lastCreatedAt).apply()
            }
            result
        }
    }

    private fun scheduleMovementReminder(
        entryId: String,
        person: String,
        kind: String,
        departureDate: String,
        arrivalDate: String,
        durationDays: Int,
        indefinite: Boolean,
    ) {
        if (departureDate.isBlank()) return

        if (indefinite) {
            ReminderScheduler.scheduleIndefiniteAvailability(
                context,
                entryId,
                person,
                kind,
                departureDate,
            )
        } else if (durationDays > 0 && arrivalDate.isNotBlank()) {
            ReminderScheduler.scheduleAvailability(
                context,
                entryId,
                person,
                kind,
                arrivalDate,
            )
        }
    }

    private suspend fun restoreMovementReminder(movementId: String) {
        if (movementId.isBlank()) return
        val movement = withContext(Dispatchers.IO) { fetchEntryById(movementId) } ?: return
        if (!movement.isActive || movement.messageKind != RegisterEntry.MESSAGE_MOVEMENT) return

        scheduleMovementReminder(
            entryId = movement.id,
            person = movement.beneficiary.ifBlank { movement.label },
            kind = movement.movementKind.ifBlank { "déplacement" },
            departureDate = movement.departureDate,
            arrivalDate = movement.arrivalDate,
            durationDays = movement.durationDays,
            indefinite = movement.durationIndefinite,
        )
    }

    private suspend fun createOrQueue(entryId: String, payload: JSONObject) {
        try {
            withContext(Dispatchers.IO) { postEntry(payload) }
        } catch (error: SupabaseHttpException) {
            throw error
        } catch (_: IOException) {
            savePending(entryId, payload)
            enqueuePendingSync()
        }
    }

    private fun fetchRemoteEntries(year: Int, type: RegisterType): List<RegisterEntry> {
        check(SupabaseConfig.isConfigured()) { "Configuration Supabase absente." }
        val encodedType = URLEncoder.encode(type.code, Charsets.UTF_8.name())
        val path = "/rest/v1/registre_entries" +
            "?select=*" +
            "&year=eq.$year" +
            "&register_type=eq.$encodedType" +
            "&deleted_at=is.null" +
            "&order=official_number.asc"

        val response = request("GET", path, null)
        val array = JSONArray(response)
        return buildList {
            for (index in 0 until array.length()) add(fromRemoteJson(array.getJSONObject(index)))
        }
    }

    private fun fetchEntryById(entryId: String): RegisterEntry? {
        val encoded = URLEncoder.encode(entryId, Charsets.UTF_8.name())
        val response = request(
            "GET",
            "/rest/v1/registre_entries?select=*&id=eq.$encoded&limit=1",
            null,
        )
        val array = JSONArray(response)
        return if (array.length() > 0) fromRemoteJson(array.getJSONObject(0)) else null
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

    private fun closeMovementRemote(entryId: String) {
        val body = JSONObject()
            .put("p_entry_id", entryId)
            .put("p_device_id", deviceId)
            .toString()
        request("POST", "/rest/v1/rpc/registre_close_movement", body)
    }

    private fun request(method: String, path: String, body: String?): String {
        val connection = URL(SupabaseConfig.url + path).openConnection() as HttpURLConnection
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
                val message = runCatching {
                    JSONObject(response).optString("message").ifBlank { response }
                }.getOrDefault(response)
                throw SupabaseHttpException(
                    status,
                    message.ifBlank { "Erreur Supabase HTTP $status" },
                )
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
        val id = obj.optString("id")
        val remoteClosed = nullableString(obj, "movement_closed_at")
        val locallyClosed = isLocallyClosed(id)

        return RegisterEntry(
            id = id,
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
            annualRightYear = obj.optInt("annual_right_year", obj.optInt("annual_right")),
            consumedRightDetail = obj.optString("consumed_right_detail").ifBlank {
                obj.optInt("consumed_right").takeIf { it > 0 }?.toString().orEmpty()
            },
            movementKind = obj.optString("movement_kind"),
            durationDays = obj.optInt("duration_days"),
            durationIndefinite = obj.optBoolean("duration_indefinite", false),
            movementClosedAt = if (locallyClosed) "local" else remoteClosed,
            beneficiary = obj.optString("beneficiary"),
            creatorDeviceId = obj.optString("creator_device_id"),
            messageKind = obj.optString("message_kind").ifBlank { RegisterEntry.MESSAGE_ORDINARY },
            relatedMovementId = nullableString(obj, "related_movement_id"),
            relatedPermissionId = nullableString(obj, "related_permission_id"),
            cancelledAt = nullableString(obj, "cancelled_at"),
            cancelledReason = obj.optString("cancelled_reason"),
            deletedAt = nullableString(obj, "deleted_at"),
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
            annualRightYear = payload.optInt("annualRightYear"),
            consumedRightDetail = payload.optString("consumedRightDetail"),
            movementKind = payload.optString("movementKind"),
            durationDays = payload.optInt("durationDays"),
            durationIndefinite = payload.optBoolean("durationIndefinite", false),
            movementClosedAt = if (payload.optBoolean("movementClosed", false)) "local" else "",
            beneficiary = payload.optString("beneficiary"),
            creatorDeviceId = payload.optString("creatorDeviceId"),
            messageKind = payload.optString("messageKind").ifBlank { RegisterEntry.MESSAGE_ORDINARY },
            relatedMovementId = payload.optString("relatedMovementId"),
            relatedPermissionId = payload.optString("relatedPermissionId"),
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
    private fun markPendingClosed(entryId: String): Boolean {
        val source = readPendingArray()
        var changed = false
        for (index in 0 until source.length()) {
            val item = source.getJSONObject(index)
            if (item.optString("localId") == entryId) {
                item.getJSONObject("payload").put("movementClosed", true)
                changed = true
                break
            }
        }
        if (changed) pendingPrefs.edit().putString(PENDING_KEY, source.toString()).apply()
        return changed
    }

    @Synchronized
    private fun removePendingIfPresent(entryId: String): Boolean {
        val source = readPendingArray()
        var found = false
        val target = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.getJSONObject(index)
            if (item.optString("localId") == entryId) {
                found = true
            } else {
                target.put(item)
            }
        }
        if (found) pendingPrefs.edit().putString(PENDING_KEY, target.toString()).apply()
        return found
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

    private fun queueClosure(entryId: String) {
        val set = pendingPrefs.getStringSet(PENDING_CLOSURES_KEY, emptySet()).orEmpty().toMutableSet()
        set += entryId
        pendingPrefs.edit().putStringSet(PENDING_CLOSURES_KEY, set).apply()
        addLocallyClosed(entryId)
    }

    private suspend fun syncQueuedClosures() {
        val closures = pendingPrefs.getStringSet(PENDING_CLOSURES_KEY, emptySet()).orEmpty().toList()
        if (closures.isEmpty()) return

        val completed = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            for (entryId in closures) {
                closeMovementRemote(entryId)
                completed += entryId
            }
        }

        val remaining = closures.toMutableSet().apply { removeAll(completed.toSet()) }
        pendingPrefs.edit().putStringSet(PENDING_CLOSURES_KEY, remaining).apply()
        completed.forEach(::removeLocallyClosed)
    }

    private fun addLocallyClosed(entryId: String) {
        val set = pendingPrefs.getStringSet(LOCAL_CLOSED_KEY, emptySet()).orEmpty().toMutableSet()
        set += entryId
        pendingPrefs.edit().putStringSet(LOCAL_CLOSED_KEY, set).apply()
    }

    private fun removeLocallyClosed(entryId: String) {
        val set = pendingPrefs.getStringSet(LOCAL_CLOSED_KEY, emptySet()).orEmpty().toMutableSet()
        set -= entryId
        pendingPrefs.edit().putStringSet(LOCAL_CLOSED_KEY, set).apply()
    }

    private fun isLocallyClosed(entryId: String): Boolean =
        pendingPrefs.getStringSet(LOCAL_CLOSED_KEY, emptySet()).orEmpty().contains(entryId)

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
        private const val PENDING_CLOSURES_KEY = "pending_closures"
        private const val LOCAL_CLOSED_KEY = "locally_closed"
        private const val DEVICE_ID_KEY = "device_id"
        private const val LAST_COMMUNITY_CURSOR_KEY = "last_created_at"
    }
}

private class SupabaseHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)
