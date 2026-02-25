package com.cellclaw.wakeword

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wake word detector using a TFLite CNN model.
 *
 * Loads wake_word_model.tflite from assets, extracts MFCC features from raw PCM audio,
 * and runs inference to determine if the "CellClaw" wake word was spoken.
 */
@Singleton
class WakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var interpreter: Interpreter? = null
    private val mfccExtractor = MfccExtractor()

    private val nMfcc = 13
    private val expectedFrames = 150

    val isInitialized: Boolean
        get() = interpreter != null

    fun initialize() {
        if (interpreter != null) return

        try {
            val model = loadModelFile("wake_word_model.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseNNAPI(true)
            }
            interpreter = Interpreter(model, options)
            Log.d(TAG, "WakeWordDetector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WakeWordDetector: ${e.message}")
        }
    }

    /**
     * Run wake word detection on raw PCM audio.
     * @param audioData 16-bit PCM samples (1.5s at 16kHz = 24000 samples)
     * @return confidence score [0-1], where 1 = high confidence wake word detected
     */
    fun detect(audioData: ShortArray): Float {
        val interp = interpreter ?: return 0f

        // Extract MFCC features
        val mfccFlat = mfccExtractor.extract(audioData, expectedFrames)

        // Reshape to model input: [1, 13, 150, 1]
        val inputBuffer = ByteBuffer.allocateDirect(4 * nMfcc * expectedFrames).apply {
            order(ByteOrder.nativeOrder())
            for (value in mfccFlat) {
                putFloat(value)
            }
            rewind()
        }

        // Output: [1, 1]
        val outputBuffer = ByteBuffer.allocateDirect(4).apply {
            order(ByteOrder.nativeOrder())
        }

        return try {
            interp.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            outputBuffer.float
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            0f
        }
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "WakeWordDetector released")
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
