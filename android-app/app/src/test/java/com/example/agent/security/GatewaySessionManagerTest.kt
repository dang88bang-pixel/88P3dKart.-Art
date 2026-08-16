package com.example.agent.security

import com.example.agent.network.GatewayAuthService
import com.example.agent.network.GatewaySession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewaySessionManagerTest {
    @Test
    fun `session is cached and explicit invalidation renews it`() = runBlocking {
        val store = FakeCredentialStore().apply {
            credential = StoredDeviceCredential(
                "CT45P-01",
                "https://gateway.example/",
                "s".repeat(43),
            )
        }
        var calls = 0
        val api = object : GatewayAuthService {
            override suspend fun claimEnrollment(
                deviceId: String,
                enrollmentCode: String,
            ): Pair<String, String> = error("not used")

            override suspend fun createSession(
                deviceId: String,
                deviceSecret: String,
            ): GatewaySession {
                calls += 1
                return GatewaySession("token-$calls".repeat(5), 1_000, "Bearer")
            }
        }
        val manager = GatewaySessionManager(store, { 100 }) { api }

        assertEquals(manager.validSession(), manager.validSession())
        assertEquals(1, calls)
        manager.invalidateSession()
        assertNotNull(manager.validSession())
        assertEquals(2, calls)
    }

    @Test
    fun `one-time enrollment normalizes HTTPS and stores returned secret`() = runBlocking {
        val store = FakeCredentialStore()
        val api = object : GatewayAuthService {
            override suspend fun claimEnrollment(
                deviceId: String,
                enrollmentCode: String,
            ): Pair<String, String> {
                assertEquals("CT45P-01", deviceId)
                return deviceId to "d".repeat(43)
            }

            override suspend fun createSession(
                deviceId: String,
                deviceSecret: String,
            ): GatewaySession = error("not used")
        }
        val manager = GatewayEnrollmentManager(store) { api }
        manager.enroll("https://gateway.example", " CT45P-01 ", "c".repeat(32))

        assertEquals("https://gateway.example/", store.credential?.gatewayBaseUrl)
        assertEquals("d".repeat(43), store.credential?.deviceSecret)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                manager.enroll(
                    "https://gateway.example",
                    "CT45P-01",
                    "c".repeat(32),
                )
            }
        }
    }

    private class FakeCredentialStore : DeviceCredentialStore {
        var credential: StoredDeviceCredential? = null

        override fun hasEnrollment(): Boolean = credential != null

        override fun saveNewEnrollment(
            deviceId: String,
            gatewayBaseUrl: String,
            deviceSecret: String,
        ) {
            check(credential == null)
            credential = StoredDeviceCredential(deviceId, gatewayBaseUrl, deviceSecret)
        }

        override fun load(): StoredDeviceCredential? = credential

        override fun clearEnrollment() {
            credential = null
        }
    }
}
