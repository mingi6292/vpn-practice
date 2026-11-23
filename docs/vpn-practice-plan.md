# VpnService 연습 계획

## 연습 범위
1. `VpnService.prepare()`로 사용자 동의 받기
2. `VpnService.Builder`로 TUN 인터페이스 만들기 (주소 설정, `addRoute`, DNS)
3. `addAllowedApplication` 또는 `addDisallowedApplication`으로 앱별 터널링
4. TUN fd에서 패킷을 읽고 IPv4 헤더를 파싱해서 로그 남기기 (목적지 IP, 프로토콜, 포트)
5. 포그라운드 서비스 알림과 `onRevoke()` 처리
6. Always-on VPN 대응과 `protect()`로 소켓 루프 막기

실제 서버 포워딩은 범위 밖. 4번까지 패킷을 보는 것만으로도 면접에서 할 얘기는 충분하다.

## Orca 비교 작업
같은 저장소에서 worktree 두 개를 만들고 각각 아래 프롬프트를 넣는다.

### A. 최소 구현 (브랜치 `feat/minimal`)
```
Kotlin으로 Android VpnService 최소 예제를 만들어줘.
Activity 하나에서 VPN 시작과 중지를 하고 TUN에서 읽은 IPv4 패킷의
목적지 IP, 프로토콜, 포트를 Logcat에 찍어줘.
포그라운드 서비스 알림과 onRevoke 처리를 포함하고
파일 수는 최소로 해줘.
```

### B. 구조화된 설계 (브랜치 `feat/structured`)
```
Kotlin으로 Android VpnService 예제를 만들어줘.
Activity 하나에서 VPN 시작과 중지를 하고 TUN에서 읽은 IPv4 패킷의
목적지 IP, 프로토콜, 포트를 Logcat에 찍어줘.
포그라운드 서비스 알림과 onRevoke 처리를 포함해줘.
VpnService는 생명주기만 맡고 패킷 읽기 루프, IP 헤더 파서, 앱별 허용 정책은
각각 별도 클래스로 분리해줘. 코루틴으로 읽기 루프를 돌리고
IP 헤더 파서는 단위 테스트를 붙여줘.
```

## 리뷰할 때 확인할 질문
- VPN 동의 Intent가 null일 때와 아닐 때 흐름이 어떻게 다른가
- TUN fd는 누가 언제 닫는가. 서비스가 죽거나 `onRevoke()`가 오면 어떻게 되나
- 읽기 루프는 어느 스레드에서 돌고 어떻게 멈추나
- `addRoute("0.0.0.0", 0)`이면 어떤 트래픽이 터널로 들어오나
- 자기 앱 트래픽이 다시 터널로 들어오는 루프를 어떻게 막나
- Android 14 이상에서 포그라운드 서비스 타입은 무엇으로 선언했나
- 두 구현 중 테스트하기 쉬운 쪽은 어디고 이유는 무엇인가
