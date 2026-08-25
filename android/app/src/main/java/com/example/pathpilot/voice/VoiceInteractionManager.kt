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
import android.util.Log
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

    /** [listenOnce]가 예약한, 아직 실행 안 된 마이크 재오픈 지연 콜백. [stopListening]이 취소한다 —
     * 안 그러면 listenOnce가 연달아 두 번 불렸을 때 recognizer가 두 개 열리는 문제가 재발한다. */
    private var pendingStart: Runnable? = null

    /** 마지막으로 recognizer를 destroy()한 시각. 재오픈 대기를 "파괴 직후"에만 적용하기 위한 기준. */
    private var lastRecognizerDestroyAt = 0L

    /**
     * TextToSpeech의 [UtteranceProgressListener] 콜백은 메인 스레드가 아닌 TTS 내부 스레드에서
     * 온다(공식 문서에 명시됨). 여기서 곧바로 [SpeechRecognizer.createSpeechRecognizer]를 부르면
     * "SpeechRecognizer should be used only from the application's main thread" RuntimeException이
     * 그 스레드에서 조용히 터져서 — 화면엔 아무 에러도 안 뜨고 그냥 마이크가 안 켜진 것처럼 보인다.
     * 실제로 겪은 버그(2026-08-25 실기기 테스트: "네, 말씀하세요" 이후 마이크가 무반응).
     * 그래서 onDone/onError를 메인 스레드로 넘겨준 다음에 콜백을 실행한다.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 발화·인식 콜백의 세대 번호. [cancelAll]이 올린다. 콜백을 등록할 때 이 값을 캡처해 두고
     * 실행 직전에 다시 비교해서, 값이 달라졌으면(= 그 사이 대화 턴이 통째로 취소됐으면)
     * 아무것도 하지 않는다.
     *
     * TTS/STT 콜백은 "이미 예약된 것"을 코드로 취소할 방법이 마땅치 않다 — [Handler]에 올린
     * 것만 removeCallbacks로 걷어낼 수 있고, 엔진 내부 스레드에서 뒤늦게 올라오는 콜백은
     * 막을 수 없다. 그래서 취소를 "실행 시점에 무시한다"로 구현한다.
     */
    private var generation = 0

    /**
     * TTS 엔진 초기화 콜백이 도착했는지(성공·실패 무관). 엔진이 뜨는 데 수백 ms가 걸리는데
     * 그 사이에 들어온 [speak]를 그냥 흘려보내면 **콜드 스타트 직후 첫 발화가 통째로 씹힌다** —
     * [askAndListen]이었다면 사용자는 무엇을 묻는지 듣지도 못한 채 마이크만 열린 상태로 남는다.
     * 그래서 초기화 전에는 [pendingUtterance]에 보관했다가 준비되면 재생한다.
     */
    private var isTtsInitialized = false

    /** 초기화 완료를 기다리는 발화. `QUEUE_FLUSH` 의미론과 맞추어 마지막 하나만 유지한다. */
    private var pendingUtterance: Pair<String, () -> Unit>? = null

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // setLanguage의 반환값을 확인한다. 한국어 음성 데이터가 없는 기기에서는
                // 엔진 초기화 자체는 SUCCESS인데 실제로는 아무 소리도 안 난다 — 그 경우
                // 최소한 로그로는 원인이 남아야 한다.
                val languageResult = textToSpeech?.setLanguage(Locale.KOREAN)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.e(TAG, "한국어 TTS 데이터 없음 (result=$languageResult) — 음성 안내가 나가지 않는다")
                }
                isTtsReady = true
            } else {
                Log.e(TAG, "TTS 초기화 실패 (status=$status) — 음성 안내 없이 진행한다")
            }
            // 성공이든 실패든 "기다림"은 끝났다. 실패했더라도 큐를 풀어줘야 speak가 onDone을
            // 실행해서 후속 흐름(마이크 열기 등)이 이어진다.
            isTtsInitialized = true
            mainHandler.post { flushPendingUtterance() }
        }
    }

    private fun flushPendingUtterance() {
        val (text, onDone) = pendingUtterance ?: return
        pendingUtterance = null
        speak(text, onDone)
    }

    /** 발화 id -> 완료 콜백. 리스너는 인스턴스에 하나뿐이라 발화별 콜백은 여기서 찾는다.
     * QUEUE_ADD로 여러 발화가 큐에 쌓여도 각자의 onDone이 유실되지 않는다.
     * 메인 스레드(등록)와 TTS 내부 스레드(제거)가 함께 만지므로 동시성 안전 맵을 쓴다. */
    private val utteranceCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private var isTtsListenerRegistered = false

    /**
     * 질문/안내 문장을 음성으로 재생한다. 재생 중인 멘트가 있으면 **끝난 뒤에 이어서** 재생한다
     * (QUEUE_ADD) — 예전 QUEUE_FLUSH는 스텝이 빠르게 이어질 때 이전 멘트를 중간에 끊어
     * 안내가 겹치고 잘려 들렸다. [onDone]은 항상 메인 스레드에서, 이번 발화가 실제로 끝났을
     * 때만 정확히 한 번 호출된다.
     *
     * `setOnUtteranceProgressListener`는 TextToSpeech 인스턴스 전체에 하나만 걸리는 API다.
     * 발화마다 리스너를 덮어쓰면 큐에 남아 있던 앞 발화의 완료 콜백이 유실된다(질문 발화의
     * onDone이 사라지면 마이크가 영영 안 열린다). 그래서 리스너는 한 번만 등록하고,
     * 발화별 콜백은 [utteranceCallbacks] 맵으로 관리한다.
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val myGeneration = generation
        if (!isTtsInitialized) {
            // 엔진이 아직 뜨는 중이다. 흘려보내면 첫 발화가 통째로 씹히므로 보관했다가
            // 초기화 콜백에서 재생한다([flushPendingUtterance]).
            Log.d(TAG, "TTS 준비 전 — 발화를 큐에 보관")
            pendingUtterance = text to onDone
            return
        }
        val tts = textToSpeech
        if (tts == null || !isTtsReady) {
            mainHandler.post { if (myGeneration == generation) onDone() }
            return
        }
        registerTtsListenerOnce(tts)

        val utteranceId = UUID.randomUUID().toString()
        utteranceCallbacks[utteranceId] = { if (myGeneration == generation) onDone() }
        // 세션 중단/새 세션 시작 시에는 cancelAll()의 tts.stop()이 큐를 통째로 비우고
        // 콜백 맵도 함께 비우므로, 묵은 멘트가 새 세션으로 넘어가지 않는다.
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun registerTtsListenerOnce(tts: TextToSpeech) {
        if (isTtsListenerRegistered) return
        isTtsListenerRegistered = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                val callback = id?.let { utteranceCallbacks.remove(it) } ?: return
                mainHandler.post(callback)
            }

            /**
             * **이 override를 지우면 안 된다.** [UtteranceProgressListener.onStop]의 기본 구현은
             * `onDone(utteranceId)`로 위임한다 — 즉 `tts.stop()`으로 발화를 죽인 것이
             * "정상 재생 완료"로 둔갑한다. 그러면 [askAndListen]의 완료 콜백인 [listenOnce]가
             * 실행되어, 방금 끈 마이크가 곧바로 다시 열린다(2026-08-26 실기기에서 겪은 버그).
             * 취소는 완료가 아니므로 콜백만 걷어내고 아무것도 실행하지 않는다.
             */
            override fun onStop(id: String?, interrupted: Boolean) {
                id?.let { utteranceCallbacks.remove(it) }
                Log.d(TAG, "발화 취소됨 (id=$id interrupted=$interrupted) — 후속 동작 없음")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                // 재생 실패도 흐름은 이어가야 한다(질문이 안 읽혔어도 마이크는 열려야 함).
                val callback = id?.let { utteranceCallbacks.remove(it) } ?: return
                mainHandler.post(callback)
            }
        })
    }

    /**
     * 마이크를 켜고 한 문장을 인식한다. 결과/오류를 콜백으로 돌려준다.
     * [onListeningChanged]는 마이크가 실제로 열려서 듣기 시작하면 true, 발화가 끝나서(또는 오류로)
     * 더 이상 안 듣기 시작하면 false로 호출된다 — "지금 듣고 있는 중인지" UI 표시용.
     */
    fun listenOnce(
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {},
        onListeningChanged: (Boolean) -> Unit = {},
    ) {
        val myGeneration = generation
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
        // 방금 막 destroy()한 이전 recognizer가 마이크/오디오 리소스를 완전히 놓기 전에 새
        // recognizer를 바로 열면, 일부 기기(삼성 실기기 포함)에서 새 세션이 사용자가 말을
        // 시작하기도 전에 "무음"으로 즉시 오판된다. 그래서 파괴 직후에는 텀을 둔다.
        //
        // **단, 이 대기는 "파괴 직후"에만 적용한다.** 파괴한 recognizer가 없는데도 무조건
        // 기다리면 질문 TTS가 끝나고 사용자가 곧장 말을 시작할 때 첫 음절이 잘린다 —
        // 실제로 "기차 예매해줘"가 "차 예매해줘"로 들어와 엉뚱한 앱이 열렸다(2026-08-26).
        val sinceDestroy = android.os.SystemClock.uptimeMillis() - lastRecognizerDestroyAt
        val delay = (MIC_REOPEN_DELAY_MS - sinceDestroy).coerceIn(0L, MIC_REOPEN_DELAY_MS)
        val runnable = Runnable {
            if (myGeneration != generation) return@Runnable
            startRecognition(myGeneration, onResult, onError, onListeningChanged)
        }
        pendingStart = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun startRecognition(
        myGeneration: Int,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningChanged: (Boolean) -> Unit,
    ) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        speechRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 침묵 길이 관련 EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS를 걸어봤으나 이 기기의 ASR
            // 스택에서는 "말은 인식되는데(withSpeech=true) 최종 텍스트가 빈 값"으로 나오는 역효과가
            // 났다(2026-08-25 실기기 재현). OEM별 동작 차이가 커서 안 믿을 수 있는 옵션이라 뺐다 —
            // 기본 엔드포인팅이 이미 문장 단위(예: "카톡으로 최원호에게 가장 최근에 찍은 사진 보내
            // 줘" 같은 긴 문장)를 잘 잡아냈으므로 그대로 둔다.
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onListeningChanged(true)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                onListeningChanged(false)
            }

            // 아래 두 콜백은 destroy() 이후에도 뒤늦게 올라올 수 있다. 세대가 바뀌었으면
            // (= 사용자가 중단/종료를 눌렀으면) 그 답변은 이미 버려진 대화 턴의 것이므로
            // 흘려보낸다 — 그냥 통과시키면 중단한 뒤에 클릭이 실행되는 사고로 이어진다.
            override fun onError(error: Int) {
                onListeningChanged(false)
                if (myGeneration != generation) {
                    Log.d(TAG, "취소된 대화 턴의 인식 오류 무시 (code=$error)")
                    return
                }
                onError("음성 인식 오류 (code=$error)")
            }

            override fun onResults(results: Bundle?) {
                onListeningChanged(false)
                if (myGeneration != generation) {
                    Log.d(TAG, "취소된 대화 턴의 인식 결과 무시")
                    return
                }
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
    fun askAndListen(
        question: String,
        onAnswer: (String) -> Unit,
        onError: (String) -> Unit = {},
        onListeningChanged: (Boolean) -> Unit = {},
    ) {
        speak(question) {
            listenOnce(onResult = onAnswer, onError = onError, onListeningChanged = onListeningChanged)
        }
    }

    /**
     * "안녕 손자" 같은 웨이크 문구가 들릴 때까지 [listenOnce]를 반복한다. `SpeechRecognizer`는
     * 한 번에 한 문장만 인식하는 API라 상시 리스닝 엔진이 아니라 "듣고 → 틀리면 잠깐 쉬었다 다시
     * 듣고"를 반복하는 방식으로 흉내낸다. [wakePhrase]는 공백을 무시하고 대소문자 구분 없이
     * 부분일치로 비교한다(예: "안녕 손자야"라고 말해도 "안녕 손자"에 매치).
     */
    /** [onHeard]는 매치 성공/실패와 무관하게 매번 STT가 실제로 인식한 문자열을 그대로 알려준다 —
     * 웨이크 문구가 왜 안 걸리는지(STT가 다르게 알아들었는지) 화면에서 바로 확인하기 위한 용도. */
    fun startWakeListening(
        wakePhrase: String,
        onWake: () -> Unit,
        onError: (String) -> Unit = {},
        onHeard: (String) -> Unit = {},
    ) {
        isWakeListening = true
        listenForWakePhraseOnce(wakePhrase, onWake, onError, onHeard)
    }

    private fun listenForWakePhraseOnce(
        wakePhrase: String,
        onWake: () -> Unit,
        onError: (String) -> Unit,
        onHeard: (String) -> Unit,
    ) {
        if (!isWakeListening) return
        listenOnce(
            onResult = { heard ->
                if (!isWakeListening) return@listenOnce
                val matched = normalizeForMatch(heard).contains(normalizeForMatch(wakePhrase))
                Log.d(TAG, "웨이크 인식: heard=\"$heard\" wakePhrase=\"$wakePhrase\" matched=$matched")
                onHeard(heard)
                if (matched) {
                    isWakeListening = false
                    onWake()
                } else {
                    scheduleWakeRelisten(wakePhrase, onWake, onError, onHeard)
                }
            },
            onError = { err ->
                if (!isWakeListening) return@listenOnce
                onError(err)
                scheduleWakeRelisten(wakePhrase, onWake, onError, onHeard)
            },
        )
    }

    private fun scheduleWakeRelisten(
        wakePhrase: String,
        onWake: () -> Unit,
        onError: (String) -> Unit,
        onHeard: (String) -> Unit,
    ) {
        restartHandler.postDelayed(
            { listenForWakePhraseOnce(wakePhrase, onWake, onError, onHeard) },
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

    /** 재생 중인 TTS를 즉시 멈춘다 (인스턴스는 유지 — 이후 [speak] 재사용 가능). */
    fun stopSpeaking() {
        textToSpeech?.stop()
    }

    /**
     * 진행 중인 발화·인식과 **아직 실행되지 않은 콜백까지** 전부 무효화한다.
     * "지금까지의 대화 턴을 통째로 버린다"는 뜻이므로 세션 중단/종료·새 세션 시작에 쓴다.
     *
     * [stopListening] + [stopSpeaking]을 따로 부르는 것과 다른 점: [generation]을 올려서
     * 이미 엔진에 등록돼 있어 취소할 수 없는 콜백들이 나중에 올라오더라도 실행되지 않게 만든다.
     * 중단을 눌렀는데 뒤늦게 도착한 음성 결과가 자동화를 다시 굴리는 사고를 막는 마지막 방어선이다.
     */
    fun cancelAll() {
        generation++
        // 아직 재생되지 않은 대기 발화도 취소 대상이다. 안 지우면 TTS 초기화가 늦게 끝날 때
        // 이미 중단한 세션의 질문이 뒤늦게 흘러나온다. 큐에 쌓인 발화들의 완료 콜백도 함께
        // 비운다 — stop()이 발화별 onStop을 안 주는 엔진도 있어 맵에 잔여물이 남을 수 있다.
        pendingUtterance = null
        utteranceCallbacks.clear()
        stopWakeListening() // 내부에서 stopListening()도 함께 호출한다
        textToSpeech?.stop()
    }

    fun stopListening() {
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        pendingStart = null
        if (speechRecognizer != null) {
            speechRecognizer?.destroy()
            speechRecognizer = null
            lastRecognizerDestroyAt = android.os.SystemClock.uptimeMillis()
        }
    }

    fun shutdown() {
        stopWakeListening()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTtsReady = false
    }

    companion object {
        private const val TAG = "VoiceInteraction"
        private const val WAKE_RELISTEN_DELAY_MS = 400L

        /** 이전 recognizer를 destroy한 직후 곧바로 새로 열 때 생기는 "즉시 무음 오판" 레이스를 피하는 텀. */
        private const val MIC_REOPEN_DELAY_MS = 450L

        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
