# TUN 열기와 IPv4 패킷 파싱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (또는 subagent-driven-development)로 task 단위로 구현한다. 체크박스(`- [ ]`)로 진행을 추적한다.

**Goal:** 앱이 VPN 동의를 받아 TUN을 열고, `10.0.0.0/24`로 가는 패킷을 읽어 IPv4 헤더를 파싱해 Logcat에 남긴다.

**Architecture:** 순수 로직인 `Ipv4Packet.parse()`를 먼저 TDD로 만든다. `PracticeVpnService`가 `Dispatchers.IO` 코루틴에서 TUN fd를 블로킹 `read()`하며 파서를 호출하고, 중지 시 fd를 닫아 `read()`를 깨운다. `MainActivity`는 동의 요청과 시작/중지/테스트 패킷 버튼만 가진다.

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines (기존 의존성만 사용, 새 라이브러리 없음)

**Spec:** `docs/superpowers/specs/2026-11-24-tun-packet-parse-design.md`

## Global Constraints

- 패키지는 `com.mg.vpnpractice` 아래, VPN 관련 코드는 `com.mg.vpnpractice.vpn`
- `minSdk` 24, `targetSdk` 37 (build-logic에서 관리, 건드리지 않음)
- 새 라이브러리 추가 금지
- TUN 주소 `10.0.0.2/24`, 라우트 `10.0.0.0/24`, MTU 1500. `0.0.0.0/0` 라우트 금지
- 테스트 소켓은 `protect()`하지 않는다 (TUN으로 들어가야 하므로). 이유를 주석으로 남긴다
- VpnService 코드에는 왜 그렇게 했는지 짧은 주석 (fd를 닫는 시점, `read()`가 취소되지 않는 이유, 라우트를 좁게 잡은 이유)
- `parse()`는 예외를 던지지 않고 실패 시 `null`
- 커밋 메시지는 한국어, 끝에 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`
- 작업 브랜치는 `feat/tun-packet-parse` (이미 생성됨, 설계/계획 문서는 첫 커밋에 포함)

## Review Focus

- 헤더 길이(20바이트)보다 짧은 입력, `length`가 배열보다 작은 입력: `null`, 크래시 없음
- IPv6 패킷(version 6)이 TUN으로 들어오는 경우: `null`을 반환하고 루프는 계속 돈다
- IHL > 5(옵션 헤더)에서 포트 위치가 `ihl*4` 기준으로 밀리는지
- Total Length보다 `length`가 짧은 잘린 TCP/UDP 패킷: 포트를 읽다 범위를 벗어나지 않고 포트 없음 처리
- 서비스 중지/`onRevoke()` 후 fd가 닫히고 읽기 루프가 끝나는지, 중지를 두 번 눌러도 안전한지
- 동의를 거부하거나 `prepare()` 후 시작 전에 앱이 죽는 경우: 서비스가 시작되지 않고 UI가 "중지"로 남는지

---

### Task 1: IPv4 헤더 파서

**Files:**
- Create: `app/src/main/java/com/mg/vpnpractice/vpn/Ipv4Packet.kt`
- Test: `app/src/test/java/com/mg/vpnpractice/vpn/Ipv4PacketTest.kt`
- Add (첫 커밋): `docs/superpowers/specs/2026-11-24-tun-packet-parse-design.md`, 이 계획 문서

**Interfaces:**
- Produces:
  - `data class Ipv4Packet(val source: String, val destination: String, val protocol: Int, val sourcePort: Int?, val destinationPort: Int?)`
  - `companion object { fun parse(bytes: ByteArray, length: Int): Ipv4Packet? }`
  - 프로토콜 상수는 `Ipv4Packet.PROTOCOL_ICMP = 1`, `PROTOCOL_TCP = 6`, `PROTOCOL_UDP = 17`
  - `fun protocolName(): String` ("TCP", "UDP", "ICMP", 그 외 `"PROTO(번호)"`)

- [ ] **Step 1: 실패하는 테스트 작성** (`Ipv4PacketTest`). 테스트용 헤더 바이트 배열을 만드는 헬퍼를 테스트 파일 안에 둔다. 테스트 이름과 단언:
  - `parses UDP packet`: 출발 `10.0.0.2:5555` -> 목적 `10.0.0.1:9999`, protocol 17 -> `source=="10.0.0.2"`, `destination=="10.0.0.1"`, 두 포트 일치, `protocolName()=="UDP"`
  - `parses TCP packet`: protocol 6, 포트 `443` 확인
  - `ICMP has no ports`: protocol 1 -> 두 포트 모두 `null`
  - `handles header options`: IHL=6(24바이트)일 때 포트를 24바이트 이후에서 읽는다
  - `returns null when shorter than 20 bytes`
  - `returns null when length is smaller than array`: 배열은 충분히 크지만 `length=10`
  - `returns null for IPv6`: 첫 바이트 상위 니블 6
  - `truncated UDP has null ports`: 헤더는 완전하나 포트 바이트가 모자람 -> 패킷은 반환, 포트는 `null`
  - `returns null when IHL is below 5`

- [ ] **Step 2: 실패 확인** — `./gradlew testDebugUnitTest --tests "*Ipv4PacketTest"` -> 컴파일 실패 또는 FAIL

- [ ] **Step 3: `Ipv4Packet.parse` 구현.** 첫 바이트 상위 4비트=version(4가 아니면 null), 하위 4비트=IHL(5 미만이면 null), `ihl*4`가 `length`를 넘으면 null. protocol은 offset 9, 출발지 offset 12~15, 목적지 16~19. TCP/UDP이고 `ihl*4 + 4 <= length`일 때만 포트(각 2바이트, 부호 없는 big-endian)를 채운다. 바이트를 `toInt() and 0xFF`로 부호 없이 다루는 이유를 한 줄 주석으로 남긴다.

- [ ] **Step 4: 통과 확인** — 같은 명령, 전부 PASS

- [ ] **Step 5: 커밋** — `git add docs app/src` 후 `feat: IPv4 헤더 파서 추가`

---

### Task 2: 매니페스트와 VpnService

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/mg/vpnpractice/vpn/PracticeVpnService.kt`
- Modify: `app/src/main/res/values/strings.xml` (알림 제목/채널 이름)

