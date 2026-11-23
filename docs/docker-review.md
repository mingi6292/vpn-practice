# Docker 복습 노트

`docs/docker-guide.md`를 읽은 뒤 복습하는 용도다. 정답은 접힌 블록을 펼쳐서 확인한다.

---

## 1. 핵심 개념 한 줄 요약

| 개념 | 한 줄 요약 | Android 비유 |
|---|---|---|
| 컨테이너 | 호스트 커널을 공유하며 격리해 돌리는 프로세스 | 같은 기기에서 격리된 프로세스 |
| VM | 자기 커널까지 가진 가상 기기 | 에뮬레이터 |
| 이미지 | 실행 전 읽기 전용 스냅샷 | APK |
| 컨테이너 인스턴스 | 이미지 위에 쓰기 층을 얹어 실행한 것 | 실행 중인 앱 프로세스 |
| Dockerfile | 이미지를 만드는 명령 순서 | build.gradle (단, 순서가 중요) |
| 레이어 | Dockerfile 명령 하나가 만든 변경 묶음 | 빌드 캐시 단위 |
| 레지스트리 | 이미지를 올리고 받는 저장소 | Maven Central / Play |
| 볼륨 | 컨테이너 밖에 두는 영속 데이터 | 앱 내부 저장소 (단, 컨테이너 삭제 후에도 남음) |
| 포트 매핑 `-p` | 호스트 포트를 컨테이너 포트에 연결 | `adb forward` |
| WSL2 | Windows 안의 경량 Linux VM. Docker는 여기서 컨테이너를 돌림 | Windows에서 Linux 기기를 빌려 쓰는 것 |
| `--cap-add NET_ADMIN` | 네트워크 인터페이스/라우팅을 바꿀 권한 | root 전체 대신 권한 한 조각 |
| `--device` | 호스트 장치 파일을 컨테이너에 연결 | 기기 하드웨어 접근 권한 |

---

## 2. 셀프 퀴즈 (15문항)

### Q1. 컨테이너와 VM의 가장 큰 차이는 무엇인가?

<details>
<summary>정답 보기</summary>

컨테이너는 **호스트 커널을 공유**하고, VM은 게스트 OS마다 **자기 커널**을 가진다. 그래서 컨테이너는 가볍고 빠르다.

</details>

### Q2. 이미지와 컨테이너의 관계를 APK와 앱 프로세스에 비유하면? 어긋나는 점은?

<details>
<summary>정답 보기</summary>

이미지는 APK처럼 실행 전의 읽기 전용 결과물이고, 컨테이너는 그 이미지를 실행한 인스턴스다.

어긋나는 점: 컨테이너는 **쓰기 층**을 가지므로 `stop` 후에도 파일 변경이 남는다. 앱 프로세스는 죽으면 상태가 사라진다는 생각으로 접근하면 헷갈린다.

</details>

### Q3. Dockerfile을 build.gradle에 비유하면 어떤 점이 어긋나는가?

<details>
<summary>정답 보기</summary>

build.gradle은 의존성을 **선언**하고 Gradle이 결과를 해석한다. Dockerfile은 명령을 **위에서 아래로 실행**해 레이어를 쌓는다. 그래서 **순서가 결과와 캐시에 영향**을 준다.

</details>

### Q4. Dockerfile에서 `RUN apk add ...`와 `COPY server.jar ...` 중 어느 것을 위에 두는 것이 좋은가? 이유는?

<details>
<summary>정답 보기</summary>

`RUN apk add ...`를 **위에** 둔다. 패키지 목록은 자주 안 바뀌므로 그 레이어가 캐시로 재사용된다. `server.jar`는 자주 바뀌므로 **아래**에 두면 jar만 바뀔 때 앞쪽 레이어를 다시 만들지 않는다.

</details>

### Q5. `docker stop` 후 `docker start`하면 컨테이너 안에서 만든 파일은? `docker rm` 후 새 컨테이너에서는?

<details>
<summary>정답 보기</summary>

- `stop` → `start`: **남는다** (같은 컨테이너의 쓰기 층이 유지됨)
- `rm` 후 새 컨테이너: **없다** (쓰기 층이 삭제됨)

