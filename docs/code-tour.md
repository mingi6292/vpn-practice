# 코드 투어

Android Studio에서 코드를 직접 읽기 전에 보는 안내서다. 어떤 순서로 읽고, 각 파일에서 무엇을 봐야 하며, 왜 이렇게 짰는지를 정리했다. 줄 번호는 `main`(`f896d0f`) 기준이다. 코드가 바뀌면 줄 번호도 어긋날 수 있다.

## 1. 한눈에 보기

6단계까지 끝난 상태다. 지금 앱은 이렇게 동작한다.

- `VpnService`로 TUN 인터페이스(`10.0.0.2/24`)를 연다.
- split tunnel: `10.0.0.0/24`만 터널로 보낸다. 앱 필터 프리셋은 "전체 앱", "Chrome만", "Chrome 제외" 세 가지다.
- TUN에서 읽은 IP 패킷을 길이 접두어 프레임(`[길이 2바이트][본문]`)으로 감싸서 TCP+TLS 연결 하나로 Docker 안의 터널 서버에 보낸다.
- 서버는 받은 패킷을 Linux `tun0`(`10.0.0.1/24`)에 쓴다. 컨테이너 커널이 만든 응답을 거꾸로 앱의 TUN에 돌려준다.
- TLS는 연습용 CA로 **서버만** 검증한다(인증서 체인과 호스트 이름 `10.0.2.2`). 사용자 인증은 없다(8단계).

```
 [에뮬레이터]                                                    [Docker 컨테이너]

 Chrome / 테스트 패킷 (dst 10.0.0.x)
        │ 라우트 10.0.0.0/24
        ▼
   TUN fd (10.0.0.2)
        │ read()                                         Linux tun0 (10.0.0.1)
        ▼                                                      ▲   │
 PracticeVpnService.readLoop                       tun.write() │   │ tun.read()
        │ TunnelClient.send                                    │   ▼
        ▼ PacketFrame.write                        TunnelServer.uplink   TunnelServer.downlink
   SSLSocket ════ TLS over TCP, 10.0.2.2:5555 ════▶ SSLSocket (accept) ──▶ PacketFrame.read
                                               ◀════            ◀── PacketFrame.write (현재 연결)
        │ TunnelClient.receive / PacketFrame.read
        ▼
 PracticeVpnService.writeLoop
        │ writePacket()
        ▼
   TUN fd ──▶ 시스템 네트워크 스택 ──▶ 원래 앱이 응답을 받는다
```

## 2. 모듈과 폴더 지도

| 위치 | 정체 | 핵심 파일 |
|------|------|-----------|
| `app/` | Android 앱 | `vpn/PracticeVpnService.kt` VpnService 본체. 생명주기, `establish()`, 읽기/쓰기 루프, 중지 |
| | | `vpn/TlsConnector.kt` CA로 `SSLContext` 조립, 연결, 핸드셰이크, 호스트 이름 검증 |
| | | `vpn/TunnelClient.kt` 연결된 소켓 위에서 프레임 송수신만 |
| | | `vpn/TunnelPolicy.kt` 라우트와 앱 필터 정책 모델(순수 Kotlin) |
| | | `vpn/Ipv4Packet.kt` IPv4 헤더 파서(로그용) |
| | | `vpn/TunWrite.kt` TUN 쓰기 실패를 견딜지 던질지 판단 |
| | | `vpn/TestPacketSender.kt` `10.0.0.1:9999`로 UDP 한 개 보내는 확인용 |
| | | `MainActivity.kt` 화면, 권한, VPN 동의(`prepare()`) |
| | | `AndroidManifest.xml` 권한, `<queries>`, 서비스 선언 |
| | | `res/raw/practice_ca.pem` 연습용 CA **공개** 인증서(커밋됨) |
| `tunnel-protocol/` | 앱과 서버가 함께 쓰는 순수 JVM 모듈 | `PacketFrame.kt` 프레임 읽기/쓰기. 양쪽 형식이 같아야 하는 유일한 코드라 한 곳에 둔다 |
| `server/` | 터널 서버(Kotlin/JVM, Docker에서 실행) | `Main.kt` 진입점. TUN 열기, `ip` 명령, TLS 서버 소켓 |
| | | `ServerTls.kt` PKCS12 키스토어로 `SSLServerSocket`, 핸드셰이크 |
| | | `TunnelServer.kt` accept 루프, 연결마다 uplink, downlink 하나 |
| | | `Tun.kt` / `LinuxTun.kt` TUN 추상화와 JNA 실구현 |
| | | `Dockerfile` 이미지와 실행 명령(주석) |
| `build-logic/` | Gradle convention plugin | `KotlinAndroid.kt` compileSdk 37, minSdk 24, JVM 17. 처음엔 훑기만 해도 된다 |
| `scripts/` | 도구 스크립트 | `make-certs.sh` 연습용 CA와 서버 인증서 생성 |
| `certs/` | 스크립트 출력. **gitignore** | `ca/`(CA 개인키), `server/`(컨테이너에 마운트), `wrong-ca/`, `wrong-san/`(실패 시나리오용) |
| `docs/` | 문서 | `vpn-practice-plan.md` 단계 계획, `superpowers/specs/` 단계별 설계, `docker-guide.md` Docker 기초 |

