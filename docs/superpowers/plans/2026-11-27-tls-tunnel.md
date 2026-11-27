# TLS 터널 (인증 없음) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 앱과 서버 사이의 평문 UDP 터널을, 연습용 CA로 서버를 검증하는 TCP+TLS 연결 하나(길이 접두어 프레임)로 바꾼다.

**Architecture:** 프레임 읽기/쓰기는 공용 JVM 모듈 `:tunnel-protocol`의 `PacketFrame`에 둔다. 서버는 `SSLServerSocket`으로 받고, 한 번에 연결 하나(마지막 연결 우선)와 프레임을 중계한다. TLS 설정은 `ServerTls.kt`에 둬서 `TunnelServer`는 일반 `ServerSocket`으로 테스트한다. 앱은 `TlsConnector`가 `res/raw`의 CA로 TrustManager를 직접 조립하고, TCP 연결 -> TLS 핸드셰이크 -> 호스트 이름 검증을 한다. 그 결과로 받은 소켓을 `TunnelClient`가 프레임으로 다룬다. 연결이 끊기면 VPN을 멈춘다.

**Tech Stack:** Kotlin, JDK `javax.net.ssl`(`SSLSocket`/`SSLServerSocket`/`KeyStore`/`TrustManagerFactory`/`KeyManagerFactory`), 코루틴, JUnit4, OpenSSL(Git Bash 기본 포함). 새 라이브러리 없음.

**Spec:** `docs/superpowers/specs/2026-11-26-tls-tunnel-design.md`

## Global Constraints

- 새 라이브러리 금지. TLS는 JDK 기본 API만 쓴다
- 반목표: 모든 인증서를 믿는 `X509TrustManager`, 항상 `true`인 `HostnameVerifier`, 검증을 끄는 플래그 금지. 디버그 빌드도 마찬가지다
- 개인키(`ca.key`, 서버 키, `server.p12`, 비밀번호 파일)는 git과 APK에 넣지 않는다. 커밋하는 인증서는 `app/src/main/res/raw/practice_ca.pem` 하나뿐이다
- 프레임: 2바이트 부호 없는 big-endian 길이 + 본문. 길이는 1~32767(`MAX_PACKET_SIZE = 32767`). 0이거나 최대를 넘으면 연결을 끊는다. 길이와 본문은 한 번의 `write`로 보낸다
- 양쪽 소켓에 `TCP_NODELAY`를 켠다
- 서버 주소 `10.0.2.2`, 포트 TCP `5555`. Docker 포트는 `127.0.0.1:5555:5555`(TCP)만 연다
- 서버 인증서 SAN `IP:10.0.2.2`, EKU `serverAuth`. CA는 `basicConstraints=critical,CA:TRUE`
- 서버 TLS 프로토콜은 `TLSv1.3`, `TLSv1.2`만 켠다. 앱은 따로 제한하지 않는다(minSdk 24)
- 앱의 연결/핸드셰이크 타임아웃은 각 5000ms이고, 핸드셰이크 뒤에는 읽기 타임아웃을 0으로 되돌린다. 서버의 핸드셰이크 타임아웃도 5000ms
- 앱: 시작 중이든 실행 중이든 연결/검증/소켓 I/O가 실패하거나 잘못된 프레임을 받으면 `stopVpn()`한다. 자동 재연결은 없다. TUN 쓰기 실패 하나는 로그만 남기고 계속한다
- 앱 TUN 주소 `10.0.0.2/24`, 라우트 `10.0.0.0/24`, 프리셋 3개는 그대로 둔다. `protect()`, `0.0.0.0/0`, 재 `establish()`는 이번 범위가 아니다
- 패키지: 앱 `com.mg.vpnpractice.vpn`, 서버 `com.mg.vpnpractice.server`, 공용 `com.mg.vpnpractice.protocol`
- VpnService/TLS 코드에는 왜 그렇게 했는지 짧은 주석을 단다 (CLAUDE.md, 설계 문서 "코드에 남길 주석 지점")
- 커밋 메시지는 한국어로 쓴다. 서명 줄(`Co-Authored-By` 등)은 넣지 않는다
- 작업 브랜치 `feat/tls-tunnel` (설계 문서 `ce65387` 커밋됨)
- 최종 확인: `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test :tunnel-protocol:test`

## Review Focus

- 바이트 하나 차이의 경계: 32767바이트 패킷은 지나가고 32768은 거부돼야 한다 -> Task 1 `max_length_frame_is_accepted`, `length_over_max_throws_invalid_frame`
- 잘못된 인증서로 접속한 클라이언트나 핸드셰이크 중 멈춘 클라이언트 때문에 정상 연결이 끊기거나 accept가 막히면 안 된다 -> Task 3 `failed_handshake_keeps_current_connection`, `stalled_handshake_does_not_block_accept`
- 쓰레기 바이트(예: `openssl s_client`에 아무 글자나 입력)를 받은 서버는 그 연결만 끊고 다음 연결을 계속 받아야 한다 -> Task 3 `invalid_length_closes_only_that_connection`
- 서버를 켜기 전에 VPN을 시작해도 앱은 죽지 않고, 5초 안에 "중지됨"으로 돌아와야 한다 -> Task 4 `connect_to_closed_port_throws_io_exception`, Task 5 수동 확인
- 중지 직후 바로 다시 시작해도, 앞 세션의 늦게 끝난 루프가 새 세션을 멈추면 안 된다(`stopIfCurrent`) -> Task 5 수동 확인

