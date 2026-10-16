# AutoMkvDubber: Streamlined MKV Subtitle Extraction, GTX Translation & Google TTS Dubbing

Refactoring the processing architecture to match the MkvEdit + SimpleFileTranslator model while preserving the complete, feature-rich Media3/ExoPlayer video player system.

## User Review & Critical Decisions

> [!IMPORTANT]
> Based on your clarifications:
> 1. **UI Style**: Modern dark Compose UI with intuitive live progress bars, real-time terminal-style logs, and recent dubbings list, seamlessly launching the video player upon completion.
> 2. **Audio Sync with atempo**: Whenever a Bangla TTS dialogue clip is longer than the subtitle cue window (`endTimeMs - startTimeMs`), audio speed will be automatically adjusted so speech never overlaps subsequent lines.
> 3. **Dual File Saving**: Translated Bangla `.srt` and dubbed audio track will be saved both in the public `Download/FileTranslato/` folder for direct access in file managers and inside app project storage for instant playback.
> 4. **Video Player Unchanged**: The existing `PlayerScreen.kt` and `MediaPlayerManager.kt` with VLC gestures, brightness/volume HUD, subtitle synchronization, and audio switching remain completely intact.

---

## 1. Overview & Core Concept

**AutoMkvDubber** transforms MKV movie files into fully dubbed Bangla experiences:
1. **MKV Track Extraction**: Instantly extracts the original soundtrack and embedded English `.srt` subtitles without requiring heavy ASR speech recognition models.
2. **Fast Batch Translation (GTX Engine)**: Translates subtitle cues in concurrent batches using Google's GTX API (`client=gtx`, `dj=1`), formatting and saving a pristine `.srt` file directly to `Download/FileTranslato/`.
3. **Google TTS Speech Synthesis**: Uses the device's native Google Text-to-Speech (`com.google.android.tts`, `bn-BD`) to convert dialogue cues into speech segments.
4. **Intelligent atempo Sync & Silence Padding**: Accurately measures speech duration against subtitle timestamps. If Bangla speech is longer than the cue duration, tempo is adjusted; between dialogues, precise silence is padded to form a continuous, synchronized dubbed audio track.
5. **Integrated Video Player**: Users can immediately watch their dubbed MKV movie with synchronized audio and bilingual subtitles, or listen to the standalone dubbed audio track.

---

## 2. User Experience & Visual Design

### Key User Flows

```
┌─────────────────────────┐
│     Home Screen         │ ── Select .mkv ──► ┌─────────────────────────┐
│  • Movie Dubbing Card   │                    │     Dubbing Progress    │
│  • Recent Dubbed Movies │                    │  • Stage 1: Extraction  │
│  • Quick Actions        │                    │  • Stage 2: Translate   │
└─────────────────────────┘                    │  • Stage 3: TTS Speech  │
                                               │  • Stage 4: atempo Sync │
                                               │  • Real-time Log Stream │
                                               └───────────┬─────────────┘
                                                           │ (On Complete)
                                                           ▼
                                               ┌─────────────────────────┐
                                               │  Advanced Video Player  │
                                               │  • MKV Video Playback   │
                                               │  • Bangla Dubbed Audio  │
                                               │  • Gesture HUD & Subs   │
                                               └─────────────────────────┘
```

- **Home Screen**: Clean dark-mode interface with a prominent "MKV মুভি ফাইল সিলেক্ট করুন" (Select MKV Movie) action button, quick stats, and list of previous dubbings.
- **Progress Screen with Terminal Log**: Displays 5 automated stages with linear progress (0% to 100%) and a terminal-style monospace scrolling log box displaying extraction events, translation batches, and TTS cue progress.
- **Completion Actions**: "Play Dubbed Video" (instant launch into player), "Open in Downloads" (shows `Download/FileTranslato`), and "Back to Home".
- **Video Player**: Full-screen gesture-controlled player with double-tap seek (±10s), brightness/volume swipe HUD, Bangla/English subtitle toggle, and dual audio track switching.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Streamlined Pipeline replacing legacy ASR/Whisper components**
  - *Chosen Approach*: Remove unnecessary on-device ONNX Whisper models and complex speech segmentation pipelines. Rely on embedded MKV subtitle extraction + external SRT fallback.
  - *Why*: Whisper models require hundreds of megabytes of downloads and several minutes of compute per video. Extracting embedded subtitles takes **less than 2 seconds** and delivers 100% accurate text timestamps.
