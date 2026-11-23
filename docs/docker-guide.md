# Docker 학습 가이드 (Android 개발자용)

이 문서는 Docker를 전혀 모르는 Android 개발자가 이 프로젝트의 터널 서버를 이해하고 직접 띄워 볼 수 있도록 쓴 학습 문서다.
이 프로젝트에서 Docker는 **"Linux 서버 환경"**으로만 쓴다. 터널 서버(Kotlin/JVM + JNA)가 Linux TUN 장치를 만지기 때문에 Linux 커널이 필요하고, Windows에서 그 Linux 환경을 가장 쉽게 얻는 방법이 Docker다.

> 표기 규칙: 본문에 있는 실행 결과는 이 PC(Windows 11 Home, Docker 29.9.3, Docker Desktop WSL2 백엔드)에서 실제로 실행해서 확인한 것이다. 실행해 보지 않은 내용은 **미검증**으로 표시했다.

---

## 1. Docker가 뭐고 왜 쓰는가

Docker는 **프로그램과 그 실행 환경을 하나의 묶음(이미지)으로 만들고, 그 묶음을 격리된 상태로 실행(컨테이너)하는 도구**다.

### VM과의 차이

| 구분 | VM (가상머신) | 컨테이너 |
|---|---|---|
| 커널 | 게스트 OS마다 자기 커널이 있다 | **호스트 커널을 공유**한다 |
| 무게 | OS 전체를 부팅한다 (수 GB, 수십 초) | 프로세스를 띄우는 수준 (수십 MB, 거의 즉시) |
| 격리 | 하드웨어 수준 | 프로세스 수준 (네임스페이스, cgroup) |
| 예시 | Android 에뮬레이터 (ARM/x86 커널 자체를 흉내 냄) | 서버 프로세스 하나 |

핵심은 **컨테이너는 커널을 공유한다**는 점이다. 그래서 컨테이너 안에서 `uname -r`을 치면 호스트 커널 버전이 나온다.

실제로 이 PC에서 확인:

```bash
docker run --rm alpine uname -r
# 6.18.58.1-microsoft-standard-WSL2
```

컨테이너가 Windows 커널이 아니라 **Linux 커널(WSL2)**을 쓴다는 뜻이다. 이 점이 3장의 핵심이다.

> Android 비유: 에뮬레이터는 기기 하나를 통째로 흉내 내는 VM이고, 컨테이너는 같은 기기 안에서 `ProcessBuilder`로 띄운 프로세스에 가깝다. 단, 프로세스보다 파일시스템과 네트워크를 더 강하게 분리한다.

---

## 2. 핵심 개념

Android 개발자에게 익숙한 것과 연결해서 정리했다. **"어긋나는 지점"**도 함께 적었다.

### 이미지 (Image)
실행하기 전의 **읽기 전용 스냅샷**. 파일시스템 + 실행 명령 정의를 담는다.
- 비유: **APK**. 빌드 결과물이고, 설치(실행) 전에는 그냥 파일이다.
- 어긋나는 지점: APK는 기기에 설치해도 APK 파일 자체가 남지만, 이미지는 실행 시 복사되는 게 아니라 **레이어를 공유**하며 실행된다.

### 컨테이너 (Container)
이미지를 실행한 **인스턴스**. 이미지 위에 쓰기 가능한 얇은 층이 하나 더 올라간다.
- 비유: **실행 중인 앱 프로세스**. 같은 APK로 프로세스를 여러 개 띄울 수 있는 것과 같다.
- 어긋나는 지점: 앱 프로세스는 죽으면 메모리만 사라지지만, 컨테이너는 `stop` 후에도 **파일 변경 내용이 남아 있다**(10장 실습 참고). 완전히 지우려면 `rm`이 필요하다.

### Dockerfile
이미지를 만드는 **레시피 파일**. 위에서 아래로 명령을 실행해 레이어를 쌓는다.

