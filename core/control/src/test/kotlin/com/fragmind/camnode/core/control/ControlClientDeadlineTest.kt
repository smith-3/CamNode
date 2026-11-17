package com.fragmind.camnode.core.control

import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.NodeEndpoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class ControlClientDeadlineTest {
    @Test
    fun aResponseThatKeepsDrippingBytesStillHitsTheTotalDeadline() = runBlocking {
        ServerSocket(0).use { server ->
            val sender = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) { }
                    val output = socket.getOutputStream()
                    try {
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 200\r\n\r\n".toByteArray())
                        repeat(150) {
                            output.write(' '.code)
                            output.flush()
                            Thread.sleep(100)
                        }
                    } catch (_: java.io.IOException) {
                        // The client closes the slow response when its call deadline expires.
                    }
                }
            }
            val client = ControlClient(OkHttpClient.Builder().readTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build())
            val result = withTimeout(12_000) {
                client.status(
                    NodeEndpoint("127.0.0.1", controlPort = server.localPort),
                    ControlAuth(HouseholdSecret.generate()),
                )
            }
            assertTrue(result is ControlResult.Unreachable)
            sender.join(2_000)
            assertTrue("The call must close the response before its body finishes", !sender.isAlive)
        }
    }
}
