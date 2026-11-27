package com.mg.vpnpractice.vpn

import com.mg.vpnpractice.protocol.InvalidFrameException
import com.mg.vpnpractice.protocol.PacketFrame
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TunnelClientTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private lateinit var listener: ServerSocket
    private lateinit var server: Socket
    private lateinit var client: TunnelClient

    @Before
    fun setUp() {
        listener = ServerSocket(0, 50, loopback)
        val socket = Socket(loopback, listener.localPort)
        server = listener.accept().apply { soTimeout = 2000 }
        client = TunnelClient(socket)
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
        listener.close()
    }

    @Test
    fun send_writes_one_frame() {
        client.send(byteArrayOf(1, 2, 3), 3)

        val buffer = ByteArray(PacketFrame.MAX_PACKET_SIZE)
        val length = PacketFrame.read(server.getInputStream(), buffer)
        assertArrayEquals(byteArrayOf(1, 2, 3), buffer.copyOf(length))
    }

    @Test
    fun receive_returns_frame_payload() {
        PacketFrame.write(server.getOutputStream(), byteArrayOf(9, 8), 2)

        val buffer = ByteArray(PacketFrame.MAX_PACKET_SIZE)
        val length = client.receive(buffer)

        assertEquals(2, length)
        assertArrayEquals(byteArrayOf(9, 8), buffer.copyOf(length))
    }

    @Test
    fun receive_returns_minus_one_when_server_closes() {
        server.close()

        assertEquals(-1, client.receive(ByteArray(PacketFrame.MAX_PACKET_SIZE)))
    }

    @Test(expected = InvalidFrameException::class)
    fun receive_throws_on_invalid_length() {
        server.getOutputStream().write(byteArrayOf(0x00, 0x00))

        client.receive(ByteArray(PacketFrame.MAX_PACKET_SIZE))
    }

    @Test
    fun close_unblocks_receive() {
        // receive()가 IOException으로 빠져나오든 -1을 돌려주든 블로킹에서 풀리기만 하면 된다.
        val outcome = AtomicReference<Any?>()
        val finished = CountDownLatch(1)
        Thread {
            try {
                outcome.set(client.receive(ByteArray(PacketFrame.MAX_PACKET_SIZE)))
            } catch (e: Throwable) {
                outcome.set(e)
            } finally {
                finished.countDown()
            }
        }.start()
        Thread.sleep(100) // receive()에서 블로킹될 시간

        client.close()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        val result = outcome.get()
        assertTrue("unexpected: $result", result is IOException || result == -1)
    }

    @Test
    fun close_twice_is_safe() {
        client.close()
        client.close()

        assertTrue(client.isClosed)
    }
}
