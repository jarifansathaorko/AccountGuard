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
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.accountguard.data.model.*
import dev.accountguard.service.MainViewModel
import dev.accountguard.ui.components.*
import dev.accountguard.ui.theme.GuardColors
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main Screen — shows:
 * 1. Root / KSU status panel
 * 2. Vector/LSPosed detection
 * 3. Google account list with status badges
 * 4. Quick actions (refresh, emergency restore)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onAccountSelected: (GoogleAccount) -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val rootStatus by viewModel.rootStatus.collectAsState()
    val accounts by viewModel.detectedAccounts.collectAsState()
    val allPolicies by viewModel.allPolicies.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showEmergencyDialog by remember { mutableStateOf(false) }

    // Snackbar messages
    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Animated background gradient
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val bgShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "bgShift"
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = GuardColors.DeepNavy,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "AccountGuard",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = GuardColors.TextPrimary
                        )
                        Text(
                            "Selective Account Visibility",
                            style = MaterialTheme.typography.labelSmall,
                            color = GuardColors.TextMuted
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.detectAccounts() }) {
                        Icon(Icons.Outlined.Refresh, "Refresh", tint = GuardColors.CyanVibrant)
                    }
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Outlined.Article, "Logs", tint = GuardColors.TextSecondary)
                    }
                    IconButton(onClick = { showEmergencyDialog = true }) {
                        Icon(Icons.Outlined.HealthAndSafety, "Emergency Restore", tint = GuardColors.AmberWarning)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GuardColors.NavyDark.copy(alpha = 0.95f),
                    scrolledContainerColor = GuardColors.NavyDark
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {

            // ── ROOT STATUS PANEL ──
            item {
                RootStatusPanel(rootStatus = rootStatus)
            }

            // ── INFO BANNER ──
            item {
                InfoBanner()
            }

            // ── SECTION HEADER: Accounts ──
            item {
                CategoryHeader("Google Accounts") {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = GuardColors.CyanVibrant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // ── LOADING SKELETONS ──
            if (isLoading) {
                items(3) {
                    ShimmerCard(modifier = Modifier.padding(vertical = 2.dp))
                }
            }

            // ── ACCOUNT LIST ──
            if (!isLoading) {
                if (accounts.isEmpty()) {
                    item {
                        EmptyAccountsPlaceholder(
                            hasRoot = rootStatus?.isRootAvailable == true
                        )
                    }
                } else {
                    items(accounts, key = { it.name }) { account ->
                        val status = viewModel.getAccountStatus(account.name)
                        val hiddenCount = allPolicies.count {
                            it.accountName == account.name && it.visibilityState == VisibilityState.HIDDEN
                        }

                        AccountRowCard(
                            account = account,
                            status = status,
                            hiddenTargetCount = hiddenCount,
                            onClick = { onAccountSelected(account) }
                        )
                    }
                }
            }

            // ── BOTTOM SPACER ──
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // Emergency restore dialog
    if (showEmergencyDialog) {
        EmergencyRestoreDialog(
            onConfirm = {
                showEmergencyDialog = false
                viewModel.emergencyRestoreAll()
            },
            onDismiss = { showEmergencyDialog = false }
        )
    }
}

@Composable
private fun RootStatusPanel(rootStatus: RootStatus?) {
    GlassCard(
        borderColor = when {
            rootStatus == null -> GuardColors.GlassBorder
            rootStatus.isPrivilegeVerified -> GuardColors.GreenSuccess.copy(0.3f)
            rootStatus.isRootAvailable -> GuardColors.AmberWarning.copy(0.3f)
            else -> GuardColors.RedError.copy(0.3f)
        }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "System Access",
                    style = MaterialTheme.typography.titleSmall,
                    color = GuardColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                if (rootStatus == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = GuardColors.CyanVibrant,
                        strokeWidth = 2.dp
                    )
                } else {
                    RootStatusChip(
                        isAvailable = rootStatus.isRootAvailable,
                        isVerified = rootStatus.isPrivilegeVerified,
                        rootType = rootStatus.rootType
                    )
                }
            }

            if (rootStatus != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = GuardColors.GlassBorder)
                Spacer(Modifier.height(12.dp))

                // Status rows
                StatusRow("Root Type", rootStatus.rootType,
                    if (rootStatus.isRootAvailable) GuardColors.GreenSuccess else GuardColors.RedError)

                val dbAccessText = if (rootStatus.isPrivilegeVerified) {
                    if (rootStatus.engineName.isNotEmpty()) "Accessible (${rootStatus.engineName})" else "Database verified"
                } else {
                    if (rootStatus.errorMessage.isNotEmpty()) "Error: ${rootStatus.errorMessage.take(35)}" else "Cannot access"
                }
                StatusRow("DB Access", dbAccessText,
                    if (rootStatus.isPrivilegeVerified) GuardColors.GreenSuccess else GuardColors.RedError)

                if (rootStatus.resolvedDbPath.isNotEmpty()) {
                    val dbName = rootStatus.resolvedDbPath.substringAfterLast('/')
                    StatusRow("Active DB", dbName, GuardColors.TextSecondary)
                }

                StatusRow("Vector/LSPosed",
                    if (rootStatus.vectorDetected) "Detected — Settings hiding available" else "Not detected — Settings not covered",
                    if (rootStatus.vectorDetected) GuardColors.GreenSuccess else GuardColors.AmberWarning)

                if (rootStatus.ksuVersion.isNotEmpty()) {
                    StatusRow("KSU Version", rootStatus.ksuVersion, GuardColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = GuardColors.TextMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 200.dp)
        )
    }
}

@Composable
private fun InfoBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = GuardColors.CyanDim.copy(alpha = 0.3f),
        border = BorderStroke(0.5.dp, GuardColors.CyanVibrant.copy(0.2f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = GuardColors.CyanVibrant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "Hiding an account is temporary and non-destructive. " +
                "Your login is always preserved — unhiding requires no re-login.",
                style = MaterialTheme.typography.bodySmall,
                color = GuardColors.TextSecondary
            )
        }
    }
}

@Composable
private fun EmptyAccountsPlaceholder(hasRoot: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            if (hasRoot) Icons.Outlined.AccountCircle else Icons.Outlined.Lock,
            contentDescription = null,
            tint = GuardColors.TextMuted,
            modifier = Modifier.size(56.dp)
        )
        Text(
            if (hasRoot) "No Google accounts found" else "Root access required",
            style = MaterialTheme.typography.titleMedium,
            color = GuardColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Text(
            if (hasRoot) "Tap refresh to scan again"
            else "Grant root access in KernelSU manager, then restart the app",
            style = MaterialTheme.typography.bodySmall,
            color = GuardColors.TextMuted,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmergencyRestoreDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardColors.NavyMid,
        icon = {
            Icon(
                Icons.Filled.HealthAndSafety,
                contentDescription = null,
                tint = GuardColors.AmberWarning,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                "Emergency Restore",
                color = GuardColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This will immediately restore ALL accounts to full visibility — removing all policies for all accounts.",
                    color = GuardColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "✓ No re-login required — accounts were never deleted",
                    color = GuardColors.GreenSuccess,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "✓ All apps will be able to see all accounts again",
                    color = GuardColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GuardColors.AmberWarning,
                    contentColor = GuardColors.DeepNavy
                )
            ) {
                Text("Restore All", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GuardColors.TextSecondary)
            }
        }
    )
}
