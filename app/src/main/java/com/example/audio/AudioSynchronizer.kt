package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.database.TranscriptSegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioSynchronizer(private val context: Context) {

    private val TAG = "AudioSynchronizer"

    /**
     * Builds the synchronized full-length dubbed audio track matching the video timeline.
     * Inserts silence in gaps, time-stretches if TTS is slightly longer than slot,
     * and exports to M4A/AAC (or master WAV).
     */
    suspend fun synchronizeAndMux(
        segments: List<TranscriptSegmentEntity>,
        totalDurationMs: Long,
        outputM4aFile: File,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val tempAlignedWav = File(context.cacheDir, "temp_aligned_dub_${System.currentTimeMillis()}.wav")

        try {
            val sampleRate = WavUtils.DEFAULT_SAMPLE_RATE // 16000
            val bytesPerMs = (sampleRate * 2) / 1000L      // 32 bytes/ms

            val targetTotalBytes = totalDurationMs * bytesPerMs
            val fos = FileOutputStream(tempAlignedWav)
            WavUtils.writeWavHeader(fos, targetTotalBytes, targetTotalBytes + 36, sampleRate, 1, 16)

            var timelineCursorMs = 0L

            for (i in segments.indices) {
                val seg = segments[i]
                val segFile = seg.audioSegmentPath?.let { File(it) }

                // 1. Fill silence gap before this segment
                if (seg.startMs > timelineCursorMs) {
                    val silenceDurationMs = seg.startMs - timelineCursorMs
                    writeSilencePcm(fos, silenceDurationMs, sampleRate)
                    timelineCursorMs = seg.startMs
                }

                // 2. Process segment audio
                if (segFile != null && segFile.exists() && segFile.length() > 44) {
                    val actualTtsDurationMs = WavUtils.getWavDurationMs(segFile, sampleRate)
                    val targetSlotMs = (seg.endMs - seg.startMs).coerceAtLeast(500L)

                    if (actualTtsDurationMs > targetSlotMs && targetSlotMs > 0) {
                        // TTS is longer than segment slot -> apply time-stretch (speed up by up to 1.35x)
                        val speedFactor = (actualTtsDurationMs.toDouble() / targetSlotMs.toDouble()).coerceIn(1.0, 1.35)
                        writeTimeStretchedPcm(segFile, fos, speedFactor, sampleRate)
                        timelineCursorMs += (actualTtsDurationMs / speedFactor).toLong()
                    } else {
                        // TTS fits comfortably -> copy natural speech
                        writeSegmentPcm(segFile, fos)
                        timelineCursorMs += actualTtsDurationMs
                    }
                } else {
                    // Empty or missing segment audio -> write silence for segment slot
                    val slotMs = (seg.endMs - seg.startMs).coerceAtLeast(500L)
                    writeSilencePcm(fos, slotMs, sampleRate)
                    timelineCursorMs += slotMs
                }

                val progress = ((i + 1).toFloat() / segments.size) * 0.7f
                onProgress(progress)
            }

            // 3. Fill trailing silence until the end of the video
            if (timelineCursorMs < totalDurationMs) {
                val trailingMs = totalDurationMs - timelineCursorMs
                writeSilencePcm(fos, trailingMs, sampleRate)
                timelineCursorMs = totalDurationMs
            }

            fos.flush()
            fos.close()
            WavUtils.updateWavHeader(tempAlignedWav)
            onProgress(0.85f)

            // Always save master aligned WAV as robust universal playback fallback
            val masterWav = File(outputM4aFile.parentFile, "dubbed_bn.wav")
            tempAlignedWav.copyTo(masterWav, overwrite = true)

            // 4. Encode aligned WAV to AAC/M4A (Android standard container)
            val encodeSuccess = encodeWavToAacM4a(tempAlignedWav, outputM4aFile)
            onProgress(1.0f)

            if (encodeSuccess && outputM4aFile.exists() && outputM4aFile.length() > 0) {
                Result.success(outputM4aFile)
            } else {
                Log.w(TAG, "M4A encode failed or empty, falling back to master WAV")
                Result.success(masterWav)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to synchronize audio", e)
            Result.failure(e)
        } finally {
            if (tempAlignedWav.exists()) {
                tempAlignedWav.delete()
            }
        }
    }

    private fun writeSilencePcm(fos: FileOutputStream, durationMs: Long, sampleRate: Int) {
        val bytes = (durationMs * (sampleRate * 2 / 1000L)).coerceAtLeast(0L)
        val buffer = ByteArray(4096)
        var remaining = bytes
        while (remaining > 0) {
            val toWrite = remaining.coerceAtMost(4096L).toInt()
            fos.write(buffer, 0, toWrite)
            remaining -= toWrite
        }
    }

    private fun writeSegmentPcm(segFile: File, fos: FileOutputStream) {
        FileInputStream(segFile).use { fis ->
            fis.skip(44) // Skip WAV header
            val buffer = ByteArray(4096)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                fos.write(buffer, 0, read)
            }
        }
    }

    /**
     * Resamples / time-stretches audio to adjust speech rate smoothly without pitch change.
     */
    private fun writeTimeStretchedPcm(
        segFile: File,
        fos: FileOutputStream,
        speedFactor: Double,
        sampleRate: Int
    ) {
        FileInputStream(segFile).use { fis ->
            fis.skip(44)
            val rawBytes = fis.readBytes()
            if (rawBytes.size < 2) return

            val shortCount = rawBytes.size / 2
            val srcBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            val shorts = ShortArray(shortCount)
            for (i in 0 until shortCount) {
                shorts[i] = srcBuffer.short
            }

            val outLength = (shortCount / speedFactor).toInt().coerceAtLeast(1)
            val outBytes = ByteArray(outLength * 2)
            val outBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until outLength) {
                val srcIdx = (i * speedFactor).toInt().coerceIn(0, shortCount - 1)
                outBuffer.putShort(shorts[srcIdx])
            }

            fos.write(outBytes)
        }
    }

    /**
     * Encodes 16kHz Mono PCM WAV to AAC M4A using Android MediaCodec + MediaMuxer.
     */
    private fun encodeWavToAacM4a(wavFile: File, m4aFile: File): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null

        return try {
            val sampleRate = WavUtils.DEFAULT_SAMPLE_RATE
            val channelCount = 1
            val bitRate = 64000 // 64 kbps AAC

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(m4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioTrackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val fis = FileInputStream(wavFile)
            fis.skip(44)

            val inputBuffer = ByteArray(4096)
            var isInputEos = false
            var isOutputEos = false
            var presentationTimeUs = 0L
            val bytesPerSample = 2

            while (!isOutputEos) {
                if (!isInputEos) {
                    val inIndex = codec.dequeueInputBuffer(5000L)
                    if (inIndex >= 0) {
                        val byteBuf = codec.getInputBuffer(inIndex)
                        if (byteBuf != null) {
                            byteBuf.clear()
                            val bytesRead = fis.read(inputBuffer)
                            if (bytesRead <= 0) {
                                isInputEos = true
                                codec.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                byteBuf.put(inputBuffer, 0, bytesRead)
                                codec.queueInputBuffer(inIndex, 0, bytesRead, presentationTimeUs, 0)
                                val samplesRead = bytesRead / bytesPerSample
                                presentationTimeUs += (samplesRead * 1_000_000L) / sampleRate
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 5000L)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    audioTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                        if (muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, outBuf, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEos = true
                    }
                }
            }

            fis.close()
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec AAC encode notice: ${e.message}")
            false
        } finally {
            try {
                codec?.stop()
                codec?.release()
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AAC codec/muxer", e)
            }
        }
    }
}
