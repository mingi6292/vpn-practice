# vpn-practice

Android VpnService 연습 프로젝트. 모니터랩 면접 준비용.

android-starter 템플릿(build-logic convention plugin, Version Catalog, GitHub Actions CI)으로 시작했어.

## 목표
- VpnService 생명주기와 TUN 인터페이스 동작을 직접 설명할 수 있을 만큼 이해하기
- 같은 기능을 두 가지 방식(최소 구현, 구조화된 설계)으로 만들고 비교하기

## 문서
- `docs/vpn-practice-plan.md` 연습 범위와 Orca용 프롬프트
- `docs/review-notes.md` 두 구현을 비교하면서 남기는 메모
- `CLAUDE.md` 에이전트 작업 규칙

## 실행
```
./gradlew assembleDebug testDebugUnitTest
```
