# split tunnel / 앱별 정책 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 화면에서 고른 정책 프리셋(전체 앱 / Chrome만 / Chrome 빼고)을 `VpnService.Builder`의 라우트와 허용/제외 앱 설정으로 적용한다.

**Architecture:** `TunnelPolicy.kt`에 정책 모델, 프리셋, 순수 변환 함수 `TunnelPolicy.resolve(isInstalled)`를 둔다. `PracticeVpnService`는 시작 Intent의 프리셋 이름을 읽어 `resolve()` 결과를 Builder에 적용하고, 실행 중인 프리셋을 `StateFlow`로 노출한다. `MainActivity`는 라디오 버튼으로 프리셋을 고르고 실행 중에는 선택을 막는다.

**Tech Stack:** Kotlin, Jetpack Compose, 코루틴, JUnit4 (모두 기존 항목. 새 라이브러리 없음)

**Spec:** `docs/superpowers/specs/2026-11-26-split-tunnel-app-policy-design.md`

## Global Constraints

- 프리셋 3개, 모두 라우트는 `10.0.0.0/24` 하나뿐. `0.0.0.0/0` 라우트 금지
  - `ALL_APPS`(전체 앱, 기본): `AppRule.AllApps`
  - `CHROME_ONLY`(Chrome만): `AppRule.Only(listOf("com.android.chrome"))`
  - `EXCEPT_CHROME`(Chrome 빼고): `AppRule.Except(listOf("com.android.chrome"))`
- 앱 TUN 주소 `10.0.0.2/24`, MTU 1500, 세션 이름 `vpn-practice`는 그대로 둔다
- 실행 중에는 프리셋을 바꿀 수 없다. 바꾸려면 중지 후 다시 시작한다. 재 `establish()` 금지
- 시작 Intent에 프리셋 extra가 없거나 모르는 값이면 `ALL_APPS`
- `Only`에서 설치된 패키지가 0개면 시작하지 않는다(Logcat 경고만, 화면은 "중지됨"). `Except`에서 0개면 경고 후 시작한다
- 선택은 디스크에 저장하지 않는다(`rememberSaveable`까지만). 새 라이브러리 없음
- 패키지 `com.mg.vpnpractice.vpn`. VpnService 코드에는 왜 그렇게 했는지 짧은 주석 (CLAUDE.md)
- 커밋 메시지는 한국어, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 작업 브랜치 `feat/split-tunnel-app-policy` (이미 있음, 설계 문서 커밋됨). 이 계획 문서는 Task 1 커밋에 포함한다
- 마무리 확인: `./gradlew assembleDebug testDebugUnitTest lintDebug`

## Review Focus

- 시스템이 서비스를 Intent 없이 다시 띄운다(Always-on 등). `ALL_APPS`로 시작해야 한다 -> Task 1 `unknown_or_missing_name_falls_back_to_all_apps`
- 에뮬레이터에 Chrome이 없는데 "Chrome만"으로 시작한다. 전체 앱이 터널을 타면 안 되고 시작이 거부돼야 한다 -> Task 1 `only_with_no_installed_package_is_rejected`, Task 3 수동 확인
- 어떤 프리셋도 일반 인터넷을 끊으면 안 된다 -> Task 1 `every_preset_routes_only_the_practice_subnet`
- VPN 실행 중에 앱을 다시 열었다. 화면이 기본값이 아니라 실제로 실행 중인 프리셋을 보여 주고 선택이 비활성이어야 한다 -> Task 3 수동 확인
- 실행 중에 "시작"을 다시 누른다. 정책이 바뀌거나 터널이 다시 열리면 안 된다(`startVpn()`의 `tunFd != null` 조기 반환 유지) -> Task 3 수동 확인

---

### Task 1: 정책 모델과 변환 함수

**Files:**
- Create: `app/src/main/java/com/mg/vpnpractice/vpn/TunnelPolicy.kt`
- Test: `app/src/test/java/com/mg/vpnpractice/vpn/TunnelPolicyTest.kt`
- Add: `docs/superpowers/plans/2026-11-26-split-tunnel-app-policy.md` (이 문서)