건너뛰어도 되는 것: `ui/theme/*`, `ExampleUnitTest.kt`, `ExampleInstrumentedTest.kt`, `res/mipmap*`와 런처 아이콘 drawable, `keepRules/rules.keep`(템플릿 그대로).

## 3. 추천 읽기 순서

바깥에서 안으로가 아니라 **작은 부품에서 조립품으로** 읽는다. 부품은 Android 의존성이 없어 테스트와 함께 읽으면 동작이 바로 보인다.

1. [PacketFrame.kt](../tunnel-protocol/src/main/kotlin/com/mg/vpnpractice/protocol/PacketFrame.kt)
   - 볼 것: TCP는 바이트 스트림이라 패킷 경계가 없다. 그래서 길이 2바이트를 앞에 붙인다.
   - `write()` 21줄: 길이 범위 검사(22), 헤더와 본문을 `write` 한 번으로 보내는 이유(27~29)
   - `read()` 36줄: 첫 바이트만 직접 읽어 "경계에서 정상 종료(-1)"와 "중간에서 끊김(`EOFException`)"을 나누는 방법(37~40), 잘못된 길이면 연결을 끊어야 하는 이유(43~46)
   - 테스트: [PacketFrameTest.kt](../tunnel-protocol/src/test/kotlin/com/mg/vpnpractice/protocol/PacketFrameTest.kt) `write_uses_single_write_call`(40), `one_byte_at_a_time_input_is_reassembled`(62), `eof_inside_header_throws_eof_exception`(80), `max_length_frame_is_accepted`(105)

2. [TlsConnector.kt](../app/src/main/java/com/mg/vpnpractice/vpn/TlsConnector.kt)
   - 볼 것: "무엇을 근거로 서버를 믿는가"의 전 과정이 이 파일에 있다.
   - `trustManagerFor()` 32줄: 인증서 -> 빈 `KeyStore` -> `TrustManagerFactory`. 시스템 CA를 믿지 않는 이유(27~31 주석)
   - `connect()` 44줄: `protect()`를 안 부르는 이유(47~49), `tcpNoDelay`(54), 연결된 TCP 소켓 위에 TLS 얹기(58), 명시적 `startHandshake()`(63), 호스트 이름 직접 검증(65~69), 타임아웃을 0으로 되돌리기(72), 실패하면 소켓 닫고 다시 던지기(74~78)
   - 테스트: [TlsConnectorTest.kt](../app/src/test/java/com/mg/vpnpractice/vpn/TlsConnectorTest.kt) `trust_manager_accepts_only_the_practice_ca`(17), `connect_to_closed_port_throws_io_exception`(26). 호스트 이름 검증은 JVM과 Android의 기본 verifier가 달라서 단위 테스트가 없고 에뮬레이터에서 확인했다.

