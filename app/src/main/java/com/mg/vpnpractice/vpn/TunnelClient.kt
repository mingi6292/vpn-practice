package com.mg.vpnpractice.vpn

import com.mg.vpnpractice.protocol.PacketFrame
import java.io.Closeable
import java.net.Socket

/**
 * 연결된 소켓 위에서 IP 패킷을 PacketFrame으로 주고받는다. TLS를 모른다(TlsConnector가 연결한 SSLSocket을 받는다).
 * 송수신은 네트워크 I/O라서 반드시 IO 스레드에서 쓴다.
 *
 * TCP-over-TCP 한계: 앱의 TCP가 이 TCP 터널 위를 지나서, 손실이 나면 두 TCP가 함께 재전송하고
 * 패킷 하나가 늦으면 뒤 패킷이 모두 기다린다. 자세한 설명은 서버 TunnelServer 클래스 주석에 있다.
 */
class TunnelClient(private val socket: Socket) : Closeable {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    val isClosed: Boolean get() = socket.isClosed

    /** 패킷 하나를 프레임 하나로 보낸다. */
    fun send(buffer: ByteArray, length: Int) {
        PacketFrame.write(output, buffer, length)
    }

    /** 블로킹 수신. 본문 길이를 돌려주고, 서버가 연결을 닫으면 -1. 잘못된 길이면 InvalidFrameException. */
    fun receive(buffer: ByteArray): Int = PacketFrame.read(input, buffer)

    /** receive()가 블로킹 중이어도 소켓을 닫으면 예외로 깨어난다. 두 번 불러도 안전하다. */
    override fun close() {
        socket.close()
    }
}