```dockerfile
FROM alpine:latest                 # 베이스 이미지 (출발점)
RUN apk add --no-cache iproute2    # 패키지 설치 → 레이어 하나
COPY server.jar /app/server.jar    # 파일 복사 → 레이어 하나
CMD ["java", "-jar", "/app/server.jar"]  # 컨테이너 시작 시 실행할 명령
```

- 비유: **build.gradle** (빌드 설정)
- 어긋나는 지점: build.gradle은 의존성을 *선언*하고 Gradle이 해석하지만, Dockerfile은 명령을 **순서대로 실행**한다. 순서가 결과를 바꾼다.

### 레이어 (Layer)
Dockerfile의 명령 한 줄(정확히는 파일시스템을 바꾸는 명령)이 만드는 변경 묶음. 이미지는 레이어를 쌓은 것이다.

실제 `alpine:latest`의 레이어 확인:

```bash
docker history alpine:latest
# SIZE     CREATED BY
# 0B       CMD ["/bin/sh"]
# 9.09MB   ADD alpine-minirootfs-... /   ← 실제 루트 파일시스템 레이어
```

- 장점: 같은 레이어를 여러 이미지가 **공유**하고, Dockerfile에서 바뀌지 않은 앞쪽 레이어는 **캐시**된다.
- 그래서 자주 바뀌는 `COPY server.jar`는 맨 아래에 두고, 잘 안 바뀌는 `RUN apk add ...`는 위에 둔다.
- 비유: Gradle의 **incremental build / build cache**와 같은 생각이다. 입력이 같으면 이전 결과를 재사용한다.

### 레지스트리 (Registry)
이미지를 올리고 내려받는 **저장소**. 기본은 Docker Hub다. (`alpine`, `hello-world`가 여기서 받아진다.)
- 비유: **Maven Central / Google Play**
- 어긋나는 지점: Play는 앱 사용자 대상의 스토어이고, Docker Hub는 개발자가 기반 이미지를 받는 곳에 더 가깝다. 자체 레지스트리를 둘 수도 있다.
- 미검증: 이미지 `push`(업로드) 실습은 하지 않았다.

### 볼륨 (Volume)
컨테이너 **밖에** 있으면서 데이터를 보관하는 저장소. 컨테이너를 지워도 남는다.
- 비유: **앱 내부 저장소**(`filesDir`)에 가깝지만, 앱 삭제(uninstall) 전까지 남는 것과 달리 볼륨은 **컨테이너를 지워도 남는다**. 어긋나는 지점이다.
- 미검증: 이름 있는 볼륨(`docker volume create`)과 `-v`로 마운트하는 실습은 하지 않았다.

### 포트 매핑 (Port mapping)
호스트(PC)의 포트를 컨테이너 내부 포트로 연결한다.

```bash
docker run -p 19999:9999/udp ...
#          ^호스트  ^컨테이너
```

- 비유: `adb forward tcp:19999 tcp:9999`와 같은 개념
- 컨테이너 안에서 9999 포트를 열어도 **호스트에서는 기본적으로 안 보인다**. 이 연결을 만들어 주는 것이 `-p`다.
- 프로토콜을 붙여야 한다. TCP가 기본이므로 UDP는 `/udp`를 명시한다.

### 네트워크 (Network)
컨테이너끼리 또는 컨테이너와 외부가 통신하는 경로. 기본은 `bridge` 네트워크다.
- 컨테이너는 자기만의 네트워크 네임스페이스(IP, 라우팅 테이블, 포트)를 가진다. 그래서 TUN 장치를 만들어도 **컨테이너 안에만** 생긴다.
- 미검증: `docker network create`로 사용자 정의 네트워크를 만드는 실습은 하지 않았다.

---

## 3. Windows에서의 구조

### 전체 그림

