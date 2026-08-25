package com.example.pathpilot.network

import com.example.pathpilot.model.DecideRequest
import com.example.pathpilot.model.DecideResponse
import retrofit2.http.Body
import retrofit2.http.POST

/** 백엔드 계약: CLAUDE.md §5, docs/ARCHITECTURE.md §6. */
interface ApiService {
    @POST("api/v1/decide")
    suspend fun decide(@Body request: DecideRequest): DecideResponse
}
