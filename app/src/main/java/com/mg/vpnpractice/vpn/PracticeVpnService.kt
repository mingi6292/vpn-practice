package com.mg.vpnpractice.vpn

import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.mg.vpnpractice.R
import com.mg.vpnpractice.protocol.PacketFrame
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** TUN을 열고 패킷을 로그로 남기면서 TLS 터널로 서버와 주고받는 연습용 VpnService. */
class PracticeVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null
    // stopVpn()(메인 스레드)과 runTunnel()(IO 스레드)이 함께 접근한다.
    @Volatile
    private var tunnel: TunnelClient? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
        } else {
            // extra 없이 시작된 경우(Always-on 등) 기본 프리셋으로 시작한다.
            startVpn(PolicyPreset.fromName(intent?.getStringExtra(EXTRA_PRESET)))
        }
        // 시스템이 서비스를 죽여도 자동 재시작하지 않는다. 재시작하려면 동의 상태를 다시 확인해야 한다.
        return START_NOT_STICKY
    }

    private fun startVpn(preset: PolicyPreset) {
        if (tunFd != null) return // 이미 실행 중. 정책을 바꾸려면 중지 후 다시 시작한다.

        // VPN 서비스는 사용자에게 계속 보여야 하고, 백그라운드 시작 제한을 피하려고 일찍 포그라운드로 올린다.
        startAsForeground()

        val policy = when (val result = preset.policy.resolve(::isInstalled)) {
            is PolicyResult.Reject -> {
                Log.w(TAG, "policy=${preset.name} rejected: ${result.reason}")
                stopVpn()
                return
            }
            is PolicyResult.Apply -> result
        }
        policy.skipped.forEach { Log.w(TAG, "app not installed, skipped: $it") }

        val builder = Builder()
            .setSession("vpn-practice")
            .addAddress("10.0.0.2", 24)
            .setMtu(1500)
        // 가짜 사내 대역만 라우팅한다(split tunnel). 0.0.0.0/0으로 잡으면 폰의 일반 인터넷이 전부 끊긴다.
        policy.routes.forEach { builder.addRoute(it.address, it.prefixLength) }
        // "Chrome만"에서는 우리 앱이 허용 목록 밖이라 터널 소켓이 TUN을 타지 않는다. 그래서 protect() 없이도 루프가 없다.
        val apps = builder.applyAppRule(policy.apps)
        if (apps is AppRule.Only && apps.packages.isEmpty()) {
            // 허용 목록이 비면 Android는 전체 앱을 터널에 태운다. 정책과 반대가 되므로 시작하지 않는다.
            Log.w(TAG, "policy=${preset.name} rejected: no allowed app could be applied")
            stopVpn()
            return
        }

        // 라우트와 앱 목록은 establish() 시점에 고정된다. 정책을 바꾸려면 중지 후 다시 시작해야 한다.
        val fd = builder.establish()
        if (fd == null) {
            // 사용자가 동의를 철회했거나 다른 VPN이 우선권을 가진 경우
            Log.w(TAG, "establish() returned null")
            stopVpn()
            return
        }
        tunFd = fd
        // launch 전에 먼저 설정한다. 터널이 곧바로 실패해 stopVpn()이 null로 되돌린 뒤에
        // 여기서 preset을 덮어쓰면 화면이 "실행 중"에 갇힌다.
        _runningPreset.value = preset
        tunnelJob = serviceScope.launch { runTunnel(fd) }
        val routes = policy.routes.joinToString { "${it.address}/${it.prefixLength}" }
        Log.i(TAG, "policy=${preset.name} routes=$routes apps=$apps")
    }

    /**
     * 앱 목록을 Builder에 적용하고, 실제로 적용된 패키지만 담은 AppRule을 돌려준다.
     * resolve()에서 설치를 확인했어도 그 사이에 앱이 지워질 수 있어서, 실패한 패키지만 건너뛴다.
     */
    private fun Builder.applyAppRule(rule: AppRule): AppRule = when (rule) {
        AppRule.AllApps -> rule // 앱 목록을 설정하지 않으면 모든 앱이 대상이다
        is AppRule.Only -> AppRule.Only(rule.packages.filter { pkg -> tryAddApp(pkg) { addAllowedApplication(pkg) } })
        is AppRule.Except -> AppRule.Except(rule.packages.filter { pkg -> tryAddApp(pkg) { addDisallowedApplication(pkg) } })
    }

    /** add()가 성공하면 true, 패키지를 못 찾으면 로그를 남기고 false */
    private fun tryAddApp(packageName: String, add: () -> Unit): Boolean = try {
        add()
        true
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "app not found, skipped: $packageName (${e.message})")
        false
    }

    // 매니페스트 <queries>에 없는 패키지는 Android 11부터 설치돼 있어도 안 보인다(NameNotFoundException).
    private fun isInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            // int 플래그 버전은 API 33부터 deprecated라서 버전으로 나눈다.
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private suspend fun runTunnel(fd: ParcelFileDescriptor) = coroutineScope {
        // 연결은 네트워크 I/O라 메인 스레드(startVpn)가 아니라 IO 스레드인 여기서 한다.
        // 순서: TCP 연결 -> TLS 핸드셰이크 -> 호스트 이름 검증(TlsConnector) -> TunnelClient -> 읽기/쓰기 루프.
        val client = try {
            val trustManager = resources.openRawResource(R.raw.practice_ca).use { TlsConnector.trustManagerFor(it) }
            val socket = TlsConnector.connect(trustManager, TlsConnector.SERVER_HOST, TlsConnector.SERVER_PORT)
            Log.i(TAG, "tls connected: protocol=${socket.session.protocol} cipher=${socket.session.cipherSuite}")
            TunnelClient(socket)
        } catch (e: IOException) {
            // 서버 꺼짐, 인증서 검증 실패(SSLHandshakeException), 호스트 이름 불일치 등. 화면은 "중지됨"으로 돌아간다.
            Log.w(TAG, "cannot open tunnel: $e")
            stopVpn()
            return@coroutineScope
        } catch (e: GeneralSecurityException) {
            // 앱에 넣은 CA 인증서를 읽지 못한 경우
            Log.w(TAG, "cannot load practice CA: $e")
            stopVpn()
            return@coroutineScope
        }
        tunnel = client
        if (tunFd == null) {
            // 연결하는 사이에 stopVpn()이 이미 지나갔다. 아무도 닫아 주지 않으니 직접 닫는다.
            client.close()
            return@coroutineScope
        }
        launch { readLoop(fd, client) }
        launch { writeLoop(fd, client) }
    }

    /** TUN에서 읽은 패킷을 로그로 남기고 서버로 보낸다. */
    private fun readLoop(fd: ParcelFileDescriptor, client: TunnelClient) {
        val buffer = ByteArray(PacketFrame.MAX_PACKET_SIZE)
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
                    // TCP 스트림은 한 번 실패하면 다시 쓸 수 없다. 전송 실패(IOException)는 견디지 않고 루프를 끝낸다.
                    client.send(buffer, length)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "read loop ended: $e")
        } finally {
            stopIfCurrent(client)
        }
    }

    /** 서버가 돌려보낸 패킷을 TUN에 써서 시스템이 응답으로 받게 한다. */
    private fun writeLoop(fd: ParcelFileDescriptor, client: TunnelClient) {
        val buffer = ByteArray(PacketFrame.MAX_PACKET_SIZE)
        // 스트림을 use{}로 닫으면 읽기 루프와 공유하는 fd까지 닫힌다. fd의 수명은 stopVpn()이 관리한다.
        val output = FileOutputStream(fd.fileDescriptor)
        try {
            while (true) {
                val length = client.receive(buffer)
                if (length < 0) {
                    Log.i(TAG, "write loop ended: server closed the connection")
                    break
                }
                logPacket("[from-server]", buffer, length)
                // IPv6처럼 파싱하지 못하는 패킷도 TUN에는 그대로 쓴다. 해석은 시스템 네트워크 스택의 몫이다.
                // TUN 쓰기 실패 하나(잘못된 패킷 등)는 연결 문제가 아니라서 로그만 남기고 계속한다.
                output.writePacket(buffer, length, isOpen = { fd.fileDescriptor.valid() }) {
                    Log.w(TAG, "tun write failed: ${it.message}")
                }
            }
        } catch (e: IOException) {
            // 소켓 I/O 실패, 잘못된 프레임(InvalidFrameException), stopVpn()이 소켓을 닫은 경우 여기로 빠져나온다.
            Log.i(TAG, "write loop ended: $e")
        } finally {
            stopIfCurrent(client)
        }
    }

    /**
     * 터널이 끊기면 VPN을 멈춘다. TCP 스트림은 끊기면 다시 쓸 수 없고, "실행 중"인데 아무것도 안 되는 상태를 만들지 않기 위해서다.
     * 자동 재연결은 하지 않는다. 실행 중에 연결을 바꾸는 것은 9단계의 실행 중 상태 전환과 같은 문제라 그때 다룬다.
     *
     * client가 아직 현재 터널일 때만 멈춘다. 사용자가 중지 후 바로 다시 시작했을 때, 앞 세션의 늦게 끝난 루프가
     * 새 세션을 멈추지 않게 하려는 것이다. IO 스레드에서 stopVpn()을 부르면 메인 스레드의 stopVpn()과 겹칠 수 있는데,
     * 이 기존 경합은 9단계에서 정리한다.
     */
    private fun stopIfCurrent(client: TunnelClient) {
        if (tunnel === client) stopVpn()
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
        _runningPreset.value = null
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

        /** 시작 Intent에 넣는 프리셋 이름(PolicyPreset.name) */
        const val EXTRA_PRESET = "com.mg.vpnpractice.extra.PRESET"
        private const val TAG = "PracticeVpn"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        private val _runningPreset = MutableStateFlow<PolicyPreset?>(null)

        /**
         * UI가 관찰하는 실행 중인 프리셋. null이면 중지됨.
         * 실행 여부와 정책을 따로 두면 "실행 중인데 정책 없음" 같은 어긋난 상태가 생길 수 있어 하나로 둔다.
         */
        val runningPreset: StateFlow<PolicyPreset?> = _runningPreset.asStateFlow()
    }
}
