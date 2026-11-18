/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import androidx.compose.material3.Switch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.edit
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.R
import com.metrolist.music.constants.AutoUploadLikedToDriveKey
import com.metrolist.music.constants.GoogleDriveWebhookUrlKey
import com.metrolist.music.db.entities.Song
import com.metrolist.music.utils.GoogleDriveUploader
import com.metrolist.music.utils.LocalMusicServer
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f MB", mb)
    } else {
        String.format(java.util.Locale.US, "%.0f KB", kb)
    }
}

@Composable
private fun StatusChip(
    label: String,
    icon: Int,
    color: Color,
    tooltip: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(painterResource(icon), contentDescription = tooltip, tint = color, modifier = Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color, fontSize = 11.sp)
        }
    }
}

@Composable
fun SyncServerDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()

    val isRunning by LocalMusicServer.isRunning
    val serverUrl by LocalMusicServer.serverUrl

    var selectedTab by remember { mutableIntStateOf(0) }
    var driveFilter by remember { mutableIntStateOf(1) } // 1: Pendientes por defecto, 2: Subidas, 0: Todas, 3: Fallidas
    var showDriveMenu by remember { mutableStateOf(false) }
    var showWebhookDialog by remember { mutableStateOf(false) }
    var showEvermusicGuideDialog by remember { mutableStateOf(false) }
    var customWebhookUrl by remember {
        mutableStateOf(context.dataStore.get(GoogleDriveWebhookUrlKey, GoogleDriveUploader.DEFAULT_WEBHOOK_URL))
    }

    var autoUploadLiked by remember {
        mutableStateOf(context.dataStore.get(AutoUploadLikedToDriveKey, true))
    }
    var syncRefreshTrigger by remember { mutableIntStateOf(0) }

    // Playlist / Source Selection
    var selectedSourceId by remember { mutableStateOf("downloaded") }
    var selectedSourceName by remember { mutableStateOf("💾 Descargadas") }
    var currentSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    val downloadedSongs by database.downloadedSongsByNameAsc().collectAsState(initial = emptyList())
    val likedSongs by database.likedSongsByNameAsc().collectAsState(initial = emptyList())
    val playlists by database.playlistsByNameAsc().collectAsState(initial = emptyList())

    val uploadedSongIds by GoogleDriveUploader.uploadedSongIds
    val failedSongIds by GoogleDriveUploader.failedSongIds
    val currentUploadingSid by GoogleDriveUploader.currentUploadingSongId

    val uploadedInCurrent = remember(currentSongs, uploadedSongIds) {
        currentSongs.filter { uploadedSongIds.contains(it.song.id) }
    }
    val failedInCurrent = remember(currentSongs, failedSongIds) {
        currentSongs.filter { failedSongIds.contains(it.song.id) }
    }
    val pendingInCurrent = remember(currentSongs, uploadedSongIds) {
        currentSongs.filter { !uploadedSongIds.contains(it.song.id) }
    }

    val displayedSongs = remember(currentSongs, driveFilter, selectedTab, uploadedSongIds, failedSongIds) {
        if (selectedTab != 0) currentSongs
        else when (driveFilter) {
            1 -> pendingInCurrent
            2 -> uploadedInCurrent
            3 -> failedInCurrent
            else -> currentSongs
        }
    }

    // Animación de pulso para el servidor activo
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = { it }),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    // Cargar canciones según fuente seleccionada
    LaunchedEffect(selectedSourceId, downloadedSongs, likedSongs, playlists, syncRefreshTrigger) {
        val songs = when {
            selectedSourceId == "downloaded" -> database.downloadedSongsByNameAsc().first()
            selectedSourceId == "liked" -> database.likedSongsByNameAsc().first()
            selectedSourceId.startsWith("pl_") -> {
                val plId = selectedSourceId.removePrefix("pl_")
                try {
                    database.playlistSongs(plId).first().map { it.song }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            else -> database.downloadedSongsByNameAsc().first()
        }
        currentSongs = songs
        GoogleDriveUploader.ensureInitialized(context)
        val pending = songs.filter { !GoogleDriveUploader.isSongUploaded(context, it.song.id) }.map { it.song.id }.toSet()
        selectedSongIds = if (pending.isNotEmpty()) pending else songs.map { it.song.id }.toSet()
        LocalMusicServer.exposedSongIds.value = selectedSongIds
    }

    LaunchedEffect(selectedSongIds) {
        LocalMusicServer.exposedSongIds.value = selectedSongIds
    }

    LaunchedEffect(Unit) {
        GoogleDriveUploader.ensureInitialized(context)
        if (!isRunning) {
            LocalMusicServer.start(
                context = context,
                downloadCache = downloadUtil.downloadCache,
                playerCache = downloadUtil.playerCache,
                database = database,
            )
        }
    }

    val totalInList = currentSongs.size

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                // Header tipo Pantalla Completa con Botón Volver, Título y Reiniciar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = "Volver",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sincronización iPhone",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = when (selectedTab) {
                                0 -> "Metrolist Music en Google Drive"
                                1 -> "Servidor FTP local para VLC"
                                else -> "Servidor Web local (.ZIP / M4A)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    if (LocalMusicServer.servedCount.intValue > 0) {
                        IconButton(
                            onClick = {
                                LocalMusicServer.servedCount.intValue = 0
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.sync),
                                contentDescription = "Reiniciar contador",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Selector de Modo (Tabs M3)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SegmentedButton(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        icon = { Icon(painterResource(R.drawable.cloud), null, Modifier.size(16.dp)) },
                    ) {
                        Text("Google Drive", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        icon = { Icon(painterResource(R.drawable.cast), null, Modifier.size(16.dp)) },
                    ) {
                        Text("VLC (FTP)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        icon = { Icon(painterResource(R.drawable.language), null, Modifier.size(16.dp)) },
                    ) {
                        Text("Modo Web", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (selectedTab == 0 || selectedTab == 1) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val localIp = LocalMusicServer.getLocalIpAddress() ?: "192.168.1.X"
                        val ftpUrl = "ftp://$localIp:2121"
                        val servingSong = LocalMusicServer.currentServingSong.value
                        val servingStatus = LocalMusicServer.currentServingStatus.value
                        val servedCount = LocalMusicServer.servedCount.intValue

                        if (selectedTab == 0) {
                            // BARRA SUPERIOR COMPACTA: GOOGLE DRIVE (NUBE)
                            ElevatedCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(Color(0xFF4CAF50), CircleShape)
                                        )
                                        Column {
                                            Text(
                                                text = "Google Drive (Metrolist Music)",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                text = "${uploadedInCurrent.size} subidas · ${pendingInCurrent.size} pendientes",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (pendingInCurrent.isEmpty()) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    val newChecked = !autoUploadLiked
                                                    autoUploadLiked = newChecked
                                                    scope.launch {
                                                        context.dataStore.edit { prefs ->
                                                            prefs[AutoUploadLikedToDriveKey] = newChecked
                                                        }
                                                    }
                                                }
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                        ) {
                                            Text("Auto ❤️", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            Spacer(Modifier.width(2.dp))
                                            Switch(
                                                checked = autoUploadLiked,
                                                onCheckedChange = { checked ->
                                                    autoUploadLiked = checked
                                                    scope.launch {
                                                        context.dataStore.edit { prefs ->
                                                            prefs[AutoUploadLikedToDriveKey] = checked
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.scale(0.75f),
                                            )
                                        }

                                        Box {
                                            IconButton(
                                                onClick = { showDriveMenu = true },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.more_vert),
                                                    contentDescription = "Opciones",
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showDriveMenu,
                                                onDismissRequest = { showDriveMenu = false },
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Marcar seleccionadas como ya subidas") },
                                                    onClick = {
                                                        showDriveMenu = false
                                                        if (selectedSongIds.isNotEmpty()) {
                                                            GoogleDriveUploader.markSongsUploaded(context, selectedSongIds)
                                                            selectedSongIds = emptySet()
                                                            Toast.makeText(context, "Marcadas como ya subidas", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Desmarcar seleccionadas (volver a pendientes)") },
                                                    onClick = {
                                                        showDriveMenu = false
                                                        if (selectedSongIds.isNotEmpty()) {
                                                            GoogleDriveUploader.markSongsUnuploaded(context, selectedSongIds)
                                                            Toast.makeText(context, "Marcadas como pendientes", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Reiniciar historial de subidas") },
                                                    onClick = {
                                                        showDriveMenu = false
                                                        GoogleDriveUploader.clearUploadedHistory(context)
                                                        Toast.makeText(context, "Historial reiniciado", Toast.LENGTH_SHORT).show()
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Configurar Webhook URL") },
                                                    onClick = {
                                                        showDriveMenu = false
                                                        showWebhookDialog = true
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("📖 ¿Cómo sincronizar en iPhone?") },
                                                    onClick = {
                                                        showDriveMenu = false
                                                        showEvermusicGuideDialog = true
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // CARD PRINCIPAL: SERVIDOR FTP (VLC)

                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(Color(0xFF4CAF50), CircleShape)
                                        )
                                        Text(
                                            text = "Servidor FTP Activo (VLC)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF4CAF50),
                                        )
                                    }
                                    Text(
                                        text = "Puerto 2121",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Spacer(Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable {
                                            val clipboard = context.getSystemService<ClipboardManager>()
                                            clipboard?.setPrimaryClip(ClipData.newPlainText("FTP URL", ftpUrl))
                                            Toast.makeText(context, "Dirección FTP copiada", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = ftpUrl,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.content_copy),
                                        contentDescription = "Copiar",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }

                                if (servingSong != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF4CAF50).copy(alpha = 0.12f))
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF4CAF50),
                                        )
                                        Text(
                                            text = "VLC transfiriendo: $servingSong ($servingStatus)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF4CAF50),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "🟢 Listo para VLC en iOS • $servedCount canciones entregadas",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                        }

                        // Barra de Origen + Filtros (Google Drive / VLC)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Selector de Playlist / Origen
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { isDropdownExpanded = true },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(selectedSourceName, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(2.dp))
                                    Icon(painterResource(R.drawable.arrow_downward), null, Modifier.size(12.dp))
                                }
                            }

                            DropdownMenu(
                                expanded = isDropdownExpanded,
                                onDismissRequest = { isDropdownExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("💾 Descargadas (${downloadedSongs.size})", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = { Icon(painterResource(R.drawable.download), null, Modifier.size(18.dp)) },
                                    onClick = {
                                        selectedSourceId = "downloaded"
                                        selectedSourceName = "💾 Descargadas (${downloadedSongs.size})"
                                        isDropdownExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("❤️ Favoritas (${likedSongs.size})", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = { Icon(painterResource(R.drawable.favorite), null, Modifier.size(18.dp)) },
                                    onClick = {
                                        selectedSourceId = "liked"
                                        selectedSourceName = "❤️ Favoritas (${likedSongs.size})"
                                        isDropdownExpanded = false
                                    },
                                )
                                if (playlists.isNotEmpty()) {
                                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                    playlists.forEach { pl ->
                                        DropdownMenuItem(
                                            text = { Text("📁 ${pl.playlist.name} (${pl.songCount})") },
                                            onClick = {
                                                selectedSourceId = "pl_${pl.playlist.id}"
                                                selectedSourceName = "📁 ${pl.playlist.name} (${pl.songCount})"
                                                isDropdownExpanded = false
                                            },
                                        )
                                    }
                                }
                            }

                            if (selectedTab == 0) {
                                // Chips de filtro para Google Drive
                                listOf(
                                    1 to "Pendientes (${pendingInCurrent.size})",
                                    2 to "Subidas (${uploadedInCurrent.size})",
                                    0 to "Todas (${currentSongs.size})",
                                    3 to "Fallidas (${failedInCurrent.size})",
                                ).forEach { (idx, label) ->
                                    if (idx != 3 || failedInCurrent.isNotEmpty()) {
                                        val isSelected = driveFilter == idx
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                                            modifier = Modifier.clickable { driveFilter = idx },
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                            )
                                        }
                                    }
                                }
                            } else {
                                StatusChip(
                                    label = "${selectedSongIds.size} en WebDAV",
                                    icon = R.drawable.check,
                                    color = Color(0xFF4CAF50),
                                    tooltip = "Canciones compartidas",
                                )
                                StatusChip(
                                    label = "${LocalMusicServer.servedCount.intValue} descargadas",
                                    icon = R.drawable.download,
                                    color = MaterialTheme.colorScheme.primary,
                                    tooltip = "Transferencias",
                                )
                            }
                        }

                        // Acciones de selección rápida (micro barra)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Seleccionadas: ${selectedSongIds.size}/${displayedSongs.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selectedTab == 0 && pendingInCurrent.isNotEmpty()) {
                                    TextButton(
                                        onClick = { selectedSongIds = pendingInCurrent.map { it.song.id }.toSet() },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    ) {
                                        Text("Solo pendientes", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                TextButton(
                                    onClick = { selectedSongIds = displayedSongs.map { it.song.id }.toSet() },
                                    enabled = displayedSongs.isNotEmpty(),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) {
                                    Text("Todas", fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { selectedSongIds = emptySet() },
                                    enabled = selectedSongIds.isNotEmpty(),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) {
                                    Text("Ninguna", fontSize = 11.sp)
                                }
                            }
                        }

                        // Lista de canciones EXPANDIDA (weight 1f) para aprovechar toda la pantalla
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            if (displayedSongs.isEmpty()) {
                                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (selectedTab == 0 && driveFilter == 1) "¡No hay canciones pendientes de subir a Drive! 🎉"
                                        else if (selectedTab == 0 && driveFilter == 2) "Aún no se han subido canciones a Drive."
                                        else if (selectedTab == 0 && driveFilter == 3) "No hay canciones fallidas. ¡Excelente!"
                                        else "No hay canciones disponibles en esta lista",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                ) {
                                    items(displayedSongs, key = { it.song.id }) { song ->
                                        val sid = song.song.id
                                        val isSelected = selectedSongIds.contains(sid)
                                        val isCurrentlyServing = LocalMusicServer.currentServingSong.value == song.song.title
                                        val isDriveUploading = currentUploadingSid == sid

                                        val bgColor = if (isDriveUploading || isCurrentlyServing) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                        } else if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)
                                        } else {
                                            Color.Transparent
                                        }

                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .padding(vertical = 1.dp)
                                                .clickable {
                                                    selectedSongIds = if (isSelected) selectedSongIds - sid else selectedSongIds + sid
                                                }
                                                .fillMaxWidth(),
                                            color = bgColor,
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { checked ->
                                                        selectedSongIds = if (checked) selectedSongIds + sid else selectedSongIds - sid
                                                    },
                                                    modifier = Modifier.size(20.dp).padding(end = 6.dp),
                                                )
                                                // Mini portada
                                                Surface(
                                                    modifier = Modifier.size(36.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                ) {
                                                    val thumb = song.song.thumbnailUrl ?: song.album?.thumbnailUrl
                                                    if (!thumb.isNullOrBlank()) {
                                                        AsyncImage(
                                                            model = thumb,
                                                            contentDescription = null,
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentScale = ContentScale.Crop,
                                                        )
                                                    } else {
                                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                            Icon(
                                                                painter = painterResource(R.drawable.music_note),
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = song.song.title,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            modifier = Modifier.weight(1f, fill = false),
                                                        )
                                                        if (isDriveUploading) {
                                                            Text(
                                                                text = "● Subiendo a Drive...",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = Color(0xFF4CAF50),
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(start = 4.dp),
                                                            )
                                                        } else if (isCurrentlyServing) {
                                                            Text(
                                                                text = "● Transfiriendo...",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.primary,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(start = 4.dp),
                                                            )
                                                        }
                                                    }
                                                    val artists = song.artists.joinToString { it.name }.ifBlank { "Desconocido" }
                                                    val dur = song.song.duration.takeIf { it > 0 }?.let { " • ${makeTimeString(it * 1000L)}" }.orEmpty()
                                                    Text(
                                                        text = "$artists$dur",
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }

                                                if (selectedTab == 0) {
                                                    val isUploaded = uploadedSongIds.contains(sid)
                                                    val isFailed = failedSongIds.contains(sid)
                                                    if (isDriveUploading) {
                                                        Surface(
                                                            shape = RoundedCornerShape(10.dp),
                                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                            ) {
                                                                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
                                                                Text("Subiendo...", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                            }
                                                        }
                                                    } else if (isUploaded) {
                                                        Surface(
                                                            shape = RoundedCornerShape(10.dp),
                                                            color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                                                        ) {
                                                            Text(
                                                                text = "✓ En Drive",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color(0xFF4CAF50),
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                            )
                                                        }
                                                    } else if (isFailed) {
                                                        Surface(
                                                            shape = RoundedCornerShape(10.dp),
                                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                                        ) {
                                                            Text(
                                                                text = "⚠️ Falló",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.error,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                            )
                                                        }
                                                    } else {
                                                        Surface(
                                                            shape = RoundedCornerShape(10.dp),
                                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                        ) {
                                                            Text(
                                                                text = "☁️ Pendiente",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                            )
                                                        }
                                                    }
                                                } else if (isSelected) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                                    ) {
                                                        Text(
                                                            text = ".m4a",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (selectedTab == 0) {
                            val isDriveUploading = GoogleDriveUploader.isUploading.value

                            if (isDriveUploading) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color(0xFF4CAF50),
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${GoogleDriveUploader.currentUploadingSong ?: "Subiendo..."}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "${GoogleDriveUploader.currentSongStage.value} (${GoogleDriveUploader.uploadedCount.intValue}/${GoogleDriveUploader.totalToUpload.intValue})",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }

                                        TextButton(
                                            onClick = { GoogleDriveUploader.cancelBatchUpload() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        ) {
                                            Text("Cancelar", fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    LinearProgressIndicator(
                                        progress = { GoogleDriveUploader.uploadProgress.floatValue },
                                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                        color = Color(0xFF4CAF50),
                                    )
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (failedInCurrent.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = {
                                                GoogleDriveUploader.startBatchUpload(
                                                    context = context,
                                                    songIds = failedInCurrent.map { it.song.id }.toSet(),
                                                    downloadCache = downloadUtil.downloadCache,
                                                    playerCache = downloadUtil.playerCache,
                                                    database = database,
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth().height(38.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        ) {
                                            Text("⚠️ Reintentar canciones fallidas (${failedInCurrent.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }

                                    val targetUploadIds = if (selectedSongIds.isNotEmpty()) {
                                        selectedSongIds.filter { !GoogleDriveUploader.isSongUploaded(context, it) }.toSet()
                                    } else {
                                        pendingInCurrent.map { it.song.id }.toSet()
                                    }
                                    val isAllUploaded = pendingInCurrent.isEmpty() && currentSongs.isNotEmpty()
                                    val buttonText = when {
                                        isAllUploaded && selectedSongIds.isEmpty() -> "✅ Todas las canciones están en Drive"
                                        selectedSongIds.isEmpty() && pendingInCurrent.isNotEmpty() -> "☁️ Subir ${pendingInCurrent.size} canciones pendientes"
                                        targetUploadIds.isNotEmpty() -> "☁️ Subir ${targetUploadIds.size} canciones seleccionadas"
                                        else -> "☁️ Selecciona canciones pendientes para subir"
                                    }

                                    Button(
                                        onClick = {
                                            val toUpload = if (targetUploadIds.isNotEmpty()) targetUploadIds else pendingInCurrent.map { it.song.id }.toSet()
                                            GoogleDriveUploader.startBatchUpload(
                                                context = context,
                                                songIds = toUpload,
                                                downloadCache = downloadUtil.downloadCache,
                                                playerCache = downloadUtil.playerCache,
                                                database = database,
                                            )
                                        },
                                        enabled = targetUploadIds.isNotEmpty(),
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isAllUploaded && selectedSongIds.isEmpty()) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                                        ),
                                    ) {
                                        Icon(painterResource(R.drawable.cloud), null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = buttonText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                            }
                        } else {
                            // Instrucciones y Acciones para VLC en iOS
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(painterResource(R.drawable.info), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(
                                            text = "Conectar VLC (iPhone):",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(
                                        text = "Servidor: $localIp  |  Puerto: 2121  |  Usuario: anonymous\n" +
                                               "⚠️ Nota: VLC en iOS solo permite descargar de 1 en 1. Para descargar TODAS las canciones juntas de un solo toque sin dolor de cabeza, usa la pestaña 'Google Drive' con Evermusic o la pestaña 'Modo Web' para descargar un único archivo .ZIP.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService<ClipboardManager>()
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("FTP URL", ftpUrl))
                                    Toast.makeText(context, "Enlace copiado: $ftpUrl", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(painterResource(R.drawable.content_copy), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Copiar dirección FTP ($ftpUrl)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }

                } else {
                    // PANEL MODO WEB
                    val webScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(webScroll),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Surface(
                                        modifier = Modifier.size(54.dp),
                                        shape = CircleShape,
                                        color = if (isRunning) Color(0xFF4CAF50).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Icon(
                                            painter = painterResource(if (isRunning) R.drawable.cast_connected else R.drawable.cast),
                                            contentDescription = null,
                                            modifier = Modifier.padding(12.dp).size(28.dp),
                                            tint = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isRunning) {
                                        Box(
                                            Modifier
                                                .size(64.dp)
                                                .graphicsLayer { this.alpha = pulseAlpha }
                                                .background(Color(0xFF4CAF50).copy(0.2f), CircleShape)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = if (isRunning) "Servidor Web Activo" else "Servidor Desactivado",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (isRunning) "Abre este enlace en Safari en tu iPhone" else "Inicia el servidor para compartir música vía navegador",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                if (isRunning && serverUrl != null) {
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedCard(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            val clipboard = context.getSystemService<ClipboardManager>()
                                            clipboard?.setPrimaryClip(ClipData.newPlainText("URL", serverUrl))
                                            Toast.makeText(context, "Enlace copiado", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = serverUrl.orEmpty(),
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Icon(
                                                painter = painterResource(R.drawable.content_copy),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                if (isRunning) LocalMusicServer.stop()
                                else LocalMusicServer.start(context, downloadUtil.downloadCache, downloadUtil.playerCache, database)
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = if (isRunning)
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            else ButtonDefaults.buttonColors(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(painterResource(if (isRunning) R.drawable.ic_widget_pause else R.drawable.ic_widget_play), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRunning) "Detener Servidor" else "Iniciar Servidor Web", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        // Guía rápida inferior
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(painterResource(R.drawable.info), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Asegúrate de estar en la misma red Wi-Fi",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (showWebhookDialog) {
            AlertDialog(
                onDismissRequest = { showWebhookDialog = false },
                title = { Text("Webhook URL de Google Drive", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "URL del script Google Apps Script que sube los audios a la carpeta 'Metrolist Music':",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = customWebhookUrl,
                            onValueChange = { customWebhookUrl = it },
                            singleLine = false,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                context.dataStore.edit { prefs ->
                                    prefs[GoogleDriveWebhookUrlKey] = customWebhookUrl.trim()
                                }
                            }
                            showWebhookDialog = false
                            Toast.makeText(context, "URL de Webhook guardada", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Guardar")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            customWebhookUrl = GoogleDriveUploader.DEFAULT_WEBHOOK_URL
                            scope.launch {
                                context.dataStore.edit { prefs ->
                                    prefs[GoogleDriveWebhookUrlKey] = GoogleDriveUploader.DEFAULT_WEBHOOK_URL
                                }
                            }
                            showWebhookDialog = false
                            Toast.makeText(context, "URL restablecida por defecto", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Por defecto")
                    }
                }
            )
        }

        if (showEvermusicGuideDialog) {
            AlertDialog(
                onDismissRequest = { showEvermusicGuideDialog = false },
                title = { Text("Descarga automática en iPhone", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Para descargar TODAS tus canciones de golpe sin tocarlas de una en una:\n\n" +
                                   "1. Abre Evermusic en tu iPhone y ve a 'Nube / Conexiones' ➔ 'Google Drive'.\n" +
                                   "2. Entra a la carpeta 'Metrolist Music'.\n" +
                                   "3. Toca los tres puntos (...) arriba a la derecha y selecciona 'Descargar carpeta' o 'Sincronización automática sin conexión'.\n" +
                                   "4. ¡Listo! Evermusic descargará todas las canciones automáticamente en segundo plano con carátulas y artistas.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showEvermusicGuideDialog = false }) {
                        Text("Entendido")
                    }
                }
            )
        }
    }
}
