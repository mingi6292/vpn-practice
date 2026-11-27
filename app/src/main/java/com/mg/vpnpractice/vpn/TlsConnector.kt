package com.mg.vpnpractice.vpn

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 터널 서버와 TCP 연결을 맺고 그 위에 TLS를 얹는다. 서버만 검증하고 사용자 인증은 없다(8단계).
 * Android 의존성이 없다(Log 금지). JVM 단위 테스트 대상이다. 네트워크 I/O라 IO 스레드에서 부른다.
 */
object TlsConnector {
    // 에뮬레이터에서 10.0.2.2는 호스트 PC를 가리킨다.
    const val SERVER_HOST = "10.0.2.2"
    const val SERVER_PORT = 5555
    const val CONNECT_TIMEOUT_MS = 5000
    const val HANDSHAKE_TIMEOUT_MS = 5000

    /**
     * CA 인증서 하나만 담은 KeyStore로 TrustManager를 만든다. 시스템 CA는 믿지 않는다.
     * 연습용 서버 인증서는 우리 CA가 서명했으니 시스템 CA는 필요 없다. 시스템 CA까지 믿으면
     * 공인 CA에서 10.0.2.2용 인증서를 받은 누구든 서버 행세를 할 수 있다.
     */
    fun trustManagerFor(caCert: InputStream): X509TrustManager {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(caCert)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null) // 빈 KeyStore로 시작한다
            setCertificateEntry("practice-ca", cert)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** TCP 연결 -> TLS 핸드셰이크 -> 호스트 이름 검증까지 마친 소켓. 하나라도 실패하면 소켓을 닫고 IOException을 던진다. */
    fun connect(trustManager: X509TrustManager, host: String, port: Int): SSLSocket {
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

        // 일부러 protect()를 호출하지 않는다. 서버 주소(10.0.2.2)가 TUN 라우트(10.0.0.0/24) 밖이라
        // 이 소켓의 패킷은 TUN으로 되돌아오지 않는다. 라우트를 넓히거나 서버가 대역 안에 있으면
        // 아래 connect() 전에 protect(plain)가 필요하다(7단계).
        val plain = Socket()
        var tls: SSLSocket? = null
        try {
            // Nagle 알고리즘이 안쪽 TCP의 작은 패킷(ACK 등)을 모아 보내느라 지연이 생기는 것을 막는다.
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

            // 이미 연결된 TCP 소켓 위에 TLS를 얹는다. true는 TLS 소켓을 닫을 때 plain도 함께 닫으라는 뜻이다.
            tls = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            // 서버가 응답하지 않으면 핸드셰이크가 영원히 기다리지 않게 읽기 타임아웃을 둔다.
            tls.soTimeout = HANDSHAKE_TIMEOUT_MS
            // 핸드셰이크를 명시적으로 한다. 첫 read/write에서 암묵적으로 일어나게 두면
            // 인증서 오류가 패킷 전송 실패처럼 보인다.
            tls.startHandshake()

            // SSLSocket은 인증서 체인만 검증하고 호스트 이름은 검증하지 않는다. 그래서 직접 한다.
            // 이걸 빼면 같은 CA가 서명한 다른 서버의 인증서도 믿게 된다. verify()는 예외 대신 false를 돌려준다.
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                throw SSLPeerUnverifiedException("hostname $host does not match server certificate")
            }

            // 터널은 오래 조용할 수 있다. 읽기 타임아웃을 남기면 조용할 때마다 끊기므로 0(무한)으로 되돌린다.
            tls.soTimeout = 0
            return tls
        } catch (e: IOException) {
            tls?.close()
            plain.close()
            throw e
        }
    }
}
