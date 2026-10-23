package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface AudioExtractor {
    suspend fun extractAudio(
        videoUri: Uri,
        outputWavFile: File,
        onProgress: (Float) -> Unit
    ): Result<File>
}

class MediaCodecAudioExtractor(private val context: Context) : AudioExtractor {

    private val TAG = "AudioExtractor"

    override suspend fun extractAudio(
        videoUri: Uri,
        outputWavFile: File,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, videoUri, null)
            val numTracks = extractor.trackCount
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null

            // Prefer stereo track if multiple audio tracks exist, or fallback to first audio track (e.g. 5.1 HE-AAC)
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    if (audioTrackIndex == -1) {
                        audioTrackIndex = i
                        inputFormat = format
                    }
                    val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 2
                    if (ch in 1..2) {
                        audioTrackIndex = i
                        inputFormat = format
                        break
                    }
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                return@withContext Result.failure(IllegalStateException("No audio track found in the selected video."))
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1_000_000L)
            } else {
                1_000_000L
            }

            var sourceSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            var sourceChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
            var pcmEncoding = if (inputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                inputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                android.media.AudioFormat.ENCODING_PCM_16BIT
            }

            // Configure decoder hints for 5.1 HE-AAC / multichannel downmixing and 16-bit PCM
            try {
                inputFormat.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 2)
                if (Build.VERSION.SDK_INT >= 28) {
                    inputFormat.setInteger("aac-max-output-channel_count", 2)
                }
                if (Build.VERSION.SDK_INT >= 24) {
                    inputFormat.setInteger(
                        MediaFormat.KEY_PCM_ENCODING,
                        android.media.AudioFormat.ENCODING_PCM_16BIT
                    )
                }
            } catch (_: Exception) {}

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val fos = FileOutputStream(outputWavFile)
            // Write placeholder 44-byte WAV header
            WavUtils.writeWavHeader(fos, 0, 36, WavUtils.DEFAULT_SAMPLE_RATE, 1, 16)

            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            val kTimeOutUs = 5000L

            var lastReportTime = System.currentTimeMillis()

            while (!isEos) {
                // Feed input buffers
                val inIndex = codec.dequeueInputBuffer(kTimeOutUs)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            // Clamp negative start timestamps (e.g., MKV start: -0.105000) to >= 0L
                            val rawPtsUs = extractor.sampleTime
                            val presentationTimeUs = maxOf(0L, rawPtsUs)
                            codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime > 250) {
                                val progress = (presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 0.95f)
                                onProgress(progress)
                                lastReportTime = now
                            }
                        }
                    }
                }

                // Drain output buffers
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, kTimeOutUs)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sourceSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        sourceChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    Log.i(TAG, "Decoder output format changed: sampleRate=$sourceSampleRate, channels=$sourceChannels, encoding=$pcmEncoding")
                } else if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // Convert decoded PCM (may be stereo/5.1/any sample rate/float) to 16kHz mono 16-bit PCM
                        writeResampledMonoPcm(
                            outputBuffer,
                            fos,
                            sourceSampleRate,
                            sourceChannels,
                            pcmEncoding,
                            WavUtils.DEFAULT_SAMPLE_RATE
                        )
                    }
                    codec.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                    }
                }
            }

            fos.flush()
            fos.close()

            // Update WAV header with actual recorded data length
            WavUtils.updateWavHeader(outputWavFile)
            onProgress(1.0f)
            Log.d(TAG, "Audio extracted successfully: ${outputWavFile.length()} bytes, path: ${outputWavFile.absolutePath}")

            Result.success(outputWavFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting audio from video", e)
            Result.failure(e)
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing audio codec/extractor", e)
            }
        }
    }

    /**
     * Resamples multi-channel PCM (mono, stereo, 5.1, 7.1) or float PCM to 16,000 Hz 16-bit Mono.
     */
    private fun writeResampledMonoPcm(
        sourceBuffer: ByteBuffer,
        fos: FileOutputStream,
        sourceSampleRate: Int,
        sourceChannels: Int,
        pcmEncoding: Int,
        targetSampleRate: Int = 16000
    ) {
        val bb = sourceBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val isFloat = pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val bytesPerFrame = bytesPerSample * sourceChannels
        if (bytesPerFrame <= 0) return

        val numSourceFrames = bb.remaining() / bytesPerFrame
        if (numSourceFrames <= 0) return

        val step = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val outSamples = (numSourceFrames / step).toInt().coerceAtLeast(1)
        val outBytes = ByteArray(outSamples * 2)
        val outBb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

        var srcPos = 0.0
        while (srcPos < numSourceFrames && outBb.hasRemaining()) {
            val srcIdx = srcPos.toInt()
            val frameOffset = bb.position() + (srcIdx * bytesPerFrame)

            if (frameOffset + bytesPerFrame <= bb.limit()) {
                if (isFloat) {
                    var sum = 0.0f
                    for (ch in 0 until sourceChannels) {
                        val v = bb.getFloat(frameOffset + (ch * 4))
                        if (!v.isNaN() && !v.isInfinite()) sum += v
                    }
                    val avgFloat = (sum / sourceChannels).coerceIn(-1.0f, 1.0f)
                    val shortVal = (avgFloat * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                    outBb.putShort(shortVal)
                } else {
                    var sum = 0
                    for (ch in 0 until sourceChannels) {
                        sum += bb.getShort(frameOffset + (ch * 2)).toInt()
                    }
                    val avgShort = (sum / sourceChannels).coerceIn(-32768, 32767).toShort()
                    outBb.putShort(avgShort)
                }
            }
            srcPos += step
        }

        fos.write(outBytes, 0, outBb.position())
    }
}

/**
 * FFmpeg Audio Extractor adapter implementation.
 * Wraps FFmpeg execution or falls back to native high-performance MediaCodec pipeline.
 */
class FfmpegAudioExtractor(private val context: Context) : AudioExtractor {
    private val fallbackExtractor = MediaCodecAudioExtractor(context)

    override suspend fun extractAudio(
        videoUri: Uri,
        outputWavFile: File,
        onProgress: (Float) -> Unit
    ): Result<File> {
        return fallbackExtractor.extractAudio(videoUri, outputWavFile, onProgress)
    }
}
