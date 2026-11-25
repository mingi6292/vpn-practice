# 5단계 설계: split tunnel / 앱별 정책

## 목적
ZTNA Agent의 "어떤 트래픽을, 어떤 앱의 것을 터널에 태울지 정책으로 정한다"를 VpnService로 직접 만들고 설명할 수 있게 된다.
`docs/vpn-practice-plan.md`의 5단계이며 연습 범위 2번(`addRoute`로 split tunnel)과 3번(`addAllowedApplication`/`addDisallowedApplication`으로 앱별 터널링)에 해당한다.

지금은 `PracticeVpnService.startVpn()`에 `addRoute("10.0.0.0", 24)`가 하드코딩돼 있고 앱 목록 설정이 없다. 이 단계에서 이를 "정책 -> Builder 설정" 구조로 바꾼다.

## 범위
- 앱 안에 고정된 정책 프리셋 3개를 두고 화면에서 고른다.
- 정책을 Builder 설정(라우트 목록, 허용 또는 제외 패키지 목록)으로 바꾸는 순수 함수와 그 단위 테스트
- 서비스가 시작 Intent로 받은 정책을 Builder에 적용한다.
- 실행 중인 정책 이름을 화면 상태 텍스트와 Logcat에 표시한다.

## 범위 밖
- 사용자가 앱을 체크해서 목록을 만드는 UI
- 서버가 정책을 내려주는 방식, 실행 중 정책 변경(재 `establish()`) (6단계)
- `0.0.0.0/0` 전체 라우트 프리셋 (아래 결정 사항 참고)
- 선택한 정책을 디스크에 저장하기. 앱을 다시 켜면 기본 프리셋으로 돌아간다. DataStore 같은 새 라이브러리는 넣지 않는다.
- IPv6 라우트, DNS 설정
- 실패하면 안 되는 것: 어떤 프리셋을 골라도 에뮬레이터의 일반 인터넷이 끊기면 안 된다. 터널 소켓이 TUN으로 되돌아오는 루프가 생기면 안 된다.

## 접근법
정책을 어디서 정하는가:
1. **앱 안 고정 프리셋 중 선택 (선택)**: 정책 모델과 변환 함수에 집중할 수 있고 테스트가 쉽다.
2. 설치된 앱 목록을 보여 주고 체크하는 UI: UI 코드가 늘고 VpnService 학습 포인트는 1번과 같다. 뺐다.
3. 서버가 정책을 내려줌: 실제 ZTNA에 가깝지만 터널 위 제어 메시지와 인증이 필요하다. 6단계로 미뤘다.

실행 중에 프리셋을 바꾸면:
- **A. 실행 중에는 선택을 막는다 (선택)**: 바꾸려면 중지 후 다시 시작한다. 정책 하나에 `establish()` 한 번이 대응해서 설명하기 쉽다.
- B. 새 정책으로 다시 `establish()`해서 새 fd로 넘긴다: 읽기/쓰기 루프를 새 fd로 옮기는 동시성 코드가 늘어난다. 서버가 정책을 바꿔 내려주는 6단계에서 필요해지므로 그때 다룬다.

## 정책 모델
```kotlin
// 개념 스케치. 이름과 세부 형태는 구현자가 정한다.
data class Route(val address: String, val prefixLength: Int)

sealed interface AppRule {
    data object AllApps : AppRule                          // 앱 목록 설정 안 함
    data class Only(val packages: List<String>) : AppRule  // addAllowedApplication
    data class Except(val packages: List<String>) : AppRule // addDisallowedApplication
}

data class TunnelPolicy(val routes: List<Route>, val apps: AppRule)
```
- 허용과 제외를 한 Builder에 섞으면 `UnsupportedOperationException`이 난다. `AppRule`을 셋 중 하나만 고르는 타입으로 만들어 섞는 실수가 코드로 표현되지 않게 한다.
- 프리셋은 `enum`(또는 비슷한 고정 목록)으로 두고 각 항목이 `TunnelPolicy`를 가진다. Intent에는 프리셋 이름(문자열)만 넣는다.

### 프리셋
| 프리셋 | 라우트 | 앱 | 수동 확인 |
|---|---|---|---|
| 전체 앱 (기본) | `10.0.0.0/24` | `AllApps` | 지금과 같다. 테스트 패킷 버튼이 터널을 탄다 |
| Chrome만 | `10.0.0.0/24` | `Only(com.android.chrome)` | 테스트 버튼은 터널을 안 탄다(우리 앱이 목록에 없음). Chrome으로 `http://10.0.0.1` 접속 시 `[to-server]` 로그 |
| Chrome 빼고 | `10.0.0.0/24` | `Except(com.android.chrome)` | 테스트 버튼은 탄다. Chrome 접속은 안 탄다 |

서버에 NAT가 없으므로 Chrome 접속은 실패한다. 앱 Logcat의 `[to-server]` SYN과 서버가 돌려준 RST(`[from-server]`)로 터널을 탔는지 확인한다.

## 변환 함수 (순수 로직, 단위 테스트 대상)
정책과 "이 패키지가 설치돼 있는가" 판정 함수를 받아 Builder에 적용할 결과를 돌려준다. `PackageManager`는 판정 함수로 주입해서 함수 자체는 Android에 의존하지 않는다.

결과에 담을 것:
- 적용할 라우트 목록
- 허용 또는 제외로 적용할 패키지 목록 (설치된 것만)
- 건너뛴 패키지 목록 (로그용)
- 시작하면 안 되는 경우의 실패: `Only`인데 설치된 패키지가 하나도 없을 때. 허용 목록이 비면 Android는 앱 제한이 없는 것으로 보고 전체 앱을 터널에 태운다. 정책이 뜻한 것과 반대가 되므로 시작하지 않는다.

