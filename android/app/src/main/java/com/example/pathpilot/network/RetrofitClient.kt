package com.example.pathpilot.network

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
    // 에뮬레이터: "http://10.0.2.2:8000/"
    // 같은 Wi-Fi에서 USB 없이 붙고 싶다면: PC IPv4 주소로 교체 (예: "http://10.28.85.74:8000/")
    private const val BASE_URL = "http://127.0.0.1:8000/"
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    /** 백엔드의 AI_CLIENT_TIMEOUT_SECONDS(5초)보다 여유 있게 잡는다. */
    private const val READ_TIMEOUT_SECONDS = 15L

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
