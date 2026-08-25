package com.example.pathpilot.network

import com.example.pathpilot.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 백엔드 API 클라이언트. 로컬 개발 시 `uvicorn backend.main:app --reload --port 8000`을 켜두고
 * [BASE_URL]을 에뮬레이터/실기기에서 접근 가능한 주소로 맞춘다.
 *
 * - 에뮬레이터에서 로컬 PC의 8000번 포트: "http://10.0.2.2:8000/"
 * - 같은 Wi-Fi의 실기기에서 로컬 PC: "http://<PC의 사설 IP>:8000/"
 * - 배포된 백엔드가 생기면 그 주소로 교체 (팀 공지 후 변경 — docs/ARCHITECTURE.md §2)
 */
object RetrofitClient {

    // 실기기 + USB 연결: `adb reverse tcp:8000 tcp:8000`로 폰의 127.0.0.1:8000을 PC로 터널링.
    // 공용/학교 Wi-Fi는 기기 간 통신(client isolation)이 막혀있는 경우가 많아 Wi-Fi IP보다 안정적이다.
    //
    // 주소는 BuildConfig 필드라 재빌드만 하면 바뀐다 — 코드를 고칠 필요가 없다:
    //   에뮬레이터:  ./gradlew installDebug -PbackendBaseUrl=http://10.0.2.2:8000/
    //   같은 Wi-Fi:  ./gradlew installDebug -PbackendBaseUrl=http://10.28.85.74:8000/
    // (Wi-Fi로 붙일 때는 서버를 `uvicorn ... --host 0.0.0.0`으로 띄워야 한다. 기본 바인딩은
    //  127.0.0.1이라 폰에서 PC의 IP로 접속해도 연결이 거부된다.)
    private val BASE_URL = BuildConfig.BACKEND_BASE_URL

    // 서버가 안 떠 있을 때 10초를 통째로 기다렸다 실패하면 시연에선 그게 곧 사고다.
    // 로컬(adb reverse)이든 같은 Wi-Fi든 연결 자체는 1초 안에 되거나 안 된다.
    private const val CONNECT_TIMEOUT_SECONDS = 3L
    /** 백엔드 라우터의 AI_CLIENT_TIMEOUT_SECONDS(12초)보다 여유 있게 잡는다. */
    private const val READ_TIMEOUT_SECONDS = 20L
    /** 연결+전송+응답 전체의 상한. 이게 없으면 어느 단계에서도 안 끝나는 요청이 남을 수 있다. */
    private const val CALL_TIMEOUT_SECONDS = 25L

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        // BODY로 두면 화면의 모든 텍스트(연락처 이름, 대화 내용 포함)가 logcat에 통째로 남아
        // CLAUDE.md §4 "화면 데이터 비영속화"에 어긋나고, 수백 KB JSON을 매 요청 문자열로
        // 만드느라 체감 지연도 늘어난다. 디버그 빌드에서도 헤더까지만 남긴다.
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