```
┌──────────────────── Windows 11 (호스트) ────────────────────┐
│  docker.exe (CLI)  ──→  Docker Desktop (서비스)            │
│                              │                             │
│                              ▼                             │
│  ┌────────── WSL2 경량 Linux VM (Linux 커널) ──────────┐    │
│  │  dockerd (Docker 엔진)                              │    │
│  │   ├─ 컨테이너 A (alpine, iproute2 설치)              │    │
│  │   └─ 컨테이너 B (터널 서버)                          │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

- **Docker Desktop**: Windows에 설치하는 제품 묶음. CLI, GUI, 엔진 실행을 관리한다.
- **WSL2 (Windows Subsystem for Linux 2)**: Windows 안에서 돌아가는 **경량 Linux VM**. Microsoft가 제공한다.
- **Docker 엔진(dockerd)**: 이 Linux VM 안에서 돌고, 실제 컨테이너를 만든다.

### 왜 컨테이너는 Linux 커널 위에서 도는가

1. 컨테이너는 **커널을 공유**한다 (1장).
2. Windows 커널에서는 Linux 네임스페이스/cgroup/`/dev/net/tun`을 쓸 수 없다.
3. 그래서 Docker Desktop은 WSL2 안의 Linux 커널을 쓰고, 컨테이너는 그 위에서 돈다.

실제로 확인한 사실:

```bash
docker run --rm alpine uname -r
# 6.18.58.1-microsoft-standard-WSL2
```

위 결과는 이 PC의 호스트 커널이 아니라 **WSL2 Linux 커널**이다.

> Android 비유: Windows에서 Android 앱을 돌리려고 에뮬레이터(VM)를 띄우는 것과 비슷하다. 다만 Docker는 "기기 전체"가 아니라 "Linux 커널 하나"만 빌려 쓰고, 그 위에서 여러 프로세스를 격리해 돌린다.

---

## 4. 자주 쓰는 명령어

Git Bash 기준으로 적었다. 먼저 PATH를 잡아야 `docker`가 인식된다 (6장 참고).

```bash
export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"
export MSYS_NO_PATHCONV=1
```

| 명령 | 의미 | 주요 옵션 | 이 문서에서 확인 |
|---|---|---|---|
| `docker run IMAGE CMD` | 이미지로 새 컨테이너를 만들고 실행 | `-d` 백그라운드, `--rm` 종료 시 자동 삭제, `--name` 이름 지정, `-p 호스트:컨테이너/프로토콜` 포트 매핑, `-e KEY=VAL` 환경변수, `-v 볼륨:경로` 마운트, `--cap-add` 권한 추가, `--device` 장치 연결, `-it` 대화형 터미널 | ✅ |
| `docker ps` | 실행 중인 컨테이너 목록 | `-a` 종료된 것 포함 | ✅ |
| `docker logs NAME` | 컨테이너 표준 출력/에러 보기 | `-f` 실시간 따라가기 | ✅ |
| `docker exec NAME CMD` | 실행 중인 컨테이너 안에서 명령 실행 | `-it` 셸 접속 (`docker exec -it NAME sh`) | ✅ |
| `docker stop NAME` | 컨테이너 정지 (데이터는 남김) | | ✅ |
| `docker start NAME` | 정지된 컨테이너 다시 실행 | | ✅ |
| `docker rm NAME` | 정지된 컨테이너 삭제 (안의 쓰기 데이터 소실) | `-f` 실행 중이어도 강제 삭제 | ✅ |
| `docker build -t NAME:TAG .` | Dockerfile로 이미지 빌드 | `-t` 이름과 태그, `-f` Dockerfile 경로 지정 | ✅ |
| `docker images` | 로컬 이미지 목록 | | ✅ |
| `docker rmi IMAGE` | 로컬 이미지 삭제 | | ✅ |
| `docker history IMAGE` | 이미지의 레이어 목록 | | ✅ |
| `docker pull IMAGE` | 레지스트리에서 이미지만 받기 | | 미검증 (run 시 자동 pull은 확인) |
| `docker compose up -d` | `compose.yaml`의 서비스들을 백그라운드로 실행 | | ✅ |
| `docker compose ps` | compose 서비스 상태 | | ✅ |
| `docker compose down` | compose 서비스와 기본 네트워크 정리 | | ✅ |
| `docker compose logs -f` | compose 서비스 로그 | | 미검증 |

> `docker compose`는 Compose v2 이상에서 `docker` 명령의 하위 명령으로 제공된다. 이 PC에서는 v5.7.1이 설치돼 있다.

---

## 5. 이 프로젝트에서 Docker가 하는 일

### 터널 서버 구조

```
[Android 에뮬레이터 / 기기]                  [Windows PC]                         [Docker 컨테이너 (Linux)]

 VpnService 가 잡은 IP 패킷
   │ (TUN fd에서 읽음)
   ▼
 UDP로 캡슐화 ─── UDP 패킷 ──▶ 10.0.2.2:PORT ──▶ 호스트 포트 PORT ──▶ 컨테이너:PORT
                                 (에뮬레이터→호스트)  (-p 매핑)           │
                                                                          ▼
                                                              터널 서버 (JVM + JNA)
                                                                          │ Linux TUN(tun0)에 write
                                                                          ▼
                                                              컨테이너 커널 라우팅 → 외부 네트워크
