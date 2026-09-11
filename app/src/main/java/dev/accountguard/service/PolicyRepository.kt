package dev.accountguard.service

import android.content.Context
import android.util.Log
import dev.accountguard.data.db.*
import dev.accountguard.data.model.*
import dev.accountguard.root.RootEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * PolicyRepository — the bridge between UI, Room DB, and root engine.
 *
 * All policy read/write operations go through here.
 * This layer is responsible for:
 *   1. Detecting accounts via root
 *   2. Syncing detected accounts into the local policy DB
 *   3. Applying visibility policies via RootEngine
 *   4. Reading/Writing policies to Room
 *   5. Verification and logging
 */
class PolicyRepository(private val db: PolicyDatabase) {

    private val accountDao = db.accountDao()
    private val policyDao = db.policyDao()
    private val logDao = db.logDao()

    private val TAG = "AccountGuard.Repo"

    // ─────────────────────────────────────────────────────────────
    // ACCOUNT DETECTION
    // ─────────────────────────────────────────────────────────────

    /**
     * Detect all Google accounts from the system, sync with local DB.
     * Returns the list of detected accounts.
     */
    suspend fun syncDetectedAccounts(): List<GoogleAccount> {
        val accounts = RootEngine.detectGoogleAccounts()
        log("DETECT", "SYSTEM", "ALL", "PASS", "Detected ${accounts.size} Google accounts")

        // Upsert into local policy DB so policies can reference them
        accounts.forEach { acc ->
            accountDao.insertAccount(AccountEntity(
                accountName = acc.name,
                accountType = acc.type,
                dbId = acc.dbId
            ))
        }

        return accounts
    }

    fun getAllAccountsFlow(): Flow<List<AccountEntity>> = accountDao.getAllAccounts()

    suspend fun getAccountByName(name: String): AccountEntity? = accountDao.findByName(name)

    // ─────────────────────────────────────────────────────────────
    // POLICY MANAGEMENT
    // ─────────────────────────────────────────────────────────────

    fun getPoliciesForAccount(accountName: String): Flow<List<PolicyEntity>> =
        policyDao.getPoliciesForAccount(accountName)

    fun getAllPolicies(): Flow<List<PolicyEntity>> = policyDao.getAllPolicies()

    /**
     * Apply a HIDE policy for (account, target).
     *
     * This:
     * 1. Writes to the accounts_de.db visibility table (via RootEngine)
     * 2. Records the policy in the local Room DB
     * 3. Logs the operation
     * 4. Verifies the result
     *
     * The account remains installed. No login state is affected.
     * To reverse: call applyPolicy() with VisibilityState.VISIBLE
     */
    suspend fun applyPolicy(
        account: GoogleAccount,
        targetPackage: String,
        state: VisibilityState
    ): Boolean {
        val action = if (state == VisibilityState.HIDDEN) "HIDE" else "SHOW"
        log("START", account.name, targetPackage, "PENDING", "$action operation started")

        // 1. Apply at the system level
        val success = if (state == VisibilityState.HIDDEN) {
            RootEngine.hideAccountFromPackage(account.dbId, targetPackage)
        } else {
            RootEngine.showAccountFromPackage(account.dbId, targetPackage)
        }

        if (!success) {
            log(action, account.name, targetPackage, "FAIL", "Root operation failed")
            return false
        }

        // 2. Save to local policy DB
        val accountEntity = accountDao.findByName(account.name) ?: run {
            accountDao.insertAccount(AccountEntity(
                accountName = account.name,
                accountType = account.type,
                dbId = account.dbId
            ))
            accountDao.findByName(account.name)
        }

        if (accountEntity == null) {
            log(action, account.name, targetPackage, "FAIL", "Could not save to policy DB")
            return false
        }

        if (state == VisibilityState.HIDDEN) {
            policyDao.insertPolicy(PolicyEntity(
                accountId = accountEntity.id,
                accountName = account.name,
                targetPackage = targetPackage,
                visibilityState = state,
                appliedAt = System.currentTimeMillis()
            ))
        } else {
            // If restoring to visible, remove the policy record
            policyDao.deletePolicy(account.name, targetPackage)
        }

        // 3. Verify
        val verification = RootEngine.verifyPolicy(account.dbId, targetPackage, state)
        if (state == VisibilityState.HIDDEN) {
            policyDao.updateVerificationResult(
                account.name, targetPackage, verification, System.currentTimeMillis()
            )
        }

        val resultStr = when (verification) {
            VerificationResult.PASS -> "PASS"
            VerificationResult.PARTIAL -> "PARTIAL"
            else -> "VERIFIED"
        }

        log(action, account.name, targetPackage, resultStr,
            "$action applied successfully (verified: ${verification.name})")

        Log.i(TAG, "$action applied: account=${account.name} target=$targetPackage state=${state.name}")
        return true
    }

