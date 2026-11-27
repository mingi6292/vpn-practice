# CLAUDE.md

## 프로젝트
Android VpnService 연습 앱. ZTNA Agent 팀 입사 전에 VpnService/ZTNA 관련 코드를 직접 짜 보며 익히는 용도다. 사용자가 코드를 직접 설명할 수 있어야 하므로 코드 양보다 이해하기 쉬운 코드가 우선이야.

구조: Android 앱(VpnService, TUN) <-UDP 평문 터널-> Docker로 띄운 터널 서버(`server/` 모듈, Kotlin/JVM + JNA로 Linux TUN 제어). 평문 터널을 먼저 만들고 TLS/인증은 나중에 붙인다.

## 기술 스택
- Kotlin, Jetpack Compose, Coroutines
- 빌드 설정은 build-logic convention plugin과 `gradle/libs.versions.toml`에서 관리
- 새 라이브러리는 꼭 필요할 때만 추가하고 Version Catalog에 등록
- CD는 GitHub Releases (태그 push 시 서명된 APK 첨부). 서명 키는 GitHub Secrets로 주입하며 시크릿 값은 코드/문서에 쓰지 않는다

## 작업 규칙
- 패키지는 `com.mg.vpnpractice` 아래에 둔다
- 진행 순서는 `docs/vpn-practice-plan.md`를 따른다. 해당 단계 범위를 넘는 기능은 먼저 확인하고 만든다
- VpnService 관련 코드에는 왜 그렇게 했는지 짧은 주석을 단다 (예: `protect()`를 호출하는 이유, fd를 닫는 시점)
- 파서 같은 순수 로직은 단위 테스트를 붙인다
- 작업이 끝나면 `./gradlew assembleDebug testDebugUnitTest lintDebug :server:test :tunnel-protocol:test`가 통과하는지 확인한다 (서버와 공용 모듈은 순수 JVM 모듈이라 `testDebugUnitTest`에 포함되지 않는다)
- 커밋 메시지는 한국어로 쓴다

## 참고 문서
- `docs/vpn-practice-plan.md` 연습 범위와 진행 순서
- `docs/lint-guide.md` lint 사용법
