# 6단계 설계: TLS 터널 (인증 없음)

## 목적
ZTNA Agent의 터널은 암호화된 연결 위에서 돈다. 이 단계에서는 4단계의 평문 UDP 터널을 TCP+TLS 연결 하나로 바꾸면서 "TLS 자체"를 직접 만들고 설명할 수 있게 된다.
- 클라이언트가 무엇을 근거로 서버를 믿는가 (CA 인증서 -> KeyStore -> TrustManager -> SSLContext)
- 호스트 이름 검증이 왜 따로 필요한가
- 스트림 위에서 IP 패킷 경계를 어떻게 표시하는가 (길이 접두어)
- TCP 위에 TCP를 실으면 무엇이 문제인가

`docs/vpn-practice-plan.md`의 6단계다. 사용자 인증과 서버 정책은 8단계로 미룬다. 이 단계의 TLS는 "서버가 진짜인지"만 확인한다. 클라이언트가 누구인지는 확인하지 않는다.

## 범위 (목표)
- 앱: `DatagramSocket` 대신 `SSLSocket`으로 서버에 연결하고, IP 패킷을 길이 접두어 프레임으로 주고받는다.
- 앱: `res/raw`에 넣은 연습용 CA 공개 인증서로 `SSLContext`를 코드에서 직접 조립한다.
- 앱: 핸드셰이크 뒤에 서버 인증서의 호스트 이름(IP `10.0.2.2`)을 검증한다.
- 서버: `DatagramSocket` 대신 `SSLServerSocket`으로 받는다. 한 번에 클라이언트 하나와 프레임을 주고받는다.
- 프레임 읽기/쓰기 순수 로직과 단위 테스트
- 연습용 CA와 서버 인증서를 만드는 스크립트. 서버 키는 컨테이너에 마운트한다.
- Dockerfile의 실행 방법을 TCP로 바꾼다. 여전히 `127.0.0.1`에만 연다.

## 범위 밖 (비목표)
- 사용자/기기 인증, 서버가 정책을 내려주는 방식 (8단계)
- `protect()`, Always-on, `0.0.0.0/0` 라우트 (7단계)
- 실행 중 재 `establish()`, `tunFd` 경합 정리 (9단계)
- 자동 재연결. 연결이 끊기면 VPN을 멈춘다(아래 "연결과 끊김"). 재연결은 실행 중 상태 전환이라 9단계의 재 `establish()`와 같은 종류의 동시성 문제가 생긴다.
- SPKI pinning. 이번 구조(직접 조립한 TrustManager) 위에 나중에 얹는다.
- mTLS(클라이언트 인증서). 8단계에서 인증 방식을 정할 때 함께 본다.
- 실기기 지원, 서버 주소 입력 UI. 계속 에뮬레이터 전용 `10.0.2.2:5555`로 둔다.
- 클라이언트 여러 개 동시 지원, keepalive
- 새 라이브러리. 양쪽 모두 JDK 기본 API(`javax.net.ssl`)만 쓴다.

## 반목표 (이렇게 되면 실패)
- 검증을 끄는 코드가 있으면 실패다. 모든 인증서를 믿는 `X509TrustManager`, 항상 `true`를 돌려주는 `HostnameVerifier`, "테스트용으로 잠깐 끄기" 플래그가 여기에 해당한다. 디버그 빌드에서도 안 된다.
- 호스트 이름 검증을 빼먹으면 실패다. `SSLSocket`은 기본적으로 호스트 이름을 확인하지 않는다(아래 "제약" 참고). 같은 CA가 서명한 다른 이름의 인증서를 믿게 된다.
- 서버 개인키나 CA 개인키가 git에 들어가면 실패다. 앱에 들어가도 실패다.
- 5단계와 같이 어떤 프리셋에서도 에뮬레이터의 일반 인터넷이 끊기면 안 된다.

