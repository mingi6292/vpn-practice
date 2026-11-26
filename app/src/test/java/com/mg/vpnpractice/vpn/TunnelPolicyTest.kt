package com.mg.vpnpractice.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelPolicyTest {
    private val route = Route("10.0.0.0", 24)

    @Test
    fun all_apps_keeps_routes_and_sets_no_packages() {
        val result = TunnelPolicy(listOf(route), AppRule.AllApps).resolve { false }
        assertEquals(PolicyResult.Apply(listOf(route), AppRule.AllApps, emptyList()), result)
    }

    @Test
    fun only_keeps_installed_packages() {
        val result = TunnelPolicy(listOf(route), AppRule.Only(listOf("a"))).resolve { it in setOf("a") }
        assertEquals(PolicyResult.Apply(listOf(route), AppRule.Only(listOf("a")), emptyList()), result)
    }

    @Test
    fun except_keeps_installed_packages() {
        val result = TunnelPolicy(listOf(route), AppRule.Except(listOf("a"))).resolve { it in setOf("a") }
        assertEquals(PolicyResult.Apply(listOf(route), AppRule.Except(listOf("a")), emptyList()), result)
    }

    @Test
    fun missing_packages_are_skipped_and_rest_applied() {
        val result = TunnelPolicy(listOf(route), AppRule.Only(listOf("a", "b"))).resolve { it in setOf("a") }
        assertEquals(PolicyResult.Apply(listOf(route), AppRule.Only(listOf("a")), listOf("b")), result)
    }

    @Test
    fun only_with_no_installed_package_is_rejected() {
        val result = TunnelPolicy(listOf(route), AppRule.Only(listOf("a"))).resolve { false }
        assertTrue(result is PolicyResult.Reject)
        assertTrue("a" in (result as PolicyResult.Reject).reason)
    }

    @Test
    fun except_missing_packages_are_skipped_and_rest_applied() {
        val result = TunnelPolicy(listOf(route), AppRule.Except(listOf("a", "b"))).resolve { it == "a" }
        assertEquals(PolicyResult.Apply(listOf(route), AppRule.Except(listOf("a")), listOf("b")), result)
    }

    @Test
    fun except_with_no_installed_package_is_applied_with_empty_list() {
        val result = TunnelPolicy(listOf(route), AppRule.Except(listOf("a"))).resolve { false }
        assertEquals(PolicyResult.Apply(listOf(route), AppRule.Except(emptyList()), listOf("a")), result)
    }

    @Test
    fun unknown_or_missing_name_falls_back_to_all_apps() {
        assertEquals(PolicyPreset.ALL_APPS, PolicyPreset.fromName(null))
        assertEquals(PolicyPreset.ALL_APPS, PolicyPreset.fromName("BOGUS"))
        assertEquals(PolicyPreset.CHROME_ONLY, PolicyPreset.fromName("CHROME_ONLY"))
    }

    @Test
    fun every_preset_routes_only_the_practice_subnet() {
        PolicyPreset.entries.forEach {
            assertEquals(listOf(Route("10.0.0.0", 24)), it.policy.routes)
        }
    }

    @Test
    fun chrome_presets_use_chrome_package() {
        assertEquals(AppRule.Only(listOf(CHROME_PACKAGE)), PolicyPreset.CHROME_ONLY.policy.apps)
        assertEquals(AppRule.Except(listOf(CHROME_PACKAGE)), PolicyPreset.EXCEPT_CHROME.policy.apps)
    }
}