**Interfaces:**
- Produces:
  ```kotlin
  data class Route(val address: String, val prefixLength: Int)

  sealed interface AppRule {
      data object AllApps : AppRule
      data class Only(val packages: List<String>) : AppRule
      data class Except(val packages: List<String>) : AppRule
  }

  data class TunnelPolicy(val routes: List<Route>, val apps: AppRule)

  sealed interface PolicyResult {
      /** apps에는 설치된 패키지만 남는다. skipped는 로그용. */
      data class Apply(val routes: List<Route>, val apps: AppRule, val skipped: List<String>) : PolicyResult
      data class Reject(val reason: String) : PolicyResult
  }

  fun TunnelPolicy.resolve(isInstalled: (String) -> Boolean): PolicyResult

  enum class PolicyPreset(val policy: TunnelPolicy) {
      ALL_APPS, CHROME_ONLY, EXCEPT_CHROME;
      companion object { fun fromName(name: String?): PolicyPreset }
  }

  const val CHROME_PACKAGE = "com.android.chrome"
  ```

- [ ] **Step 1: 실패하는 테스트 작성** (`TunnelPolicyTest`, JUnit4, 기존 `TunWriteTest`처럼 `org.junit.Assert` 사용)

  `private val route = Route("10.0.0.0", 24)`, 설치 판정은 `{ it in setOf("a") }` 같은 람다로 준다.
  - `all_apps_keeps_routes_and_sets_no_packages`: `TunnelPolicy(listOf(route), AllApps).resolve { false }` == `Apply(listOf(route), AllApps, emptyList())`
  - `only_keeps_installed_packages`: `Only(listOf("a"))`, 설치 `{a}` -> `Apply(listOf(route), Only(listOf("a")), emptyList())`
  - `except_keeps_installed_packages`: `Except(listOf("a"))`, 설치 `{a}` -> `Apply(..., Except(listOf("a")), emptyList())`
  - `missing_packages_are_skipped_and_rest_applied`: `Only(listOf("a", "b"))`, 설치 `{a}` -> `Apply(..., Only(listOf("a")), listOf("b"))`
  - `only_with_no_installed_package_is_rejected`: `Only(listOf("a"))`, 설치 없음 -> `is PolicyResult.Reject`
  - `except_with_no_installed_package_is_applied_with_empty_list`: `Except(listOf("a"))`, 설치 없음 -> `Apply(..., Except(emptyList()), listOf("a"))`
  - `unknown_or_missing_name_falls_back_to_all_apps`: `fromName(null)`, `fromName("BOGUS")` == `ALL_APPS`; `fromName("CHROME_ONLY")` == `CHROME_ONLY`
  - `every_preset_routes_only_the_practice_subnet`: 모든 `PolicyPreset.entries`의 `policy.routes` == `listOf(Route("10.0.0.0", 24))`
  - `chrome_presets_use_chrome_package`: `CHROME_ONLY.policy.apps` == `Only(listOf(CHROME_PACKAGE))`, `EXCEPT_CHROME.policy.apps` == `Except(listOf(CHROME_PACKAGE))`

- [ ] **Step 2: 실패 확인**

  Run: `./gradlew :app:testDebugUnitTest --tests "com.mg.vpnpractice.vpn.TunnelPolicyTest"`
  Expected: 컴파일 실패 (`Unresolved reference: TunnelPolicy`)

- [ ] **Step 3: `TunnelPolicy.kt` 구현**

  위 Interfaces 그대로. Android import 없이 순수 Kotlin으로 둔다. 주석으로 남길 것:
  - `AppRule`이 셋 중 하나인 이유: Builder에 허용/제외를 섞으면 `UnsupportedOperationException`
  - `Only`가 0개면 `Reject`인 이유: 허용 목록이 비면 Android는 앱 제한이 없는 것으로 보고 전체 앱을 태운다
  - `fromName`이 기본값으로 떨어지는 이유: 시스템이 Intent 없이 서비스를 다시 띄울 수 있다