## 제약 (확인한 사실과 확인 못 한 것)
- **minSdk 24, targetSdk/compileSdk 37** (`build-logic/.../KotlinAndroid.kt:17`, `AndroidApplicationConventionPlugin.kt:15`에서 확인)
- **Android의 TLS 1.3**: Android 10(API 29)부터 기본으로 켜져 있고, API 24~28은 TLS 1.2까지 된다. (기억에 의존. 구현 전에 Android 문서로 확인한다)
- **서버 JRE**: `eclipse-temurin:17-jre`(`server/Dockerfile`). JDK 11부터 TLS 1.3을 지원한다.
- **Android `SSLSocket`과 호스트 이름 검증**: Android 문서("Security with network protocols")에는 `SSLSocket`이 호스트 이름을 검증하지 않는다는 경고가 있다. 같은 문서는 `HttpsURLConnection.getDefaultHostnameVerifier()`로 직접 검증하라고 권한다. (기억에 의존. 구현 전에 확인한다) `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`가 API 24의 Conscrypt에서 동작하는지는 확인하지 못했다.
- **JVM과 Android의 기본 HostnameVerifier가 다르다**: JVM의 `HttpsURLConnection` 기본 verifier는 Android 것과 동작이 다르다. 그래서 앱의 호스트 이름 검증은 JVM 단위 테스트로 검증할 수 없다. 에뮬레이터에서 수동으로 확인한다.
- **인증서 도구**: Git Bash에 OpenSSL 3.5가 들어 있다(`/mingw64/bin/openssl`). `keytool`은 PATH에 없다.
- **`ACCESS_LOCAL_NETWORK`**: Android 17(API 37)에서 이 권한이 없으면 `10.0.2.2`로의 통신이 `EPERM`으로 실패한다(4단계에서 확인). UDP일 때는 전송 실패 로그만 남았다. TCP에서는 `connect()`가 실패해서 VPN이 시작되지 않는다.
- **현재 코드 구조**
  - `TunnelClient`(앱)는 생성자에서 `DatagramSocket`을 `connect`한다. `tolerate()`로 열린 소켓의 I/O 실패를 견디며 루프를 계속 돌린다.
  - `PracticeVpnService.runTunnel()`은 IO 스레드에서 소켓을 만들고 `readLoop`/`writeLoop` 두 코루틴을 띄운다.
  - `TunnelServer`(서버)는 uplink/downlink 두 루프를 돌리고, 마지막 발신 주소를 클라이언트로 본다(`TunnelServer.kt:59`).
  - `Tun` 인터페이스가 있어서 서버 테스트는 `FakeTun`을 쓴다.

## 접근법
TLS를 어디에 씌우나:
1. **A. UDP 터널을 TCP+TLS 연결 하나로 바꾼다 (선택)**: 양쪽 모두 JDK의 `SSLSocket`/`SSLServerSocket`만 쓴다. 새 라이브러리가 없고, TLS 핸드셰이크와 인증서 검증을 표준 API 그대로 보여 줄 수 있다. 대가는 TCP-over-TCP다(아래).
2. B. DTLS로 UDP를 그대로 둔다: 실제 VPN 데이터 채널에 가깝다. 하지만 Android 플랫폼에는 쓸 수 있는 DTLS API가 없어서 BouncyCastle 같은 새 라이브러리가 필요하다. 핸드셰이크 재전송, 쿠키 같은 DTLS 고유 개념까지 함께 익혀야 해서 "TLS 자체"를 먼저 익히려는 목적에서 벗어난다. 버렸다.
3. C. 제어 채널만 TCP+TLS로 두고 데이터는 평문 UDP로 둔다: 실제 제품 구조와 닮았다. 하지만 이번 단계에는 제어 채널로 주고받을 것(인증, 정책)이 없어서 형식뿐인 채널이 된다. 게다가 데이터가 평문 그대로라 "TLS로 터널을 보호한다"를 확인할 수 없다. 인증과 정책이 생기는 8단계에서 다시 고려할 수 있다. 버렸다.

