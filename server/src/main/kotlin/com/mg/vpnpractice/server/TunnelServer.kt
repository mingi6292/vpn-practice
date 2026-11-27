package com.mg.vpnpractice.server

import com.mg.vpnpractice.protocol.PacketFrame
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * TCP(+TLS) 연결과 TUN 사이에서 패킷을 중계한다. 패킷 하나는 PacketFrame 하나로 감싼다.
 * 클라이언트는 하나만 지원해서, 마지막으로 핸드셰이크에 성공한 연결을 현재 연결로 쓴다.
 *
 * TCP-over-TCP 한계: 앱 안의 TCP가 바깥 TCP 터널 위를 지난다. 바깥에서 손실이 나면 두 TCP가 함께
 * 재전송해서 지연이 커지고(TCP meltdown), 패킷 하나가 늦으면 뒤의 패킷이 모두 기다린다(head-of-line blocking).
 * 에뮬레이터와 PC loopback 사이에는 손실이 거의 없어 이 문제가 보이지 않는다. 실제 VPN이 UDP를 쓰는 이유다.
 */
class TunnelServer(
    private val tun: Tun,
    private val serverSocket: ServerSocket,
    /** accept 직후, 이 연결을 현재 연결로 삼기 전에 부른다. 던지면 그 연결만 닫는다. Main은 tlsHandshake를 넘긴다. */
    private val handshake: (Socket) -> Unit = {},
) {

    // 연결 코루틴이 바꾸고 downlink 코루틴이 읽는다.
    private val current = AtomicReference<Socket?>(null)

    /** accept 루프와 downlink(TUN -> 소켓) 루프를 돌린다. 하나가 끝나면 close()해서 나머지도 끝낸다. */
    suspend fun run() = coroutineScope {
        launch(Dispatchers.IO) { runLoop(::downlink) }
        launch(Dispatchers.IO) { runLoop { acceptLoop(this@coroutineScope) } }
        Unit
    }

    /**
     * serverSocket, 현재 연결, TUN을 닫는다. accept()/read()는 블로킹 호출이라 코루틴 취소로는 깨어나지 않고,
     * 소켓/fd를 닫아야 예외나 -1로 빠져나온다. 두 번 불러도 안전하다.
     */
    fun close() {
        serverSocket.close()
        current.getAndSet(null)?.close()
        tun.close()
    }

    private fun runLoop(loop: () -> Unit) {
        try {
            loop()
        } finally {
            close() // 한쪽 루프가 끝나면 다른 쪽의 블로킹 호출도 깨운다
        }
    }

    private fun acceptLoop(scope: CoroutineScope) {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (serverSocket.isClosed) return
                println("accept error: ${e.message}")
                continue
            }
            // 핸드셰이크는 연결마다 따로 한다. 잘못된 인증서로 접속하거나 중간에 멈춘 클라이언트가
            // accept 루프와 정상 연결을 막지 않게 하려는 것이다.
            scope.launch(Dispatchers.IO) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            // 패킷 하나를 바로 보내야 한다. Nagle 알고리즘이 작은 패킷을 모아 두면 안쪽 TCP/DNS 지연이 늘어난다.
            socket.tcpNoDelay = true
            handshake(socket)
        } catch (e: Exception) {
            println("handshake failed from ${socket.remoteSocketAddress}: ${e.message}")
            socket.close()
            return
        }
        // 마지막 연결 우선: keepalive가 없어서 에뮬레이터가 비정상 종료되면 이전 연결의 FIN이 오지 않는다.
        // 이전 연결을 계속 붙잡으면 새 연결을 영영 못 쓰므로, 새 연결로 바꾸고 이전 것은 닫는다.
        current.getAndSet(socket)?.close()
        // close()가 위의 교체보다 먼저 current를 비웠다면 이 소켓을 닫을 사람이 없으니 여기서 닫는다.
        if (serverSocket.isClosed) socket.close()
        println("connected ${socket.remoteSocketAddress}")
        uplink(socket)
    }

    /** 프레임 읽기 -> TUN 쓰기. 연결이 끝나거나 잘못된 프레임이 오면 그 연결만 닫는다. */
    private fun uplink(socket: Socket) {
        val buffer = ByteArray(PacketFrame.MAX_PACKET_SIZE)
        try {
            // 교체나 close()로 이미 닫힌 소켓이면 여기서 IOException이 난다. 아래 catch가 받아야 run()이 죽지 않는다.
            val input = socket.getInputStream()
            while (true) {
                val length = PacketFrame.read(input, buffer) // InvalidFrameException도 IOException이다
                if (length < 0) break
                println("uplink ${length}B from ${socket.remoteSocketAddress}")
                try {
                    tun.write(buffer, length)
                } catch (e: IOException) {
                    // 잘못된 패킷 하나 때문에 연결 전체를 끊으면 안 된다
                    println("uplink write error: ${e.message}")
                }
            }
        } catch (e: IOException) {
            println("uplink read error from ${socket.remoteSocketAddress}: ${e.message}")
        } finally {
            socket.close()
            // 이미 새 연결로 바뀌었다면 그 연결을 지우지 않는다.
            current.compareAndSet(socket, null)
            println("disconnected ${socket.remoteSocketAddress}")
        }
    }

    private fun downlink() {
        val buffer = ByteArray(PacketFrame.MAX_PACKET_SIZE)
        while (!serverSocket.isClosed) {
            val length = try {
                tun.read(buffer)
            } catch (e: IOException) {
                if (serverSocket.isClosed) return
                println("downlink read error: ${e.message}")
                continue
            }
            if (length < 0) return
            // 빈 패킷은 프레임으로 만들 수 없어(write가 예외) 서버가 죽으니 건너뛴다.
            if (length == 0) continue
            val socket = current.get()
            if (socket == null) {
                println("drop ${length}B (no client)")
                continue
            }
            try {
                PacketFrame.write(socket.getOutputStream(), buffer, length)
                println("downlink ${length}B to ${socket.remoteSocketAddress}")
            } catch (e: IOException) {
                // 교체되거나 끊겨서 막 닫힌 소켓일 수 있다. 서버는 계속 돈다.
                println("downlink write error: ${e.message}")
            }
        }
    }
}