- [ ] **Step 4: 통과 확인**

  Run: `./gradlew :app:testDebugUnitTest --tests "com.mg.vpnpractice.vpn.TunnelPolicyTest"`
  Expected: 9개 테스트 PASS

- [ ] **Step 5: 커밋**

  ```bash
  git add app/src/main/java/com/mg/vpnpractice/vpn/TunnelPolicy.kt app/src/test/java/com/mg/vpnpractice/vpn/TunnelPolicyTest.kt docs/superpowers/plans/2026-11-26-split-tunnel-app-policy.md
  git commit -m "feat: 터널 정책 모델과 Builder 설정 변환 함수 추가"
  ```

### Task 2: 서비스에 정책 적용, 화면에서 프리셋 선택

서비스의 `isRunning`을 `runningPreset`으로 바꾸면 화면도 같이 바뀌어야 컴파일되므로 한 작업으로 묶는다.

**Files:**
- Modify: `app/src/main/java/com/mg/vpnpractice/vpn/PracticeVpnService.kt` (`onStartCommand`, `startVpn`, `stopVpn`, companion)
- Modify: `app/src/main/java/com/mg/vpnpractice/MainActivity.kt` (`VpnScreen`, `requestVpnConsent`, `startVpnService`)
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: Task 1의 `PolicyPreset`, `PolicyPreset.fromName`, `TunnelPolicy.resolve`, `PolicyResult`, `AppRule`, `Route`
- Produces:
  - `PracticeVpnService.EXTRA_PRESET = "com.mg.vpnpractice.extra.PRESET"` (값은 `PolicyPreset.name`)
  - `PracticeVpnService.runningPreset: StateFlow<PolicyPreset?>` (null = 중지됨). 기존 `isRunning`은 지운다. 상태를 하나로 둬서 "실행 중인데 정책 없음" 같은 어긋난 상태가 생기지 않게 한다

- [ ] **Step 1: 매니페스트와 문자열**

  - 매니페스트 `<application>` 앞에 `<queries><package android:name="com.android.chrome" /></queries>`. 주석: Android 11부터 패키지 가시성 제한 때문에 없으면 Chrome이 설치돼 있어도 `NameNotFoundException`
  - strings: `preset_all_apps`=`전체 앱`, `preset_chrome_only`=`Chrome만`, `preset_except_chrome`=`Chrome 빼고`, `status_running`을 `실행 중 (정책: %1$s)`로 변경

- [ ] **Step 2: 서비스 수정**

  - `onStartCommand`: `startVpn(PolicyPreset.fromName(intent?.getStringExtra(EXTRA_PRESET)))`
  - `startVpn(preset: PolicyPreset)`: 기존 `tunFd != null` 조기 반환과 `startAsForeground()` 순서는 유지. 이어서 `preset.policy.resolve(::isInstalled)`
    - `Reject` -> `Log.w`로 이유 남기고 `stopVpn()` 후 반환
    - `Apply` -> `skipped`마다 `Log.w`. Builder에 `routes`를 `addRoute`, `apps`가 `Only`면 `addAllowedApplication`, `Except`면 `addDisallowedApplication`. 각 호출의 `PackageManager.NameNotFoundException`은 잡아서 로그 후 건너뛴다. `Only`인데 실제로 적용된 패키지가 0개면 `establish()` 하지 않고 `stopVpn()`
    - 성공하면 `Log.i(TAG, "policy=${preset.name} routes=... apps=...")`, `_runningPreset.value = preset`
  - `private fun isInstalled(packageName: String): Boolean`: `packageManager.getApplicationInfo`를 try/catch(`NameNotFoundException`)
  - `stopVpn()`: `_runningPreset.value = null`
  - 주석으로 남길 것: 기존 `addRoute` 주석(0.0.0.0/0이면 인터넷이 끊김) 유지, "Chrome만"에서는 우리 앱이 허용 목록 밖이라 터널 소켓이 TUN을 타지 않아 `protect()` 없이도 루프가 없다는 점, 정책은 `establish()` 시점에 고정되므로 바꾸려면 다시 시작해야 한다는 점

