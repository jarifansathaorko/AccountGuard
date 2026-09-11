package dev.accountguard.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.accountguard.data.model.*
import dev.accountguard.ui.theme.GuardColors

// ─────────────────────────────────────────────────────────────
// GLASS CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = GuardColors.GlassBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = GuardColors.GlassCard.copy(alpha = 0.7f)
        ),
        border = BorderStroke(0.5.dp, borderColor)
    ) {
        Column(content = content)
    }
}

// ─────────────────────────────────────────────────────────────
// ROOT STATUS CHIP
// ─────────────────────────────────────────────────────────────

@Composable
fun RootStatusChip(
    isAvailable: Boolean,
    isVerified: Boolean,
    rootType: String,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, icon, label) = when {
        !isAvailable -> Quadruple(GuardColors.RedDim, GuardColors.RedError, Icons.Filled.Block, "No Root")
        isVerified -> Quadruple(GuardColors.GreenDim, GuardColors.GreenSuccess, Icons.Filled.Shield, "$rootType Active")
        else -> Quadruple(GuardColors.AmberDim, GuardColors.AmberWarning, Icons.Filled.Warning, "Unverified")
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        border = BorderStroke(0.5.dp, textColor.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(14.dp))
            Text(label, color = textColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

// Helper data class
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// ─────────────────────────────────────────────────────────────
// ACCOUNT ROW CARD — for the main list screen
// ─────────────────────────────────────────────────────────────

@Composable
fun AccountRowCard(
    account: GoogleAccount,
    status: AccountStatus,
    hiddenTargetCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (statusColor, statusLabel) = when (status) {
        AccountStatus.FULLY_VISIBLE    -> GuardColors.GreenSuccess to "VISIBLE"
        AccountStatus.PARTIALLY_HIDDEN -> GuardColors.AmberWarning to "PARTIAL"
        AccountStatus.FULLY_HIDDEN     -> GuardColors.RedError     to "HIDDEN"
        AccountStatus.ERROR            -> GuardColors.RedError      to "ERROR"
    }

    val borderColor = if (status != AccountStatus.FULLY_VISIBLE)
        statusColor.copy(alpha = pulseAlpha * 0.5f)
    else
        GuardColors.GlassBorder

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        borderColor = borderColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar circle with initial
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(GuardColors.CyanVibrant.copy(0.3f), GuardColors.PurpleAccent.copy(0.2f)),
                            radius = 80f
                        )
                    )
                    .border(1.dp, GuardColors.CyanVibrant.copy(0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = account.name.first().uppercaseChar().toString(),
                    color = GuardColors.CyanVibrant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hiddenTargetCount > 0) {
                    Text(
                        text = "Hidden from $hiddenTargetCount target${if (hiddenTargetCount > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardColors.TextSecondary
                    )
                } else {
                    Text(
                        text = "Visible everywhere",
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardColors.TextMuted
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Status badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = statusColor.copy(alpha = 0.15f),
                border = BorderStroke(0.5.dp, statusColor.copy(alpha = 0.4f))
            ) {
                Text(
                    text = statusLabel,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Open",
                tint = GuardColors.TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// TARGET TOGGLE ROW — for the policy editor screen
// ─────────────────────────────────────────────────────────────

@Composable
fun TargetToggleRow(
    target: Target,
    isHidden: Boolean,
    isApplying: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isInteractive = target.supportLevel != SupportLevel.UNSUPPORTED
    val toggleColor = when {
        !isInteractive -> GuardColors.GrayDisabled
        isHidden -> GuardColors.RedError
        else -> GuardColors.GreenSuccess
    }

    val bgColor = when {
        !isInteractive -> Color.Transparent
        isHidden -> GuardColors.RedDim.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    val rowModifier = if (isInteractive) {
        modifier.clickable(enabled = !isApplying) { onToggle() }
    } else {
        modifier
    }

    Surface(
        modifier = rowModifier.fillMaxWidth(),
        color = bgColor,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Support level indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (target.supportLevel) {
                            SupportLevel.SUPPORTED -> GuardColors.GreenSuccess
                            SupportLevel.EXPERIMENTAL -> GuardColors.AmberWarning
                            SupportLevel.UNSUPPORTED -> GuardColors.GrayDisabled
                        }
                    )
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = target.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isInteractive) GuardColors.TextPrimary else GuardColors.TextMuted,
                        fontWeight = if (isHidden) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (target.supportLevel == SupportLevel.EXPERIMENTAL) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = GuardColors.AmberDim
                        ) {
                            Text(
                                "EXP",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = GuardColors.AmberWarning,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    if (target.supportLevel == SupportLevel.UNSUPPORTED) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = GuardColors.GrayDisabled.copy(alpha = 0.2f)
                        ) {
                            Text(
                                "N/A",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = GuardColors.GrayDisabled,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
                if (target.description.isNotEmpty()) {
                    Text(
                        text = target.description.take(60) + if (target.description.length > 60) "…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardColors.TextMuted,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Toggle or lock icon
            if (!isInteractive) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Not controllable",
                    tint = GuardColors.GrayDisabled,
                    modifier = Modifier.size(20.dp)
                )
            } else if (isApplying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = GuardColors.CyanVibrant,
                    strokeWidth = 2.dp
                )
            } else {
                // Custom switch-style toggle
                val switchBg = if (isHidden) GuardColors.RedError.copy(0.8f) else GuardColors.NavySurface
                val thumbColor = if (isHidden) Color.White else GuardColors.GrayDisabled

                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(switchBg)
                        .border(1.dp, toggleColor.copy(0.4f), RoundedCornerShape(12.dp)),
                    contentAlignment = if (isHidden) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .padding(3.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(thumbColor)
                    )
                }
            }
        }
    }

    HorizontalDivider(
        color = GuardColors.GlassBorder.copy(alpha = 0.3f),
        thickness = 0.5.dp
    )
}

// ─────────────────────────────────────────────────────────────
// CATEGORY HEADER
// ─────────────────────────────────────────────────────────────

@Composable
fun CategoryHeader(text: String, icon: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icon()
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = GuardColors.CyanVibrant,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = GuardColors.CyanVibrant.copy(alpha = 0.2f)
        )
    }
}

// ─────────────────────────────────────────────────────────────
// SHIMMER LOADING SKELETON
// ─────────────────────────────────────────────────────────────

@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmerX"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        GuardColors.GlassCard,
                        GuardColors.NavySurface.copy(alpha = 0.8f),
                        GuardColors.GlassCard
                    ),
                    start = Offset(shimmerX * 600f, 0f),
                    end = Offset(shimmerX * 600f + 400f, 0f)
                )
            )
    )
}

// ─────────────────────────────────────────────────────────────
// LOG ENTRY ROW
// ─────────────────────────────────────────────────────────────

@Composable
fun LogEntryRow(
    timestamp: String,
    action: String,
    accountName: String,
    target: String,
    result: String,
    details: String
) {
    val resultColor = when (result) {
        "PASS" -> GuardColors.GreenSuccess
        "FAIL" -> GuardColors.RedError
        "PARTIAL" -> GuardColors.AmberWarning
        else -> GuardColors.TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = GuardColors.TextMuted,
            modifier = Modifier.width(60.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = GuardColors.CyanDim
                ) {
                    Text(
                        action,
                        Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardColors.CyanVibrant,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    accountName.substringBefore("@"),
                    style = MaterialTheme.typography.labelSmall,
                    color = GuardColors.TextSecondary
                )
                Text("→", style = MaterialTheme.typography.labelSmall, color = GuardColors.TextMuted)
                Text(
                    target.substringAfterLast(".").take(12),
                    style = MaterialTheme.typography.labelSmall,
                    color = GuardColors.TextSecondary,
                    maxLines = 1
                )
            }
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall,
                    color = GuardColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = resultColor.copy(alpha = 0.15f)
        ) {
            Text(
                result,
                Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = resultColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