신뢰 방식:
1. **가. CA 공개 인증서를 `res/raw`에 넣고, 코드에서 KeyStore -> TrustManagerFactory -> SSLContext로 조립한다 (선택)**: 검증의 각 단계가 코드에 그대로 보인다. 나중에 pinning을 얹을 자리도 분명하다.
2. 나. `network_security_config`로 CA를 등록한다: 설정 한 줄로 끝나지만 검증 과정이 프레임워크 안에 감춰진다. 이 설정은 주로 `HttpsURLConnection`/OkHttp를 위한 것이라, 직접 만든 `SSLSocket`에 어디까지 적용되는지도 따로 확인해야 한다. 버렸다.
3. 다. SPKI pinning: 가.보다 한 단계 더 나아간 것이다. 가. 위에 나중에 얹는다.

### TCP-over-TCP 한계 (문서와 코드 주석에 남긴다)
- 앱 안의 TCP(예: Chrome)가 바깥 TCP 터널 위를 지난다. 바깥 연결에서 손실이 나면 바깥 TCP가 재전송하는 동안 안쪽 TCP도 타임아웃으로 재전송한다. 재전송이 겹쳐 지연이 커진다(TCP meltdown).
- 패킷 하나가 늦으면 그 뒤의 모든 패킷이 기다린다(head-of-line blocking). 안쪽 UDP(DNS 등)도 순서 보장과 재전송을 강제로 얻게 되고 그만큼 지연이 생긴다.
- 에뮬레이터와 PC loopback 사이에는 손실이 거의 없어서 이 문제가 눈에 보이지 않는다. 실제 VPN이 UDP(WireGuard, IPsec, DTLS)를 쓰는 이유다.

## 정해진 설계

### 구성
| 파일 | 역할 |
|------|------|
| `tunnel-protocol/` (신규 JVM 모듈, 이름은 구현자가 정해도 된다) | 프레임 읽기/쓰기(`PacketFrame` 가칭)와 그 단위 테스트. 앱과 서버가 함께 쓴다 |
| `app/.../vpn/TunnelClient.kt` (교체) | 연결된 `Socket`을 받아 프레임 송수신만 한다. TLS를 몰라도 되게 해서 일반 TCP 소켓으로 단위 테스트한다 |
| `app/.../vpn/TlsConnector.kt` (신규, 가칭) | CA 인증서로 `SSLContext` 조립, 연결, 핸드셰이크, 호스트 이름 검증, 협상된 TLS 버전 로그 |
| `app/src/main/res/raw/practice_ca.pem` (신규) | 연습용 CA 공개 인증서. 스크립트가 복사해 넣는다. 커밋한다 |
| `app/.../vpn/PracticeVpnService.kt` (수정) | `runTunnel()`에서 TLS 연결 후 루프 시작. 끊기면 VPN 중지 |
| `app/src/main/AndroidManifest.xml`, `TestPacketSender.kt` (주석 수정) | "UDP"로 적힌 설명을 TCP/TLS에 맞게 고친다 |
| `server/.../TunnelServer.kt` (수정) | `ServerSocket`을 받아 accept 루프 + 현재 연결 하나와 프레임 중계 |
| `server/.../Main.kt` (수정) | 마운트된 PKCS12 키스토어로 `SSLServerSocket` 생성, TLS 버전 제한 |
| `server/Dockerfile` (주석 수정) | 실행 명령을 TCP 포트 + 인증서 마운트로 바꾼다 |
| `scripts/make-certs.sh` (신규) | 연습용 CA와 서버 인증서 생성 |
| `.gitignore` (수정) | `certs/` 추가 |
| `settings.gradle.kts`, 각 `build.gradle.kts`, `.github/workflows/ci.yml` | 공용 모듈 포함, CI에 공용 모듈 테스트 추가 |
| `CLAUDE.md` (수정) | 프로젝트 설명을 TLS 터널 기준으로 고치고, 완료 확인 명령에 `:server:test`와 공용 모듈 테스트 추가 |

