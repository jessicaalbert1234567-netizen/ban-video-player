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

    /**
     * Gets duration of a 16-bit mono 16kHz WAV file in milliseconds.
     */
    fun getWavDurationMs(wavFile: File, sampleRate: Int = DEFAULT_SAMPLE_RATE): Long {
        if (!wavFile.exists() || wavFile.length() <= 44) return 0L
        val audioBytes = wavFile.length() - 44
        val bytesPerMs = (sampleRate * 2) / 1000.0 // 16-bit = 2 bytes
        return (audioBytes / bytesPerMs).toLong()
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

    private fun bufferSize(remaining: Long): Int = remaining.coerceAtMost(4096).toInt()
}
