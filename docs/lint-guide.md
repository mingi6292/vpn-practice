# Android Lint 가이드

이 프로젝트에서 `./gradlew lintDebug`를 돌리고 결과를 읽고 다루는 방법을 정리한 문서다.

## 1. lint가 무엇이고 왜 쓰나

Android Lint는 Android Gradle Plugin에 포함된 **정적 분석 도구**다. 코드와 리소스, 매니페스트, Gradle 설정을 실행하지 않고 읽어서 버그 가능성, 성능 문제, 보안 문제, 접근성 문제, 오래된 의존성 등을 찾는다.

| 구분 | 컴파일 | 단위 테스트 | lint |
|---|---|---|---|
| 하는 일 | 문법/타입 검사, 바이트코드 생성 | 작성한 시나리오를 실제로 실행해 결과 검증 | 코드를 실행하지 않고 규칙(패턴)으로 검사 |
| 잡는 것 | 문법 오류, 타입 불일치 | 로직 버그 (테스트가 있는 부분만) | 컴파일은 되지만 Android에서 문제가 되는 것 (권한 누락, API 레벨, 안 쓰는 리소스, 누락된 `foregroundServiceType` 등) |
| 못 잡는 것 | Android 플랫폼 규칙 위반 | 테스트가 없는 코드 | 런타임 값에 의존하는 로직 오류 |

세 가지는 서로 대체 관계가 아니다. 컴파일이 통과해도 lint에 걸릴 수 있고, lint가 깨끗해도 로직은 틀릴 수 있다.

## 2. 심각도와 빌드 실패 조건

lint 이슈에는 심각도가 있다. 높은 쪽부터:

- **Fatal**: 앱이 크래시하거나 심하게 오동작할 가능성이 큰 이슈
- **Error**: 반드시 고쳐야 할 문제
- **Warning**: 고치는 것이 좋은 문제 (기본 심각도)
- **Informational**: 참고용

어떤 Gradle 태스크가 빌드를 막는지는 태스크마다 다르다.

- `lintDebug` (이 프로젝트에서 직접 실행): 기본 설정에서 **Error 이상**이 있으면 실패한다. Warning은 리포트에만 남고 빌드는 통과한다.
- `lintVitalRelease`: release 빌드(`assembleRelease`, `bundleRelease`) 때 자동으로 끼어드는 검사로, **Fatal 이슈만** 검사해서 실패시킨다. 가볍게 돌도록 만든 최소 안전장치다.

따라서 debug 빌드만 만들 때는 lint가 돌지 않고, 릴리스 직전에야 Fatal 이슈가 터질 수 있다. 그래서 CI나 로컬에서 `lintDebug`를 미리 돌려 두는 것이다.

## 3. 리포트 위치와 읽는 법

`./gradlew lintDebug`를 실행하면 아래에 리포트가 생긴다.

- `app/build/reports/lint-results-debug.html` — 브라우저로 여는 리포트
- `app/build/reports/lint-results-debug.xml`, `lint-results-debug.sarif` — 도구/CI 연동용

HTML 리포트는 위쪽에 이슈 개수 요약이 있고, 아래에 카테고리(Correctness, Security, Performance, Usability, Accessibility 등)별로 이슈가 묶여 나온다. 각 이슈는 **ID**(예: `UnusedResources`), 위치(파일:줄), 설명, 해결 방법을 보여 준다. 이슈 ID를 알아 두면 뒤에서 설명하는 억제/설정을 할 때 그대로 쓴다.

## 4. 이 프로젝트의 실제 lint 결과

`./gradlew assembleDebug testDebugUnitTest lintDebug`를 실행했을 때 빌드는 성공했고, 이슈는 **Error 0개, Warning 31개**였다 (lint 9.4.1 기준). 종류별로 보면:

| ID | 개수 | 의미 | 예 |
|---|---|---|---|
| `GradleDependency` | 9 | 사용 중인 라이브러리보다 새 버전이 있음 | compose-bom 2026.02.01 -> 2026.11.01 |
| `NewerVersionAvailable` | 10 | 위와 비슷하지만 Version Catalog 선언을 대상으로 한 알림 | kotlin 2.2.10 -> 2.4.21 |
| `AndroidGradlePluginVersion` | 3 | AGP나 Gradle 자체의 새 버전 | Gradle 9.6.0 -> 9.9.2 |
| `UnusedResources` | 7 | 어디서도 참조되지 않는 리소스 | `colors.xml`의 `purple_200`, `teal_700`, `black`, `white` 등 |
| `RedundantLabel` | 1 | `AndroidManifest.xml`에서 Activity의 `android:label`이 application의 label과 같아서 중복 | `MainActivity`의 label |