---

### Task 1: 공용 프레임 모듈 `:tunnel-protocol`

**Files:**
- Create: `tunnel-protocol/build.gradle.kts`
- Create: `tunnel-protocol/src/main/kotlin/com/mg/vpnpractice/protocol/PacketFrame.kt`
- Test: `tunnel-protocol/src/test/kotlin/com/mg/vpnpractice/protocol/PacketFrameTest.kt`
- Modify: `settings.gradle.kts` (`include(":tunnel-protocol")`)
- Modify: `.github/workflows/ci.yml:27` (명령 끝에 `:tunnel-protocol:test`)
- Modify: `CLAUDE.md` 작업 규칙의 완료 확인 명령 -> `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test :tunnel-protocol:test`. 서버와 공용 모듈은 순수 JVM 모듈이라 `testDebugUnitTest`에 포함되지 않는다는 이유를 한 줄로 덧붙인다
- Add: `docs/superpowers/plans/2026-11-27-tls-tunnel.md` (이 문서, 이미 커밋돼 있으면 생략)

**Interfaces:**
- Produces:
  ```kotlin
  package com.mg.vpnpractice.protocol

  /** 길이 필드가 0이거나 MAX_PACKET_SIZE보다 크다. 스트림 경계가 어긋났으므로 연결을 끊어야 한다. */
  class InvalidFrameException(message: String) : IOException(message)

  object PacketFrame {
      const val HEADER_SIZE = 2
      const val MAX_PACKET_SIZE = 32767

      /** length는 1..MAX_PACKET_SIZE. 벗어나면 IllegalArgumentException. 헤더와 본문을 output.write() 한 번으로 보낸다. */
      fun write(output: OutputStream, buffer: ByteArray, length: Int)

      /**
       * 프레임 하나를 buffer에 읽고 본문 길이를 돌려준다. buffer.size >= MAX_PACKET_SIZE여야 한다.
       * 프레임 경계에서 스트림이 끝나면 -1. 헤더/본문 중간에서 끝나면 EOFException. 길이가 0이거나 최대 초과면 InvalidFrameException.
       */
      fun read(input: InputStream, buffer: ByteArray): Int
  }
  ```

- [ ] **Step 1: 모듈 뼈대 만들기.** `tunnel-protocol/build.gradle.kts`는 `plugins { alias(libs.plugins.convention.jvm.library) }`만 둔다(JUnit은 convention이 넣는다). `settings.gradle.kts`에 include를 추가한다.

- [ ] **Step 2: 실패하는 테스트 작성** (`PacketFrameTest`, 입출력은 `ByteArrayInputStream`/`ByteArrayOutputStream`)

  ```kotlin
  @Test fun round_trip_returns_same_bytes()            // write(1,2,3) -> read == 3, buffer[0..3) == [1,2,3]
  @Test fun write_puts_big_endian_length_header()      // write 3바이트 -> 출력 == [0x00, 0x03, 1, 2, 3]
  @Test fun write_uses_single_write_call()             // write 호출 수를 세는 OutputStream: 1
  @Test fun multiple_frames_in_one_stream_are_read_in_order() // [1], [2,2], [3,3,3] -> 1, 2, 3바이트 순서, 마지막 read == -1
  @Test fun one_byte_at_a_time_input_is_reassembled()  // read(ByteArray, off, len)가 1바이트씩만 돌려주는 InputStream에서도 같은 결과
  @Test fun eof_at_frame_boundary_returns_minus_one()  // 빈 입력 -> -1
  @Test(expected = EOFException::class) fun eof_inside_header_throws_eof_exception()  // [0x00]
  @Test(expected = EOFException::class) fun eof_inside_body_throws_eof_exception()    // [0x00, 0x03, 1]
  @Test(expected = InvalidFrameException::class) fun zero_length_throws_invalid_frame()  // [0x00, 0x00]
  @Test(expected = InvalidFrameException::class) fun length_over_max_throws_invalid_frame() // [0x80, 0x00] (= 32768)
  @Test fun max_length_frame_is_accepted()             // 32767바이트 왕복 성공
  @Test fun write_rejects_zero_and_over_max()          // length 0, 32768 -> 둘 다 IllegalArgumentException
  ```
  `InvalidFrameException`이 `IOException`의 하위 타입이라는 것도 `zero_length_throws_invalid_frame` 안에서 `assertTrue(e is IOException)`으로 확인한다(이 경우는 `expected` 대신 try/catch로 쓴다).

- [ ] **Step 3: 테스트 실패 확인**

  Run: `./gradlew :tunnel-protocol:test`
  Expected: 컴파일 실패(`PacketFrame` 없음)