3. [TunnelClient.kt](../app/src/main/java/com/mg/vpnpractice/vpn/TunnelClient.kt)
   - 볼 것: TLS를 모르고 `Socket`만 받는다. 그래서 일반 TCP 소켓으로 테스트할 수 있다.
   - `send()` 22, `receive()` 27, `close()` 30(블로킹 중인 `receive()`를 깨우는 방법). TCP-over-TCP 한계 주석(11~12)
   - 테스트: [TunnelClientTest.kt](../app/src/test/java/com/mg/vpnpractice/vpn/TunnelClientTest.kt) `receive_returns_minus_one_when_server_closes`(61), `close_unblocks_receive`(75)

4. [PracticeVpnService.kt](../app/src/main/java/com/mg/vpnpractice/vpn/PracticeVpnService.kt) — 가장 중요하다. 네 덩어리로 나눠 읽는다.
   - **필드**(35~42): `tunFd`, `tunnel`이 `@Volatile`인 이유는 메인 스레드와 IO 스레드가 함께 보기 때문이다.
   - **시작**: `onStartCommand()` 44(`START_NOT_STICKY` 52), `startVpn()` 55. 순서는 포그라운드(59) -> 정책 해석(61) -> `Builder`(71~78) -> 허용 목록이 비면 거부(79~84) -> `establish()`(87, null 처리 88~93) -> 상태 설정(94, 97) -> `runTunnel` 실행(98)
   - **터널**: `runTunnel()` 136. IO 스레드에서 연결(140~143), 실패 처리(144~153), 늦게 끝난 연결 확인 두 번(157~166), 루프 두 개 실행(167~168). `readLoop()` 172(TUN -> 서버), `writeLoop()` 196(서버 -> TUN). 둘 다 `finally`에서 `stopIfCurrent()`(191, 218)
   - **중지**: `stopIfCurrent()` 230, `stopIfSession()` 235, `stopVpn()` 253, `onRevoke()` 281, `onDestroy()` 287
   - 이 파일 자체의 단위 테스트는 없다. 순수 로직은 아래 파일로 빼서 테스트한다.

5. 정책과 보조 로직
   - [TunnelPolicy.kt](../app/src/main/java/com/mg/vpnpractice/vpn/TunnelPolicy.kt): `AppRule`을 sealed로 둔 이유(10~19, 허용/제외를 섞으면 예외), `resolve()` 30(허용 목록이 비면 거부 35~38), `PolicyPreset.fromName()` 60, `PRACTICE_ROUTES` 66. 테스트: [TunnelPolicyTest.kt](../app/src/test/java/com/mg/vpnpractice/vpn/TunnelPolicyTest.kt) `only_with_no_installed_package_is_rejected`(35)
   - [Ipv4Packet.kt](../app/src/main/java/com/mg/vpnpractice/vpn/Ipv4Packet.kt): `parse()` 29, 옵션 헤더 때문에 포트 위치가 `headerLength` 기준인 것(41~42), `u8()` 53의 `and 0xFF`. 테스트: [Ipv4PacketTest.kt](../app/src/test/java/com/mg/vpnpractice/vpn/Ipv4PacketTest.kt) `handles header options`(72)
   - [TunWrite.kt](../app/src/main/java/com/mg/vpnpractice/vpn/TunWrite.kt): `writePacket()` 10. TUN이 열려 있으면 실패를 견디고, 닫혔으면 다시 던진다(19). 테스트: [TunWriteTest.kt](../app/src/test/java/com/mg/vpnpractice/vpn/TunWriteTest.kt)
   - [TestPacketSender.kt](../app/src/main/java/com/mg/vpnpractice/vpn/TestPacketSender.kt): `send()` 11, 여기서 `protect()`를 부르면 안 되는 이유(14~15). 테스트: [TestPacketSenderTest.kt](../app/src/test/java/com/mg/vpnpractice/vpn/TestPacketSenderTest.kt)

