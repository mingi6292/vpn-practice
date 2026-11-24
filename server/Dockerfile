# 빌드와 실행 (저장소 루트에서, Git Bash는 MSYS_NO_PATHCONV=1 필요):
#   ./gradlew :server:installDist && docker build -t vpn-practice-server server
#   docker run -d --rm --name tunnel-server --cap-add NET_ADMIN --device /dev/net/tun -p 5555:5555/udp vpn-practice-server
# Alpine(musl)이 아니라 Debian/Ubuntu 계열을 쓴다. JNA가 포함한 네이티브 라이브러리가 glibc용이다.
FROM eclipse-temurin:17-jre

# TUN에 주소를 붙이는 ip 명령. 잘 안 바뀌는 레이어라 위에 둔다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*

# 자주 바뀌는 애플리케이션 파일은 아래에 둔다 (레이어 캐시).
COPY build/install/server /app

ENTRYPOINT ["/app/bin/server"]
