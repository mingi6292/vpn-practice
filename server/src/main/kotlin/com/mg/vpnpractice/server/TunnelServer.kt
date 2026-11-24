package com.mg.vpnpractice.server

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * UDP 소켓과 TUN 사이에서 패킷을 중계한다. 데이터그램 하나가 IP 패킷 하나이고 터널 헤더는 없다.
 * 클라이언트는 하나만 지원해서, 마지막으로 데이터그램을 보낸 주소를 클라이언트로 기억한다.
 */
class TunnelServer(private val tun: Tun, private val socket: DatagramSocket) {

    // uplink 코루틴이 쓰고 downlink 코루틴이 읽는다.
    @Volatile
    private var client: SocketAddress? = null

    /** uplink(소켓 -> TUN)와 downlink(TUN -> 소켓)를 동시에 돌린다. 하나가 끝나면 나머지도 닫아서 끝낸다. */
    suspend fun run() = coroutineScope {
        listOf(
            async(Dispatchers.IO) { runLoop(::uplink) },
            async(Dispatchers.IO) { runLoop(::downlink) },
        ).awaitAll()
        Unit
    }

    /**
     * 소켓과 TUN을 닫는다. receive()/read()는 블로킹 호출이라 코루틴 취소로는 깨어나지 않고,
     * fd/소켓을 닫아야 예외나 -1로 빠져나온다. 두 번 불러도 안전하다.
     */
    fun close() {
        socket.close()
        tun.close()
    }

    private fun runLoop(loop: () -> Unit) {
        try {
            loop()
        } finally {
            close() // 한쪽 루프가 끝나면 다른 쪽의 블로킹 호출도 깨운다
        }
    }

    private fun uplink() {
        val buffer = ByteArray(BUFFER_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!socket.isClosed) {
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                if (socket.isClosed) return
                println("uplink receive error: ${e.message}")
                continue
            }
            client = packet.socketAddress
            println("uplink ${packet.length}B from ${packet.socketAddress}")
            try {
                tun.write(buffer, packet.length)
            } catch (e: IOException) {
                // 잘못된 패킷 하나 때문에 서버 전체가 멈추면 안 된다
                println("uplink write error: ${e.message}")
            }
            packet.length = buffer.size // receive() 뒤에 줄어든 길이를 되돌린다
        }
    }

    private fun downlink() {
        val buffer = ByteArray(BUFFER_SIZE)
        while (!socket.isClosed) {
            val length = try {
                tun.read(buffer)
            } catch (e: IOException) {
                if (socket.isClosed) return
                println("downlink read error: ${e.message}")
                continue
            }
            if (length < 0) return
            val target = client
            if (target == null) {
                println("drop ${length}B (no client)")
                continue
            }
            try {
                socket.send(DatagramPacket(buffer, length, target))
                println("downlink ${length}B to $target")
            } catch (e: IOException) {
                if (socket.isClosed) return
                println("downlink send error: ${e.message}")
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 32767
    }
}
