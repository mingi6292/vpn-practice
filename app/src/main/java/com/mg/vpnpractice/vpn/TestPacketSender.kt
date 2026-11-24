package com.mg.vpnpractice.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** 10.0.0.1:9999로 UDP "hello"를 한 번 보내 TUN 읽기 루프가 도는지 확인한다. 네트워크 호출이라 IO 스레드에서 부른다. */
object TestPacketSender {
    fun send() {
        val payload = "hello".toByteArray()
        // 일부러 protect()를 호출하지 않는다. protect()하면 소켓이 VPN을 우회해서 TUN으로 들어가지 않아 로그가 안 남는다.
        // 나중에 서버와 통신하는 실제 터널 소켓은 자기 패킷이 TUN으로 되돌아오는 루프를 막으려고 protect()가 필요하다.
        DatagramSocket().use {
            it.send(DatagramPacket(payload, payload.size, InetAddress.getByName("10.0.0.1"), 9999))
        }
    }
}
