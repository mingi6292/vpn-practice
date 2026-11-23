# vpn-practice

Android VpnService 연습 프로젝트. ZTNA Agent 팀 입사 전에 VpnService/ZTNA 관련 코드를 연습하는 용도.

android-starter 템플릿(build-logic convention plugin, Version Catalog, GitHub Actions CI)으로 시작했어.

## 목표
- VpnService 생명주기와 TUN 인터페이스 동작을 직접 설명할 수 있을 만큼 이해하기
- Android 앱 <-UDP 평문 터널-> Docker 터널 서버 구조를 직접 만들어 보기
- split tunnel, 앱별 정책, 인증 등 ZTNA Agent에 필요한 개념을 코드로 익히기

## 문서
- `docs/vpn-practice-plan.md` 연습 범위와 진행 순서
- `docs/lint-guide.md` lint 사용법
- `CLAUDE.md` 에이전트 작업 규칙

## 실행
```
./gradlew assembleDebug testDebugUnitTest lintDebug
```
