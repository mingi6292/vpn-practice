# Docker 서버와 평문 UDP 터널 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 앱 TUN에서 읽은 패킷을 UDP로 Docker 서버에 보내고, 서버의 Linux TUN을 거쳐 돌아온 패킷을 앱 TUN에 쓰는 왕복을 만든다.

**Architecture:** `server/` 모듈의 `TunnelServer`가 `Tun` 인터페이스(실구현 `LinuxTun`, JNA)와 `DatagramSocket` 사이를 코루틴 두 개(uplink, downlink)로 중계한다. 클라이언트는 마지막으로 받은 발신지 주소 하나만 기억한다. 앱은 `TunnelClient`(connected `DatagramSocket`)를 `PracticeVpnService`의 읽기 루프에 붙이고 수신 루프를 추가한다.

**Tech Stack:** Kotlin/JVM, 코루틴, JNA(신규, 서버 전용), Docker, 기존 Android 스택

**Spec:** `docs/superpowers/specs/2026-11-25-udp-tunnel-server-design.md`

## Global Constraints

- 터널 포트 `5555/udp`. 서버 TUN `tun0`, `10.0.0.1/24`. 앱 TUN은 기존 `10.0.0.2/24`, 라우트 `10.0.0.0/24`, MTU 1500. `0.0.0.0/0` 라우트 금지
- 앱의 서버 주소는 `10.0.2.2:5555` 고정 (에뮬레이터 전용, 입력 UI 없음)
- 터널 형식: 데이터그램 하나 = IP 패킷 하나, 헤더 없음. 서버는 마지막 수신 발신지를 클라이언트로 쓴다. 클라이언트는 하나만 지원
- 앱의 터널 소켓은 `protect()`하지 않는다. 서버 주소가 라우트 밖이라 TUN으로 되돌아오지 않기 때문이다. 이유를 코드 주석으로 남기고 `protect()`는 6단계로 미룬다
- 새 라이브러리는 서버의 JNA뿐. 코루틴/JUnit은 기존 Version Catalog 항목 사용. 새 라이브러리는 `gradle/libs.versions.toml`에 등록
- 서버 패키지 `com.mg.vpnpractice.server`, 앱 코드는 `com.mg.vpnpractice.vpn`
- 블로킹 `read()`는 코루틴 취소로 깨어나지 않는다. 종료는 fd/소켓을 닫아서 깨운다 (서버, 앱 모두). 이유를 주석으로 남긴다
- 앱의 소켓 생성/송수신은 `Dispatchers.IO`에서만 한다. `startVpn()`은 메인 스레드에서 돈다
- VpnService 코드에는 왜 그렇게 했는지 짧은 주석 (CLAUDE.md)
- Git Bash에서 Docker 사용 전에 `export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"`와 `export MSYS_NO_PATHCONV=1` (`docs/docker-guide.md` 6장)
- 커밋 메시지는 한국어, 끝에 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`
- 작업 브랜치는 `feat/udp-tunnel-server` (이미 생성됨). 설계/계획 문서는 Task 1 커밋에 포함한다

## Review Focus

- 서버: 잘못된 데이터그램(TUN `write`가 실패)이 와도 루프가 죽지 않고 다음 패킷을 중계한다 -> Task 1 `tun_write_failure_does_not_stop_relay`
- 서버: 클라이언트가 한 번도 보내기 전에 TUN에서 패킷이 나온다(새 인터페이스에서 커널이 IPv6 패킷 등을 먼저 낸다). 버리고 계속 돈다 -> Task 1 `tun_packet_before_any_client_is_dropped`
- 서버: 앱을 중지했다 다시 시작하면 발신 포트가 바뀐다. 클라이언트 주소가 최신 발신지를 따라간다 -> Task 1 `client_address_follows_latest_sender`
- 앱: 서버가 꺼져 있으면 ICMP port unreachable 때문에 connected 소켓의 `send`/`receive`가 `PortUnreachableException`을 던질 수 있다. 서비스가 죽지 않고 루프가 계속 돈다 (소켓이 닫혔을 때만 종료). Task 4 수동 확인
- 앱: 중지를 두 번 눌러도, 서비스가 중지되는 순간에도 소켓/fd가 정리되고 예외로 죽지 않는다 -> Task 3 `close_twice_is_safe`, Task 4 수동 확인

---

### Task 1: 서버 모듈 골격과 중계 로직 (`TunnelServer`)

**Files:**
- Modify: `settings.gradle.kts` (`include(":server")`), `gradle/libs.versions.toml` (JNA 등록)
- Create: `server/build.gradle.kts`
- Create: `server/src/main/kotlin/com/mg/vpnpractice/server/Tun.kt`
- Create: `server/src/main/kotlin/com/mg/vpnpractice/server/TunnelServer.kt`
- Test: `server/src/test/kotlin/com/mg/vpnpractice/server/TunnelServerTest.kt`
- Add to git: `docs/superpowers/specs/2026-11-25-udp-tunnel-server-design.md`, 이 계획서

**Interfaces:**
- Produces:
  - `interface Tun { fun read(buffer: ByteArray): Int; fun write(buffer: ByteArray, length: Int); fun close() }` — `read`는 블로킹, 닫히면 `-1` 또는 `IOException`. `write` 실패는 `IOException`. `close()`는 두 번 불러도 안전
  - `class TunnelServer(tun: Tun, socket: DatagramSocket)` — `suspend fun run()`: uplink(소켓 수신 -> `tun.write`)와 downlink(`tun.read` -> 마지막 클라이언트로 `socket.send`)를 `Dispatchers.IO`에서 동시에 돌리고 둘 중 하나가 끝나면 `close()`한다. `fun close()`: `socket`과 `tun`을 닫는다(두 번 안전)
  - 로그는 표준 출력에 `uplink <n>B from <addr>`, `downlink <n>B to <addr>`, 버린 패킷은 `drop <n>B (no client)` 형식

- [ ] **Step 1: 모듈 골격**
  - `server/build.gradle.kts`: `alias(libs.plugins.convention.jvm.library)`와 `application` 플러그인, `mainClass = "com.mg.vpnpractice.server.MainKt"`, 의존성은 `implementation(libs.jna)`, `implementation(libs.kotlinx.coroutines.core)`, `testImplementation(libs.kotlinx.coroutines.test)`
  - `libs.versions.toml`에 `jna`(버전 `5.17.0`, 구현 때 Maven Central에서 5.x 최신 안정판인지 확인), 라이브러리 `jna = { group = "net.java.dev.jna", name = "jna", ... }` 추가
  - Run: `./gradlew :server:tasks` — Expected: 성공, `installDist`가 목록에 보인다

- [ ] **Step 2: 실패하는 테스트 작성** (`TunnelServerTest.kt`)
  - 가짜 `Tun`: 읽을 패킷은 `LinkedBlockingQueue`로 주입, `write`한 패킷은 리스트에 기록, `close()` 때 `read`가 `-1`을 돌려주게 한다. 실제 `DatagramSocket(0, InetAddress.getLoopbackAddress())`와 `runBlocking` + `launch(Dispatchers.IO) { server.run() }`을 쓴다. 클라이언트용 소켓은 `soTimeout = 2000`
  - 테스트 이름과 단언:
    - `datagram_is_written_to_tun`: 클라이언트가 `[1,2,3]`을 보내면 가짜 TUN의 `write` 기록이 `[1,2,3]`
    - `tun_packet_is_sent_to_last_client`: 클라이언트가 한 번 보낸 뒤 TUN에서 `[9,8]`이 나오면 클라이언트 소켓이 `[9,8]`을 받는다
    - `tun_packet_before_any_client_is_dropped`: 클라이언트 없이 TUN에서 `[7]`이 나와도 예외 없이 계속 돌고, 이후 클라이언트가 보낸 데이터그램은 정상 중계된다
    - `client_address_follows_latest_sender`: 소켓 A가 보낸 뒤 소켓 B가 보내면, 그 다음 TUN 패킷은 B가 받는다
    - `tun_write_failure_does_not_stop_relay`: 첫 `write`는 `IOException`, 두 번째는 성공하게 만들고 데이터그램 두 개를 보내면 두 번째가 기록된다
    - `close_stops_run`: `server.close()` 후 `run()`이 2초 안에 반환한다 (`withTimeout`)

- [ ] **Step 3: 실패 확인**
  - Run: `./gradlew :server:test` — Expected: FAIL (`TunnelServer`/`Tun` 미정의로 컴파일 실패)

- [ ] **Step 4: `Tun`과 `TunnelServer` 구현**
  - `socket.receive`/`tun.read`는 블로킹이므로 각각 `withContext(Dispatchers.IO)` 안의 루프로 돌린다. 버퍼는 32767바이트(앱과 동일)
  - uplink에서 `tun.write`가 `IOException`이면 로그만 남기고 계속한다. 단 `socket`이 닫혔으면 루프를 끝낸다
  - downlink에서 클라이언트가 없으면 `drop` 로그 후 계속한다
  - 종료는 `close()`로 소켓/TUN을 닫아 블로킹 호출을 깨우는 방식이다. 이유를 주석으로 남긴다

- [ ] **Step 5: 통과 확인**
  - Run: `./gradlew :server:test` — Expected: PASS (6개)

- [ ] **Step 6: Commit**
```bash
git add settings.gradle.kts gradle/libs.versions.toml server docs/superpowers
git commit -m "feat: 서버 모듈과 UDP-TUN 중계 로직 추가"
```

---

### Task 2: `LinuxTun` (JNA), `Main`, Docker 이미지

**Files:**
- Create: `server/src/main/kotlin/com/mg/vpnpractice/server/LinuxTun.kt`
- Create: `server/src/main/kotlin/com/mg/vpnpractice/server/Main.kt`
- Create: `server/Dockerfile`

**Interfaces:**
- Consumes: Task 1의 `Tun`, `TunnelServer`
- Produces: `class LinuxTun : Tun` — `companion object { fun open(name: String): LinuxTun }`, `fun main()`

- [ ] **Step 1: `LinuxTun.open(name)` 구현**
  - JNA로 libc `open`, `ioctl`, `read`, `write`, `close` 바인딩 (`Native.load("c", ...)`)
  - `open("/dev/net/tun", O_RDWR = 2)` 후 `ifreq` 구조체(40바이트: 이름 16바이트 + `short flags` + 패딩)에 이름과 `IFF_TUN(0x0001) or IFF_NO_PI(0x1000)`을 채워 `ioctl(fd, TUNSETIFF = 0x400454ca, ifreq)`. 실패하면 `errno`를 담은 `IOException`
  - `IFF_NO_PI`를 쓰는 이유(패킷 앞에 4바이트 정보 헤더가 붙지 않아 앱의 `Ipv4Packet.parse`와 같은 형식이 된다)를 주석으로 남긴다
  - `read`/`write`는 libc 호출 결과가 `-1`이면 `IOException`. `close()`는 한 번만 실제로 닫는다
  - 유닛 테스트는 하지 않는다 (Step 4에서 컨테이너로 확인)

- [ ] **Step 2: `Main.kt`**
  - `LinuxTun.open("tun0")` -> `ip addr add 10.0.0.1/24 dev tun0`, `ip link set tun0 up`을 `ProcessBuilder`로 실행(실패하면 종료 코드를 담아 예외) -> `DatagramSocket(5555)` -> `TunnelServer` 시작
  - JVM 종료 훅에서 `server.close()`. 시작 로그에 `tun0 10.0.0.1/24 up, udp 5555 listening` 한 줄

- [ ] **Step 3: `Dockerfile`**
  - 베이스는 Debian/Ubuntu 계열 JRE 이미지(`eclipse-temurin:17-jre`). Alpine(musl)은 쓰지 않는다. JNA가 기본 포함하는 네이티브 라이브러리가 glibc용이라 로딩에 실패할 수 있다
  - `apt-get install -y --no-install-recommends iproute2` 레이어를 위에, 자주 바뀌는 `COPY build/install/server /app`을 아래에 둔다 (`docs/docker-guide.md` 2장). `ENTRYPOINT ["/app/bin/server"]`
  - 빌드 컨텍스트는 `server/`. 파일 맨 위 주석에 빌드/실행 명령 세 줄을 적는다

- [ ] **Step 4: 컨테이너에서 확인**
  - Run:
    ```bash
    ./gradlew :server:installDist
    docker build -t vpn-practice-server server
    docker run -d --rm --name tunnel-server --cap-add NET_ADMIN --device /dev/net/tun -p 5555:5555/udp vpn-practice-server
    docker logs tunnel-server
    docker exec tunnel-server ip -br addr show tun0
    ```
  - Expected: 로그에 `tun0 10.0.0.1/24 up, udp 5555 listening`, `ip` 출력에 `tun0 ... 10.0.0.1/24`. 컨테이너는 Task 4 수동 확인까지 켜 둔다
  - 실패하면(특히 `/dev/net/tun`이 안 열리면) 다음 Task로 가지 말고 원인을 사용자에게 보고한다. 설계가 바뀔 수 있다

- [ ] **Step 5: Commit**
```bash
git add server
git commit -m "feat: JNA로 Linux TUN을 여는 서버 진입점과 Docker 이미지 추가"
```

---

### Task 3: 앱 `TunnelClient`

**Files:**
- Create: `app/src/main/java/com/mg/vpnpractice/vpn/TunnelClient.kt`
- Test: `app/src/test/java/com/mg/vpnpractice/vpn/TunnelClientTest.kt`

**Interfaces:**
- Produces: `class TunnelClient(host: String, port: Int) : Closeable` — `val localPort: Int`, `val isClosed: Boolean`, `fun send(buffer: ByteArray, length: Int)`, `fun receive(buffer: ByteArray): Int`(블로킹, 수신 길이 반환, 닫히면 `SocketException`), `override fun close()`(두 번 안전). `companion object { const val SERVER_HOST = "10.0.2.2"; const val SERVER_PORT = 5555 }`

- [ ] **Step 1: 실패하는 테스트 작성**
  - 서버 역할은 `DatagramSocket(0, InetAddress.getLoopbackAddress())`, 클라이언트는 `TunnelClient("127.0.0.1", server.localPort)`. 수신 쪽 `soTimeout`은 2000
  - `send_delivers_datagram_to_server`: `send([1,2,3], 3)` -> 서버가 `[1,2,3]` 수신
  - `receive_returns_server_datagram`: 서버가 `client.localPort`로 `[4,5]`를 보내면 `receive`가 `2`를 반환하고 버퍼 앞 2바이트가 `[4,5]`
  - `datagram_from_other_source_is_ignored`: 다른 소켓이 먼저 `client.localPort`로 `[0]`을 보내고, 이어서 진짜 서버가 `[6]`을 보내면 `receive`는 `[6]`만 돌려준다 (connected 소켓의 필터링)
  - `close_unblocks_receive`: 다른 스레드가 `receive`에서 대기 중일 때 `close()`하면 `SocketException`으로 빠져나온다
  - `close_twice_is_safe`: `close()`를 두 번 불러도 예외 없음, `isClosed == true`

- [ ] **Step 2: 실패 확인**
  - Run: `./gradlew :app:testDebugUnitTest --tests "com.mg.vpnpractice.vpn.TunnelClientTest"` — Expected: FAIL (컴파일 실패)

- [ ] **Step 3: `TunnelClient` 구현**
  - `DatagramSocket()`을 만들고 `connect(InetAddress.getByName(host), port)`. connect하는 이유(다른 곳에서 온 데이터그램을 커널이 걸러 준다)와, `protect()`를 호출하지 않는 이유(서버 주소가 TUN 라우트 밖)를 주석으로 남긴다

- [ ] **Step 4: 통과 확인**
  - Run: 위와 같은 명령 — Expected: PASS (5개)

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/mg/vpnpractice/vpn/TunnelClient.kt app/src/test/java/com/mg/vpnpractice/vpn/TunnelClientTest.kt
git commit -m "feat: 서버와 통신하는 UDP TunnelClient 추가"
```