해석:

- 31개 중 22개(`GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion`)는 "새 버전이 있다"는 알림이다. 코드 결함이 아니므로 급하지 않다. 업데이트는 별도 작업으로 하고 이 문서의 범위에서는 그대로 둔다.
- `UnusedResources`와 `RedundantLabel`은 템플릿에서 넘어온 잔재라서 정리할 수 있다. 다만 이 문서 작성 단계에서는 고치지 않았다.

참고로 이 프로젝트에는 `lint.xml`, baseline 파일, `lint { }` 설정이 아직 없다. 즉 위 결과는 전부 기본 설정에서 나온 것이다.

## 5. 설정하고 억제하는 방법

원칙: **먼저 고치고, 못 고칠 이유가 있을 때만 억제한다.** 억제는 문제를 숨길 뿐 없애지 않는다.

### `@SuppressLint`
코드의 특정 지점에서만 이슈를 끈다. 가장 좁은 범위라 가장 안전하다.

```kotlin
@SuppressLint("MissingPermission") // 권한은 호출 전에 확인함
fun start() { ... }
```

XML에서는 `tools:ignore="UnusedResources"`를 쓴다. 억제할 때는 **왜 안전한지 이유를 주석으로 남긴다.**

### `lint.xml`
모듈 루트(`app/lint.xml`)에 두고 이슈별 심각도를 바꾸거나 끈다.

```xml
<lint>
    <issue id="GradleDependency" severity="ignore" />
    <issue id="UnusedResources" severity="informational" />
</lint>
```

### `lint { }` DSL
Gradle 빌드 스크립트에서 설정한다 (이 프로젝트는 build-logic convention plugin에서 관리하므로 설정을 넣는다면 그 위치가 맞다).

```kotlin
android {
    lint {
        abortOnError = true      // Error가 있으면 빌드 실패 (기본값)
        warningsAsErrors = false // true면 Warning도 실패 처리
        baseline = file("lint-baseline.xml")
        disable += "GradleDependency"
    }
}
```

### baseline
현재 존재하는 이슈를 "기존 문제"로 기록해 두고, **새로 생기는 이슈만** 잡게 하는 방법이다. 이슈가 많은 기존 프로젝트에 lint를 처음 도입할 때 쓴다. baseline 파일이 없을 때 `lintDebug`를 실행하면 파일이 만들어진다.

### 남용하면 안 되는 이유
- `disable`이나 `severity="ignore"`는 그 이슈가 **앞으로 생길 모든 곳**에서 꺼진다. 한 번 쓴 억제가 나중의 진짜 버그를 가린다.
- baseline은 기존 문제를 눈에 안 보이게 만든다. "나중에 고치겠다"가 영원히 안 고쳐지기 쉽다.
- 억제가 늘수록 lint 결과를 신뢰할 수 없게 된다. 억제는 범위를 좁게(`@SuppressLint` > `lint.xml` > 전역 `disable`), 이유를 적어서 한다.

## 6. VpnService 작업에서 자주 만날 항목

아래는 VpnService 앱을 만들면서 lint가 지적할 가능성이 큰 영역이다. 이 프로젝트에는 아직 서비스 코드가 없어서 **지금은 실제로 발생한 결과가 아니다.** 해당 코드를 추가한 뒤 `lintDebug`를 돌려 실제로 나오는지 확인하자.

- **포그라운드 서비스 타입**: Android 14 이상에서는 포그라운드 서비스에 타입 선언이 필요하다. 매니페스트의 `android:foregroundServiceType`과 대응하는 권한(`FOREGROUND_SERVICE_*`)이 빠지면 lint가 지적한다. VPN 서비스에 어떤 타입이 맞는지는 공식 문서(foreground service types)를 확인하고 정한다.
- **권한 선언**: 포그라운드 서비스를 쓰려면 `FOREGROUND_SERVICE` 권한, 알림을 보이려면 Android 13 이상에서 `POST_NOTIFICATIONS`가 필요하다. 코드에서 필요한 권한이 매니페스트에 없으면 `MissingPermission` 계열 이슈로 나온다.
- **서비스 선언**: `VpnService`는 매니페스트에서 `android.permission.BIND_VPN_SERVICE` 권한과 `android.net.VpnService` intent-filter가 필요하다. 빠졌을 때 lint가 잡아 주는지는 실제로 돌려 확인한다.
- **`exported` 속성**: intent-filter가 있는 컴포넌트는 `android:exported`를 명시해야 한다 (Android 12 이상).
