/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.getSystemService
import androidx.media3.datasource.cache.Cache
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LocalMusicServer {
    private const val TAG = "LocalMusicServer"
    private const val DEFAULT_PORT = 8080
    const val FTP_PORT = 2121

    val isRunning = mutableStateOf(false)
    val serverUrl = mutableStateOf<String?>(null)

    // Estado observable para la UI sobre actividad WebDAV y FTP en tiempo real
    val currentServingSong = mutableStateOf<String?>(null)
    val currentServingStatus = mutableStateOf<String>("")
    val servedCount = mutableIntStateOf(0)
    val activeClientIp = mutableStateOf<String?>(null)
    val exposedSongIds = mutableStateOf<Set<String>?>(null)

    private var serverSocket: ServerSocket? = null
    private var ftpSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private val httpClient = OkHttpClient.Builder().build()

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error obtaining local IP address")
        }
        return null
    }

    @Synchronized
    fun start(
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
        preferredPort: Int = DEFAULT_PORT,
    ): String? {
        if (isRunning.value) return serverUrl.value

        val ip = getLocalIpAddress()
        var port = preferredPort
        var createdSocket: ServerSocket? = null

        for (p in preferredPort..(preferredPort + 10)) {
            try {
                createdSocket = ServerSocket(p)
                port = p
                break
            } catch (_: Exception) {}
        }

        if (createdSocket == null) {
            Timber.tag(TAG).e("Failed to bind ServerSocket on ports $preferredPort..${preferredPort + 10}")
            return null
        }

        serverSocket = createdSocket
        val effectiveIp = ip ?: "127.0.0.1"
        val url = "http://$effectiveIp:$port"
        serverUrl.value = url
        isRunning.value = true

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope

        val appContext = context.applicationContext

        scope.launch {
            Timber.tag(TAG).i("Local music server started at $url")
            while (isActive && isRunning.value) {
                val client = try {
                    serverSocket?.accept()
                } catch (e: Exception) {
                    break
                } ?: break

                launch {
                    try {
                        handleClient(client, appContext, downloadCache, playerCache, database)
                    } catch (e: Exception) {
                        Timber.tag(TAG).d("Client connection closed: ${e.message}")
                    } finally {
                        try {
                            client.close()
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // Iniciar Servidor FTP en puerto 2121 (Soporte nativo para VLC en iOS)
        try {
            ftpSocket = ServerSocket(FTP_PORT)
            Timber.tag(TAG).i("FTP server started on port $FTP_PORT")
            scope.launch {
                while (isActive && isRunning.value) {
                    val client = try {
                        ftpSocket?.accept()
                    } catch (_: Exception) {
                        break
                    } ?: break

                    launch {
                        try {
                            handleFtpClient(client, appContext, downloadCache, playerCache, database)
                        } catch (e: Exception) {
                            Timber.tag(TAG).d("FTP connection closed: ${e.message}")
                        } finally {
                            try {
                                client.close()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to bind FTP port $FTP_PORT")
        }

        return url
    }

    @Synchronized
    fun stop() {
        isRunning.value = false
        serverUrl.value = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        try {
            ftpSocket?.close()
        } catch (_: Exception) {}
        ftpSocket = null
        serverScope?.cancel()
        serverScope = null
        Timber.tag(TAG).i("Local music server stopped")
    }

    private suspend fun handleClient(
        socket: Socket,
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
    ) {
        val inputStream = socket.getInputStream()
        val reader = inputStream.bufferedReader()

        val requestLine = reader.readLine() ?: return
        val tokens = requestLine.split(" ")
        if (tokens.size < 2) return

        val method = tokens[0].uppercase()
        val rawPath = tokens[1]
        val path = URLDecoder.decode(rawPath.substringBefore("?"), "UTF-8")

        // Parse HTTP request headers
        val headers = mutableMapOf<String, String>()
        while (true) {
            val headerLine = reader.readLine() ?: break
            if (headerLine.isEmpty()) break
            val colonIdx = headerLine.indexOf(':')
            if (colonIdx > 0) {
                val key = headerLine.substring(0, colonIdx).trim().lowercase()
                val value = headerLine.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }

        val output = socket.getOutputStream()

        // 1. WEBDAV OPTIONS METHOD
        if (method == "OPTIONS") {
            val resHeader = "HTTP/1.1 200 OK\r\n" +
                "DAV: 1, 2\r\n" +
                "Allow: OPTIONS, GET, HEAD, PROPFIND\r\n" +
                "MS-Author-Via: DAV\r\n" +
                "Content-Length: 0\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: OPTIONS, GET, HEAD, PROPFIND\r\n" +
                "Access-Control-Allow-Headers: Depth, Content-Type, Range, Authorization\r\n" +
                "Connection: close\r\n\r\n"
            output.write(resHeader.toByteArray(Charsets.UTF_8))
            output.flush()
            return
        }

        // 2. WEBDAV PROPFIND METHOD
        if (method == "PROPFIND") {
            val depth = headers["depth"] ?: "1"
            val songsToExpose = getExposedSongs(database)
            val xml = buildWebDavMultiStatus(songsToExpose, depth)
            val xmlBytes = xml.toByteArray(Charsets.UTF_8)
            val resHeader = "HTTP/1.1 207 Multi-Status\r\n" +
                "Content-Type: application/xml; charset=utf-8\r\n" +
                "Content-Length: ${xmlBytes.size}\r\n" +
                "DAV: 1, 2\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            output.write(resHeader.toByteArray(Charsets.UTF_8))
            output.write(xmlBytes)
            output.flush()
            return
        }

        if (method != "GET" && method != "HEAD") {
            writeResponse(socket.getOutputStream(), 405, "Method Not Allowed", "text/plain", "Method Not Allowed".toByteArray())
            return
        }

        when {
            path == "/" || path == "/index.html" -> {
                val likedSongs = try { database.likedSongsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val downloadedSongs = try { database.downloadedSongsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val librarySongs = try { database.songsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val userPlaylists = try { database.playlistsByNameAsc().first() } catch (_: Exception) { emptyList() }

                val playlistSongsMap = mutableMapOf<String, List<Song>>()
                userPlaylists.forEach { pl ->
                    try {
                        val pSongs = database.playlistSongs(pl.playlist.id).first().map { it.song }
                        playlistSongsMap[pl.playlist.id] = pSongs
                    } catch (_: Exception) {}
                }

                val songPlaylistsMap = mutableMapOf<String, MutableList<String>>()
                playlistSongsMap.forEach { (plId, songs) ->
                    songs.forEach { s ->
                        songPlaylistsMap.getOrPut(s.song.id) { mutableListOf() }.add(plId)
                    }
                }

                val allSongs = (likedSongs + downloadedSongs + librarySongs + playlistSongsMap.values.flatten()).distinctBy { it.song.id }

                val html = renderWebPage(
                    allSongs = allSongs,
                    likedIds = likedSongs.map { it.song.id }.toSet(),
                    downloadedIds = downloadedSongs.map { it.song.id }.toSet(),
                    userPlaylists = userPlaylists,
                    playlistSongsMap = playlistSongsMap,
                    songPlaylistsMap = songPlaylistsMap,
                )
                val htmlBytes = html.toByteArray(Charsets.UTF_8)
                writeResponse(output, 200, "OK", "text/html; charset=utf-8", htmlBytes)
            }

            path.startsWith("/download/") || path.startsWith("/stream/") -> {
                val songId = path.removePrefix("/download/").removePrefix("/stream/")
                val isDownload = path.startsWith("/download/")
                serveSong(output, songId, isDownload, context, downloadCache, playerCache, database)
            }

            path == "/download-zip" -> {
                val tab = rawPath.substringAfter("tab=", "all").substringBefore("&")

                val likedSongs = try { database.likedSongsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val downloadedSongs = try { database.downloadedSongsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val librarySongs = try { database.songsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val downloadedIds = downloadedSongs.map { it.song.id }.toSet()
                val likedIds = likedSongs.map { it.song.id }.toSet()

                val userPlaylists = try { database.playlistsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val playlistSongsMap = mutableMapOf<String, List<Song>>()
                userPlaylists.forEach { pl ->
                    try {
                        val pSongs = database.playlistSongs(pl.playlist.id).first().map { it.song }
                        playlistSongsMap[pl.playlist.id] = pSongs
                    } catch (_: Exception) {}
                }

                val allSongs = (likedSongs + downloadedSongs + librarySongs + playlistSongsMap.values.flatten()).distinctBy { it.song.id }

                val targetSongs = when {
                    tab == "liked" -> allSongs.filter { likedIds.contains(it.song.id) || it.song.liked }
                    tab == "downloaded" -> allSongs.filter { downloadedIds.contains(it.song.id) || it.song.dateDownload != null }
                    tab.startsWith("pl_") -> {
                        val plId = tab.removePrefix("pl_")
                        playlistSongsMap[plId] ?: emptyList()
                    }
                    else -> allSongs
                }

                serveZip(output, targetSongs, context, downloadCache, playerCache, database)
            }

            path == "/manifest" -> {
                val tab = rawPath.substringAfter("tab=", "all").substringBefore("&")
                val likedSongs = try { database.likedSongsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val downloadedSongs = try { database.downloadedSongsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val librarySongs = try { database.songsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val downloadedIds = downloadedSongs.map { it.song.id }.toSet()
                val likedIds = likedSongs.map { it.song.id }.toSet()

                val userPlaylists = try { database.playlistsByNameAsc().first() } catch (_: Exception) { emptyList() }
                val playlistSongsMap = mutableMapOf<String, List<Song>>()
                userPlaylists.forEach { pl ->
                    try {
                        val pSongs = database.playlistSongs(pl.playlist.id).first().map { it.song }
                        playlistSongsMap[pl.playlist.id] = pSongs
                    } catch (_: Exception) {}
                }

                val allSongs = (likedSongs + downloadedSongs + librarySongs + playlistSongsMap.values.flatten()).distinctBy { it.song.id }

                val filtered = when {
                    tab == "liked" -> allSongs.filter { likedIds.contains(it.song.id) || it.song.liked }
                    tab == "downloaded" -> allSongs.filter { downloadedIds.contains(it.song.id) || it.song.dateDownload != null }
                    tab.startsWith("pl_") -> {
                        val plId = tab.removePrefix("pl_")
                        playlistSongsMap[plId] ?: emptyList()
                    }
                    else -> allSongs
                }

                val json = buildString {
                    append("[")
                    filtered.forEachIndexed { i, song ->
                        val artists = song.artists.joinToString(", ") { it.name }.ifBlank { "" }
                        val title = song.song.title.ifBlank { song.song.id }
                        val raw = if (artists.isNotBlank()) "$artists - $title" else title
                        val filename = sanitizeFilename(raw)
                        if (i > 0) append(",")
                        append("{\"id\":\"${song.song.id}\",\"filename\":\"${filename}.aac\"}")
                    }
                    append("]")
                }
                val jsonBytes = json.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: ${jsonBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.UTF_8))
                output.write(jsonBytes)
                output.flush()
            }

            else -> {
                // Peticiones de canciones para WebDAV o clientes de streaming/descarga directa
                val cleanPath = path.removePrefix("/")
                val songsToExpose = getExposedSongs(database)

                val songMatch = songsToExpose.firstOrNull { s ->
                    val title = s.song.title.ifBlank { s.song.id }
                    val artists = s.artists.joinToString(", ") { it.name }.orEmpty()
                    val rawFilename = if (artists.isNotBlank() && artists != "Desconocido") "$artists - $title" else title
                    val m4aName = "${sanitizeFilename(rawFilename)}.m4a"
                    val aacName = "${sanitizeFilename(rawFilename)}.aac"
                    val idM4a = "${s.song.id}.m4a"
                    val idAac = "${s.song.id}.aac"

                    cleanPath.equals(m4aName, ignoreCase = true) ||
                    cleanPath.equals(aacName, ignoreCase = true) ||
                    cleanPath == idM4a || cleanPath == idAac ||
                    cleanPath == s.song.id || cleanPath.equals(title, ignoreCase = true) ||
                    cleanPath.removePrefix("download/") == s.song.id ||
                    cleanPath.removePrefix("stream/") == s.song.id
                } ?: try {
                    val idCandidate = cleanPath.removePrefix("download/").removePrefix("stream/").removeSuffix(".m4a").removeSuffix(".aac")
                    database.getSongByIdBlocking(idCandidate)
                } catch (_: Exception) { null }

                if (songMatch != null) {
                    val clientIp = socket.inetAddress?.hostAddress
                    activeClientIp.value = clientIp
                    currentServingSong.value = songMatch.song.title
                    currentServingStatus.value = "Sirviendo a cliente ($clientIp)..."

                    val result = getSongFileM4a(songMatch.song.id, context, downloadCache, playerCache, database)
                    if (result != null) {
                        val (filename, file) = result
                        serveFileWithRangeSupport(output, method, file, filename, headers["range"])
                        servedCount.value++
                        currentServingStatus.value = "Listo"
                        currentServingSong.value = null
                        return
                    }
                }

                writeResponse(socket.getOutputStream(), 404, "Not Found", "text/plain", "Resource not found".toByteArray())
            }
        }
    }

    private fun renderWebPage(
        allSongs: List<Song>,
        likedIds: Set<String>,
        downloadedIds: Set<String>,
        userPlaylists: List<Playlist>,
        playlistSongsMap: Map<String, List<Song>>,
        songPlaylistsMap: Map<String, List<String>>,
    ): String {
        val countAll = allSongs.size
        val countLiked = likedIds.size
        val countDownloaded = downloadedIds.size

        val tabsHtml = StringBuilder().apply {
            append("""<button class="tab-btn active" onclick="setTab('all', this)">Todas ($countAll)</button>""")
            append("""<button class="tab-btn" onclick="setTab('liked', this)">❤️ Me gusta ($countLiked)</button>""")
            append("""<button class="tab-btn" onclick="setTab('downloaded', this)">💾 Descargadas ($countDownloaded)</button>""")
            userPlaylists.forEach { pl ->
                val pCount = playlistSongsMap[pl.playlist.id]?.size ?: 0
                val pName = escapeHtml(pl.playlist.name)
                append("""<button class="tab-btn" onclick="setTab('pl_${pl.playlist.id}', this)">📁 $pName ($pCount)</button>""")
            }
        }.toString()

        val songCards = StringBuilder()
        allSongs.forEachIndexed { index, song ->
            val artists = song.artists.joinToString(", ") { it.name }.ifBlank { "Desconocido" }
            val title = song.song.title.ifBlank { "Canción ${index + 1}" }
            val songId = song.song.id
            val isLiked = likedIds.contains(songId) || song.song.liked
            val isDownloaded = downloadedIds.contains(songId) || song.song.dateDownload != null
            val durationSec = song.song.duration
            val durationStr = if (durationSec > 0) {
                val min = durationSec / 60
                val sec = durationSec % 60
                String.format("%d:%02d", min, sec)
            } else ""

            val rawFilename = if (artists.isNotBlank() && artists != "Desconocido") "$artists - $title" else title
            val cleanFilename = sanitizeFilename(rawFilename) + ".aac"
            val playlistsAttr = songPlaylistsMap[songId]?.joinToString(",") ?: ""

            val badges = buildString {
                if (isLiked) append("""<span class="badge badge-liked">❤️ Me gusta</span>""")
                if (isDownloaded) append("""<span class="badge badge-dl">💾 Descargada</span>""")
                append("""<span class="badge badge-pending badge-sync" id="sync-badge-$songId">📥 Pendiente</span>""")
            }

            songCards.append(
                """
                <div class="song-card" data-id="$songId" data-liked="$isLiked" data-dl="$isDownloaded" data-playlists="$playlistsAttr" data-filename="${escapeHtml(cleanFilename)}" data-title="${escapeHtml(title)}" data-artist="${escapeHtml(artists)}">
                  <div class="song-info">
                    <span class="song-index">${index + 1}</span>
                    <div class="song-meta">
                      <div class="song-title">${escapeHtml(title)}</div>
                      <div class="song-artist">${escapeHtml(artists)}${if (durationStr.isNotEmpty()) " • $durationStr" else ""} $badges</div>
                    </div>
                  </div>
                  <div class="song-controls">
                    <audio controls preload="none" src="/stream/$songId"></audio>
                    <a href="/download/$songId" download="${escapeHtml(cleanFilename)}" class="btn-dl" onclick="markSingleDownloaded('${escapeHtml(cleanFilename)}')">⬇️ .aac</a>
                  </div>
                </div>
                """.trimIndent()
            )
        }

        return """
        <!DOCTYPE html>
        <html lang="es">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <title>Metrolist iPhone Sync</title>
          <style>
            :root {
              --bg: #000000;
              --card: #1c1c1e;
              --card-hover: #2c2c2e;
              --text: #ffffff;
              --sub: #8e8e93;
              --accent: #fc3c44;
              --green: #30d158;
              --blue: #0a84ff;
              --border: #2c2c2e;
            }
            body {
              margin: 0;
              padding: 16px;
              font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Segoe UI", Roboto, sans-serif;
              background-color: var(--bg);
              color: var(--text);
              -webkit-font-smoothing: antialiased;
            }
            .header {
              text-align: center;
              margin: 12px 0 16px 0;
            }
            h1 {
              font-size: 24px;
              margin: 0 0 4px 0;
              font-weight: 700;
              letter-spacing: -0.5px;
            }
            .subtitle {
              color: var(--sub);
              font-size: 14px;
              margin: 0;
            }
            .search-box {
              max-width: 650px;
              margin: 0 auto 14px auto;
            }
            .search-input {
              width: 100%;
              box-sizing: border-box;
              background: var(--card);
              border: 1px solid var(--border);
              border-radius: 12px;
              padding: 12px 16px;
              color: #ffffff;
              font-size: 15px;
              outline: none;
            }
            .search-input:focus {
              border-color: var(--accent);
            }
            .tabs {
              display: flex;
              gap: 8px;
              max-width: 650px;
              margin: 0 auto 16px auto;
              overflow-x: auto;
              padding-bottom: 4px;
              scrollbar-width: none;
            }
            .tabs::-webkit-scrollbar { display: none; }
            .tab-btn {
              background: var(--card);
              border: 1px solid var(--border);
              color: var(--sub);
              padding: 8px 16px;
              border-radius: 20px;
              font-size: 14px;
              font-weight: 600;
              cursor: pointer;
              white-space: nowrap;
              transition: all 0.2s ease;
            }
            .tab-btn.active {
              background: var(--accent);
              color: #ffffff;
              border-color: var(--accent);
            }
            .sync-panel {
              max-width: 650px;
              margin: 0 auto 18px auto;
              background: var(--card);
              border: 1px solid var(--border);
              border-radius: 16px;
              padding: 16px;
              display: flex;
              flex-direction: column;
              gap: 12px;
            }
            .sync-stats {
              display: flex;
              justify-content: space-around;
              align-items: center;
              font-size: 14px;
              font-weight: 600;
              padding-bottom: 8px;
              border-bottom: 1px solid var(--border);
            }
            .sync-stats div {
              text-align: center;
            }
            .sync-stats span {
              display: block;
              font-size: 18px;
              font-weight: 700;
              margin-top: 2px;
            }
            .stat-total span { color: #ffffff; }
            .stat-synced span { color: var(--green); }
            .stat-pending span { color: #ff9f0a; }
            .sync-actions {
              display: flex;
              flex-direction: column;
              gap: 8px;
            }
            .btn-zip {
              width: 100%;
              background: linear-gradient(135deg, #0a84ff, #0056b3);
              color: #ffffff;
              border: none;
              border-radius: 12px;
              padding: 14px;
              font-size: 15px;
              font-weight: 700;
              cursor: pointer;
              text-decoration: none;
              text-align: center;
              box-sizing: border-box;
              box-shadow: 0 4px 14px rgba(10, 132, 255, 0.3);
              display: block;
            }
            .btn-zip:active { transform: scale(0.98); }
            .sync-subactions {
              display: flex;
              gap: 8px;
            }
            .btn-sync {
              flex: 2;
              background: linear-gradient(135deg, #30d158, #25a244);
              color: #ffffff;
              border: none;
              border-radius: 12px;
              padding: 12px;
              font-size: 14px;
              font-weight: 700;
              cursor: pointer;
              box-shadow: 0 4px 14px rgba(48, 209, 88, 0.3);
              transition: transform 0.1s ease;
            }
            .btn-sync:active { transform: scale(0.98); }
            .btn-sync.btn-done {
              background: #2c2c2e;
              color: var(--sub);
              box-shadow: none;
              cursor: default;
            }
            .btn-sync.btn-stop {
              background: #ff453a;
              box-shadow: 0 4px 14px rgba(255, 69, 58, 0.3);
            }
            .btn-filter-toggle {
              flex: 1;
              background: var(--card-hover);
              border: 1px solid var(--border);
              color: #ffffff;
              border-radius: 12px;
              padding: 12px 8px;
              font-size: 13px;
              font-weight: 600;
              cursor: pointer;
              white-space: nowrap;
            }
            .btn-filter-toggle.active {
              background: #ff9f0a;
              color: #000000;
              border-color: #ff9f0a;
            }
            .sync-status {
              background: #121212;
              border-radius: 10px;
              padding: 12px;
              font-size: 13px;
              line-height: 1.5;
            }
            .sync-progress-bar {
              height: 5px;
              background: #2c2c2e;
              border-radius: 4px;
              margin-top: 8px;
              overflow: hidden;
            }
            .sync-progress-fill {
              height: 100%;
              background: var(--green);
              border-radius: 4px;
              transition: width 0.3s ease;
              width: 0%;
            }
            .list {
              display: flex;
              flex-direction: column;
              gap: 10px;
              max-width: 650px;
              margin: 0 auto;
            }
            .song-card {
              background: var(--card);
              border-radius: 14px;
              padding: 12px 14px;
              border: 1px solid var(--border);
              display: flex;
              flex-direction: column;
              gap: 10px;
            }
            .song-info {
              display: flex;
              align-items: center;
              gap: 12px;
            }
            .song-index {
              font-size: 13px;
              font-weight: 600;
              color: var(--sub);
              min-width: 22px;
              text-align: center;
            }
            .song-meta {
              flex: 1;
              min-width: 0;
            }
            .song-title {
              font-size: 15px;
              font-weight: 600;
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
            }
            .song-artist {
              font-size: 12px;
              color: var(--sub);
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
              margin-top: 2px;
              display: flex;
              align-items: center;
              gap: 6px;
            }
            .badge {
              font-size: 10px;
              padding: 2px 6px;
              border-radius: 6px;
              font-weight: 600;
            }
            .badge-liked { background: rgba(255, 45, 85, 0.2); color: #ff6482; }
            .badge-dl { background: rgba(52, 199, 89, 0.2); color: #5cdb7d; }
            .badge-synced { background: rgba(48, 209, 88, 0.2); color: #30d158; }
            .badge-pending { background: rgba(255, 159, 10, 0.2); color: #ff9f0a; }
            .song-controls {
              display: flex;
              align-items: center;
              gap: 8px;
            }
            audio {
              flex: 1;
              height: 36px;
              border-radius: 8px;
              outline: none;
            }
            .btn-dl {
              background: var(--blue);
              color: #ffffff;
              padding: 8px 14px;
              border-radius: 10px;
              text-decoration: none;
              font-size: 13px;
              font-weight: 700;
              white-space: nowrap;
              transition: transform 0.1s ease;
            }
            .btn-dl:active { transform: scale(0.95); }
            .footer-info {
              text-align: center;
              margin: 30px 0 20px 0;
              color: var(--sub);
              font-size: 12px;
              line-height: 1.8;
            }
            .btn-reset {
              background: none;
              border: none;
              color: var(--sub);
              font-size: 12px;
              text-decoration: underline;
              cursor: pointer;
              padding: 4px;
            }
          </style>
        </head>
        <body>
          <div class="header">
            <h1>🎵 Metrolist Sync</h1>
            <p class="subtitle">Música .AAC nativa de Apple con carátulas en alta definición</p>
          </div>

          <div class="search-box">
            <input type="text" id="searchInput" class="search-input" placeholder="🔍 Buscar canción o artista..." oninput="updateFilter()">
          </div>

          <div class="tabs">
            $tabsHtml
          </div>

          <div class="sync-panel">
            <div class="sync-stats">
              <div class="stat-total">Total<span id="statTotal">0</span></div>
              <div class="stat-synced">En iPhone<span id="statSynced">0</span></div>
              <div class="stat-pending">Pendientes<span id="statPending">0</span></div>
            </div>
            <div class="sync-actions">
              <a id="btnZipAll" href="/download-zip?tab=all" class="btn-zip">📦 Descargar lista completa (.ZIP ultrarrápido)</a>
              <div class="sync-subactions">
                <button id="syncBtn" class="btn-sync" onclick="startSync()">⚡ Sincronizar una a una</button>
                <button id="btnTogglePending" class="btn-filter-toggle" onclick="togglePendingFilter()">📥 Solo pendientes</button>
              </div>
            </div>
            <div id="syncStatus" class="sync-status" style="display:none"></div>
          </div>

          <div class="list" id="songContainer">
            $songCards
          </div>

          <div class="footer-info">
            💡 <strong>Súper rápido:</strong> Se procesa directamente desde el almacenamiento de tu teléfono.<br>
            Descarga en formato AAC nativo con portada y se descomprime en 1 segundo en la app <strong>Archivos</strong>.<br><br>
            <button class="btn-reset" onclick="resetSync()">🔄 Reiniciar registro de sincronización</button>
          </div>

          <script>
            let activeTab = 'all';
            let showOnlyPending = false;
            let isSyncing = false;
            let shouldStopSync = false;

            function getSynced() {
              try { return JSON.parse(localStorage.getItem('metrolist_synced') || '[]'); }
              catch(e) { return []; }
            }

            function markSynced(filename) {
              const list = getSynced();
              if (!list.includes(filename)) {
                list.push(filename);
                localStorage.setItem('metrolist_synced', JSON.stringify(list));
              }
            }

            function setTab(tab, element) {
              activeTab = tab;
              document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
              element.classList.add('active');
              
              const zipBtn = document.getElementById('btnZipAll');
              if (zipBtn) {
                zipBtn.href = '/download-zip?tab=' + tab;
              }
              
              updateFilter();
            }

            function togglePendingFilter() {
              showOnlyPending = !showOnlyPending;
              const btn = document.getElementById('btnTogglePending');
              btn.textContent = showOnlyPending ? '👁️ Ver todas' : '📥 Solo pendientes';
              btn.classList.toggle('active', showOnlyPending);
              updateFilter();
            }

            function updateFilter() {
              const query = (document.getElementById('searchInput').value || '').toLowerCase().trim();
              const cards = document.querySelectorAll('.song-card');
              const syncedList = getSynced();

              cards.forEach(card => {
                const isLiked = card.getAttribute('data-liked') === 'true';
                const isDl = card.getAttribute('data-dl') === 'true';
                const playlists = (card.getAttribute('data-playlists') || '').split(',');
                const filename = card.getAttribute('data-filename');
                const isSynced = syncedList.includes(filename);
                const title = card.getAttribute('data-title').toLowerCase();
                const artist = card.getAttribute('data-artist').toLowerCase();

                let matchesTab = true;
                if (activeTab === 'liked') matchesTab = isLiked;
                else if (activeTab === 'downloaded') matchesTab = isDl;
                else if (activeTab.startsWith('pl_')) {
                  matchesTab = playlists.includes(activeTab.substring(3));
                }

                const matchesQuery = !query || title.includes(query) || artist.includes(query);
                const matchesPending = !showOnlyPending || !isSynced;

                card.style.display = (matchesTab && matchesQuery && matchesPending) ? 'flex' : 'none';
              });

              updateSyncHeader();
            }

            function updateSyncHeader() {
              const syncedList = getSynced();
              const cards = document.querySelectorAll('.song-card');
              let totalInTab = 0;
              let syncedInTab = 0;

              cards.forEach(card => {
                const isLiked = card.getAttribute('data-liked') === 'true';
                const isDl = card.getAttribute('data-dl') === 'true';
                const playlists = (card.getAttribute('data-playlists') || '').split(',');
                const filename = card.getAttribute('data-filename');
                const isSynced = syncedList.includes(filename);

                let inTab = true;
                if (activeTab === 'liked') inTab = isLiked;
                else if (activeTab === 'downloaded') inTab = isDl;
                else if (activeTab.startsWith('pl_')) {
                  inTab = playlists.includes(activeTab.substring(3));
                }

                const badge = card.querySelector('.badge-sync');
                if (badge) {
                  if (isSynced) {
                    badge.className = 'badge badge-synced badge-sync';
                    badge.textContent = '✅ En iPhone';
                  } else {
                    badge.className = 'badge badge-pending badge-sync';
                    badge.textContent = '📥 Pendiente';
                  }
                }

                if (inTab) {
                  totalInTab++;
                  if (isSynced) syncedInTab++;
                }
              });

              const pendingInTab = totalInTab - syncedInTab;
              document.getElementById('statTotal').textContent = totalInTab;
              document.getElementById('statSynced').textContent = syncedInTab;
              document.getElementById('statPending').textContent = pendingInTab;

              const syncBtn = document.getElementById('syncBtn');
              if (syncBtn && !isSyncing) {
                if (pendingInTab === 0) {
                  syncBtn.disabled = true;
                  syncBtn.textContent = '✅ Todo sincronizado';
                  syncBtn.classList.add('btn-done');
                } else {
                  syncBtn.disabled = false;
                  syncBtn.textContent = '⚡ Sincronizar (' + pendingInTab + ' pendientes)';
                  syncBtn.classList.remove('btn-done');
                }
              }
            }

            function markSingleDownloaded(filename) {
              markSynced(filename);
              setTimeout(updateSyncHeader, 100);
            }

            async function startSync() {
              if (isSyncing) {
                shouldStopSync = true;
                return;
              }

              const syncedList = getSynced();
              const cards = Array.from(document.querySelectorAll('.song-card')).filter(card => {
                const isLiked = card.getAttribute('data-liked') === 'true';
                const isDl = card.getAttribute('data-dl') === 'true';
                const playlists = (card.getAttribute('data-playlists') || '').split(',');
                const filename = card.getAttribute('data-filename');

                let inTab = true;
                if (activeTab === 'liked') inTab = isLiked;
                else if (activeTab === 'downloaded') inTab = isDl;
                else if (activeTab.startsWith('pl_')) inTab = playlists.includes(activeTab.substring(3));

                return inTab && !syncedList.includes(filename);
              });

              if (cards.length === 0) return;

              isSyncing = true;
              shouldStopSync = false;
              const syncBtn = document.getElementById('syncBtn');
              syncBtn.textContent = '⏹️ Detener';
              syncBtn.classList.add('btn-stop');

              const statusEl = document.getElementById('syncStatus');
              statusEl.style.display = 'block';

              const total = cards.length;
              for (let i = 0; i < total; i++) {
                if (shouldStopSync) {
                  statusEl.innerHTML = '⚠️ Sincronización detenida.';
                  break;
                }

                const card = cards[i];
                const songId = card.getAttribute('data-id');
                const filename = card.getAttribute('data-filename');

                const pct = Math.round(((i + 1) / total) * 100);
                statusEl.innerHTML = '📥 Descargando (' + (i + 1) + '/' + total + '):<br><strong>' + filename + '</strong>' +
                  '<div class="sync-progress-bar"><div class="sync-progress-fill" style="width:' + pct + '%"></div></div>';

                try {
                  const res = await fetch('/download/' + songId);
                  if (!res.ok) throw new Error('Error al descargar');
                  const blob = await res.blob();
                  
                  const url = URL.createObjectURL(blob);
                  const a = document.createElement('a');
                  a.href = url;
                  a.download = filename;
                  document.body.appendChild(a);
                  a.click();
                  document.body.removeChild(a);
                  setTimeout(() => URL.revokeObjectURL(url), 45000);

                  markSynced(filename);
                  updateSyncHeader();
                } catch(e) {
                  console.error('Error downloading ' + filename, e);
                  statusEl.innerHTML = '⚠️ Error en: ' + filename + '. Pasando a la siguiente...';
                }

                await new Promise(r => setTimeout(r, 1200));
              }

              isSyncing = false;
              syncBtn.classList.remove('btn-stop');
              if (!shouldStopSync) {
                statusEl.innerHTML = '🎉 ¡Sincronización completa! Se descargaron ' + total + ' canciones en formato .aac con carátula en alta definición.';
              }
              updateSyncHeader();
            }

            function resetSync() {
              if (confirm('¿Deseas reiniciar el historial de sincronización?')) {
                localStorage.removeItem('metrolist_synced');
                updateFilter();
              }
            }

            // Initial load
            updateFilter();
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    suspend fun getSongFileM4a(
        songId: String,
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
    ): Pair<String, File>? {
        val song = try { database.getSongByIdBlocking(songId) } catch (_: Exception) { null }
        val title = song?.song?.title ?: songId
        val artists = song?.artists?.joinToString(", ") { it.name }.orEmpty()
        val rawFilename = if (artists.isNotBlank() && artists != "Desconocido") "$artists - $title" else title
        val filename = "${sanitizeFilename(rawFilename)}.m4a"

        val syncCacheDir = File(context.cacheDir, "sync_m4a_v2").apply { mkdirs() }
        val cachedFile = File(syncCacheDir, "$songId.m4a")

        // Limpiar caché anterior v1 para evitar servir audios lentos o sin carátula
        try {
            val oldCache = File(context.cacheDir, "sync_m4a")
            if (oldCache.exists()) oldCache.deleteRecursively()
        } catch (_: Exception) {}

        val durationSec = song?.song?.duration ?: 0
        // A 192kbps AAC, 1 segundo = ~24 KB. Mínimo 10 KB/s para considerar archivo completo.
        val minExpectedM4aBytes = if (durationSec > 10) (durationSec * 10_000L) else 250_000L
        // En Opus de origen (generalmente 50-160 kbps), al menos 5 KB/s.
        val minExpectedSourceBytes = if (durationSec > 10) (durationSec * 5_000L) else 150_000L

        // 1. Si ya está en caché M4A y completo, devolver de inmediato (0.001s)
        if (cachedFile.exists()) {
            if (cachedFile.length() >= minExpectedM4aBytes) {
                ensureMetadataAndCover(cachedFile, song, songId, title, artists)
                return Pair(filename, cachedFile)
            } else {
                Timber.tag(TAG).w("Cache M4A incompleto detectado (${cachedFile.length()} bytes vs esperado $minExpectedM4aBytes). Re-generando.")
                cachedFile.delete()
            }
        }

        currentServingStatus.value = "Preparando $title..."
        currentServingSong.value = title

        // 2. Transcodificar desde caché local ÚNICAMENTE si está 100% completo
        val downloadSpans = downloadCache.getCachedSpans(songId).sortedBy { it.position }
        val downloadBytes = downloadSpans.sumOf { it.length }

        var spans = if (downloadBytes >= minExpectedSourceBytes) downloadSpans else emptyList()

        // Solo usar playerCache si también contiene el archivo completo (evita fragmentos de 14s/56s)
        if (spans.isEmpty()) {
            val playerSpans = playerCache.getCachedSpans(songId).sortedBy { it.position }
            if (playerSpans.sumOf { it.length } >= minExpectedSourceBytes) {
                spans = playerSpans
            }
        }

        if (spans.isNotEmpty()) {
            val tempSource = File.createTempFile("src_$songId", ".tmp", context.cacheDir)
            try {
                tempSource.outputStream().use { out ->
                    for (span in spans) {
                        span.file?.inputStream()?.use { it.copyTo(out) }
                    }
                }

                if (tempSource.length() >= minExpectedSourceBytes) {
                    val success = AudioTranscoder.transcodeToM4a(
                        sourceFile = tempSource,
                        destM4aFile = cachedFile,
                    )
                    if (success && cachedFile.exists() && cachedFile.length() >= minExpectedM4aBytes) {
                        ensureMetadataAndCover(cachedFile, song, songId, title, artists)
                        return Pair(filename, cachedFile)
                    } else {
                        cachedFile.delete()
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error convirtiendo caché local M4A para $songId")
                cachedFile.delete()
            } finally {
                tempSource.delete()
            }
        }

        // 3. Fallback: Descarga íntegra desde YouTube con InnerTubeXPlayer
        val connectivityManager = context.getSystemService<ConnectivityManager>()
        if (connectivityManager != null) {
            try {
                val songEntity = song?.song ?: database.songEntity(songId)
                val playbackData = InnerTubeXPlayer.playerResponseForPlayback(
                    videoId = songId,
                    audioQuality = AudioQuality.LOW,
                    connectivityManager = connectivityManager,
                    contentHints = ContentHints(
                        isExplicit = songEntity?.explicit,
                        isUploaded = songEntity?.isUploaded,
                    ),
                ).getOrNull()

                if (playbackData != null) {
                    val reqBuilder = Request.Builder().url(playbackData.streamUrl).get()
                    playbackData.streamHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

                    val clientToUse = httpClient.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS)
                        .build()

                    val tempRemote = File.createTempFile("rem_$songId", ".tmp", context.cacheDir)
                    try {
                        val call = clientToUse.newCall(reqBuilder.build())
                        val response = call.execute()
                        response.use { resp ->
                            if (resp.isSuccessful) {
                                val body = resp.body
                                if (body != null) {
                                    tempRemote.outputStream().use { out -> body.byteStream().copyTo(out, bufferSize = 65536) }
                                }
                            }
                        }

                        if (tempRemote.length() >= minExpectedSourceBytes) {
                            val mime = playbackData.format.mimeType.orEmpty()
                            val isAlreadyM4a = mime.contains("audio/mp4", ignoreCase = true) || mime.contains("audio/m4a", ignoreCase = true)

                            val success = if (isAlreadyM4a) {
                                tempRemote.copyTo(cachedFile, overwrite = true)
                                true
                            } else {
                                AudioTranscoder.transcodeToM4a(
                                    sourceFile = tempRemote,
                                    destM4aFile = cachedFile,
                                )
                            }
                            if (success && cachedFile.exists() && cachedFile.length() >= minExpectedM4aBytes) {
                                ensureMetadataAndCover(cachedFile, song, songId, title, artists)
                                return Pair(filename, cachedFile)
                            } else {
                                cachedFile.delete()
                            }
                        }
                    } finally {
                        tempRemote.delete()
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error descargando y convirtiendo M4A remoto para $songId")
                cachedFile.delete()
            }
        }

        return null
    }

    private fun ensureMetadataAndCover(
        m4aFile: File,
        song: Song?,
        songId: String,
        title: String,
        artists: String,
    ) {
        val album = song?.album?.title.orEmpty()
        val coverBytes = fetchCoverArtBytes(song, songId, title)

        M4aMetadataInjector.embedMetadata(
            m4aFile = m4aFile,
            title = title,
            artist = artists,
            album = album,
            coverBytes = coverBytes,
        )
    }

    private fun fetchCoverArtBytes(
        song: Song?,
        songId: String,
        title: String,
    ): ByteArray? {
        val urlCandidates = mutableListOf<String>()

        // 1. URL de la canción
        val songThumb = song?.song?.thumbnailUrl
        if (!songThumb.isNullOrBlank()) {
            if (songThumb.contains("=w") || songThumb.contains("=s")) {
                val hqThumb = songThumb
                    .replace(Regex("=w\\d+-h\\d+.*"), "=w800-h800-l90-rj")
                    .replace(Regex("=s\\d+.*"), "=s800")
                if (hqThumb != songThumb) urlCandidates.add(hqThumb)
            }
            urlCandidates.add(songThumb)
        }

        // 2. URL del álbum
        val albumThumb = song?.album?.thumbnailUrl
        if (!albumThumb.isNullOrBlank() && !urlCandidates.contains(albumThumb)) {
            urlCandidates.add(albumThumb)
        }

        // 3. Fallback directo a CDN de YouTube usando el ID de la canción
        val cleanId = songId.trim()
        if (cleanId.length == 11 && !cleanId.contains("/")) {
            urlCandidates.add("https://i.ytimg.com/vi/$cleanId/maxresdefault.jpg")
            urlCandidates.add("https://i.ytimg.com/vi/$cleanId/hqdefault.jpg")
            urlCandidates.add("https://i.ytimg.com/vi/$cleanId/mqdefault.jpg")
        }

        for (url in urlCandidates) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .get()
                    .build()

                val rawBytes = httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }

                if (rawBytes != null && rawBytes.isNotEmpty()) {
                    val normalized = normalizeToJpeg(rawBytes)
                    if (normalized != null && normalized.isNotEmpty()) {
                        return normalized
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("Fallo al obtener portada candidata $url para $title: ${e.message}")
            }
        }
        return null
    }

    private fun normalizeToJpeg(rawBytes: ByteArray): ByteArray? {
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return rawBytes
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            rawBytes
        }
    }

    suspend fun getSongFile(
        songId: String,
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
    ): Pair<String, File>? {
        return getSongFileM4a(songId, context, downloadCache, playerCache, database)
    }

    private suspend fun serveSong(
        output: OutputStream,
        songId: String,
        isDownload: Boolean,
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
    ) {
        val result = getSongFileM4a(songId, context, downloadCache, playerCache, database)
        if (result != null) {
            val (filename, file) = result
            serveFileWithRangeSupport(output, "GET", file, filename, null)
            return
        }

        writeResponse(output, 404, "Not Found", "text/plain", "Song not found".toByteArray())
    }

    private fun serveFileWithRangeSupport(
        output: OutputStream,
        method: String,
        file: File,
        filename: String,
        rangeHeader: String?,
    ) {
        val fileLength = file.length()
        if (fileLength == 0L) {
            writeResponse(output, 404, "Not Found", "text/plain", "File empty".toByteArray())
            return
        }

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val ranges = rangeHeader.removePrefix("bytes=").split("-")
            val start = ranges.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = ranges.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (fileLength - 1L)

            val boundedStart = start.coerceIn(0L, fileLength - 1L)
            val boundedEnd = end.coerceIn(boundedStart, fileLength - 1L)
            val contentLength = boundedEnd - boundedStart + 1L

            val header = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: audio/mp4\r\n" +
                "Content-Range: bytes $boundedStart-$boundedEnd/$fileLength\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Disposition: inline; filename=\"$filename\"\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"

            output.write(header.toByteArray(Charsets.UTF_8))

            if (method != "HEAD") {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(boundedStart)
                    val buffer = ByteArray(64 * 1024)
                    var bytesRemaining = contentLength
                    while (bytesRemaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                        val bytesRead = raf.read(buffer, 0, toRead)
                        if (bytesRead <= 0) break
                        output.write(buffer, 0, bytesRead)
                        bytesRemaining -= bytesRead
                    }
                }
                output.flush()
            }
        } else {
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/mp4\r\n" +
                "Content-Length: $fileLength\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Disposition: inline; filename=\"$filename\"\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"

            output.write(header.toByteArray(Charsets.UTF_8))

            if (method != "HEAD") {
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.flush()
            }
        }
    }

    suspend fun getExposedSongs(database: MusicDatabase): List<Song> {
        val filter = exposedSongIds.value
        val allSongs = try {
            val downloaded = database.downloadedSongsByNameAsc().first()
            val liked = database.likedSongsByNameAsc().first()
            (downloaded + liked).distinctBy { it.song.id }
        } catch (_: Exception) {
            emptyList()
        }
        return if (filter != null) {
            allSongs.filter { filter.contains(it.song.id) }
        } else {
            allSongs
        }
    }

    private fun buildWebDavMultiStatus(
        songs: List<Song>,
        depth: String,
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<d:multistatus xmlns:d=\"DAV:\">\n")

        // Colección Raíz
        sb.append("  <d:response>\n")
        sb.append("    <d:href>/</d:href>\n")
        sb.append("    <d:propstat>\n")
        sb.append("      <d:prop>\n")
        sb.append("        <d:displayname>Metrolist Music</d:displayname>\n")
        sb.append("        <d:resourcetype><d:collection/></d:resourcetype>\n")
        sb.append("      </d:prop>\n")
        sb.append("      <d:status>HTTP/1.1 200 OK</d:status>\n")
        sb.append("    </d:propstat>\n")
        sb.append("  </d:response>\n")

        if (depth != "0") {
            for (song in songs) {
                val title = song.song.title.ifBlank { song.song.id }
                val artists = song.artists.joinToString(", ") { it.name }.orEmpty()
                val rawFilename = if (artists.isNotBlank() && artists != "Desconocido") "$artists - $title" else title
                val filename = "${sanitizeFilename(rawFilename)}.m4a"
                val durationSec = song.song.duration.takeIf { it > 0 } ?: 180
                val estimatedSize = durationSec * 24_000L

                val encodedHref = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")

                sb.append("  <d:response>\n")
                sb.append("    <d:href>/$encodedHref</d:href>\n")
                sb.append("    <d:propstat>\n")
                sb.append("      <d:prop>\n")
                sb.append("        <d:displayname>${escapeXml(filename)}</d:displayname>\n")
                sb.append("        <d:resourcetype/>\n")
                sb.append("        <d:getcontenttype>audio/mp4</d:getcontenttype>\n")
                sb.append("        <d:getcontentlength>$estimatedSize</d:getcontentlength>\n")
                sb.append("      </d:prop>\n")
                sb.append("      <d:status>HTTP/1.1 200 OK</d:status>\n")
                sb.append("    </d:propstat>\n")
                sb.append("  </d:response>\n")
            }
        }

        sb.append("</d:multistatus>")
        return sb.toString()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private suspend fun serveZip(
        output: OutputStream,
        songs: List<Song>,
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
    ) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/zip\r\n" +
                "Content-Disposition: attachment; filename=\"Metrolist_Music.zip\"\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"

        output.write(header.toByteArray(Charsets.UTF_8))

        val zipOut = ZipOutputStream(output)
        val addedNames = mutableSetOf<String>()

        for (song in songs) {
            try {
                val result = getSongFile(song.song.id, context, downloadCache, playerCache, database) ?: continue
                var entryName = result.first
                if (addedNames.contains(entryName)) {
                    entryName = "${song.song.id}_$entryName"
                }
                addedNames.add(entryName)

                zipOut.putNextEntry(ZipEntry(entryName))
                result.second.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
                zipOut.flush()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error adding song ${song.song.id} to zip")
            }
        }

        try {
            zipOut.finish()
            zipOut.flush()
        } catch (_: Exception) {}
    }

    private fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
    ) {
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private suspend fun handleFtpClient(
        socket: Socket,
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
    ) = withContext(Dispatchers.IO) {
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val writer = socket.getOutputStream()

        fun sendLine(code: Int, text: String) {
            val msg = "$code $text\r\n"
            writer.write(msg.toByteArray(Charsets.UTF_8))
            writer.flush()
        }

        sendLine(220, "Metrolist FTP Server ready.")

        var pendingPassiveSocket: ServerSocket? = null
        var restartOffset = 0L

        try {
            while (isActive && isRunning.value) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val parts = line.trim().split(" ", limit = 2)
                val cmd = parts[0].uppercase()
                val arg = parts.getOrNull(1).orEmpty().trim().removeSurrounding("\"")

                when (cmd) {
                    "USER" -> sendLine(230, "User logged in anonymously.")
                    "PASS" -> sendLine(230, "User logged in.")
                    "SYST" -> sendLine(215, "UNIX Type: L8")
                    "FEAT" -> {
                        val feat = "211-Features:\r\n SIZE\r\n UTF8\r\n REST STREAM\r\n PASV\r\n EPSV\r\n211 End\r\n"
                        writer.write(feat.toByteArray(Charsets.UTF_8))
                        writer.flush()
                    }
                    "OPTS" -> sendLine(200, "UTF8 set to on.")
                    "PWD" -> sendLine(257, "\"/\" is current directory.")
                    "CWD", "CDUP" -> sendLine(250, "Directory changed to /")
                    "TYPE" -> sendLine(200, "Type set to I.")
                    "MODE" -> sendLine(200, "Mode set to S.")
                    "STRU" -> sendLine(200, "Structure set to F.")
                    "NOOP" -> sendLine(200, "OK.")
                    "PASV" -> {
                        pendingPassiveSocket?.close()
                        val pasv = ServerSocket(0)
                        pendingPassiveSocket = pasv
                        val localAddr = getLocalIpAddress() ?: "127.0.0.1"
                        val ipParts = localAddr.split(".").map { it.toIntOrNull() ?: 0 }
                        val port = pasv.localPort
                        val p1 = port / 256
                        val p2 = port % 256
                        val ipFormatted = "${ipParts.getOrElse(0){127}},${ipParts.getOrElse(1){0}},${ipParts.getOrElse(2){0}},${ipParts.getOrElse(3){1}},$p1,$p2"
                        sendLine(227, "Entering Passive Mode ($ipFormatted).")
                    }
                    "EPSV" -> {
                        pendingPassiveSocket?.close()
                        val pasv = ServerSocket(0)
                        pendingPassiveSocket = pasv
                        sendLine(229, "Entering Extended Passive Mode (|||${pasv.localPort}|).")
                    }
                    "REST" -> {
                        restartOffset = arg.toLongOrNull() ?: 0L
                        sendLine(350, "Restarting at $restartOffset. Send RETR.")
                    }
                    "SIZE" -> {
                        val songs = getExposedSongs(database)
                        val match = findSongForFilename(songs, arg)
                        if (match != null) {
                            val durationSec = match.song.duration.takeIf { it > 0 } ?: 180
                            val size = durationSec * 24_000L
                            sendLine(213, "$size")
                        } else {
                            sendLine(550, "File not found.")
                        }
                    }
                    "MLSD", "LIST", "NLST" -> {
                        val pasv = pendingPassiveSocket
                        if (pasv == null) {
                            sendLine(425, "Use PASV or EPSV first.")
                            continue
                        }
                        sendLine(150, "Opening ASCII mode data connection for file list.")
                        try {
                            pasv.accept().use { dataClient ->
                                val dataOut = dataClient.getOutputStream().bufferedWriter(Charsets.UTF_8)
                                val songs = getExposedSongs(database)
                                val isNlst = cmd == "NLST"
                                val isMlsd = cmd == "MLSD"
                                for (s in songs) {
                                    val title = s.song.title.ifBlank { s.song.id }
                                    val artists = s.artists.joinToString(", ") { it.name }.orEmpty()
                                    val rawFilename = if (artists.isNotBlank() && artists != "Desconocido") "$artists - $title" else title
                                    val filename = "${sanitizeFilename(rawFilename)}.m4a"
                                    val durationSec = s.song.duration.takeIf { it > 0 } ?: 180
                                    val size = durationSec * 24_000L

                                    when {
                                        isNlst -> dataOut.write("$filename\r\n")
                                        isMlsd -> dataOut.write("type=file;size=$size;modify=20260101000000; $filename\r\n")
                                        else -> dataOut.write("-rw-r--r--   1 ftp      ftp      $size Jan 01 00:00 $filename\r\n")
                                    }
                                }
                                dataOut.flush()
                            }
                            sendLine(226, "Directory send OK.")
                        } catch (e: Exception) {
                            sendLine(426, "Data connection closed: ${e.message}")
                        } finally {
                            pasv.close()
                            pendingPassiveSocket = null
                        }
                    }
                    "RETR" -> {
                        val pasv = pendingPassiveSocket
                        if (pasv == null) {
                            sendLine(425, "Use PASV or EPSV first.")
                            continue
                        }
                        val songs = getExposedSongs(database)
                        val match = findSongForFilename(songs, arg)
                        if (match == null) {
                            sendLine(550, "File not found: $arg")
                            pasv.close()
                            pendingPassiveSocket = null
                            continue
                        }

                        val title = match.song.title
                        currentServingSong.value = title
                        currentServingStatus.value = "Sirviendo a VLC (FTP)..."

                        val result = getSongFileM4a(match.song.id, context, downloadCache, playerCache, database)
                        if (result == null) {
                            sendLine(550, "Failed to transcode song.")
                            pasv.close()
                            pendingPassiveSocket = null
                            currentServingSong.value = null
                            continue
                        }

                        val (_, m4aFile) = result
                        val fileLength = m4aFile.length()
                        sendLine(150, "Opening BINARY mode data connection for ${m4aFile.name} ($fileLength bytes).")

                        try {
                            pasv.accept().use { dataClient ->
                                val dataOut = dataClient.getOutputStream()
                                RandomAccessFile(m4aFile, "r").use { raf ->
                                    if (restartOffset in 1 until fileLength) {
                                        raf.seek(restartOffset)
                                    }
                                    val buffer = ByteArray(64 * 1024)
                                    while (isActive && isRunning.value) {
                                        val read = raf.read(buffer)
                                        if (read <= 0) break
                                        dataOut.write(buffer, 0, read)
                                    }
                                    dataOut.flush()
                                }
                            }
                            servedCount.intValue++
                            currentServingStatus.value = "Listo"
                            currentServingSong.value = null
                            sendLine(226, "Transfer complete.")
                        } catch (e: Exception) {
                            sendLine(426, "Transfer aborted: ${e.message}")
                        } finally {
                            restartOffset = 0L
                            pasv.close()
                            pendingPassiveSocket = null
                        }
                    }
                    "QUIT" -> {
                        sendLine(221, "Goodbye.")
                        break
                    }
                    else -> sendLine(502, "Command not implemented.")
                }
            }
        } finally {
            try { pendingPassiveSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun findSongForFilename(songs: List<Song>, rawArg: String): Song? {
        var cleanName = rawArg.trim().removeSurrounding("\"").removePrefix("/")
        if (cleanName.contains("00:00 ")) {
            cleanName = cleanName.substringAfterLast("00:00 ").trim()
        }
        cleanName = cleanName.removeSuffix(".m4a").removeSuffix(".aac").trim()
        return songs.firstOrNull { s ->
            val title = s.song.title.ifBlank { s.song.id }
            val artists = s.artists.joinToString(", ") { it.name }.orEmpty()
            val raw = if (artists.isNotBlank() && artists != "Desconocido") "$artists - $title" else title
            val sanitized = sanitizeFilename(raw)
            cleanName.equals(sanitized, ignoreCase = true) ||
            cleanName.equals(title, ignoreCase = true) ||
            cleanName == s.song.id
        }
    }

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
