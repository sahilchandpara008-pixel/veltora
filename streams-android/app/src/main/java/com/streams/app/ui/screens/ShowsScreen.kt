package com.streams.app.ui.screens

import com.streams.app.ui.theme.Surface1
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.streams.app.AppState
import com.streams.app.Load
import com.streams.app.data.Repo
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.PosterCard

@Composable
fun ShowsScreen(nav: NavController) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val load = rememberLoad(access) { Repo.titles() }
    var filter by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Shows",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search movies & series") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Surface1,
                focusedContainerColor = Surface1,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Color.White.copy(alpha = 0.5f),
                cursorColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
        ChipRow(listOf("All", "Free", "Premium"), filter, { filter = it }, Modifier.padding(bottom = 8.dp))

        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> {
                val list = s.data.filterTier(filter).filter {
                    query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)
                }
                // Adaptive = fluid columns: each poster stretches to fill its column at any width.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (list.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                title = if (query.isBlank()) "No titles yet" else "No results for \"${query.trim()}\"",
                                message = if (query.isBlank()) "New movies and series are on the way." else "Check the spelling or try another title.",
                                icon = Icons.Default.Search,
                            )
                        }
                    }
                    items(list, key = { it.id }) { t -> PosterCard(t) { nav.navigate(Routes.title(t.id)) } }
                }
            }
        }
    }
}
