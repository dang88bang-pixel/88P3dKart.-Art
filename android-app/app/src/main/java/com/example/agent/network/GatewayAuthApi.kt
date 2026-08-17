package com.example.agent.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GatewayAuthenticationException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

@Serializable
private data class EnrollmentClaimBody(
    @SerialName("device_id") val deviceId: String,
    val code: String,
)

@Serializable
private data class EnrollmentClaimResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_secret") val deviceSecret: String,
)

@Serializable
private data class SessionBody(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_secret") val deviceSecret: String,
)

@Serializable
data class GatewaySession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_at") val expiresAtEpochSeconds: Long,
    @SerialName("token_type") val tokenType: String,
)

interface GatewayAuthService {
    suspend fun claimEnrollment(deviceId: String, enrollmentCode: String): Pair<String, String>
    suspend fun createSession(deviceId: String, deviceSecret: String): GatewaySession
}

/** HTTPS-only enrollment and session API with bounded response parsing. */
class GatewayAuthApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) : GatewayAuthService {
    private val normalizedBaseUrl = GatewayEndpoint.normalizeHttpsBase(baseUrl)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun claimEnrollment(
        deviceId: String,
        enrollmentCode: String,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        require(DEVICE_ID_PATTERN.matches(deviceId)) { "Invalid device identifier" }
        require(enrollmentCode.length in 20..200) { "Invalid enrollment code" }
        val body = json.encodeToString(EnrollmentClaimBody(deviceId, enrollmentCode))
        val request = Request.Builder()
            .url(GatewayEndpoint.api(normalizedBaseUrl, "/api/v1/enrollment/claim"))
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        execute(request, expectedStatus = 200) { responseBody ->
            val response = decode<EnrollmentClaimResponse>(responseBody)
            if (response.deviceId != deviceId || response.deviceSecret.length !in 32..256) {
                throw GatewayAuthenticationException("Gateway returned an invalid enrollment response")
            }
            response.deviceId to response.deviceSecret
        }
    }

    override suspend fun createSession(deviceId: String, deviceSecret: String): GatewaySession =
        withContext(Dispatchers.IO) {
            require(DEVICE_ID_PATTERN.matches(deviceId)) { "Invalid device identifier" }
            require(deviceSecret.length in 32..256) { "Invalid device credential" }
            val body = json.encodeToString(SessionBody(deviceId, deviceSecret))
            val request = Request.Builder()
                .url(GatewayEndpoint.api(normalizedBaseUrl, "/api/v1/session"))
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            execute(request, expectedStatus = 200) { responseBody ->
                val session = decode<GatewaySession>(responseBody)
                if (session.tokenType != "Bearer" ||
                    session.accessToken.length !in 32..4096 ||
                    session.expiresAtEpochSeconds <= 0
                ) {
                    throw GatewayAuthenticationException("Gateway returned an invalid session")
                }
                session
            }
        }

    private fun <T> execute(request: Request, expectedStatus: Int, block: (String) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body
                    ?: throw GatewayAuthenticationException("Gateway response body is missing")
                val responseText = body.readBoundedUtf8(MAX_RESPONSE_BYTES)
                if (response.code != expectedStatus) {
                    throw GatewayAuthenticationException(
                        when (response.code) {
                            401 -> "Gateway rejected the credential"
                            429 -> "Too many credential attempts; retry later"
                            else -> "Gateway authentication request failed (${response.code})"
                        },
                    )
                }
                return block(responseText)
            }
        } catch (error: GatewayAuthenticationException) {
            throw error
        } catch (error: Exception) {
            throw GatewayAuthenticationException("Gateway authentication request failed", error)
        }
    }

    private inline fun <reified T> decode(value: String): T = try {
        json.decodeFromString(value)
    } catch (error: Exception) {
        throw GatewayAuthenticationException("Gateway returned malformed JSON", error)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 64L * 1024L
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

private fun okhttp3.ResponseBody.readBoundedUtf8(maxBytes: Long): String {
    val declared = contentLength()
    if (declared > maxBytes) {
        throw GatewayAuthenticationException("Gateway response is too large")
    }
    val source = source()
    val buffer = Buffer()
    var total = 0L
    while (total <= maxBytes) {
        val allowed = minOf(8192L, maxBytes + 1L - total)
        val read = source.read(buffer, allowed)
        if (read == -1L) break
        total += read
        if (total > maxBytes) {
            throw GatewayAuthenticationException("Gateway response is too large")
        }
    }
    if (!source.exhausted()) {
        throw GatewayAuthenticationException("Gateway response is too large")
    }
    return buffer.readUtf8()
}