- [ ] **Step 4: `PacketFrame` 구현.** 경계에서 끝난 것과 중간에서 끝난 것을 가르려고 첫 바이트는 `input.read()`로 읽는다(-1이면 -1을 돌려준다). 나머지는 `DataInputStream(input)`의 `readUnsignedByte`/`readFully`로 읽는다. `BufferedInputStream`으로 감싸지 않는다(다음 프레임 바이트를 삼킨다). `write`는 `ByteArray(HEADER_SIZE + length)`에 헤더와 본문을 담아 한 번에 쓴다. 주석 지점: 길이 필드 크기와 최대 길이, 잘못된 길이에서 연결을 끊어야 하는 이유(경계를 다시 찾을 수 없다), 한 번의 `write`로 보내는 이유(`SSLSocket`이 `write`마다 TLS 레코드를 만들 수 있다).

- [ ] **Step 5: 테스트 통과 확인**

  Run: `./gradlew :tunnel-protocol:test`
  Expected: PASS (12개)

- [ ] **Step 6: CI와 CLAUDE.md 완료 명령 수정, 전체 확인**

  Run: `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test :tunnel-protocol:test`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

  ```bash
  git add settings.gradle.kts tunnel-protocol .github/workflows/ci.yml CLAUDE.md
  git commit -m "feat: 앱과 서버가 함께 쓰는 길이 접두어 패킷 프레임 모듈 추가"
  ```

---

### Task 2: 연습용 인증서 스크립트와 CA 리소스

**Files:**
- Create: `scripts/make-certs.sh`
- Create: `app/src/main/res/raw/practice_ca.pem` (스크립트가 만든다)
- Modify: `.gitignore` (`certs/` 추가, 개인키를 커밋하지 않는 이유를 주석으로)

**Interfaces:**
- Produces (Task 3, 4, 5가 쓰는 경로):
  - `certs/ca/ca.key`, `certs/ca/ca.crt`: CA. 마운트하지 않는다
  - `certs/server/server.p12`, `certs/server/server.p12.pass`: 정상 서버 키스토어(별칭 `server`, 체인에 CA 포함)와 무작위 비밀번호(`openssl rand -hex 16`, 개행 없이)
  - `certs/wrong-ca/server/` 같은 두 파일: 다른 CA(`certs/wrong-ca/ca/`)가 서명, SAN `IP:10.0.2.2`
  - `certs/wrong-san/server/` 같은 두 파일: 진짜 CA가 서명, SAN `IP:10.0.2.3`
  - `app/src/main/res/raw/practice_ca.pem` = `certs/ca/ca.crt` 복사본. 앱에서는 `R.raw.practice_ca`

- [ ] **Step 1: 스크립트 작성.** bash, `set -euo pipefail`, 저장소 루트에서 실행한다. 정한 값은 다음과 같다.
  - `export MSYS_NO_PATHCONV=1`: Git Bash가 `-subj "/CN=..."`를 Windows 경로로 바꾸는 것을 막는다(주석으로 남긴다)
  - 키는 RSA 2048. CA `CN=vpn-practice CA`(3650일), 서버 `CN=vpn-practice server`(825일)
  - CA 확장은 `basicConstraints=critical,CA:TRUE`와 `keyUsage=critical,keyCertSign,cRLSign`, 서버 확장은 `subjectAltName`, `extendedKeyUsage=serverAuth`, `basicConstraints=CA:FALSE`
  - `certs/ca/`가 이미 있으면 CA를 다시 만들지 않는다. CA가 바뀌면 이미 빌드한 APK가 새 서버를 믿지 않기 때문이다. 처음 만들 때는 이 점을 출력한다
  - 서버 키, CSR, crt 같은 중간 파일은 임시 폴더에서 만들고 지운다. `certs/*/server/`에는 `server.p12`와 `server.p12.pass`만 남긴다(이 폴더를 통째로 마운트하기 때문)
  - p12는 `openssl pkcs12 -export ... -name server -certfile <ca.crt> -passout file:<pass>`
  - 마지막에 `ca.crt`를 `app/src/main/res/raw/practice_ca.pem`으로 복사한다

- [ ] **Step 2: 실행하고 결과 확인**

  Run: `bash scripts/make-certs.sh && ls certs/server certs/wrong-ca/server certs/wrong-san/server`
  Expected: 세 폴더 모두 `server.p12 server.p12.pass`만 있음

  Run: `openssl pkcs12 -in certs/server/server.p12 -passin file:certs/server/server.p12.pass -nokeys -clcerts | openssl x509 -noout -ext subjectAltName,extendedKeyUsage`
  Expected: `IP Address:10.0.2.2`, `TLS Web Server Authentication`

  `certs/wrong-san`에서 같은 명령을 돌리면 `IP Address:10.0.2.3`이 나와야 한다.

- [ ] **Step 3: 체인 검증 확인.** 정상 인증서는 통과하고 wrong-ca는 실패하는지 본다.

  Run: `openssl pkcs12 -in certs/server/server.p12 -passin file:certs/server/server.p12.pass -nokeys -clcerts | openssl verify -CAfile certs/ca/ca.crt -verify_ip 10.0.2.2`
  Expected: `stdin: OK`

  같은 명령을 `certs/wrong-ca/server/`로 돌리면 `unable to get local issuer certificate`, `certs/wrong-san/server/`로 돌리면 `IP address mismatch`가 나와야 한다.

