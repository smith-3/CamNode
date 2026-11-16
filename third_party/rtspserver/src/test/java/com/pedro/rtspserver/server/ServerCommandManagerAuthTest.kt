package com.pedro.rtspserver.server

import com.pedro.rtsp.rtsp.commands.Method
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Regression for the unauthenticated-stream bug: upstream only checked credentials on
 * DESCRIBE, so a client could send SETUP and PLAY directly and receive video.
 */
class ServerCommandManagerAuthTest {
    private val manager = ServerCommandManager().apply {
        authenticator = { user, password -> user == "viewer" && password == "ticket" }
    }

    private fun request(method: String, extra: String = "") =
        "$method rtsp://127.0.0.1:8554/streamid=0 RTSP/1.0\r\nCSeq: 2\r\n$extra\r\n"

    private fun basic(user: String, password: String) =
        "Authorization: Basic ${Base64.getEncoder().encodeToString("$user:$password".toByteArray())}\r\n"

    @Test
    fun setupAndPlayWithoutAuthenticatingAreRefused() {
        val transport = "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
        assertTrue(manager.createResponse(Method.SETUP, request("SETUP", transport), 2, "1.2.3.4").startsWith("RTSP/1.0 401"))
        assertTrue(manager.createResponse(Method.PLAY, request("PLAY"), 3, "1.2.3.4").startsWith("RTSP/1.0 401"))
        assertFalse("ServerClient must not start sending media", manager.mayStream)
    }

    @Test
    fun describeWithWrongOrMissingCredentialsIsRefused() {
        assertTrue(manager.createResponse(Method.DESCRIBE, request("DESCRIBE"), 2, "1.2.3.4").startsWith("RTSP/1.0 401"))
        assertTrue(manager.createResponse(Method.DESCRIBE, request("DESCRIBE", basic("viewer", "adivinanza")), 2, "1.2.3.4").startsWith("RTSP/1.0 401"))
        assertTrue(manager.createResponse(Method.DESCRIBE, request("DESCRIBE", basic("otro", "ticket")), 2, "1.2.3.4").startsWith("RTSP/1.0 401"))
    }
}
