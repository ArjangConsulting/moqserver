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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * HTTP status code badge colors.
 * Keep in sync with [com.moqserver.studio.StudioColors] httpSuccess/httpRedirect/etc.
 */
private object StatusCodeColors {
    val success = Color(0xFF2E7D32)
    val redirect = Color(0xFF7B5E00)
    val clientError = Color(0xFFBF360C)
    val serverError = Color(0xFFC62828)
    val unknown = Color(0xFF616161)
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
    val color = when {
        status in 200..299 -> StatusCodeColors.success
        status in 300..399 -> StatusCodeColors.redirect
        status in 400..499 -> StatusCodeColors.clientError
        status >= 500 -> StatusCodeColors.serverError
        else -> StatusCodeColors.unknown
    }
    Text(
        text = "$status",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
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
