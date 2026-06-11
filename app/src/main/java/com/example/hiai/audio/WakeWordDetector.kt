package com.example.hiai.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 唤醒词检测器
 * 
 * 使用 Sherpa-ONNX 进行本地离线唤醒词检测
 * 
 * @param context Android Context，用于访问 assets 和文件系统
 * @param onDetected 检测到唤醒词时的回调
 */
class WakeWordDetector(
    private val context: Context,
    private val onDetected: (keyword: String) -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val MODEL_DIR = "sherpa-onnx-models"
        
        // 使用 chunk-16 模型（延迟 320ms，但准确率更高）
        private const val ENCODER_MODEL = "encoder-epoch-13-avg-2-chunk-16-left-64.onnx"
        private const val DECODER_MODEL = "decoder-epoch-13-avg-2-chunk-16-left-64.onnx"
        private const val JOINER_MODEL = "joiner-epoch-13-avg-2-chunk-16-left-64.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val KEYWORDS_FILE = "keywords.txt"
    }
    
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var detectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isRunning = false
    
    /**
     * 初始化检测器
     * 将模型文件从 assets 复制到内部存储并创建 KeywordSpotter
     */
    fun init(): Boolean {
        try {
            // 复制模型文件到内部存储
            val modelPath = copyModelFiles()
            if (modelPath == null) {
                Log.e(TAG, "Failed to copy model files")
                return false
            }
            
            // 复制关键词文件
            val keywordsPath = copyKeywordsFile()
            if (keywordsPath == null) {
                Log.e(TAG, "Failed to copy keywords file")
                return false
            }
            
            // 创建 KeywordSpotter 配置
            val config = KeywordSpotterConfig(
                featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                    featureDim = 80
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelPath/$ENCODER_MODEL",
                        decoder = "$modelPath/$DECODER_MODEL",
                        joiner = "$modelPath/$JOINER_MODEL"
                    ),
                    tokens = "$modelPath/$TOKENS_FILE",
                    modelType = "zipformer2"
                ),
                keywordsFile = keywordsPath,
                keywordsScore = 1.5f,
                keywordsThreshold = 0.25f,
                numTrailingBlanks = 2
            )
            
            spotter = KeywordSpotter(config = config)
            Log.i(TAG, "WakeWordDetector initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WakeWordDetector", e)
            return false
        }
    }
    
    /**
     * 启动唤醒词检测
     */
    fun start() {
        Log.d(TAG, "=== start: START ===")
        
        if (spotter == null) {
            Log.e(TAG, "Spotter not initialized")
            return
        }
        
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }
        
        try {
            // 创建 AudioRecord
            Log.d(TAG, "  - Creating AudioRecord...")
            val channelConfig = if (CHANNELS == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }
            
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )
            Log.d(TAG, "  - Buffer size: $bufferSize")
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed, state: ${audioRecord?.state}")
                audioRecord = null
                return
            }
            Log.d(TAG, "  - AudioRecord initialized")
            
            // 创建检测流
            stream = spotter!!.createStream()
            Log.d(TAG, "  - Detection stream created")
            
            audioRecord?.startRecording()
            Log.d(TAG, "  - AudioRecord started")
            isRunning = true
            
            // 启动检测循环
            detectionJob = scope.launch {
                Log.d(TAG, "  - Detection loop started")
                // 每 100ms 读取一次音频
                val samplesPerRead = SAMPLE_RATE / 10 // 1600 samples
                val buffer = ShortArray(samplesPerRead)
                
                while (isRunning && isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // 转换为 float 数组
                        val samples = FloatArray(read) { i ->
                            buffer[i] / 32768.0f
                        }
                        
                        // 送入检测器
                        stream?.let { s ->
                            s.acceptWaveform(samples, SAMPLE_RATE)
                            
                            while (spotter?.isReady(s) == true) {
                                spotter?.decode(s)
                            }
                            
                            // 检查是否检测到关键词
                            val result = spotter?.getResult(s)
                            if (result != null && result.keyword.isNotEmpty()) {
                                Log.i(TAG, "Detected keyword: ${result.keyword}")
                                
                                // 通知主线程
                                withContext(Dispatchers.Main) {
                                    onDetected(result.keyword)
                                }
                                // 仅在检测到关键词并处理后，才重置流以检测下一个关键词
                                spotter?.reset(s)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WakeWordDetector", e)
        }
        
        Log.i(TAG, "WakeWordDetector started")
    }
    
    /**
     * 停止唤醒词检测
     */
    fun stop() {
        Log.d(TAG, "=== stop: START ===")
        
        isRunning = false
        Log.d(TAG, "  - isRunning set to false")
        
        detectionJob?.cancel()
        detectionJob = null
        Log.d(TAG, "  - Detection job cancelled")
        
        try {
            audioRecord?.stop()
            Log.d(TAG, "  - AudioRecord stopped")
        } catch (e: Exception) {
            Log.e(TAG, "  - Error stopping AudioRecord", e)
        }
        
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "  - AudioRecord released")
        
        stream?.let { spotter?.reset(it) }
        Log.d(TAG, "=== stop: DONE ===")
        
        Log.i(TAG, "WakeWordDetector stopped")
    }
    
    /**
     * 更新关键词列表
     * 
     * 注意：这需要重新创建 KeywordSpotter
     */
    fun updateKeywords(keywords: List<String>): Boolean {
        try {
            // 生成关键词文件内容
            val content = keywords.joinToString("\n")
            
            // 写入新文件
            val keywordsFile = File(context.filesDir, "custom_keywords.txt")
            keywordsFile.writeText(content)
            
            // 停止当前检测
            val wasRunning = isRunning
            if (wasRunning) {
                stop()
            }
            
            // 释放旧的 spotter
            stream?.release()
            stream = null
            spotter?.release()
            spotter = null
            
            // 重新初始化
            val modelPath = File(context.filesDir, MODEL_DIR).absolutePath
            val config = KeywordSpotterConfig(
                featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                    featureDim = 80
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelPath/$ENCODER_MODEL",
                        decoder = "$modelPath/$DECODER_MODEL",
                        joiner = "$modelPath/$JOINER_MODEL"
                    ),
                    tokens = "$modelPath/$TOKENS_FILE",
                    modelType = "zipformer2"
                ),
                keywordsFile = keywordsFile.absolutePath,
                keywordsScore = 1.5f,
                keywordsThreshold = 0.25f,
                numTrailingBlanks = 2
            )
            
            spotter = KeywordSpotter(config = config)
            
            // 如果之前在运行，重新启动
            if (wasRunning) {
                start()
            }
            
            Log.i(TAG, "Keywords updated: $keywords")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update keywords", e)
            return false
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stop()
        stream?.release()
        stream = null
        spotter?.release()
        spotter = null
        scope.cancel()
        Log.i(TAG, "WakeWordDetector released")
    }
    
    /**
     * 复制模型文件到内部存储
     */
    private fun copyModelFiles(): String? {
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        
        try {
            val assetsFiles = context.assets.list(MODEL_DIR) ?: return null
            
            for (fileName in assetsFiles) {
                // 跳过目录（如 test_wavs）
                if (context.assets.list("$MODEL_DIR/$fileName")?.isNotEmpty() == true) {
                    continue
                }
                
                val destFile = File(modelDir, fileName)
                if (!destFile.exists()) {
                    context.assets.open("$MODEL_DIR/$fileName").use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Copied model file: $fileName")
                }
            }
            
            return modelDir.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy model files", e)
            return null
        }
    }
    
    /**
     * 复制关键词文件到内部存储
     */
    private fun copyKeywordsFile(): String? {
        try {
            val keywordsFile = File(context.filesDir, KEYWORDS_FILE)
            
            // 始终复制最新的关键词文件
            context.assets.open(KEYWORDS_FILE).use { input ->
                FileOutputStream(keywordsFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied keywords file")
            
            return keywordsFile.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy keywords file", e)
            return null
        }
    }
}
