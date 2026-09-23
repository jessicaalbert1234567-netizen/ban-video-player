package com.example.audio

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object WavUtils {

    const val DEFAULT_SAMPLE_RATE = 16000
    const val DEFAULT_CHANNELS = 1
    const val DEFAULT_BITS_PER_SAMPLE = 16

    /**
     * Writes a standard 44-byte RIFF WAV header for 16-bit PCM.
     */
    fun writeWavHeader(
        outputStream: FileOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE
    ) {
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val blockAlign = (channels * bitsPerSample / 8)

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte() // WAVE
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte() // 'data' chunk
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        outputStream.write(header, 0, 44)
    }

    /**
     * Updates the header of an existing WAV file once all PCM bytes are written.
     */
    fun updateWavHeader(wavFile: File) {
        val totalAudioLen = (wavFile.length() - 44).coerceAtLeast(0)
        val totalDataLen = totalAudioLen + 36
        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(4)
            raf.write(
                byteArrayOf(
                    (totalDataLen and 0xff).toByte(),
                    ((totalDataLen shr 8) and 0xff).toByte(),
                    ((totalDataLen shr 16) and 0xff).toByte(),
                    ((totalDataLen shr 24) and 0xff).toByte()
                )
            )
            raf.seek(40)
            raf.write(
                byteArrayOf(
                    (totalAudioLen and 0xff).toByte(),
                    ((totalAudioLen shr 8) and 0xff).toByte(),
                    ((totalAudioLen shr 16) and 0xff).toByte(),
                    ((totalAudioLen shr 24) and 0xff).toByte()
                )
            )
        }
    }

    data class WavMetadata(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Long
    )

    /**
     * Accurately parses the RIFF WAV header and finds the exact fmt and data chunks.
     */
    fun readWavMetadata(file: File): WavMetadata? {
        if (!file.exists() || file.length() < 44) return null
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(12)
                if (fis.read(header) < 12) return null
                if (header[0] != 'R'.code.toByte() || header[1] != 'I'.code.toByte() ||
                    header[2] != 'F'.code.toByte() || header[3] != 'F'.code.toByte() ||
                    header[8] != 'W'.code.toByte() || header[9] != 'A'.code.toByte() ||
                    header[10] != 'V'.code.toByte() || header[11] != 'E'.code.toByte()
                ) {
                    return null
                }

                var sampleRate = DEFAULT_SAMPLE_RATE
                var channels = DEFAULT_CHANNELS
                var bitsPerSample = DEFAULT_BITS_PER_SAMPLE
                var dataOffset = 44
                var dataSize = (file.length() - 44).coerceAtLeast(0L)
                var currentOffset = 12

                val chunkHeader = ByteArray(8)
                while (fis.read(chunkHeader) == 8) {
                    currentOffset += 8
                    val chunkId = String(chunkHeader, 0, 4)
                    val bb = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
                    val chunkSize = bb.getInt().toLong() and 0xFFFFFFFFL

                    if (chunkId == "fmt ") {
                        val fmtData = ByteArray(chunkSize.toInt().coerceAtMost(40))
                        val readFmt = fis.read(fmtData)
                        currentOffset += readFmt
                        val skipRemaining = chunkSize - readFmt
                        if (skipRemaining > 0) fis.skip(skipRemaining)
                        currentOffset += skipRemaining.toInt()

                        val fmtBb = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                        if (fmtData.size >= 16) {
                            channels = fmtBb.getShort(2).toInt()
                            sampleRate = fmtBb.getInt(4)
                            bitsPerSample = fmtBb.getShort(14).toInt()
                        }
                    } else if (chunkId == "data") {
                        dataOffset = currentOffset
                        dataSize = chunkSize
                        break
                    } else {
                        fis.skip(chunkSize)
                        currentOffset += chunkSize.toInt()
                    }
                }

                WavMetadata(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets duration of a 16-bit mono 16kHz WAV file in milliseconds.
     */
    fun getWavDurationMs(wavFile: File, sampleRate: Int = DEFAULT_SAMPLE_RATE): Long {
        if (!wavFile.exists() || wavFile.length() <= 44) return 0L
        val meta = readWavMetadata(wavFile)
        val effSampleRate = meta?.sampleRate ?: sampleRate
        val effChannels = meta?.channels ?: 1
        val effBits = meta?.bitsPerSample ?: 16
        val dataBytes = meta?.dataSize ?: (wavFile.length() - 44)
        val bytesPerMs = (effSampleRate * effChannels * (effBits / 8)) / 1000.0
        return if (bytesPerMs > 0) (dataBytes / bytesPerMs).toLong() else 0L
    }

    /**
     * Extracts a chunk of audio from a WAV file without loading the entire file into RAM.
     */
    fun extractWavChunk(
        sourceWav: File,
        chunkFile: File,
        startMs: Long,
        endMs: Long,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ) {
        val bytesPerMs = (sampleRate * 2) / 1000 // 32 bytes per ms for 16kHz 16-bit mono
        val startByteOffset = 44 + (startMs * bytesPerMs)
        val totalBytesToRead = ((endMs - startMs) * bytesPerMs).coerceAtLeast(0)

        FileOutputStream(chunkFile).use { fos ->
            writeWavHeader(fos, totalBytesToRead, totalBytesToRead + 36, sampleRate, 1, 16)
            FileInputStream(sourceWav).use { fis ->
                fis.skip(startByteOffset)
                val buffer = ByteArray(4096)
                var bytesRemaining = totalBytesToRead
                while (bytesRemaining > 0) {
                    val toRead = buffer.size.toLong().coerceAtMost(bytesRemaining).toInt()
                    val read = fis.read(buffer, 0, toRead)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    bytesRemaining -= read
                }
            }
        }
    }

    /**
     * Calculates the Root Mean Square (RMS) energy of a 16-bit PCM chunk to detect voice vs silence.
     */
    fun isSegmentSilent(
        wavFile: File,
        startMs: Long,
        durationMs: Long,
        thresholdRms: Double = 150.0,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): Boolean {
        val bytesPerMs = (sampleRate * 2) / 1000
        val startOffset = 44 + (startMs * bytesPerMs)
        val bytesToRead = (durationMs * bytesPerMs).coerceAtLeast(0)

        var sumSquares = 0.0
        var sampleCount = 0L

        FileInputStream(wavFile).use { fis ->
            fis.skip(startOffset)
            val buffer = ByteArray(2048)
            var remaining = bytesToRead
            while (remaining > 0) {
                val toRead = buffer.size.toLong().coerceAtMost(remaining).toInt()
                val read = fis.read(buffer, 0, toRead)
                if (read == -1) break
                val bb = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                while (bb.remaining() >= 2) {
                    val sample = bb.short.toDouble()
                    sumSquares += sample * sample
                    sampleCount++
                }
                remaining -= read
            }
        }

        if (sampleCount == 0L) return true
        val rms = sqrt(sumSquares / sampleCount)
        return rms < thresholdRms
    }

    /**
     * Generates a silence WAV of specified duration in milliseconds.
     */
    fun createSilenceWav(outputFile: File, durationMs: Long, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        val bytesPerMs = (sampleRate * 2) / 1000
        val totalBytes = durationMs * bytesPerMs
        FileOutputStream(outputFile).use { fos ->
            writeWavHeader(fos, totalBytes, totalBytes + 36, sampleRate, 1, 16)
            val silenceBuffer = ByteArray(4096)
            var remaining = totalBytes
            while (remaining > 0) {
                val toWrite = bufferSize(remaining)
                fos.write(silenceBuffer, 0, toWrite)
                remaining -= toWrite
            }
        }
    }

    /**
     * Concatenates multiple WAV audio files into a single WAV file sequentially.
     */
    fun concatenateWavFiles(inputFiles: List<File>, outputFile: File) {
        val validFiles = inputFiles.filter { it.exists() && it.length() > 44 }
        if (validFiles.isEmpty()) {
            createSilenceWav(outputFile, 300L)
            return
        }
        if (validFiles.size == 1) {
            validFiles[0].copyTo(outputFile, overwrite = true)
            return
        }

        // Read sample rate and channels from first file
        var sampleRate = DEFAULT_SAMPLE_RATE
        var channels = DEFAULT_CHANNELS
        var bitsPerSample = DEFAULT_BITS_PER_SAMPLE
        try {
            FileInputStream(validFiles[0]).use { fis ->
                val header = ByteArray(44)
                val read = fis.read(header)
                if (read >= 44) {
                    val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    channels = bb.getShort(22).toInt()
                    sampleRate = bb.getInt(24)
                    bitsPerSample = bb.getShort(34).toInt()
                }
            }
        } catch (_: Exception) {}

        var totalPcmBytes = 0L
        for (f in validFiles) {
            totalPcmBytes += (f.length() - 44).coerceAtLeast(0L)
        }

        FileOutputStream(outputFile).use { fos ->
            writeWavHeader(fos, totalPcmBytes, totalPcmBytes + 36, sampleRate, channels, bitsPerSample)
            val buffer = ByteArray(4096)
            for (f in validFiles) {
                FileInputStream(f).use { fis ->
                    fis.skip(44)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                    }
                }
            }
        }
        updateWavHeader(outputFile)
    }

    /**
     * Checks if a WAV file exists, has a valid header and contains non-silent audio.
     */
    fun isAudioPlayable(wavFile: File, minDurationMs: Long = 100L): Boolean {
        if (!wavFile.exists() || wavFile.length() <= 44) return false
        val durationMs = getWavDurationMs(wavFile)
        if (durationMs < minDurationMs) return false
        return !isSegmentSilent(wavFile, 0L, durationMs.coerceAtMost(2000L), thresholdRms = 10.0)
    }

    private fun bufferSize(remaining: Long): Int = remaining.coerceAtMost(4096).toInt()
}
