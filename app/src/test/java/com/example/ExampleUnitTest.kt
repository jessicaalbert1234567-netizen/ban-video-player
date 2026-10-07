package com.example

import com.example.subtitle.SubtitleExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
}

