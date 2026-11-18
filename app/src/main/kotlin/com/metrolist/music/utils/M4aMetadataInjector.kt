/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

object M4aMetadataInjector {
    private const val TAG = "M4aMetadataInjector"

    /**
     * Embeds standard Apple/iTunes metadata tags into an MPEG-4 Audio (.m4a) file,
     * including Title (©nam), Artist (©ART), Album (©alb), and Cover Art (covr).
     * Compatible with iOS Music, Evermusic, VLC, Apple Music, and Android players.
     */
    fun embedMetadata(
        m4aFile: File,
        title: String?,
        artist: String?,
        album: String?,
        coverBytes: ByteArray?,
    ): Boolean {
        if (!m4aFile.exists() || m4aFile.length() < 32) return false

        val udta = buildUdtaAtom(title, artist, album, coverBytes) ?: return false

        return try {
            RandomAccessFile(m4aFile, "rw").use { raf ->
                val fileLength = raf.length()

                // Check if already contains covr atom to prevent duplicate writes
                if (coverBytes != null && coverBytes.isNotEmpty() && fileLength > 100) {
                    val tailLength = minOf(fileLength, 128 * 1024L).toInt()
                    raf.seek(fileLength - tailLength)
                    val tailBytes = ByteArray(tailLength)
                    raf.readFully(tailBytes)
                    val tailStr = String(tailBytes, Charsets.ISO_8859_1)
                    if (tailStr.contains("covr") && tailStr.contains("©nam")) {
                        return true
                    }
                }

                var offset = 0L
                var moovOffset = -1L
                var moovSize = 0L

                while (offset < fileLength - 8) {
                    raf.seek(offset)
                    val size = raf.readInt().toLong() and 0xFFFFFFFFL
                    val typeBytes = ByteArray(4)
                    raf.readFully(typeBytes)
                    val type = String(typeBytes, Charsets.US_ASCII)

                    val actualSize = if (size == 1L) {
                        raf.readLong()
                    } else if (size == 0L) {
                        fileLength - offset
                    } else {
                        size
                    }

                    if (type == "moov") {
                        moovOffset = offset
                        moovSize = actualSize
                        break
                    }
                    if (actualSize <= 0) break
                    offset += actualSize
                }

                if (moovOffset != -1L) {
                    val newMoovSize = moovSize + udta.size
                    if (moovOffset + moovSize == fileLength) {
                        // moov is at the end of the file (standard for MediaMuxer output)
                        raf.seek(moovOffset)
                        raf.writeInt(newMoovSize.toInt())
                        raf.seek(fileLength)
                        raf.write(udta)
                        Timber.tag(TAG).i("Embedded metadata + cover art (${coverBytes?.size ?: 0} B) to ${m4aFile.name}")
                        true
                    } else {
                        // moov is not at the end: read remaining bytes and rewrite
                        raf.seek(moovOffset)
                        raf.writeInt(newMoovSize.toInt())
                        raf.seek(moovOffset + moovSize)
                        val remainingBytes = ByteArray((fileLength - (moovOffset + moovSize)).toInt())
                        raf.readFully(remainingBytes)
                        raf.seek(moovOffset + moovSize)
                        raf.write(udta)
                        raf.write(remainingBytes)
                        Timber.tag(TAG).i("Embedded metadata + cover art (${coverBytes?.size ?: 0} B) to ${m4aFile.name}")
                        true
                    }
                } else {
                    Timber.tag(TAG).w("No moov atom found in ${m4aFile.name}")
                    false
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error embedding metadata to ${m4aFile.name}")
            false
        }
    }

    private fun buildUdtaAtom(
        title: String?,
        artist: String?,
        album: String?,
        coverBytes: ByteArray?,
    ): ByteArray? {
        val ilstItems = ArrayList<ByteArray>()

        if (!title.isNullOrBlank()) {
            ilstItems.add(buildTextAtom("©nam", title))
        }
        if (!artist.isNullOrBlank() && artist != "Desconocido") {
            ilstItems.add(buildTextAtom("©ART", artist))
        }
        if (!album.isNullOrBlank()) {
            ilstItems.add(buildTextAtom("©alb", album))
        }
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val isPng = coverBytes.size > 8 && coverBytes[0] == 0x89.toByte() && coverBytes[1] == 0x50.toByte()
            val flags = if (isPng) 0x0000000E else 0x0000000D
            val dataAtom = buildAtom("data", createDataPayload(flags, coverBytes))
            ilstItems.add(buildAtom("covr", dataAtom))
        }

        if (ilstItems.isEmpty()) return null

        val ilstPayload = ilstItems.reduce { acc, bytes -> acc + bytes }
        val ilstAtom = buildAtom("ilst", ilstPayload)

        // hdlr atom inside meta
        val hdlrPayload = ByteArray(25)
        hdlrPayload[8] = 'm'.code.toByte()
        hdlrPayload[9] = 'd'.code.toByte()
        hdlrPayload[10] = 'i'.code.toByte()
        hdlrPayload[11] = 'r'.code.toByte()
        hdlrPayload[12] = 'a'.code.toByte()
        hdlrPayload[13] = 'p'.code.toByte()
        hdlrPayload[14] = 'p'.code.toByte()
        hdlrPayload[15] = 'l'.code.toByte()
        val hdlrAtom = buildAtom("hdlr", hdlrPayload)

        // meta atom (FullAtom: 4 bytes version/flags = 0)
        val metaPayload = byteArrayOf(0, 0, 0, 0) + hdlrAtom + ilstAtom
        val metaAtom = buildAtom("meta", metaPayload)

        return buildAtom("udta", metaAtom)
    }

    private fun buildTextAtom(type: String, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val dataPayload = createDataPayload(0x00000001, textBytes)
        val dataAtom = buildAtom("data", dataPayload)
        return buildAtom(type, dataAtom)
    }

    private fun createDataPayload(typeIndicator: Int, data: ByteArray): ByteArray {
        val payload = ByteArray(8 + data.size)
        payload[0] = ((typeIndicator ushr 24) and 0xFF).toByte()
        payload[1] = ((typeIndicator ushr 16) and 0xFF).toByte()
        payload[2] = ((typeIndicator ushr 8) and 0xFF).toByte()
        payload[3] = (typeIndicator and 0xFF).toByte()
        // bytes 4..7 son locale = 0
        System.arraycopy(data, 0, payload, 8, data.size)
        return payload
    }

    private fun buildAtom(type: String, payload: ByteArray): ByteArray {
        val length = 8 + payload.size
        val atom = ByteArray(length)
        atom[0] = ((length ushr 24) and 0xFF).toByte()
        atom[1] = ((length ushr 16) and 0xFF).toByte()
        atom[2] = ((length ushr 8) and 0xFF).toByte()
        atom[3] = (length and 0xFF).toByte()
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        atom[4] = typeBytes[0]
        atom[5] = typeBytes[1]
        atom[6] = typeBytes[2]
        atom[7] = typeBytes[3]
        System.arraycopy(payload, 0, atom, 8, payload.size)
        return atom
    }
}
