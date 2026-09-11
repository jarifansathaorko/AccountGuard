package dev.accountguard.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.accountguard.data.model.*
import dev.accountguard.service.AccountPolicyViewModel
import dev.accountguard.ui.components.*
import dev.accountguard.ui.theme.GuardColors

/**
 * Account Policy Screen — the core feature screen.
 *
 * Shows a list of targets organized by category, each with a toggle.
 * Toggling HIDES or SHOWS the selected account from that target.
 *
 * KEY BEHAVIORS:
 * - HIDE: writes VISIBILITY_NOT_VISIBLE to accounts_de.db for (account, target)
 * - SHOW: removes the visibility entry (restores default visible state)
 * - No re-login required for either operation
 * - The account is never deleted
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPolicyScreen(
    account: GoogleAccount,
    viewModel: AccountPolicyViewModel,
    onBack: () -> Unit
) {
    val policies by viewModel.policies.collectAsState()
    val isApplying by viewModel.isApplying.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showDumpDialog by remember { mutableStateOf(false) }
    var dumpText by remember { mutableStateOf("Loading...") }

    // Handle operation results
    LaunchedEffect(Unit) {
        viewModel.operationResult.collect { result ->
            snackbarHostState.showSnackbar(result.message)
        }
    }

    // Categorize targets
    val systemTargets = TargetRegistry.byCategory(TargetCategory.SYSTEM)
    val googleTargets = TargetRegistry.byCategory(TargetCategory.GOOGLE)
    val syncTargets = TargetRegistry.byCategory(TargetCategory.SYNC)
    val thirdPartyTargets = TargetRegistry.byCategory(TargetCategory.THIRD_PARTY)

    val activeHiddenCount = policies.count { it.visibilityState == VisibilityState.HIDDEN }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = GuardColors.DeepNavy,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = GuardColors.CyanVibrant)
                    }
                },
                title = {
                    Column {
                        Text(
                            account.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = GuardColors.TextPrimary
                        )
                        Text(
                            account.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = GuardColors.TextMuted
                        )
                    }
                },
                actions = {
                    // Restore all button
                    if (activeHiddenCount > 0) {
                        IconButton(onClick = { showRestoreDialog = true }) {
                            Icon(Icons.Outlined.SettingsBackupRestore, "Restore All",
                                tint = GuardColors.GreenSuccess)
                        }
                    }
                    IconButton(onClick = {
                        showDumpDialog = true
                        viewModel.loadVisibilityDump()
                    }) {
                        Icon(Icons.Outlined.BugReport, "Diagnostics", tint = GuardColors.TextMuted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GuardColors.NavyDark.copy(alpha = 0.95f)
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {

            // ── ACCOUNT SUMMARY CARD ──
            item {
                AccountSummaryCard(
                    account = account,
                    activeHiddenCount = activeHiddenCount,
                    totalTargets = TargetRegistry.SUPPORTED.size + TargetRegistry.EXPERIMENTAL.size
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            // ── SYSTEM TARGETS ──
            item {
                CategoryHeader("System & OS") {
                    Icon(Icons.Outlined.Settings, null, tint = GuardColors.CyanVibrant,
                        modifier = Modifier.size(14.dp))
                }
            }
            item {
                GlassCard {
                    systemTargets.forEach { target ->
                        TargetToggleRow(
                            target = target,
                            isHidden = viewModel.isHiddenFrom(target.packageName),
                            isApplying = isApplying,
                            onToggle = { viewModel.togglePolicy(target.packageName, target.displayName) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }

            // ── GOOGLE APPS ──
            item {
                CategoryHeader("Google Apps") {
                    Icon(Icons.Outlined.Apps, null, tint = GuardColors.CyanVibrant,
                        modifier = Modifier.size(14.dp))
                }
            }
            item {
                GlassCard {
                    googleTargets.forEach { target ->
                        TargetToggleRow(
                            target = target,
                            isHidden = viewModel.isHiddenFrom(target.packageName),
                            isApplying = isApplying,
                            onToggle = { viewModel.togglePolicy(target.packageName, target.displayName) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }

            // ── SYNC CONTROL ──
            item {
                CategoryHeader("Sync Control") {
                    Icon(Icons.Outlined.Sync, null, tint = GuardColors.CyanVibrant,
                        modifier = Modifier.size(14.dp))
                }
            }
            item {
                GlassCard {
                    syncTargets.forEach { target ->
                        TargetToggleRow(
                            target = target,
                            isHidden = viewModel.isHiddenFrom(target.packageName),
                            isApplying = isApplying,
                            onToggle = { viewModel.togglePolicy(target.packageName, target.displayName) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }

            // ── THIRD-PARTY APPS ──
            item {
                CategoryHeader("Third-Party Apps") {
                    Icon(Icons.Outlined.GridView, null, tint = GuardColors.CyanVibrant,
                        modifier = Modifier.size(14.dp))
                }
            }
            item {
                GlassCard {
                    thirdPartyTargets.forEach { target ->
                        TargetToggleRow(
                            target = target,
                            isHidden = viewModel.isHiddenFrom(target.packageName),
                            isApplying = isApplying,
                            onToggle = { viewModel.togglePolicy(target.packageName, target.displayName) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }

            // ── HOW THIS WORKS INFO ──
            item { HowItWorksCard() }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // Restore All dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            containerColor = GuardColors.NavyMid,
            icon = {
                Icon(Icons.Filled.SettingsBackupRestore, null,
                    tint = GuardColors.GreenSuccess, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Restore All Visibility",
                    color = GuardColors.TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Remove all $activeHiddenCount hiding policies for ${account.displayName}?",
                        color = GuardColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "✓ No re-login required\n✓ Account was never deleted\n✓ Immediate effect",
                        color = GuardColors.GreenSuccess,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showRestoreDialog = false; viewModel.restoreAccount() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardColors.GreenSuccess,
                        contentColor = GuardColors.DeepNavy
                    )
                ) {
                    Text("Restore All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("Cancel", color = GuardColors.TextSecondary)
                }
            }
        )
    }

    // Diagnostics dump dialog
    val visibilityDump by viewModel.visibilityDump.collectAsState()
    if (showDumpDialog) {
        AlertDialog(
            onDismissRequest = { showDumpDialog = false },
            containerColor = GuardColors.NavyMid,
            modifier = Modifier.fillMaxWidth(),
            title = {
                Text("Visibility DB Dump", color = GuardColors.CyanVibrant,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        visibilityDump.ifEmpty { "Loading..." },
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardColors.GreenSuccess,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDumpDialog = false }) {
                    Text("Close", color = GuardColors.CyanVibrant)
                }
            }
        )
    }
}

@Composable
private fun AccountSummaryCard(
    account: GoogleAccount,
    activeHiddenCount: Int,
    totalTargets: Int
) {
    val progress = if (totalTargets > 0) activeHiddenCount.toFloat() / totalTargets else 0f
    val progressColor = when {
        activeHiddenCount == 0 -> GuardColors.GreenSuccess
        activeHiddenCount >= totalTargets * 0.7f -> GuardColors.RedError
        else -> GuardColors.AmberWarning
    }

    GlassCard(
        borderColor = progressColor.copy(0.3f)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                GuardColors.CyanVibrant.copy(0.3f),
                                GuardColors.PurpleAccent.copy(0.15f)
                            )
                        )
                    )
                    .border(2.dp, GuardColors.CyanVibrant.copy(0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    account.name.first().uppercaseChar().toString(),
                    color = GuardColors.CyanVibrant,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                account.name,
                style = MaterialTheme.typography.bodyMedium,
                color = GuardColors.TextPrimary,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            // Progress bar
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Hidden from $activeHiddenCount of $totalTargets targets",
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardColors.TextMuted
                    )
                    Text(
                        if (activeHiddenCount == 0) "VISIBLE" else "PARTIALLY HIDDEN",
                        style = MaterialTheme.typography.labelSmall,
                        color = progressColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = progressColor,
                    trackColor = GuardColors.NavySurface
                )
            }
        }
    }
}

@Composable
private fun HowItWorksCard() {
    GlassCard(borderColor = GuardColors.PurpleDim.copy(alpha = 0.3f)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Info, null, tint = GuardColors.PurpleAccent, modifier = Modifier.size(16.dp))
                Text("How This Works", style = MaterialTheme.typography.labelLarge,
                    color = GuardColors.PurpleAccent, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = GuardColors.PurpleDim.copy(0.3f))
            val bullets = listOf(
                "🔒 Toggling OFF hides the account from that app's account list",
                "🔓 Toggling ON instantly restores it — no re-login ever needed",
                "💾 Your credentials, emails, and data are completely untouched",
                "⚡ Changes take effect immediately after the next app open",
                "🔄 Persists through device reboots automatically",
                "⚙️ Settings hiding requires Vector/LSPosed (shown in root panel)"
            )
            bullets.forEach { bullet ->
                Text(bullet, style = MaterialTheme.typography.bodySmall, color = GuardColors.TextSecondary)
            }
        }
    }
}