- [ ] **Step 4: 구현 전 확인 — JDK가 OpenSSL 3의 PKCS12를 읽는가.** OpenSSL 3은 기본으로 AES-256/PBKDF2 PKCS12를 만든다. JDK 17(서버 이미지 `eclipse-temurin:17-jre`)이 이것을 읽을 수 있는지는 확인하지 못했다. 로컬 JDK로 먼저 본다.

  Run: `"/c/Program Files/Java/jdk-21.0.12/bin/keytool" -list -storetype PKCS12 -keystore certs/server/server.p12 -storepass "$(cat certs/server/server.p12.pass)"`
  Expected: `server, ... PrivateKeyEntry`

  JDK 17에서의 최종 확인은 Task 3 Step 7(컨테이너 기동)에서 한다. 실패하면 스크립트의 `pkcs12 -export`에 `-keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1`을 붙인다. 그렇게 한 이유도 주석으로 남긴다.

- [ ] **Step 5: 개인키가 git에 안 잡히는지 확인**

  Run: `git status --short`
  Expected: `.gitignore`, `scripts/make-certs.sh`, `app/src/main/res/raw/practice_ca.pem`만 보임(`certs/` 없음)

  Run: `./gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL (`res/raw` 리소스 이름 규칙 통과)

- [ ] **Step 6: 커밋**

  ```bash
  git add .gitignore scripts/make-certs.sh app/src/main/res/raw/practice_ca.pem
  git commit -m "feat: 연습용 CA와 서버 인증서 생성 스크립트, 앱에 CA 공개 인증서 추가"
  ```

---

### Task 3: 서버를 TCP+TLS로 바꾸기

이 Task가 끝나면 서버는 TCP+TLS가 되고 앱은 아직 UDP다. 빌드와 테스트는 통과하지만, 앱과 서버를 잇는 확인은 Task 4 뒤에 한다.

**Files:**
- Modify: `server/build.gradle.kts` (`implementation(project(":tunnel-protocol"))`)
- Modify: `server/src/main/kotlin/com/mg/vpnpractice/server/TunnelServer.kt` (전체 재작성)
- Create: `server/src/main/kotlin/com/mg/vpnpractice/server/ServerTls.kt`
- Modify: `server/src/main/kotlin/com/mg/vpnpractice/server/Main.kt`
- Modify: `server/Dockerfile:1-6` (실행 방법 주석)
- Test: `server/src/test/kotlin/com/mg/vpnpractice/server/TunnelServerTest.kt` (`FakeTun`은 유지, 나머지 재작성)

**Interfaces:**
- Consumes: Task 1 `PacketFrame.read/write`, `InvalidFrameException`, `PacketFrame.MAX_PACKET_SIZE`. Task 2 `certs/server/server.p12`, `server.p12.pass`
- Produces:
  ```kotlin
  class TunnelServer(
      private val tun: Tun,
      private val serverSocket: ServerSocket,
      /** accept 직후, 이 연결을 현재 연결로 삼기 전에 부른다. 던지면 그 연결만 닫는다. Main은 tlsHandshake를 넘긴다. */
      private val handshake: (Socket) -> Unit = {},
  ) {
      suspend fun run()   // accept 루프 + TUN 읽기(downlink) 루프. close()되면 끝난다
      fun close()         // serverSocket, 현재 연결, tun을 닫는다. 두 번 불러도 안전
  }

  // ServerTls.kt
  fun createTlsServerSocket(port: Int, keyStoreFile: File, passwordFile: File): SSLServerSocket
  fun tlsHandshake(socket: Socket, timeoutMillis: Int = 5000)
  ```

- [ ] **Step 1: 실패하는 테스트 작성.** 서버는 `ServerSocket(0, 50, loopback)`, 클라이언트는 일반 `Socket`(`soTimeout = 2000`)이다. 프레임은 `PacketFrame`으로 주고받는다. 기존 `waitUntil` 도우미는 남긴다.

  ```kotlin
  @Test fun frame_from_client_is_written_to_tun()          // 클라이언트 프레임 [1,2,3] -> tun.written == [[1,2,3]]
  @Test fun tun_packet_is_framed_to_current_client()       // tun.feed([9,8]) -> 클라이언트 PacketFrame.read == [9,8]
  @Test fun tun_packet_is_dropped_without_client()         // 연결 없이 feed -> 예외 없음. 그 뒤 연결한 클라이언트는 다음 feed만 받는다
  @Test fun new_connection_replaces_previous()             // A 연결 -> B 연결 -> A의 read는 -1 또는 IOException. feed는 B가 받는다
  @Test fun failed_handshake_keeps_current_connection()    // handshake가 두 번째 소켓에서 IOException -> A는 계속 feed를 받는다
  @Test fun stalled_handshake_does_not_block_accept()      // 첫 소켓의 handshake가 latch에서 대기 -> 두 번째 소켓은 연결돼 feed를 받는다
  @Test fun invalid_length_closes_only_that_connection()   // A가 [0x00,0x00] 전송 -> A의 read == -1(서버가 닫음). 이어서 B는 정상 동작
  @Test fun tun_write_failure_keeps_connection()           // failNextWrites = 1 -> 첫 프레임 버림, 둘째 프레임은 tun.written에 들어감
  @Test fun close_ends_run()                               // close() 뒤 2초 안에 run() 코루틴이 끝남
  ```

- [ ] **Step 2: 테스트 실패 확인**

  Run: `./gradlew :server:test`
  Expected: 컴파일 실패(생성자 시그니처가 다름)

- [ ] **Step 3: `TunnelServer` 재작성.** 현재 연결은 `AtomicReference<Socket?>`로 둔다. 지금의 `client: SocketAddress?`가 이 자리로 바뀐다.
  - accept 루프는 소켓마다 `Dispatchers.IO` 코루틴을 띄운다. 그 코루틴은 `tcpNoDelay = true` -> `handshake(socket)`(실패하면 로그를 남기고 그 소켓만 닫는다) -> 이전 연결과 교체(`getAndSet` 후 이전 것 `close`) -> uplink 루프 순서로 돈다.
  - uplink 루프: `PacketFrame.read` -> `tun.write`. `tun.write` 실패는 로그만 남기고 계속한다. -1/`IOException`/`InvalidFrameException`이면 그 소켓을 닫고 `compareAndSet(socket, null)`한다.
  - downlink 루프: 지금처럼 서버 수명 동안 하나로 돈다. `tun.read` -> 현재 연결이 있으면 `PacketFrame.write`, 없으면 버린다. 쓰기 실패는 로그만 남긴다(교체 중에 닫힌 소켓일 수 있다).
  - accept가 `serverSocket`이 닫혀서 끝나면 `close()`해서 downlink도 끝낸다(기존 `runLoop` 패턴).
  - TUN 쓰기는 패킷 하나에 `write` 한 번이라 잠금을 추가하지 않는다.
  - 주석 지점: 마지막 연결 우선인 이유(keepalive가 없어 죽은 연결이 남을 수 있다), 핸드셰이크를 연결마다 따로 하는 이유(잘못된 인증서나 멈춘 클라이언트가 정상 연결과 accept를 막지 않게), 클래스 주석에 TCP-over-TCP 한계(meltdown, head-of-line blocking, loopback에서는 안 보인다)

- [ ] **Step 4: 테스트 통과 확인**

  Run: `./gradlew :server:test`
  Expected: PASS (9개)

- [ ] **Step 5: `ServerTls.kt`와 `Main.kt` 작성**
  - `createTlsServerSocket`: `KeyStore.getInstance("PKCS12")` 로드 -> `KeyManagerFactory`(기본 알고리즘) -> `SSLContext.getInstance("TLS").init(keyManagers, null, null)` -> `serverSocketFactory.createServerSocket(port)`, `enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")`. 비밀번호는 파일 내용을 `trim()`한다.
  - `tlsHandshake`: `socket as SSLSocket`, `soTimeout = timeoutMillis` -> `startHandshake()` -> `soTimeout = 0` -> `session.protocol`과 `cipherSuite`를 출력한다.
  - `Main.kt`: 상수 `CERT_DIR = "/certs"`, `server.p12`, `server.p12.pass`. 파일이 없으면 "scripts/make-certs.sh를 실행하고 certs/server를 /certs에 마운트" 메시지로 `check` 실패. `TunnelServer(tun, createTlsServerSocket(...), ::tlsHandshake)`. 시작 로그에 `tcp 5555 (TLS)`.
  - 주석 지점: 1.2를 함께 켜는 이유(minSdk 24, API 24~28은 1.3이 기본이 아니다), `startHandshake()`를 명시적으로 부르는 이유(인증서 오류가 패킷 오류처럼 보이지 않게), 비밀번호를 코드에 두지 않는 이유, TLS는 도청만 막고 접속자는 막지 않는다는 점(그래서 계속 `127.0.0.1`에만 연다)

