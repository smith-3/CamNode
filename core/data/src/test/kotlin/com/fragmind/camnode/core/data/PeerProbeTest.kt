package com.fragmind.camnode.core.data

import com.fragmind.camnode.core.control.ControlAuth
import com.fragmind.camnode.core.control.ControlClient
import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.EventListener
import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PeerProbeTest {
    @Test
    fun preferredRouteReturnsAndCancelsAnUnresponsiveSecondaryRoute() = runBlocking {
        ServerSocket(0).use { fast ->
            ServerSocket(0).use { slow ->
                val secondaryStarted = CountDownLatch(1)
                val secondaryClosed = CountDownLatch(1)
                thread(isDaemon = true) {
                    slow.accept().use { socket ->
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
                            secondaryClosed.countDown()
                        }
                    }
                }
                thread(isDaemon = true) {
                    fast.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) { }
                        secondaryStarted.await(1, TimeUnit.SECONDS)
                        val body = """{"nodeId":"camera","role":"CAMERA","batteryPercent":80,"charging":true}"""
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                            flush()
                        }
                    }
                }
                val preferred = NodeEndpoint("127.0.0.1", controlPort = fast.localPort)
                val secondary = NodeEndpoint("camera.ts.net", controlPort = slow.localPort)
                val http = OkHttpClient.Builder()
                    .dns { listOf(java.net.InetAddress.getLoopbackAddress()) }
                    .eventListener(object : EventListener() {
                        override fun responseBodyStart(call: Call) {
                            if (call.request().url.host == "camera.ts.net") secondaryStarted.countDown()
                        }
                    })
                    .build()
                val probe = PeerProbe(ControlClient(http))
                val result = withTimeout(400) {
                    probe.probe(listOf(secondary, preferred), ControlAuth(HouseholdSecret.generate()), NodeId("camera"))
                }
                assertTrue(result is PeerReachability.Online)
                assertEquals(preferred, (result as PeerReachability.Online).route)
                assertTrue(secondaryClosed.await(1, TimeUnit.SECONDS))
            }
        }
    }
}
