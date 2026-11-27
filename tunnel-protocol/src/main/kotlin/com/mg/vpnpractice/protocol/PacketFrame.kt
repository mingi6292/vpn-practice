package com.mg.vpnpractice.protocol

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 길이 필드가 0이거나 MAX_PACKET_SIZE보다 크다. 스트림 경계가 어긋났으므로 연결을 끊어야 한다. */
class InvalidFrameException(message: String) : IOException(message)

/**
 * TCP/TLS는 바이트 스트림이라 패킷 경계가 없다.
 * 그래서 패킷마다 [길이 2바이트(big-endian)][본문] 형태로 감싸 경계를 만든다.
 */
object PacketFrame {
    // 길이 필드는 2바이트(부호 없는 16비트)다. 최대 길이는 TUN MTU보다 충분히 큰 32767로 제한한다.
    const val HEADER_SIZE = 2
    const val MAX_PACKET_SIZE = 32767

    /** length는 1..MAX_PACKET_SIZE. 벗어나면 IllegalArgumentException. 헤더와 본문을 output.write() 한 번으로 보낸다. */
    fun write(output: OutputStream, buffer: ByteArray, length: Int) {
        require(length in 1..MAX_PACKET_SIZE) { "length out of range: $length" }
        val frame = ByteArray(HEADER_SIZE + length)
        frame[0] = (length shr 8).toByte()
        frame[1] = length.toByte()
        System.arraycopy(buffer, 0, frame, HEADER_SIZE, length)
        // 한 번에 쓰는 이유: SSLSocket은 write 호출마다 TLS 레코드를 만들 수 있어서,
        // 헤더와 본문을 따로 쓰면 레코드가 둘로 쪼개져 낭비가 생긴다.
        output.write(frame)
    }

    /**
     * 프레임 하나를 buffer에 읽고 본문 길이를 돌려준다. buffer.size >= MAX_PACKET_SIZE여야 한다.
     * 프레임 경계에서 스트림이 끝나면 -1. 헤더/본문 중간에서 끝나면 EOFException. 길이가 0이거나 최대 초과면 InvalidFrameException.
     */
    fun read(input: InputStream, buffer: ByteArray): Int {
        // 첫 바이트만 직접 읽는다: -1이면 프레임 경계에서 정상 종료, 이후에 끝나면 중간 종료(EOFException)로 구분된다.
        // BufferedInputStream으로 감싸면 다음 프레임 바이트를 미리 삼키므로 감싸지 않는다.
        val high = input.read()
        if (high == -1) return -1
        val data = DataInputStream(input)
        val length = (high shl 8) or data.readUnsignedByte()
        // 잘못된 길이를 받으면 다음 프레임 경계를 다시 찾을 수 없으니 연결을 끊어야 한다.
        if (length == 0 || length > MAX_PACKET_SIZE) {
            throw InvalidFrameException("invalid frame length: $length")
        }
        data.readFully(buffer, 0, length)
        return length
    }
}
