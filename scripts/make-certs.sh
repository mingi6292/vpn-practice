#!/usr/bin/env bash
# 연습용 CA와 서버 인증서(PKCS12)를 만든다. 저장소 루트에서 실행한다: bash scripts/make-certs.sh
set -euo pipefail

# Git Bash가 -subj "/CN=..."을 Windows 경로로 바꿔 버리는 것을 막는다.
export MSYS_NO_PATHCONV=1

CERTS=certs
# MSYS_NO_PATHCONV 때문에 /tmp 같은 MSYS 경로를 openssl이 못 읽는다. 상대 경로 폴더를 쓴다.
TMP="$CERTS/.tmp"
mkdir -p "$TMP"
# 서버 키/CSR 같은 중간 파일은 임시 폴더에만 두고 끝나면 지운다.
trap 'rm -rf "$TMP"' EXIT

# make_ca <폴더> : CA 키와 자체 서명 인증서를 만든다.
make_ca() {
  local dir=$1
  mkdir -p "$dir"
  openssl genrsa -out "$dir/ca.key" 2048 2>/dev/null
  openssl req -x509 -new -key "$dir/ca.key" -sha256 -days 3650 \
    -subj "/CN=vpn-practice CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$dir/ca.crt"
}

# make_server <CA 폴더> <출력 폴더> <SAN IP> : CA가 서명한 서버 인증서를 p12로 만든다.
make_server() {
  local ca_dir=$1 out_dir=$2 ip=$3
  local work
  work="$TMP/$RANDOM$RANDOM"
  mkdir -p "$work"
  mkdir -p "$out_dir"

  openssl genrsa -out "$work/server.key" 2048 2>/dev/null
  openssl req -new -key "$work/server.key" -subj "/CN=vpn-practice server" -out "$work/server.csr"
  printf 'subjectAltName=IP:%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n' "$ip" > "$work/ext.cnf"
  openssl x509 -req -in "$work/server.csr" -CA "$ca_dir/ca.crt" -CAkey "$ca_dir/ca.key" \
    -CAcreateserial -CAserial "$work/ca.srl" -sha256 -days 825 \
    -extfile "$work/ext.cnf" -out "$work/server.crt" 2>/dev/null

  # 비밀번호는 개행 없이 저장한다(서버가 파일 내용을 그대로 읽는다).
  printf '%s' "$(openssl rand -hex 16)" > "$out_dir/server.p12.pass"
  # 이 폴더를 통째로 마운트하므로 server.p12와 server.p12.pass만 둔다.
  openssl pkcs12 -export -inkey "$work/server.key" -in "$work/server.crt" \
    -name server -certfile "$ca_dir/ca.crt" \
    -passout "file:$out_dir/server.p12.pass" -out "$out_dir/server.p12"
}

if [ -d "$CERTS/ca" ]; then
  echo "certs/ca/가 이미 있어 CA를 다시 만들지 않는다."
else
  make_ca "$CERTS/ca"
  echo "CA를 새로 만들었다. 이미 빌드한 APK는 이 CA를 모르므로 새 서버를 믿지 않는다. 앱을 다시 빌드해야 한다."
fi

# 다른 CA는 "모르는 CA가 서명한 서버" 실패 시나리오용이다.
[ -d "$CERTS/wrong-ca/ca" ] || make_ca "$CERTS/wrong-ca/ca"

make_server "$CERTS/ca" "$CERTS/server" 10.0.2.2
make_server "$CERTS/wrong-ca/ca" "$CERTS/wrong-ca/server" 10.0.2.2   # 앱이 모르는 CA가 서명
make_server "$CERTS/ca" "$CERTS/wrong-san/server" 10.0.2.3           # 주소(SAN)가 다름

# 앱에는 CA의 공개 인증서만 넣는다. 개인키는 절대 넣지 않는다.
cp "$CERTS/ca/ca.crt" app/src/main/res/raw/practice_ca.pem
echo "완료: app/src/main/res/raw/practice_ca.pem 갱신"