- **Decision 2: Google Translate GTX Batch Architecture**
  - *Chosen Approach*: Group dialogue lines into batches of 10-15 cues and query `translate.googleapis.com/translate_a/single?client=gtx&dj=1`.
  - *Why*: Translates an entire 2-hour movie's subtitles (1,000+ cues) in under 15 seconds without API keys or token limits.
- **Decision 3: Smart Dialogue Duration Matching (atempo / sonic)**
  - *Chosen Approach*: Check each TTS audio clip against `cue.endTimeMs - cue.startTimeMs`. If speech overflows the time slot, scale speed proportionally up to 1.35x. If there is a pause before the next dialogue, pad PCM silence.
  - *Why*: Ensures dialogue never lags behind scenes or collides with the next speaker's lines.
- **Decision 4: Preserve Media3 / ExoPlayer Player Architecture**
  - *Chosen Approach*: Keep `MediaPlayerManager.kt` and `PlayerScreen.kt` completely unchanged in their core playback logic, while pointing them to the newly generated `Download/FileTranslato` media assets.
  - *Why*: Matches user directive: *"ভিডিও প্লেয়ার ছাড়া বাকি সব কোড পরিবর্তন অথবা বাদ দাও ডিলিট করো"*.

---

## 4. Technical Architecture & Component Structure

```
┌────────────────────────────────────────────────────────────────────────┐
│                        AutoMkvDubber Architecture                      │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  [UI Layer]                                                            │
│    ├── HomeScreen (Compose M3)                                         │
│    ├── DubbingProgressScreen (Real-time logs + 5-stage progress)       │
│    └── PlayerScreen (Preserved: Media3 ExoPlayer, HUD, Gestures)       │
│                                                                        │
│  [Pipeline Layer: AutoMkvPipeline]                                     │
│    ├── 1. MkvTrackExtractor: Extracts AAC/Audio & English SRT from MKV │
│    ├── 2. GtxTranslator: OkHttp client=gtx batch subtitle translator   │
│    ├── 3. GoogleTtsEngine: Android TTS synthesizer (bn-BD)             │
│    └── 4. AudioSyncMerger: atempo speed scaling & silence timeline     │
│                                                                        │
│  [Storage & Export Layer]                                              │
│    ├── Public: /storage/emulated/0/Download/FileTranslato/             │
│    │     ├── [Movie]_original_audio.aac                                │
│    │     ├── [Movie]_english.srt                                       │
│    │     ├── [Movie]_bangla.srt                                        │
│    │     └── [Movie]_bangla_dubbed_audio.mp3                           │
│    └── App Internal: Context.getExternalFilesDir / Room Database       │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

### Components & Responsibilities:

1. **`AutoMkvPipeline.kt`**:
   - Orchestrates the 5 stages: MKV copy/access -> Audio/SRT extraction -> GTX translation -> TTS generation -> Audio synchronization and MP3 merge.
   - Provides live log events and percentage progress flows to UI.
2. **`GtxTranslator.kt`**:
   - Implements OkHttp GTX API with proper headers (`User-Agent`, `client=gtx`, `dj=1`), URL encoding, and sentence JSON parsing.
   - Saves translated Bangla `.srt` to `Download/FileTranslato/[Movie]_bangla.srt`.
3. **`GoogleTtsEngine.kt`**:
   - Manages `TextToSpeech` lifecycle, utterance progress listener, and per-cue synthesis to cache files.
4. **`AudioSyncMerger.kt`**:
   - Assembles the complete timeline: inserts calculated silence intervals and applies tempo speed-up for lengthy Bangla lines so dialogue never drifts.
5. **`DubbingViewModel.kt`**:
   - Bridges the simplified pipeline with Compose UI and provides seamless transition to `PlayerScreen`.
6. **`PlayerScreen.kt` & `MediaPlayerManager.kt`**:
   - Fully preserved for smooth playback of the MKV video with dynamic track switching and bilingual subtitles.
7. **`.github/workflows/build-apk.yml`**:
   - Clean GitHub Actions workflow for building release/debug APKs with direct download links.

---

## 5. Verification & Testing Plan

1. **Compilation Check**:
   - Run `compile_applet` to ensure zero compilation or syntax errors across Kotlin, Compose, and Media3 libraries.
2. **MKV Extraction Verification**:
   - Test subtitle track detection and extraction from MKV files.
3. **Translation Verification**:
   - Verify GTX API batch translation produces valid SRT timestamps and Bangla dialogue text.
4. **TTS & Sync Verification**:
   - Verify speech synthesis generates audio segments and concatenates them with accurate silence intervals matching subtitle cue start times.
5. **Playback Verification**:
   - Verify `PlayerScreen` loads the video and dubbed audio track cleanly without permission dialogs or black screens.
