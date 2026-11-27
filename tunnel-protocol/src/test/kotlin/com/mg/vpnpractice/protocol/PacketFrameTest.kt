package com.mg.vpnpractice.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PacketFrameTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        PacketFrame.write(out, payload, payload.size)
        return out.toByteArray()
    }

    private fun newBuffer() = ByteArray(PacketFrame.MAX_PACKET_SIZE)

    @Test
    fun round_trip_returns_same_bytes() {
        val buffer = newBuffer()
        val n = PacketFrame.read(ByteArrayInputStream(frame(bytes(1, 2, 3))), buffer)
        assertEquals(3, n)
        assertArrayEquals(bytes(1, 2, 3), buffer.copyOf(3))
    }

    @Test
    fun write_puts_big_endian_length_header() {
        assertArrayEquals(bytes(0x00, 0x03, 1, 2, 3), frame(bytes(1, 2, 3)))
    }

    @Test
    fun write_uses_single_write_call() {
        var calls = 0
        val counting = object : OutputStream() {
            override fun write(b: Int) { calls++ }
            override fun write(b: ByteArray, off: Int, len: Int) { calls++ }
        }
        PacketFrame.write(counting, bytes(1, 2, 3), 3)
        assertEquals(1, calls)
    }

    @Test
    fun multiple_frames_in_one_stream_are_read_in_order() {
        val data = frame(bytes(1)) + frame(bytes(2, 2)) + frame(bytes(3, 3, 3))
        val input = ByteArrayInputStream(data)
        val buffer = newBuffer()
        assertEquals(1, PacketFrame.read(input, buffer))
        assertEquals(2, PacketFrame.read(input, buffer))
        assertEquals(3, PacketFrame.read(input, buffer))
        assertEquals(-1, PacketFrame.read(input, buffer))
    }

    @Test
    fun one_byte_at_a_time_input_is_reassembled() {
        val source = ByteArrayInputStream(frame(bytes(1, 2, 3)))
        val slow = object : InputStream() {
            override fun read() = source.read()
            override fun read(b: ByteArray, off: Int, len: Int) =
                if (len == 0) 0 else source.read(b, off, 1)
        }
        val buffer = newBuffer()
        assertEquals(3, PacketFrame.read(slow, buffer))
        assertArrayEquals(bytes(1, 2, 3), buffer.copyOf(3))
    }

    @Test
    fun eof_at_frame_boundary_returns_minus_one() {
        assertEquals(-1, PacketFrame.read(ByteArrayInputStream(ByteArray(0)), newBuffer()))
    }

    @Test(expected = EOFException::class)
    fun eof_inside_header_throws_eof_exception() {
        PacketFrame.read(ByteArrayInputStream(bytes(0x00)), newBuffer())
    }

    @Test(expected = EOFException::class)
    fun eof_inside_body_throws_eof_exception() {
        PacketFrame.read(ByteArrayInputStream(bytes(0x00, 0x03, 1)), newBuffer())
    }

    @Test
    fun zero_length_throws_invalid_frame() {
        try {
            PacketFrame.read(ByteArrayInputStream(bytes(0x00, 0x00)), newBuffer())
            fail("InvalidFrameException expected")
        } catch (e: InvalidFrameException) {
            assertTrue(e is IOException)
        }
    }

    @Test(expected = InvalidFrameException::class)
    fun length_over_max_throws_invalid_frame() {
        PacketFrame.read(ByteArrayInputStream(bytes(0x80, 0x00)), newBuffer())
    }

    @Test
    fun max_length_frame_is_accepted() {
        val payload = ByteArray(PacketFrame.MAX_PACKET_SIZE) { it.toByte() }
        val buffer = newBuffer()
        val n = PacketFrame.read(ByteArrayInputStream(frame(payload)), buffer)
        assertEquals(PacketFrame.MAX_PACKET_SIZE, n)
        assertArrayEquals(payload, buffer)
    }

    @Test
    fun write_rejects_zero_and_over_max() {
        for (length in intArrayOf(0, PacketFrame.MAX_PACKET_SIZE + 1)) {
            try {
                PacketFrame.write(ByteArrayOutputStream(), ByteArray(PacketFrame.MAX_PACKET_SIZE + 1), length)
                fail("IllegalArgumentException expected for $length")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