실습에서 `keep` 파일로 확인했다. 오래 보관할 데이터는 **볼륨**에 둔다.

</details>

### Q6. 볼륨은 어떤 문제를 해결하는가? 앱 내부 저장소와 무엇이 다른가?

<details>
<summary>정답 보기</summary>

컨테이너 수명과 데이터 수명을 **분리**한다. 컨테이너를 지우고 새로 만들어도 볼륨 데이터는 남는다. 앱 내부 저장소는 앱을 삭제하기 전까지 남는다는 점에서 비슷하지만, 볼륨은 **컨테이너 단위 삭제와 무관**하다는 점이 다르다.

</details>

### Q7. `-p 19999:9999/udp`에서 왼쪽 숫자와 오른쪽 숫자는 각각 무엇인가? `/udp`를 빼면?

<details>
<summary>정답 보기</summary>

- 왼쪽 `19999`: **호스트(PC)** 포트
- 오른쪽 `9999`: **컨테이너 내부** 포트
- `/udp`를 빼면 기본값인 **TCP**로 매핑된다. UDP 패킷은 전달되지 않는다.

실습에서 호스트의 `127.0.0.1:19999`로 보낸 UDP가 컨테이너 `socat`에서 9999로 받아져 echo 됐다.

</details>

### Q8. Windows에서 Linux 컨테이너가 도는 이유는?

<details>
<summary>정답 보기</summary>

컨테이너는 **Linux 커널**을 필요로 한다(네임스페이스, cgroup, `/dev/net/tun` 등). Windows 커널에는 이 기능이 없다. 그래서 Docker Desktop은 **WSL2의 Linux 커널** 위에 엔진을 띄우고 컨테이너를 그 안에서 돌린다.

실제로 `docker run --rm alpine uname -r`은 `...-WSL2` 커널 버전을 출력한다.

</details>

### Q9. 컨테이너 안에서 TUN 장치를 만들려면 어떤 두 가지가 필요한가?

<details>
<summary>정답 보기</summary>

1. `--cap-add NET_ADMIN`: 네트워크 인터페이스를 만들고 설정할 **권한**
2. `--device /dev/net/tun`: 커널 TUN 장치 파일을 컨테이너에 **연결**

둘 중 하나만 있으면 실패한다.

</details>

### Q10. `--device`는 있는데 `--cap-add NET_ADMIN`이 없으면 어떤 에러가 나오는가?

<details>
<summary>정답 보기</summary>

```
ioctl(TUNSETIFF): Operation not permitted
```

장치 파일은 열리지만, 커널이 인터페이스 생성을 **권한 없음**으로 거부한다.

</details>

### Q11. `--cap-add NET_ADMIN`은 있는데 `--device /dev/net/tun`이 없으면?

<details>
<summary>정답 보기</summary>

```
ls: /dev/net/tun: No such file or directory
```

컨테이너는 호스트 장치 파일을 **기본적으로 보지 못한다**. 명시적으로 넘겨줘야 한다.

</details>

### Q12. Git Bash에서 `--device /dev/net/tun`을 쓸 때 `MSYS_NO_PATHCONV=1`을 빼면 어떻게 되는가?

<details>
<summary>정답 보기</summary>

Git Bash가 `/dev/net/tun`을 **Windows 경로로 변환**한다. 그 결과 Docker가 장치 정보를 찾지 못해 다음과 같이 실패한다.

```
error gathering device information while adding custom device "C": no such file or directory
```

해결: 실행 전에 `export MSYS_NO_PATHCONV=1`.

</details>

### Q13. 에뮬레이터에서 호스트 PC의 서버에 접속할 때 쓰는 주소는? 이 프로젝트에서 왜 중요한가?

<details>
<summary>정답 보기</summary>

`10.0.2.2`. 에뮬레이터 안에서 이 주소는 **호스트 PC의 loopback**을 가리킨다. 그래서 PC의 `-p`로 열어 둔 포트에 에뮬레이터 앱이 UDP로 접속할 수 있다.

(미검증: 실제 에뮬레이터에서 접속해 보지는 않았다.)

</details>

### Q14. `docker compose up -d`와 `docker compose down`은 각각 무엇을 하는가?

