package dev.accountguard.data.model

/**
 * Registry of all supported, experimental, and unsupported targets.
 * Only targets listed here are shown in the UI.
 *
 * Evidence for each:
 * - SUPPORTED: Uses AccountManager.getAccountsByType() → visibility table filtering works
 * - EXPERIMENTAL: Has additional state or partial coverage (Chrome)
 * - UNSUPPORTED: GMS authenticator — always sees all accounts by AOSP design
 */
object TargetRegistry {

    val ALL: List<Target> = listOf(

        // ══════════════════════════════════════════
        // SYSTEM TARGETS
        // ══════════════════════════════════════════
        Target(
            packageName = "com.android.settings",
            displayName = "Android Settings",
            category = TargetCategory.SYSTEM,
            supportLevel = SupportLevel.EXPERIMENTAL,
            description = "Requires Vector/LSPosed system_server hook (active if Vector is installed). " +
                          "Without Vector, Settings shows all accounts due to Android's SYSTEM_UID bypass."
        ),
        Target(
            packageName = "__ACCOUNT_PICKER__",
            displayName = "Account Pickers",
            category = TargetCategory.SYSTEM,
            supportLevel = SupportLevel.SUPPORTED,
            description = "System account picker dialogs shown when apps request account selection."
        ),

        // ══════════════════════════════════════════
        // GOOGLE APPS — FULLY SUPPORTED
        // These all call AccountManager.getAccountsByType("com.google")
        // ══════════════════════════════════════════
        Target(
            packageName = "com.google.android.gm",
            displayName = "Gmail",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Gmail's account switcher and inbox list."
        ),
        Target(
            packageName = "com.android.vending",
            displayName = "Play Store",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Play Store's account switcher. " +
                          "Previously purchased apps continue downloading."
        ),
        Target(
            packageName = "com.google.android.youtube",
            displayName = "YouTube",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from YouTube's account switcher."
        ),
        Target(
            packageName = "com.google.android.apps.youtube.creator",
            displayName = "YouTube Studio",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from YouTube Studio."
        ),
        Target(
            packageName = "com.google.android.apps.docs",
            displayName = "Google Drive",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Google Drive."
        ),
        Target(
            packageName = "com.google.android.apps.photos",
            displayName = "Google Photos",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Google Photos."
        ),
        Target(
            packageName = "com.google.android.apps.maps",
            displayName = "Google Maps",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Google Maps."
        ),
        Target(
            packageName = "com.google.android.calendar",
            displayName = "Google Calendar",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Google Calendar."
        ),
        Target(
            packageName = "com.google.android.apps.meetings",
            displayName = "Google Meet",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Google Meet."
        ),
        Target(
            packageName = "com.google.android.contacts",
            displayName = "Google Contacts",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Google Contacts."
        ),
        Target(
            packageName = "com.google.android.googlequicksearchbox",
            displayName = "Google App",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from the Google search app."
        ),
        Target(
            packageName = "com.google.android.keep",
            displayName = "Google Keep",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Google Keep Notes."
        ),
        Target(
            packageName = "com.google.android.apps.tasks",
            displayName = "Google Tasks",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from Google Tasks."
        ),

        // ══════════════════════════════════════════
        // SYNC CONTROL (separate mechanism from visibility)
        // ══════════════════════════════════════════
        Target(
            packageName = "__SYNC__",
            displayName = "Account Sync",
            category = TargetCategory.SYNC,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Pauses all automatic synchronization for this account. " +
                          "Does NOT affect visibility or authentication. " +
                          "Fully reversible — resuming sync does not require re-login."
        ),

        // ══════════════════════════════════════════
        // THIRD-PARTY (uses legacy visibility key)
        // ══════════════════════════════════════════
        Target(
            packageName = "__THIRD_PARTY__",
            displayName = "All Other Apps",
            category = TargetCategory.THIRD_PARTY,
            supportLevel = SupportLevel.SUPPORTED,
            description = "Hides the account from all installed apps not explicitly listed above. " +
                          "Uses Android's default visibility key. " +
                          "May affect apps that legitimately need account access."
        ),

        // ══════════════════════════════════════════
        // UNSUPPORTED — shown with lock icon, cannot be toggled
        // ══════════════════════════════════════════
        Target(
            packageName = "com.google.android.gms",
            displayName = "Google Play Services",
            category = TargetCategory.GOOGLE,
            supportLevel = SupportLevel.UNSUPPORTED,
            description = "Cannot be controlled. Google Play Services IS the authenticator for " +
                          "Google accounts and always has visibility by Android design. " +
                          "This is what keeps your account credentials safe and functional."
        ),
    )

    val SUPPORTED: List<Target> = ALL.filter { it.supportLevel == SupportLevel.SUPPORTED }
    val EXPERIMENTAL: List<Target> = ALL.filter { it.supportLevel == SupportLevel.EXPERIMENTAL }
    val UNSUPPORTED: List<Target> = ALL.filter { it.supportLevel == SupportLevel.UNSUPPORTED }

    fun find(packageName: String): Target? = ALL.find { it.packageName == packageName }

    fun byCategory(category: TargetCategory) = ALL.filter { it.category == category }
}
