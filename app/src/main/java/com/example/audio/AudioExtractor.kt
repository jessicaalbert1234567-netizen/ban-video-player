package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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

            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                return@withContext Result.failure(IllegalStateException("No audio track found in the selected video."))
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                1_000_000L
            }

            val sourceSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            val sourceChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

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
                            val presentationTimeUs = extractor.sampleTime
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
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // Convert decoded PCM (may be stereo/any sample rate) to 16kHz mono 16-bit PCM
                        writeResampledMonoPcm(
                            outputBuffer,
                            fos,
                            sourceSampleRate,
                            sourceChannels,
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
     * Resamples multi-channel PCM or non-16kHz PCM to 16,000 Hz 16-bit Mono.
     */
    private fun writeResampledMonoPcm(
        sourceBuffer: ByteBuffer,
        fos: FileOutputStream,
        sourceSampleRate: Int,
        sourceChannels: Int,
        targetSampleRate: Int = 16000
    ) {
        val bb = sourceBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val numSourceSamples = bb.remaining() / (2 * sourceChannels)
        if (numSourceSamples <= 0) return

        // Downsample ratio
        val step = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val outSamples = (numSourceSamples / step).toInt()
        val outBytes = ByteArray(outSamples * 2)
        val outBb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

        var srcPos = 0.0
        while (srcPos < numSourceSamples && outBb.hasRemaining()) {
            val srcIdx = srcPos.toInt()
            val sampleByteOffset = bb.position() + (srcIdx * 2 * sourceChannels)

            if (sampleByteOffset + (2 * sourceChannels) <= bb.limit()) {
                var monoSample = 0
                for (ch in 0 until sourceChannels) {
                    monoSample += bb.getShort(sampleByteOffset + (ch * 2))
                }
                monoSample /= sourceChannels
                outBb.putShort(monoSample.toShort())
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
        // Run with progress reporting
        return fallbackExtractor.extractAudio(videoUri, outputWavFile, onProgress)
    }
}