`Except`에서 설치된 패키지가 하나도 없으면 결과적으로 전체 앱과 같다. 뜻과 어긋나지 않으므로 경고 로그만 남기고 시작한다.

테스트할 경우:
- `AllApps` -> 라우트만 있고 패키지 목록 없음
- `Only` / `Except` -> 해당 종류의 패키지 목록
- 일부 패키지가 설치 안 됨 -> 건너뛴 목록에 들어가고 나머지는 적용
- `Only`의 패키지가 모두 설치 안 됨 -> 실패
- `Except`의 패키지가 모두 설치 안 됨 -> 성공(빈 제외 목록)

## 구성
| 파일 | 역할 |
|------|------|
| `app/.../vpn/TunnelPolicy.kt` (신규) | 정책 모델, 프리셋, 변환 함수 |
| `app/src/test/.../vpn/TunnelPolicyTest.kt` (신규) | 변환 함수 단위 테스트 |
| `app/.../vpn/PracticeVpnService.kt` (수정) | 시작 Intent의 프리셋 extra를 읽고, 변환 결과를 Builder에 적용한다. 실패면 시작하지 않고 정리한다. 실행 중인 프리셋을 `StateFlow`로 노출한다 |
| `app/.../MainActivity.kt` (수정) | 프리셋 라디오 버튼. 실행 중에는 비활성이고 실행 중인 프리셋을 보여 준다. 시작 Intent에 프리셋을 넣는다. 상태 텍스트에 정책 이름 |
| `app/src/main/AndroidManifest.xml` (수정) | `<queries>`에 `com.android.chrome`. Android 11부터 패키지 가시성 제한으로 Chrome이 안 보이면 `addAllowedApplication`이 `NameNotFoundException`을 던진다 |
| `app/src/main/res/values/strings.xml` (수정) | 프리셋 이름, 상태 문구 |

화면:
```
실행 중 (정책: Chrome만)
( ) 전체 앱
(•) Chrome만        <- 실행 중엔 비활성
( ) Chrome 빼고
[시작] [중지] [테스트 패킷 보내기]
```

## 결정 사항
- `0.0.0.0/0` 프리셋은 넣지 않는다. 서버에 NAT가 없어 인터넷이 끊기지 않으려면 "우리 앱만 허용"과 묶어야 하는데, 그러면 터널 소켓이 보내는 `10.0.2.2` 행 패킷도 TUN으로 들어와 루프가 생긴다. 이 루프는 `protect()`로 막아야 하므로 6단계에서 다룬다. (대화에서 합의)
- Intent extra가 없거나 모르는 값이면 기본 프리셋(전체 앱)으로 시작한다. 시스템이 서비스를 Intent 없이 다시 띄우는 경우(Always-on 등)를 대비한다. (내 선택)
- 실행 중에는 화면이 서비스가 노출한 "실행 중인 프리셋"을 보여 준다. 앱을 다시 열었을 때 화면 선택과 실제 정책이 달라 보이지 않게 하려는 것이다. 중지 상태에서는 화면 선택값(`rememberSaveable`)을 보여 준다. (내 선택)
- Builder 적용 중 `NameNotFoundException`이 나면(판정과 적용 사이에 앱이 지워진 경우 등) 그 패키지만 건너뛰고 로그를 남긴다. 단, 그 결과 `Only`의 적용 패키지가 0개가 되면 시작하지 않는다. (내 선택)
- "Chrome만" 프리셋에서 우리 앱의 터널 소켓은 허용 목록 밖이라 TUN을 타지 않는다. 그래서 `protect()` 없이도 루프가 없다. 이 이유를 코드 주석으로 남긴다. "전체 앱"과 "Chrome 빼고"는 4단계와 같이 서버 주소가 라우트 밖이라 루프가 없다.
- `Only` 실패로 시작하지 않을 때는 Logcat 경고만 남기고 화면은 "중지됨" 그대로 둔다. 실패 이유를 화면에 보이려면 상태 모델이 늘어나는데, 프리셋이 고정이라 에뮬레이터에 Chrome이 없을 때만 생기는 일이다. (내 선택, 리뷰 후 추가)
- 새 라이브러리 없음.

## 테스트
- 단위 테스트: 위 "테스트할 경우". 서비스와 화면은 단위 테스트하지 않는다.
- 수동 확인 (에뮬레이터 `Pixel_8`, 서버 컨테이너 실행 상태):
  - 먼저 AVD에 Chrome이 깔려 있는지, Android 17에서 Chrome이 `10.0.0.1` 접속에 로컬 네트워크 권한을 요구하는지 확인한다. 요구하면 Chrome에서 허용한다.
  - 프리셋마다 위 표의 확인 방법대로 테스트 버튼과 Chrome 접속 결과를 본다.
  - 실행 중에 라디오 버튼이 비활성인지, 중지 후 다른 프리셋으로 다시 시작되는지 본다.
  - 어떤 프리셋에서도 일반 웹사이트 접속이 되는지 본다.
- 작업 마무리에 `./gradlew assembleDebug testDebugUnitTest lintDebug` 통과.

## 구현자에게 맡기는 것
- 이름(클래스, 함수, extra 키)과 변환 결과 타입의 형태
- 프리셋을 화면에 어떤 문자열로 보여 줄지(문자열 리소스 매핑 방식)
- `isRunning`을 유지하고 `runningPreset`을 추가할지, 하나로 합칠지
- 설치 판정에 쓰는 `PackageManager` API 선택
