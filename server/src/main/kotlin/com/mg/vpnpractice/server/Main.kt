package com.mg.vpnpractice.server

import java.io.File
import kotlinx.coroutines.runBlocking

private const val TUN_NAME = "tun0"
private const val TUN_ADDRESS = "10.0.0.1/24"
private const val TUNNEL_PORT = 5555

// docker run -v .../certs/server:/certs:ro 로 마운트한다. 키스토어와 비밀번호는 이미지에 넣지 않는다.
private const val CERT_DIR = "/certs"
private const val KEY_STORE_FILE = "server.p12"
private const val PASSWORD_FILE = "server.p12.pass"

fun main() {
    val keyStoreFile = File(CERT_DIR, KEY_STORE_FILE)
    val passwordFile = File(CERT_DIR, PASSWORD_FILE)
    check(keyStoreFile.isFile && passwordFile.isFile) {
        "$keyStoreFile 또는 $passwordFile 이 없다. scripts/make-certs.sh를 실행하고 certs/server를 $CERT_DIR 에 마운트한다"
    }

    val tun = LinuxTun.open(TUN_NAME)
    // TUN을 연 직후에는 주소가 없고 down 상태다. 주소를 붙이고 올려야 커널이 이 대역으로 패킷을 라우팅한다.
    runIp("addr", "add", TUN_ADDRESS, "dev", TUN_NAME)
    runIp("link", "set", TUN_NAME, "up")

    val serverSocket = createTlsServerSocket(TUNNEL_PORT, keyStoreFile, passwordFile)
    val server = TunnelServer(tun, serverSocket, ::tlsHandshake)
    // docker stop은 SIGTERM을 보낸다. 종료 훅에서 소켓과 TUN을 닫아야 블로킹 중인 루프가 끝난다.
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })

    println("$TUN_NAME $TUN_ADDRESS up, tcp $TUNNEL_PORT (TLS) listening")
    runBlocking { server.run() }
    println("server stopped")
}

private fun runIp(vararg args: String) {
    val process = ProcessBuilder(listOf("ip") + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "ip ${args.joinToString(" ")} failed ($exitCode): $output" }
}
