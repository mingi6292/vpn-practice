# 빌드와 실행 (저장소 루트에서, Git Bash는 MSYS_NO_PATHCONV=1 필요):
#   ./gradlew :server:installDist && docker build -t vpn-practice-server server
#   docker run -d --rm --name tunnel-server --cap-add NET_ADMIN --device /dev/net/tun -p 127.0.0.1:5555:5555 -v "$(pwd)/certs/server:/certs:ro" vpn-practice-server
# 터널이 TCP+TLS라서 포트 매핑에 /udp를 붙이지 않는다(기본이 TCP).
# certs/server(서버 키스토어와 비밀번호 파일)만 읽기 전용으로 마운트한다. 키는 이미지에 넣지 않는다. 먼저 scripts/make-certs.sh를 돌린다.
# 포트는 계속 127.0.0.1에만 연다. TLS는 도청을 막을 뿐 누가 접속하는지는 막지 않아서(클라이언트 인증 없음),
# 모든 인터페이스에 열면 같은 LAN의 누구나 접속해 패킷을 넣을 수 있다.
# 에뮬레이터의 10.0.2.2는 PC의 loopback이라 이대로 접속된다. 실기기를 붙일 때는 다시 정해야 한다.
# Alpine(musl)이 아니라 Debian/Ubuntu 계열을 쓴다. JNA가 포함한 네이티브 라이브러리가 glibc용이다.
FROM eclipse-temurin:17-jre

# TUN에 주소를 붙이는 ip 명령. 잘 안 바뀌는 레이어라 위에 둔다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*

# 자주 바뀌는 애플리케이션 파일은 아래에 둔다 (레이어 캐시).
COPY build/install/server /app

ENTRYPOINT ["/app/bin/server"]