### 프레이밍
```
+----------------+---------------------------+
| 길이 (2바이트) | IP 패킷 (길이만큼)        |
| big-endian,    |                           |
| 부호 없음      |                           |
+----------------+---------------------------+
```
- 길이 필드는 2바이트 부호 없는 big-endian이다. `DataOutputStream.writeShort`/`DataInputStream.readUnsignedShort`로 그대로 다룰 수 있다.
- 최대 길이는 32767이다. 앱과 서버의 기존 버퍼 크기(`ByteArray(32767)`)와 같다. TUN MTU가 1500이라 실제 패킷은 이보다 훨씬 작다.
- 길이가 0이거나 최대보다 크면 연결을 끊는다. 스트림에는 경계 표시가 이 길이 필드 하나뿐이라, 한 번 어긋나면 다음 프레임이 어디서 시작하는지 다시 찾을 방법이 없다. 건너뛰고 계속 읽으면 쓰레기 바이트를 패킷으로 TUN에 쓰게 된다.
- 프레임 경계에서 스트림이 끝나면 정상 종료다(읽기 함수가 -1 같은 값을 돌려준다). 길이 필드나 본문 중간에서 끝나면 잘린 프레임이므로 예외를 던진다.
- 쓰는 쪽은 길이와 본문을 한 번의 `write`로 보낸다. `SSLSocket`은 `write` 호출마다 TLS 레코드를 만들 수 있어서, 두 번 나눠 쓰면 2바이트짜리 레코드가 따로 생긴다.
- 양쪽 소켓에 `TCP_NODELAY`를 켠다. Nagle 알고리즘이 작은 패킷(안쪽 TCP의 ACK 등)을 모아 보내느라 지연이 생기는 것을 막는다.

### 앱
- `runTunnel()`(IO 스레드)의 순서: TLS 연결 -> 핸드셰이크 -> 호스트 이름 검증 -> `TunnelClient` 생성 -> 읽기/쓰기 루프 시작.
- `SSLContext` 조립 순서: `CertificateFactory("X.509")`로 `res/raw` 인증서 읽기 -> 빈 `KeyStore`에 `setCertificateEntry` -> `TrustManagerFactory.init(keyStore)` -> `SSLContext.getInstance("TLS").init(null, trustManagers, null)`. 이렇게 하면 시스템 CA는 믿지 않고 연습용 CA만 믿는다.
- 핸드셰이크는 `startHandshake()`로 명시적으로 한다. 첫 `read`/`write`에서 암묵적으로 일어나게 두면, 인증서 오류가 패킷 전송 실패처럼 보인다.
- 호스트 이름 검증: 핸드셰이크 뒤에 `HttpsURLConnection.getDefaultHostnameVerifier().verify("10.0.2.2", session)`를 부르고, `false`면 소켓을 닫고 VPN을 중지한다. (구현 전에 "제약"의 Android 문서 확인)
- 연결 타임아웃과 핸드셰이크 타임아웃을 둔다(값은 구현자가 정한다. 몇 초 수준). 핸드셰이크가 끝나면 읽기 타임아웃은 다시 0(무한)으로 돌린다. 터널은 오래 조용할 수 있기 때문이다.
- TLS 버전은 앱에서 따로 제한하지 않는다. 서버가 정한다(아래). 협상된 `session.protocol`과 `cipherSuite`를 Logcat에 남긴다.