---

### Task 4: `PracticeVpnService`에 터널 연결

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (`<uses-permission android:name="android.permission.INTERNET" />` 추가)
- Modify: `app/src/main/java/com/mg/vpnpractice/vpn/PracticeVpnService.kt`

**Interfaces:**
- Consumes: Task 3의 `TunnelClient`, 기존 `Ipv4Packet.parse`

- [ ] **Step 1: 매니페스트에 `INTERNET` 권한 추가**
  - 지금 매니페스트에 없다. 없으면 `DatagramSocket`이 `EPERM`으로 실패한다. 3단계의 테스트 패킷 버튼도 같은 영향을 받았을 수 있으니 이 단계 확인에서 같이 본다

- [ ] **Step 2: 서비스 수정**
  - `startVpn()`에서 TUN을 연 뒤, `serviceScope.launch`(IO) 안에서 `TunnelClient(SERVER_HOST, SERVER_PORT)`를 만들고 읽기 루프와 수신 루프를 시작한다. 클라이언트는 `stopVpn()`이 닫을 수 있게 필드에 보관한다
  - 읽기 루프: 기존 파싱/로그 뒤에 `client.send(buffer, length)`. 로그 한 줄에 방향을 구분하는 접두어 `[to-server]`를 붙인다
  - 수신 루프(신규): `FileOutputStream(fd.fileDescriptor)`에 `client.receive(buffer)`로 받은 패킷을 그대로 쓴다. 쓰기 전에 `Ipv4Packet.parse`로 같은 형식의 로그를 `[from-server]` 접두어로 남기고, 파싱이 `null`이어도(IPv6 등) TUN에는 쓴다
  - `FileOutputStream`을 `use {}`로 감싸지 않는다. 스트림을 닫으면 읽기 쪽과 공유하는 fd까지 닫혀 버린다. fd의 수명은 `stopVpn()`이 관리한다는 주석을 남긴다
  - 두 루프 모두 `PortUnreachableException`(서버가 꺼진 경우)은 로그만 남기고 계속 돈다. 소켓/fd가 닫혀서 생긴 예외일 때만 루프를 끝낸다 (`client.isClosed`로 구분)
  - `stopVpn()`은 기존대로 TUN fd를 닫고, 추가로 `TunnelClient`를 닫는다 (둘 다 블로킹 호출을 깨우기 위함이라는 주석). 두 번 호출돼도 안전해야 한다

