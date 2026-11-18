/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import timber.log.Timber
import java.io.File

object AudioTranscoder {
    private const val TAG = "AudioTranscoder"
    private const val TIMEOUT_US = 0L

    /**
     * Transcodes any audio file supported by Android (Opus, WebM, FLAC, AAC, etc.) to standard MPEG-4 Audio (.m4a).
     * Output uses AAC-LC 192kbps inside an ISO MP4 container.
     *
     * IMPORTANT: Dynamic sample rate & channel negotiation is enforced.
     * When decoding Opus (standard YouTube format), Android decodes to 48,000 Hz PCM.
     * The encoder is dynamically configured with the exact sample rate and channel count
     * produced by the decoder, eliminating audio slowdowns and pitch distortions.
     */
    fun transcodeToM4a(
        sourceFile: File,
        destM4aFile: File,
    ): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerTrackIndex = -1

        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                Timber.tag(TAG).e("No audio track found in source file: ${sourceFile.name}")
                return false
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()

            // Previsión de sample rate: Opus siempre decodifica a 48000 Hz en Android
            val isOpus = mime.contains("opus", ignoreCase = true)
            var targetSampleRate = if (isOpus) 48000 else if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            var targetChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            // Iniciar Decodificador
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Helper para instanciar e iniciar el Encoder AAC con parámetros PCM exactos
            fun startEncoder(sampleRate: Int, channels: Int): MediaCodec {
                Timber.tag(TAG).i("Iniciando AAC encoder: $sampleRate Hz, $channels canales para ${sourceFile.name}")
                val outputFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels)
                outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)

                val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
                enc.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                return enc
            }

            destM4aFile.parentFile?.mkdirs()
            if (destM4aFile.exists()) destM4aFile.delete()

            muxer = MediaMuxer(destM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val decoderInputBufferInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()

            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var encoderEosQueued = false
            var pendingDecOutputIndex = -1
            var lastProgressTime = System.currentTimeMillis()
            val stallTimeoutMs = 25_000L

            while (!encoderDone) {
                var madeProgress = false

                if (System.currentTimeMillis() - lastProgressTime > stallTimeoutMs) {
                    Timber.tag(TAG).w("Transcoding estancado sin progreso para ${sourceFile.name}, abortando.")
                    break
                }

                // 1. Extractor -> Decoder
                if (!extractorDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIndex)
                        if (inputBuf != null) {
                            inputBuf.clear()
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                            madeProgress = true
                        }
                    }
                }

                // 2. Decoder -> Encoder (Con detección dinámica de formato PCM exacto)
                if (!decoderDone) {
                    if (pendingDecOutputIndex < 0) {
                        val decIndex = decoder.dequeueOutputBuffer(decoderInputBufferInfo, TIMEOUT_US)
                        if (decIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val decFormat = decoder.outputFormat
                            if (decFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                targetSampleRate = decFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (decFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                targetChannels = decFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            Timber.tag(TAG).i("Decoder reportó cambio de formato PCM: $targetSampleRate Hz, $targetChannels canales")
                            if (encoder == null) {
                                encoder = startEncoder(targetSampleRate, targetChannels)
                            }
                            madeProgress = true
                        } else if (decIndex >= 0) {
                            pendingDecOutputIndex = decIndex
                        }
                    }

                    if (pendingDecOutputIndex >= 0) {
                        // Si el decodificador entregó un buffer antes del format changed, asegurar encoder iniciado
                        if (encoder == null) {
                            val decFormat = try { decoder.outputFormat } catch (_: Exception) { inputFormat }
                            if (decFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                targetSampleRate = decFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (decFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                targetChannels = decFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            encoder = startEncoder(targetSampleRate, targetChannels)
                        }

                        val activeEncoder = encoder!!
                        var consumed = false

                        if (decoderInputBufferInfo.size > 0) {
                            val encInputIndex = activeEncoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInputIndex >= 0) {
                                val encInputBuf = activeEncoder.getInputBuffer(encInputIndex)
                                val pcmBuf = decoder.getOutputBuffer(pendingDecOutputIndex)
                                if (encInputBuf != null && pcmBuf != null) {
                                    encInputBuf.clear()
                                    pcmBuf.position(decoderInputBufferInfo.offset)
                                    pcmBuf.limit(decoderInputBufferInfo.offset + decoderInputBufferInfo.size)
                                    encInputBuf.put(pcmBuf)
                                    activeEncoder.queueInputBuffer(
                                        encInputIndex,
                                        0,
                                        decoderInputBufferInfo.size,
                                        decoderInputBufferInfo.presentationTimeUs,
                                        0,
                                    )
                                }
                                consumed = true
                                madeProgress = true
                            }
                        } else {
                            consumed = true
                        }

                        if (consumed) {
                            if ((decoderInputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decoderDone = true
                            }
                            decoder.releaseOutputBuffer(pendingDecOutputIndex, false)
                            pendingDecOutputIndex = -1
                        }
                    }
                }

                // 3. Encolar EOS al encoder una vez que el decodificador haya terminado
                if (decoderDone && !encoderEosQueued && encoder != null) {
                    val encInputIndex = encoder!!.dequeueInputBuffer(TIMEOUT_US)
                    if (encInputIndex >= 0) {
                        encoder!!.queueInputBuffer(encInputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderEosQueued = true
                        madeProgress = true
                    }
                }

                // 4. Encoder -> Write to MediaMuxer (M4A)
                if (encoder != null) {
                    val encOutputIndex = encoder!!.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_US)
                    if (encOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            val newFormat = encoder!!.outputFormat
                            muxerTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                            madeProgress = true
                        }
                    } else if (encOutputIndex >= 0) {
                        val encodedData = encoder!!.getOutputBuffer(encOutputIndex)
                        if (encodedData != null && encoderBufferInfo.size > 0 && muxerStarted) {
                            if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                encodedData.position(encoderBufferInfo.offset)
                                encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                                muxer.writeSampleData(muxerTrackIndex, encodedData, encoderBufferInfo)
                            }
                        }

                        if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                        }
                        encoder!!.releaseOutputBuffer(encOutputIndex, false)
                        madeProgress = true
                    }
                }

                if (madeProgress) {
                    lastProgressTime = System.currentTimeMillis()
                } else {
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {}
                }
            }

            if (!encoderDone || !muxerStarted) {
                Timber.tag(TAG).e("Transcode a M4A inconcluso para ${sourceFile.name}")
                destM4aFile.delete()
                return false
            }
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error transcoding to M4A: ${sourceFile.name}")
            destM4aFile.delete()
            return false
        } finally {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * Compatibilidad hacia atrás con llamadas previas a transcodeToAac
     */
    fun transcodeToAac(
        sourceFile: File,
        destAacFile: File,
        title: String = "",
        artist: String = "",
        album: String = "",
        coverBytes: ByteArray? = null,
    ): Boolean {
        return transcodeToM4a(sourceFile, destAacFile)
    }
}
