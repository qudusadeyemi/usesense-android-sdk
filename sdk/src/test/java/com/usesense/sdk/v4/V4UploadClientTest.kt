package com.usesense.sdk.v4

import com.usesense.sdk.api.V4NetworkException
import com.usesense.sdk.api.V4VerificationRequest
import com.usesense.sdk.capture.ZoomMotionStats
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class V4UploadClientTest {
    private val request = V4VerificationRequest("session-1", "token", "nonce", "unused")
    private val stats = ZoomMotionStats(null, null, 1f, 1, 0f, 0f, 1)

    @Test
    fun `stalled signals response is bounded and phase specific`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

            val error = uploadExpectingFailure(server, V4TransportTimeouts(200, 200, 200))

            assertEquals(V4NetworkException.Kind.TIMEOUT, error.kind)
            assertEquals(V4NetworkException.Phase.SIGNALS_RESPONSE, error.phase)
        }
    }

    @Test
    fun `successful signals and result return the verdict`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody(verdictJson()))
            val verdict = upload(server)
            assertEquals("session-1", verdict.sessionId)
            assertEquals("/sessions/session-1/signals", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/sessions/session-1/result", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test
    fun `non 2xx result is structured`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val error = uploadExpectingFailure(server, V4TransportTimeouts(500, 500, 500))
            assertEquals(V4NetworkException.Kind.HTTP, error.kind)
            assertEquals(V4NetworkException.Phase.RESULT_RESPONSE, error.phase)
            assertEquals(503, error.httpStatus)
        }
    }

    @Test
    fun `stalled result response is bounded`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val error = uploadExpectingFailure(server, V4TransportTimeouts(200, 200, 200))
            assertEquals(V4NetworkException.Kind.TIMEOUT, error.kind)
            assertEquals(V4NetworkException.Phase.RESULT_RESPONSE, error.phase)
        }
    }

    @Test
    fun `stalled connect is bounded`() {
        val error = uploadWithConnectionsExpectingFailure(BlockingConnection(URL("http://unused"), Block.CONNECT))
        assertEquals(V4NetworkException.Phase.SIGNALS_CONNECT, error.phase)
    }

    @Test
    fun `stalled upload is bounded`() {
        val error = uploadWithConnectionsExpectingFailure(BlockingConnection(URL("http://unused"), Block.UPLOAD))
        assertEquals(V4NetworkException.Phase.SIGNALS_UPLOAD, error.phase)
    }

    private fun upload(server: MockWebServer) = V4UploadClient.upload(
        request.copy(apiBaseUrl = server.url("/").toString()),
        ByteArray(16), emptyList(), "hash", "signature", "key", "hardware", stats,
        V4TransportTimeouts(500, 500, 500),
    )

    private fun verdictJson() = """{"session_id":"session-1","verdict":"pass","confidence":"high","assurance_level_achieved":"hardware","timestamp":"now"}"""

    private fun uploadWithConnectionsExpectingFailure(connection: HttpURLConnection): V4NetworkException {
        try {
            V4UploadClient.upload(
                request.copy(apiBaseUrl = "http://unused"),
                ByteArray(16), emptyList(), "hash", "signature", "key", "hardware", stats,
                V4TransportTimeouts(100, 100, 100), { connection },
            )
        } catch (error: V4NetworkException) {
            return error
        }
        throw AssertionError("expected V4NetworkException")
    }

    private fun uploadExpectingFailure(
        server: MockWebServer,
        timeouts: V4TransportTimeouts,
    ): V4NetworkException {
        try {
            V4UploadClient.upload(
                request.copy(apiBaseUrl = server.url("/").toString()),
                ByteArray(16), emptyList(), "hash", "signature", "key", "hardware", stats,
                timeouts,
            )
            fail("expected V4NetworkException")
        } catch (error: V4NetworkException) {
            return error
        }
        throw AssertionError("unreachable")
    }

    private enum class Block { CONNECT, UPLOAD }

    private class BlockingConnection(url: URL, private val block: Block) : HttpURLConnection(url) {
        override fun connect() { if (block == Block.CONNECT) waitForever() }
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 204
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { if (block == Block.UPLOAD) waitForever() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { if (block == Block.UPLOAD) waitForever() }
        }
        private fun waitForever(): Nothing {
            while (true) Thread.sleep(10_000)
        }
    }
}
