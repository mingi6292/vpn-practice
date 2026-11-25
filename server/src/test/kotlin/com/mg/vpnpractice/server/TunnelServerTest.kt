package com.mg.vpnpractice.server

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    private val serverSocket = DatagramSocket(0, loopback)
    private val server = TunnelServer(tun, serverSocket)
    private val clients = mutableListOf<DatagramSocket>()
    private lateinit var runJob: Job

    private fun newClient() = DatagramSocket(0, loopback).apply { soTimeout = 2000 }.also { clients += it }

    private fun DatagramSocket.sendToServer(bytes: ByteArray) =
        send(DatagramPacket(bytes, bytes.size, loopback, serverSocket.localPort))

    private fun DatagramSocket.receiveBytes(): ByteArray {
        val packet = DatagramPacket(ByteArray(100), 100)
        receive(packet)
        return packet.data.copyOf(packet.length)
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
        clients.forEach { it.close() }
        runBlocking { withTimeout(2000) { runJob.join() } }
    }

    @Test
    fun datagram_is_written_to_tun() {
        newClient().sendToServer(byteArrayOf(1, 2, 3))

        waitUntil { tun.written.size == 1 }
        assertArrayEquals(byteArrayOf(1, 2, 3), tun.written[0])
    }

    @Test
    fun tun_packet_is_sent_to_last_client() {
        val client = newClient()
        client.sendToServer(byteArrayOf(1))
        waitUntil { tun.written.size == 1 }

        tun.feed(byteArrayOf(9, 8))

        assertArrayEquals(byteArrayOf(9, 8), client.receiveBytes())
    }

    @Test
    fun tun_packet_before_any_client_is_dropped() {
        tun.feed(byteArrayOf(7))
        Thread.sleep(200) // downlink가 패킷을 읽고 버릴 시간

        val client = newClient()
        client.sendToServer(byteArrayOf(4))
        waitUntil { tun.written.size == 1 }
        tun.feed(byteArrayOf(5))

        // 버려진 [7]이 아니라 이후의 [5]가 도착해야 한다
        assertArrayEquals(byteArrayOf(5), client.receiveBytes())
    }

    @Test
    fun client_address_follows_latest_sender() {
        val a = newClient()
        val b = newClient()
        a.sendToServer(byteArrayOf(1))
        waitUntil { tun.written.size == 1 }
        b.sendToServer(byteArrayOf(2))
        waitUntil { tun.written.size == 2 }

        tun.feed(byteArrayOf(3))

        assertArrayEquals(byteArrayOf(3), b.receiveBytes())
    }

    @Test
    fun tun_write_failure_does_not_stop_relay() {
        tun.failNextWrites = 1
        val client = newClient()
        client.sendToServer(byteArrayOf(1))
        Thread.sleep(100)
        client.sendToServer(byteArrayOf(2))

        waitUntil { tun.written.size == 1 }
        assertArrayEquals(byteArrayOf(2), tun.written[0])
    }

    @Test
    fun close_stops_run() {
        server.close()

        runBlocking { withTimeout(2000) { runJob.join() } }
        assertEquals(true, runJob.isCompleted)
    }
}
