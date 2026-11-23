# 릴리스 방법

`v*` 태그를 push하면 GitHub Actions(`.github/workflows/release.yml`)가 서명된 release APK를 빌드해 GitHub Release에 `vpn-practice-<태그>.apk`로 첨부한다.

## 릴리스 하기
```
git checkout main && git pull
git tag v1.0.0
git push origin v1.0.0
```
Actions 탭에서 Release 워크플로가 끝나면 Releases 페이지에 APK가 올라온다.
태그와 `versionName`(`app/build.gradle.kts`)은 자동으로 맞춰 주지 않으므로 직접 맞춘다.

## 필요한 GitHub Secrets
- `KEYSTORE_BASE64` 키스토어 파일을 base64로 인코딩한 값
- `KEYSTORE_PASSWORD` 키스토어 비밀번호
- `KEY_ALIAS` 키 alias
- `KEY_PASSWORD` 키 비밀번호

값은 문서/코드/커밋에 쓰지 않는다.

## 로컬에서 서명 빌드 확인
같은 이름의 환경변수에 `KEYSTORE_PATH`(키스토어 파일 경로)를 더해 설정하고 `./gradlew assembleRelease`를 실행한다.
환경변수가 하나라도 없으면 서명 없이 빌드된다.

## 키스토어 백업
키스토어와 비밀번호는 repo 밖(`~/.android-keys/`)에 있다. 이 키를 잃어버리면 같은 키로 서명한 업데이트를 낼 수 없으므로
`vpn-practice-release.jks`와 `vpn-practice-release.properties`를 비밀번호 관리자 등 안전한 곳에 따로 백업해 둔다.
