package dev.accountguard.data.db

import androidx.room.*
import dev.accountguard.data.model.VerificationResult
import dev.accountguard.data.model.VisibilityState

// ─────────────────────────────────────────────────────────────
// ENTITIES
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "account_type") val accountType: String = "com.google",
    @ColumnInfo(name = "db_id") val dbId: Long,    // _id from accounts_de.db
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "policies",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["account_id", "target_package"], unique = true)]
)
data class PolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "account_name") val accountName: String,  // denormalized for fast queries
    @ColumnInfo(name = "target_package") val targetPackage: String,
    @ColumnInfo(name = "visibility_state") val visibilityState: VisibilityState,
    @ColumnInfo(name = "applied_at") val appliedAt: Long = 0,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long = 0,
    @ColumnInfo(name = "verification_result") val verificationResult: VerificationResult = VerificationResult.NOT_VERIFIED
)

@Entity(tableName = "policy_log")
data class PolicyLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "target_package") val targetPackage: String,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "result") val result: String,
    @ColumnInfo(name = "details") val details: String = ""
)

// ─────────────────────────────────────────────────────────────
// TYPE CONVERTERS
// ─────────────────────────────────────────────────────────────

class Converters {
    @TypeConverter
    fun fromVisibilityState(value: VisibilityState): Int = value.dbValue

    @TypeConverter
    fun toVisibilityState(value: Int): VisibilityState =
        VisibilityState.entries.first { it.dbValue == value }

    @TypeConverter
    fun fromVerificationResult(value: VerificationResult): String = value.name

    @TypeConverter
    fun toVerificationResult(value: String): VerificationResult =
        VerificationResult.valueOf(value)
}

// ─────────────────────────────────────────────────────────────
// DAOs
// ─────────────────────────────────────────────────────────────

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY account_name ASC")
    fun getAllAccounts(): kotlinx.coroutines.flow.Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE account_name = :name LIMIT 1")
    suspend fun findByName(name: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity): Long

    @Delete
    suspend fun deleteAccount(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE account_name = :name")
    suspend fun deleteByName(name: String)
}

@Dao
interface PolicyDao {
    @Query("SELECT * FROM policies WHERE account_name = :accountName")
    fun getPoliciesForAccount(accountName: String): kotlinx.coroutines.flow.Flow<List<PolicyEntity>>

    @Query("SELECT * FROM policies")
    fun getAllPolicies(): kotlinx.coroutines.flow.Flow<List<PolicyEntity>>

    @Query("SELECT * FROM policies WHERE account_name = :accountName AND target_package = :targetPackage LIMIT 1")
    suspend fun getPolicy(accountName: String, targetPackage: String): PolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: PolicyEntity): Long

    @Query("DELETE FROM policies WHERE account_name = :accountName")
    suspend fun deletePoliciesForAccount(accountName: String)

    @Query("DELETE FROM policies WHERE account_name = :accountName AND target_package = :targetPackage")
    suspend fun deletePolicy(accountName: String, targetPackage: String)

    @Query("UPDATE policies SET verification_result = :result, last_verified_at = :time WHERE account_name = :accountName AND target_package = :targetPackage")
    suspend fun updateVerificationResult(accountName: String, targetPackage: String, result: VerificationResult, time: Long)

    @Query("SELECT COUNT(*) FROM policies WHERE account_name = :accountName AND visibility_state = 4")
    suspend fun countHiddenPolicies(accountName: String): Int
}

@Dao
interface LogDao {
    @Query("SELECT * FROM policy_log ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): kotlinx.coroutines.flow.Flow<List<PolicyLogEntity>>

    @Insert
    suspend fun insertLog(log: PolicyLogEntity)

    @Query("DELETE FROM policy_log WHERE timestamp < :cutoff")
    suspend fun pruneOldLogs(cutoff: Long)
}

// ─────────────────────────────────────────────────────────────
// DATABASE
// ─────────────────────────────────────────────────────────────

@Database(
    entities = [AccountEntity::class, PolicyEntity::class, PolicyLogEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PolicyDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun policyDao(): PolicyDao
    abstract fun logDao(): LogDao

    companion object {
        const val DATABASE_NAME = "accountguard_policy.db"
    }
}
