package com.bistu.allthebistu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * 语音识别探针（仅 debug 源集，不进正式包）。
 *
 * 回答一个问题：这台手机上，第三方 App 到底能用哪些语音识别引擎？
 * 小米机型上如果这里列出的服务是小米自己的，那系统 `SpeechRecognizer`
 * 就有可能实际跑在小爱那套 ASR 上 —— 这点只能在真机上看。
 */
class SpeechProbeActivity : ComponentActivity() {

    private val currentLocale = Locale.getDefault().toLanguageTag()

    private val log = mutableStateListOf<String>()
    private var listening by mutableStateOf(false)
    private var recognizer: SpeechRecognizer? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startOnce() else log.add(0, "麦克风权限被拒绝，无法测试识别")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = buildReport()

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("语音识别探针", fontSize = 18.sp)
                    Text(report, fontSize = 12.sp)
                    Button(
                        onClick = {
                            when {
                                listening -> stop()
                                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED -> startOnce()
                                else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) {
                        Text(if (listening) "停止" else "说一句话试试（中文）")
                    }
                    log.forEach { Text(it, fontSize = 12.sp) }
                }
            }
        }
    }

    private fun buildReport(): String {
        val sb = StringBuilder()
        sb.appendLine("系统能否识别：" + SpeechRecognizer.isRecognitionAvailable(this))
        val def = Settings.Secure.getString(contentResolver, "voice_recognition_service")
        sb.appendLine("默认引擎：" + (def ?: "未设置"))
        sb.appendLine()
        sb.appendLine("已注册的 RecognitionService：")

        val services = packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.MATCH_ALL
        )
        if (services.isEmpty()) {
            sb.appendLine("（一个都没有 —— 这台机器上第三方拿不到系统识别）")
        } else {
            services.forEach { si ->
                val info = si.serviceInfo
                sb.appendLine("· ${info.packageName}/${info.name}")
                sb.appendLine("   enabled=${info.enabled}")
            }
        }

        sb.appendLine()
        sb.appendLine("相关包是否安装：")
        listOf(
            "com.miui.voiceassist",
            "com.xiaomi.aiasst.service",
            "com.miui.voiceassistpro",
            "com.miui.voicetrigger",
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts"
        ).forEach { p ->
            val installed = runCatching { packageManager.getPackageInfo(p, 0) }.isSuccess
            if (installed) sb.appendLine("· $p ✓")
        }
        return sb.toString().trimEnd()
    }

    private fun startOnce() {
        recognizer?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log.add(0, "系统没有可用的识别服务，后续就不用试了")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { log.add(0, "就绪，开始说话…") }
            override fun onBeginningOfSpeech() { log.add(0, "检测到说话") }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { log.add(0, "说话结束，等待结果…") }
            override fun onError(error: Int) {
                listening = false
                log.add(0, "错误码 $error（" + errName(error) + "）")
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                log.add(0, "最终结果：${text ?: "（空）"}")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                log.add(0, "中间结果：$text")
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        log.add(0, "开始识别（中文 zh-CN，系统默认 $currentLocale）")
        r.startListening(intent)
        listening = true
    }

    private fun stop() {
        recognizer?.stopListening()
        listening = false
    }

    private fun errName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听懂"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
        SpeechRecognizer.ERROR_SERVER -> "服务端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "一直没检测到说话"
        else -> "未知"
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
