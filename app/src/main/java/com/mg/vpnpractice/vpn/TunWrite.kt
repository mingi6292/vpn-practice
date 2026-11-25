package com.mg.vpnpractice.vpn

import java.io.IOException
import java.io.OutputStream

/**
 * TUN에 패킷 하나를 쓴다. 커널이 패킷 하나를 거부해도(EINVAL 등) TUN이 열려 있으면 onError로 알리고 넘어간다.
 * 그렇지 않으면 수신 루프가 끝나 버려 "실행 중"인데 응답을 못 받는 상태가 된다. TUN이 닫혀서 난 실패는 다시 던진다.
 */
fun OutputStream.writePacket(
    buffer: ByteArray,
    length: Int,
    isOpen: () -> Boolean,
    onError: (IOException) -> Unit,
) {
    try {
        write(buffer, 0, length)
    } catch (e: IOException) {
        if (!isOpen()) throw e
        onError(e)
    }
}