### 연결과 끊김 (앱)
- 지금(5단계까지)은 UDP라서 "연결"이 없다. 서버가 꺼져 있어도 전송 실패 로그만 남기고 VPN은 켜진 채로 있다가, 서버가 다시 뜨면 저절로 이어졌다.
- TCP에서는 끊긴 스트림을 다시 쓸 수 없다. 그래서 이렇게 한다.
  - 시작할 때 연결/핸드셰이크/호스트 이름 검증 중 하나라도 실패하면 Logcat에 경고를 남기고 `stopVpn()`한다. 지금 소켓 생성 실패를 처리하는 방식(`runTunnel()`의 `IOException` 분기)과 같다. 화면은 "중지됨"으로 돌아간다.
  - 실행 중에 서버가 끊거나, 소켓 I/O가 실패하거나, 잘못된 프레임을 받으면 `stopVpn()`한다. "실행 중"인데 아무것도 안 되는 상태를 만들지 않는다는 기존 원칙(`readLoop`의 주석)을 따른 것이다.
  - TUN 쓰기 실패 하나(잘못된 패킷 등)는 지금처럼 로그만 남기고 계속한다. 이것은 연결 문제가 아니다.
  - `tolerate()`는 없앤다. 열린 TCP 소켓의 I/O 실패는 견딜 대상이 아니다.
- 자동 재연결은 비목표다(위 "범위 밖").
- 루프가 끝나며 부르는 `stopVpn()`은 그 루프의 클라이언트가 아직 현재 `tunnel`일 때만 부른다. 사용자가 중지 후 바로 다시 시작했을 때, 앞 세션의 늦은 루프가 새 세션을 멈추지 않게 하려는 것이다. IO 스레드에서 `stopVpn()`을 부르는 기존 경합은 그대로 둔다(9단계).

### 서버
- `TunnelServer`는 `ServerSocket`을 받는다(`SSLServerSocket`은 그 하위 클래스). 테스트에서는 일반 `ServerSocket`을 넣는다. TLS 설정은 `Main.kt`가 맡는다.
- 한 번에 클라이언트 하나만 다룬다. 새 연결이 핸드셰이크에 성공하면 이전 연결을 닫고 새 연결로 바꾼다(마지막 연결 우선). 지금의 "마지막 발신 주소가 클라이언트" 동작을 이어받은 것이다.
  - 순서대로 하나씩 받는 방식은 더 단순하다. 하지만 에뮬레이터가 비정상 종료되어 이전 연결의 FIN이 오지 않으면, 서버가 죽은 연결을 붙잡고 새 연결을 영영 받지 않는다. keepalive가 없는 지금은 교체가 더 안전하다.
  - 핸드셰이크는 연결마다 따로(accept 루프 밖에서) 한다. 잘못된 CA로 접속하는 확인 시나리오가 정상 연결을 끊지 않게 하려는 것이다.
- TUN 읽기(downlink) 루프는 지금처럼 서버 수명 동안 하나로 계속 돈다. 현재 연결이 없으면 패킷을 버린다. 지금의 `client: SocketAddress?`가 "현재 연결의 출력"으로 바뀌는 것이다.
- 연결마다 uplink 루프(프레임 읽기 -> `tun.write`)가 돈다. 잘못된 프레임이나 EOF면 그 연결만 닫고 서버는 계속 받는다.
- TLS 버전: 서버는 `TLSv1.3`과 `TLSv1.2`만 켠다(`setEnabledProtocols`). minSdk 24라서 1.3으로 고정하면 API 24~28 기기가 접속하지 못한다. 연습 에뮬레이터(API 37)에서는 1.3으로 협상되는 것을 로그로 확인한다.
- 키스토어는 마운트된 PKCS12 파일과 비밀번호 파일에서 읽는다. 비밀번호를 코드나 Dockerfile에 쓰지 않는다.
- `close()`는 `ServerSocket`, 현재 연결, TUN을 모두 닫는다. 종료 훅은 지금과 같다.

### 인증서 스크립트
- 위치: `scripts/make-certs.sh` (Git Bash의 `openssl` 사용)
- 출력은 `certs/`이고 gitignore한다.
  - `certs/ca/`: `ca.key`, `ca.crt`. CA 개인키는 여기에만 두고 컨테이너에 마운트하지 않는다.
  - `certs/server/`: `server.p12`(서버 키 + 인증서 체인), `server.p12.pass`(스크립트가 만든 무작위 비밀번호). 이 폴더만 컨테이너에 읽기 전용으로 마운트한다.
