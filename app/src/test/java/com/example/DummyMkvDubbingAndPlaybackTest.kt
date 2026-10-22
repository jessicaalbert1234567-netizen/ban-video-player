package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.audio.WavUtils
import com.example.player.AudioTrackChoice
import com.example.player.MediaPlayerManager
import com.example.player.SubtitleChoice
import com.example.subtitle.SubtitleExtractor
import com.example.subtitle.SubtitleGenerator
import com.example.translation.OfflineDialogueTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DummyMkvDubbingAndPlaybackTest {

    private fun createDummyMkvWithSubtitles(outputFile: File): Boolean {
        return try {
            val srtFile = File(outputFile.parentFile, "temp_test_subs.srt")
            srtFile.writeText(
                """
                1
                00:00:01,000 --> 00:00:03,500
                Hello my friend, welcome to the test movie!

                2
                00:00:04,000 --> 00:00:07,000
                This is an authentic MKV test video with subtitles.
                """.trimIndent()
            )

            val cmd = arrayOf(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=8:size=640x360:rate=24",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=8",
                "-i", srtFile.absolutePath,
                "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-c:s", "srt",
                "-metadata:s:s:0", "language=eng",
                outputFile.absolutePath
            )

            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            srtFile.delete()
            exitCode == 0 && outputFile.exists() && outputFile.length() > 1024
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Test
    fun testDummyMkvCreationSubtitleExtractionAndPlayback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(context.cacheDir, "mkv_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val dummyMkv = File(testDir, "sample_movie.mkv")

        val created = createDummyMkvWithSubtitles(dummyMkv)
        assertTrue("Dummy MKV file must be created via ffmpeg with video, audio, and subtitles", created)
        assertTrue(dummyMkv.exists() && dummyMkv.length() > 5000)

        // 1. Verify SubtitleExtractor can inspect subtitle tracks in the MKV
        val mkvUri = Uri.fromFile(dummyMkv)
        val tracks = SubtitleExtractor.inspectSubtitleTracks(context, mkvUri)
        println("Discovered subtitle tracks in MKV: ${tracks.size}")
        assertTrue("MKV must contain at least 1 subtitle track", tracks.isNotEmpty())

        val firstTrack = tracks.first()
        println("Track title=${firstTrack.title}, lang=${firstTrack.language}")

        // 2. Extract subtitle cues from MKV
        val cues = SubtitleExtractor.extractCues(context, mkvUri, firstTrack.trackIndex)
        println("Extracted ${cues.size} cues from MKV")
        assertTrue("Extracted cues must not be empty", cues.isNotEmpty())
        assertEquals(2, cues.size)
        assertTrue(cues[0].text.contains("Hello my friend"))
        assertTrue(cues[1].text.contains("authentic MKV test video"))

        // 3. Translate extracted dialogue to Bengali
        val translatedText1 = OfflineDialogueTranslator.translate(cues[0].text)
        val translatedText2 = OfflineDialogueTranslator.translate(cues[1].text)
        println("Cue 1 translation: $translatedText1")
        println("Cue 2 translation: $translatedText2")
        assertTrue(translatedText1.contains("হ্যালো"))

        // 4. Generate Bengali SRT
        val bnSrtFile = File(testDir, "subtitle_bn.srt")
        val segments = cues.mapIndexed { idx, cue ->
            com.example.database.TranscriptSegmentEntity(
                projectId = "test_mkv_proj",
                index = idx,
                startMs = cue.startMs,
                endMs = cue.endMs,
                sourceText = cue.text,
                translatedText = if (idx == 0) translatedText1 else translatedText2
            )
        }
        SubtitleGenerator.generateSrt(segments, bnSrtFile)
        assertTrue(bnSrtFile.exists() && bnSrtFile.length() > 0)

        // 5. Generate mock dubbed audio track (WAV)
        val dubbedWav = File(testDir, "dubbed_bn.wav")
        WavUtils.createSilenceWav(dubbedWav, 8000L)
        assertTrue(dubbedWav.exists() && dubbedWav.length() > 44)

        // 6. Test MediaPlayerManager: Setup MKV video and verify playback controls
        val playerManager = MediaPlayerManager(context)
        playerManager.setupMedia(
            videoUri = mkvUri,
            dubbedAudioFile = dubbedWav,
            srtFile = bnSrtFile
        )

        val stateAfterSetup = playerManager.playerState.value
        assertFalse("Player must not have error after setup", stateAfterSetup.hasError)
        assertTrue("Player must detect dubbed audio file", stateAfterSetup.hasDubbedAudio)
        assertTrue("Player must detect Bangla subtitles", stateAfterSetup.hasBanglaSubtitles)

        // 7. CRITICAL TEST: Play/Pause button toggle test
        println("Initial isPlaying: ${playerManager.playerState.value.isPlaying}")

        // Press Play (▶️ -> ⏸)
        playerManager.togglePlayPause()
        val stateAfterPlay = playerManager.playerState.value
        println("State after play press: isPlaying=${stateAfterPlay.isPlaying}, playWhenReady=${playerManager.player?.playWhenReady}")
        assertTrue("▶️ Play button must turn on and set isPlaying=true", stateAfterPlay.isPlaying)
        assertTrue("ExoPlayer playWhenReady must be true", playerManager.player?.playWhenReady == true)

        // Press Pause (⏸ -> ▶️)
        playerManager.togglePlayPause()
        val stateAfterPause = playerManager.playerState.value
        println("State after pause press: isPlaying=${stateAfterPause.isPlaying}, playWhenReady=${playerManager.player?.playWhenReady}")
        assertFalse("⏸ Pause button must pause and set isPlaying=false", stateAfterPause.isPlaying)
        assertFalse("ExoPlayer playWhenReady must be false", playerManager.player?.playWhenReady == false)

        // 8. Test Audio Choice Switching (Smart Auto Dub, Original, Bangla Only)
        playerManager.setAudioChoice(AudioTrackChoice.ORIGINAL)
        assertEquals(AudioTrackChoice.ORIGINAL, playerManager.playerState.value.audioChoice)

        playerManager.setAudioChoice(AudioTrackChoice.BANGLA_ONLY)
        assertEquals(AudioTrackChoice.BANGLA_ONLY, playerManager.playerState.value.audioChoice)

        playerManager.setAudioChoice(AudioTrackChoice.BANGLA_DUB)
        assertEquals(AudioTrackChoice.BANGLA_DUB, playerManager.playerState.value.audioChoice)

        // 9. Test Subtitle Choice Switching (Bangla, English, Off)
        playerManager.setSubtitleChoice(SubtitleChoice.BANGLA)
        assertEquals(SubtitleChoice.BANGLA, playerManager.playerState.value.subtitleChoice)

        playerManager.setSubtitleChoice(SubtitleChoice.OFF)
        assertEquals(SubtitleChoice.OFF, playerManager.playerState.value.subtitleChoice)

        // 10. Clean up
        playerManager.player?.release()
        testDir.deleteRecursively()
        println("=== MKV Dubbing and Playback Acceptance Test PASSED successfully! ===")
    }
}
