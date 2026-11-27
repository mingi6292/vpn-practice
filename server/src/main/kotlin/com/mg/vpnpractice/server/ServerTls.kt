package com.mg.vpnpractice.server

import java.io.File
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * 마운트된 PKCS12 키스토어(서버 키 + 인증서 체인)로 TLS 서버 소켓을 연다.
 * 비밀번호는 코드나 Dockerfile에 두지 않고 파일에서 읽는다. 코드에 있으면 git과 이미지에 그대로 남는다.
 *
 * TLS는 도청과 변조만 막고, 누가 접속하는지는 막지 않는다(클라이언트 인증이 없다).
 * 그래서 포트는 계속 127.0.0.1에만 연다.
 */
fun createTlsServerSocket(port: Int, keyStoreFile: File, passwordFile: File): SSLServerSocket {
    val password = passwordFile.readText().trim().toCharArray()
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStoreFile.inputStream().use { keyStore.load(it, password) }

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    keyManagerFactory.init(keyStore, password)

    // 서버는 클라이언트 인증서를 받지 않으므로 TrustManager가 필요 없다(null).
    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.keyManagers, null, null)

    val serverSocket = context.serverSocketFactory.createServerSocket(port) as SSLServerSocket
    // 1.2도 켜 두는 이유: 앱의 minSdk가 24이고, API 24~28은 TLS 1.3이 기본으로 켜져 있지 않다.
    // 1.3만 켜면 그 기기들은 접속하지 못한다. 그보다 낮은 버전은 취약해서 끈다.
    serverSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
    return serverSocket
}

/**
 * 핸드셰이크를 명시적으로 끝낸다. 부르지 않으면 첫 read/write 때 암묵적으로 일어나서,
 * 인증서 오류가 패킷 읽기 오류처럼 보이고 어디서 실패했는지 알기 어렵다.
 * 멈춘 클라이언트가 연결 코루틴을 영영 붙잡지 않게 핸드셰이크 동안만 읽기 타임아웃을 건다.
 */
fun tlsHandshake(socket: Socket, timeoutMillis: Int = 5000) {
    val sslSocket = socket as SSLSocket
    sslSocket.soTimeout = timeoutMillis
    sslSocket.startHandshake()
    // 터널은 패킷이 없으면 한참 조용할 수 있다. 핸드셰이크 뒤에는 타임아웃을 없앤다.
    sslSocket.soTimeout = 0
    println("tls ${sslSocket.session.protocol} ${sslSocket.session.cipherSuite} from ${socket.remoteSocketAddress}")
}