**Interfaces:**
- Consumes: `Ipv4Packet.parse(bytes: ByteArray, length: Int): Ipv4Packet?`, `Ipv4Packet.protocolName()`
- Produces:
  - `class PracticeVpnService : VpnService()`
  - `companion object { const val ACTION_START = "com.mg.vpnpractice.action.START"; const val ACTION_STOP = "com.mg.vpnpractice.action.STOP"; val isRunning: StateFlow<Boolean> }` — UI가 상태를 관찰하는 용도
  - 서비스 시작/중지는 `startService(Intent(context, PracticeVpnService::class.java).setAction(...))`

- [ ] **Step 1: 매니페스트 수정.**
  - `<service android:name=".vpn.PracticeVpnService" android:permission="android.permission.BIND_VPN_SERVICE" android:foregroundServiceType="systemExempted" android:exported="false">` 와 `<intent-filter><action android:name="android.net.VpnService"/></intent-filter>`
  - 권한: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `POST_NOTIFICATIONS`
  - **확인 필요**: `systemExempted`가 VpnService에 맞는 타입인지 Android 공식 문서(포그라운드 서비스 타입)로 확인한다. 다르면 문서에 맞는 타입으로 바꾸고 설계 문서의 "결정 사항"과 이 계획을 함께 고친다.

- [ ] **Step 2: `onStartCommand` 구현.** `ACTION_STOP`이면 `stopVpn()`, 그 외(`ACTION_START`)면 `startVpn()` 후 `START_NOT_STICKY` 반환. 이미 실행 중이면 `startVpn()`은 아무것도 하지 않는다.

- [ ] **Step 3: `startVpn()` 구현.**
  - 먼저 알림 채널을 만들고 `startForeground(...)`를 호출한다 (VPN 서비스는 사용자에게 계속 보여야 하고, 포그라운드 시작 제한 때문에 빨리 호출한다)
  - `Builder().setSession("vpn-practice").addAddress("10.0.0.2", 24).addRoute("10.0.0.0", 24).setMtu(1500).establish()`로 fd를 얻는다. 라우트를 좁게 잡은 이유(인터넷을 끊지 않기 위해)를 주석으로 남긴다
  - `establish()`가 `null`(동의 철회 등)이면 서비스를 정리하고 종료
  - `serviceScope`(`CoroutineScope(SupervisorJob() + Dispatchers.IO)`)에서 읽기 루프를 launch하고 `isRunning`을 `true`로

