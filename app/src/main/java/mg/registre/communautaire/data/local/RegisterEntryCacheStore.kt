package mg.registre.communautaire.data.local

import android.content.Context
import mg.registre.communautaire.domain.RegisterEntry
import org.json.JSONObject

class RegisterEntryCacheStore(context: Context) {
    private val dao = OfflineDatabase.get(context).entryDao()
    private val cipher = OfflinePayloadCipher()

    suspend fun replace(year: Int, registerType: String, entries: List<RegisterEntry>) {
        val numbered = entries
            .filter { it.officialNumber != null && it.status != RegisterEntry.STATUS_PENDING }
            .map { entry ->
                CachedRegisterEntryEntity(
                    id = entry.id,
                    year = year,
                    registerType = registerType,
                    officialNumber = entry.officialNumber,
                    createdAtMillis = entry.createdAtMillis,
                    encryptedPayload = cipher.encrypt(encode(entry).toString()),
                )
            }
        dao.replaceCached(year, registerType, numbered)
    }

    suspend fun read(year: Int, registerType: String): List<RegisterEntry> =
        dao.cachedEntries(year, registerType).mapNotNull { entity ->
            runCatching {
                decode(JSONObject(cipher.decrypt(entity.encryptedPayload)))
            }.getOrNull()
        }

    private fun encode(entry: RegisterEntry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("year", entry.year)
        .put("registerType", entry.registerType)
        .put("officialNumber", entry.officialNumber ?: JSONObject.NULL)
        .put("displayNumber", entry.displayNumber)
        .put("status", entry.status)
        .put("createdAtMillis", entry.createdAtMillis)
        .put("createdBy", entry.createdBy)
        .put("pieceNumber", entry.pieceNumber)
        .put("pieceDate", entry.pieceDate)
        .put("origin", entry.origin)
        .put("label", entry.label)
        .put("observation", entry.observation)
        .put("grade", entry.grade)
        .put("fullName", entry.fullName)
        .put("matricule", entry.matricule)
        .put("numberR3", entry.numberR3)
        .put("departureDate", entry.departureDate)
        .put("arrivalDate", entry.arrivalDate)
        .put("annualRightYear", entry.annualRightYear)
        .put("consumedRightDetail", entry.consumedRightDetail)
        .put("movementKind", entry.movementKind)
        .put("durationDays", entry.durationDays)
        .put("durationIndefinite", entry.durationIndefinite)
        .put("movementClosedAt", entry.movementClosedAt)
        .put("beneficiary", entry.beneficiary)
        .put("creatorDeviceId", entry.creatorDeviceId)
        .put("messageKind", entry.messageKind)
        .put("relatedMovementId", entry.relatedMovementId)
        .put("relatedPermissionId", entry.relatedPermissionId)
        .put("cancelledAt", entry.cancelledAt)
        .put("cancelledReason", entry.cancelledReason)
        .put("deletedAt", entry.deletedAt)

    private fun decode(obj: JSONObject): RegisterEntry = RegisterEntry(
        id = obj.optString("id"),
        year = obj.optInt("year"),
        registerType = obj.optString("registerType"),
        officialNumber = if (obj.isNull("officialNumber")) null else obj.optLong("officialNumber"),
        displayNumber = obj.optString("displayNumber"),
        status = obj.optString("status", RegisterEntry.STATUS_NUMBERED),
        createdAtMillis = obj.optLong("createdAtMillis"),
        createdBy = obj.optString("createdBy"),
        pieceNumber = obj.optString("pieceNumber"),
        pieceDate = obj.optString("pieceDate"),
        origin = obj.optString("origin"),
        label = obj.optString("label"),
        observation = obj.optString("observation"),
        grade = obj.optString("grade"),
        fullName = obj.optString("fullName"),
        matricule = obj.optString("matricule"),
        numberR3 = obj.optString("numberR3"),
        departureDate = obj.optString("departureDate"),
        arrivalDate = obj.optString("arrivalDate"),
        annualRightYear = obj.optInt("annualRightYear"),
        consumedRightDetail = obj.optString("consumedRightDetail"),
        movementKind = obj.optString("movementKind"),
        durationDays = obj.optInt("durationDays"),
        durationIndefinite = obj.optBoolean("durationIndefinite"),
        movementClosedAt = obj.optString("movementClosedAt"),
        beneficiary = obj.optString("beneficiary"),
        creatorDeviceId = obj.optString("creatorDeviceId"),
        messageKind = obj.optString("messageKind", RegisterEntry.MESSAGE_ORDINARY),
        relatedMovementId = obj.optString("relatedMovementId"),
        relatedPermissionId = obj.optString("relatedPermissionId"),
        cancelledAt = obj.optString("cancelledAt"),
        cancelledReason = obj.optString("cancelledReason"),
        deletedAt = obj.optString("deletedAt"),
    )
}
