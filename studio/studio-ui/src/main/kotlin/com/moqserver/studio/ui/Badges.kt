package com.moqserver.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Paired background/foreground colors for a status badge.
 * Using distinct container + on-container colors (rather than the same hue at low alpha)
 * guarantees readable contrast in both light and dark themes.
 * Keep in sync with [com.moqserver.studio.StudioColors].
 */
private data class StatusBadgeColors(val background: Color, val foreground: Color)

@Composable
private fun statusBadgeColors(status: Int): StatusBadgeColors {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when {
        status in 200..299 -> if (isDark)
            StatusBadgeColors(Color(0xFF1B5E20), Color(0xFFA5D6A7))
        else
            StatusBadgeColors(Color(0xFFC8E6C9), Color(0xFF1B5E20))
        status in 300..399 -> if (isDark)
            StatusBadgeColors(Color(0xFF3D2B00), Color(0xFFFFE082))
        else
            StatusBadgeColors(Color(0xFFFFF8E1), Color(0xFF6D4C00))
        status in 400..499 -> if (isDark)
            StatusBadgeColors(Color(0xFF3E1200), Color(0xFFFFAB91))
        else
            StatusBadgeColors(Color(0xFFFFCCBC), Color(0xFFBF360C))
        status >= 500 -> if (isDark)
            StatusBadgeColors(Color(0xFF400000), Color(0xFFEF9A9A))
        else
            StatusBadgeColors(Color(0xFFFFCDD2), Color(0xFFB71C1C))
        else -> if (isDark)
            StatusBadgeColors(Color(0xFF212121), Color(0xFFB0BEC5))
        else
            StatusBadgeColors(Color(0xFFEEEEEE), Color(0xFF424242))
    }
}

@Composable
fun MethodBadge(method: String) {
    val color = when (method.uppercase()) {
        "GET" -> MaterialTheme.colorScheme.primary
        "POST" -> MaterialTheme.colorScheme.tertiary
        "PUT" -> MaterialTheme.colorScheme.secondary
        "PATCH" -> MaterialTheme.colorScheme.secondary
        "DELETE" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Text(
        text = method.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun StatusBadge(status: Int) {
    val colors = statusBadgeColors(status)
    Text(
        text = "$status",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = colors.foreground,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun VariantCountBadge(count: Int) {
    val color = MaterialTheme.colorScheme.secondary

    Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
