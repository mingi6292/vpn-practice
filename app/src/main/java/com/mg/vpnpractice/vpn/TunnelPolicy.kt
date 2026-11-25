package com.mg.vpnpractice.vpn

// 터널 정책 모델. Android 의존성 없는 순수 Kotlin이라 단위 테스트로 검증한다.

const val CHROME_PACKAGE = "com.android.chrome"

/** TUN으로 보낼 목적지 대역. 예: 10.0.0.0/24 */
data class Route(val address: String, val prefixLength: Int)

/**
 * 앱 필터는 셋 중 하나만 고른다.
 * VpnService.Builder에 허용(addAllowedApplication)과 제외(addDisallowedApplication)를
 * 섞어 쓰면 UnsupportedOperationException이 나므로, 타입으로 섞을 수 없게 막는다.
 */
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

/** 설치되지 않은 패키지를 걸러 Builder에 넘길 수 있는 형태로 바꾼다. */
fun TunnelPolicy.resolve(isInstalled: (String) -> Boolean): PolicyResult =
    when (val rule = apps) {
        is AppRule.AllApps -> PolicyResult.Apply(routes, rule, emptyList())
        is AppRule.Only -> {
            val (installed, skipped) = rule.packages.partition(isInstalled)
            if (installed.isEmpty()) {
                // 허용 목록이 비면 Android는 앱 제한이 없는 것으로 보고 전체 앱을 터널에 태운다.
                // 의도와 정반대가 되므로 적용하지 않고 거부한다.
                PolicyResult.Reject("허용할 앱이 설치되어 있지 않음: ${rule.packages}")
            } else {
                PolicyResult.Apply(routes, AppRule.Only(installed), skipped)
            }
        }
        is AppRule.Except -> {
            // 제외 목록이 비어도 "전체 앱"과 같을 뿐이라 안전하다.
            val (installed, skipped) = rule.packages.partition(isInstalled)
            PolicyResult.Apply(routes, AppRule.Except(installed), skipped)
        }
    }

enum class PolicyPreset(val policy: TunnelPolicy) {
    ALL_APPS(TunnelPolicy(PRACTICE_ROUTES, AppRule.AllApps)),
    CHROME_ONLY(TunnelPolicy(PRACTICE_ROUTES, AppRule.Only(listOf(CHROME_PACKAGE)))),
    EXCEPT_CHROME(TunnelPolicy(PRACTICE_ROUTES, AppRule.Except(listOf(CHROME_PACKAGE))));

    companion object {
        /**
         * 모르는 이름이나 null은 ALL_APPS로 떨어진다.
         * 시스템이 Intent 없이 서비스를 다시 띄울 수 있어 이름이 null일 수 있기 때문이다.
         */
        fun fromName(name: String?): PolicyPreset =
            entries.firstOrNull { it.name == name } ?: ALL_APPS
    }
}

// 연습용 서브넷만 터널로 보낸다 (0.0.0.0/0은 쓰지 않는다).
private val PRACTICE_ROUTES = listOf(Route("10.0.0.0", 24))
