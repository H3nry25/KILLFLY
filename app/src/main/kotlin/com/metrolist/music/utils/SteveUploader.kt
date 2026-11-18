/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.media3.datasource.cache.Cache
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import timber.log.Timber
import java.io.File
import java.text.DecimalFormat
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Estados por canción durante el envío
 */
@Serializable
enum class SongSendStatus {
    PENDING,        // Pendiente por enviar
    QUEUED,         // En cola, esperando turno
    TRANSCODING,    // Obteniendo/transcodificando archivo
    UPLOADING,      // Subiendo activamente
    SENT,           // Enviado exitosamente
    FAILED,         // Falló después de todos los reintentos
    SKIPPED         // Saltado (ya estaba enviado, cancelado, etc.)
}

/**
 * Estado detallado persistente de una canción
 */
@Serializable
data class SongSendState(
    val songId: String,
    val status: SongSendStatus = SongSendStatus.PENDING,
    val attempt: Int = 0,
    val maxAttempts: Int = 3,
    val error: String? = null,
    val progress: Float = 0f,        // 0.0 a 1.0 (transcodificación + subida)
    val title: String = "",
    val fileSize: Long = 0,          // Tamaño archivo local
    val bytesSent: Long = 0,         // Bytes enviados en intento actual
    val lastAttemptAt: Long = 0,     // Timestamp último intento
    val sentAt: Long = 0,            // Timestamp cuando se marcó SENT
)

/**
 * Resultado final de una canción enviada
 */
data class SongSendResult(
    val songId: String,
    val success: Boolean,
    val error: String? = null,
    val finalStatus: SongSendStatus = SongSendStatus.SENT
)

/**
 * Configuración de reintentos y paralelismo
 */
data class UploadConfig(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 5000,
    val backoffFactor: Float = 2.0f,
    val maxParallelUploads: Int = 1,        // 1 a 1: Evita sobrecargar el servidor embebido de Steve (iOS) y asegura entrega 100%
    val connectTimeoutSec: Int = 10,        // Conexión rápida
    val readTimeoutSec: Int = 120,          // 2 min para archivos grandes
    val writeTimeoutSec: Int = 120,
    val chunkSizeBytes: Int = 256 * 1024,
)

@Deprecated("El flujo Push POST /upload.json ha sido sustituido por el Servidor WebDAV local en LocalMusicServer")
object SteveUploader {
    private const val TAG = "SteveUploader"
    private const val PREFS_NAME = "steve_sync_prefs"
    private const val KEY_SENT_IDS = "sent_song_ids"           // Historial global persistente
    private const val KEY_SONG_STATES = "song_send_states"     // Estado detallado por canción (JSON)
    private const val KEY_SESSION_SENT = "sent_in_session"     // Solo sesión actual

    // ============ ESTADO OBSERVABLE PARA UI ============
    val songSendStates = mutableStateMapOf<String, SongSendState>()
    val isUploading = mutableStateOf(false)
    val uploadProgress = mutableFloatStateOf(0f)      // Progreso global 0-1
    val currentSongName = mutableStateOf("")
    val uploadedCount = mutableIntStateOf(0)
    val totalCount = mutableIntStateOf(0)
    val statusMessage = mutableStateOf("")
    val activeUploads = mutableIntStateOf(0)          // Cuántas subiendo ahora
    val queuedCount = mutableIntStateOf(0)            // Cuántas en cola

    // Detalle visual en tiempo real de la canción en transferencia
    val currentSong = mutableStateOf<Song?>(null)
    val currentSongProgress = mutableFloatStateOf(0f)  // Progreso individual 0-1
    val currentSongBytes = mutableStateOf<Pair<Long, Long>?>(null) // (enviados, total)
    val currentStage = mutableStateOf("")              // Etapa actual: "Transcodificando", "Enviando", etc.
    val lastSentSong = mutableStateOf<Song?>(null)     // Última canción enviada con éxito

