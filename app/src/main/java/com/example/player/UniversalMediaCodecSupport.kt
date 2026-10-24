package com.example.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo as ExoMediaCodecInfo

/**
 * Universal Stereo Downmix & PCM Normalizer AudioProcessor.
 *
 * Safely converts ANY channel configuration (Mono 1.0, Stereo 2.0, 2.1, 3.0, Quad 4.0, 5.0,
 * 5.1 Surround, 6.1, 7.1 Surround) and ANY PCM sample encoding (16-bit, Float/fltp, 24-bit,
 * 32-bit, 8-bit) into universal 16-bit Stereo PCM (C.ENCODING_PCM_16BIT, 2 channels).
 */
@UnstableApi
class UniversalStereoAudioProcessor : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        val channels = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate

        if (channels <= 0 || sampleRate <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        val isSupportedEncoding = encoding == C.ENCODING_PCM_16BIT ||
                encoding == C.ENCODING_PCM_FLOAT ||
                encoding == C.ENCODING_PCM_24BIT ||
                encoding == C.ENCODING_PCM_32BIT ||
                encoding == C.ENCODING_PCM_8BIT

        if (!isSupportedEncoding) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        // Standard 16-bit stereo passes through directly with zero overhead
        if (channels == 2 && encoding == C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        return AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val channels = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val frameSize = channels * bytesPerSample
        if (frameSize <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val frameCount = remaining / frameSize
        if (frameCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        // Output is always 2 channels * 2 bytes (16-bit stereo) = 4 bytes per frame
        val outBuffer = replaceOutputBuffer(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        val chSamples = FloatArray(channels)

        for (frame in 0 until frameCount) {
            for (ch in 0 until channels) {
                chSamples[ch] = readNormalizedSample(src, encoding)
            }

            val left: Float
            val right: Float

            when (channels) {
                1 -> {
                    left = chSamples[0]
                    right = chSamples[0]
                }
                2 -> {
                    left = chSamples[0]
                    right = chSamples[1]
                }
                3 -> {
                    // 3.0: FL, FR, FC
                    left = chSamples[0] * 0.65f + chSamples[2] * 0.45f
                    right = chSamples[1] * 0.65f + chSamples[2] * 0.45f
                }
                4 -> {
                    // Quad / 4.0: FL, FR, BL, BR
                    left = chSamples[0] * 0.65f + chSamples[2] * 0.45f
                    right = chSamples[1] * 0.65f + chSamples[3] * 0.45f
                }
                5 -> {
                    // 5.0: FL, FR, FC, BL, BR
                    left = chSamples[0] * 0.55f + chSamples[2] * 0.50f + chSamples[3] * 0.35f
                    right = chSamples[1] * 0.55f + chSamples[2] * 0.50f + chSamples[4] * 0.35f
                }
                6 -> {
                    // 5.1 Surround (HE-AAC / AAC / AC3): FL(0), FR(1), FC(2), LFE(3), BL(4), BR(5)
                    // Center channel (FC) carries primary spoken dialogue; preserve it clearly!
                    val fl = chSamples[0]
                    val fr = chSamples[1]
                    val fc = chSamples[2]
                    val lfe = chSamples[3]
                    val bl = chSamples[4]
                    val br = chSamples[5]
                    left = fl * 0.55f + fc * 0.58f + lfe * 0.15f + bl * 0.35f
                    right = fr * 0.55f + fc * 0.58f + lfe * 0.15f + br * 0.35f
                }
                else -> {
                    // 7.1 or higher: FL(0), FR(1), FC(2), LFE(3), BL(4), BR(5), SL(6), SR(7)
                    val fl = chSamples[0]
                    val fr = chSamples[1]
                    val fc = chSamples[2]
                    val lfe = chSamples[3]
                    val bl = chSamples[4]
                    val br = chSamples[5]
                    val sl = if (channels > 6) chSamples[6] else 0f
                    val sr = if (channels > 7) chSamples[7] else 0f
                    left = fl * 0.48f + fc * 0.55f + lfe * 0.12f + bl * 0.28f + sl * 0.28f
                    right = fr * 0.48f + fc * 0.55f + lfe * 0.12f + br * 0.28f + sr * 0.28f
                }
            }

            val leftShort = (left * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            val rightShort = (right * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            outBuffer.putShort(leftShort)
            outBuffer.putShort(rightShort)
        }

        inputBuffer.position(inputBuffer.limit())
        outBuffer.flip()
    }

    private fun readNormalizedSample(src: ByteBuffer, encoding: Int): Float {
        return when (encoding) {
            C.ENCODING_PCM_16BIT -> src.short / 32768.0f
            C.ENCODING_PCM_FLOAT -> {
                val f = src.float
                if (f.isNaN() || f.isInfinite()) 0f else f.coerceIn(-1.0f, 1.0f)
            }
            C.ENCODING_PCM_8BIT -> ((src.get().toInt() and 0xFF) - 128) / 128.0f
            C.ENCODING_PCM_24BIT -> {
                val b0 = src.get().toInt() and 0xFF
                val b1 = src.get().toInt() and 0xFF
                val b2 = src.get().toInt() // signed MSB
                val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                sample24 / 8388608.0f
            }
            C.ENCODING_PCM_32BIT -> src.int / 2147483648.0f
            else -> 0f
        }
    }
}

/**
 * VLC-Grade RenderersFactory for Media3 ExoPlayer:
 * 1. Handles 10-bit HEVC (Main 10, yuv420p10le) and non-16-aligned dimensions (e.g., 1280x536)
 *    by prioritizing Android's 10-bit capable software HEVC decoder (c2.android.hevc.decoder /
 *    OMX.google.hevc.decoder) with seamless fallback to hardware decoders.
 * 2. Requests SDR color transfer output (COLOR_TRANSFER_SDR_VIDEO) on API 31+ for 10-bit SDR HEVC
 *    while preserving MediaCodec PlaceholderSurface and setOutputSurface support.
 * 3. Synchronizes MediaCodecAudioRenderer output channel counts in onOutputFormatChanged so 5.1 HE-AAC
 *    tracks never trigger ChannelMappingAudioProcessor UnhandledAudioFormatException, and downmixes
 *    multichannel/float PCM to 16-bit Stereo via UniversalStereoAudioProcessor.
 */
@UnstableApi
class VlcGradeRenderersFactory(
    context: Context,
    private val preferSoftwareVideoDecoderProvider: () -> Boolean = { true }
) : DefaultRenderersFactory(context) {

    companion object {
        private const val TAG = "VlcGradeRenderers"

        fun createUniversalAudioSink(context: Context): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(UniversalStereoAudioProcessor()))
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(false)
                .build()
        }

        fun isFormat10BitHevc(format: Format): Boolean {
            if (format.sampleMimeType != MimeTypes.VIDEO_H265) return false
            val codecs = format.codecs?.lowercase() ?: ""
            val hasMain10CodecTag = codecs.startsWith("hev1.2") ||
                    codecs.startsWith("hvc1.2") ||
                    codecs.contains(".2.") ||
                    codecs.contains("main10")
            val has10BitColor = (format.colorInfo?.lumaBitdepth ?: -1) >= 10 ||
                    (format.colorInfo?.chromaBitdepth ?: -1) >= 10
            if (hasMain10CodecTag || has10BitColor) return true

            // Also inspect raw HEVC SPS/VPS NAL units in initializationData for Main 10 profile_idc == 2
            return hasHevcMain10NalUnit(format.initializationData)
        }

        fun hasNonMacroblockAlignedDimensions(format: Format): Boolean {
            if (format.sampleMimeType != MimeTypes.VIDEO_H265) return false
            val w = format.width
            val h = format.height
            if (w <= 0 || h <= 0) return false
            if (w == 1920 && h == 1080) return false
            return (w % 16 != 0) || (h % 16 != 0)
        }

        private fun hasHevcMain10NalUnit(initData: List<ByteArray>?): Boolean {
            if (initData.isNullOrEmpty()) return false
            for (buf in initData) {
                var i = 0
                while (i + 7 < buf.size) {
                    // Find 3-byte or 4-byte Annex-B start code: 0x000001 or 0x00000001
                    if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) {
                        val nalStart = when {
                            buf[i + 2].toInt() == 1 -> i + 3
                            i + 3 < buf.size && buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1 -> i + 4
                            else -> -1
                        }
                        if (nalStart != -1 && nalStart + 4 < buf.size) {
                            val nalType = (buf[nalStart].toInt() and 0x7E) ushr 1
                            // SPS (nal_unit_type == 33)
                            if (nalType == 33) {
                                val ptlByte0 = buf[nalStart + 3].toInt() and 0xFF
                                val profileIdc = ptlByte0 and 0x1F
                                val compatByte0 = buf[nalStart + 4].toInt() and 0xFF
                                val main10Compat = (compatByte0 and 0x20) != 0
                                if (profileIdc == 2 || main10Compat) return true
                            }
                            i = nalStart + 2
                            continue
                        }
                    }
                    i++
                }
            }
            return false
        }

        fun isSoftwareDecoder(info: ExoMediaCodecInfo): Boolean {
            val name = info.name.lowercase()
            return info.softwareOnly ||
                    !info.hardwareAccelerated ||
                    name.startsWith("c2.android.") ||
                    name.startsWith("omx.google.") ||
                    name.contains(".sw.") ||
                    name.contains("ffmpeg")
        }

        fun supportsHevcMain10Profile(info: ExoMediaCodecInfo): Boolean {
            return try {
                val levels = info.profileLevels
                levels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return createUniversalAudioSink(context)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val smartVideoCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaultList = mediaCodecSelector.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            ).toMutableList()

            try {
                val rawSystemDecoders = MediaCodecUtil.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
                for (d in rawSystemDecoders) {
                    if (defaultList.none { it.name == d.name }) {
                        defaultList.add(d)
                    }
                }
            } catch (_: Exception) {}

            if (defaultList.size <= 1) return@MediaCodecSelector defaultList

            val preferSw = preferSoftwareVideoDecoderProvider()
            if (mimeType.equals(MimeTypes.VIDEO_H265, ignoreCase = true)) {
                defaultList.sortedBy { info ->
                    if (preferSw) {
                        when {
                            isSoftwareDecoder(info) -> 0
                            supportsHevcMain10Profile(info) -> 1
                            else -> 2
                        }
                    } else {
                        when {
                            !isSoftwareDecoder(info) && supportsHevcMain10Profile(info) -> 0
                            !isSoftwareDecoder(info) -> 1
                            else -> 2
                        }
                    }
                }
            } else {
                defaultList
            }
        }

        val customVideoRenderer = object : MediaCodecVideoRenderer(
            context,
            codecAdapterFactory,
            smartVideoCodecSelector,
            allowedVideoJoiningTimeMs,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
        ) {
            override fun supportsFormat(
                mediaCodecSelector: MediaCodecSelector,
                format: Format
            ): Int {
                val baseSupport = super.supportsFormat(mediaCodecSelector, format)
                val formatSupport = RendererCapabilities.getFormatSupport(baseSupport)
                if (format.sampleMimeType == MimeTypes.VIDEO_H265 && formatSupport != C.FORMAT_HANDLED) {
                    return RendererCapabilities.create(
                        C.FORMAT_HANDLED,
                        RendererCapabilities.ADAPTIVE_SEAMLESS,
                        RendererCapabilities.TUNNELING_NOT_SUPPORTED
                    )
                }
                return baseSupport
            }

            override fun getDecoderInfos(
                selector: MediaCodecSelector,
                format: Format,
                requiresSecureDecoder: Boolean
            ): MutableList<ExoMediaCodecInfo> {
                val infos = super.getDecoderInfos(selector, format, requiresSecureDecoder).toMutableList()
                val mime = format.sampleMimeType ?: MimeTypes.VIDEO_H265

                try {
                    val allFromSelector = selector.getDecoderInfos(mime, requiresSecureDecoder, false)
                    for (candidate in allFromSelector) {
                        if (infos.none { it.name == candidate.name }) {
                            infos.add(candidate)
                        }
                    }
                    val allRaw = MediaCodecUtil.getDecoderInfos(mime, requiresSecureDecoder, false)
                    for (candidate in allRaw) {
                        if (infos.none { it.name == candidate.name }) {
                            infos.add(candidate)
                        }
                    }
                } catch (_: Exception) {}

                val is10BitOrOddSizeHevc = isFormat10BitHevc(format) || hasNonMacroblockAlignedDimensions(format)
                val preferSw = preferSoftwareVideoDecoderProvider()

                if (mime.equals(MimeTypes.VIDEO_H265, ignoreCase = true) || is10BitOrOddSizeHevc) {
                    infos.sortBy { info ->
                        if (preferSw && is10BitOrOddSizeHevc) {
                            when {
                                isSoftwareDecoder(info) -> 0
                                supportsHevcMain10Profile(info) -> 1
                                else -> 2
                            }
                        } else if (preferSw) {
                            when {
                                isSoftwareDecoder(info) -> 0
                                supportsHevcMain10Profile(info) -> 1
                                else -> 2
                            }
                        } else {
                            when {
                                !isSoftwareDecoder(info) && supportsHevcMain10Profile(info) -> 0
                                !isSoftwareDecoder(info) -> 1
                                else -> 2
                            }
                        }
                    }
                    Log.i(
                        TAG,
                        "Resolved HEVC decoders (10bitOrOdd=$is10BitOrOddSizeHevc, preferSw=$preferSw, ${format.width}x${format.height}): ${infos.map { it.name }}"
                    )
                }
                return infos
            }

            override fun getMediaFormat(
                format: Format,
                codecMimeType: String,
                codecMaxValues: CodecMaxValues,
                codecOperatingRate: Float,
                deviceNeedsNoPostProcessWorkaround: Boolean,
                tunnelingAudioSessionId: Int
            ): MediaFormat {
                val mediaFormat = super.getMediaFormat(
                    format,
                    codecMimeType,
                    codecMaxValues,
                    codecOperatingRate,
                    deviceNeedsNoPostProcessWorkaround,
                    tunnelingAudioSessionId
                )
                // Request 8-bit SDR output transfer for 10-bit SDR HEVC (e.g. yuv420p10le bt709)
                // while keeping KEY_PROFILE intact so both software and hardware Main 10 decoders initialize properly.
                if (Build.VERSION.SDK_INT >= 31 && isFormat10BitHevc(format)) {
                    try {
                        mediaFormat.setInteger(
                            MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                            MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                        )
                    } catch (_: Exception) {}
                }
                return mediaFormat
            }
        }

        out.add(customVideoRenderer)

        val tempOut = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            smartVideoCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            tempOut
        )
        for (r in tempOut) {
            if (r !is MediaCodecVideoRenderer) {
                out.add(r)
            }
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val customAudioRenderer = object : MediaCodecAudioRenderer(
            context,
            codecAdapterFactory,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            audioSink
        ) {
            override fun getMediaFormat(
                format: Format,
                codecMimeType: String,
                codecMaxValues: Int,
                codecOperatingRate: Float
            ): MediaFormat {
                val mediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate)
                // Allow the decoder to output all channels (up to 99) so Media3's 5.1/7.1 AAC
                // ChannelMappingAudioProcessor never sees a 6-vs-2 channel mismatch, while
                // UniversalStereoAudioProcessor in DefaultAudioSink downmixes 5.1/7.1 to 2-channel Stereo.
                mediaFormat.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 99)
                if (Build.VERSION.SDK_INT >= 28) {
                    mediaFormat.setInteger("aac-max-output-channel_count", 99)
                }
                if (Build.VERSION.SDK_INT >= 24) {
                    mediaFormat.setInteger(
                        MediaFormat.KEY_PCM_ENCODING,
                        android.media.AudioFormat.ENCODING_PCM_16BIT
                    )
                }
                return mediaFormat
            }

            override fun onOutputFormatChanged(format: Format, outputMediaFormat: MediaFormat?) {
                // Synchronize format.channelCount with the actual channel count emitted by the decoder
                // so MediaCodecAudioRenderer never attaches a 6-channel AAC channelMap if the device's
                // decoder downmixed 5.1 to 2 channels internally.
                val syncedFormat = if (outputMediaFormat != null) {
                    val actualChannels = if (outputMediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outputMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else {
                        format.channelCount
                    }
                    val actualSampleRate = if (outputMediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outputMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        format.sampleRate
                    }
                    format.buildUpon()
                        .setChannelCount(actualChannels)
                        .setSampleRate(actualSampleRate)
                        .setEncoderDelay(0)
                        .setEncoderPadding(0)
                        .build()
                } else {
                    format
                }
                super.onOutputFormatChanged(syncedFormat, outputMediaFormat)
            }
        }

        out.add(customAudioRenderer)

        val tempOut = ArrayList<Renderer>()
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            tempOut
        )
        for (r in tempOut) {
            if (r !is MediaCodecAudioRenderer) {
                out.add(r)
            }
        }
    }
}