- [ ] **Step 6: Dockerfile 주석 수정.** 실행 명령을 다음으로 바꾸고, `/udp`가 빠진 이유와 `127.0.0.1`을 유지하는 이유를 적는다.

  ```
  docker run -d --rm --name tunnel-server --cap-add NET_ADMIN --device /dev/net/tun -p 127.0.0.1:5555:5555 -v "$(pwd)/certs/server:/certs:ro" vpn-practice-server
  ```

- [ ] **Step 7: 컨테이너에서 TLS 확인 (JDK 17 PKCS12 호환 최종 확인 포함)**

  Run: `./gradlew :server:installDist && docker build -t vpn-practice-server server && MSYS_NO_PATHCONV=1 docker run -d --rm --name tunnel-server --cap-add NET_ADMIN --device /dev/net/tun -p 127.0.0.1:5555:5555 -v "$(pwd)/certs/server:/certs:ro" vpn-practice-server && docker logs tunnel-server`
  Expected: `tcp 5555 (TLS)` 시작 로그. 키스토어 오류가 없어야 한다(있으면 Task 2 Step 4의 대안을 적용한다)

  Run: `echo | openssl s_client -connect 127.0.0.1:5555 -CAfile certs/ca/ca.crt -verify_ip 10.0.2.2 -verify_return_error -brief`
  Expected: `Protocol version: TLSv1.3`, `Verification: OK`

  Run: `echo | openssl s_client -connect 127.0.0.1:5555 -CAfile certs/wrong-ca/ca/ca.crt -verify_return_error -brief`
  Expected: 검증 실패로 핸드셰이크 중단. 이어서 `docker logs tunnel-server`에 핸드셰이크 실패 로그가 찍히고, 서버는 계속 떠 있다

  Run: `docker stop tunnel-server`

