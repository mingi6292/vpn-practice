# 3단계 설계: TUN 열기와 IPv4 패킷 파싱

## 목적
ZTNA Agent 입사 전에 VpnService의 기본 흐름(동의 -> TUN 생성 -> 패킷 읽기 -> 종료)을 직접 만들고 설명할 수 있게 된다.
`docs/vpn-practice-plan.md`의 3단계이며 연습 범위 1, 2, 4번과, 서비스 동작에 필요한 5번의 최소 부분을 포함한다.

## 범위
- `VpnService.prepare()`로 사용자 동의 받기
- `VpnService.Builder`로 TUN 열기. 가짜 사내 대역 `10.0.0.0/24`만 `addRoute` (split tunnel 연습)
- TUN fd에서 패킷을 읽어 IPv4 헤더를 파싱하고 Logcat에 기록 (출발지/목적지 IP, 프로토콜, 포트)
- 앱 안에서 `10.0.0.1`로 UDP 패킷을 보내 루프를 확인하는 테스트 버튼
- 포그라운드 서비스 알림, `onRevoke()`와 중지 시 fd 정리 (서비스가 돌기 위한 최소한)

## 범위 밖
- 패킷을 서버로 전달하기 (4단계), 앱별 정책 (5단계), TLS/인증 (6단계)
- `protect()`와 Always-on 대응. 테스트 소켓은 일부러 `protect()`하지 않는다. TUN으로 들어가야 하기 때문이다. 이 대비는 코드 주석에 남긴다.
- IPv6, TCP/UDP 페이로드 해석, 화면에 로그 목록 표시 (로그는 Logcat만)
- 실패하면 안 되는 것: 폰의 일반 인터넷이 끊기면 안 된다. 그래서 전체 라우팅(`0.0.0.0/0`)은 쓰지 않는다.

## 접근법
1. **서비스가 코루틴으로 읽기 루프를 소유 (선택)**: `Dispatchers.IO`에서 `FileInputStream.read()`를 반복한다. 프로젝트가 이미 Coroutines를 쓰고, 읽기/종료가 한 곳에 모여 설명하기 쉽다.
2. 전용 `Thread`: 코루틴 없이도 되지만 취소/정리를 직접 짜야 하고 프로젝트 스타일과 다르다.
3. NIO `FileChannel`: 이 단계에는 과하다.

주의할 점: `read()`는 블로킹이라 코루틴 취소만으로 멈추지 않는다. 중지할 때 fd를 닫아서 `read()`가 예외로 빠져나오게 한다. 이 부분이 학습 점검 질문("읽기 루프는 어떻게 멈추나")의 답이다.

## 구성
패키지는 `com.mg.vpnpractice` 아래.

| 파일 | 역할 |
|------|------|
| `vpn/Ipv4Packet.kt` | 파싱 결과(출발지/목적지 IP, 프로토콜, 포트?)와 `parse(bytes, length)`. 순수 로직, 안드로이드 의존 없음 |
| `vpn/PracticeVpnService.kt` | `VpnService`. Builder 설정, 읽기 루프, 포그라운드 알림, `onRevoke()`, fd 닫기 |
| `vpn/TestPacketSender.kt` | `DatagramSocket`으로 `10.0.0.1:9999`에 UDP 전송 |
| `MainActivity.kt` | Compose 화면: 시작/중지/테스트 패킷 버튼, 동의 요청(`prepare()` 결과가 null이면 바로 시작, 아니면 Intent 실행) |
| `AndroidManifest.xml` | 서비스 선언(`BIND_VPN_SERVICE`), 포그라운드 서비스 권한/타입, `POST_NOTIFICATIONS` |

## 결정 사항
- TUN 주소 `10.0.0.2/24`, 라우트 `10.0.0.0/24`, MTU 1500. (내 선택)
- 포그라운드 서비스 타입은 `systemExempted` 후보. (내 선택, 구현 때 Android 공식 문서로 확인해서 확정)
- `parse()`는 IPv4가 아니거나 길이가 모자라면 `null`을 반환한다. 예외를 던지지 않는다. (내 선택)
- TCP/UDP만 포트를 채우고 ICMP 등은 포트 없음. IHL이 5보다 큰 옵션 헤더도 처리한다.
- 서비스 제어는 `startService`/`stopService`와 Intent action으로 한다. 바인딩은 쓰지 않는다. (내 선택, 더 단순)
- 새 라이브러리는 추가하지 않는다.

## 테스트
- `Ipv4Packet.parse` 단위 테스트: UDP, TCP, ICMP(포트 없음), IHL > 5, 길이 부족, IPv4가 아닌 패킷
- 서비스는 단위 테스트가 어려워서 에뮬레이터/실기기에서 수동 확인: 시작 -> 테스트 패킷 -> Logcat에 `dst=10.0.0.1 proto=UDP port=9999` 출력 -> 중지 시 키 아이콘 사라짐
- 작업 마무리에 `./gradlew assembleDebug testDebugUnitTest lintDebug` 통과

## 구현자에게 맡기는 것
- Compose 화면의 세부 모양 (최소한의 버튼 3개)
- 로그 태그와 포맷 문구
- 상태 표시 방식 (실행 중/중지)
