package dev.accountguard.service

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.accountguard.data.db.AccountEntity
import dev.accountguard.data.db.PolicyEntity
import dev.accountguard.data.db.PolicyLogEntity
import dev.accountguard.data.model.*
import dev.accountguard.root.RootEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
// MAIN VIEW MODEL
// ─────────────────────────────────────────────────────────────

class MainViewModel(
    application: Application,
    private val repo: PolicyRepository
) : AndroidViewModel(application) {

    // Root status
    private val _rootStatus = MutableStateFlow<RootStatus?>(null)
    val rootStatus: StateFlow<RootStatus?> = _rootStatus.asStateFlow()

    // Detected accounts from system
    private val _detectedAccounts = MutableStateFlow<List<GoogleAccount>>(emptyList())
    val detectedAccounts: StateFlow<List<GoogleAccount>> = _detectedAccounts.asStateFlow()

    // Policies from Room (reactive)
    val allPolicies: StateFlow<List<PolicyEntity>> = repo.getAllPolicies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Logs
    val recentLogs: StateFlow<List<PolicyLogEntity>> = repo.getRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Loading and error states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    // Emergency recovery state
    private val _emergencyRestoreResult = MutableSharedFlow<Boolean>()
    val emergencyRestoreResult: SharedFlow<Boolean> = _emergencyRestoreResult.asSharedFlow()

    init {
        checkRoot()
        detectAccounts()
    }

    fun checkRoot() {
        viewModelScope.launch {
            _rootStatus.value = null
            val status = RootEngine.checkRootStatus()
            _rootStatus.value = status
        }
    }

    fun detectAccounts() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val accounts = repo.syncDetectedAccounts()
                _detectedAccounts.value = accounts
                _snackbarMessage.emit("Detected ${accounts.size} Google accounts")
            } catch (e: Exception) {
                _snackbarMessage.emit("Failed to detect accounts: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Get the composite status of an account (visible/partially hidden/hidden).
     */
    fun getAccountStatus(accountName: String): AccountStatus {
        val policies = allPolicies.value.filter {
            it.accountName == accountName && it.visibilityState == VisibilityState.HIDDEN
        }
        return when {
            policies.isEmpty() -> AccountStatus.FULLY_VISIBLE
            policies.size >= 3 -> AccountStatus.FULLY_HIDDEN
            else -> AccountStatus.PARTIALLY_HIDDEN
        }
    }

    /**
     * Check if a specific policy is active (account hidden from target).
     */
    fun isPolicyActive(accountName: String, targetPackage: String): Boolean {
        return allPolicies.value.any {
            it.accountName == accountName &&
            it.targetPackage == targetPackage &&
            it.visibilityState == VisibilityState.HIDDEN
        }
    }

    fun emergencyRestoreAll() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repo.emergencyRestore()
                _emergencyRestoreResult.emit(result)
                if (result) {
                    _snackbarMessage.emit("✓ All accounts restored — no re-login needed")
                } else {
                    _snackbarMessage.emit("✗ Emergency restore failed — check root access")
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// ACCOUNT POLICY VIEW MODEL
// Scoped to a specific account's policy screen
// ─────────────────────────────────────────────────────────────

class AccountPolicyViewModel(
    application: Application,
    private val repo: PolicyRepository,
    private val account: GoogleAccount
) : AndroidViewModel(application) {

    // Policies for this specific account
    val policies: StateFlow<List<PolicyEntity>> = repo.getPoliciesForAccount(account.name)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isApplying = MutableStateFlow(false)
    val isApplying: StateFlow<Boolean> = _isApplying.asStateFlow()

    private val _operationResult = MutableSharedFlow<OperationResult>()
    val operationResult: SharedFlow<OperationResult> = _operationResult.asSharedFlow()

    private val _visibilityDump = MutableStateFlow("")
    val visibilityDump: StateFlow<String> = _visibilityDump.asStateFlow()

    data class OperationResult(
        val success: Boolean,
        val message: String,
        val targetName: String
    )

    /**
     * Toggle visibility for a target.
     * If currently hidden → show (restore).
     * If currently visible → hide.
     */
    fun togglePolicy(targetPackage: String, targetDisplayName: String) {
        viewModelScope.launch {
            _isApplying.value = true
            try {
                val currentPolicies = policies.value
                val existingPolicy = currentPolicies.find { it.targetPackage == targetPackage }
                val isCurrentlyHidden = existingPolicy?.visibilityState == VisibilityState.HIDDEN

                val newState = if (isCurrentlyHidden) VisibilityState.VISIBLE else VisibilityState.HIDDEN
                val success = repo.applyPolicy(account, targetPackage, newState)

                val msg = if (success) {
                    if (newState == VisibilityState.HIDDEN)
                        "✓ Hidden from $targetDisplayName"
                    else
                        "✓ Restored to $targetDisplayName — no re-login needed"
                } else {
                    "✗ Operation failed — check root access"
                }

                _operationResult.emit(OperationResult(success, msg, targetDisplayName))
            } finally {
                _isApplying.value = false
            }
        }
    }

    /**
     * Apply a specific visibility state (used by bulk actions).
     */
    fun applyPolicy(targetPackage: String, state: VisibilityState, targetDisplayName: String) {
        viewModelScope.launch {
            _isApplying.value = true
            try {
                val success = repo.applyPolicy(account, targetPackage, state)
                val action = if (state == VisibilityState.HIDDEN) "Hidden from" else "Restored to"
                _operationResult.emit(OperationResult(
                    success,
                    if (success) "✓ $action $targetDisplayName" else "✗ Operation failed",
                    targetDisplayName
                ))
            } finally {
                _isApplying.value = false
            }
        }
    }

    /**
     * Restore this account fully — removes ALL policies, makes account visible everywhere.
     * This is the "un-hide" operation. No re-login needed.
     */
    fun restoreAccount() {
        viewModelScope.launch {
            _isApplying.value = true
            try {
                val success = repo.restoreAccount(account)
                _operationResult.emit(OperationResult(
                    success,
                    if (success) "✓ ${account.name} fully restored — no re-login needed"
                    else "✗ Restore failed",
                    "All Targets"
                ))
            } finally {
                _isApplying.value = false
            }
        }
    }

    /**
     * Check if a specific target has a HIDDEN policy.
     */
    fun isHiddenFrom(targetPackage: String): Boolean {
        return policies.value.any {
            it.targetPackage == targetPackage && it.visibilityState == VisibilityState.HIDDEN
        }
    }

    fun loadVisibilityDump() {
        viewModelScope.launch {
            _visibilityDump.value = repo.getVisibilityDump(account)
        }
    }
}