6. [MainActivity.kt](../app/src/main/java/com/mg/vpnpractice/MainActivity.kt)와 [AndroidManifest.xml](../app/src/main/AndroidManifest.xml)
   - `runningPreset` 관찰(70), 동의 결과 처리(79~81), 런타임 권한(85, `runtimePermissions()` 142), `requestVpnConsent()` 151의 `VpnService.prepare()`(152), `startVpnService()` 156, 중지 버튼(124~128), 테스트 패킷을 IO에서 보내기(129~137)
   - 매니페스트: `ACCESS_LOCAL_NETWORK`(8), `<queries>`(14), 서비스 선언(41~50, `systemExempted` 45, `BIND_VPN_SERVICE` 46)

7. 서버
   - [Main.kt](../server/src/main/kotlin/com/mg/vpnpractice/server/Main.kt): `main()` 15. 인증서 파일 확인(18) -> `LinuxTun.open`(22) -> `ip addr`/`ip link up`(24~25) -> TLS 서버 소켓(27) -> `TunnelServer`(28) -> 종료 훅(30) -> `run()`(33)
   - [ServerTls.kt](../server/src/main/kotlin/com/mg/vpnpractice/server/ServerTls.kt): `createTlsServerSocket()` 18. 비밀번호를 파일에서 읽기(19), `KeyManager`만 있고 `TrustManager`는 null(26~28, 클라이언트 인증 없음), TLS 1.3+1.2(31~33). `tlsHandshake()` 42
   - [TunnelServer.kt](../server/src/main/kotlin/com/mg/vpnpractice/server/TunnelServer.kt): `run()` 32, `close()` 42, `runLoop()` 48, `acceptLoop()` 56(연결마다 따로 `serve` 67), `serve()` 71(마지막 연결 우선 81~83, 닫힌 서버 확인 84~85), `uplink()` 91(`compareAndSet` 112), `downlink()` 117(연결 없으면 버림 130~133)
   - [Tun.kt](../server/src/main/kotlin/com/mg/vpnpractice/server/Tun.kt) / [LinuxTun.kt](../server/src/main/kotlin/com/mg/vpnpractice/server/LinuxTun.kt): `read()` 31에서 `poll()`을 쓰는 이유(32~33), `close()` 56, `IFF_NO_PI`(72~73), `open()` 81(`TUNSETIFF` 92)
   - 테스트: [TunnelServerTest.kt](../server/src/test/kotlin/com/mg/vpnpractice/server/TunnelServerTest.kt) `new_connection_replaces_previous`(159), `failed_handshake_keeps_current_connection`(173), `stalled_handshake_does_not_block_accept`(188), `invalid_length_closes_only_that_connection`(203)

8. 인프라
   - [make-certs.sh](../scripts/make-certs.sh): `make_ca` 16(`CA:TRUE`), `make_server` 28(SAN `IP:...`과 `serverAuth` 37), 무작위 비밀번호(43), 실패 시나리오용 인증서(58~62), 앱에는 공개 인증서만 복사(65)
   - [Dockerfile](../server/Dockerfile): 실행 명령(3)과 `127.0.0.1`에만 여는 이유(6~7), glibc 이미지를 쓰는 이유(9)

## 4. 흐름 따라가기

### (a) "시작" 버튼을 누르면