<details>
<summary>정답 보기</summary>

- `up -d`: `compose.yaml`에 정의된 서비스(컨테이너)들을 **백그라운드로 생성하고 실행**한다. 없으면 기본 네트워크도 만든다.
- `down`: 그 서비스 컨테이너와 compose가 만든 기본 네트워크를 **정리(삭제)**한다.

실습에서 `docker-lab_default` 네트워크가 `down` 때 제거되는 것을 확인했다.

</details>

### Q15. `docker history alpine:latest`에서 확인할 수 있는 것은? 레이어가 중요한 이유는?

<details>
<summary>정답 보기</summary>

이미지가 어떤 명령으로 만들어졌는지와 **각 레이어의 크기**를 보여준다. `alpine:latest`에서는 실제 루트 파일시스템 레이어가 약 9MB이고, `CMD` 레이어는 0B다.

레이어가 중요한 이유: 여러 이미지가 레이어를 **공유**해 디스크를 아끼고, Dockerfile에서 **바뀌지 않은 앞쪽 레이어는 캐시**되어 빌드가 빨라진다.

</details>

---

## 3. 명령어 치트시트

Git Bash 기준. 먼저 실행:

```bash
export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"
export MSYS_NO_PATHCONV=1
```

### 실행과 확인

| 목적 | 명령 |
|---|---|
| 이미지로 실행 (끝나면 삭제) | `docker run --rm IMAGE CMD` |
| 백그라운드 실행 + 이름 | `docker run -d --name NAME IMAGE CMD` |
| 포트 매핑 (UDP) | `docker run -p 호스트:컨테이너/udp ...` |
| 권한 + 장치 (TUN) | `docker run --cap-add NET_ADMIN --device /dev/net/tun ...` |
| 실행 중 목록 / 전체 | `docker ps` / `docker ps -a` |
| 로그 (실시간) | `docker logs -f NAME` |
| 안에서 명령 실행 | `docker exec -it NAME sh` |

### 정리

| 목적 | 명령 |
|---|---|
| 정지 / 재시작 | `docker stop NAME` / `docker start NAME` |
| 컨테이너 삭제 (강제) | `docker rm -f NAME` |
| 이미지 목록 / 삭제 | `docker images` / `docker rmi IMAGE` |
| 이미지 레이어 확인 | `docker history IMAGE` |

### 빌드

| 목적 | 명령 |
|---|---|
| Dockerfile로 빌드 | `docker build -t NAME:TAG .` |
| 다른 Dockerfile 지정 | `docker build -f 경로 -t NAME:TAG .` |

### Compose

| 목적 | 명령 |
|---|---|
| 백그라운드로 올리기 | `docker compose up -d` |
| 상태 | `docker compose ps` |
| 로그 | `docker compose logs -f` |
| 내리기 (컨테이너 + 네트워크) | `docker compose down` |

### 이 프로젝트 기본 실행 패턴

```bash
docker run -d --rm --name tunnel \
  --cap-add NET_ADMIN --device /dev/net/tun \
  -p 9999:9999/udp \
  IMAGE_NAME
```

---

## 4. 이 프로젝트 터널 서버를 설명하는 30초 스크립트

> 면접이나 팀 설명에서 그대로 읽어도 되도록 짧게 쓴 예시다.

"Android 앱의 VpnService가 잡은 IP 패킷을 UDP로 감싸서 호스트 PC로 보내면, PC에서 Docker로 띄운 터널 서버가 받아 Linux TUN 장치에 씁니다. 컨테이너는 호스트 커널을 공유하는 격리 프로세스라서, Windows에서는 WSL2의 Linux 커널 위에서 돌아갑니다. TUN은 커널 장치라서 컨테이너에 `--device /dev/net/tun`으로 장치를 연결하고, `--cap-add NET_ADMIN`으로 인터페이스를 만들 권한을 줍니다. 두 옵션 중 하나만 있으면 실패하는 것을 실제로 확인했습니다. 호스트 포트는 `-p`로 컨테이너 UDP 포트에 연결합니다. 에뮬레이터에서는 `10.0.2.2`로 호스트에 접근합니다."

> 30초에 맞추려면 마지막 두 문장을 빼도 된다.
