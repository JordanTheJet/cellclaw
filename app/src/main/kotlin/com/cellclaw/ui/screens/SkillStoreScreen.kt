package com.cellclaw.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cellclaw.skills.SkillListing
import com.cellclaw.ui.viewmodel.SkillStoreViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillStoreScreen(
    onBack: () -> Unit,
    viewModel: SkillStoreViewModel = hiltViewModel()
) {
    val listings by viewModel.listings.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val installing by viewModel.installing.collectAsState()
    val installedSkills by viewModel.installedSkills.collectAsState()

    var selectedCategory by remember { mutableStateOf<String?>(null) }

    // Get unique categories
    val categories = remember(listings) {
        listings.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }

    // Filter by selected category
    val filteredListings = remember(listings, selectedCategory) {
        if (selectedCategory == null) listings
        else listings.filter { it.category == selectedCategory }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skill Store") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchIndex(forceRefresh = true) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Error banner
            error?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            // Category filter chips
            if (categories.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            label = { Text("All") }
                        )
                    }
                    items(categories) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = {
                                selectedCategory = if (selectedCategory == category) null else category
                            },
                            label = { Text(category.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            // Main content
            if (isLoading && listings.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredListings.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No skills available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.fetchIndex(forceRefresh = true) }) {
                            Text("Retry")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredListings, key = { it.slug }) { listing ->
                        val isInstalled = remember(installedSkills) {
                            viewModel.isInstalled(listing)
                        }
                        val isInstalling = listing.slug in installing

                        StoreSkillCard(
                            listing = listing,
                            isInstalled = isInstalled,
                            isInstalling = isInstalling,
                            onInstall = { viewModel.installSkill(listing) },
                            onUninstall = { viewModel.uninstallSkill(listing) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreSkillCard(
    listing: SkillListing,
    isInstalled: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(listing.name, style = MaterialTheme.typography.titleMedium)
                    if (listing.author.isNotBlank()) {
                        Text(
                            "by ${listing.author}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else if (isInstalled) {
                    FilledIconButton(
                        onClick = onUninstall,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Installed",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                } else {
                    FilledIconButton(onClick = onInstall) {
                        Icon(Icons.Default.Download, contentDescription = "Install")
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                listing.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            // Category + apps chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (listing.category.isNotBlank()) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                listing.category.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
                listing.apps.take(3).forEach { app ->
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(app, style = MaterialTheme.typography.labelSmall)
                        }
                    )
                }
            }

            // Tools used
            if (listing.tools.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tools: ${listing.tools.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