1. `MainActivity` 시작 버튼(116): 알림/로컬 네트워크 권한이 필요하면 먼저 요청한다(85). 거부해도 다음 단계로 간다.
2. `requestVpnConsent()`(151): `VpnService.prepare()`가 null이면 이미 동의한 상태라 바로 시작하고, Intent를 주면 시스템 동의 화면을 띄운다. `RESULT_OK`일 때만 시작한다(80).
3. `startVpnService()`(156): 프리셋 **이름**만 extra로 넣어 `startService`.
4. `onStartCommand()`(44) -> `startVpn()`(55), 메인 스레드다.
   - 이미 `tunFd`가 있으면 무시(56). 정책은 `establish()` 때 고정되기 때문이다.
   - `startAsForeground()`(59, 293): `FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED`(309).
   - `resolve(::isInstalled)`(61)로 설치 안 된 앱을 거르고, `Builder`에 주소/MTU/라우트/앱 목록을 넣는다(71~78).
   - `establish()`(87)가 TUN fd를 돌려준다. null이면 동의 철회나 다른 VPN 우선이라 중지한다.
   - `_runningPreset`을 **launch 전에** 설정한다(95~97). 터널이 곧바로 실패해 `stopVpn()`이 null로 돌린 뒤 덮어쓰면 화면이 "실행 중"에 갇힌다.
5. `runTunnel()`(136), IO 스레드다. CA 읽기 -> `TlsConnector.connect()` -> `TunnelClient`. 실패하면 `stopIfSession(fd)`.
6. 연결이 끝났을 때 `tunFd`가 여전히 이 세션의 fd인지 확인하고(157), `tunnel`에 넣은 뒤 **한 번 더** 확인한다(162). 두 줄 사이에 `stopVpn()`이 지나갔다면 아무도 이 클라이언트를 닫아 주지 않기 때문이다.
7. `readLoop`/`writeLoop`를 각각 코루틴으로 띄운다(167~168).

### (b) 패킷 하나의 왕복

"전체 앱" 프리셋에서 테스트 패킷 버튼을 누른 경우다.

1. `TestPacketSender.send()`가 UDP `hello`를 `10.0.0.1:9999`로 보낸다. 목적지가 라우트 `10.0.0.0/24` 안이라 커널이 TUN으로 보낸다.
2. `readLoop`의 `input.read(buffer)`(179)가 IP 패킷 하나를 받는다. `logPacket("[to-server]")` 뒤 `client.send()`(185) -> `PacketFrame.write()` -> TLS 레코드로 암호화되어 `10.0.2.2:5555`로 나간다. `10.0.2.2`는 라우트 밖이라 이 TCP 소켓은 TUN을 타지 않는다.
3. 서버 `uplink()`가 `PacketFrame.read()`(97)로 꺼내 `tun.write()`(101)로 `tun0`에 쓴다.
4. 컨테이너 커널이 `10.0.0.1`의 9999 포트에 아무도 없으니 ICMP port unreachable을 `10.0.0.2`로 만든다. 이 응답은 `tun0`으로 나간다.
5. `downlink()`의 `tun.read()`(121)가 받아 현재 연결에 `PacketFrame.write()`(136).
6. 앱 `writeLoop`의 `client.receive()`(202)가 받아 `logPacket("[from-server]")` 뒤 `writePacket()`(210)으로 TUN fd에 쓴다. 시스템 스택이 응답을 원래 소켓에 전달한다.

Chrome으로 `http://10.0.0.1`을 열면 같은 길로 TCP SYN이 가고, 서버 커널의 RST가 돌아온다.

### (c) "중지"와 끊김 처리

중지 경로는 네 가지다. 모두 `stopVpn()`(253)에 모인다.

- 사용자 "중지" 버튼 -> `ACTION_STOP` -> `onStartCommand()`(45~46). 메인 스레드
- `onRevoke()`(281): 다른 VPN이 켜지거나 사용자가 설정에서 해제. 메인 스레드
- `onDestroy()`(287)
- 루프가 끝날 때 `stopIfCurrent()`(230), 연결 실패 때 `stopIfSession()`(235). IO 스레드

`stopVpn()`에서 볼 것:

