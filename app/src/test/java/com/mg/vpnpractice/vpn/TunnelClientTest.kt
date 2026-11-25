package com.mg.vpnpractice.vpn

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TunnelClientTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private lateinit var server: DatagramSocket
    private lateinit var client: TunnelClient

    @Before
    fun setUp() {
        server = DatagramSocket(0, loopback).apply { soTimeout = 2000 }
        client = TunnelClient("127.0.0.1", server.localPort)
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
    }

    private fun sendToClient(from: DatagramSocket, bytes: ByteArray) =
        from.send(DatagramPacket(bytes, bytes.size, loopback, client.localPort))

    @Test
    fun send_delivers_datagram_to_server() {
        client.send(byteArrayOf(1, 2, 3), 3)

        val received = DatagramPacket(ByteArray(100), 100)
        server.receive(received)
        assertArrayEquals(byteArrayOf(1, 2, 3), received.data.copyOf(received.length))
    }

    @Test
    fun receive_returns_server_datagram() {
        sendToClient(server, byteArrayOf(4, 5))

        val buffer = ByteArray(100)
        val length = client.receive(buffer)

        assertEquals(2, length)
        assertArrayEquals(byteArrayOf(4, 5), buffer.copyOf(length))
    }

    @Test
    fun datagram_from_other_source_is_ignored() {
        DatagramSocket(0, loopback).use { other -> sendToClient(other, byteArrayOf(0)) }
        sendToClient(server, byteArrayOf(6))

        val buffer = ByteArray(100)
        val length = client.receive(buffer)

        assertEquals(1, length)
        assertEquals(6, buffer[0].toInt())
    }

    @Test
    fun close_unblocks_receive() {
        val failure = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        Thread {
            try {
                client.receive(ByteArray(100))
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                finished.countDown()
            }
        }.start()
        Thread.sleep(100) // receive()에서 블로킹될 시간

        client.close()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(failure.get() is SocketException)
    }

    @Test
    fun close_twice_is_safe() {
        client.close()
        client.close()

        assertTrue(client.isClosed)
    }

    // UDP 데이터그램 한도(65507바이트)를 넘겨 보내면 소켓이 열려 있어도 send()가 IOException을 던진다.
    private val tooLarge = ByteArray(70_000)

    @Test
    fun tolerate_reports_failure_while_socket_is_open() {
        var reported: IOException? = null

        val result = client.tolerate({ reported = it }) { send(tooLarge, tooLarge.size) }

        assertNull(result)
        assertNotNull(reported)
        assertFalse(client.isClosed)
    }

    @Test(expected = IOException::class)
    fun tolerate_rethrows_after_close() {
        client.close()

        client.tolerate({ }) { send(byteArrayOf(1), 1) }
    }
}