- [ ] **Step 8: 전체 확인**

  Run: `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test :tunnel-protocol:test`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

  ```bash
  git add server
  git commit -m "feat: 터널 서버를 TCP+TLS로 바꾸고 마지막 연결 하나와 프레임 중계"
  ```

---

### Task 4: 앱 TLS 연결과 VpnService

**Files:**
- Modify: `app/build.gradle.kts` (`implementation(project(":tunnel-protocol"))`)
- Create: `app/src/main/java/com/mg/vpnpractice/vpn/TlsConnector.kt`
- Modify: `app/src/main/java/com/mg/vpnpractice/vpn/TunnelClient.kt` (전체 재작성)
- Modify: `app/src/main/java/com/mg/vpnpractice/vpn/PracticeVpnService.kt:131-193` (`runTunnel`, `readLoop`, `writeLoop`)
- Modify: `app/src/main/AndroidManifest.xml:5-7` (권한 주석의 "UDP"를 TCP 연결 기준으로), `app/src/main/java/com/mg/vpnpractice/vpn/TestPacketSender.kt:15` (`TunnelClient` 언급을 `TlsConnector`의 TCP 소켓으로)
- Modify: `CLAUDE.md` 프로젝트 "구조" 문단. 새 문장: `Android 앱(VpnService, TUN) <-TCP+TLS 터널(길이 접두어 프레임)-> Docker로 띄운 터널 서버(server/ 모듈, Kotlin/JVM + JNA로 Linux TUN 제어). 프레임 형식은 공용 모듈 tunnel-protocol/에 있다. TLS는 연습용 CA로 서버만 검증하고, 사용자 인증은 8단계에서 붙인다.`
- Test: `app/src/test/java/com/mg/vpnpractice/vpn/TunnelClientTest.kt` (재작성), `app/src/test/java/com/mg/vpnpractice/vpn/TlsConnectorTest.kt` (신규)

**Interfaces:**
- Consumes: Task 1 `PacketFrame`, `InvalidFrameException`. Task 2 `R.raw.practice_ca`(테스트에서는 `File("src/main/res/raw/practice_ca.pem")`, CN `vpn-practice CA`). Task 3 서버(포트 5555, TLS)
- Produces:
  ```kotlin
  /** Android 의존성이 없다(Log 금지). JVM 단위 테스트 대상이다. */
  object TlsConnector {
      const val SERVER_HOST = "10.0.2.2"
      const val SERVER_PORT = 5555
      const val CONNECT_TIMEOUT_MS = 5000
      const val HANDSHAKE_TIMEOUT_MS = 5000

      /** CA 인증서 하나만 담은 KeyStore로 TrustManager를 만든다. 시스템 CA는 믿지 않는다. */
      fun trustManagerFor(caCert: InputStream): X509TrustManager

      /** TCP 연결 -> TLS 핸드셰이크 -> 호스트 이름 검증까지 마친 소켓. 하나라도 실패하면 소켓을 닫고 IOException을 던진다. */
      fun connect(trustManager: X509TrustManager, host: String, port: Int): SSLSocket
  }

  /** 연결된 소켓 위에서 IP 패킷을 프레임으로 주고받는다. TLS를 모른다. */
  class TunnelClient(private val socket: Socket) : Closeable {
      val isClosed: Boolean
      fun send(buffer: ByteArray, length: Int)   // PacketFrame.write
      fun receive(buffer: ByteArray): Int        // PacketFrame.read. 서버가 닫으면 -1
      override fun close()                       // 두 번 불러도 안전. 블로킹 중인 receive()를 깨운다
  }
  ```
  `TunnelClient.SERVER_HOST`/`SERVER_PORT`와 `tolerate()`는 없어진다.

- [ ] **Step 1: 구현 전 확인 — Android TLS 사실 세 가지.** Android 개발자 문서 "Security with network protocols"(`https://developer.android.com/privacy-and-security/security-ssl`)의 SSLSocket 경고 절과 `SSLSocket` API 레퍼런스를 읽고, 결과를 PR 설명에 적는다.
  - (a) `SSLSocket`이 호스트 이름을 검증하지 않아서 `HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)`로 직접 검증하라고 권하는가. 문서가 다르게 말하면 **멈추고 보고한다**(설계의 반목표가 이 사실에 기대고 있다)
  - (b) `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`가 API 24에서 동작한다고 문서에 나오는가. 결과만 기록하고 구현은 (a)의 방식으로 한다
  - (c) TLS 1.3이 API 29부터 기본으로 켜져 있는가. 다르면 Task 3의 서버 프로토콜 주석을 고친다

