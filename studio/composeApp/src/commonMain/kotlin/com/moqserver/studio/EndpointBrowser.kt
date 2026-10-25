package com.moqserver.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.displayAlias
import com.moqserver.studio.ui.MethodBadge
import com.moqserver.studio.ui.VariantCountBadge
import io.github.maniramezan.kmpcomponents.ContentRow
import io.github.maniramezan.kmpcomponents.EmptyState
import io.github.maniramezan.kmpcomponents.HorizontalSplitPane
import io.github.maniramezan.kmpcomponents.SearchField
import io.github.maniramezan.kmpcomponents.SearchFieldLabels
import io.github.maniramezan.kmpcomponents.SidebarNavigation
import io.github.maniramezan.kmpcomponents.SplitPaneLabels

private object EndpointBrowserStrings {
	const val SEARCH = "Search endpoints..."
	const val CLEAR_SEARCH = "Clear endpoint search"
	const val RESIZE = "Resize endpoint browser"
	const val SELECT_ENDPOINT = "Select an endpoint"
	const val NO_PROJECT = "No project loaded"
	const val OPEN_PROJECT = "Open or import a project to browse its endpoints."
	const val NO_MATCHES = "No matching endpoints"
	const val NO_MATCHES_HELP = "Try a different method, alias, path, or tag."
}

@Composable
fun EndpointBrowser(
	state: StudioState,
	onSelectEndpoint: (String) -> Unit,
	detailContent: @Composable () -> Unit,
) {
	val project = state.project
	if (project == null) {
		EmptyState(title = EndpointBrowserStrings.NO_PROJECT, message = EndpointBrowserStrings.OPEN_PROJECT)
		return
	}
	var searchQuery by remember { mutableStateOf("") }
	var sidebarSplit by remember { mutableFloatStateOf(0.25f) }
	val filteredEndpoints = remember(project.endpoints, searchQuery) {
		val query = searchQuery.trim()
		project.endpoints.filter { endpoint ->
			query.isEmpty() || listOf(endpoint.id, endpoint.path, endpoint.method, endpoint.displayAlias)
				.plus(endpoint.tags.orEmpty()).any { it.contains(query, ignoreCase = true) }
		}
	}
	val grouped = remember(filteredEndpoints) {
		filteredEndpoints.groupBy { endpoint ->
			endpoint.tags?.firstOrNull()?.takeIf(String::isNotBlank) ?: run {
				val segments = endpoint.path.removePrefix("/").split("/")
				if (segments.size > 1) "/${segments.first()}" else endpoint.path
			}
		}.toSortedMap()
	}
	BoxWithConstraints(Modifier.fillMaxSize()) {
	val minimumSidebarWidth = StudioDimens.endpointBrowserWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)
	val minimumSplit = (minimumSidebarWidth / maxWidth).coerceIn(0.2f, 0.6f)
	HorizontalSplitPane(
		split = sidebarSplit,
		onSplitChange = { sidebarSplit = it },
		modifier = Modifier.fillMaxSize(),
		minSplit = minimumSplit,
		maxSplit = 0.8f,
		labels = SplitPaneLabels(handleDescription = EndpointBrowserStrings.RESIZE),
		leading = {
			SidebarNavigation(modifier = Modifier.fillMaxHeight(), header = {
				SearchField(
					value = searchQuery,
					onValueChange = { searchQuery = it },
					labels = SearchFieldLabels(EndpointBrowserStrings.SEARCH, EndpointBrowserStrings.CLEAR_SEARCH),
				)
			}) {
				if (filteredEndpoints.isEmpty()) {
					EmptyState(title = EndpointBrowserStrings.NO_MATCHES, message = EndpointBrowserStrings.NO_MATCHES_HELP)
				}
				LazyColumn(Modifier.weight(1f)) {
					grouped.forEach { (group, endpoints) ->
						item(key = "group-$group") {
							Text(
								text = group,
								style = MaterialTheme.typography.labelSmall,
								modifier = Modifier.padding(vertical = StudioDimens.m),
							)
						}
						items(endpoints, key = { "endpoint-${it.id}" }) { endpoint ->
							EndpointListItem(endpoint, state.selectedEndpointId == endpoint.id) { onSelectEndpoint(endpoint.id) }
						}
					}
				}
			}
		},
		trailing = {
			Box(Modifier.fillMaxSize()) {
				if (state.selectedEndpoint != null) detailContent() else EmptyState(EndpointBrowserStrings.SELECT_ENDPOINT)
			}
		},
	)
	}
}

@Composable
private fun EndpointListItem(endpoint: EndpointDocument, isSelected: Boolean, onClick: () -> Unit) {
	ContentRow(
		title = endpoint.displayAlias,
		supportingText = endpoint.path,
		modifier = Modifier.fillMaxWidth(),
		selected = isSelected,
		onClick = onClick,
		leadingContent = { MethodBadge(endpoint.method) },
		trailingContent = { VariantCountBadge(endpoint.variants.size) },
	)
}
