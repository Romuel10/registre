package mg.registre.communautaire.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import mg.registre.communautaire.domain.MovementRules
import mg.registre.communautaire.domain.PermissionEntryInput
import mg.registre.communautaire.domain.RegisterEntry
import mg.registre.communautaire.domain.RegisterType
import mg.registre.communautaire.domain.StandardEntryInput
import mg.registre.communautaire.reminder.NumberReservationWorker
import mg.registre.communautaire.reminder.ReminderScheduler

class RegistreRepository(private val context: Context) {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    suspend fun ensureSignedIn() {
        if (auth.currentUser == null) auth.signInAnonymously().await()
    }

    fun observeEntries(year: Int, type: RegisterType): Flow<List<RegisterEntry>> = callbackFlow {
        if (!FirebaseBootstrap.initialize(context)) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val registration = db.collection("entries")
            .whereEqualTo("year", year)
            .whereEqualTo("registerType", type.code)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val values = snapshot?.documents.orEmpty()
                    .map { fromMap(it.id, it.data.orEmpty()) }
                    .sortedWith(
                        compareBy<RegisterEntry> { it.officialNumber == null }
                            .thenBy { it.officialNumber ?: Long.MAX_VALUE }
                            .thenBy { it.createdAtMillis }
                    )
                trySend(values)
            }

        awaitClose { registration.remove() }
    }

    suspend fun createStandard(year: Int, type: RegisterType, input: StandardEntryInput): String {
        ensureSignedIn()
        val ref = db.collection("entries").document()
        val payload = mapOf<String, Any?>(
            "year" to year,
            "registerType" to type.code,
            "officialNumber" to null,
            "displayNumber" to "",
            "status" to RegisterEntry.STATUS_PENDING,
            "createdAtMillis" to System.currentTimeMillis(),
            "createdBy" to auth.currentUser?.uid.orEmpty(),
            "pieceNumber" to input.pieceNumber.trim(),
            "pieceDate" to input.pieceDate,
            "origin" to input.origin.trim(),
            "label" to input.label.trim(),
            "observation" to input.observation.trim(),
            "movementKind" to input.movementKind,
            "durationDays" to input.durationDays,
            "beneficiary" to input.beneficiary.trim(),
            "departureDate" to input.departureDate,
            "arrivalDate" to input.arrivalDate,
        )

        ref.set(payload)
        enqueueNumberReservation(ref.id)

        if (input.durationDays > 0 && input.arrivalDate.isNotBlank()) {
            ReminderScheduler.scheduleAvailability(
                context,
                ref.id,
                input.beneficiary.ifBlank { input.label },
                input.movementKind.ifBlank { "déplacement" },
                input.arrivalDate,
            )
        }
        return ref.id
    }

    suspend fun createPermission(year: Int, input: PermissionEntryInput): String {
        ensureSignedIn()
        val ref = db.collection("entries").document()
        val payload = mapOf<String, Any?>(
            "year" to year,
            "registerType" to RegisterType.R3PERM.code,
            "officialNumber" to null,
            "displayNumber" to "",
            "status" to RegisterEntry.STATUS_PENDING,
            "createdAtMillis" to System.currentTimeMillis(),
            "createdBy" to auth.currentUser?.uid.orEmpty(),
            "grade" to input.grade.trim(),
            "fullName" to input.fullName.trim(),
            "matricule" to input.matricule.trim(),
            "numberR3" to input.numberR3.trim(),
            "departureDate" to input.departureDate,
            "arrivalDate" to input.arrivalDate,
            "annualRight" to input.annualRight,
            "consumedRight" to input.consumedRight,
            "durationDays" to input.durationDays,
            "movementKind" to "permission",
            "beneficiary" to input.fullName.trim(),
        )

        ref.set(payload)
        enqueueNumberReservation(ref.id)
        ReminderScheduler.scheduleAvailability(
            context,
            ref.id,
            input.fullName,
            "permission",
            input.arrivalDate,
        )
        return ref.id
    }

    suspend fun reserveOfficialNumber(entryId: String) {
        ensureSignedIn()
        db.waitForPendingWrites().await()

        val entryRef = db.collection("entries").document(entryId)
        db.runTransaction { transaction ->
            val entry = transaction.get(entryRef)
            if (!entry.exists()) error("Entrée introuvable.")

            entry.getLong("officialNumber")?.let { return@runTransaction it }

            val year = entry.getLong("year")?.toInt() ?: error("Année absente.")
            val type = RegisterType.fromCode(entry.getString("registerType").orEmpty())
            val counterRef = db.collection("counters")
                .document(year.toString() + "_" + counterKey(type))
            val counter = transaction.get(counterRef)
            val next = counter.getLong("next") ?: 1L

            transaction.set(
                counterRef,
                mapOf(
                    "year" to year,
                    "registerType" to type.code,
                    "next" to next + 1L,
                    "updatedAtMillis" to System.currentTimeMillis(),
                ),
                SetOptions.merge(),
            )
            transaction.update(
                entryRef,
                mapOf(
                    "officialNumber" to next,
                    "displayNumber" to MovementRules.displayNumber(next, type),
                    "status" to RegisterEntry.STATUS_NUMBERED,
                )
            )
            next
        }.await()
    }

    private fun enqueueNumberReservation(entryId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<NumberReservationWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString(NumberReservationWorker.KEY_ENTRY_ID, entryId).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "number_" + entryId,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun counterKey(type: RegisterType): String =
        type.code.replace("/", "r").replace(".", "_")

    private fun fromMap(id: String, map: Map<String, Any?>): RegisterEntry =
        RegisterEntry(
            id = id,
            year = (map["year"] as? Number)?.toInt() ?: 0,
            registerType = map["registerType"] as? String ?: "",
            officialNumber = (map["officialNumber"] as? Number)?.toLong(),
            displayNumber = map["displayNumber"] as? String ?: "",
            status = map["status"] as? String ?: RegisterEntry.STATUS_PENDING,
            createdAtMillis = (map["createdAtMillis"] as? Number)?.toLong() ?: 0L,
            createdBy = map["createdBy"] as? String ?: "",
            pieceNumber = map["pieceNumber"] as? String ?: "",
            pieceDate = map["pieceDate"] as? String ?: "",
            origin = map["origin"] as? String ?: "",
            label = map["label"] as? String ?: "",
            observation = map["observation"] as? String ?: "",
            grade = map["grade"] as? String ?: "",
            fullName = map["fullName"] as? String ?: "",
            matricule = map["matricule"] as? String ?: "",
            numberR3 = map["numberR3"] as? String ?: "",
            departureDate = map["departureDate"] as? String ?: "",
            arrivalDate = map["arrivalDate"] as? String ?: "",
            annualRight = (map["annualRight"] as? Number)?.toInt() ?: 0,
            consumedRight = (map["consumedRight"] as? Number)?.toInt() ?: 0,
            movementKind = map["movementKind"] as? String ?: "",
            durationDays = (map["durationDays"] as? Number)?.toInt() ?: 0,
            beneficiary = map["beneficiary"] as? String ?: "",
        )
}