- **필드를 먼저 비우고 닫는다**(255~260). fd와 소켓을 닫으면 블로킹 중인 `read()`/`receive()`가 예외로 깨어나 `finally`에서 `stopIfCurrent(client)`를 부른다. 이때 `tunnel`이 아직 그 client면 IO 스레드에서 `stopVpn()`이 한 번 더 돈다. 먼저 비워 두면 `tunnel === client`가 거짓이라 아무 일도 없다.
- **fd를 닫아 `read()`를 깨운다**(262). 블로킹 `read()`는 코루틴 취소로 깨어나지 않는다(176~177 주석). `writeLoop`가 `FileOutputStream`을 `use {}`로 닫지 않는 것도 같은 fd를 공유해서다(198).
- **TLS 소켓은 별도 스레드에서 닫는다**(263~273). `SSLSocket.close()`는 `close_notify`를 네트워크로 보내므로 메인 스레드에서 부르면 `NetworkOnMainThreadException`이 난다. `serviceScope`가 아니라 일반 스레드인 이유는 곧 `onDestroy()`에서 scope가 취소되어도 닫기는 끝까지 실행되게 하려는 것이다. 일반 스레드의 잡히지 않은 예외는 앱을 죽이므로 `IOException`을 잡는다.
- 그다음 job 취소(275), 포그라운드 해제(277), `stopSelf()`(278).

**`stopIfCurrent`/`stopIfSession`이 있는 이유**: 사용자가 중지 후 곧바로 다시 시작하면, 앞 세션의 루프나 연결 시도(최대 5초 이상)가 늦게 끝난다. 그때 무조건 `stopVpn()`을 부르면 방금 시작한 새 세션을 멈춘다. 그래서 "끝난 것이 지금 세션의 것일 때만" 멈춘다. 비교는 `===`(같은 객체인지)다.

끊김 정책: TCP 스트림은 한 번 깨지면 다시 쓸 수 없어서, 서버가 끊거나 I/O가 실패하거나 잘못된 프레임이 오면 VPN을 멈춘다(222~229). 자동 재연결은 없다. 반대로 TUN 쓰기 실패 하나는 연결 문제가 아니라서 로그만 남긴다(208~212).

## 5. 스레드 지도

앱

| 코드 | 스레드 |
|------|--------|
| `MainActivity` 전체, `onStartCommand`, `startVpn`, `onRevoke`, `onDestroy`, 버튼에서 오는 `stopVpn` | 메인 |
| `runTunnel` (`serviceScope` = `SupervisorJob + Dispatchers.IO`, 35) | IO |
| `readLoop`, `writeLoop` (각각 IO 스레드 하나를 블로킹으로 점유) | IO |
| `stopIfCurrent`/`stopIfSession` -> `stopVpn` | **IO** |
| `TunnelClient.close()` in `stopVpn` | 별도 스레드 `tunnel-close` |
| `TestPacketSender.send` (`scope.launch(Dispatchers.IO)`, 133) | IO |

서버

| 코드 | 스레드 |
|------|--------|
| `main()`의 `runBlocking { server.run() }` | 메인(대기만 함) |
| `downlink` (서버 수명 동안 하나), `acceptLoop` | `Dispatchers.IO` 코루틴 각각 |
| `serve`/`uplink` (연결마다 하나) | `Dispatchers.IO` 코루틴 |
| 종료 훅의 `server.close()` | 종료 훅 스레드 |

서버의 공유 상태는 `current`(`AtomicReference`, 29) 하나라서 `getAndSet`/`compareAndSet`으로 정리된다.

**알려진 경합(9단계)**: 앱의 `stopVpn()`은 메인 스레드와 IO 스레드에서 동시에 불릴 수 있다. `@Volatile`은 가시성만 보장하고, "읽고 비우기"(257~260)는 원자적이지 않다. `tunnelJob`, `_runningPreset`, `startVpn()`과의 겹침도 마찬가지다. 6단계에서 끊길 때마다 IO 스레드가 `stopVpn()`을 부르게 되어 경로가 늘었다. 재 `establish()`와 함께 9단계에서 정리한다(36~37 주석).

