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
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** TUN을 열고 패킷을 읽어 IPv4 헤더를 Logcat에 남기는 연습용 VpnService. */
class PracticeVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: ParcelFileDescriptor? = null
    private var readJob: Job? = null

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
        readJob = serviceScope.launch { readLoop(fd) }
        _isRunning.value = true
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
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
                    val packet = Ipv4Packet.parse(buffer, length)
                    if (packet == null) {
                        Log.d(TAG, "ignored non-IPv4 or malformed packet ($length bytes)")
                    } else {
                        Log.i(
                            TAG,
                            "src=${packet.source} dst=${packet.destination} " +
                                "proto=${packet.protocolName()} " +
                                "port=${packet.sourcePort}->${packet.destinationPort}",
                        )
                    }
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "read loop ended: ${e.message}")
        }
    }

    private fun stopVpn() {
        _isRunning.value = false
        // fd를 먼저 닫아 블로킹 중인 read()를 깨운 뒤 job을 취소한다. 두 번 호출돼도 null이라 안전하다.
        tunFd?.close()
        tunFd = null
        readJob?.cancel()
        readJob = null
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
