package com.mg.vpnpractice.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4PacketTest {

    // 테스트용 IPv4 패킷을 만든다. ihl은 32비트 단어 수(5 = 20바이트), 옵션 영역은 0으로 채운다.
    private fun packet(
        protocol: Int,
        src: List<Int> = listOf(10, 0, 0, 2),
        dst: List<Int> = listOf(10, 0, 0, 1),
        srcPort: Int = 5555,
        dstPort: Int = 9999,
        ihl: Int = 5,
        version: Int = 4,
        withPorts: Boolean = true,
    ): ByteArray {
        val headerLen = ihl * 4
        val bytes = ByteArray(headerLen + if (withPorts) 8 else 0)
        bytes[0] = ((version shl 4) or ihl).toByte()
        bytes[9] = protocol.toByte()
        src.forEachIndexed { i, v -> bytes[12 + i] = v.toByte() }
        dst.forEachIndexed { i, v -> bytes[16 + i] = v.toByte() }
        if (withPorts) {
            bytes[headerLen] = (srcPort shr 8).toByte()
            bytes[headerLen + 1] = srcPort.toByte()
            bytes[headerLen + 2] = (dstPort shr 8).toByte()
            bytes[headerLen + 3] = dstPort.toByte()
        }
        return bytes
    }

    @Test
    fun `parses UDP packet`() {
        val bytes = packet(protocol = 17)
        val parsed = Ipv4Packet.parse(bytes, bytes.size)!!
        assertEquals("10.0.0.2", parsed.source)
        assertEquals("10.0.0.1", parsed.destination)
        assertEquals(5555, parsed.sourcePort)
        assertEquals(9999, parsed.destinationPort)
        assertEquals("UDP", parsed.protocolName())
    }

    @Test
    fun `parses TCP packet`() {
        val bytes = packet(protocol = 6, srcPort = 50000, dstPort = 443)
        val parsed = Ipv4Packet.parse(bytes, bytes.size)!!
        assertEquals("TCP", parsed.protocolName())
        assertEquals(50000, parsed.sourcePort)
        assertEquals(443, parsed.destinationPort)
    }

    @Test
    fun `ICMP has no ports`() {
        val bytes = packet(protocol = 1)
        val parsed = Ipv4Packet.parse(bytes, bytes.size)!!
        assertEquals("ICMP", parsed.protocolName())
        assertNull(parsed.sourcePort)
        assertNull(parsed.destinationPort)
    }

    @Test
    fun `unknown protocol is named by number`() {
        val bytes = packet(protocol = 47)
        assertEquals("PROTO(47)", Ipv4Packet.parse(bytes, bytes.size)!!.protocolName())
    }

    @Test
    fun `handles header options`() {
        val bytes = packet(protocol = 17, ihl = 6, srcPort = 1234, dstPort = 4321)
        val parsed = Ipv4Packet.parse(bytes, bytes.size)!!
        assertEquals(1234, parsed.sourcePort)
        assertEquals(4321, parsed.destinationPort)
    }

    @Test
    fun `returns null when shorter than 20 bytes`() {
        assertNull(Ipv4Packet.parse(ByteArray(19), 19))
    }

    @Test
    fun `returns null when length is smaller than array`() {
        val bytes = packet(protocol = 17)
        assertNull(Ipv4Packet.parse(bytes, 10))
    }

    @Test
    fun `returns null for IPv6`() {
        val bytes = packet(protocol = 17, version = 6)
        assertNull(Ipv4Packet.parse(bytes, bytes.size))
    }

    @Test
    fun `truncated UDP has null ports`() {
        val bytes = packet(protocol = 17, withPorts = false)
        val parsed = Ipv4Packet.parse(bytes, bytes.size)
        assertNotNull(parsed)
        assertNull(parsed!!.sourcePort)
        assertNull(parsed.destinationPort)
    }

    @Test
    fun `returns null when IHL is below 5`() {
        val bytes = packet(protocol = 17)
        bytes[0] = ((4 shl 4) or 4).toByte()
        assertNull(Ipv4Packet.parse(bytes, bytes.size))
    }

    @Test
    fun `returns null when header is longer than length`() {
        val bytes = packet(protocol = 17, ihl = 6, withPorts = false)
        assertNull(Ipv4Packet.parse(bytes, 20))
    }
}