```

- **에뮬레이터 → 호스트**: 에뮬레이터 안에서 `10.0.2.2`는 호스트 PC의 loopback을 가리킨다. (미검증: 실제 에뮬레이터에서 접속해 보지 않았다.)
- **호스트 → 컨테이너**: `-p`로 연결한다. 4장의 `-p 19999:9999/udp`가 이 역할이다.
- **컨테이너 → 커널**: 서버가 Linux TUN 장치에 패킷을 쓴다. 이 부분이 권한 문제를 일으킨다.

### 왜 `--cap-add NET_ADMIN`이 필요한가

- TUN은 **커널이 제공하는 가상 네트워크 장치**다. `ip tuntap add`는 커널에게 "새 TUN 장치를 만들어 달라"고 요청하는 것이다.
- 커널은 이런 **네트워크 설정 변경을 특권 작업**으로 본다. 기본 컨테이너는 이 권한이 없다.
- `NET_ADMIN`은 Linux **capability**(권한 조각) 중 "네트워크 인터페이스와 라우팅을 바꿀 권한"이다. root 전체 권한 대신 이것만 준다.

실제 실험 결과:

```bash
# NET_ADMIN 없이 (device는 있음)
docker run --rm --device /dev/net/tun alpine sh -c 'apk add -q iproute2 && ip tuntap add dev tun0 mode tun'
# ioctl(TUNSETIFF): Operation not permitted
```

### 왜 `--device /dev/net/tun`이 필요한가

- TUN을 제어하려면 `/dev/net/tun` 파일을 열어야 한다. 이것이 커널 장치의 입구다.
- 컨테이너는 기본적으로 **호스트의 장치 파일을 보지 못한다**. `--device`로 명시적으로 넘겨준다.

실제 실험 결과:

```bash
# device 없이 (NET_ADMIN은 있음)
docker run --rm --cap-add NET_ADMIN alpine ls /dev/net/tun
# ls: /dev/net/tun: No such file or directory
```

두 옵션은 **둘 다 필요**하다. 하나만 있으면 위와 같이 실패한다.

### 왜 `-p`로 UDP 포트를 매핑하는가

- 터널 서버는 에뮬레이터의 패킷을 **UDP**로 받는다.
- 컨테이너 안의 서버가 9999에서 듣고 있어도, 호스트에서 `-p 9999:9999/udp`로 연결해야 밖에서 도달할 수 있다.
- 기본 프로토콜은 TCP이므로 `/udp`를 빠뜨리면 UDP가 전달되지 않는다. (미검증: `/udp` 없이 실행했을 때의 동작은 확인하지 않았다.)

---

## 6. 흔한 함정

### 함정 1: Git Bash 경로 변환 (`MSYS_NO_PATHCONV`)

Git Bash는 `/dev/net/tun` 같은 **POSIX 경로를 Windows 경로로 자동 변환**한다. 그 결과 Docker가 엉뚱한 경로를 받는다.

실제 재현:

```bash
env -u MSYS_NO_PATHCONV docker run --rm --cap-add NET_ADMIN --device /dev/net/tun alpine ls -l /dev/net/tun
# docker: Error response from daemon: error gathering device information while adding custom device "C": no such file or directory
```

해결: 실행 전에 `export MSYS_NO_PATHCONV=1`을 해 둔다.

### 함정 2: `docker`가 PATH에 없음

Docker Desktop 설치 후에도 Git Bash 세션에서는 PATH가 적용되지 않을 수 있다. 실행 파일 위치는 다음과 같다.

```
C:\Program Files\Docker\Docker\resources\bin
```

해결:

```bash
export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"
```

이 설정은 **현재 셸에만** 적용된다. 영구 적용하려면 Windows 환경 변수 `Path`에 추가해야 한다. (미검증: Windows 환경 변수 추가 절차는 따로 확인하지 않았다.)

### 함정 3: 컨테이너를 지우면 데이터도 사라진다

컨테이너 안에서 만든 파일은 그 컨테이너의 쓰기 층에 있다.

실제 확인:

```bash
docker run -d --name vol-test alpine sleep 300
docker exec vol-test sh -c 'echo keep > /data.txt'
docker stop vol-test && docker start vol-test
docker exec vol-test cat /data.txt        # keep  ← stop/start는 살아남음
docker rm -f vol-test
docker run --rm alpine cat /data.txt      # No such file  ← rm 하면 사라짐
```

정리하면:
- `stop` / `start`: 데이터 유지
- `rm`: 데이터 소실
- 오래 보관할 데이터는 **볼륨**에 둔다 (2장 참고, 볼륨 실습은 미검증)

### 함정 4: 방화벽

Windows 방화벽이 Docker로 들어오는 UDP를 막을 수 있다. (미검증: 이 PC에서 방화벽을 켠 상태로 에뮬레이터에서 접속해 보지 않았다.) 에뮬레이터 연결이 안 되면 방화벽 규칙부터 확인한다.

---

## 7. 직접 해보는 실습

모든 실습은 실제로 실행해서 결과를 확인했다. 각 실습 뒤에 정리 명령이 있다.

### 실습 1: hello-world

```bash
export MSYS_NO_PATHCONV=1
docker run --rm hello-world
```

기대 출력 (앞부분):

```
Hello from Docker!
This message shows that your installation appears to be working correctly.
```

- `--rm`: 실행이 끝나면 컨테이너를 자동 삭제한다.
- 이미지가 없으면 Docker Hub에서 자동으로 받는다.

### 실습 2: alpine에서 TUN 장치 만들기

```bash
export MSYS_NO_PATHCONV=1
docker run --rm --cap-add NET_ADMIN --device /dev/net/tun alpine sh -c \
  'apk add -q iproute2 && ip tuntap add dev tun0 mode tun && ip -br link show tun0 && ip tuntap del dev tun0 mode tun && echo DONE'
