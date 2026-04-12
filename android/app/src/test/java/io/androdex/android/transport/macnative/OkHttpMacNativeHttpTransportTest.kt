package io.androdex.android.transport.macnative

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpMacNativeHttpTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var serverTarget: MacNativeServerTarget
    private val okHttpClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        serverTarget = createMacNativeServerTarget(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun authTransport_bootstrapBearerSession_postsCredentialAndParsesResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                    {
                      "authenticated": true,
                      "role": "client",
                      "sessionMethod": "bearer-session-token",
                      "expiresAt": "2026-04-12T12:00:00Z",
                      "sessionToken": "session-token-1"
                    }
                """.trimIndent(),
            ),
        )

        val transport = OkHttpMacNativeAuthHttpTransport(okHttpClient)
        val session = transport.bootstrapBearerSession(serverTarget, "credential-1")

        assertEquals("session-token-1", session.sessionToken)
        assertEquals("client", session.role)
        assertTrue(session.expiresAtEpochMs != null)

        val recordedRequest = server.takeRequest()
        assertEquals("/api/auth/bootstrap/bearer", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
        assertEquals(
            "credential-1",
            JSONObject(recordedRequest.body.readUtf8()).getString("credential"),
        )
    }

    @Test
    fun authTransport_readSession_includesBearerHeader() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                    {
                      "authenticated": true,
                      "auth": {
                        "policy": "remote-reachable"
                      },
                      "role": "client",
                      "sessionMethod": "bearer-session-token",
                      "expiresAt": "2026-04-12T13:00:00Z"
                    }
                """.trimIndent(),
            ),
        )

        val transport = OkHttpMacNativeAuthHttpTransport(okHttpClient)
        val state = transport.readSession(serverTarget, "bearer-123")

        assertTrue(state.authenticated)
        assertEquals("client", state.role)
        assertEquals("bearer-session-token", state.sessionMethod)

        val recordedRequest = server.takeRequest()
        assertEquals("/api/auth/session", recordedRequest.path)
        assertEquals("Bearer bearer-123", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun authTransport_issueWebSocketToken_postsAuthorizedRequest() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                    {
                      "token": "ws-token-1",
                      "expiresAt": "2026-04-12T14:00:00Z"
                    }
                """.trimIndent(),
            ),
        )

        val transport = OkHttpMacNativeAuthHttpTransport(okHttpClient)
        val token = transport.issueWebSocketToken(
            MacNativeBearerSession(
                serverTarget = serverTarget,
                sessionToken = "bearer-456",
                role = "client",
                expiresAtEpochMs = null,
            ),
        )

        assertEquals("ws-token-1", token.token)
        assertTrue(token.expiresAtEpochMs != null)

        val recordedRequest = server.takeRequest()
        assertEquals("/api/auth/ws-token", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
        assertEquals("Bearer bearer-456", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun orchestrationTransport_dispatchCommand_postsAuthorizedJson() = runTest {
        server.enqueue(MockResponse().setBody("""{"sequence":42}"""))

        val transport = OkHttpMacNativeOrchestrationHttpTransport(okHttpClient)
        val response = transport.dispatchCommand(
            session = MacNativeBearerSession(
                serverTarget = serverTarget,
                sessionToken = "bearer-789",
                role = "owner",
                expiresAtEpochMs = null,
            ),
            command = JSONObject()
                .put("type", "thread.turn.interrupt")
                .put("threadId", "thread-1"),
        )

        assertEquals(42, response.getInt("sequence"))

        val recordedRequest = server.takeRequest()
        assertEquals("/api/orchestration/dispatch", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
        assertEquals("Bearer bearer-789", recordedRequest.getHeader("Authorization"))
        assertEquals("thread.turn.interrupt", JSONObject(recordedRequest.body.readUtf8()).getString("type"))
    }

    @Test
    fun orchestrationTransport_fetchSnapshot_getsAuthorizedSnapshot() = runTest {
        server.enqueue(MockResponse().setBody("""{"snapshotSequence":17,"threads":[]}"""))

        val transport = OkHttpMacNativeOrchestrationHttpTransport(okHttpClient)
        val snapshot = transport.fetchSnapshot(
            session = MacNativeBearerSession(
                serverTarget = serverTarget,
                sessionToken = "bearer-snapshot",
                role = "owner",
                expiresAtEpochMs = null,
            ),
        )

        assertEquals(17, snapshot.getInt("snapshotSequence"))

        val recordedRequest = server.takeRequest()
        assertEquals("/api/orchestration/snapshot", recordedRequest.path)
        assertEquals("GET", recordedRequest.method)
        assertEquals("Bearer bearer-snapshot", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun executeJsonRequest_surfacesServerErrorMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))

        val transport = OkHttpMacNativeAuthHttpTransport(okHttpClient)
        try {
            transport.readSession(serverTarget, "bad-token")
        } catch (error: MacNativeHttpException) {
            assertEquals(403, error.statusCode)
            assertEquals("forbidden", error.message)
            return@runTest
        }

        throw AssertionError("Expected MacNativeHttpException.")
    }
}
