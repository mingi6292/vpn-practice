package com.mg.vpnpractice.vpn

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.mg.vpnpractice.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** TUN을 열고 패킷을 로그로 남기면서 UDP 터널로 서버와 주고받는 연습용 VpnService. */
class PracticeVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null
    // stopVpn()(메인 스레드)과 runTunnel()(IO 스레드)이 함께 접근한다.
    @Volatile
    private var tunnel: TunnelClient? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopVpn() else startVpn()
        // 시스템이 서비스를 죽여도 자동 재시작하지 않는다. 재시작하려면 동의 상태를 다시 확인해야 한다.
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (tunFd != null) return // 이미 실행 중

        // VPN 서비스는 사용자에게 계속 보여야 하고, 백그라운드 시작 제한을 피하려고 일찍 포그라운드로 올린다.
        startAsForeground()

        val fd = Builder()
            .setSession("vpn-practice")
            .addAddress("10.0.0.2", 24)
            // 가짜 사내 대역만 라우팅한다(split tunnel). 0.0.0.0/0으로 잡으면 폰의 일반 인터넷이 전부 끊긴다.
            .addRoute("10.0.0.0", 24)
            .setMtu(1500)
            .establish()
        if (fd == null) {
            // 사용자가 동의를 철회했거나 다른 VPN이 우선권을 가진 경우
            Log.w(TAG, "establish() returned null")
            stopVpn()
            return
        }
        tunFd = fd
        tunnelJob = serviceScope.launch { runTunnel(fd) }
        _isRunning.value = true
    }

    private suspend fun runTunnel(fd: ParcelFileDescriptor) = coroutineScope {
        // 소켓 생성은 네트워크 I/O라 메인 스레드(startVpn)가 아니라 IO 스레드인 여기서 한다.
        val client = try {
            TunnelClient(TunnelClient.SERVER_HOST, TunnelClient.SERVER_PORT)
        } catch (e: IOException) {
            Log.w(TAG, "cannot open tunnel socket: ${e.message}")
            stopVpn()
            return@coroutineScope
        }
        tunnel = client
        if (tunFd == null) {
            // 소켓을 만드는 사이에 stopVpn()이 이미 지나갔다. 아무도 닫아 주지 않으니 직접 닫는다.
            client.close()
            return@coroutineScope
        }
        launch { readLoop(fd, client) }
        launch { writeLoop(fd, client) }
    }

    /** TUN에서 읽은 패킷을 로그로 남기고 서버로 보낸다. */
    private fun readLoop(fd: ParcelFileDescriptor, client: TunnelClient) {
        val buffer = ByteArray(32767)
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                // read()는 블로킹 호출이라 코루틴 취소로는 깨어나지 않는다.
                // stopVpn()이 fd를 닫아야 read()가 IOException으로 빠져나온다.
                while (true) {
                    val length = input.read(buffer)
                    // -1은 스트림 끝이다. continue만 하면 같은 read()를 무한 반복(busy loop)하므로 루프를 끝낸다.
                    if (length < 0) break
                    if (length == 0) continue
                    logPacket("[to-server]", buffer, length)
                    // 전송 하나가 실패해도(서버 꺼짐, 권한 없음) 루프는 계속 돈다.
                    // 여기서 루프를 빠져나가면 fd가 닫혀서 "실행 중"인데 아무것도 안 되는 상태가 된다.
                    client.tolerate({ Log.w(TAG, "send failed: ${it.message}") }) { send(buffer, length) }
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "read loop ended: ${e.message}")
        }
    }

    /** 서버가 돌려보낸 패킷을 TUN에 써서 시스템이 응답으로 받게 한다. */
    private fun writeLoop(fd: ParcelFileDescriptor, client: TunnelClient) {
        val buffer = ByteArray(32767)
        // 스트림을 use{}로 닫으면 읽기 루프와 공유하는 fd까지 닫힌다. fd의 수명은 stopVpn()이 관리한다.
        val output = FileOutputStream(fd.fileDescriptor)
        try {
            while (true) {
                val length = client.tolerate({ Log.w(TAG, "receive failed: ${it.message}") }) { receive(buffer) }
                    ?: continue
                if (length == 0) continue
                logPacket("[from-server]", buffer, length)
                // IPv6처럼 파싱하지 못하는 패킷도 TUN에는 그대로 쓴다. 해석은 시스템 네트워크 스택의 몫이다.
                output.writePacket(buffer, length, isOpen = { fd.fileDescriptor.valid() }) {
                    Log.w(TAG, "tun write failed: ${it.message}")
                }
            }
        } catch (e: IOException) {
            // 소켓이 닫히면(SocketException) 또는 TUN fd가 닫히면 여기로 빠져나온다.
            Log.i(TAG, "write loop ended: ${e.message}")
        }
    }

    private fun logPacket(direction: String, buffer: ByteArray, length: Int) {
        val packet = Ipv4Packet.parse(buffer, length)
        if (packet == null) {
            Log.d(TAG, "$direction ignored non-IPv4 or malformed packet ($length bytes)")
        } else {
            Log.i(
                TAG,
                "$direction src=${packet.source} dst=${packet.destination} " +
                    "proto=${packet.protocolName()} " +
                    "port=${packet.sourcePort}->${packet.destinationPort}",
            )
        }
    }

    private fun stopVpn() {
        _isRunning.value = false
        // fd와 소켓을 먼저 닫아 블로킹 중인 read()/receive()를 깨운 뒤 job을 취소한다. 두 번 호출돼도 null이라 안전하다.
        tunFd?.close()
        tunFd = null
        tunnel?.close()
        tunnel = null
        tunnelJob?.cancel()
        tunnelJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // 다른 VPN 앱이 켜지거나 사용자가 해제하면 시스템이 이미 TUN을 회수한 상태다. 우리 쪽 정리만 한다.
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        // 채널은 API 26부터 필요하다. minSdk 24라서 Compat이 버전 분기를 대신 처리한다.
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.vpn_notification_channel))
                .build(),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
    }

    companion object {
        const val ACTION_START = "com.mg.vpnpractice.action.START"
        const val ACTION_STOP = "com.mg.vpnpractice.action.STOP"
        private const val TAG = "PracticeVpn"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        private val _isRunning = MutableStateFlow(false)

        /** UI가 실행 상태를 관찰하는 용도 */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}
