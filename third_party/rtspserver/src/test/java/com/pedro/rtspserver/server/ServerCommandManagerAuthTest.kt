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

    /**
     * Media3 encodes Basic credentials with android.util.Base64.DEFAULT, which breaks the line
     * every 76 characters and ends it with "\n". A node id plus a ticket is longer than that.
     */
    @Test
    fun describeAcceptsBasicCredentialsWrappedByMedia3() {
        val longManager = ServerCommandManager().apply {
            authenticator = { user, password -> user == NODE_ID && password == TICKET }
            // No encoder here: an empty SDP lets DESCRIBE answer once authentication passes.
            videoDisabled = true
            audioDisabled = true
        }
        val encoded = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString("$NODE_ID:$TICKET".toByteArray())
        assertTrue("credentials must span two lines to exercise the bug", encoded.contains('\n'))
        val wrapped = "Authorization: Basic $encoded\n\r\nAccept: application/sdp\r\n"

        val response = longManager.createResponse(Method.DESCRIBE, request("DESCRIBE", wrapped), 2, "1.2.3.4")

        assertTrue(response, response.startsWith("RTSP/1.0 200"))
        assertTrue(longManager.mayStream)
    }

    /**
     * The transport used to be guessed from the whole request, so credentials whose base64
     * happened to contain "udp" turned a TCP SETUP into UDP and the server answered 500.
     */
    @Test
    fun setupOverTcpStaysTcpWhateverTheCredentialsSpell() {
        val manager = ServerCommandManager().apply {
            authenticator = { _, _ -> true }
            videoDisabled = true
            audioDisabled = true
        }
        // "QXVkUDpz..." spells "UDp" in the header text itself, as a real ticket can.
        val credentials = "Authorization: Basic QXVkUDpzZWNyZXQ=\r\n"
        manager.createResponse(Method.DESCRIBE, request("DESCRIBE", credentials), 1, "1.2.3.4")

        val setup = request("SETUP", credentials + "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
        val response = manager.createResponse(Method.SETUP, setup, 2, "1.2.3.4")

        assertTrue(response, response.startsWith("RTSP/1.0 200"))
        assertTrue(response, response.contains("RTP/AVP/TCP;unicast;interleaved=0-1"))
    }

    @Test
    fun describeWithWrongOrMissingCredentialsIsRefused() {
        assertTrue(manager.createResponse(Method.DESCRIBE, request("DESCRIBE"), 2, "1.2.3.4").startsWith("RTSP/1.0 401"))
        assertTrue(manager.createResponse(Method.DESCRIBE, request("DESCRIBE", basic("viewer", "adivinanza")), 2, "1.2.3.4").startsWith("RTSP/1.0 401"))
        assertTrue(manager.createResponse(Method.DESCRIBE, request("DESCRIBE", basic("otro", "ticket")), 2, "1.2.3.4").startsWith("RTSP/1.0 401"))
    }

    private companion object {
        const val NODE_ID = "611b5776-6b93-49a9-94fd-fd254ff59fb4"
        const val TICKET = "abcdefghijklmnopqrstuvwxyz012345"
    }
}
