package com.example.agent.network

import com.example.agent.network.models.AlarmHistoryResponse
import com.example.agent.network.models.AlarmRuntime
import com.example.agent.network.models.AlarmSnoozeRequest
import java.io.IOException
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface AgentApiService {
    @POST("/api/v1/agent/config")
    suspend fun startScenario(
        @Header("Authorization") authorization: String,
        @Body config: ScenarioConfig,
    ): Response<Unit>

    @GET("/api/v1/agent/history")
    suspend fun getHistory(
        @Header("Authorization") authorization: String,
        @Query("device_id") deviceId: String,
        @Query("limit") limit: Int,
    ): Response<HistoryResponse>

    @GET("/api/v1/alarm/runtime")
    suspend fun getAlarmRuntime(
        @Header("Authorization") authorization: String,
        @Query("policy_id") policyId: String,
        @Query("asset_id") assetId: String,
    ): Response<AlarmRuntime>

    @GET("/api/v1/alarm/events")
    suspend fun getAlarmEvents(
        @Header("Authorization") authorization: String,
        @Query("policy_id") policyId: String,
        @Query("asset_id") assetId: String,
        @Query("after_state_revision") afterStateRevision: Long,
        @Query("limit") limit: Int,
    ): Response<AlarmHistoryResponse>

    @POST("/api/v1/alarm/acknowledge")
    suspend fun acknowledgeAlarm(
        @Header("Authorization") authorization: String,
        @Query("policy_id") policyId: String,
        @Query("asset_id") assetId: String,
    ): Response<AlarmRuntime>

    @POST("/api/v1/alarm/snooze")
    suspend fun snoozeAlarm(
        @Header("Authorization") authorization: String,
        @Query("policy_id") policyId: String,
        @Query("asset_id") assetId: String,
        @Body request: AlarmSnoozeRequest,
    ): Response<AlarmRuntime>
}

data class ScenarioConfig(val type: String, val params: Map<String, Any>)
data class HistoryResponse(val device_id: String, val records: List<Map<String, Any>>)

/**
 * Authenticated HTTPS REST client.
 *
 * Every operation acquires a bounded, renewable in-memory session. A 401 causes
 * exactly one invalidation/renewal/retry; other failures are surfaced without
 * leaking response bodies or credentials.
 */
class AgentApiClient(
    baseUrl: String,
    private val sessionProvider: suspend () -> GatewaySession?,
    private val invalidateSession: () -> Unit,
) {
    private val httpClient = OkHttpClient.Builder().build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(GatewayEndpoint.normalizeHttpsBase(baseUrl))
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(AgentApiService::class.java)

    suspend fun startScenario(type: String, params: Map<String, Any>) {
        executeUnit { authorization ->
            service.startScenario(authorization, ScenarioConfig(type, params))
        }
    }

    suspend fun getHistory(deviceId: String, limit: Int = 100): HistoryResponse {
        requireDeviceId(deviceId)
        require(limit in 1..1000) { "History limit is outside accepted bounds" }
        return execute { authorization -> service.getHistory(authorization, deviceId, limit) }
    }

    suspend fun getAlarmRuntime(policyId: String, assetId: String): AlarmRuntime {
        requireAlarmIdentifiers(policyId, assetId)
        return execute { authorization ->
            service.getAlarmRuntime(authorization, policyId, assetId)
        }
    }

    suspend fun getAlarmEvents(
        policyId: String,
        assetId: String,
        afterStateRevision: Long = 0,
        limit: Int = 100,
    ): AlarmHistoryResponse {
        requireAlarmIdentifiers(policyId, assetId)
        require(afterStateRevision >= 0) { "Alarm event cursor must not be negative" }
        require(limit in 1..500) { "Alarm event limit is outside accepted bounds" }
        return execute { authorization ->
            service.getAlarmEvents(
                authorization,
                policyId,
                assetId,
                afterStateRevision,
                limit,
            )
        }
    }

    suspend fun acknowledgeAlarm(policyId: String, assetId: String): AlarmRuntime {
        requireAlarmIdentifiers(policyId, assetId)
        return execute { authorization ->
            service.acknowledgeAlarm(authorization, policyId, assetId)
        }
    }

    suspend fun snoozeAlarm(
        policyId: String,
        assetId: String,
        durationMs: Long,
    ): AlarmRuntime {
        requireAlarmIdentifiers(policyId, assetId)
        require(durationMs in 1_000..86_400_000) { "Snooze duration is outside accepted bounds" }
        return execute { authorization ->
            service.snoozeAlarm(
                authorization,
                policyId,
                assetId,
                AlarmSnoozeRequest(durationMs),
            )
        }
    }

    private suspend fun <T> execute(call: suspend (String) -> Response<T>): T {
        var response = call(bearerAuthorization())
        if (response.code() == 401) {
            invalidateSession()
            response = call(bearerAuthorization())
        }
        if (!response.isSuccessful) {
            throw IOException("Gateway request failed (${response.code()})")
        }
        return response.body() ?: throw IOException("Gateway returned an empty response")
    }

    private suspend fun executeUnit(call: suspend (String) -> Response<Unit>) {
        var response = call(bearerAuthorization())
        if (response.code() == 401) {
            invalidateSession()
            response = call(bearerAuthorization())
        }
        if (!response.isSuccessful) {
            throw IOException("Gateway request failed (${response.code()})")
        }
    }

    private suspend fun bearerAuthorization(): String {
        val token = sessionProvider()?.accessToken
            ?: throw IOException("No current gateway session")
        return "Bearer $token"
    }

    private fun requireAlarmIdentifiers(policyId: String, assetId: String) {
        require(POLICY_ID.matches(policyId)) { "Invalid alarm policy identifier" }
        require(ASSET_ID.matches(assetId)) { "Invalid alarm asset identifier" }
    }

    private fun requireDeviceId(deviceId: String) {
        require(DEVICE_ID.matches(deviceId)) { "Invalid device identifier" }
    }

    companion object {
        private val POLICY_ID = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
        )
        private val ASSET_ID = Regex("[A-Za-z0-9._:/-]{1,160}")
        private val DEVICE_ID = Regex("[A-Za-z0-9._:-]{1,160}")
    }
}