    /**
     * Restore ALL policies for an account — makes the account fully visible again.
     * No re-login required. This is the primary "unhide" operation.
     */
    suspend fun restoreAccount(account: GoogleAccount): Boolean {
        log("RESTORE", account.name, "ALL", "PENDING", "Restoring all visibility for ${account.name}")

        val success = RootEngine.restoreAllPoliciesForAccount(account.dbId)
        if (success) {
            policyDao.deletePoliciesForAccount(account.name)
            log("RESTORE", account.name, "ALL", "PASS", "Account fully restored — no re-login needed")
        } else {
            log("RESTORE", account.name, "ALL", "FAIL", "Restore operation failed at system level")
        }

        return success
    }

    /**
     * Emergency recovery — clears ALL policies for ALL accounts.
     */
    suspend fun emergencyRestore(): Boolean {
        log("EMERGENCY", "ALL", "ALL", "PENDING", "Emergency recovery initiated")
        val success = RootEngine.emergencyRecoverAllAccounts()
        if (success) {
            // Clear local policy DB too
            val allPolicies = policyDao.getAllPolicies().first()
            allPolicies.forEach { policyDao.deletePolicy(it.accountName, it.targetPackage) }
            log("EMERGENCY", "ALL", "ALL", "PASS", "All accounts restored to default visibility")
        } else {
            log("EMERGENCY", "ALL", "ALL", "FAIL", "Emergency recovery failed")
        }
        return success
    }

    // ─────────────────────────────────────────────────────────────
    // ACCOUNT STATUS
    // ─────────────────────────────────────────────────────────────

    suspend fun getAccountStatus(accountName: String): AccountStatus {
        val hiddenCount = policyDao.countHiddenPolicies(accountName)
        return when {
            hiddenCount == 0 -> AccountStatus.FULLY_VISIBLE
            hiddenCount > 0 -> AccountStatus.PARTIALLY_HIDDEN
            else -> AccountStatus.FULLY_VISIBLE
        }
    }

    // ─────────────────────────────────────────────────────────────
    // LOGGING
    // ─────────────────────────────────────────────────────────────

    fun getRecentLogs(): Flow<List<PolicyLogEntity>> = logDao.getRecentLogs()

    private suspend fun log(action: String, accountName: String, targetPackage: String, result: String, details: String) {
        logDao.insertLog(PolicyLogEntity(
            accountName = accountName,
            targetPackage = targetPackage,
            action = action,
            result = result,
            details = details
        ))
        
        // Optimize: Prune logs older than 7 days to prevent DB bloat
        val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
        logDao.pruneOldLogs(sevenDaysAgo)
        
        Log.d(TAG, "[$action] $accountName → $targetPackage: $result | $details")
    }

    // ─────────────────────────────────────────────────────────────
    // DIAGNOSTICS
    // ─────────────────────────────────────────────────────────────

    suspend fun getVisibilityDump(account: GoogleAccount): String =
        RootEngine.getVisibilityDump(account.dbId)

    suspend fun getDumpsys(): String = RootEngine.getDumpsysSnapshot()
}
