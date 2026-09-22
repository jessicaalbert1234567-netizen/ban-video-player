package com.example.asr

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * High-performance 80-channel Log Mel-Spectrogram feature extractor for OpenAI Whisper.
 * Complies with the official Whisper feature specification:
 * - 16 kHz sampling rate
 * - 400-point STFT window (periodic Hann)
 * - 160-point hop length
 * - 80-channel Mel filterbank (loaded from mel_80.bin)
 * - 3000 frames (30.0s window, padded with reflection)
 * - Dynamic range clamp (max - 8.0 dB) and normalization: (val + 4.0) / 4.0
 */
class WhisperFeatureExtractor(private val context: Context) {

    companion object {
        private const val TAG = "WhisperFeatureExtractor"
        const val SAMPLE_RATE = 16000
        const val N_FFT = 400
        const val HOP_LENGTH = 160
        const val CHUNK_SAMPLES = 480000 // 30 seconds
        const val NUM_FRAMES = 3000
        const val N_MELS = 80
        const val NUM_BINS = 201 // N_FFT / 2 + 1
        private const val N1 = 16
        private const val N2 = 25

        // Periodic Hann window: w[n] = 0.5 * (1 - cos(2 * pi * n / 400))
        private val hannWindow = FloatArray(N_FFT) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT.toDouble()))).toFloat()
        }

        // Precomputed twiddle factors for 16x25 composite FFT
        // twiddles[k1 * N2 + n2] = exp(-2pi * i * n2 * k1 / 400)
        private val twiddleCos = FloatArray(N1 * N2)
        private val twiddleSin = FloatArray(N1 * N2)

        // Precomputed 25-point DFT basis: exp(-2pi * i * n2 * k2 / 25)
        private val dft25Cos = FloatArray(N2 * N2)
        private val dft25Sin = FloatArray(N2 * N2)

        // Precomputed 16-point DFT twiddles for fast radix-2 FFT
        private val w16Cos = FloatArray(8)
        private val w16Sin = FloatArray(8)

        init {
            for (k1 in 0 until N1) {
                for (n2 in 0 until N2) {
                    val angle = -2.0 * PI * (n2 * k1) / N_FFT.toDouble()
                    twiddleCos[k1 * N2 + n2] = cos(angle).toFloat()
                    twiddleSin[k1 * N2 + n2] = sin(angle).toFloat()
                }
            }
            for (k2 in 0 until N2) {
                for (n2 in 0 until N2) {
                    val angle = -2.0 * PI * (n2 * k2) / N2.toDouble()
                    dft25Cos[k2 * N2 + n2] = cos(angle).toFloat()
                    dft25Sin[k2 * N2 + n2] = sin(angle).toFloat()
                }
            }
            for (i in 0 until 8) {
                val angle = -2.0 * PI * i / 16.0
                w16Cos[i] = cos(angle).toFloat()
                w16Sin[i] = sin(angle).toFloat()
            }
        }
    }

    // Mel filterbank weights: 80 x 201
    private val melFilterbank = Array(N_MELS) { FloatArray(NUM_BINS) }
    private var isFilterbankLoaded = false

    init {
        loadMelFilterbank()
    }

    private fun loadMelFilterbank() {
        try {
            val assetNames = context.assets.list("") ?: emptyArray()
            if ("mel_80.bin" in assetNames) {
                context.assets.open("mel_80.bin").use { inputStream ->
                    val bytes = inputStream.readBytes()
                    val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val floatBuffer = byteBuffer.asFloatBuffer()
                    val totalFloats = floatBuffer.remaining()
                    if (totalFloats >= N_MELS * NUM_BINS) {
                        for (m in 0 until N_MELS) {
                            for (k in 0 until NUM_BINS) {
                                melFilterbank[m][k] = floatBuffer.get()
                            }
                        }
                        isFilterbankLoaded = true
                        Log.d(TAG, "Loaded 80-channel Mel filterbank from assets (mel_80.bin)")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load mel_80.bin from assets: ${e.message}. Using built-in Slaney filterbank.")
        }

        // Fallback: Compute Slaney Mel filterbank mathematically
        computeSlaneyMelFilterbank()
        isFilterbankLoaded = true
    }

    private fun computeSlaneyMelFilterbank() {
        // Mel points from 0 to 8000 Hz using Slaney scale
        fun hzToMel(hz: Double): Double {
            return if (hz >= 1000.0) {
                15.0 + 27.0 * kotlin.math.ln(hz / 1000.0) / kotlin.math.ln(6.4)
            } else {
                3.0 * hz / 200.0
            }
        }

        fun melToHz(mel: Double): Double {
            return if (mel >= 15.0) {
                1000.0 * Math.pow(6.4, (mel - 15.0) / 27.0)
            } else {
                200.0 * mel / 3.0
            }
        }

        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(8000.0)
        val melPoints = DoubleArray(N_MELS + 2) { i -> minMel + i * (maxMel - minMel) / (N_MELS + 1) }
        val hzPoints = DoubleArray(N_MELS + 2) { i -> melToHz(melPoints[i]) }
        val binFreqs = DoubleArray(NUM_BINS) { i -> i * 16000.0 / N_FFT }

        for (m in 0 until N_MELS) {
            val leftHz = hzPoints[m]
            val centerHz = hzPoints[m + 1]
            val rightHz = hzPoints[m + 2]
            val norm = 2.0 / (rightHz - leftHz)

            for (k in 0 until NUM_BINS) {
                val freq = binFreqs[k]
                val weight = when {
                    freq in leftHz..centerHz -> (freq - leftHz) / (centerHz - leftHz) * norm
                    freq in centerHz..rightHz -> (rightHz - freq) / (rightHz - centerHz) * norm
                    else -> 0.0
                }
                melFilterbank[m][k] = weight.toFloat()
            }
        }
    }

    /**
     * Extracts 80-channel log-Mel spectrogram from 16kHz audio samples.
     * Returns a flat FloatArray with shape [1, 80, 3000] (layout: channel * 3000 + frame).
     */
    fun extract(audioSamples: FloatArray): FloatArray {
        // 1. Prepare 30.0s buffer (480,000 samples)
        val samples30s = FloatArray(CHUNK_SAMPLES)
        val copyCount = audioSamples.size.coerceAtMost(CHUNK_SAMPLES)
        System.arraycopy(audioSamples, 0, samples30s, 0, copyCount)

        // 2. Reflection padding of 200 samples on each side -> 480,400 samples
        val pad = N_FFT / 2 // 200
        val paddedLen = CHUNK_SAMPLES + N_FFT // 480,400
        val paddedAudio = FloatArray(paddedLen)

        // Front reflection
        for (i in 0 until pad) {
            paddedAudio[i] = samples30s[pad - i]
        }
        // Center
        System.arraycopy(samples30s, 0, paddedAudio, pad, CHUNK_SAMPLES)
        // End reflection
        for (i in 0 until pad) {
            paddedAudio[pad + CHUNK_SAMPLES + i] = samples30s[CHUNK_SAMPLES - 2 - i]
        }

        // 3. STFT & Mel power spectrogram
        val melOutput = FloatArray(N_MELS * NUM_FRAMES)
        val frameWindowed = FloatArray(N_FFT)
        val power = FloatArray(NUM_BINS)

        // Reusable working buffers for composite FFT
        val colReal = Array(N1) { FloatArray(N2) }
        val colImag = Array(N1) { FloatArray(N2) }
        val tempR = FloatArray(16)
        val tempI = FloatArray(16)

        for (frameIdx in 0 until NUM_FRAMES) {
            val startSample = frameIdx * HOP_LENGTH

            // Apply periodic Hann window
            for (n in 0 until N_FFT) {
                frameWindowed[n] = paddedAudio[startSample + n] * hannWindow[n]
            }

            // Step A: 25 16-point FFTs along columns
            for (n2 in 0 until N2) {
                // Bit-reversal for 16-point input: x[n1 * 25 + n2]
                tempR[0]  = frameWindowed[0 * 25 + n2]
                tempR[8]  = frameWindowed[1 * 25 + n2]
                tempR[4]  = frameWindowed[2 * 25 + n2]
                tempR[12] = frameWindowed[3 * 25 + n2]
                tempR[2]  = frameWindowed[4 * 25 + n2]
                tempR[10] = frameWindowed[5 * 25 + n2]
                tempR[6]  = frameWindowed[6 * 25 + n2]
                tempR[14] = frameWindowed[7 * 25 + n2]
                tempR[1]  = frameWindowed[8 * 25 + n2]
                tempR[9]  = frameWindowed[9 * 25 + n2]
                tempR[5]  = frameWindowed[10 * 25 + n2]
                tempR[13] = frameWindowed[11 * 25 + n2]
                tempR[3]  = frameWindowed[12 * 25 + n2]
                tempR[11] = frameWindowed[13 * 25 + n2]
                tempR[7]  = frameWindowed[14 * 25 + n2]
                tempR[15] = frameWindowed[15 * 25 + n2]
                tempI.fill(0f)

                // 16-point Radix-2 FFT
                fft16(tempR, tempI)

                // Multiply by twiddle factors: exp(-2pi * i * n2 * k1 / 400)
                for (k1 in 0 until N1) {
                    val twIdx = k1 * N2 + n2
                    val cosW = twiddleCos[twIdx]
                    val sinW = twiddleSin[twIdx]
                    val r = tempR[k1]
                    val im = tempI[k1]
                    colReal[k1][n2] = r * cosW - im * sinW
                    colImag[k1][n2] = r * sinW + im * cosW
                }
            }

            // Step B: 25-point DFT combinations for k = 0 until 201
            for (k in 0 until NUM_BINS) {
                val k1 = k % N1
                val k2 = k / N1
                val dftOffset = k2 * N2
                var rAcc = 0f
                var iAcc = 0f

                val rowR = colReal[k1]
                val rowI = colImag[k1]

                for (n2 in 0 until N2) {
                    val c = dft25Cos[dftOffset + n2]
                    val s = dft25Sin[dftOffset + n2]
                    rAcc += rowR[n2] * c - rowI[n2] * s
                    iAcc += rowR[n2] * s + rowI[n2] * c
                }
                power[k] = rAcc * rAcc + iAcc * iAcc
            }

            // Step C: Mel filterbank dot product & log10
            for (m in 0 until N_MELS) {
                var energy = 0f
                val filter = melFilterbank[m]
                for (k in 0 until NUM_BINS) {
                    energy += power[k] * filter[k]
                }
                val logEnergy = log10(max(energy, 1e-10f))
                melOutput[m * NUM_FRAMES + frameIdx] = logEnergy
            }
        }

        // 4. Dynamic range clamp and normalization across all 3000 frames
        var globalMax = Float.NEGATIVE_INFINITY
        for (i in melOutput.indices) {
            if (melOutput[i] > globalMax) {
                globalMax = melOutput[i]
            }
        }

        val clampMin = globalMax - 8.0f
        for (i in melOutput.indices) {
            val clamped = max(melOutput[i], clampMin)
            melOutput[i] = (clamped + 4.0f) / 4.0f
        }

        return melOutput
    }

    private fun fft16(r: FloatArray, im: FloatArray) {
        // Stage 1 (size 2)
        for (i in 0 until 16 step 2) {
            val uR = r[i]; val uI = im[i]
            val vR = r[i + 1]; val vI = im[i + 1]
            r[i] = uR + vR; im[i] = uI + vI
            r[i + 1] = uR - vR; im[i + 1] = uI - vI
        }
        // Stage 2 (size 4)
        for (i in 0 until 16 step 4) {
            for (j in 0 until 2) {
                val cosW = if (j == 0) 1f else 0f
                val sinW = if (j == 0) 0f else -1f
                val idx1 = i + j
                val idx2 = idx1 + 2
                val vR = r[idx2] * cosW - im[idx2] * sinW
                val vI = r[idx2] * sinW + im[idx2] * cosW
                val uR = r[idx1]; val uI = im[idx1]
                r[idx1] = uR + vR; im[idx1] = uI + vI
                r[idx2] = uR - vR; im[idx2] = uI - vI
            }
        }
        // Stage 3 (size 8)
        for (i in 0 until 16 step 8) {
            for (j in 0 until 4) {
                val cosW = w16Cos[j * 2]
                val sinW = w16Sin[j * 2]
                val idx1 = i + j
                val idx2 = idx1 + 4
                val vR = r[idx2] * cosW - im[idx2] * sinW
                val vI = r[idx2] * sinW + im[idx2] * cosW
                val uR = r[idx1]; val uI = im[idx1]
                r[idx1] = uR + vR; im[idx1] = uI + vI
                r[idx2] = uR - vR; im[idx2] = uI - vI
            }
        }
        // Stage 4 (size 16)
        for (j in 0 until 8) {
            val cosW = w16Cos[j]
            val sinW = w16Sin[j]
            val idx1 = j
            val idx2 = j + 8
            val vR = r[idx2] * cosW - im[idx2] * sinW
            val vI = r[idx2] * sinW + im[idx2] * cosW
            val uR = r[idx1]; val uI = im[idx1]
            r[idx1] = uR + vR; im[idx1] = uI + vI
            r[idx2] = uR - vR; im[idx2] = uI - vI
        }
    }
}
