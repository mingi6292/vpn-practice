package com.mg.vpnpractice.vpn

import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TunWriteTest {

    /** 첫 바이트가 0인 패킷은 커널이 EINVAL로 거부하는 것처럼 실패시키고, 나머지는 기록한다. */
    private class RejectingOutput : OutputStream() {
        val written = mutableListOf<List<Byte>>()

        override fun write(b: Int) = error("패킷 단위로만 써야 한다")

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (b[off] == 0.toByte()) throw IOException("write failed: EINVAL")
            written.add(b.copyOfRange(off, off + len).toList())
        }
    }

    private val output = RejectingOutput()

    @Test
    fun write_failure_while_open_is_reported_and_next_packet_is_written() {
        var reported: IOException? = null

        output.writePacket(byteArrayOf(0), 1, isOpen = { true }) { reported = it }
        output.writePacket(byteArrayOf(4, 5), 2, isOpen = { true }) { }

        assertNotNull(reported)
        assertEquals(listOf(listOf<Byte>(4, 5)), output.written)
    }

    @Test(expected = IOException::class)
    fun write_failure_after_close_is_rethrown() {
        output.writePacket(byteArrayOf(0), 1, isOpen = { false }) { }
    }
}
