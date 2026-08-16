package com.example.agent.security

import com.example.agent.network.GatewayAuthApi
import com.example.agent.network.GatewayAuthenticationException
import com.example.agent.network.GatewayAuthService
import com.example.agent.network.GatewayEndpoint
import com.example.agent.network.GatewaySession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes session renewal so reconnecting transports never stampede the gateway. */
class GatewaySessionManager(
    private val credentialStore: DeviceCredentialStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val apiFactory: (String) -> GatewayAuthService = { GatewayAuthApi(it) },
) {
    private val renewalMutex = Mutex()
    @Volatile private var cachedSession: GatewaySession? = null

    suspend fun validSession(): GatewaySession? = renewalMutex.withLock {
        val now = nowEpochSeconds()
        cachedSession?.takeIf { it.expiresAtEpochSeconds - RENEWAL_MARGIN_SECONDS > now }
            ?.let { return@withLock it }

        val credential = credentialStore.load() ?: return@withLock null
        val session = apiFactory(credential.gatewayBaseUrl).createSession(
            credential.deviceId,
            credential.deviceSecret,
        )
        if (session.expiresAtEpochSeconds - MINIMUM_USABLE_SESSION_SECONDS <= now) {
            throw GatewayAuthenticationException("Gateway session expires too soon")
        }
        cachedSession = session
        session
    }

    fun invalidateSession() {
        cachedSession = null
    }

    companion object {
        const val RENEWAL_MARGIN_SECONDS = 60L
        private const val MINIMUM_USABLE_SESSION_SECONDS = 10L
    }
}

/** Executes one-time enrollment and persists the returned credential before use. */
class GatewayEnrollmentManager(
    private val credentialStore: DeviceCredentialStore,
    private val apiFactory: (String) -> GatewayAuthService = { GatewayAuthApi(it) },
) {
    suspend fun enroll(gatewayValue: String, deviceIdValue: String, code: String) {
        check(!credentialStore.hasEnrollment()) { "This installation is already enrolled" }
        val gatewayBaseUrl = GatewayEndpoint.normalizeHttpsBase(gatewayValue)
        val deviceId = deviceIdValue.trim()
        require(DEVICE_ID_PATTERN.matches(deviceId)) { "Invalid device identifier" }
        require(code.length in 20..200) { "Invalid enrollment code" }

        val (claimedDeviceId, deviceSecret) = apiFactory(gatewayBaseUrl)
            .claimEnrollment(deviceId, code)
        credentialStore.saveNewEnrollment(claimedDeviceId, gatewayBaseUrl, deviceSecret)
    }

    companion object {
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
    }
}
