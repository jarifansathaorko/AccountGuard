package dev.accountguard.root

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import dev.accountguard.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * RootEngine — the core privileged operations layer.
 *
 * Communicates with the Android system via root shell using libsu
 * (KernelSU-Next / Magisk / APatch compatible).
 *
 * TRIPLE-TIER SQLITE EXECUTION:
 *   1. Bundled static `sqlite3` binary (self-contained, extracted from assets to /data/local/tmp)
 *   2. System/Module `sqlite3` CLI if installed
 *   3. Built-in `SqliteCli` via `/system/bin/app_process` (zero binary dependency)
 *
 * DYNAMIC DATABASE RESOLUTION:
 *   Probes all CE, DE, and legacy account database paths across active user boundaries.
 *   Automatically identifies which database hosts the `accounts` table and which hosts
 *   the `visibility` table.
 */
object RootEngine {

    private const val TAG = "AccountGuard.Root"

    private var appContext: Context? = null
    private var appSourceDir: String? = null
    private var sqlite3Binary: String? = null
    private var activeEngine: String = "Detecting..."

    // Resolved database locations
    private var resolvedDbAccounts: String? = null
    private var resolvedDbVisibility: String? = null
    private val allDiscoveredDbs = mutableSetOf<String>()
    private var isPathsResolved = false

    private const val BUNDLED_SQLITE_TMP = "/data/local/tmp/accountguard_sqlite3"
    private const val ACCOUNTS_CHANGED_ACTION = "android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION"

    data class SqlResult(
        val isSuccess: Boolean,
        val out: List<String> = emptyList(),
        val err: List<String> = emptyList(),
        val code: Int = 0
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        appSourceDir = context.applicationInfo.sourceDir
        Log.i(TAG, "RootEngine initialized with APK sourceDir: $appSourceDir")

        // Asynchronously prepare bundled static sqlite3 binary
        Thread {
            try {
                deployBundledSqlite(context)
            } catch (e: Exception) {
                Log.w(TAG, "Bundled sqlite deployment deferred: ${e.message}")
            }
        }.start()
    }

    private fun deployBundledSqlite(context: Context) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val assetName = when {
            abi.contains("arm64") -> "sqlite3.arm64"
            abi.contains("armeabi") || abi.contains("arm") -> "sqlite3.arm"
            abi.contains("x86_64") -> "sqlite3.x64"
            abi.contains("x86") -> "sqlite3.x86"
            else -> "sqlite3.arm64"
        }

