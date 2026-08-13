package com.example.agent.network

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AgentApiService {
    @POST("/api/v1/agent/config")
    suspend fun startScenario(@Body config: ScenarioConfig): Response<Unit>

    @GET("/api/v1/agent/history")
    suspend fun getHistory(
        @Query("device_id") deviceId: String,
        @Query("limit") limit: Int,
    ): HistoryResponse
}

data class ScenarioConfig(val type: String, val params: Map<String, Any>)
data class HistoryResponse(val device_id: String, val records: List<Map<String, Any>>)

class AgentApiClient(private val baseUrl: String = "http://192.168.1.100:8080") {
    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(AgentApiService::class.java)

    suspend fun startScenario(type: String, params: Map<String, Any>) {
        service.startScenario(ScenarioConfig(type, params))
    }

    suspend fun getHistory(deviceId: String, limit: Int = 100): HistoryResponse =
        service.getHistory(deviceId, limit)
}
