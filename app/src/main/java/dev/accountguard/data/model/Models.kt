package dev.accountguard.data.model

/**
 * Represents a detected Google account on the device.
 * This is READ from accounts_de.db — it is NEVER modified/deleted.
 */
data class GoogleAccount(
    val dbId: Long,           // _id from accounts table in accounts_de.db
    val name: String,         // email address, e.g. "user@gmail.com"
    val type: String = "com.google",
    val displayName: String = name.substringBefore("@")
)

/**
 * A target represents an application or system component
 * that can receive a per-account visibility policy.
 */
data class Target(
    val packageName: String,    // e.g. "com.google.android.youtube"
    val displayName: String,    // e.g. "YouTube"
    val category: TargetCategory,
    val supportLevel: SupportLevel,
    val iconResId: Int = 0,
    val description: String = ""
)

enum class TargetCategory {
    SYSTEM,    // Android OS / Settings
    GOOGLE,    // First-party Google apps
    SYNC,      // Synchronization control
    THIRD_PARTY
}

enum class SupportLevel {
    SUPPORTED,     // Works reliably via DB visibility table
    EXPERIMENTAL,  // Partially works; limitations documented
    UNSUPPORTED    // Cannot be controlled (GMS itself)
}

/**
 * Visibility state for a single (account, target) pair.
 * VISIBLE = account is visible to target
 * HIDDEN  = account is hidden from target
 */
enum class VisibilityState(val dbValue: Int) {
    VISIBLE(1),   // AccountManager.VISIBILITY_VISIBLE
    HIDDEN(4)     // AccountManager.VISIBILITY_NOT_VISIBLE
}

/**
 * A policy record: one account, one target, one visibility state.
 */
data class Policy(
    val id: Long = 0,
    val accountName: String,
    val targetPackageName: String,
    val visibilityState: VisibilityState,
    val appliedAt: Long = 0,
    val lastVerifiedAt: Long = 0,
    val verificationResult: VerificationResult = VerificationResult.NOT_VERIFIED
)

enum class VerificationResult {
    PASS,         // Policy applied and confirmed
    FAIL,         // Policy not applied or contradicted
    PARTIAL,      // Applied in DB but Settings-level bypass active
    UNKNOWN,      // Cannot determine
    NOT_VERIFIED  // Verification not yet run
}

/**
 * Composite status of an account across all its policies.
 */
enum class AccountStatus {
    FULLY_VISIBLE,     // No policies applied — visible everywhere
    PARTIALLY_HIDDEN,  // Some policies applied
    FULLY_HIDDEN,      // All supported targets are hidden
    ERROR              // One or more policies failed to apply
}

/**
 * Log entry for the diagnostics/logging screen.
 */
data class LogEntry(
    val id: Long = 0,
    val timestamp: Long,
    val accountName: String,
    val targetPackage: String,
    val action: String,   // HIDE, SHOW, VERIFY, DETECT
    val result: String,
    val details: String = ""
)

/**
 * Root/KSU status snapshot.
 */
data class RootStatus(
    val isRootAvailable: Boolean,
    val rootType: String,          // "KernelSU", "Magisk", "Unknown"
    val isPrivilegeVerified: Boolean,
    val ksuVersion: String = "",
    val vectorDetected: Boolean = false,
    val errorMessage: String = "",
    val engineName: String = "",
    val resolvedDbPath: String = ""
)