        try {
            val localFile = File(context.filesDir, "sqlite3_bin")
            context.assets.open("bin/$assetName").use { input ->
                FileOutputStream(localFile).use { output ->
                    input.copyTo(output)
                }
            }
            localFile.setExecutable(true, false)

            // Copy to /data/local/tmp via root for unrestricted execution in root domain
            val copyCmd = "cp \"${localFile.absolutePath}\" \"$BUNDLED_SQLITE_TMP\" && chmod 755 \"$BUNDLED_SQLITE_TMP\""
            Shell.cmd(copyCmd).exec()
            Log.i(TAG, "Deployed bundled static sqlite3 ($assetName) to $BUNDLED_SQLITE_TMP")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy bundled sqlite3", e)
        }
    }

    private fun getApkPath(): String? {
        if (!appSourceDir.isNullOrEmpty()) return appSourceDir
        val out = Shell.cmd("pm path dev.accountguard 2>/dev/null | head -n 1 | cut -d: -f2").exec().out.firstOrNull()?.trim()
        if (!out.isNullOrEmpty()) {
            appSourceDir = out
        }
        return appSourceDir
    }

    private fun findSqlite3Binary(): String? {
        if (sqlite3Binary != null) return sqlite3Binary

        // 1. Check bundled static binary in /data/local/tmp
        val bundledTest = Shell.cmd("[ -x \"$BUNDLED_SQLITE_TMP\" ] && \"$BUNDLED_SQLITE_TMP\" --version 2>/dev/null").exec()
        if (bundledTest.isSuccess && bundledTest.out.isNotEmpty()) {
            sqlite3Binary = BUNDLED_SQLITE_TMP
            Log.i(TAG, "Using bundled static sqlite3: $sqlite3Binary (${bundledTest.out.first()})")
            return sqlite3Binary
        }

        // Try extracting if not present
        appContext?.let { ctx ->
            deployBundledSqlite(ctx)
            val retry = Shell.cmd("[ -x \"$BUNDLED_SQLITE_TMP\" ] && \"$BUNDLED_SQLITE_TMP\" --version 2>/dev/null").exec()
            if (retry.isSuccess && retry.out.isNotEmpty()) {
                sqlite3Binary = BUNDLED_SQLITE_TMP
                return sqlite3Binary
            }
        }

        // 2. Check system and module binaries
        val candidates = listOf(
            "sqlite3",
            "/system/bin/sqlite3",
            "/system/xbin/sqlite3",
            "/data/adb/ksu/bin/sqlite3",
            "/data/adb/magisk/sqlite3",
            "/data/adb/ap/bin/sqlite3",
            "/apex/com.android.runtime/bin/sqlite3"
        )

        for (candidate in candidates) {
            val test = Shell.cmd("$candidate --version 2>/dev/null").exec()
            if (test.isSuccess && test.out.isNotEmpty()) {
                sqlite3Binary = candidate
                Log.i(TAG, "Found working system sqlite3: $candidate")
                return candidate
            }
        }

        // Search root modules
        val modCheck = Shell.cmd("find /data/adb/modules -name sqlite3 -type f 2>/dev/null").exec()
        if (modCheck.isSuccess && modCheck.out.isNotEmpty()) {
            for (cand in modCheck.out) {
                val trimmed = cand.trim()
                val test = Shell.cmd("$trimmed --version 2>/dev/null").exec()
                if (test.isSuccess && test.out.isNotEmpty()) {
                    sqlite3Binary = trimmed
                    Log.i(TAG, "Found sqlite3 in module: $trimmed")
                    return trimmed
                }
            }
        }

        return null
    }

    /**
     * Deep scan for accounts and visibility database files.
     */
    private fun resolveDbPaths() {
        if (isPathsResolved && resolvedDbAccounts != null) return

        val userOut = Shell.cmd("am get-current-user 2>/dev/null").exec().out.firstOrNull()?.trim()
        val userId = userOut?.toIntOrNull() ?: 0

        val probeCmd = """
for p in \
  "/data/system_ce/$userId/accounts_ce.db" \
  "/data/system_de/$userId/accounts_de.db" \
  "/data/system_ce/0/accounts_ce.db" \
  "/data/system_de/0/accounts_de.db" \
  "/data/system/users/$userId/accounts.db" \
  "/data/system/users/0/accounts.db" \
  "/data/system/users/$userId/accounts_ce.db" \
  "/data/system/users/0/accounts_ce.db" \
  "/data/system/users/$userId/accounts_de.db" \
  "/data/system/users/0/accounts_de.db" \
  "/data/system/accounts.db" \
  "/data/system/accounts_ce.db" \
  "/data/system/accounts_de.db" \
  /data/system_ce/*/accounts_ce.db \
  /data/system_de/*/accounts_de.db \
  /data/system/users/*/accounts*.db
do
  [ -f "${'$'}p" ] && echo "${'$'}p"
done
"""
        val probeRes = Shell.cmd(probeCmd).exec()
        val candidates = probeRes.out.map { it.trim() }.filter { it.endsWith(".db") }.distinct().toMutableList()

        if (candidates.isEmpty()) {
            val findRes = Shell.cmd("find /data/system_ce /data/system_de /data/system /data/user_de -maxdepth 3 -name '*account*.db' 2>/dev/null").exec()
            candidates.addAll(findRes.out.map { it.trim() }.filter { it.endsWith(".db") }.distinct())
        }

        Log.i(TAG, "Found ${candidates.size} candidate account databases: $candidates")
        allDiscoveredDbs.clear()
        allDiscoveredDbs.addAll(candidates)

        // Test each database for accounts and visibility tables
        for (candidate in candidates) {
            val resAccounts = runRawSql(candidate, "SELECT COUNT(*) FROM accounts;")
            if (resAccounts.isSuccess) {
                if (resolvedDbAccounts == null) {
                    resolvedDbAccounts = candidate
                    Log.i(TAG, "Resolved primary accounts DB: $candidate")
                }
            }

            val resVis = runRawSql(candidate, "SELECT COUNT(*) FROM visibility;")
            if (resVis.isSuccess) {
                if (resolvedDbVisibility == null) {
                    resolvedDbVisibility = candidate
                    Log.i(TAG, "Resolved primary visibility DB: $candidate")
                }
            }
        }

        // If visibility DB wasn't found, use accounts DB and create visibility table
        if (resolvedDbVisibility == null && resolvedDbAccounts != null) {
            resolvedDbVisibility = resolvedDbAccounts
            val createVisSql = "CREATE TABLE IF NOT EXISTS visibility (_id INTEGER PRIMARY KEY AUTOINCREMENT, accounts_id INTEGER NOT NULL, package_name TEXT NOT NULL, visibility INTEGER NOT NULL, UNIQUE(accounts_id, package_name));"
            runRawSql(resolvedDbVisibility!!, createVisSql)
            Log.i(TAG, "Created visibility table in: $resolvedDbVisibility")
        }

        // Final fallback if nothing was discovered
        if (resolvedDbAccounts == null) {
            resolvedDbAccounts = "/data/system_ce/0/accounts_ce.db"
        }
        if (resolvedDbVisibility == null) {
            resolvedDbVisibility = "/data/system_de/0/accounts_de.db"
        }

        isPathsResolved = true
    }

    private fun runRawSql(dbPath: String, sql: String): SqlResult {
        val base64Sql = Base64.encodeToString(sql.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // 1. Try static / system sqlite3 binary
        val binary = findSqlite3Binary()
        if (binary != null) {
            val cmd = "echo \"$base64Sql\" | base64 -d | $binary \"$dbPath\" 2>&1"
            val res = Shell.cmd(cmd).exec()
            if (res.isSuccess) {
                activeEngine = if (binary == BUNDLED_SQLITE_TMP) "Static sqlite3 (in-app)" else "System ($binary)"
                return SqlResult(true, res.out, res.err, res.code)
            } else {
                Log.w(TAG, "sqlite3 ($binary) failed on $dbPath: ${res.out.joinToString(" ")}")
            }
        }

        // 2. Try built-in SqliteCli via app_process
        val apk = getApkPath()
        if (!apk.isNullOrEmpty()) {
            val appProcessCmd = "CLASSPATH=\"$apk\" /system/bin/app_process /system/bin dev.accountguard.root.SqliteCli \"$dbPath\" \"$base64Sql\" 2>&1"
            val res = Shell.cmd(appProcessCmd).exec()
            if (res.isSuccess) {
                activeEngine = "Built-in Framework (app_process)"
                return SqlResult(true, res.out, res.err, res.code)
            } else {
                Log.w(TAG, "SqliteCli failed on $dbPath: ${res.out.joinToString(" ")}")
                return SqlResult(false, res.out, res.err, res.code)
            }
        }

        return SqlResult(false, emptyList(), listOf("No SQLite executor available"), 1)
    }

    private fun runSql(dbPath: String, sql: String): SqlResult {
        resolveDbPaths()
        return runRawSql(dbPath, sql)
    }

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
                    errorMessage = "Root permission not granted"
                )
            }

            val rootType = detectRootType()
            resolveDbPaths()

            val primaryDb = resolvedDbAccounts ?: "/data/system_ce/0/accounts_ce.db"
            val verifyResult = runSql(primaryDb, "SELECT COUNT(*) FROM accounts;")
            val privilegeVerified = verifyResult.isSuccess && verifyResult.out.isNotEmpty()

            val errorMsg = if (!privilegeVerified) {
                val detail = (verifyResult.err + verifyResult.out).joinToString(" ").trim()
                if (detail.isNotEmpty()) detail else "Cannot query accounts database"
            } else ""

            val vectorResult = Shell.cmd("ls /data/adb/modules/ 2>/dev/null | grep -i vector").exec()
            val vectorDetected = vectorResult.isSuccess && vectorResult.out.isNotEmpty()

            val ksuVersion = if (rootType == "KernelSU") {
                Shell.cmd("ksud --version 2>/dev/null").exec().out.firstOrNull() ?: ""
            } else ""

            RootStatus(
                isRootAvailable = true,
                rootType = rootType,
                isPrivilegeVerified = privilegeVerified,
                ksuVersion = ksuVersion,
                vectorDetected = vectorDetected,
                errorMessage = errorMsg,
                engineName = activeEngine,
                resolvedDbPath = primaryDb
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

        val apatchCheck = Shell.cmd("[ -d /data/adb/ap ] && echo APATCH").exec()
        if (apatchCheck.isSuccess && apatchCheck.out.contains("APATCH")) return "APatch"

        return "Root (su)"
    }

    // ─────────────────────────────────────────────────────────────
    // ACCOUNT DETECTION
    // Reads directly from the resolved accounts database
    // ─────────────────────────────────────────────────────────────

    suspend fun detectGoogleAccounts(): List<GoogleAccount> = withContext(Dispatchers.IO) {
        try {
            resolveDbPaths()
            val dbToQuery = resolvedDbAccounts ?: return@withContext detectFromDumpsys()

            // 1. Primary query targeting Google accounts
            val query1 = "SELECT _id, name FROM accounts WHERE type='com.google' OR name LIKE '%@gmail.com' OR name LIKE '%@googlemail.com' ORDER BY name ASC;"
            val result = runSql(dbToQuery, query1)

            if (result.isSuccess && result.out.isNotEmpty()) {
                val accounts = result.out.mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 2) {
                        val id = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                        val name = parts[1].trim()
                        if (name.isNotEmpty()) GoogleAccount(dbId = id, name = name) else null
                    } else null
                }
                if (accounts.isNotEmpty()) {
                    Log.i(TAG, "Detected ${accounts.size} Google accounts from $dbToQuery")
                    return@withContext accounts
                }
            }

            // 2. Fetch all accounts and filter in Kotlin
            val queryAll = "SELECT _id, name, type FROM accounts ORDER BY name ASC;"
            val resultAll = runSql(dbToQuery, queryAll)
            if (resultAll.isSuccess && resultAll.out.isNotEmpty()) {
                val accounts = resultAll.out.mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 3) {
                        val id = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                        val name = parts[1].trim()
                        val type = parts[2].trim()
                        if (type == "com.google" || name.contains("@gmail.com") || name.contains("@googlemail.com")) {
                            GoogleAccount(dbId = id, name = name)
                        } else null
                    } else null
                }
                if (accounts.isNotEmpty()) {
                    Log.i(TAG, "Detected ${accounts.size} accounts via secondary query on $dbToQuery")
                    return@withContext accounts
                }
            }

            // 3. Fallback to native dumpsys account
            val dumpsysAccounts = detectFromDumpsys()
            if (dumpsysAccounts.isNotEmpty()) {
                Log.i(TAG, "Detected ${dumpsysAccounts.size} accounts via dumpsys fallback")
                return@withContext dumpsysAccounts
            }

            Log.w(TAG, "No Google accounts detected on device")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Account detection failed", e)
            detectFromDumpsys()
        }
    }

    private fun detectFromDumpsys(): List<GoogleAccount> {
        val dump = Shell.cmd("dumpsys account 2>&1").exec()
        if (!dump.isSuccess) return emptyList()

        val found = mutableListOf<GoogleAccount>()
        var fallbackId = 1L

        val patterns = listOf(
            Regex("""Account\s*\{\s*name=([^,\}]+),\s*type=com\.google\s*\}"""),
            Regex("""name=([^,\}]+),\s*type=com\.google"""),
            Regex("""([a-zA-Z0-9._%+-]+@gmail\.com)"""),
            Regex("""([a-zA-Z0-9._%+-]+@googlemail\.com)""")
        )

        dump.out.forEach { line ->
            for (pattern in patterns) {
                pattern.find(line)?.let { match ->
                    val name = match.groupValues[1].trim()
                    if (name.isNotEmpty() && found.none { it.name.equals(name, ignoreCase = true) }) {
                        found.add(GoogleAccount(dbId = fallbackId++, name = name))
                    }
                }
            }
        }
        return found
    }

    // ─────────────────────────────────────────────────────────────
    // VISIBILITY CONTROL
    // Modifies visibility table in the resolved visibility database(s)
    // ─────────────────────────────────────────────────────────────

    suspend fun hideAccountFromPackage(
        accountDbId: Long,
        targetPackage: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            when (targetPackage) {
                "__SYNC__" -> disableAccountSync(accountDbId)
                "__ACCOUNT_PICKER__" -> hideFromAccountPicker(accountDbId)
                "__THIRD_PARTY__" -> hideFromThirdParty(accountDbId)
                else -> writeVisibility(accountDbId, targetPackage, VisibilityState.HIDDEN)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hide failed: dbId=$accountDbId pkg=$targetPackage", e)
            false
        }
    }

    suspend fun showAccountFromPackage(
        accountDbId: Long,
        targetPackage: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            when (targetPackage) {
                "__SYNC__" -> enableAccountSync(accountDbId)
                "__ACCOUNT_PICKER__" -> showFromAccountPicker(accountDbId)
                "__THIRD_PARTY__" -> showFromThirdParty(accountDbId)
                else -> {
                    resolveDbPaths()
                    val sql = "DELETE FROM visibility WHERE accounts_id=$accountDbId AND package_name='$targetPackage';"
                    val dbsToWrite = (listOfNotNull(resolvedDbVisibility, resolvedDbAccounts) + allDiscoveredDbs).distinct()
                    var anySuccess = false

                    for (db in dbsToWrite) {
                        val res = runSql(db, sql)
                        if (res.isSuccess) anySuccess = true
                    }

                    if (anySuccess) {
                        broadcastAccountsChanged()
                        Log.i(TAG, "Restored visibility: dbId=$accountDbId pkg=$targetPackage")
                        true
                    } else {
                        Log.e(TAG, "Restore visibility failed across all databases")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Show failed", e)
            false
        }
    }

    private fun writeVisibility(
        accountDbId: Long,
        packageName: String,
        state: VisibilityState
    ): Boolean {
        resolveDbPaths()
        val sql = "INSERT OR REPLACE INTO visibility (accounts_id, package_name, visibility) VALUES ($accountDbId, '$packageName', ${state.dbValue});"
        val dbsToWrite = (listOfNotNull(resolvedDbVisibility, resolvedDbAccounts) + allDiscoveredDbs).distinct()

        var writtenCount = 0
        for (db in dbsToWrite) {
            val res = runSql(db, sql)
            if (res.isSuccess) {
                writtenCount++
            }
        }

        if (writtenCount > 0) {
            broadcastAccountsChanged()
            Log.i(TAG, "Visibility set in $writtenCount database(s): dbId=$accountDbId pkg=$packageName state=${state.name}")
            return true
        }

        Log.e(TAG, "Failed to write visibility to any database")
        return false
    }

    // ─────────────────────────────────────────────────────────────
    // SPECIAL TARGETS
    // ─────────────────────────────────────────────────────────────

    private fun hideFromAccountPicker(accountDbId: Long): Boolean {
        return writeVisibility(accountDbId, "android", VisibilityState.HIDDEN)
    }

    private fun showFromAccountPicker(accountDbId: Long): Boolean {
        resolveDbPaths()
        val sql = "DELETE FROM visibility WHERE accounts_id=$accountDbId AND package_name='android';"
        val dbs = (listOfNotNull(resolvedDbVisibility, resolvedDbAccounts) + allDiscoveredDbs).distinct()
        var ok = false
        for (db in dbs) {
            if (runSql(db, sql).isSuccess) ok = true
        }
        if (ok) broadcastAccountsChanged()
        return ok
    }

    private fun hideFromThirdParty(accountDbId: Long): Boolean {
        return writeVisibility(
            accountDbId,
            "__PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE__",
            VisibilityState.HIDDEN
        )
    }

    private fun showFromThirdParty(accountDbId: Long): Boolean {
        resolveDbPaths()
        val sql = "DELETE FROM visibility WHERE accounts_id=$accountDbId AND package_name='__PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE__';"
        val dbs = (listOfNotNull(resolvedDbVisibility, resolvedDbAccounts) + allDiscoveredDbs).distinct()
        var ok = false
        for (db in dbs) {
            if (runSql(db, sql).isSuccess) ok = true
        }
        if (ok) broadcastAccountsChanged()
        return ok
    }

    // ─────────────────────────────────────────────────────────────
    // SYNC CONTROL
    // ─────────────────────────────────────────────────────────────

    private fun disableAccountSync(accountDbId: Long): Boolean {
        val targetDb = resolvedDbAccounts ?: return false
        val nameResult = runSql(targetDb, "SELECT name FROM accounts WHERE _id=$accountDbId;")
        val accountName = nameResult.out.firstOrNull()?.trim() ?: return false

        val result = Shell.cmd(
            "content call --uri content://com.android.sync/syncstatus --method disableAllSync --arg '$accountName:com.google' 2>&1"
        ).exec()

        if (!result.isSuccess) {
            Shell.cmd("am broadcast -a android.intent.action.SYNC_CONNECTION_CHANGE --ez connected false 2>/dev/null").exec()
        }

        Log.i(TAG, "Sync disabled for account dbId=$accountDbId name=$accountName")
        return true
    }

    private fun enableAccountSync(accountDbId: Long): Boolean {
        val targetDb = resolvedDbAccounts ?: return false
        val nameResult = runSql(targetDb, "SELECT name FROM accounts WHERE _id=$accountDbId;")
        val accountName = nameResult.out.firstOrNull()?.trim() ?: return false

        val result = Shell.cmd(
            "content call --uri content://com.android.sync/syncstatus --method enableAllSync --arg '$accountName:com.google' 2>&1"
        ).exec()

        Log.i(TAG, "Sync enabled for account dbId=$accountDbId name=$accountName")
        return true
    }

    // ─────────────────────────────────────────────────────────────
    // RECOVERY
    // ─────────────────────────────────────────────────────────────

    suspend fun restoreAllPoliciesForAccount(accountDbId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            resolveDbPaths()
            val sql = "DELETE FROM visibility WHERE accounts_id=$accountDbId;"
            val dbs = (listOfNotNull(resolvedDbVisibility, resolvedDbAccounts) + allDiscoveredDbs).distinct()
            var ok = false
            for (db in dbs) {
                if (runSql(db, sql).isSuccess) ok = true
            }
            broadcastAccountsChanged()
            Log.i(TAG, "Restored all policies for account dbId=$accountDbId")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            false
        }
    }

    suspend fun emergencyRecoverAllAccounts(): Boolean = withContext(Dispatchers.IO) {
        try {
            resolveDbPaths()
            val clearAll = "DELETE FROM visibility;"
            val dbs = (listOfNotNull(resolvedDbVisibility, resolvedDbAccounts) + allDiscoveredDbs).distinct()
            for (db in dbs) {
                runSql(db, clearAll)
            }
            broadcastAccountsChanged()
            Log.i(TAG, "Emergency recovery completed — cleared visibility in all databases")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Emergency recovery failed", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
    // VERIFICATION
    // ─────────────────────────────────────────────────────────────

    suspend fun verifyPolicy(
        accountDbId: Long,
        targetPackage: String,
        expectedState: VisibilityState
    ): VerificationResult = withContext(Dispatchers.IO) {
        try {
            resolveDbPaths()
            val targetDb = resolvedDbVisibility ?: resolvedDbAccounts ?: return@withContext VerificationResult.UNKNOWN

            when (targetPackage) {
                "__SYNC__" -> VerificationResult.UNKNOWN
                "__THIRD_PARTY__" -> {
                    val sql = "SELECT visibility FROM visibility WHERE accounts_id=$accountDbId AND package_name='__PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE__';"
                    val result = runSql(targetDb, sql)
                    val found = result.out.firstOrNull()?.trim()?.toIntOrNull()
                    if (found == expectedState.dbValue) VerificationResult.PASS
                    else VerificationResult.FAIL
                }
                else -> {
                    val sql = "SELECT visibility FROM visibility WHERE accounts_id=$accountDbId AND package_name='$targetPackage';"
                    val result = runSql(targetDb, sql)
                    val found = result.out.firstOrNull()?.trim()?.toIntOrNull()
                    when {
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

    suspend fun getVisibilityDump(accountDbId: Long): String = withContext(Dispatchers.IO) {
        resolveDbPaths()
        val targetDb = resolvedDbVisibility ?: resolvedDbAccounts ?: return@withContext "No database found"
        val result = runSql(targetDb, "SELECT package_name, visibility FROM visibility WHERE accounts_id=$accountDbId;")
        result.out.joinToString("\n").ifEmpty { "No visibility entries found" }
    }

    suspend fun getDumpsysSnapshot(): String = withContext(Dispatchers.IO) {
        val result = Shell.cmd("dumpsys account 2>&1 | grep -v 'authtoken\\|password\\|token' | head -100").exec()
        result.out.joinToString("\n")
    }

    private fun broadcastAccountsChanged() {
        Shell.cmd("am broadcast -a android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION 2>/dev/null").exec()
        Shell.cmd("am broadcast --user 0 -a android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION 2>/dev/null").exec()
    }
}
