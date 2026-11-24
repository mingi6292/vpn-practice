package com.mg.vpnpractice.server

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** JNA로 호출하는 libc 함수들. Kotlin의 internal은 바이트코드에서 public이라 JNA 프록시가 접근할 수 있다. */
internal interface LibC : Library {
    fun open(path: String, flags: Int): Int
    fun close(fd: Int): Int

    // ioctl은 C에서 가변 인자 함수라 vararg로 선언한다.
    fun ioctl(fd: Int, request: NativeLong, vararg args: Any?): Int
    fun read(fd: Int, buffer: ByteArray, count: NativeLong): NativeLong
    fun write(fd: Int, buffer: ByteArray, count: NativeLong): NativeLong
    fun poll(fds: Pointer, nfds: NativeLong, timeoutMillis: Int): Int
}

/** /dev/net/tun을 열어 만든 Linux TUN 장치. 패킷 단위로 읽고 쓴다. */
class LinuxTun private constructor(private val libc: LibC, private val fd: Int) : Tun {

    @Volatile
    private var closed = false
    private val readLock = ReentrantLock()

    override fun read(buffer: ByteArray): Int = readLock.withLock {
        // 리눅스에서는 다른 스레드가 close(fd)해도 블로킹 중인 read()가 깨어나지 않는다(진행 중인 시스템 콜이 파일 참조를 쥐고 있다).
        // 그래서 poll()을 짧은 타임아웃으로 돌면서 closed 플래그를 확인하고, 읽을 게 있을 때만 read()한다.
        val pollFd = Memory(POLLFD_SIZE).apply { clear(); setInt(0, fd); setShort(4, POLLIN) }
        while (!closed) {
            val ready = libc.poll(pollFd, NativeLong(1), POLL_TIMEOUT_MS)
            if (ready < 0) {
                if (Native.getLastError() == EINTR) continue
                throw IOException("poll failed, errno=${Native.getLastError()}")
            }
            if (ready == 0) continue
            val length = libc.read(fd, buffer, NativeLong(buffer.size.toLong())).toInt()
            if (length < 0) throw IOException("read failed, errno=${Native.getLastError()}")
            return@withLock length
        }
        -1
    }

    override fun write(buffer: ByteArray, length: Int) {
        if (closed) throw IOException("tun is closed")
        val written = libc.write(fd, buffer, NativeLong(length.toLong())).toInt()
        // 잘못된 IP 패킷이면 커널이 EINVAL로 거부한다
        if (written < 0) throw IOException("write failed, errno=${Native.getLastError()}")
    }

    override fun close() {
        closed = true
        // 진행 중인 read()가 poll 타임아웃 안에 빠져나온 뒤에 fd를 닫아야 fd 번호 재사용과 겹치지 않는다.
        readLock.withLock {
            if (fdClosed) return
            fdClosed = true
            libc.close(fd)
        }
    }

    private var fdClosed = false

    companion object {
        private const val O_RDWR = 2
        private const val TUNSETIFF = 0x400454caL
        private const val IFF_TUN: Short = 0x0001
        // IFF_NO_PI가 없으면 패킷 앞에 4바이트 정보 헤더가 붙어서 앱의 Ipv4Packet.parse와 형식이 달라진다.
        private const val IFF_NO_PI: Short = 0x1000
        private const val IFREQ_SIZE = 40L // struct ifreq: 이름 16바이트 + 플래그 2바이트 + 패딩
        private const val IFNAMSIZ = 16
        private const val POLLFD_SIZE = 8L // struct pollfd: int fd, short events, short revents
        private const val POLLIN: Short = 0x0001
        private const val POLL_TIMEOUT_MS = 200
        private const val EINTR = 4

        fun open(name: String): LinuxTun {
            require(name.length < IFNAMSIZ) { "interface name too long: $name" }
            val libc = Native.load("c", LibC::class.java)
            val fd = libc.open("/dev/net/tun", O_RDWR)
            if (fd < 0) throw IOException("open /dev/net/tun failed, errno=${Native.getLastError()}")

            val ifreq = Memory(IFREQ_SIZE).apply {
                clear()
                setString(0, name)
                setShort(IFNAMSIZ.toLong(), (IFF_TUN.toInt() or IFF_NO_PI.toInt()).toShort())
            }
            if (libc.ioctl(fd, NativeLong(TUNSETIFF), ifreq) < 0) {
                val errno = Native.getLastError()
                libc.close(fd)
                throw IOException("ioctl(TUNSETIFF) failed, errno=$errno")
            }
            return LinuxTun(libc, fd)
        }
    }
}