- [ ] **Step 3: 화면 수정**

  - `val runningPreset by PracticeVpnService.runningPreset.collectAsState()`, `var selected by rememberSaveable { mutableStateOf(PolicyPreset.ALL_APPS) }`
  - 화면에 보일 선택값 = `runningPreset ?: selected`. 라디오 버튼 3개(`Row` + `RadioButton` + `Text`, `Modifier.selectable`), `enabled = runningPreset == null`
  - 상태 텍스트: 실행 중이면 `stringResource(R.string.status_running, 프리셋 이름)`, 아니면 `status_stopped`
  - 프리셋 -> 문자열 리소스 매핑은 `MainActivity.kt` 안의 `@StringRes private fun PolicyPreset.labelRes(): Int`로 둔다(`TunnelPolicy.kt`를 순수 Kotlin으로 유지)
  - `startVpnService(context, preset)`가 `putExtra(PracticeVpnService.EXTRA_PRESET, preset.name)`. `requestVpnConsent`와 동의 결과 콜백도 `selected`를 넘긴다
  - 테스트 패킷 버튼 `enabled = runningPreset != null`

- [ ] **Step 4: 빌드, 테스트, lint**

  Run: `./gradlew assembleDebug testDebugUnitTest lintDebug`
  Expected: BUILD SUCCESSFUL, 새 lint 경고 없음

- [ ] **Step 5: 커밋**

  ```bash
  git add app/src/main
  git commit -m "feat: 정책 프리셋을 골라 VPN 라우트와 앱 목록에 적용"
  ```

### Task 3: 에뮬레이터 수동 확인

코드 변경이 없으면 커밋하지 않는다. 확인 중 고친 것이 있으면 Task 2 범위 안에서 고치고 Step 4 명령을 다시 돌린 뒤 커밋한다.

준비: 서버 컨테이너 실행(`docs/docker-guide.md`), 에뮬레이터 `Pixel_8`. 실기기가 함께 연결돼 있으니 `ANDROID_SERIAL=emulator-5554`로 대상을 지정해 설치한다. Logcat 태그 `PracticeVpn`.

- [ ] **Step 1: 사전 확인**: `adb shell pm list packages com.android.chrome`로 Chrome이 있는지 본다. Chrome에서 `http://10.0.0.1`에 접속할 때 로컬 네트워크 권한 요청이 뜨면 허용한다
- [ ] **Step 2: 전체 앱**: 테스트 패킷 버튼 -> `[to-server]` 로그. Chrome `http://10.0.0.1` -> `[to-server]` TCP SYN, `[from-server]` RST
- [ ] **Step 3: Chrome만**: 테스트 패킷 버튼 -> `[to-server]` 로그 없음. Chrome 접속 -> `[to-server]` 로그 있음
- [ ] **Step 4: Chrome 빼고**: 테스트 패킷 버튼 -> 로그 있음. Chrome 접속 -> 로그 없음
- [ ] **Step 5: 공통**: 각 프리셋에서 Chrome으로 일반 웹사이트가 열린다. 실행 중 라디오 버튼이 비활성이다. 실행 중 앱을 최근 앱에서 지우고 다시 열면 실행 중인 프리셋이 선택돼 보인다. 실행 중 "시작"을 다시 눌러도 `policy=` 로그가 새로 찍히지 않는다
- [ ] **Step 6: 거부 경로**(가능하면): `adb shell pm disable-user --user 0 com.android.chrome` 후 "Chrome만"으로 시작 -> `Log.w` 거부 로그, 화면 "중지됨". 확인 후 `pm enable com.android.chrome`으로 되돌린다. 비활성 앱도 `getApplicationInfo`가 찾아서 거부가 재현되지 않으면 이 단계는 건너뛰고 Task 1 단위 테스트로 갈음한다(그 사실을 보고에 적는다)