- 서버 인증서의 SAN은 `IP:10.0.2.2`이고, 확장 키 용도는 `serverAuth`다. CA 인증서는 `basicConstraints=CA:TRUE`.
- 스크립트는 `ca.crt`를 `app/src/main/res/raw/practice_ca.pem`으로 복사한다. 이 파일만 커밋한다.
- CA 개인키는 커밋하지 않아서, clone한 사람이나 키를 잃은 뒤에는 스크립트를 다시 돌려 CA를 새로 만든다. 그러면 `practice_ca.pem`이 바뀌므로, 이전에 빌드한 APK는 새 서버를 믿지 않는다. 이 점을 스크립트 출력과 문서에 적는다.
- 확인용으로 "다른 CA가 서명한 서버 인증서"와 "SAN이 다른 서버 인증서"를 만들 수 있게 한다(옵션이든 별도 출력 폴더든 방식은 구현자가 정한다).

### Docker
```
docker run -d --rm --name tunnel-server --cap-add NET_ADMIN --device /dev/net/tun \
  -p 127.0.0.1:5555:5555 -v "$(pwd)/certs/server:/certs:ro" vpn-practice-server
```
- 포트는 TCP 5555다(`/udp`를 뺀다). 인증이 없어서 계속 `127.0.0.1`에만 연다. TLS는 도청을 막을 뿐이고, 누가 접속하는지는 막지 않는다.
- Git Bash에서는 `MSYS_NO_PATHCONV=1`이 필요하다(기존 주석과 같다).

### 코드에 남길 주석 지점
- `SSLContext` 조립: 왜 시스템 CA가 아니라 연습용 CA만 믿는가
- 호스트 이름 검증: `SSLSocket`이 하지 않아서 직접 한다. 끄면 같은 CA의 다른 인증서도 믿게 된다
- `startHandshake()`를 명시적으로 부르는 이유
- 프레임: 길이 필드 크기, 최대 길이, 잘못된 길이에서 연결을 끊는 이유, 한 번의 `write`로 보내는 이유
- `TCP_NODELAY`를 켜는 이유
- TCP-over-TCP 한계 (`TunnelClient` 또는 `TunnelServer` 클래스 주석)
- 끊기면 VPN을 멈추는 이유, 재연결이 없는 이유
- `protect()`가 여전히 필요 없는 이유: 서버 `10.0.2.2`가 라우트 밖이다. 기존 주석을 TCP 소켓에 맞게 옮긴다
- 서버: 마지막 연결 우선인 이유, 핸드셰이크를 연결마다 따로 하는 이유, TLS 1.2를 함께 켜 두는 이유
- `.gitignore`/스크립트: 개인키를 커밋하지 않는 이유

## 결정 사항
- TLS 방식 A, 신뢰 방식 가. (대화에서 합의)
- CA와 서버 인증서는 스크립트로 만든다. 앱에는 CA 공개 인증서만 넣는다. 개인키는 gitignore된 폴더에 두고 서버 키만 컨테이너에 마운트한다. 호스트 이름 검증은 끄지 않고 SAN에 `IP:10.0.2.2`를 넣는다. (대화에서 합의)
- 프레임은 2바이트 길이 + 본문, 최대 32767, 0이나 최대 초과면 연결을 끊는다. (내 선택)
- 프레임 코드는 공용 JVM 모듈 하나에 두고 앱과 서버가 함께 쓴다. 형식이 양쪽에서 같아야 하는 유일한 코드라 한 곳에서 테스트한다. 대가로 모듈이 하나 늘고 CI에 그 모듈의 테스트를 추가해야 한다. (사용자 승인. 버린 안은 양쪽에 같은 파일을 하나씩 두는 것)
- 끊기면 VPN 중지, 자동 재연결 없음. 서버보다 앱을 먼저 켜면 VPN이 시작되지 않는 동작 변화도 받아들인다. (사용자 승인)
- 루트 `CLAUDE.md`를 이번 작업에서 고친다. 프로젝트 설명의 "UDP 평문 터널", "TLS/인증은 나중에"를 TLS 터널(인증은 8단계) 기준으로 바꾸고, 완료 확인 명령에 `:server:test`와 공용 모듈 테스트를 넣는다. (사용자 승인)
- 서버는 한 번에 하나, 마지막 연결 우선. (내 선택)
- 서버 TLS는 1.3 + 1.2. (내 선택, minSdk 24 때문)
- 인증서 도구는 `openssl`. Git Bash에 들어 있고, CA -> CSR -> 서명 단계가 명령에 그대로 드러난다. (내 선택)
- `TunnelServer`는 `ServerSocket`을 받아서, 테스트는 TLS 없이 중계 로직만 본다. TLS 경로는 수동으로 확인한다. (내 선택)
- 새 라이브러리 없음.

