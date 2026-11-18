/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.util.Base64
import android.widget.Toast
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.datasource.cache.Cache
import com.metrolist.music.constants.AutoUploadLikedToDriveKey
import com.metrolist.music.constants.GoogleDriveWebhookUrlKey
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object GoogleDriveUploader {
    private const val TAG = "GoogleDriveUploader"
    private const val PREFS_NAME = "google_drive_sync_prefs"
    private const val KEY_UPLOADED_IDS = "uploaded_song_ids"

    const val DEFAULT_WEBHOOK_URL =
        "https://script.google.com/macros/s/AKfycbzHmTqyBNsSoTXqfm3dDpowNzsS3a0mhf02fcDFoUh6GN8839_W-xo9CfIeOhyc3tXf/exec"

    val isUploading = mutableStateOf(false)
    val uploadProgress = mutableFloatStateOf(0f)
    val currentUploadingSong = mutableStateOf<String?>(null)
    val currentUploadingSongId = mutableStateOf<String?>(null)
    val currentSongStage = mutableStateOf<String>("")
    val uploadedCount = mutableIntStateOf(0)
    val totalToUpload = mutableIntStateOf(0)
    val lastStatusMessage = mutableStateOf<String?>(null)

    // Estados reactivos persistentes de canciones
    val uploadedSongIds = mutableStateOf<Set<String>>(emptySet())
    val failedSongIds = mutableStateOf<Set<String>>(emptySet())
    private var isInitialized = false

    private var batchJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(KEY_UPLOADED_IDS, emptySet()) ?: emptySet()
            uploadedSongIds.value = saved.toSet()
            isInitialized = true
        }
    }

    fun isSongUploaded(context: Context, songId: String): Boolean {
        ensureInitialized(context)
        return uploadedSongIds.value.contains(songId)
    }

    fun markSongUploaded(context: Context, songId: String) {
        ensureInitialized(context)
        val updated = uploadedSongIds.value + songId
        uploadedSongIds.value = updated
        failedSongIds.value = failedSongIds.value - songId

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_UPLOADED_IDS, updated).apply()
    }

    fun markSongsUploaded(context: Context, songIds: Collection<String>) {
        ensureInitialized(context)
        val updated = uploadedSongIds.value + songIds
        uploadedSongIds.value = updated
        failedSongIds.value = failedSongIds.value - songIds.toSet()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_UPLOADED_IDS, updated).apply()
    }

    fun markSongsUnuploaded(context: Context, songIds: Collection<String>) {
        ensureInitialized(context)
        val updated = uploadedSongIds.value - songIds.toSet()
        uploadedSongIds.value = updated

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_UPLOADED_IDS, updated).apply()
    }

    fun markSongFailed(songId: String) {
        failedSongIds.value = failedSongIds.value + songId
    }

    fun clearUploadedHistory(context: Context) {
        uploadedSongIds.value = emptySet()
        failedSongIds.value = emptySet()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_UPLOADED_IDS).apply()
    }

    /**
     * Sube una canción en segundo plano automáticamente al dar "Like".
     */
    fun uploadSongInBackground(
        context: Context,
        songId: String,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
    ) {
        ensureInitialized(context)
        if (isSongUploaded(context, songId)) {
            Timber.tag(TAG).d("Canción $songId ya está en Google Drive. Omitiendo auto-upload.")
            return
        }

        scope.launch {
            val url = context.dataStore.get(GoogleDriveWebhookUrlKey, DEFAULT_WEBHOOK_URL).ifBlank { DEFAULT_WEBHOOK_URL }
            val song = try { database.getSongByIdBlocking(songId) } catch (_: Exception) { null }
            val title = song?.song?.title ?: songId

            try {
                Timber.tag(TAG).i("Auto-uploading liked song $title to Google Drive...")
                val success = uploadSongInternal(url, songId, context, downloadCache, playerCache, database)
                if (success) {
                    markSongUploaded(context, songId)
                    Timber.tag(TAG).i("Successfully uploaded $title to Google Drive")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "☁️ Guardada en Google Drive: $title", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    markSongFailed(songId)
                    Timber.tag(TAG).w("Failed to upload $title to Google Drive")
                }
            } catch (e: Exception) {
                markSongFailed(songId)
                Timber.tag(TAG).e(e, "Error auto-uploading $title to Google Drive")
            }
        }
    }

    /**
     * Sube múltiples canciones por lote con seguimiento de progreso detallado.
     * FILTRA automáticamente canciones que ya están en Google Drive para evitar resubidas innecesarias.
     */
    fun startBatchUpload(
        context: Context,
        songIds: Set<String>,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
        forceReupload: Boolean = false,
    ) {
        if (isUploading.value) return
        ensureInitialized(context)

        // Excluir canciones que ya están subidas salvo que se fuerce explícitamente
        val list = if (forceReupload) {
            songIds.toList()
        } else {
            songIds.filter { !isSongUploaded(context, it) }
        }

        if (list.isEmpty()) {
            Toast.makeText(context, "✅ Las canciones seleccionadas ya están en Google Drive", Toast.LENGTH_SHORT).show()
            return
        }

        // Limpiar de la lista de fallidas las que vamos a procesar
        failedSongIds.value = failedSongIds.value - list.toSet()

        isUploading.value = true
        totalToUpload.intValue = list.size
        uploadedCount.intValue = 0
        uploadProgress.floatValue = 0f
        lastStatusMessage.value = "Iniciando subida a Drive..."

        batchJob = scope.launch {
            val url = context.dataStore.get(GoogleDriveWebhookUrlKey, DEFAULT_WEBHOOK_URL).ifBlank { DEFAULT_WEBHOOK_URL }
            val total = list.size
            val processedCounter = AtomicInteger(0)
            val successCounter = AtomicInteger(0)
            val semaphore = Semaphore(3)
            val activeSongTitles = ConcurrentHashMap<String, String>()

            coroutineScope {
                for (sid in list) {
                    if (!isUploading.value) break

                    launch {
                        semaphore.withPermit {
                            if (!isUploading.value) return@withPermit

                            val song = try { database.getSongByIdBlocking(sid) } catch (_: Exception) { null }
                            val title = song?.song?.title ?: sid
                            activeSongTitles[sid] = title

                            currentUploadingSong.value = activeSongTitles.values.firstOrNull() ?: title
                            currentUploadingSongId.value = sid
                            val inProgress = processedCounter.get()
                            lastStatusMessage.value = "Sincronizando ($inProgress/$total) [3 en paralelo]..."

                            try {
                                val success = uploadSongInternal(url, sid, context, downloadCache, playerCache, database)
                                if (success) {
                                    markSongUploaded(context, sid)
                                    val sc = successCounter.incrementAndGet()
                                    uploadedCount.intValue = sc
                                } else {
                                    markSongFailed(sid)
                                }
                            } catch (e: Exception) {
                                markSongFailed(sid)
                                Timber.tag(TAG).e(e, "Error in batch upload for $sid")
                            } finally {
                                activeSongTitles.remove(sid)
                                val done = processedCounter.incrementAndGet()
                                uploadProgress.floatValue = done.toFloat() / total.toFloat()
                                currentUploadingSong.value = activeSongTitles.values.firstOrNull()
                            }
                        }
                    }
                }
            }

            isUploading.value = false
            currentUploadingSong.value = null
            currentUploadingSongId.value = null
            currentSongStage.value = ""
            val totalSuccess = successCounter.get()
            lastStatusMessage.value = "Completado: $totalSuccess de $total en Google Drive"
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "☁️ $totalSuccess canciones sincronizadas con Google Drive", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun cancelBatchUpload() {
        batchJob?.cancel()
        batchJob = null
        isUploading.value = false
        currentUploadingSong.value = null
        currentUploadingSongId.value = null
        currentSongStage.value = ""
        lastStatusMessage.value = "Subida cancelada"
    }

    private suspend fun uploadSongInternal(
        webhookUrl: String,
        songId: String,
        context: Context,
        downloadCache: Cache,
        playerCache: Cache,
        database: MusicDatabase,
    ): Boolean = withContext(Dispatchers.IO) {
        currentSongStage.value = "Transcodificando a M4A con carátula..."
        val result = LocalMusicServer.getSongFileM4a(songId, context, downloadCache, playerCache, database)
            ?: return@withContext false

        val (filename, m4aFile) = result
        if (!m4aFile.exists() || m4aFile.length() == 0L) return@withContext false

        currentSongStage.value = "Enviando a Google Drive..."
        val fileBytes = m4aFile.readBytes()
        val base64Str = Base64.encodeToString(fileBytes, Base64.NO_WRAP)

        val json = buildString(base64Str.length + filename.length + 64) {
            append("{\"filename\":")
            append(JSONObject.quote(filename))
            append(",\"base64\":\"")
            append(base64Str)
            append("\"}")
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(webhookUrl)
            .post(body)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    return@withContext true
                }
                if (resp.code in 300..399) {
                    val location = resp.header("Location")
                    if (!location.isNullOrBlank()) {
                        val redirectReq = Request.Builder().url(location).get().build()
                        httpClient.newCall(redirectReq).execute().use { redResp ->
                            return@withContext redResp.isSuccessful
                        }
                    }
                    return@withContext true
                }
                return@withContext false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "HTTP POST to Google Drive webhook failed for $filename")
            return@withContext false
        }
    }
}