## 6. 설명할 수 있어야 할 질문

1. `VpnService.prepare()`가 null을 돌려줄 때와 Intent를 돌려줄 때 흐름은 어떻게 다른가? 힌트: `MainActivity.kt:151`
2. `establish()`가 돌려주는 것은 무엇이고, 언제 null인가? 라우트와 앱 목록은 언제 고정되나? 힌트: `PracticeVpnService.kt:86`
3. `addRoute("10.0.0.0", 24)` 대신 `0.0.0.0/0`이면 무엇이 달라지고 지금 코드에서는 무엇이 깨지나? 힌트: `PracticeVpnService.kt:75`, `TlsConnector.kt:47`
4. 지금 `protect()` 없이도 터널 소켓이 TUN으로 되돌아오지 않는 이유는? "Chrome만"과 "전체 앱"에서 이유가 다르다. 힌트: `TlsConnector.kt:47`, `PracticeVpnService.kt:77`
5. 허용 목록이 비었을 때 왜 시작을 거부하나? 거부가 두 군데 있는 이유는? 힌트: `TunnelPolicy.kt:35`, `PracticeVpnService.kt:79`
6. 클라이언트는 무엇을 근거로 서버 인증서를 믿나? 시스템 CA를 함께 믿으면 무엇이 위험한가? 힌트: `TlsConnector.kt:27`
7. 호스트 이름 검증은 왜 따로 하나? 빼면 어떤 인증서가 통과하나? 힌트: `TlsConnector.kt:65`, `make-certs.sh:62`
8. `startHandshake()`를 명시적으로 부르는 이유와, 핸드셰이크 뒤 `soTimeout`을 0으로 되돌리는 이유는? 힌트: `TlsConnector.kt:60`
9. 길이 접두어가 왜 필요하고, 길이가 0이거나 너무 크면 왜 건너뛰지 않고 연결을 끊나? 힌트: `PacketFrame.kt:43`
10. TCP 위에 TCP를 실으면 무엇이 문제인가? 지금 환경에서 안 보이는 이유는? 힌트: `TunnelServer.kt:17`
11. 중지 후 바로 시작하면 어떤 경합이 생기고, 코드는 어떻게 막나? 아직 남은 경합은 무엇인가? 힌트: `PracticeVpnService.kt:157`, `PracticeVpnService.kt:226`
12. `stopVpn()`에서 필드를 먼저 비우는 이유, TLS 소켓을 별도 스레드에서 닫는 이유는? 힌트: `PracticeVpnService.kt:255`, `PracticeVpnService.kt:263`
13. 블로킹 `read()`는 어떻게 멈추나? 앱과 서버의 방법이 왜 다른가? 힌트: `PracticeVpnService.kt:176`, `LinuxTun.kt:32`
14. 서버는 왜 "마지막 연결 우선"이고, 핸드셰이크를 왜 연결마다 따로 하나? 힌트: `TunnelServer.kt:65`, `TunnelServer.kt:81`
15. CA 개인키와 서버 키를 왜 커밋하지 않나? CA를 다시 만들면 무슨 일이 생기나? 포트를 왜 `127.0.0.1`에만 여나? 힌트: `.gitignore`의 `certs/`, `make-certs.sh:54`, `Dockerfile:6`

## 7. 직접 돌려 보기

Git Bash, 저장소 루트 기준이다. Docker 개념은 [docker-guide.md](docker-guide.md)를 본다(5장이 터널 서버의 포트와 인증서 마운트를 설명한다). 수동 확인 시나리오 전체는 [6단계 계획 Task 5](superpowers/plans/2026-11-27-tls-tunnel.md)에 있다.

