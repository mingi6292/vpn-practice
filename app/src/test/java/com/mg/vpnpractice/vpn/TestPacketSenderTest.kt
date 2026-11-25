package com.mg.vpnpractice.vpn

import org.junit.Assert.assertNotNull
import org.junit.Test

class TestPacketSenderTest {

    @Test
    fun send_failure_is_returned_instead_of_thrown() {
        // .invalid는 절대 해석되지 않는 예약 도메인이라 전송이 반드시 실패한다.
        val error = TestPacketSender.send(host = "unreachable.invalid")

        assertNotNull(error)
    }
}