- [ ] **Step 2: 실패하는 테스트 작성**

  `TunnelClientTest` (일반 `ServerSocket(0, 50, loopback)` + `Socket`, 서버 쪽은 `PacketFrame`으로 읽고 쓴다):
  ```kotlin
  @Test fun send_writes_one_frame()                        // send([1,2,3]) -> 서버 PacketFrame.read == [1,2,3]
  @Test fun receive_returns_frame_payload()                // 서버 write([9,8]) -> receive == 2, [9,8]
  @Test fun receive_returns_minus_one_when_server_closes() // 서버 소켓 close -> -1
  @Test(expected = InvalidFrameException::class) fun receive_throws_on_invalid_length() // 서버가 [0x00,0x00] 전송
  @Test fun close_unblocks_receive()                       // 다른 스레드에서 receive 대기 중 close -> 2초 안에 IOException 또는 -1로 빠져나옴
  @Test fun close_twice_is_safe()                          // close 두 번, isClosed == true
  ```

  `TlsConnectorTest`:
  ```kotlin
  @Test fun trust_manager_accepts_only_the_practice_ca()   // acceptedIssuers.size == 1, subjectX500Principal.name에 "CN=vpn-practice CA"
  @Test fun connect_to_closed_port_throws_io_exception()   // 닫힌 로컬 포트로 connect -> IOException, 6초 안에
  ```

- [ ] **Step 3: 테스트 실패 확인**

  Run: `./gradlew testDebugUnitTest --tests "com.mg.vpnpractice.vpn.TunnelClientTest" --tests "com.mg.vpnpractice.vpn.TlsConnectorTest"`
  Expected: 컴파일 실패

- [ ] **Step 4: `TlsConnector`와 `TunnelClient` 구현**
  - `trustManagerFor`: `CertificateFactory.getInstance("X.509")` -> `KeyStore.getInstance(KeyStore.getDefaultType()).load(null, null)` -> `setCertificateEntry("practice-ca", cert)` -> `TrustManagerFactory`(기본 알고리즘)`.init(keyStore)` -> `X509TrustManager` 하나를 꺼낸다.
  - `connect`: 먼저 일반 `Socket()`에 `tcpNoDelay = true`를 켜고 `connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)`한다. 그 위에 `sslContext.socketFactory.createSocket(plain, host, port, true)`로 TLS를 얹는다. TCP 위에 TLS를 얹는 구조가 코드에 드러나고, 7단계에서 `protect()`를 끼울 자리(일반 소켓의 connect 전)도 생긴다. 이어서 `soTimeout = HANDSHAKE_TIMEOUT_MS` -> `startHandshake()` -> `HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)`. `false`면 `SSLPeerUnverifiedException`을 던진다. 마지막으로 `soTimeout = 0`. 실패하면 두 소켓을 모두 닫고 다시 던진다.
  - 주석 지점(`TlsConnector`): 연습용 CA만 믿고 시스템 CA는 믿지 않는 이유, 호스트 이름 검증을 직접 하는 이유와 끄면 생기는 일(같은 CA의 다른 인증서도 믿게 된다), `startHandshake()`를 명시적으로 부르는 이유, `TCP_NODELAY`를 켜는 이유(Nagle이 안쪽 TCP의 작은 패킷을 묶어 지연시킨다), 핸드셰이크 뒤 읽기 타임아웃을 0으로 돌리는 이유(터널은 오래 조용할 수 있다), `protect()`가 여전히 필요 없는 이유(기존 `TunnelClient` 주석을 옮긴다: 서버 `10.0.2.2`가 라우트 `10.0.0.0/24` 밖)
  - 주석 지점(`TunnelClient`): TCP-over-TCP 한계를 짧게 적고 서버 클래스 주석을 가리킨다

- [ ] **Step 5: 테스트 통과 확인**

  Run: `./gradlew testDebugUnitTest --tests "com.mg.vpnpractice.vpn.TunnelClientTest" --tests "com.mg.vpnpractice.vpn.TlsConnectorTest"`
  Expected: PASS (8개)

- [ ] **Step 6: `PracticeVpnService` 수정**
  - `runTunnel()`: 기존 소켓 생성 자리에서 `resources.openRawResource(R.raw.practice_ca).use { TlsConnector.trustManagerFor(it) }`를 부르고 `TlsConnector.connect(...)`로 `TunnelClient`를 만든다. `IOException`과 `GeneralSecurityException`을 잡으면 `Log.w` 후 `stopVpn()`한다(기존 분기와 같다). 연결되면 `session.protocol`과 `cipherSuite`를 `Log.i`로 남긴다. 기존 `tunFd == null` 확인은 유지한다.
  - `readLoop`: `tolerate` 없이 `client.send`. 소켓 `IOException`이면 루프를 끝낸다. 루프가 어떤 이유로 끝나든 `stopIfCurrent(client)`.
  - `writeLoop`: `client.receive`가 -1이거나 `IOException`(`InvalidFrameException` 포함)이면 끝내고 `stopIfCurrent(client)`. TUN 쓰기 실패(`writePacket`)는 지금처럼 로그만 남기고 계속한다.
  - 새 함수 `private fun stopIfCurrent(client: TunnelClient)`: `tunnel === client`일 때만 `stopVpn()`한다.
  - 주석 지점: 끊기면 VPN을 멈추는 이유(TCP 스트림은 다시 쓸 수 없고, "실행 중인데 아무것도 안 되는" 상태를 만들지 않는다), 재연결이 없는 이유(9단계의 실행 중 상태 전환과 같은 문제), `stopIfCurrent`가 필요한 이유(중지 후 바로 다시 시작했을 때 앞 세션의 늦은 루프가 새 세션을 멈추지 않게), IO 스레드에서 `stopVpn()`을 부르는 기존 경합은 9단계에서 정리한다는 점, TUN 쓰기 실패는 연결 문제가 아니라서 견디는 이유

