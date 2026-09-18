package com.example.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 80-channel Log Mel-Filterbank Feature Extractor for NeMo Citrinet / Conformer ASR models.
 * Operates on 16kHz mono 32-bit floating point audio [-1.0, 1.0].
 */
object MelSpectrogramExtractor {

    private const val SAMPLE_RATE = 16000
    private const val N_FFT = 512
    private const val WIN_LENGTH = 400 // 25ms
    private const val HOP_LENGTH = 160 // 10ms
    private const val N_MELS = 80
    private const val F_MIN = 0.0
    private const val F_MAX = 8000.0

    // Precomputed Hann window
    private val hannWindow = FloatArray(WIN_LENGTH) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (WIN_LENGTH - 1)))).toFloat()
    }

    // Precomputed 80-channel triangular mel filterbank weights [N_MELS, N_FFT / 2 + 1]
    private val melFilterbank: Array<FloatArray> by lazy {
        computeMelFilterbank(N_MELS, N_FFT, SAMPLE_RATE, F_MIN, F_MAX)
    }

    fun extract(audio: FloatArray): Array<FloatArray> {
        if (audio.size < WIN_LENGTH) {
            return Array(N_MELS) { FloatArray(0) }
        }

        val numFrames = (audio.size - WIN_LENGTH) / HOP_LENGTH + 1
        // Output format: [N_MELS][numFrames]
        val melEnergies = Array(N_MELS) { FloatArray(numFrames) }

        val real = FloatArray(N_FFT)
        val imag = FloatArray(N_FFT)
        val power = FloatArray(N_FFT / 2 + 1)

        for (frameIdx in 0 until numFrames) {
            val startSample = frameIdx * HOP_LENGTH
            // Apply window and pre-emphasis
            for (i in 0 until N_FFT) {
                if (i < WIN_LENGTH) {
                    val sample = audio[startSample + i]
                    val preEmph = if (i > 0) sample - 0.97f * audio[startSample + i - 1] else sample
                    real[i] = preEmph * hannWindow[i]
                } else {
                    real[i] = 0f
                }
                imag[i] = 0f
            }

            // In-place Radix-2 Cooley-Tukey FFT (N_FFT = 512)
            fft(real, imag)

            // Compute power spectrum (half spectrum)
            for (k in 0..N_FFT / 2) {
                val r = real[k]
                val im = imag[k]
                power[k] = r * r + im * im
            }

            // Apply 80 triangular mel filterbank channels
            for (m in 0 until N_MELS) {
                var energy = 0f
                val filter = melFilterbank[m]
                for (k in 0..N_FFT / 2) {
                    energy += power[k] * filter[k]
                }
                // Log energy
                melEnergies[m][frameIdx] = ln((energy + 1e-5f).toDouble()).toFloat()
            }
        }

        return melEnergies
    }

    private fun computeMelFilterbank(
        nMels: Int,
        nFft: Int,
        sampleRate: Int,
        fMin: Double,
        fMax: Double
    ): Array<FloatArray> {
        val numBins = nFft / 2 + 1
        val filterbank = Array(nMels) { FloatArray(numBins) }

        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        val minMel = hzToMel(fMin)
        val maxMel = hzToMel(fMax)

        val melPoints = DoubleArray(nMels + 2) { i ->
            minMel + i * (maxMel - minMel) / (nMels + 1)
        }
        val binPoints = IntArray(nMels + 2) { i ->
            val hz = melToHz(melPoints[i])
            ((nFft + 1) * hz / sampleRate).toInt().coerceIn(0, numBins - 1)
        }

        for (m in 1..nMels) {
            val left = binPoints[m - 1]
            val center = binPoints[m]
            val right = binPoints[m + 1]

            for (k in left until center) {
                filterbank[m - 1][k] = ((k - left).toFloat() / (center - left).coerceAtLeast(1))
            }
            for (k in center until right) {
                filterbank[m - 1][k] = ((right - k).toFloat() / (right - center).coerceAtLeast(1))
            }
        }

        return filterbank
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        // Bit reversal
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // Cooley-Tukey butterfly
        var step = 1
        while (step < n) {
            val angle = -PI / step
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()

            var group = 0
            while (group < n) {
                var curRe = 1f
                var curIm = 0f
                for (pair in 0 until step) {
                    val u = group + pair
                    val v = u + step
                    val vr = real[v] * curRe - imag[v] * curIm
                    val vi = real[v] * curIm + imag[v] * curRe

                    real[v] = real[u] - vr
                    imag[v] = imag[u] - vi
                    real[u] += vr
                    imag[u] += vi

                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
                group += step shl 1
            }
            step = step shl 1
        }
    }
}
