package com.mg.vpnpractice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mg.vpnpractice.ui.theme.VpnPracticeTheme
import com.mg.vpnpractice.vpn.PolicyPreset
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
    // null이면 중지됨. 실행 중일 때 정책은 establish() 시점에 고정되므로 선택을 막는다.
    val runningPreset by PracticeVpnService.runningPreset.collectAsState()
    var selected by rememberSaveable { mutableStateOf(PolicyPreset.ALL_APPS) }
    // 실행 중에는 서비스가 실제로 쓰는 프리셋을 보여 줘서, 앱을 다시 열어도 화면과 실제 정책이 어긋나 보이지 않게 한다.
    val shownPreset = runningPreset ?: selected
    val scope = rememberCoroutineScope()

    // 동의 화면 결과. RESULT_OK일 때만 서비스를 시작하고, 거부하면 아무것도 하지 않는다(상태는 "중지됨" 그대로).
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService(context, selected)
    }

    // 권한을 거부해도 VPN은 시작한다. 알림 권한이 없으면 알림만 안 보이고,
    // 로컬 네트워크 권한이 없으면 TUN은 열리지만 서버 전송이 실패해서 Logcat에 "send failed"가 남는다.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestVpnConsent(context, selected) { consentLauncher.launch(it) }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val preset = runningPreset
        Text(
            if (preset != null) {
                stringResource(R.string.status_running, stringResource(preset.labelRes()))
            } else {
                stringResource(R.string.status_stopped)
            },
        )
        PolicyPreset.entries.forEach { option ->
            Row(
                modifier = Modifier.selectable(
                    selected = option == shownPreset,
                    enabled = runningPreset == null,
                    role = Role.RadioButton,
                    onClick = { selected = option },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Row가 클릭을 처리하므로 RadioButton의 onClick은 null로 둔다(접근성 도구가 한 항목으로 읽는다).
                RadioButton(selected = option == shownPreset, onClick = null, enabled = runningPreset == null)
                Text(stringResource(option.labelRes()), modifier = Modifier.padding(start = 8.dp))
            }
        }
        Button(onClick = {
            val permissions = runtimePermissions()
            if (permissions.isEmpty()) {
                requestVpnConsent(context, selected) { consentLauncher.launch(it) }
            } else {
                permissionLauncher.launch(permissions)
            }
        }) { Text(stringResource(R.string.action_start)) }
        Button(onClick = {
            context.startService(
                Intent(context, PracticeVpnService::class.java).setAction(PracticeVpnService.ACTION_STOP),
            )
        }) { Text(stringResource(R.string.action_stop)) }
        Button(
            enabled = runningPreset != null,
            // 소켓 전송은 네트워크 호출이라 메인 스레드에서 하면 NetworkOnMainThreadException이 난다.
            onClick = {
                scope.launch(Dispatchers.IO) {
                    TestPacketSender.send()?.let { Log.w("PracticeVpn", "test packet failed: ${it.message}") }
                }
            },
        ) { Text(stringResource(R.string.action_send_test_packet)) }
    }
}

/** 이 OS 버전에서 실행 중에 요청해야 하는 권한 목록 */
private fun runtimePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    // Android 17부터 터널 서버(10.0.2.2)와 테스트 대상(10.0.0.1) 같은 사설 대역 통신에 필요하다.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
}.toTypedArray()

/**
 * prepare()가 null이면 이미 동의된 상태라 바로 시작하고, Intent를 돌려주면 시스템 동의 화면을 띄워야 한다.
 */
private fun requestVpnConsent(context: Context, preset: PolicyPreset, launchConsent: (Intent) -> Unit) {
    val consentIntent = VpnService.prepare(context)
    if (consentIntent == null) startVpnService(context, preset) else launchConsent(consentIntent)
}

private fun startVpnService(context: Context, preset: PolicyPreset) {
    // 서비스 쪽에서 곧바로 startForeground()를 호출하므로 일반 startService로 충분하다(앱이 포그라운드일 때만 호출됨).
    // Intent에는 프리셋 이름만 넣고, 실제 정책 내용은 서비스가 PolicyPreset에서 찾는다.
    context.startService(
        Intent(context, PracticeVpnService::class.java)
            .setAction(PracticeVpnService.ACTION_START)
            .putExtra(PracticeVpnService.EXTRA_PRESET, preset.name),
    )
}

// 문자열 리소스 매핑은 화면 쪽에 둔다. TunnelPolicy.kt를 Android 의존성 없는 순수 Kotlin으로 유지하려는 것이다.
@StringRes
private fun PolicyPreset.labelRes(): Int = when (this) {
    PolicyPreset.ALL_APPS -> R.string.preset_all_apps
    PolicyPreset.CHROME_ONLY -> R.string.preset_chrome_only
    PolicyPreset.EXCEPT_CHROME -> R.string.preset_except_chrome
}

@Preview(showBackground = true)
@Composable
private fun VpnScreenPreview() {
    VpnPracticeTheme { VpnScreen() }
}
