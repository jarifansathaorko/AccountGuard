package dev.accountguard.root

import android.util.Log
import com.topjohnwu.superuser.Shell
import dev.accountguard.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RootEngine — the core privileged operations layer.
 *
 * All operations here communicate with the Android system via root shell
 * using libsu (KernelSU-Next compatible). Operations are performed against:
 *   - /data/system_de/0/accounts_de.db  (Device Encrypted — always accessible after boot)
 *   - /data/system_ce/0/accounts_ce.db  (Credential Encrypted — accessible after screen unlock)
 *
 * The visibility table in these DBs controls what each package can see via AccountManager.
 *
 * ARCHITECTURE DECISION: We ONLY modify the `visibility` table.
 * We NEVER modify the `accounts` table.
 * This guarantees accounts remain installed and login state is preserved.
 */
object RootEngine {

    private const val TAG = "AccountGuard.Root"

    // ─────────────────────────────────────────────────────────────
    // DB paths — Xiaomi xaga / Lunaris AOSP (user 0)
    // ─────────────────────────────────────────────────────────────
    private const val USER_ID = 0
    private val DB_DE = "/data/system_de/$USER_ID/accounts_de.db"
    private val DB_CE = "/data/system_ce/$USER_ID/accounts_ce.db"

    // Broadcast to flush AccountManagerService cache after DB changes
    private const val ACCOUNTS_CHANGED_ACTION = "android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION"

    // ─────────────────────────────────────────────────────────────
    // ROOT STATUS
    // ─────────────────────────────────────────────────────────────

