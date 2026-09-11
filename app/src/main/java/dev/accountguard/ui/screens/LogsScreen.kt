package dev.accountguard.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.accountguard.data.db.PolicyLogEntity
import dev.accountguard.ui.components.CategoryHeader
import dev.accountguard.ui.components.GlassCard
import dev.accountguard.ui.components.LogEntryRow
import dev.accountguard.ui.theme.GuardColors
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logs: List<PolicyLogEntity>,
    onBack: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
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
                        Text("Operation Log", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = GuardColors.TextPrimary)
                        Text("${logs.size} recent entries", style = MaterialTheme.typography.labelSmall,
                            color = GuardColors.TextMuted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GuardColors.NavyDark.copy(alpha = 0.95f)
                )
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No operations logged yet", style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.TextMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item { CategoryHeader("Recent Operations") }
                item {
                    GlassCard {
                        Column(Modifier.padding(8.dp)) {
                            logs.forEach { log ->
                                LogEntryRow(
                                    timestamp = sdf.format(Date(log.timestamp)),
                                    action = log.action,
                                    accountName = log.accountName,
                                    target = log.targetPackage,
                                    result = log.result,
                                    details = log.details
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}
