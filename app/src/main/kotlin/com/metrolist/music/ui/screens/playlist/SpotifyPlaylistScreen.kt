/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.metrolist.music.ui.component.DefaultDialog
import androidx.media3.exoplayer.offline.Download
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.SpotifyPlaylistSortDescendingKey
import com.metrolist.music.constants.SpotifyPlaylistSortTypeKey
import com.metrolist.music.constants.SpotifySortType
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.queues.SpotifyPlaylistQueue
import com.metrolist.music.ui.component.DraggableScrollbar
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.menu.SpotifyTrackMenu
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.LocalDatabase
import com.metrolist.music.constants.SwipeToRemoveSongKey
import com.metrolist.music.extensions.move
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.viewmodels.SpotifyPlaylistViewModel
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyPlaylistTrack

import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.music.playback.ExoDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.material3.Surface
import com.metrolist.music.ui.menu.SpotifyPlaylistMenu

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpotifyPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current

    val playlist by viewModel.playlist.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val mutationError by viewModel.mutationError.collectAsState()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val currentSpotifyId by produceState<String?>(initialValue = null, mediaMetadata?.id) {
        val ytId = mediaMetadata?.id
        value = if (ytId != null) {
            withContext(Dispatchers.IO) { database.getSpotifyMatchByYouTubeId(ytId)?.spotifyId }
        } else {
            null
        }
    }

    val (sortType, onSortTypeChange) = rememberEnumPreference(
        SpotifyPlaylistSortTypeKey,
        SpotifySortType.ORIGINAL,
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        SpotifyPlaylistSortDescendingKey,
        true,
    )

    var locked by rememberSaveable { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val downloadUtil = com.metrolist.music.LocalDownloadUtil.current
    var downloadState by remember { mutableIntStateOf(Download.STATE_STOPPED) }
    var showRemoveDownloadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tracks) {
        if (tracks.isEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            val matchedYtIds = withContext(Dispatchers.IO) {
                tracks.mapNotNull { track ->
                    database.getSpotifyMatch(track.id)?.youtubeId
                }
            }
            if (matchedYtIds.isEmpty()) {
                downloadState = Download.STATE_STOPPED
                return@collect
            }
            downloadState =
                if (matchedYtIds.all { downloads[it]?.state == Download.STATE_COMPLETED }) {
                    Download.STATE_COMPLETED
                } else if (matchedYtIds.all {
                        val state = downloads[it]?.state
                        state == Download.STATE_QUEUED ||
                                state == Download.STATE_DOWNLOADING ||
                                state == Download.STATE_COMPLETED
                    }) {
                    Download.STATE_DOWNLOADING
                } else {
                    Download.STATE_STOPPED
                }
        }
    }
    val pullRefreshState = rememberPullToRefreshState()

    val mapper = remember { SpotifyYouTubeMapper(database) }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val swipeRemoveEnabled by rememberPreference(SwipeToRemoveSongKey, defaultValue = false)

    LaunchedEffect(mutationError) {
        mutationError?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short,
            )
            viewModel.clearMutationError()
        }
    }

    val playlistItems by viewModel.playlistItems.collectAsState()

    val sortedItems = remember(playlistItems, sortType, sortDescending) {
        val sorted = when (sortType) {
            SpotifySortType.ORIGINAL -> playlistItems
            SpotifySortType.NAME -> playlistItems.sortedBy { it.track?.name?.lowercase() ?: "" }
            SpotifySortType.ARTIST -> playlistItems.sortedBy {
                it.track?.artists?.firstOrNull()?.name?.lowercase() ?: ""
            }
            SpotifySortType.DURATION -> playlistItems.sortedBy { it.track?.durationMs ?: 0 }
        }
        if (sortDescending) sorted.reversed() else sorted
    }

    val mutableItems = remember { mutableStateListOf<SpotifyPlaylistTrack>() }
    LaunchedEffect(sortedItems) {
        if (sortedItems.isEmpty() && mutableItems.isEmpty()) return@LaunchedEffect
        val currentUids = mutableItems.map { it.uid }
        val newUids = sortedItems.map { it.uid }
        if (currentUids != newUids) {
            mutableItems.clear()
            mutableItems.addAll(sortedItems)
        }
    }

    val filteredItems = remember(mutableItems.toList(), query) {
        if (query.text.isEmpty()) {
            mutableItems.toList()
        } else {
            mutableItems.filter { item ->
                val t = item.track ?: return@filter false
                t.name.contains(query.text, ignoreCase = true) ||
                    t.artists.any { it.name.contains(query.text, ignoreCase = true) }
            }
        }
    }

    val headerItems = 2
    var dragInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scrollThresholdPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    ) { from, to ->
        if (to.index >= headerItems && from.index >= headerItems) {
            val currentDragInfo = dragInfo
            dragInfo = if (currentDragInfo == null) {
                (from.index - headerItems) to (to.index - headerItems)
            } else {
                currentDragInfo.first to (to.index - headerItems)
            }
            mutableItems.move(from.index - headerItems, to.index - headerItems)
        }
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            dragInfo?.let { (from, to) ->
                viewModel.moveTrack(from, to)
                dragInfo = null
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    BackHandler(enabled = isSearching) {
        isSearching = false
        query = TextFieldValue()
    }

    if (showRemoveDownloadDialog) {
        val name = playlist?.name ?: ""
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = { showRemoveDownloadDialog = false },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            tracks.forEach { track ->
                                val ytId = database.getSpotifyMatch(track.id)?.youtubeId ?: return@forEach
                                DownloadService.sendRemoveDownload(
                                    context,
                                    ExoDownloadService::class.java,
                                    ytId,
                                    false,
                                )
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        indicator = {
            Indicator(
                isRefreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            // Playlist header
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Playlist cover art
                    val imageUrl = playlist?.images?.firstOrNull()?.url
                    Surface(
                        modifier = Modifier
                            .size(240.dp)
                            .shadow(
                                elevation = 24.dp,
                                shape = RoundedCornerShape(3.dp),
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            ),
                        shape = RoundedCornerShape(3.dp),
                    ) {
                        if (imageUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(imageUrl).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.music_note),
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Playlist Name
                    Text(
                        text = playlist?.name.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Subtitle / Details
                    Text(
                        text = buildString {
                            val ownerName = playlist?.owner?.displayName
                            if (!ownerName.isNullOrEmpty()) {
                                append(ownerName)
                                append(" • ")
                            }
                            append(pluralStringResource(R.plurals.n_song, tracks.size, tracks.size))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )

                    if (!isLoading && tracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Shuffle Button
                            Surface(
                                onClick = {
                                    playerConnection.playQueue(
                                        SpotifyPlaylistQueue(
                                            playlistId = viewModel.playlistId,
                                            initialTracks = sortedItems.mapNotNull { it.track }.shuffled(),
                                            startIndex = 0,
                                            mapper = viewModel.mapper,
                                        )
                                    )
                                },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.shuffle),
                                        contentDescription = stringResource(R.string.shuffle),
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }

                            // Play Button - Larger primary circular button
                            Surface(
                                onClick = {
                                    playerConnection.playQueue(
                                        SpotifyPlaylistQueue(
                                            playlistId = viewModel.playlistId,
                                            initialTracks = sortedItems.mapNotNull { it.track },
                                            startIndex = 0,
                                            mapper = viewModel.mapper,
                                        )
                                    )
                                },
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                                modifier = Modifier.size(72.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.play),
                                        contentDescription = stringResource(R.string.play),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }

                            // Download Button next to Play button
                            val downloadIcon = when (downloadState) {
                                Download.STATE_COMPLETED -> R.drawable.offline
                                Download.STATE_DOWNLOADING -> R.drawable.download
                                else -> R.drawable.download
                            }
                            val downloadTint = if (downloadState == Download.STATE_COMPLETED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Surface(
                                onClick = {
                                    coroutineScope.launch {
                                        when (downloadState) {
                                            Download.STATE_COMPLETED -> {
                                                showRemoveDownloadDialog = true
                                            }
                                            Download.STATE_DOWNLOADING -> {
                                                withContext(Dispatchers.IO) {
                                                    tracks.forEach { track ->
                                                        val ytId = database.getSpotifyMatch(track.id)?.youtubeId ?: return@forEach
                                                        DownloadService.sendRemoveDownload(
                                                            context,
                                                            ExoDownloadService::class.java,
                                                            ytId,
                                                            false,
                                                        )
                                                    }
                                                }
                                            }
                                            else -> {
                                                withContext(Dispatchers.IO) {
                                                    tracks.forEach { track ->
                                                        val mediaMetadata = viewModel.mapper.mapToYouTube(track) ?: return@forEach
                                                        val downloadRequest = DownloadRequest
                                                            .Builder(mediaMetadata.id, mediaMetadata.id.toUri())
                                                            .setCustomCacheKey(mediaMetadata.id)
                                                            .setData(mediaMetadata.title.toByteArray())
                                                            .build()
                                                        DownloadService.sendAddDownload(
                                                            context,
                                                            ExoDownloadService::class.java,
                                                            downloadRequest,
                                                            false,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(downloadIcon),
                                        contentDescription = "Download Playlist",
                                        tint = downloadTint,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }

                            // Menu Button (SpotifyPlaylistMenu is available for Spotify playlists)
                            Surface(
                                onClick = {
                                    playlist?.let {
                                        menuState.show {
                                            SpotifyPlaylistMenu(
                                                playlist = it,
                                                onNavigate = {},
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                    }
                                },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!isLoading && tracks.isNotEmpty()) {
                item(key = "sort") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        SortHeader(
                            sortType = sortType,
                            sortDescending = sortDescending,
                            onSortTypeChange = onSortTypeChange,
                            onSortDescendingChange = onSortDescendingChange,
                            sortTypeText = { type ->
                                when (type) {
                                    SpotifySortType.ORIGINAL -> R.string.sort_by_original
                                    SpotifySortType.NAME -> R.string.sort_by_name
                                    SpotifySortType.ARTIST -> R.string.sort_by_artist
                                    SpotifySortType.DURATION -> R.string.sort_by_duration
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.IconButton(
                            onClick = { locked = !locked },
                            modifier = Modifier.padding(horizontal = 6.dp),
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (locked) R.drawable.lock else R.drawable.lock_open,
                                ),
                                contentDescription = null,
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (error != null) {
                item(key = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = { viewModel.retry() },
                        ) {
                            Text(stringResource(R.string.retry_button))
                        }
                    }
                }
            }

            if (!isLoading && error == null && tracks.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.spotify_no_tracks),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val displayItems = if (isSearching) filteredItems else mutableItems.toList()
            val reorderEnabled = sortType == SpotifySortType.ORIGINAL && !sortDescending && !locked && !isSearching
            itemsIndexed(
                items = displayItems,
                key = { index, item -> item.uid ?: "item_${item.track?.id}_$index" },
            ) { index, item ->
                val track = item.track ?: return@itemsIndexed
                ReorderableItem(
                    state = reorderableState,
                    key = item.uid ?: "item_${track.id}_$index",
                ) {
                    val currentTrack by rememberUpdatedState(track)
                    val thumbnailUrl = SpotifyMapper.getTrackThumbnail(track)
                    val originalIndex = if (isSearching) {
                        mutableItems.indexOfFirst { it.uid == item.uid }.coerceAtLeast(0)
                    } else {
                        index
                    }

                    val dismissBoxState = rememberSwipeToDismissBoxState(
                        positionalThreshold = { totalDistance -> totalDistance },
                    )
                    var processedDismiss by remember { mutableStateOf(false) }
                    LaunchedEffect(dismissBoxState.currentValue) {
                        val dv = dismissBoxState.currentValue
                        if (swipeRemoveEnabled && !processedDismiss && (
                                dv == SwipeToDismissBoxValue.StartToEnd ||
                                    dv == SwipeToDismissBoxValue.EndToStart
                                )
                        ) {
                            processedDismiss = true
                            viewModel.removeTrack(currentTrack)
                            coroutineScope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.spotify_track_removed),
                                    actionLabel = context.getString(R.string.undo),
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.addTracks(
                                        listOf(currentTrack.uri ?: "spotify:track:${currentTrack.id}"),
                                    )
                                }
                            }
                        }
                        if (dv == SwipeToDismissBoxValue.Settled) {
                            processedDismiss = false
                        }
                    }

                    val isActive = currentSpotifyId != null && currentSpotifyId == track.id
                    val content: @Composable () -> Unit = {
                        ListItem(
                            title = track.name,
                            subtitle = joinByBullet(
                                track.artists.joinToString { it.name },
                                makeTimeString((track.durationMs).toLong()),
                            ),
                            isActive = isActive,
                            thumbnailContent = {
                                ItemThumbnail(
                                    thumbnailUrl = thumbnailUrl,
                                    isActive = isActive,
                                    isPlaying = isPlaying,
                                    shape = RoundedCornerShape(ThumbnailCornerRadius),
                                    modifier = Modifier.size(ListThumbnailSize),
                                )
                            },
                            trailingContent = {
                                if (reorderEnabled) {
                                    com.metrolist.music.ui.component.IconButton(
                                        onClick = { },
                                        onLongClick = { },
                                        modifier = Modifier.draggableHandle(),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.drag_handle),
                                            contentDescription = null,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        playerConnection.playQueue(
                                            SpotifyPlaylistQueue(
                                                playlistId = viewModel.playlistId,
                                                initialTracks = mutableItems.mapNotNull { it.track },
                                                startIndex = originalIndex,
                                                mapper = viewModel.mapper,
                                            ),
                                        )
                                    },
                                    onLongClick = {
                                        menuState.show {
                                            SpotifyTrackMenu(
                                                track = track,
                                                mapper = mapper,
                                                onDismiss = menuState::dismiss,
                                                navController = navController,
                                                onRemoveFromPlaylist = {
                                                    viewModel.removeTrack(track)
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.spotify_track_removed),
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                            )
                                        }
                                    },
                                ),
                        )
                    }

                    if (locked || !swipeRemoveEnabled) {
                        Box(modifier = Modifier.animateItem()) {
                            content()
                        }
                    } else {
                        SwipeToDismissBox(
                            state = dismissBoxState,
                            backgroundContent = {},
                            modifier = Modifier.animateItem(),
                        ) {
                            content()
                        }
                    }
                }
            }
        }

        DraggableScrollbar(
            modifier = Modifier
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues())
                .align(Alignment.CenterEnd),
            scrollState = lazyListState,
            headerItems = headerItems,
        )

        TopAppBar(
            title = {
                if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                } else {
                    Text(playlist?.name ?: stringResource(R.string.playlists))
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (isSearching) {
                            isSearching = false
                            query = TextFieldValue()
                        } else {
                            navController.navigateUp()
                        }
                    },
                    onLongClick = {
                        if (!isSearching) {
                            navController.backToMain()
                        }
                    },
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
            actions = {
                if (!isSearching) {
                    IconButton(
                        onClick = { isSearching = true },
                        onLongClick = {},
                    ) {
                        Icon(
                            painterResource(R.drawable.search),
                            contentDescription = null,
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            onLongClick = {},
                        ) {
                            Icon(
                                painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.spotify_rename_playlist)) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.edit),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_download)) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.download),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    Timber.d("SpotifyPlaylistDownload: started, ${tracks.size} tracks")
                                    coroutineScope.launch {
                                        var resolved = 0
                                        var skipped = 0
                                        tracks.forEach { track ->
                                            val metadata = mapper.mapToYouTube(track)
                                            if (metadata == null) {
                                                skipped++
                                                Timber.w("SpotifyPlaylistDownload: SKIP '${track.name}' — no YouTube match")
                                                return@forEach
                                            }
                                            resolved++
                                            Timber.d("SpotifyPlaylistDownload: queuing '${track.name}' -> yt:${metadata.id}")
                                            val downloadRequest = DownloadRequest
                                                .Builder(metadata.id, metadata.id.toUri())
                                                .setCustomCacheKey(metadata.id)
                                                .setData(metadata.title.toByteArray())
                                                .build()
                                            DownloadService.sendAddDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                downloadRequest,
                                                false,
                                            )
                                        }
                                        Timber.d("SpotifyPlaylistDownload: done — $resolved queued, $skipped skipped")
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showRenameDialog) {
        RenamePlaylistDialog(
            currentName = playlist?.name ?: "",
            onConfirm = { newName ->
                viewModel.renamePlaylist(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
}

@Composable
private fun RenamePlaylistDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spotify_rename_playlist)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.spotify_playlist_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name != currentName,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