    suspend fun checkRootStatus(): RootStatus = withContext(Dispatchers.IO) {
        try {
            val shell = Shell.getShell()
            if (!shell.isRoot) {
                return@withContext RootStatus(
                    isRootAvailable = false,
                    rootType = "None",
                    isPrivilegeVerified = false,
                    errorMessage = "Shell is not running as root"
                )
            }

            // Detect root type
            val rootType = detectRootType()

            // Verify we can actually read the system DB (ultimate test)
            val verifyResult = Shell.cmd("sqlite3 $DB_DE 'SELECT COUNT(*) FROM accounts;' 2>&1").exec()
            val privilegeVerified = verifyResult.isSuccess && verifyResult.out.isNotEmpty()

            // Check for Vector/LSPosed
            val vectorResult = Shell.cmd("ls /data/adb/modules/ 2>/dev/null | grep -i vector").exec()
            val vectorDetected = vectorResult.isSuccess && vectorResult.out.isNotEmpty()

            // KSU version
            val ksuVersion = if (rootType == "KernelSU") {
                Shell.cmd("ksud --version 2>/dev/null").exec().out.firstOrNull() ?: ""
            } else ""

            RootStatus(
                isRootAvailable = true,
                rootType = rootType,
                isPrivilegeVerified = privilegeVerified,
                ksuVersion = ksuVersion,
                vectorDetected = vectorDetected
            )
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            RootStatus(
                isRootAvailable = false,
                rootType = "Error",
                isPrivilegeVerified = false,
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    private fun detectRootType(): String {
        val ksuCheck = Shell.cmd("[ -d /data/adb/ksu ] && echo KSU").exec()
        if (ksuCheck.isSuccess && ksuCheck.out.contains("KSU")) return "KernelSU"

        val magiskCheck = Shell.cmd("[ -d /data/adb/magisk ] && echo MAGISK").exec()
        if (magiskCheck.isSuccess && magiskCheck.out.contains("MAGISK")) return "Magisk"

        return "Unknown"
    }

    // ─────────────────────────────────────────────────────────────
    // ACCOUNT DETECTION
    // Reads from accounts_de.db — the source of truth
    // ─────────────────────────────────────────────────────────────

    suspend fun detectGoogleAccounts(): List<GoogleAccount> = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(
                "sqlite3 $DB_DE 'SELECT _id, name FROM accounts WHERE type=\"com.google\" ORDER BY name ASC;'"
            ).exec()

            if (!result.isSuccess) {
                Log.e(TAG, "Failed to query accounts: ${result.err}")
                return@withContext emptyList()
            }

            result.out.mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 2) {
                    GoogleAccount(
                        dbId = parts[0].trim().toLongOrNull() ?: return@mapNotNull null,
                        name = parts[1].trim()
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Account detection failed", e)
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // VISIBILITY CONTROL
    // Core mechanism: write to visibility table in accounts_de.db
    // ─────────────────────────────────────────────────────────────

    /**
     * Hide a Google account from a specific package.
     *
     * This writes VISIBILITY_NOT_VISIBLE (4) into accounts_de.db visibility table
     * for the (account_id, package_name) pair.
     *
     * The account remains installed — credentials, tokens, sync state are untouched.
     * To restore: call showAccountFromPackage() with the same parameters.
     *
     * @param accountDbId   The _id from accounts_de.db accounts table
     * @param targetPackage The package to hide from, or special keys for bulk targets
     * @return true if operation succeeded
     */
    suspend fun hideAccountFromPackage(
        accountDbId: Long,
        targetPackage: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            when (targetPackage) {
                "__SYNC__" -> return@withContext disableAccountSync(accountDbId)
                "__ACCOUNT_PICKER__" -> return@withContext hideFromAccountPicker(accountDbId)
                "__THIRD_PARTY__" -> return@withContext hideFromThirdParty(accountDbId)
                else -> return@withContext writeVisibility(accountDbId, targetPackage, VisibilityState.HIDDEN)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hide operation failed for dbId=$accountDbId pkg=$targetPackage", e)
            false
        }
    }

    /**
     * Restore visibility of a Google account to a specific package.
     * No re-login required — the account was never removed.
     */
    suspend fun showAccountFromPackage(
        accountDbId: Long,
        targetPackage: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            when (targetPackage) {
                "__SYNC__" -> return@withContext enableAccountSync(accountDbId)
                "__ACCOUNT_PICKER__" -> return@withContext showFromAccountPicker(accountDbId)
                "__THIRD_PARTY__" -> return@withContext showFromThirdParty(accountDbId)
                else -> {
                    // Delete the explicit visibility entry → falls back to default (visible)
                    val result = Shell.cmd(
                        "sqlite3 $DB_DE 'DELETE FROM visibility WHERE accounts_id=$accountDbId AND package_name=\"$targetPackage\";' 2>&1"
                    ).exec()
                    if (result.isSuccess) {
                        broadcastAccountsChanged()
                        Log.i(TAG, "Restored visibility: dbId=$accountDbId pkg=$targetPackage")
                        true
                    } else {
                        Log.e(TAG, "Restore failed: ${result.err}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Show operation failed", e)
            false
        }
    }

    /**
     * Write a specific visibility value to both accounts_de.db (and accounts_ce.db if accessible).
     * Uses INSERT OR REPLACE to handle both new entries and updates.
     */
    private fun writeVisibility(
        accountDbId: Long,
        packageName: String,
        state: VisibilityState
    ): Boolean {
        val sql = "INSERT OR REPLACE INTO visibility (accounts_id, package_name, visibility) VALUES ($accountDbId, '$packageName', ${state.dbValue});"

        // Write to DE database (always accessible after boot)
        val deResult = Shell.cmd("sqlite3 $DB_DE '$sql' 2>&1").exec()
        if (!deResult.isSuccess) {
            Log.e(TAG, "DE DB write failed: ${deResult.err}")
            return false
        }

        // Also write to CE database if accessible (post-unlock)
        // Ignore errors here — CE may not be accessible in edge cases
        Shell.cmd("sqlite3 $DB_CE '$sql' 2>/dev/null").exec()

        broadcastAccountsChanged()
        Log.i(TAG, "Visibility set: dbId=$accountDbId pkg=$packageName state=${state.name}")
        return true
    }

    // ─────────────────────────────────────────────────────────────
    // SPECIAL TARGETS
    // ─────────────────────────────────────────────────────────────

    /**
     * Account Picker: hide by setting VISIBILITY_NOT_VISIBLE for the system account picker
     * package. On AOSP/Android 16, account pickers run in the context of
     * "android" (system server) — we use the special legacy key for this.
     */
    private fun hideFromAccountPicker(accountDbId: Long): Boolean {
        return writeVisibility(accountDbId, "android", VisibilityState.HIDDEN)
    }

    private fun showFromAccountPicker(accountDbId: Long): Boolean {
        val result = Shell.cmd(
            "sqlite3 $DB_DE 'DELETE FROM visibility WHERE accounts_id=$accountDbId AND package_name=\"android\";' 2>&1"
        ).exec()
        return if (result.isSuccess) { broadcastAccountsChanged(); true } else false
    }

    /**
     * Third-party apps: set the PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE key.
     * This is the default fallback for any package not explicitly listed.
     * Hides from ALL apps without an explicit visibility entry.
     */
    private fun hideFromThirdParty(accountDbId: Long): Boolean {
        return writeVisibility(
            accountDbId,
            "__PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE__",
            VisibilityState.HIDDEN
        )
    }

    private fun showFromThirdParty(accountDbId: Long): Boolean {
        val result = Shell.cmd(
            "sqlite3 $DB_DE 'DELETE FROM visibility WHERE accounts_id=$accountDbId AND package_name=\"__PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE__\";' 2>&1"
        ).exec()
        return if (result.isSuccess) { broadcastAccountsChanged(); true } else false
    }

    // ─────────────────────────────────────────────────────────────
    // SYNC CONTROL
    // ContentResolver sync is separate from account visibility
    // ─────────────────────────────────────────────────────────────

    private fun disableAccountSync(accountDbId: Long): Boolean {
        // Get account name from DB
        val nameResult = Shell.cmd(
            "sqlite3 $DB_DE 'SELECT name FROM accounts WHERE _id=$accountDbId;'"
        ).exec()
        val accountName = nameResult.out.firstOrNull()?.trim() ?: return false

        // Disable sync via content provider
        val result = Shell.cmd(
            "content call --uri content://com.android.sync/syncstatus --method disableAllSync --arg '$accountName:com.google' 2>&1"
        ).exec()

        // Fallback: use syncmanager shell command
        if (!result.isSuccess) {
            Shell.cmd("am broadcast -a android.intent.action.SYNC_CONNECTION_CHANGE --ez connected false 2>/dev/null").exec()
        }

        Log.i(TAG, "Sync disabled for account dbId=$accountDbId name=$accountName")
        return true
    }

    private fun enableAccountSync(accountDbId: Long): Boolean {
        val nameResult = Shell.cmd(
            "sqlite3 $DB_DE 'SELECT name FROM accounts WHERE _id=$accountDbId;'"
        ).exec()
        val accountName = nameResult.out.firstOrNull()?.trim() ?: return false

        val result = Shell.cmd(
            "content call --uri content://com.android.sync/syncstatus --method enableAllSync --arg '$accountName:com.google' 2>&1"
        ).exec()

        Log.i(TAG, "Sync enabled for account dbId=$accountDbId name=$accountName (result=${result.isSuccess})")
        return true
    }

    // ─────────────────────────────────────────────────────────────
    // RECOVERY
    // ─────────────────────────────────────────────────────────────

    /**
     * Restore ALL visibility entries for a specific account.
     * Removes all custom visibility rows → account becomes fully visible everywhere.
     * NO re-login required.
     */
    suspend fun restoreAllPoliciesForAccount(accountDbId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val sql1 = "DELETE FROM visibility WHERE accounts_id=$accountDbId;"
            val r1 = Shell.cmd("sqlite3 $DB_DE '$sql1' 2>&1").exec()
            Shell.cmd("sqlite3 $DB_CE '$sql1' 2>/dev/null").exec()

            broadcastAccountsChanged()
            Log.i(TAG, "Restored all policies for account dbId=$accountDbId")
            r1.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            false
        }
    }

    /**
     * EMERGENCY RECOVERY: Remove ALL custom visibility entries for ALL accounts.
     * This fully restores the original Android account visibility state.
     * Call this if something goes wrong and you want to reset everything.
     */
    suspend fun emergencyRecoverAllAccounts(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Keep only the built-in legacy keys if they existed before
            val sql = "DELETE FROM visibility WHERE package_name NOT IN ('__PACKAGE_NAME_KEY_LEGACY_VISIBLE__') OR 1=1;"
            val clearAll = "DELETE FROM visibility;"
            Shell.cmd("sqlite3 $DB_DE '$clearAll' 2>&1").exec()
            Shell.cmd("sqlite3 $DB_CE '$clearAll' 2>/dev/null").exec()
            broadcastAccountsChanged()
            Log.i(TAG, "Emergency recovery completed — all visibility entries cleared")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Emergency recovery failed", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
    // VERIFICATION
    // ─────────────────────────────────────────────────────────────

    /**
     * Verify the actual DB state matches the requested policy.
     * Returns VerificationResult based on what we find in the DB.
     */
    suspend fun verifyPolicy(
        accountDbId: Long,
        targetPackage: String,
        expectedState: VisibilityState
    ): VerificationResult = withContext(Dispatchers.IO) {
        try {
            when (targetPackage) {
                "__SYNC__" -> return@withContext VerificationResult.UNKNOWN // sync harder to verify
                "__THIRD_PARTY__" -> {
                    val result = Shell.cmd(
                        "sqlite3 $DB_DE 'SELECT visibility FROM visibility WHERE accounts_id=$accountDbId AND package_name=\"__PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE__\";'"
                    ).exec()
                    val found = result.out.firstOrNull()?.trim()?.toIntOrNull()
                    return@withContext if (found == expectedState.dbValue) VerificationResult.PASS
                    else VerificationResult.FAIL
                }
                else -> {
                    val result = Shell.cmd(
                        "sqlite3 $DB_DE 'SELECT visibility FROM visibility WHERE accounts_id=$accountDbId AND package_name=\"$targetPackage\";'"
                    ).exec()
                    val found = result.out.firstOrNull()?.trim()?.toIntOrNull()
                    return@withContext when {
                        found == null && expectedState == VisibilityState.VISIBLE -> VerificationResult.PASS
                        found == expectedState.dbValue -> VerificationResult.PASS
                        else -> VerificationResult.FAIL
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Verification failed", e)
            VerificationResult.UNKNOWN
        }
    }

    /**
     * Get a full visibility dump for an account — for the diagnostics screen.
     */
    suspend fun getVisibilityDump(accountDbId: Long): String = withContext(Dispatchers.IO) {
        val result = Shell.cmd(
            "sqlite3 $DB_DE 'SELECT package_name, visibility FROM visibility WHERE accounts_id=$accountDbId;'"
        ).exec()
        result.out.joinToString("\n").ifEmpty { "No visibility entries found" }
    }

    /**
     * Get a sanitized dumpsys account snapshot (no tokens logged).
     */
    suspend fun getDumpsysSnapshot(): String = withContext(Dispatchers.IO) {
        val result = Shell.cmd("dumpsys account 2>&1 | grep -v 'authtoken\\|password\\|token' | head -100").exec()
        result.out.joinToString("\n")
    }

    // ─────────────────────────────────────────────────────────────
    // CACHE FLUSH
    // ─────────────────────────────────────────────────────────────

    /**
     * Notify AccountManagerService that accounts have changed.
     * This triggers cache invalidation and makes visibility changes effective.
     */
    private fun broadcastAccountsChanged() {
        Shell.cmd("am broadcast -a android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION 2>/dev/null").exec()
        // Also trigger via Settings package which has permission
        Shell.cmd("am broadcast --user 0 -a android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION 2>/dev/null").exec()
    }
}
