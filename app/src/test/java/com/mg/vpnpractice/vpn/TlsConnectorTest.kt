package com.mg.vpnpractice.vpn

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TlsConnectorTest {
    // 단위 테스트는 app 모듈 폴더에서 돌아서 res/raw의 인증서를 파일로 바로 읽을 수 있다.
    private val caFile = File("src/main/res/raw/practice_ca.pem")

    @Test
    fun trust_manager_accepts_only_the_practice_ca() {
        val trustManager = caFile.inputStream().use { TlsConnector.trustManagerFor(it) }

        val issuers = trustManager.acceptedIssuers
        assertEquals(1, issuers.size)
        assertTrue(issuers[0].subjectX500Principal.name.contains("CN=vpn-practice CA"))
    }

    @Test
    fun connect_to_closed_port_throws_io_exception() {
        val trustManager = caFile.inputStream().use { TlsConnector.trustManagerFor(it) }
        // 빈 포트를 하나 얻은 뒤 닫아서, 아무도 듣지 않는 로컬 포트를 만든다.
        val loopback = InetAddress.getLoopbackAddress()
        val port = ServerSocket(0, 50, loopback).use { it.localPort }

        val started = System.nanoTime()
        try {
            TlsConnector.connect(trustManager, loopback.hostAddress!!, port)
            fail("connect should fail")
        } catch (_: IOException) {
            // 기대한 결과
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 6000)
    }
}