```bash
# 1. 인증서 (certs/가 없을 때만. CA를 새로 만들면 앱을 다시 빌드해야 한다)
bash scripts/make-certs.sh

# 2. 빌드와 테스트
./gradlew assembleDebug testDebugUnitTest lintDebug :server:test :tunnel-protocol:test

# 3. 서버 이미지와 실행
./gradlew :server:installDist && docker build -t vpn-practice-server server
MSYS_NO_PATHCONV=1 docker run -d --rm --name tunnel-server --cap-add NET_ADMIN --device /dev/net/tun \
  -p 127.0.0.1:5555:5555 -v "$(pwd)/certs/server:/certs:ro" vpn-practice-server
docker logs -f tunnel-server

# 4. 앱 설치와 로그 (serial은 adb devices로 먼저 확인. emulator-5556일 때도 있다)
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -s PracticeVpn
```

확인할 것:

- 시작하면 Logcat에 `tls connected: protocol=TLSv1.3 cipher=...`
- 테스트 패킷 버튼 -> `[to-server] ... proto=UDP`와 `[from-server] ... proto=ICMP`
- "Chrome만"으로 시작하고 Chrome에서 `http://10.0.0.1` -> `[to-server]` TCP와 `[from-server]` 응답
- 실패 시나리오: `-v` 경로를 `certs/wrong-ca/server`(`SSLHandshakeException`) 또는 `certs/wrong-san/server`(`SSLPeerUnverifiedException`)로 바꿔 띄우면 VPN이 "중지됨"으로 돌아간다
- 어느 경우든 에뮬레이터의 일반 웹사이트는 열려야 한다

에뮬레이터는 세션과 분리된 프로세스로 띄운다(사용자 CLAUDE.md 참고).

## 8. 알려진 한계와 다음 단계

다음 단계([vpn-practice-plan.md](vpn-practice-plan.md)):

- **7단계 `protect()` / Always-on**: 라우트를 넓히면 터널 소켓이 TUN으로 되돌아오는 루프를 `protect()`로 막는다. Always-on으로 extra 없이 시작되는 경우는 이미 `fromName()`이 `ALL_APPS`로 받는다(`TunnelPolicy.kt:56~61`).
- **8단계 사용자 인증 / 서버 정책**: 지금 TLS는 서버만 검증한다. 누가 접속하는지는 막지 않아서 포트를 `127.0.0.1`에만 연다.
- **9단계 실행 중 정책 변경**: 재 `establish()`와 함께 메인/IO 스레드의 `tunFd`/`tunnel` 경합을 정리한다. 자동 재연결도 같은 종류의 문제라 그때 본다.

남은 Minor(6단계 리뷰에서 넘긴 것):

- `close()` 예외 처리가 제각각이다. `stopVpn()`은 `IOException`을 잡지만(268~272), `runTunnel()`의 `client.close()`(158, 164)와 서버 `TunnelServer.close()`(42~46)는 잡지 않는다. 서버 쪽은 앞의 `close()`가 던지면 뒤의 것이 닫히지 않는다.
- `TlsConnector.connect()`가 `IOException`만 잡는다(74). 다른 예외(런타임 예외 등)가 나면 소켓을 닫지 않고 빠져나간다.
- 서버 `serve()` 전체가 try로 감싸져 있지 않다. 핸드셰이크 뒤 `current.getAndSet(socket)?.close()`(83)에서 이전 TLS 소켓을 닫다가 예외가 나면 `run()`의 `coroutineScope`까지 올라갈 수 있다.
- `TlsConnector` 실패 경로(잘못된 CA, 호스트 이름 불일치, 핸드셰이크 타임아웃) 단위 테스트가 없다. 에뮬레이터 수동 확인뿐이다.
- `TunnelServerTest.tun_packet_is_dropped_without_client`가 `Thread.sleep(200)`(147)에 기대서 느린 환경에서 불안정할 수 있다.

그 밖에 읽다가 보일 것:

- keepalive가 없다. 앱이 비정상 종료되면 서버는 다음 연결이 올 때까지 이전 연결을 쥐고 있다(그래서 마지막 연결 우선).
- 실기기는 지원하지 않는다. 서버 주소가 에뮬레이터 전용 `10.0.2.2`다.
