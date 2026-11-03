package mg.registre.communautaire.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "cached_register_entries")
data class CachedRegisterEntryEntity(
    @PrimaryKey val id: String,
    val year: Int,
    val registerType: String,
    val officialNumber: Long?,
    val createdAtMillis: Long,
    val encryptedPayload: String,
)

@Entity(tableName = "pending_register_entries")
data class PendingRegisterEntryEntity(
    @PrimaryKey val localId: String,
    val year: Int,
    val registerType: String,
    val createdAtMillis: Long,
    val encryptedPayload: String,
)

@Dao
interface OfflineEntryDao {
    @Query(
        "SELECT * FROM cached_register_entries " +
            "WHERE year = :year AND registerType = :registerType " +
            "ORDER BY officialNumber ASC, createdAtMillis ASC"
    )
    suspend fun cachedEntries(year: Int, registerType: String): List<CachedRegisterEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCached(entries: List<CachedRegisterEntryEntity>)

    @Query("DELETE FROM cached_register_entries WHERE year = :year AND registerType = :registerType")
    suspend fun clearCached(year: Int, registerType: String)

    @Transaction
    suspend fun replaceCached(
        year: Int,
        registerType: String,
        entries: List<CachedRegisterEntryEntity>,
    ) {
        clearCached(year, registerType)
        if (entries.isNotEmpty()) upsertCached(entries)
    }

    @Query(
        "SELECT * FROM pending_register_entries " +
            "WHERE year = :year AND registerType = :registerType " +
            "ORDER BY createdAtMillis ASC"
    )
    suspend fun pendingEntries(year: Int, registerType: String): List<PendingRegisterEntryEntity>

    @Query("SELECT * FROM pending_register_entries ORDER BY createdAtMillis ASC")
    suspend fun allPendingEntries(): List<PendingRegisterEntryEntity>

    @Query("SELECT * FROM pending_register_entries WHERE localId = :localId LIMIT 1")
    suspend fun pendingById(localId: String): PendingRegisterEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPending(entry: PendingRegisterEntryEntity)

    @Query("DELETE FROM pending_register_entries WHERE localId = :localId")
    suspend fun deletePending(localId: String)
}

@Database(
    entities = [CachedRegisterEntryEntity::class, PendingRegisterEntryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OfflineDatabase : RoomDatabase() {
    abstract fun entryDao(): OfflineEntryDao

    companion object {
        @Volatile private var instance: OfflineDatabase? = null

        fun get(context: Context): OfflineDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OfflineDatabase::class.java,
                "registre_offline.db",
            ).build().also { instance = it }
        }
    }
}
