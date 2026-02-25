package com.cellclaw.wakeword

import kotlin.math.*

/**
 * Pure Kotlin MFCC feature extraction matching the Python training config exactly.
 *
 * Config: sample_rate=16000, n_mfcc=13, n_fft=512, n_mels=40,
 *         frame_length=400 (25ms), hop_length=160 (10ms),
 *         fmin=20Hz, fmax=8000Hz
 *
 * Input:  ShortArray of raw 16-bit PCM audio (1.5s = 24000 samples at 16kHz)
 * Output: FloatArray of shape (13 * numFrames) flattened row-major
 */
class MfccExtractor(
    private val sampleRate: Int = 16000,
    private val nMfcc: Int = 13,
    private val nFft: Int = 512,
    private val nMels: Int = 40,
    private val frameLength: Int = 400,
    private val hopLength: Int = 160,
    private val fmin: Float = 20f,
    private val fmax: Float = 8000f,
) {
    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    /**
     * Extract MFCC features from raw PCM audio.
     * @param audio 16-bit PCM samples
     * @param expectedFrames expected number of time frames (pad/trim)
     * @return FloatArray of shape (nMfcc * expectedFrames), row-major: [mfcc0_frame0, mfcc0_frame1, ...]
     */
    fun extract(audio: ShortArray, expectedFrames: Int = 150): FloatArray {
        // Convert to float and normalize
        val signal = FloatArray(audio.size) { audio[it] / 32768f }

        // Pre-emphasis
        val emphasized = preEmphasis(signal)

        // Framing + windowing + FFT + power spectrum + mel filterbank + log + DCT
        val numFrames = maxOf(1, (emphasized.size - frameLength) / hopLength + 1)
        val hammingWindow = hammingWindow(frameLength)

        // Compute MFCC for each frame
        val mfccs = Array(nMfcc) { FloatArray(numFrames) }

        for (f in 0 until numFrames) {
            val start = f * hopLength
            val frame = FloatArray(nFft)

            // Apply window
            for (i in 0 until minOf(frameLength, emphasized.size - start)) {
                frame[i] = emphasized[start + i] * hammingWindow[i]
            }

            // FFT
            val spectrum = fft(frame)

            // Power spectrum (only positive frequencies)
            val nBins = nFft / 2 + 1
            val powerSpectrum = FloatArray(nBins)
            for (i in 0 until nBins) {
                val re = spectrum[i * 2]
                val im = spectrum[i * 2 + 1]
                powerSpectrum[i] = (re * re + im * im) / nFft
            }

            // Mel filterbank
            val melEnergies = FloatArray(nMels)
            for (m in 0 until nMels) {
                var sum = 0f
                for (i in 0 until nBins) {
                    sum += melFilterbank[m][i] * powerSpectrum[i]
                }
                melEnergies[m] = ln(maxOf(sum, 1e-10f))
            }

            // DCT-II to get MFCCs
            for (c in 0 until nMfcc) {
                var sum = 0f
                for (m in 0 until nMels) {
                    sum += melEnergies[m] * cos(PI.toFloat() * c * (m + 0.5f) / nMels)
                }
                mfccs[c][f] = sum
            }
        }

        // Pad or trim to expectedFrames and flatten row-major
        val result = FloatArray(nMfcc * expectedFrames)
        for (c in 0 until nMfcc) {
            for (f in 0 until expectedFrames) {
                result[c * expectedFrames + f] = if (f < numFrames) mfccs[c][f] else 0f
            }
        }

        return result
    }

    private fun preEmphasis(signal: FloatArray, coeff: Float = 0.97f): FloatArray {
        if (signal.isEmpty()) return signal
        val result = FloatArray(signal.size)
        result[0] = signal[0]
        for (i in 1 until signal.size) {
            result[i] = signal[i] - coeff * signal[i - 1]
        }
        return result
    }

    private fun hammingWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            0.54f - 0.46f * cos(2f * PI.toFloat() * i / (size - 1))
        }
    }

    /**
     * Cooley-Tukey FFT for power-of-2 sizes.
     * Input length must be power of 2 (nFft=512 is fine).
     * Returns interleaved [re0, im0, re1, im1, ...] of length 2*n.
     */
    private fun fft(input: FloatArray): FloatArray {
        val n = input.size
        // Interleave as complex: [re, im, re, im, ...]
        val data = FloatArray(n * 2)
        for (i in 0 until n) {
            data[i * 2] = input[i]
            data[i * 2 + 1] = 0f
        }

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                // Swap complex values
                val tmpRe = data[i * 2]
                val tmpIm = data[i * 2 + 1]
                data[i * 2] = data[j * 2]
                data[i * 2 + 1] = data[j * 2 + 1]
                data[j * 2] = tmpRe
                data[j * 2 + 1] = tmpIm
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }

        // Cooley-Tukey butterfly
        var step = 1
        while (step < n) {
            val halfStep = step
            step *= 2
            val angleStep = -PI.toFloat() / halfStep
            for (k in 0 until halfStep) {
                val angle = angleStep * k
                val wRe = cos(angle)
                val wIm = sin(angle)
                var i = k
                while (i < n) {
                    val jIdx = i + halfStep
                    val tRe = wRe * data[jIdx * 2] - wIm * data[jIdx * 2 + 1]
                    val tIm = wRe * data[jIdx * 2 + 1] + wIm * data[jIdx * 2]
                    data[jIdx * 2] = data[i * 2] - tRe
                    data[jIdx * 2 + 1] = data[i * 2 + 1] - tIm
                    data[i * 2] = data[i * 2] + tRe
                    data[i * 2 + 1] = data[i * 2 + 1] + tIm
                    i += step
                }
            }
        }

        return data
    }

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

    private fun buildMelFilterbank(): Array<FloatArray> {
        val nBins = nFft / 2 + 1
        val melMin = hzToMel(fmin)
        val melMax = hzToMel(fmax)

        // nMels + 2 equally spaced points in mel scale
        val melPoints = FloatArray(nMels + 2) { i ->
            melMin + i * (melMax - melMin) / (nMels + 1)
        }
        val hzPoints = FloatArray(nMels + 2) { melToHz(melPoints[it]) }
        val binPoints = IntArray(nMels + 2) { i ->
            ((hzPoints[i] * nFft / sampleRate) + 0.5f).toInt()
        }

        return Array(nMels) { m ->
            val filter = FloatArray(nBins)
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]

            for (k in left until center) {
                if (k < nBins && center > left) {
                    filter[k] = (k - left).toFloat() / (center - left)
                }
            }
            for (k in center until right) {
                if (k < nBins && right > center) {
                    filter[k] = (right - k).toFloat() / (right - center)
                }
            }
            filter
        }
    }
}
