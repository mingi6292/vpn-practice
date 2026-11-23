# CLAUDE.md

## 프로젝트
Android VpnService 학습용 연습 앱. 목적은 면접에서 구현 이유를 설명할 수 있게 되는 것이라서 코드 양보다 이해하기 쉬운 코드가 우선이야.

## 기술 스택
- Kotlin, Jetpack Compose, Coroutines
- 빌드 설정은 build-logic convention plugin과 `gradle/libs.versions.toml`에서 관리
- 새 라이브러리는 꼭 필요할 때만 추가하고 Version Catalog에 등록

## 작업 규칙
- 패키지는 `com.mg.vpnpractice` 아래에 둔다
- 실제 원격 서버로 패킷을 포워딩하는 기능은 만들지 않는다. TUN에서 읽은 패킷을 파싱하고 로그로 남기는 데까지만
- VpnService 관련 코드에는 왜 그렇게 했는지 짧은 주석을 단다 (예: `protect()`를 호출하는 이유, fd를 닫는 시점)
- 파서 같은 순수 로직은 단위 테스트를 붙인다
- 작업이 끝나면 `./gradlew assembleDebug testDebugUnitTest`가 통과하는지 확인한다
- 커밋 메시지는 한국어로 쓴다

## 참고 문서
- `docs/vpn-practice-plan.md` 연습 범위