- [ ] **Step 7: 매니페스트, `TestPacketSender`, `CLAUDE.md` 구조 문단 수정** (Files 목록의 문장대로)

- [ ] **Step 8: 전체 확인**

  Run: `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test :tunnel-protocol:test`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

  ```bash
  git add app CLAUDE.md
  git commit -m "feat: 앱 터널을 연습용 CA로 검증하는 TLS 연결로 바꾸고 끊기면 VPN 중지"
  ```

---

### Task 5: 에뮬레이터 수동 확인

코드는 바꾸지 않는다. 확인 중에 문제가 나오면 해당 Task로 돌아가 고치고 커밋한다. 결과는 PR 설명에 적는다.

**Files:** 없음 (문제가 나오면 해당 Task의 파일)

**Interfaces:**
- Consumes: Task 2의 `certs/{server,wrong-ca/server,wrong-san/server}`, Task 3의 이미지와 실행 명령, Task 4의 APK와 Logcat 태그 `PracticeVpn`

- [ ] **Step 1: 준비.** 서버 이미지를 빌드하고 정상 인증서로 띄운다(Task 3 Step 7 명령). 에뮬레이터 `Pixel_8`은 사용자 CLAUDE.md대로 `Start-Process`로 세션과 분리해 띄우고, `sys.boot_completed`가 1이 될 때까지 기다린다. adb는 항상 `-s emulator-5554`를 붙인다.

  Run: `./gradlew assembleDebug && adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`
  Expected: `Success`

- [ ] **Step 2: 정상 경로.** `adb -s emulator-5554 logcat -s PracticeVpn`을 보면서 확인한다.
  - "전체 앱"으로 시작 -> Logcat에 `TLSv1.3`과 cipher suite
  - 테스트 패킷 버튼 -> `[to-server]`와 `[from-server] ... proto=ICMP`
  - "Chrome만"으로 다시 시작 -> Chrome에서 `http://10.0.0.1` -> `[to-server]` SYN과 `[from-server]` RST
  - 어느 프리셋에서든 Chrome으로 일반 웹사이트가 열린다

- [ ] **Step 3: 검증 실패 경로.** 서버를 `certs/wrong-ca/server`로 다시 띄운다(`-v` 경로만 바꾼다). VPN 시작 -> Logcat에 `SSLHandshakeException`, 화면은 "중지됨", 알림이 사라진다. `certs/wrong-san/server`로 바꾸면 호스트 이름 검증 실패(`SSLPeerUnverifiedException`)로 똑같이 중지된다. 끝나면 정상 인증서로 되돌린다.

- [ ] **Step 4: 끊김과 재시작.**
  - 실행 중에 `docker stop tunnel-server` -> 앱이 "중지됨"으로 바뀐다
  - 서버를 끈 채로 시작 -> 5초 안에 "중지됨", 앱은 죽지 않는다
  - 서버를 켜고 시작 -> 중지 -> 곧바로 시작을 여러 번 반복 -> 마지막 시작이 "실행 중"으로 남고 테스트 패킷이 왕복한다(Review Focus의 `stopIfCurrent`)
  - `docker logs tunnel-server`에서 Step 3의 실패 접속 뒤에도 정상 연결을 받았는지 본다

- [ ] **Step 5: 최종 확인**

  Run: `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test :tunnel-protocol:test && git status --short`
  Expected: BUILD SUCCESSFUL. `git status`에 `certs/`나 개인키가 보이지 않는다

---

## 자체 점검 결과

- 설계 문서 대비 빠진 것 없음: 프레이밍(Task 1), 스크립트/출력 폴더/gitignore/CA 리소스(Task 2), 서버 구조/교체/TLS 버전/키스토어/Docker(Task 3), 앱 조립/핸드셰이크/호스트 이름 검증/타임아웃/끊김/주석/매니페스트(Task 4), CLAUDE.md 두 군데(Task 1 완료 명령, Task 4 구조), CI(Task 1), 수동 확인(Task 5)
- "구현 전 확인" 항목의 위치: PKCS12 호환은 Task 2 Step 4와 Task 3 Step 7, Android TLS 세 가지는 Task 4 Step 1
- 이름 일관성: `PacketFrame.read/write`, `InvalidFrameException`, `TlsConnector.trustManagerFor/connect`, `TunnelClient(socket)`, `stopIfCurrent`, `TunnelServer(tun, serverSocket, handshake)`, `createTlsServerSocket`, `tlsHandshake`
