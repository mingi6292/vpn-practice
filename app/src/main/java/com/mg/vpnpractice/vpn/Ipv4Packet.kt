package com.mg.vpnpractice.vpn

/**
 * TUN에서 읽은 IPv4 패킷의 헤더 정보. 안드로이드에 의존하지 않는 순수 로직이다.
 * 포트는 TCP/UDP이고 포트 바이트까지 온전히 읽힌 경우에만 채워진다.
 */
data class Ipv4Packet(
    val source: String,
    val destination: String,
    val protocol: Int,
    val sourcePort: Int?,
    val destinationPort: Int?,
) {
    fun protocolName(): String = when (protocol) {
        PROTOCOL_ICMP -> "ICMP"
        PROTOCOL_TCP -> "TCP"
        PROTOCOL_UDP -> "UDP"
        else -> "PROTO($protocol)"
    }

    companion object {
        const val PROTOCOL_ICMP = 1
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17

        private const val MIN_HEADER_LENGTH = 20

        /** IPv4가 아니거나 길이가 모자라면 예외 대신 null을 반환한다. */
        fun parse(bytes: ByteArray, length: Int): Ipv4Packet? {
            if (length < MIN_HEADER_LENGTH || length > bytes.size) return null

            val first = u8(bytes, 0)
            val version = first shr 4
            val ihl = first and 0x0F // 헤더 길이, 4바이트 단위
            if (version != 4 || ihl < 5) return null
            val headerLength = ihl * 4
            if (headerLength > length) return null

            val protocol = u8(bytes, 9)
            val hasPorts = protocol == PROTOCOL_TCP || protocol == PROTOCOL_UDP
            // 옵션 헤더가 있으면 포트 위치가 밀리므로 20이 아니라 headerLength 기준이다.
            val portsPresent = hasPorts && headerLength + 4 <= length
            return Ipv4Packet(
                source = address(bytes, 12),
                destination = address(bytes, 16),
                protocol = protocol,
                sourcePort = if (portsPresent) u16(bytes, headerLength) else null,
                destinationPort = if (portsPresent) u16(bytes, headerLength + 2) else null,
            )
        }

        // Kotlin의 Byte는 부호가 있어서 0xFF(255)가 -1이 된다. and 0xFF로 0~255로 되돌린다.
        private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

        private fun u16(bytes: ByteArray, index: Int): Int =
            (u8(bytes, index) shl 8) or u8(bytes, index + 1)

        private fun address(bytes: ByteArray, index: Int): String =
            (0 until 4).joinToString(".") { u8(bytes, index + it).toString() }
    }
}