```

기대 출력:

```
tun0             DOWN           <POINTOPOINT,MULTICAST,NOARP>
DONE
```

- busybox의 `ip`는 `tuntap`을 지원하지 않으므로 `apk add iproute2`가 필요하다.
- `--rm`으로 정리되므로 별도 삭제가 필요 없다.

권한을 하나씩 빼 보면 원인을 확인할 수 있다.

```bash
# NET_ADMIN 없이
docker run --rm --device /dev/net/tun alpine sh -c 'apk add -q iproute2 && ip tuntap add dev tun0 mode tun'
# ioctl(TUNSETIFF): Operation not permitted

# device 없이
docker run --rm --cap-add NET_ADMIN alpine ls /dev/net/tun
# ls: /dev/net/tun: No such file or directory
```

### 실습 3: 컨테이너에서 UDP echo 서버 띄우고 호스트에서 보내기

컨테이너에 UDP 서버를 띄우고, 호스트(PC)에서 패킷을 보내 돌아오는지 확인한다. 터널 서버 실습의 축소판이다.

1) 컨테이너 실행 (백그라운드, 19999 → 9999 매핑):

```bash
export MSYS_NO_PATHCONV=1
docker run -d --rm --name udp-echo -p 19999:9999/udp alpine sh -c 'apk add -q socat && socat -v UDP-LISTEN:9999,fork PIPE'
```

`socat` 설치에 몇 초 걸린다. 약 8초 기다린 뒤 다음 단계로 간다.

2) 호스트에서 UDP 패킷 보내고 응답 받기 (Windows PowerShell):

```powershell
$c = New-Object System.Net.Sockets.UdpClient
$c.Client.ReceiveTimeout = 3000
$b = [Text.Encoding]::UTF8.GetBytes("hello-udp")
[void]$c.Send($b, $b.Length, "127.0.0.1", 19999)
$ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
$r = $c.Receive([ref]$ep)
"echo=" + [Text.Encoding]::UTF8.GetString($r)
$c.Close()
```

기대 출력:

```
echo=hello-udp
```

3) 컨테이너 로그에서 패킷이 실제로 들어왔는지 확인:

```bash
docker logs udp-echo
# > ... length=9 from=0 to=8
# hello-udp< ... length=9 from=0 to=8
```

4) 정리:

```bash
docker rm -f udp-echo
```

- `>`는 받은 데이터, `<`는 보낸 데이터다(`socat -v` 출력 형식).
- 이 실습은 PowerShell로 보냈다. Git Bash에서 `echo -n hello | nc -u 127.0.0.1 19999` 같은 방식도 가능할 것으로 보이나 **미검증**이다.
- 에뮬레이터에서 `10.0.2.2:19999`로 보내는 실습은 **미검증**이다.

### 실습 4: Dockerfile로 이미지 만들고 compose로 띄우기

1) 작업 폴더에 `Dockerfile` 작성:

```dockerfile
FROM alpine:latest
RUN apk add --no-cache iproute2
CMD ["ip", "-V"]
```

2) 빌드하고 실행:

```bash
export MSYS_NO_PATHCONV=1
docker build -t vpn-lab:test .
docker run --rm vpn-lab:test
# ip utility, iproute2-v7.0.0
```

3) 같은 폴더에 `compose.yaml` 작성 후 실행:

```yaml
services:
  lab:
    image: alpine:latest
    command: sh -c "echo compose-up && sleep 60"
