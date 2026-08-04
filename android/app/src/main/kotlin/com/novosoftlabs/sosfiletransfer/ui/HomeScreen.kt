package com.novosoftlabs.sosfiletransfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novosoftlabs.sosfiletransfer.ui.theme.calmSignal

private data class Stat(val value: String, val label: String)

private val stats = listOf(
    Stat("64 MB", "max file size, gzip'd automatically"),
    Stat("SHA-256", "verified before a file is ever saved"),
    Stat("Zero", "network path, accounts, or pairing"),
    Stat("~129 KB/s", "phone to phone, best case"),
)

@Composable
fun HomeScreen(onSend: () -> Unit, onReceive: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(
                    "SOS — SEND OVER SCREEN",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    buildAnnotatedHeadline(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Move a file or text to another device using nothing but a screen and a camera. No account, pairing, or cloud storage in between.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        item {
            ActionCard(
                kicker = "THIS SCREEN TRANSMITS",
                title = "Send a file or text",
                description = "Any file up to 64 MB, or a pasted text snippet. Compressed when it helps.",
                actionLabel = "SEND",
                onClick = onSend,
            )
        }
        item {
            ActionCard(
                kicker = "THIS CAMERA RECEIVES",
                title = "Point and recover",
                description = "Detects on its own whether a file or text is arriving, SHA-256 verified.",
                actionLabel = "RECEIVE",
                onClick = onReceive,
            )
        }

        // A 2x2 grid, not a horizontally-scrolling row — four fixed items
        // never need scroll affordance, and a scrollable row inside a
        // scrollable column reads as a mistake (worse: the last chip has no
        // reliable way to signal "there's more" and was clipping at the
        // screen edge instead). Mirrors the web app's own mobile breakpoint,
        // which falls back to the same 2x2 layout for the same reason.
        items(stats.chunked(2)) { rowStats ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                for (stat in rowStats) {
                    StatChip(stat, modifier = Modifier.weight(1f))
                }
            }
        }

        item {
            Text(
                "Files are not encrypted — anything on the sending screen is readable by any camera pointed at it. The property here is no network, not confidentiality.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun buildAnnotatedHeadline() = buildAnnotatedString {
    append("Send anything.\nScreen to screen.\n")
    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)) {
        append("No network.")
    }
}

@Composable
private fun ActionCard(
    kicker: String,
    title: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.calmSignal.glass, RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column {
            Text(kicker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)),
                    RoundedCornerShape(16.dp),
                ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp, horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionLabel, color = MaterialTheme.calmSignal.accentInk, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.calmSignal.accentInk)
            }
        }
    }
}

@Composable
private fun StatChip(stat: Stat, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(100.dp)
            .background(MaterialTheme.calmSignal.glass, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stat.value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stat.label,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