- [ ] **Step 3: 빌드 확인**
  - Run: `./gradlew assembleDebug testDebugUnitTest lintDebug` — Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 에뮬레이터 수동 확인** (Task 2의 컨테이너가 떠 있어야 한다)
  - Run: `./gradlew installDebug`, `adb logcat -s PracticeVpn`, `docker logs -f tunnel-server`
  - 동의 후 시작 -> 테스트 패킷 버튼. Expected:
    - Logcat: `[to-server] src=10.0.0.2 dst=10.0.0.1 proto=UDP ... ->9999`, 이어서 `[from-server] src=10.0.0.1 dst=10.0.0.2 proto=ICMP`
    - 서버 로그: `uplink ...B from ...`, `downlink ...B to ...`
  - 중지 -> 다시 시작 -> 테스트 패킷: 두 번째도 왕복이 된다 (서버가 새 발신 포트를 따라간다)
  - 중지를 연속으로 두 번: 앱이 죽지 않는다. 키 아이콘과 알림이 사라진다
  - `docker stop tunnel-server` 후 시작 -> 테스트 패킷: 앱이 죽지 않고, Logcat에 예외 로그만 남으며 에뮬레이터의 일반 인터넷은 계속 된다
  - 안 되면 확인할 것: Windows 방화벽의 UDP 5555 인바운드, `docs/docker-guide.md` 6장 함정 4

- [ ] **Step 5: Commit**
```bash
git add app/src/main
git commit -m "feat: VpnService에 UDP 터널 송수신 연결"
```

---

### Task 5: CI에 서버 테스트 추가와 최종 확인

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: CI 명령에 `:server:test` 추가**
  - `Build and test` 단계를 `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test`로 바꾼다. 서버는 순수 JVM 모듈이라 `testDebugUnitTest`에 포함되지 않는다
  - 참고: `CLAUDE.md`의 완료 확인 명령도 같은 이유로 바꿔야 하는데, 프로젝트 지침 파일이라 사용자 확인 후에 바꾼다 (이 계획에서는 수정하지 않는다)

- [ ] **Step 2: 전체 확인**
  - Run: `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test` — Expected: BUILD SUCCESSFUL, 서버 6개와 앱 `TunnelClientTest` 5개 포함
  - 정리: `docker stop tunnel-server`

- [ ] **Step 3: Commit**
```bash
git add .github/workflows/ci.yml
git commit -m "ci: 서버 모듈 테스트를 CI에 추가"
```
