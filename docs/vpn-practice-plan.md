# VpnService 연습 계획

목적: ZTNA Agent 팀 입사 전에 VpnService와 ZTNA 관련 코드를 직접 만들어 보고 설명할 수 있게 된다.

## 구조
```
Android 앱 (VpnService, TUN)  <-- UDP 평문 터널 -->  Docker 터널 서버 (server/, Kotlin/JVM + JNA, Linux TUN)
```
평문 터널을 먼저 만들고 TLS/인증은 나중에 붙인다.
배포는 GitHub Releases다. 태그를 push하면 서명된 APK가 첨부된다. 서명 키는 GitHub Secrets로 주입한다.

## 진행 순서
1. 문서/테마 정리 + 로컬 lint
2. CD (GitHub Releases, 서명된 APK)
3. 앱에서 TUN 열기와 패킷 파싱
4. Docker 서버와 평문 UDP 터널
5. split tunnel / 앱별 정책
6. TLS / 인증

## 연습 범위
1. `VpnService.prepare()`로 사용자 동의 받기
   - ZTNA 관점: 에이전트가 처음 켜질 때의 동의 흐름, 동의 철회 시 상태 복구
2. `VpnService.Builder`로 TUN 인터페이스 만들기 (주소 설정, `addRoute`, DNS)
   - ZTNA 관점: split tunnel. 사내 대역만 `addRoute`로 터널에 넣고 나머지는 직접 나가게 한다
3. `addAllowedApplication` 또는 `addDisallowedApplication`으로 앱별 터널링
   - ZTNA 관점: 앱별 정책. 어떤 앱을 터널에 태울지 정책으로 결정한다
4. TUN fd에서 패킷을 읽고 IPv4 헤더를 파싱해서 로그 남기기 (목적지 IP, 프로토콜, 포트)
   - 이후 파싱한 패킷을 UDP 터널로 서버에 보내고, 서버가 Linux TUN으로 되돌려 주는 데까지 확장한다
5. 포그라운드 서비스 알림과 `onRevoke()` 처리
   - ZTNA 관점: 에이전트가 항상 떠 있어야 하므로 서비스가 죽거나 권한이 회수될 때의 복구
6. Always-on VPN 대응과 `protect()`로 소켓 루프 막기
   - 터널용 UDP 소켓이 다시 TUN으로 들어가지 않도록 `protect()`를 호출한다
7. (6단계) TLS 적용과 사용자/기기 인증
   - ZTNA 관점: 인증된 주체에게만 터널을 열어 준다

## 학습 점검 질문
- VPN 동의 Intent가 null일 때와 아닐 때 흐름이 어떻게 다른가
- TUN fd는 누가 언제 닫는가. 서비스가 죽거나 `onRevoke()`가 오면 어떻게 되나
- 읽기 루프는 어느 스레드에서 돌고 어떻게 멈추나
- `addRoute("0.0.0.0", 0)`이면 어떤 트래픽이 터널로 들어오나. split tunnel이면 무엇이 달라지나
- 자기 앱 트래픽이 다시 터널로 들어오는 루프를 어떻게 막나
- Android 14 이상에서 포그라운드 서비스 타입은 무엇으로 선언했나
- 앱별 정책은 어디에서 결정하고 어떻게 테스트하나
- 평문 터널에서 TLS/인증을 붙이면 어디가 바뀌나