    @Volatile
    private var cachedWorkingBaseUrl: String? = null

    private val defaultConfig = UploadConfig()
    private var shouldCancel = AtomicBoolean(false)
    private var uploadJob: Job? = null
    private val httpClient: OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<X509TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0])
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(defaultConfig.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
                .readTimeout(defaultConfig.readTimeoutSec.toLong(), TimeUnit.SECONDS)
                .writeTimeout(defaultConfig.writeTimeoutSec.toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        } catch (_: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(defaultConfig.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
                .readTimeout(defaultConfig.readTimeoutSec.toLong(), TimeUnit.SECONDS)
                .writeTimeout(defaultConfig.writeTimeoutSec.toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ============ PERSISTENCIA DE ESTADO POR CANCIÓN ============

    /**
     * Guarda el estado completo de todas las canciones en SharedPreferences (JSON)
     */
    fun saveSongStates(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = songSendStates.values.toList()
        val jsonString = json.encodeToString<List<SongSendState>>(list)
        prefs.edit().putString(KEY_SONG_STATES, jsonString).apply()
    }

    /**
     * Carga estados persistentes al iniciar
     */
    fun loadSongStates(context: Context): List<SongSendState> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_SONG_STATES, "") ?: ""
        if (jsonString.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<SongSendState>>(jsonString)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error cargando estados, empezando limpio")
            emptyList()
        }
    }

    /**
     * Inicializa estados desde persistencia + historial global
     */
    fun initializeStates(context: Context, allSongIds: List<String>) {
        val persisted = loadSongStates(context).associateBy { it.songId }
        val globallySent = getSentSongIds(context)

        songSendStates.clear()
        for (songId in allSongIds) {
            val state = persisted[songId] ?: SongSendState(songId = songId)
            // Si está en historial global pero no en persistido → SENT
            val finalState = if (globallySent.contains(songId) && state.status != SongSendStatus.SENT) {
                state.copy(status = SongSendStatus.SENT, sentAt = System.currentTimeMillis())
            } else {
                state
            }
            songSendStates[songId] = finalState
        }
        updateCounters()
    }

    /**
     * Actualiza contadores globales para UI
     */
    private fun updateCounters() {
        val states = songSendStates.values
        totalCount.value = states.size
        uploadedCount.value = states.count { it.status == SongSendStatus.SENT }
        activeUploads.value = states.count { it.status == SongSendStatus.UPLOADING }
        queuedCount.value = states.count { it.status in setOf(SongSendStatus.PENDING, SongSendStatus.QUEUED, SongSendStatus.TRANSCODING) }
        uploadProgress.value = if (totalCount.value > 0) {
            uploadedCount.value.toFloat() / totalCount.value
        } else 0f
    }

    // ============ HISTORIAL GLOBAL (SIMPLE SET DE IDs) ============

    fun getSentSongIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SENT_IDS, emptySet()) ?: emptySet()
    }

    fun markSongSent(context: Context, songId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_SENT_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(songId)
        prefs.edit().putStringSet(KEY_SENT_IDS, set).apply()

        // Actualizar estado en memoria
        val current = songSendStates[songId] ?: SongSendState(songId = songId)
        songSendStates[songId] = current.copy(
            status = SongSendStatus.SENT,
            progress = 1f,
            sentAt = System.currentTimeMillis()
        )
        saveSongStates(context)
        updateCounters()
    }

    fun clearSentHistory(context: Context) {
        // 1. Quitar de SharedPreferences (historial global de IDs)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SENT_IDS).apply()
        prefs.edit().remove(KEY_SONG_STATES).apply()
        prefs.edit().remove(KEY_SESSION_SENT).apply()

        // 2. Limpiar cache local de AAC para forzar re-transcodificación limpia completa
        try {
            val syncCacheDir = java.io.File(context.cacheDir, "sync_aac")
            syncCacheDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}

        // 3. Reiniciar estados en memoria a PENDING en vez de vaciar el mapa
        for (key in songSendStates.keys) {
            val s = songSendStates[key]
            if (s != null) {
                songSendStates[key] = s.copy(
                    status = SongSendStatus.PENDING,
                    progress = 0f,
                    error = null,
                    attempt = 0,
                    sentAt = 0L,
                    bytesSent = 0L
                )
            }
        }
        updateCounters()
    }

    // ============ CONSULTA GET DE ARCHIVOS EXISTENTES EN VLC ============

    /**
     * Consulta al servidor VLC (o Steve) mediante HTTP GET para obtener la lista de canciones
     * que ya existen en el iPhone, evitando subirlas de nuevo.
     */
    suspend fun fetchExistingVlcFiles(host: String): Set<String> {
        return withContext(Dispatchers.IO) {
            val cleanHost = host.removePrefix("http://").removePrefix("https://").removePrefix("HTTP://").removePrefix("HTTPS://").removeSuffix("/")
            val hostWithoutPort = cleanHost.substringBefore(":")
            val urlsToTry = mutableListOf<String>()

            if (cleanHost.contains(":")) {
                // Usuario especificó puerto (ej. :8080), usar solo ese
                urlsToTry.add("http://$cleanHost/")
                urlsToTry.add("http://$cleanHost/upload.json")
            } else {
                // Sin puerto explícito: probar VLC puerto 80 y Steve puerto 8080
                urlsToTry.add("http://$cleanHost/")
                urlsToTry.add("http://$cleanHost/upload.json")
                urlsToTry.add("http://$hostWithoutPort/")
                urlsToTry.add("http://$hostWithoutPort/upload.json")
                urlsToTry.add("http://$cleanHost:8080/")
                urlsToTry.add("http://$cleanHost:8080/upload.json")
            }
            // NO probar HTTPS (puerto 443) - Steve y VLC usan HTTP

            val foundFiles = mutableSetOf<String>()

            for (url in urlsToTry) {
                try {
                    val req = Request.Builder().url(url).get().build()
                    httpClient.newCall(req).execute().use { res ->
                        if (res.isSuccessful) {
                            val bodyStr = res.body.string()
                            val audioRegex = Regex("""([a-zA-Z0-9_\-\.\s%]+?\.(aac|mp3|m4a|flac|opus|ogg|wav))""", RegexOption.IGNORE_CASE)
                            val matches = audioRegex.findAll(bodyStr)
                            for (m in matches) {
                                val rawName = m.groupValues[1]
                                val decoded = try {
                                    java.net.URLDecoder.decode(rawName, "UTF-8")
                                } catch (_: Exception) { rawName }
                                foundFiles.add(decoded.trim().lowercase())
                            }
                            if (foundFiles.isNotEmpty()) {
                                Timber.tag(TAG).d("Detectados ${foundFiles.size} archivos en VLC via GET $url")
                                return@withContext foundFiles
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).d("GET falló en $url: ${e.message}")
                }
            }
            foundFiles
        }
    }

    // ============ CONTROL DE EJECUCIÓN ============

    fun cancel() {
        shouldCancel.set(true)
        uploadJob?.cancel()
        isUploading.value = false
        statusMessage.value = "Envío cancelado"
        currentSong.value = null
        currentSongBytes.value = null
        currentSongProgress.floatValue = 0f
        currentStage.value = ""
        // Marcar UPLOADING/TRANSCODING/QUEUED como PENDING para reintentar después
        for ((id, state) in songSendStates) {
            if (state.status in setOf(SongSendStatus.UPLOADING, SongSendStatus.TRANSCODING, SongSendStatus.QUEUED)) {
                songSendStates[id] = state.copy(status = SongSendStatus.PENDING)
            }
        }
        // Guardar estados (necesitamos contexto - se llamará desde UI que lo tiene)
        // saveSongStates(context) - se hará desde la UI al cancelar
        updateCounters()
    }

    fun resetSongState(songId: String, context: Context) {
        val current = songSendStates[songId]
        songSendStates[songId] = (current ?: SongSendState(songId = songId)).copy(
            status = SongSendStatus.PENDING,
            progress = 0f,
            error = null,
            attempt = 0,
            sentAt = 0L,
            bytesSent = 0L
        )
        // Quitar de historial global para permitir reenvío
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_SENT_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(songId)
        prefs.edit().putStringSet(KEY_SENT_IDS, set).apply()
        saveSongStates(context)
        updateCounters()
    }

    fun getSongState(songId: String): SongSendState {
        return songSendStates[songId] ?: SongSendState(songId = songId)
    }

    fun isSongAlreadySent(context: Context, songId: String): Boolean {
        return getSentSongIds(context).contains(songId)
    }

    // ============ ENVÍO PARALELO CON COLA ============

    /**
     * Envía múltiples canciones en paralelo (config.maxParallelUploads a la vez)
     * con cola, reintentos, progreso por canción y cancelación real.
     */
    suspend fun uploadSongsWithTracking(
        targetHost: String,
        songs: List<Song>,
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
        config: UploadConfig = defaultConfig,
        scope: CoroutineScope,
    ): List<SongSendResult> {
        val cleanHost = targetHost.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/")

        shouldCancel.set(false)
        cachedWorkingBaseUrl = null
        isUploading.value = true
        statusMessage.value = "Preparando..."

        // Usar un CoroutineScope hijo ligado al scope del llamador para structured concurrency correcta
        val jobScope = CoroutineScope(scope.coroutineContext + Job())
        uploadJob = jobScope.coroutineContext[Job]

        val results = mutableMapOf<String, SongSendResult>()
        val semaphore = Semaphore(config.maxParallelUploads)
        val sentIdsInPrefs = getSentSongIds(context)
        totalCount.value = songs.size
        updateCounters()

        // 1. Consultar VLC por GET para auto-detectar qué canciones ya están en el iPhone
        statusMessage.value = "Consultando canciones en iPhone (VLC)..."
        val existingVlcFiles = try {
            fetchExistingVlcFiles(cleanHost)
        } catch (_: Exception) {
            emptySet()
        }

        var vlcDetected = 0
        if (existingVlcFiles.isNotEmpty()) {
            for (song in songs) {
                val songId = song.song.id
                val title = song.song.title.trim().lowercase()
                val artists = song.artists.joinToString(", ") { it.name }.trim().lowercase()
                val rawFilename = if (artists.isNotBlank() && artists != "desconocido") "$artists - $title" else title
                val sanitized = LocalMusicServer.sanitizeFilename(rawFilename).lowercase()

                val existsInVlc = existingVlcFiles.any { f ->
                    f == "$sanitized.aac" || f == "$title.aac" || (title.length > 4 && f.contains(title))
                }

                if (existsInVlc) {
                    markSongSent(context, songId)
                    vlcDetected++
                }
            }
            updateCounters()
        }

        // Se envían todas las canciones seleccionadas que no estén marcadas explícitamente como SENT
        val pendingSongs = songs.filter { song ->
            val songId = song.song.id
            val currentState = songSendStates[songId]
            val isExplicitlySent = currentState?.status == SongSendStatus.SENT
            !isExplicitlySent
        }.toList()

        if (pendingSongs.isEmpty()) {
            statusMessage.value = if (vlcDetected > 0) {
                "✅ ¡Todas las $vlcDetected canciones seleccionadas ya están en VLC!"
            } else {
                "✅ Las canciones seleccionadas ya estaban enviadas."
            }
            isUploading.value = false
            return songs.map { SongSendResult(it.song.id, true, finalStatus = SongSendStatus.SENT) }
        }

        statusMessage.value = if (vlcDetected > 0) {
            "✓ $vlcDetected ya en VLC. Enviando ${pendingSongs.size} restantes..."
        } else {
            "Enviando ${pendingSongs.size} de ${songs.size}..."
        }

        // Canal para resultados
        val resultChannel = Channel<SongSendResult>(pendingSongs.size)

        // Lanzar workers usando structured concurrency correcta
        val workerJob = jobScope.launch {
            pendingSongs.forEach { song ->
                if (shouldCancel.get()) return@forEach
                jobScope.launch {
                    semaphore.acquire()
                    try {
                        if (shouldCancel.get()) return@launch
                        val result = sendSongWithRetry(
                            cleanHost = cleanHost,
                            song = song,
                            context = context,
                            downloadCache = downloadCache,
                            playerCache = playerCache,
                            database = database,
                            config = config,
                            resultChannel = resultChannel,
                        )
                        resultChannel.send(result)
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }

        // Recolectar resultados conforme llegan
        val collectorJob = jobScope.launch {
            repeat(pendingSongs.size) {
                if (shouldCancel.get()) return@repeat
                val result = resultChannel.receive()
                results[result.songId] = result
                // Contar las canciones que realmente se subieron con éxito en esta tanda
                uploadedCount.value = results.count { it.value.success }
                uploadProgress.value = uploadedCount.value.toFloat() / totalCount.value.coerceAtLeast(1)
                updateCounters()

                val state = songSendStates[result.songId]
                currentSongName.value = state?.title ?: result.songId
            }
        }

        // Esperar ambos
        try {
            workerJob.join()
            collectorJob.join()
        } catch (e: CancellationException) {
            // Cancelado limpio
        } finally {
            resultChannel.close()
            isUploading.value = false
            currentSong.value = null
            currentSongBytes.value = null
            currentSongProgress.floatValue = 0f
            currentStage.value = ""
        }

        // Mensaje final
        val successCount = results.values.count { it.success }
        val failedCount = results.values.count { !it.success }
        val skippedCount = results.values.count { it.finalStatus == SongSendStatus.SKIPPED }

        statusMessage.value = when {
            shouldCancel.get() -> "⏹ Cancelado: $successCount OK, $failedCount fallaron, $skippedCount saltadas"
            failedCount == 0 -> "✅ ¡Completado! $successCount canción(es) enviadas."
            else -> "⚠️ Parcial: $successCount OK, $failedCount fallaron. Ver detalles."
        }

        return songs.map { results[it.song.id] ?: SongSendResult(it.song.id, false, "No procesada", SongSendStatus.SKIPPED) }
    }

    // ============ ENVÍO INDIVIDUAL CON PROGRESO REAL ============

    private suspend fun sendSongWithRetry(
        cleanHost: String,
        song: Song,
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
        config: UploadConfig,
        resultChannel: Channel<SongSendResult>,
    ): SongSendResult {
        val songId = song.song.id
        val title = song.song.title.ifBlank { songId }

        currentSong.value = song
        currentSongProgress.floatValue = 0f
        currentSongBytes.value = null
        currentStage.value = "Preparando audio..."

        // Estado inicial - marcar como QUEUED en memoria y persistente
        var currentState = songSendStates[songId] ?: SongSendState(songId = songId, title = title)
        currentState = currentState.copy(
            status = SongSendStatus.QUEUED,
            maxAttempts = config.maxAttempts,
            attempt = 0,
            progress = 0f,
        )
        songSendStates[songId] = currentState
        // Guardar estado una sola vez al inicio (no en cada retry para evitar spam)
        saveSongStates(context)
        updateCounters()

        try {
            // ---- PASO 1: DESCARGA COMPLETA DESDE YOUTUBE ----
            currentStage.value = "Descargando stream completo desde YouTube..."
            currentSongProgress.floatValue = 0.05f
            currentState = currentState.copy(status = SongSendStatus.TRANSCODING, progress = 0.05f)
            songSendStates[songId] = currentState

            val fileResult = withContext(Dispatchers.IO) {
                downloadCompleteSongFromYouTube(
                    songId = songId,
                    context = context,
                    database = database,
                )
            }

            if (fileResult == null) {
                return SongSendResult(songId, false, "No se pudo descargar archivo completo desde YouTube", SongSendStatus.FAILED).also { finish(songId, it, context, song) }
            }

            val (filename, file) = fileResult
            val fileSize = file.length()
            currentSongBytes.value = Pair(0L, fileSize)
            currentSongProgress.floatValue = 0.15f
            currentState = currentState.copy(fileSize = fileSize, progress = 0.15f)
            songSendStates[songId] = currentState

            // ---- PASO 2: SUBIDA CON REINTENTOS ----
            var attempt = 0
            var lastError: String? = null

            while (attempt < config.maxAttempts && !shouldCancel.get()) {
                attempt++
                currentState = currentState.copy(
                    attempt = attempt,
                    status = SongSendStatus.UPLOADING,
                    progress = 0.15f + (0.8f * (attempt - 1) / config.maxAttempts),
                    lastAttemptAt = System.currentTimeMillis(),
                )
                songSendStates[songId] = currentState

                // Delay exponencial antes de reintento (no antes del primero)
                if (attempt > 1) {
                    val delayMs = Math.min(
                        (config.initialDelayMs.toDouble() * Math.pow(config.backoffFactor.toDouble(), (attempt - 1).toDouble())).toLong(),
                        config.maxDelayMs
                    )
                    delay(delayMs)
                    if (shouldCancel.get()) break
                }

                try {
                    val uploadResult = uploadFileWithProgress(
                        host = cleanHost,
                        filename = filename,
                        file = file,
                        fileSize = fileSize,
                        songId = songId,
                        currentState = currentState,
                        context = context,
                        config = config,
                    )

                    if (uploadResult.success) {
                        return SongSendResult(songId, true, finalStatus = SongSendStatus.SENT).also { finish(songId, it, context, song) }
                    } else {
                        lastError = uploadResult.error
                        Timber.tag(TAG).w("Intento $attempt falló para $title: $lastError")
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Error: ${e::class.simpleName}"
                    Timber.tag(TAG).e(e, "Excepción en intento $attempt para $title")
                }
            }

            // Todos los intentos fallaron o cancelado
            val finalStatus = if (shouldCancel.get()) SongSendStatus.SKIPPED else SongSendStatus.FAILED
            return SongSendResult(
                songId = songId,
                success = false,
                error = lastError ?: (if (shouldCancel.get()) "Cancelado" else "Falló tras ${config.maxAttempts} intentos"),
                finalStatus = finalStatus
            ).also { finish(songId, it, context, song) }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error crítico para $title")
            return SongSendResult(songId, false, e.message ?: "Error crítico", SongSendStatus.FAILED).also { finish(songId, it, context, song) }
        }
    }

    /**
     * Sube archivo con tracking de bytes enviados para progreso real
     */
    private suspend fun uploadFileWithProgress(
        host: String,
        filename: String,
        file: java.io.File,
        fileSize: Long,
        songId: String,
        currentState: SongSendState,
        context: Context,
        config: UploadConfig,
    ): SongSendResult {
        return withContext(Dispatchers.IO) {
            val cleanHost = host.removePrefix("http://").removePrefix("https://").removePrefix("HTTP://").removePrefix("HTTPS://").removeSuffix("/")
            val hostWithoutPort = cleanHost.substringBefore(":")

            // Construir lista inteligente de candidatos a probar
            val candidates = mutableListOf<String>()
            cachedWorkingBaseUrl?.let { candidates.add(it) }

            if (cleanHost.contains(":")) {
                // Si el usuario incluyó puerto (ej. :8080 de Steve), probar ESE puerto primero
                candidates.add("http://$cleanHost/upload")
                candidates.add("http://$cleanHost/upload.json")
                // NO probar sin puerto ni HTTPS si el usuario especificó uno explícitamente
            } else {
                // Sin puerto explícito: VLC en puerto 80 primero, luego Steve en 8080
                candidates.add("http://$cleanHost/upload.json")
                candidates.add("http://$cleanHost/upload")
                candidates.add("http://$cleanHost:8080/upload")
                candidates.add("http://$cleanHost:8080/upload.json")
                // NO probar HTTPS (puerto 443) - Steve y VLC usan HTTP
            }

            currentStage.value = "Enviando al iPhone..."

            val fileBytes = file.readBytes()
            val totalBytes = fileBytes.size.toLong()
            currentSongBytes.value = Pair(0L, totalBytes)

            val fileBody = object : RequestBody() {
                override fun contentType() = "audio/aac".toMediaType()
                override fun contentLength() = totalBytes
                override fun writeTo(sink: BufferedSink) {
                    var offset = 0
                    val chunkSize = 64 * 1024
                    while (offset < fileBytes.size) {
                        val toWrite = minOf(chunkSize, fileBytes.size - offset)
                        sink.write(fileBytes, offset, toWrite)
                        offset += toWrite
                        sink.flush()
                        val ratio = (offset.toFloat() / totalBytes).coerceIn(0f, 1f)
                        currentSongProgress.floatValue = ratio
                        currentSongBytes.value = Pair(offset.toLong(), totalBytes)

                        val s = songSendStates[songId]
                        if (s != null) {
                            songSendStates[songId] = s.copy(
                                progress = 0.15f + (0.85f * ratio),
                                bytesSent = offset.toLong()
                            )
                        }
                    }
                }
            }

            var lastEx: Exception? = null
            var response: okhttp3.Response? = null
            var workingUrl: String? = null

            for (targetUrl in candidates.distinct()) {
                try {
                    val partName = if (targetUrl.contains("upload.json")) "files[]" else "file"
                    val body = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(partName, filename, fileBody)
                        .build()

                    val req = Request.Builder().url(targetUrl).post(body).build()
                    val res = httpClient.newCall(req).execute()
                    if (res.isSuccessful) {
                        response = res
                        workingUrl = targetUrl
                        break
                    } else if (res.code != 404) {
                        response = res
                        workingUrl = targetUrl
                        break
                    } else {
                        res.close()
                    }
                } catch (e: Exception) {
                    lastEx = e
                }
            }

            if (response == null) {
                throw lastEx ?: Exception("No se pudo conectar con el iPhone en $host")
            }

            if (workingUrl != null) {
                cachedWorkingBaseUrl = workingUrl
            }

            if (response.isSuccessful) {
                response.body.close()
                currentSongProgress.floatValue = 1f
                currentStage.value = "¡Enviada exitosamente!"
                SongSendResult(songId, true, finalStatus = SongSendStatus.SENT)
            } else {
                val errBody = response.body.string()
                response.body.close()
                SongSendResult(songId, false, "HTTP ${response.code}: ${response.message} $errBody", SongSendStatus.FAILED)
            }
        }
    }

    private fun finish(songId: String, result: SongSendResult, context: Context, song: Song? = null) {
        val finalState = songSendStates[songId] ?: SongSendState(songId = songId)
        songSendStates[songId] = finalState.copy(
            status = result.finalStatus,
            progress = if (result.success) 1f else finalState.progress,
            error = result.error,
            sentAt = if (result.success) System.currentTimeMillis() else 0,
        )
        if (result.success) {
            if (song != null) {
                lastSentSong.value = song
            }
            // Marcar como enviado en historial persistente para evitar re-envios duplicados
            markSongSent(context, songId)
        } else {
            // Para canciones fallidas, guardar estado pero NO agregar al historial de enviados
            // Así la canción podrá reintentarse la próxima vez que el usuario haga sync
            saveSongStates(context)
        }
        updateCounters()
    }

    /**
     * Descarga la canción COMPLETA directamente desde YouTube (stream de audio Opus/WebM)
     * y la transcodifica a AAC. Ignora caché local para asegurar archivo completo.
     */
    private suspend fun downloadCompleteSongFromYouTube(
        songId: String,
        context: Context,
        database: MusicDatabase,
    ): Pair<String, File>? {
        val song = try { database.getSongByIdBlocking(songId) } catch (_: Exception) { null }
        val title = song?.song?.title ?: songId
        val artists = song?.artists?.joinToString(", ") { it.name }.orEmpty()
        val album = song?.album?.title.orEmpty()
        val rawFilename = if (artists.isNotBlank() && artists != "Desconocido") "$artists - $title" else title
        val filename = "${LocalMusicServer.sanitizeFilename(rawFilename)}.aac"

        val syncCacheDir = File(context.cacheDir, "sync_aac").apply { mkdirs() }
        val cachedFile = File(syncCacheDir, "$songId.aac")

        // Si ya existe y está completo, usarlo
        if (cachedFile.exists() && cachedFile.length() > 100_000) {
            return Pair(filename, cachedFile)
        }

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

        if (playbackData == null) {
            Timber.tag(TAG).e("No se pudo obtener stream de YouTube para $songId")
            return null
        }

        val reqBuilder = Request.Builder().url(playbackData.streamUrl).get()
        playbackData.streamHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()

        val tempSource = File.createTempFile("src_$songId", ".webm", context.cacheDir)
        try {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).e("Error descargando stream YouTube: ${response.code} - ${response.message}")
                    return null
                }
                val body = response.body ?: return@use null
                val contentLength = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = tempSource.outputStream()
                val buffer = ByteArray(64 * 1024)
                var totalRead = 0L
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    // Actualizar progreso cada ~1MB
                    if (totalRead % (1024 * 1024) < bytesRead) {
                        val progress = if (contentLength > 0) (totalRead.toFloat() / contentLength * 0.8f + 0.05f) else 0f
                        currentSongProgress.floatValue = progress.coerceIn(0.05f, 0.85f)
                        currentStage.value = "Descargando: ${formatBytes(totalRead)}${if (contentLength > 0) " / ${formatBytes(contentLength)}" else ""}"
                    }
                }
                outputStream.flush()
                inputStream.close()
                outputStream.close()
            }

            if (tempSource.length() < 100_000) {
                Timber.tag(TAG).w("Archivo descargado muy pequeño (${tempSource.length()} bytes), posible error")
                tempSource.delete()
                return null
            }

            // Transcodificar a AAC
            val coverBytes = songEntity?.thumbnailUrl?.let { url ->
                if (url.isNotBlank()) {
                    try {
                        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                            if (res.isSuccessful) res.body?.bytes() else null
                        }
                    } catch (_: Exception) { null }
                } else null
            }

            val success = AudioTranscoder.transcodeToAac(
                sourceFile = tempSource,
                destAacFile = cachedFile,
                title = title,
                artist = artists,
                album = album,
                coverBytes = coverBytes,
            )

            if (!success || !cachedFile.exists() || cachedFile.length() < 100_000) {
                Timber.tag(TAG).e("Falló transcodificación para $songId")
                cachedFile.delete()
                return null
            }

            return Pair(filename, cachedFile)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error descargando/transcodificando $songId desde YouTube")
            cachedFile.delete()
            return null
        } finally {
            tempSource.delete()
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    // ============ UTILIDADES SESIÓN ============

    fun getSentSongIdsInSession(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SESSION_SENT, emptySet()) ?: emptySet()
    }

    fun markSongSentInSession(context: Context, songId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_SESSION_SENT, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(songId)
        prefs.edit().putStringSet(KEY_SESSION_SENT, set).apply()
    }

    fun clearSentHistoryInSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SESSION_SENT).apply()
    }
}