```

```bash
docker compose up -d
docker compose ps --format '{{.Name}} {{.State}}'
# docker-lab-lab-1 running
docker compose down
```

4) 정리:

```bash
docker rmi vpn-lab:test
```

- `docker build`는 Dockerfile의 명령을 순서대로 실행해 레이어를 쌓는다 (2장).
- `compose.yaml`은 여러 컨테이너 설정을 파일 하나로 관리한다. `up -d`로 띄우고 `down`으로 내린다.

---

## 부록: 이 가이드의 검증 범위

| 항목 | 상태 |
|---|---|
| Docker 29.9.3 동작, WSL2 커널 확인 | 검증됨 |
| hello-world 실행 | 검증됨 |
| NET_ADMIN + device로 tun0 생성 | 검증됨 |
| NET_ADMIN 없음 / device 없음 / MSYS 변환 실패 재현 | 검증됨 |
| UDP 포트 매핑 (호스트 → 컨테이너 echo) | 검증됨 |
| 컨테이너 데이터 stop/start 유지, rm 소실 | 검증됨 |
| Dockerfile build, compose up/down | 검증됨 |
| 에뮬레이터에서 `10.0.2.2` 접속 | 미검증 |
| 볼륨(`-v`, `docker volume`) 실습 | 미검증 |
| 사용자 정의 네트워크 | 미검증 |
| 이미지 push, `docker pull` 단독 | 미검증 |
| Windows 방화벽 영향 | 미검증 |
| PowerShell에서 PATH 설정 | 미검증 |
