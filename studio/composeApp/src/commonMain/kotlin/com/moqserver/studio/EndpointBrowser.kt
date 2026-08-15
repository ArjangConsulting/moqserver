package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.displayAlias
import com.moqserver.studio.ui.MethodBadge
import com.moqserver.studio.ui.VariantCountBadge

@Composable
fun EndpointBrowser(
    state: StudioState,
    onSelectEndpoint: (String) -> Unit,
    detailContent: @Composable () -> Unit,
) {
    val project = state.project
    if (project == null) {
        EmptyProjectState()
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredEndpoints = remember(project.endpoints, searchQuery) {
        if (searchQuery.isBlank()) {
            project.endpoints
        } else {
            val q = searchQuery.lowercase()
            project.endpoints.filter { ep ->
                ep.id.lowercase().contains(q) ||
                    ep.path.lowercase().contains(q) ||
                    ep.method.lowercase().contains(q) ||
                    ep.displayAlias.lowercase().contains(q) ||
                    ep.tags?.any { it.lowercase().contains(q) } == true
            }
        }
    }

	// Group by first path segment
	val grouped = remember(filteredEndpoints) {
		filteredEndpoints.groupBy { ep ->
			ep.tags
				?.firstOrNull()
				?.takeIf { it.isNotBlank() }
				?: run {
					val segments = ep.path.removePrefix("/").split("/")
					if (segments.size > 1) "/${segments.first()}" else ep.path
				}
		}.toSortedMap()
	}

    Row(modifier = Modifier.fillMaxSize()) {
        // Left sidebar
        Column(
            modifier = Modifier
                .width(StudioDimens.endpointBrowserWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search endpoints...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(StudioDimens.m),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                grouped.forEach { (group, endpoints) ->
                    item {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = StudioDimens.l,
                                top = StudioDimens.l,
                                bottom = StudioDimens.xs,
                            ),
                        )
                    }

                    items(endpoints, key = { it.id }) { endpoint ->
                        EndpointListItem(
                            endpoint = endpoint,
                            isSelected = state.selectedEndpointId == endpoint.id,
                            onClick = { onSelectEndpoint(endpoint.id) },
                        )
                    }
                }
            }
        }

        VerticalDivider()

        // Right detail pane
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            if (state.selectedEndpoint != null) {
                detailContent()
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Select an endpoint",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointListItem(
    endpoint: EndpointDocument,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = StudioDimens.xs, vertical = 1.dp),
        color = bgColor,
        shape = RoundedCornerShape(StudioDimens.s),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = StudioDimens.l, vertical = StudioDimens.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
        ) {
            MethodBadge(endpoint.method)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = endpoint.displayAlias,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = endpoint.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VariantCountBadge(endpoint.variants.size)
        }
    }
}

@Composable
private fun EmptyProjectState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No project loaded",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(StudioDimens.m))
        Text(
            "Open a .moqproj directory from the Dashboard",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
