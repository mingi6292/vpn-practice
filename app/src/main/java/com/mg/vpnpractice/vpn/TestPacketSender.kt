package com.mg.vpnpractice.vpn

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** 10.0.0.1:9999로 UDP "hello"를 한 번 보내 TUN 읽기 루프가 도는지 확인한다. 네트워크 호출이라 IO 스레드에서 부른다. */
object TestPacketSender {
    /** 보내지 못하면(권한 없음 등) 앱이 죽지 않도록 예외를 던지는 대신 돌려준다. 성공하면 null. */
    fun send(host: String = "10.0.0.1"): IOException? {
        val payload = "hello".toByteArray()
        return try {
            // 일부러 protect()를 호출하지 않는다. protect()하면 소켓이 VPN을 우회해서 TUN으로 들어가지 않아 로그가 안 남는다.
            // 터널 TCP 소켓(TlsConnector)도 지금은 protect()가 없는데, 서버 주소가 TUN 라우트 밖이라 가능한 것이다.
            DatagramSocket().use {
                it.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), 9999))
            }
            null
        } catch (e: IOException) {
            e
        }
    }
}
