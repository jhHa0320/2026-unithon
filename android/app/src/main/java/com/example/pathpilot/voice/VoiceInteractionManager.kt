package com.example.pathpilot.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

/**
 * TTS(질문 재생) -> STT(답변 캡처) 연속 대화 루프.
 *
 * 되묻기(ASK_USER, CLAUDE.md §5-1) 흐름에서 쓴다:
 * 1) AI가 보낸 질문을 [speak]로 재생
 * 2) 재생이 끝나면 자동으로 마이크를 켜고 [SpeechRecognizer]로 답변을 받는다
 * 3) 답변 텍스트를 콜백으로 돌려준다 (호출부에서 goal에 이어붙여 재요청)
 *
 * RECORD_AUDIO 런타임 권한이 이미 허용돼 있어야 한다 (ui/permission/PermissionActivity 담당).
 */
class VoiceInteractionManager(context: Context) {

    private val appContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isTtsReady = false

    private val restartHandler = Handler(Looper.getMainLooper())
    private var isWakeListening = false

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.KOREAN
                isTtsReady = true
            }
        }
    }

    /** 질문/안내 문장을 음성으로 재생한다. */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val tts = textToSpeech
        if (tts == null || !isTtsReady) {
            onDone()
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onDone()
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** 마이크를 켜고 한 문장을 인식한다. 결과/오류를 콜백으로 돌려준다. */
    fun listenOnce(onResult: (String) -> Unit, onError: (String) -> Unit = {}) {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            onError("마이크 권한이 없습니다. 설정에서 허용해주세요.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("이 기기에서는 음성 인식을 사용할 수 없습니다.")
            return
        }
        stopListening()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        speechRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                onError("음성 인식 오류 (code=$error)")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val answer = matches?.firstOrNull()
                if (answer.isNullOrBlank()) {
                    onError("답변을 인식하지 못했습니다.")
                } else {
                    onResult(answer)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
    }

    /** [speak]가 끝나자마자 자동으로 [listenOnce]를 시작하는 연속 대화 한 턴. */
    fun askAndListen(question: String, onAnswer: (String) -> Unit, onError: (String) -> Unit = {}) {
        speak(question) {
            listenOnce(onResult = onAnswer, onError = onError)
        }
    }

    /**
     * "안녕 손자" 같은 웨이크 문구가 들릴 때까지 [listenOnce]를 반복한다. `SpeechRecognizer`는
     * 한 번에 한 문장만 인식하는 API라 상시 리스닝 엔진이 아니라 "듣고 → 틀리면 잠깐 쉬었다 다시
     * 듣고"를 반복하는 방식으로 흉내낸다. [wakePhrase]는 공백을 무시하고 대소문자 구분 없이
     * 부분일치로 비교한다(예: "안녕 손자야"라고 말해도 "안녕 손자"에 매치).
     */
    fun startWakeListening(wakePhrase: String, onWake: () -> Unit, onError: (String) -> Unit = {}) {
        isWakeListening = true
        listenForWakePhraseOnce(wakePhrase, onWake, onError)
    }

    private fun listenForWakePhraseOnce(wakePhrase: String, onWake: () -> Unit, onError: (String) -> Unit) {
        if (!isWakeListening) return
        listenOnce(
            onResult = { heard ->
                if (!isWakeListening) return@listenOnce
                if (normalizeForMatch(heard).contains(normalizeForMatch(wakePhrase))) {
                    isWakeListening = false
                    onWake()
                } else {
                    scheduleWakeRelisten(wakePhrase, onWake, onError)
                }
            },
            onError = { err ->
                if (!isWakeListening) return@listenOnce
                onError(err)
                scheduleWakeRelisten(wakePhrase, onWake, onError)
            },
        )
    }

    private fun scheduleWakeRelisten(wakePhrase: String, onWake: () -> Unit, onError: (String) -> Unit) {
        restartHandler.postDelayed(
            { listenForWakePhraseOnce(wakePhrase, onWake, onError) },
            WAKE_RELISTEN_DELAY_MS,
        )
    }

    private fun normalizeForMatch(text: String) = text.replace(WHITESPACE_REGEX, "").lowercase(Locale.KOREAN)

    /** [startWakeListening] 루프를 멈춘다. 웨이크 문구를 찾기 전에 화면을 벗어나는 등의 상황에서 쓴다. */
    fun stopWakeListening() {
        isWakeListening = false
        restartHandler.removeCallbacksAndMessages(null)
        stopListening()
    }

    fun stopListening() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun shutdown() {
        stopWakeListening()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTtsReady = false
    }

    companion object {
        private const val WAKE_RELISTEN_DELAY_MS = 400L
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
