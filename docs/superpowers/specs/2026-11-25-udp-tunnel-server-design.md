# 4단계 설계: Docker 서버와 평문 UDP 터널

## 목적
ZTNA Agent 입사 전에 "앱 TUN <-> UDP 터널 <-> 서버 Linux TUN" 왕복 구조를 직접 만들고 설명할 수 있게 된다.
`docs/vpn-practice-plan.md`의 4단계이며 연습 범위 4번의 확장 부분("파싱한 패킷을 UDP 터널로 서버에 보내고, 서버가 Linux TUN으로 되돌려 주는 데까지")에 해당한다.

## 범위
- `server/` 모듈 (Kotlin/JVM): JNA로 `/dev/net/tun`을 열고 `tun0`을 만든다. UDP 소켓을 받아 터널 패킷을 TUN에 쓰고, TUN에서 읽은 패킷을 클라이언트에 UDP로 돌려보낸다.
- Docker 이미지와 실행 방법 (`NET_ADMIN`, `/dev/net/tun`, UDP 포트 매핑)
- 앱: TUN에서 읽은 패킷을 서버로 UDP 전송, 서버가 보낸 패킷을 받아 TUN에 쓰기
- 왕복 확인: 3단계의 테스트 패킷 버튼(`10.0.0.1:9999`로 UDP)을 누르면 서버 커널이 ICMP "port unreachable"로 응답하고, 앱 Logcat에 서버가 보낸 패킷이 찍힌다.

## 범위 밖
- 클라이언트 여러 개, 세션 ID, keepalive, 재연결 (6단계 이후)
- TLS/인증 (6단계), 앱별 정책 (5단계)
- 서버가 패킷을 인터넷으로 내보내는 NAT/포워딩
- IPv6
- 서버 주소 입력 UI. 에뮬레이터 전용이라 `10.0.2.2:5555`로 고정한다. 실기기 지원은 나중에 별도로 한다.
- Docker 이미지의 CI 빌드/배포
- 실패하면 안 되는 것: 에뮬레이터의 일반 인터넷이 끊기면 안 된다. 라우트는 계속 `10.0.0.0/24`만 쓴다.

## 접근법 (터널 패킷 형식)
1. **A. 데이터그램 하나에 IP 패킷 하나, 헤더 없음 (선택)**: TUN에서 읽은 바이트를 그대로 UDP로 보낸다. 서버는 마지막으로 받은 발신지 주소를 클라이언트로 기억해서 되돌려 보낸다. 가장 단순하고 설명하기 쉽다.
2. B. 터널 헤더(타입, 세션 ID) 추가: 이 단계에서 쓰지 않는 필드가 생긴다. 6단계의 인증/keepalive가 필요해질 때 추가한다.
3. C. 접속 요청/응답 핸드셰이크: 인증이 없는 지금은 형식뿐인 절차가 된다. 6단계에서 TLS/인증과 함께 설계한다.

## 구성
패키지는 `com.mg.vpnpractice` 아래. 서버는 `com.mg.vpnpractice.server`.

| 파일 | 역할 |
|------|------|
| `server/.../LinuxTun.kt` | JNA로 `open`, `ioctl(TUNSETIFF)`, `read`, `write`, `close` 호출. `tun0`을 만들고 fd를 감싼다 |
| `server/.../TunnelServer.kt` | 코루틴 두 개: (UDP 수신 -> TUN 쓰기), (TUN 읽기 -> UDP 송신). 클라이언트 주소는 마지막 수신 발신지 |
| `server/.../Main.kt` | 포트 설정, TUN 생성, `ip` 명령으로 `10.0.0.1/24` 설정과 인터페이스 up, 서버 시작, 종료 시 fd 닫기 |
| `server/Dockerfile` | 호스트에서 `:server:installDist`로 만든 결과를 `COPY`하는 단일 스테이지. JRE 이미지에 `iproute2` 포함. 컨테이너 안에서 Gradle을 돌리면 `:app` 때문에 Android SDK까지 필요해서 멀티 스테이지는 쓰지 않는다 |
| `app/src/main/AndroidManifest.xml` (수정) | `INTERNET` 권한 추가. 지금 매니페스트에 없어서 앱이 소켓을 열 수 없다 |
| `app/.../vpn/TunnelClient.kt` | `DatagramSocket` 보유, 패킷 송신과 수신 |
| `app/.../vpn/PracticeVpnService.kt` (수정) | 읽기 루프에서 로그 후 `TunnelClient`로 송신, 수신 루프를 추가해 TUN에 쓰기. 중지 시 fd와 소켓을 함께 닫는다 |
| `gradle/libs.versions.toml`, `settings.gradle.kts` | `:server` 포함, JNA 등록 (서버에서만 사용) |

## 결정 사항
- 터널 포트 `5555/udp`, 서버 TUN `10.0.0.1/24`. 앱 TUN은 기존 `10.0.0.2/24`. (내 선택)
- 서버 종료 때 fd를 닫아 블로킹 `read()`를 깨운다. 3단계와 같은 방식이다. (내 선택)
- 서버의 TUN IP 설정은 TUN을 만든 뒤 `ip` 명령을 실행해서 한다. JNA ioctl로 직접 하는 것보다 짧고 설명하기 쉽다. (내 선택)
- 앱의 터널 소켓은 이 단계에서 `protect()`하지 않는다. 서버 주소 `10.0.2.2`가 라우트 `10.0.0.0/24` 밖이라 TUN으로 되돌아오지 않는다. 이유를 코드 주석으로 남기고 `protect()`는 6단계에서 다룬다. (내 선택, 필요하면 이 단계에 넣을 수 있다)
- 서버 컨테이너 실행: `docker run --cap-add NET_ADMIN --device /dev/net/tun -p 5555:5555/udp`. Docker Desktop(WSL2)에서 `/dev/net/tun`이 열리는지는 구현 때 확인한다.
- 새 라이브러리는 서버의 JNA뿐이다. 코루틴과 JUnit은 기존 Version Catalog 항목을 쓴다.

## 테스트
- 단위 테스트: 서버 쪽 순수 로직(클라이언트 주소 기억, 클라이언트가 없을 때 TUN 패킷 버리기)은 TUN/소켓 없이 테스트할 수 있게 분리한다. `LinuxTun`과 소켓은 단위 테스트하지 않는다.
- 수동 확인: 서버 컨테이너 실행 -> 에뮬레이터에서 VPN 시작 -> 테스트 패킷 버튼 -> 서버 로그에 수신 패킷, 앱 Logcat에 `src=10.0.0.1 dst=10.0.0.2 proto=ICMP` 출력 -> 중지 시 서버/앱 모두 정상 종료
- 작업 마무리에 `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test` 통과. (서버는 순수 JVM 모듈이라 `testDebugUnitTest`에 포함되지 않아서 `:server:test`를 따로 돌린다.)

## 구현자에게 맡기는 것
- 서버 로그 포맷과 로깅 방식 (표준 출력으로 충분)
- 서버 설정을 환경변수로 받을지 상수로 둘지 (포트 정도만 필요)
- Dockerfile의 베이스 이미지와 빌드 세부
- `TunnelClient`의 내부 구조와 스레드 모델 (서비스의 코루틴 스코프를 쓴다)
