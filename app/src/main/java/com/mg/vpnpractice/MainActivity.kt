package com.mg.vpnpractice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mg.vpnpractice.ui.theme.VpnPracticeTheme
import com.mg.vpnpractice.vpn.PracticeVpnService
import com.mg.vpnpractice.vpn.TestPacketSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VpnPracticeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VpnScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun VpnScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val running by PracticeVpnService.isRunning.collectAsState()
    val scope = rememberCoroutineScope()

    // 동의 화면 결과. RESULT_OK일 때만 서비스를 시작하고, 거부하면 아무것도 하지 않는다(상태는 "중지됨" 그대로).
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService(context)
    }

    // 알림 권한은 거부해도 VPN은 시작한다. 알림만 안 보일 뿐 VPN 동작에는 영향이 없다.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpnConsent(context) { consentLauncher.launch(it) }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(if (running) R.string.status_running else R.string.status_stopped))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestVpnConsent(context) { consentLauncher.launch(it) }
            }
        }) { Text(stringResource(R.string.action_start)) }
        Button(onClick = {
            context.startService(
                Intent(context, PracticeVpnService::class.java).setAction(PracticeVpnService.ACTION_STOP),
            )
        }) { Text(stringResource(R.string.action_stop)) }
        Button(
            enabled = running,
            // 소켓 전송은 네트워크 호출이라 메인 스레드에서 하면 NetworkOnMainThreadException이 난다.
            onClick = { scope.launch(Dispatchers.IO) { TestPacketSender.send() } },
        ) { Text(stringResource(R.string.action_send_test_packet)) }
    }
}

/**
 * prepare()가 null이면 이미 동의된 상태라 바로 시작하고, Intent를 돌려주면 시스템 동의 화면을 띄워야 한다.
 */
private fun requestVpnConsent(context: Context, launchConsent: (Intent) -> Unit) {
    val consentIntent = VpnService.prepare(context)
    if (consentIntent == null) startVpnService(context) else launchConsent(consentIntent)
}

private fun startVpnService(context: Context) {
    // 서비스 쪽에서 곧바로 startForeground()를 호출하므로 일반 startService로 충분하다(앱이 포그라운드일 때만 호출됨).
    context.startService(
        Intent(context, PracticeVpnService::class.java).setAction(PracticeVpnService.ACTION_START),
    )
}

@Preview(showBackground = true)
@Composable
private fun VpnScreenPreview() {
    VpnPracticeTheme { VpnScreen() }
}
