package com.mg.vpnpractice.vpn

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 터널 서버와 UDP로 패킷을 주고받는다. 데이터그램 하나가 IP 패킷 하나이고 터널 헤더는 없다.
 * 소켓 생성과 송수신은 네트워크 I/O라서 반드시 IO 스레드에서 쓴다.
 */
class TunnelClient(host: String, port: Int) : Closeable {

    // 서버 주소로 connect()하면 커널이 다른 곳에서 온 데이터그램을 걸러 준다.
    // 일부러 protect()를 호출하지 않는다. 서버 주소(10.0.2.2)가 TUN 라우트(10.0.0.0/24) 밖이라
    // 이 소켓의 패킷은 TUN으로 되돌아오지 않는다. 라우트를 넓히거나 서버가 대역 안에 있으면 protect()가 필요하다(6단계).
    private val socket = DatagramSocket().apply { connect(InetAddress.getByName(host), port) }

    val localPort: Int get() = socket.localPort
    val isClosed: Boolean get() = socket.isClosed

    fun send(buffer: ByteArray, length: Int) {
        socket.send(DatagramPacket(buffer, length))
    }

    /** 블로킹 수신. 읽은 길이를 돌려주고, close()되면 SocketException으로 빠져나온다. */
    fun receive(buffer: ByteArray): Int {
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        return packet.length
    }

    /** receive()가 블로킹 중이어도 소켓을 닫으면 예외로 깨어난다. 두 번 불러도 안전하다. */
    override fun close() {
        socket.close()
    }

    companion object {
        // 에뮬레이터에서 10.0.2.2는 호스트 PC를 가리킨다.
        const val SERVER_HOST = "10.0.2.2"
        const val SERVER_PORT = 5555
    }
}
