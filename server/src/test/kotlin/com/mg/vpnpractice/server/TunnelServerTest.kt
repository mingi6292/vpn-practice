package com.mg.vpnpractice.server

import com.mg.vpnpractice.protocol.PacketFrame
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** TUN 읽기는 큐로, 쓰기는 리스트로 흉내 낸다. close()하면 read()가 -1로 깨어난다. */
private class FakeTun : Tun {
    private val toRead = LinkedBlockingQueue<ByteArray>()
    val written = CopyOnWriteArrayList<ByteArray>()
    @Volatile var failNextWrites = 0
    @Volatile private var closed = false

    fun feed(packet: ByteArray) = toRead.put(packet)

    override fun read(buffer: ByteArray): Int {
        while (!closed) {
            val packet = toRead.poll(50, TimeUnit.MILLISECONDS) ?: continue
            packet.copyInto(buffer)
            return packet.size
        }
        return -1
    }

    override fun write(buffer: ByteArray, length: Int) {
        if (failNextWrites > 0) {
            failNextWrites--
            throw IOException("fake write failure")
        }
        written.add(buffer.copyOf(length))
    }

    override fun close() {
        closed = true
    }
}

class TunnelServerTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val tun = FakeTun()
    private val serverSocket = ServerSocket(0, 50, loopback)

    // 테스트마다 핸드셰이크 동작을 바꿀 수 있게 서버는 이 변수를 거쳐 부른다.
    @Volatile private var handshake: (Socket) -> Unit = {}
    private val server = TunnelServer(tun, serverSocket) { handshake(it) }
    private val clients = mutableListOf<Socket>()
    private val releaseHandshake = CountDownLatch(1)
    private lateinit var runJob: Job

    private fun newClient() =
        Socket(loopback, serverSocket.localPort).apply { soTimeout = 2000 }.also { clients += it }

    private fun Socket.sendFrame(vararg bytes: Byte) = PacketFrame.write(getOutputStream(), bytes, bytes.size)

    private fun Socket.readFrame(): ByteArray {
        val buffer = ByteArray(PacketFrame.MAX_PACKET_SIZE)
        val length = PacketFrame.read(getInputStream(), buffer)
        return buffer.copyOf(length)
    }

    /** 서버가 이 연결을 닫았는지 본다. 읽기 타임아웃은 "닫히지 않았다"는 뜻이라 실패로 친다. */
    private fun Socket.assertClosedByServer() {
        try {
            assertEquals(-1, getInputStream().read())
        } catch (e: SocketTimeoutException) {
            fail("connection was not closed by server")
        } catch (e: IOException) {
            // 서버가 RST로 닫으면 IOException이 난다. 이것도 닫힌 것이다.
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assert(condition()) { "condition not met within 2s" }
    }

    @Before
    fun start() {
        runJob = CoroutineScope(Dispatchers.IO).launch { server.run() }
    }

    @After
    fun stop() {
        server.close()
        // 멈춘 핸드셰이크는 close() 뒤에 풀어 준다. 그 연결이 닫힌 서버에 남지 않는지도 함께 확인된다.
        releaseHandshake.countDown()
        clients.forEach { it.close() }
        runBlocking { withTimeout(2000) { runJob.join() } }
    }

    @Test
    fun frame_from_client_is_written_to_tun() {
        newClient().sendFrame(1, 2, 3)

        waitUntil { tun.written.size == 1 }
        assertArrayEquals(byteArrayOf(1, 2, 3), tun.written[0])
    }

    @Test
    fun tun_packet_is_framed_to_current_client() {
        val client = newClient()
        client.sendFrame(1)
        waitUntil { tun.written.size == 1 } // uplink가 돌면 이미 현재 연결이다

        tun.feed(byteArrayOf(9, 8))

        assertArrayEquals(byteArrayOf(9, 8), client.readFrame())
    }

    @Test
    fun tun_packet_is_dropped_without_client() {
        tun.feed(byteArrayOf(7))
        Thread.sleep(200) // downlink가 패킷을 읽고 버릴 시간

        val client = newClient()
        client.sendFrame(4)
        waitUntil { tun.written.size == 1 }
        tun.feed(byteArrayOf(5))

        // 버려진 [7]이 아니라 이후의 [5]가 도착해야 한다
        assertArrayEquals(byteArrayOf(5), client.readFrame())
    }

    @Test
    fun new_connection_replaces_previous() {
        val a = newClient()
        a.sendFrame(1)
        waitUntil { tun.written.size == 1 }
        val b = newClient()
        b.sendFrame(2)
        waitUntil { tun.written.size == 2 }

        a.assertClosedByServer()
        tun.feed(byteArrayOf(3))
        assertArrayEquals(byteArrayOf(3), b.readFrame())
    }

    @Test
    fun failed_handshake_keeps_current_connection() {
        val calls = AtomicInteger()
        handshake = { if (calls.incrementAndGet() == 2) throw IOException("fake handshake failure") }
        val a = newClient()
        a.sendFrame(1)
        waitUntil { tun.written.size == 1 }

        val bad = newClient()
        bad.assertClosedByServer()

        tun.feed(byteArrayOf(3))
        assertArrayEquals(byteArrayOf(3), a.readFrame())
    }

    @Test
    fun stalled_handshake_does_not_block_accept() {
        val calls = AtomicInteger()
        handshake = { if (calls.incrementAndGet() == 1) releaseHandshake.await(5, TimeUnit.SECONDS) }
        newClient() // 핸드셰이크에서 멈추는 연결
        waitUntil { calls.get() == 1 }

        val b = newClient()
        b.sendFrame(2)
        waitUntil { tun.written.size == 1 }

        tun.feed(byteArrayOf(6))
        assertArrayEquals(byteArrayOf(6), b.readFrame())
    }

    @Test
    fun invalid_length_closes_only_that_connection() {
        val a = newClient()
        a.getOutputStream().write(byteArrayOf(0x00, 0x00)) // 길이 0은 잘못된 프레임

        a.assertClosedByServer()

        val b = newClient()
        b.sendFrame(5)
        waitUntil { tun.written.size == 1 }
        tun.feed(byteArrayOf(6))
        assertArrayEquals(byteArrayOf(6), b.readFrame())
    }

    @Test
    fun tun_write_failure_keeps_connection() {
        tun.failNextWrites = 1
        val client = newClient()
        client.sendFrame(1)
        client.sendFrame(2)

        waitUntil { tun.written.size == 1 }
        assertArrayEquals(byteArrayOf(2), tun.written[0])
    }

    @Test
    fun close_ends_run() {
        server.close()

        runBlocking { withTimeout(2000) { runJob.join() } }
        assertEquals(true, runJob.isCompleted)
    }
}