## 테스트
- 단위 테스트 (공용 모듈): 왕복, 한 스트림에 프레임 여러 개, 1바이트씩 끊어 오는 입력, 경계에서 EOF는 정상 종료, 길이 필드나 본문 중간의 EOF는 예외, 길이 0과 최대 초과는 예외, 최대 초과 쓰기 거부
- 단위 테스트 (앱 `TunnelClient`): 일반 TCP 소켓 쌍으로 프레임 송수신과 닫힘 동작
- 서버 테스트 (`TunnelServerTest` 다시 작성, `FakeTun` + 일반 `ServerSocket`): 클라이언트 프레임 -> `tun.written`, `tun.feed` -> 클라이언트가 프레임 수신, 연결이 없을 때 버리기, 두 번째 연결이 첫 번째를 대체, 잘못된 길이를 받으면 그 연결만 끊기고 다음 연결은 받음
- 수동 확인 (에뮬레이터 `Pixel_8`, 스크립트로 만든 인증서로 서버 컨테이너 실행):
  - VPN 시작 -> Logcat에 협상된 `TLSv1.3`과 cipher suite
  - "전체 앱"에서 테스트 패킷 버튼 -> `[to-server]`와 서버 ICMP 응답 `[from-server]`
  - "Chrome만"에서 Chrome으로 `http://10.0.0.1` -> `[to-server]` SYN과 `[from-server]` RST
  - 다른 CA가 서명한 서버 인증서로 서버 실행 -> 앱 Logcat에 `SSLHandshakeException`, VPN 중지, 화면 "중지됨"
  - SAN이 `10.0.2.2`가 아닌 서버 인증서로 서버 실행 -> 호스트 이름 검증 실패로 중지
  - 실행 중 `docker stop` -> 앱이 끊김을 감지하고 VPN 중지
  - 서버 쪽: `docker logs`에서 잘못된 CA 접속이 실패한 뒤에도 정상 클라이언트를 계속 받는지
  - 어떤 경우에도 에뮬레이터의 일반 웹사이트 접속이 되는지
- 작업 마무리에 `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test`와 공용 모듈 테스트 통과

## 구현자에게 맡기는 것
- 공용 모듈 이름, 클래스와 함수 이름(`PacketFrame`, `TlsConnector` 등은 가칭)
- 연결/핸드셰이크 타임아웃 값
- 프레임 읽기 함수가 정상 종료를 알리는 방식(-1, null 등)
- 서버의 연결 교체를 코루틴으로 할지 스레드로 할지. 단, 교체 중에도 TUN 쓰기는 패킷 단위로 한 번의 `write`라 잠금은 추가하지 않는다
- 키스토어 경로와 비밀번호 파일 경로를 상수로 둘지 환경변수로 받을지
- 확인용 "잘못된 인증서"를 스크립트 옵션으로 만들지 별도 스크립트로 만들지
- 서버 로그 문구
