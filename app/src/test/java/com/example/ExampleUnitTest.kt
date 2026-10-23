package com.example

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import com.example.player.UniversalStereoAudioProcessor
import com.example.player.VlcGradeRenderersFactory
import com.example.subtitle.SubtitleExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testSubRipSrtParsing() {
        val srtContent = """
            1
            00:00:01,200 --> 00:00:04,500
            Hello world! Welcome to the show.

            2
            00:00:05,100 --> 00:00:08,200
            This is an English dialogue line.
        """.trimIndent()

        val cues = SubtitleExtractor.parseSubtitleContent(srtContent)
        assertEquals(2, cues.size)
        assertEquals(1200L, cues[0].startMs)
        assertEquals(4500L, cues[0].endMs)
        assertEquals("Hello world! Welcome to the show.", cues[0].text)

        assertEquals(5100L, cues[1].startMs)
        assertEquals(8200L, cues[1].endMs)
        assertEquals("This is an English dialogue line.", cues[1].text)
    }

    @Test
    fun testWebVttParsing() {
        val vttContent = """
            WEBVTT

            00:01:05.120 --> 00:01:09.450
            <v Narrator>Welcome back to the video.</v>

            00:01:10.000 --> 00:01:14.000
            <b>Enjoy the movie.</b>
        """.trimIndent()

        val cues = SubtitleExtractor.parseSubtitleContent(vttContent)
        assertEquals(2, cues.size)
        assertEquals(65120L, cues[0].startMs)
        assertEquals(69450L, cues[0].endMs)
        assertEquals("Welcome back to the video.", cues[0].text)
        assertEquals("Enjoy the movie.", cues[1].text)
    }

    @Test
    fun testAssSsaParsing() {
        val assContent = """
            [Script Info]
            Title: Sample ASS
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.50,0:00:06.10,Default,,0,0,0,,{\an8}Hello from {\b1}ASS{\b0} subtitle!
            Dialogue: 0,0:00:07.00,0:00:10.50,Default,,0,0,0,,Second line\Nwith newline.
        """.trimIndent()

        val cues = SubtitleExtractor.parseSubtitleContent(assContent)
        assertEquals(2, cues.size)
        assertEquals(2500L, cues[0].startMs)
        assertEquals(6100L, cues[0].endMs)
        assertEquals("Hello from ASS subtitle!", cues[0].text)
        assertEquals("Second line\nwith newline.", cues[1].text)
    }

    @Test
    fun testExportToSrt() {
        val tempFile = File.createTempFile("test_export", ".srt")
        val srtContent = """
            1
            00:00:01,000 --> 00:00:03,000
            Test Dialogue
        """.trimIndent()
        val cues = SubtitleExtractor.parseSubtitleContent(srtContent)
        val exported = SubtitleExtractor.exportToSrt(cues, tempFile)

        assertTrue(exported.exists())
        val text = exported.readText(Charsets.UTF_8)
        assertTrue(text.contains("00:00:01,000 --> 00:00:03,000"))
        assertTrue(text.contains("Test Dialogue"))
        tempFile.delete()
    }

    @Test
    fun testUniversalStereoAudioProcessorHandles5Point1FloatAndStereo() {
        val processor = UniversalStereoAudioProcessor()

        // 1. Standard 16-bit Stereo should pass through (isActive == false) without throwing
        val stereo16Format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        val outStereo16 = processor.configure(stereo16Format)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, outStereo16)
        assertFalse(processor.isActive)

        // 2. 5.1 Surround (6 channels) Float PCM (fltp, like HE-AAC 5.1 in MKV) must configure to 2ch 16-bit PCM
        val surround51Float = AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT)
        val outSurround = processor.configure(surround51Float)
        processor.flush()
        assertTrue(processor.isActive)
        assertEquals(48000, outSurround.sampleRate)
        assertEquals(2, outSurround.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, outSurround.encoding)

        // Feed 2 frames of 6-channel float PCM (FL, FR, FC, LFE, BL, BR)
        val inBuf = ByteBuffer.allocateDirect(2 * 6 * 4).order(ByteOrder.nativeOrder())
        repeat(2) {
            inBuf.putFloat(0.4f) // FL
            inBuf.putFloat(0.4f) // FR
            inBuf.putFloat(0.5f) // FC (Center dialogue)
            inBuf.putFloat(0.1f) // LFE
            inBuf.putFloat(0.2f) // BL
            inBuf.putFloat(0.2f) // BR
        }
        inBuf.flip()
        processor.queueInput(inBuf)
        val output = processor.output
        // 2 frames * 2 channels * 2 bytes = 8 bytes
        assertEquals(8, output.remaining())
    }

    @Test
    fun testHevc10BitDetection() {
        val hevcMain10Format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L93.B0")
            .build()
        assertTrue(VlcGradeRenderersFactory.isFormat10BitHevc(hevcMain10Format))

        val hevcColor10Format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build())
            .build()
        assertTrue(VlcGradeRenderersFactory.isFormat10BitHevc(hevcColor10Format))

        val hevc8BitFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L93.B0")
            .build()
        assertFalse(VlcGradeRenderersFactory.isFormat10BitHevc(hevc8BitFormat))
    }
}
