package com.mg.vpnpractice.server

import java.io.IOException

/** 서버가 패킷을 주고받는 TUN 장치. 실구현은 LinuxTun이고, 테스트에서는 가짜로 바꿔 끼운다. */
interface Tun {
    /** 블로킹 읽기. 읽은 길이를 돌려주고, 닫히면 -1 또는 IOException으로 빠져나온다. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray): Int

    /** 패킷 하나를 쓴다. 커널이 거부하면(잘못된 패킷 등) IOException. */
    @Throws(IOException::class)
    fun write(buffer: ByteArray, length: Int)

    /** 두 번 불러도 안전해야 한다. */
    fun close()
}