- [ ] **Step 4: 읽기 루프 구현.** `FileInputStream(fd.fileDescriptor)`에서 `ByteArray(32767)`로 `read()`를 반복한다. 읽은 길이가 0 이하면 건너뛰고, `Ipv4Packet.parse`가 null이면 `Log.d`로 "ignored"만 남기고, 아니면 `Log.i(TAG, "src=… dst=… proto=UDP port=9999")` 형태로 기록한다. `IOException`이면(fd가 닫힘) 루프를 정상 종료한다. `read()`는 블로킹이라 코루틴 취소로 깨어나지 않으므로 fd를 닫아야 깨어난다는 주석을 단다.

- [ ] **Step 5: `stopVpn()` 구현.** 순서: `isRunning=false` -> `fd.close()`(읽기 루프가 `IOException`으로 빠져나옴) -> `job.cancel()` -> `stopForeground(STOP_FOREGROUND_REMOVE)` -> `stopSelf()`. fd와 job은 null 가능 필드로 두고, 두 번 호출돼도 안전하게 한다. `onDestroy()`와 `onRevoke()`는 `stopVpn()`을 호출한다 (`onRevoke()`가 오면 시스템이 이미 TUN을 회수한 상태라는 주석, `super.onRevoke()` 호출 포함).

- [ ] **Step 6: 빌드 확인** — `./gradlew assembleDebug lintDebug` 통과 (UI 연결 전이라 서비스는 아직 실행되지 않음)

- [ ] **Step 7: 커밋** — `feat: VpnService로 TUN을 열고 패킷 읽기 루프 추가`

---

### Task 3: 동의 흐름, 테스트 패킷, 화면

**Files:**
- Create: `app/src/main/java/com/mg/vpnpractice/vpn/TestPacketSender.kt`
- Modify: `app/src/main/java/com/mg/vpnpractice/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `PracticeVpnService.ACTION_START`, `ACTION_STOP`, `PracticeVpnService.isRunning: StateFlow<Boolean>`
- Produces: `object TestPacketSender { fun send() }` — `10.0.0.1:9999`로 UDP 페이로드 `"hello"`를 한 번 보낸다. 호출은 `Dispatchers.IO`에서 한다.

- [ ] **Step 1: `TestPacketSender.send()` 구현.** `DatagramSocket().use { it.send(DatagramPacket(...)) }`. `protect()`를 호출하지 않는 이유(이 패킷이 TUN으로 들어가야 로그가 남음, 실제 터널 소켓은 루프를 막으려고 `protect()`가 필요함)를 주석으로 남긴다.

- [ ] **Step 2: 동의 흐름 구현 (`MainActivity`).** `rememberLauncherForActivityResult(StartActivityForResult())`를 쓴다. "시작" 클릭 시 `VpnService.prepare(context)`가 `null`이면 이미 동의됨이므로 바로 서비스를 시작하고, `Intent`가 오면 그 Intent로 launcher를 실행한다. 결과가 `RESULT_OK`일 때만 서비스를 시작한다(거부 시 아무것도 하지 않음). null/non-null 분기에 학습용 주석을 단다.

- [ ] **Step 3: 알림 권한.** Android 13 이상에서는 시작 전에 `POST_NOTIFICATIONS`를 요청한다. 거부해도 VPN 자체는 시작되게 한다(알림만 안 보임), 이 선택을 주석으로 남긴다.

- [ ] **Step 4: 화면 구성.** 상태 텍스트("실행 중"/"중지됨", `isRunning`을 `collectAsState`로 관찰)와 버튼 3개: 시작, 중지, 테스트 패킷 보내기. 테스트 패킷 버튼은 실행 중일 때만 활성화하고 `lifecycleScope`/`rememberCoroutineScope`의 `Dispatchers.IO`에서 `TestPacketSender.send()`를 호출한다. 문자열은 `strings.xml`로.

- [ ] **Step 5: 전체 검증** — `./gradlew assembleDebug testDebugUnitTest lintDebug` 통과

- [ ] **Step 6: 수동 확인 (에뮬레이터 또는 실기기, adb 사용 가능 시)** — 설치 -> 시작 -> 동의 -> `adb logcat -s <TAG>` -> 테스트 패킷 버튼 -> `src=10.0.0.2 dst=10.0.0.1 proto=UDP … port=9999` 확인 -> 중지 -> 상태바 키 아이콘 사라짐, 인터넷은 계속 동작. 기기가 없으면 이 단계는 건너뛰고 최종 보고에 "수동 확인 못함"이라고 적는다.

- [ ] **Step 7: 커밋, push, PR** — `feat: 동의 흐름과 테스트 패킷 버튼 추가`, PR 본문 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. 머지는 하지 않는다.
