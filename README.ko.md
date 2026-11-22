<h1 align="center">
  <img src="docs/branding/icon.svg" alt="SC:R 로고" width="160">
  <br>
  SC:R
</h1>

<p align="center">스타크래프트: 리마스터를 SC4J 모드로.</p>

<p align="center">
  <a href="https://github.com/fogofwarlabs/sc4j-scr/releases"><img src="https://img.shields.io/github/v/release/fogofwarlabs/sc4j-scr" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
</p>

<p align="center"><a href="README.md">English</a> | <b>한국어</b></p>

---

[SC4J](https://github.com/fogofwarlabs/sc4j/blob/main/README.ko.md) 모드이며, 모드 id는 `scr`, jar는 `scr.jar`입니다. 게임 데이터는 플레이어 본인의 스타크래프트: 리마스터 설치본에서 읽습니다.

지원하는 게임 빌드: 스타크래프트: 리마스터 1.23.10.

- [구성](#구성)
- [게임 데이터](#게임-데이터)
- [빌드와 실행](#빌드와-실행)
- [테스트](#테스트)
- [기여](#기여)
- [릴리즈](#릴리즈)
- [라이선스](#라이선스)

## 구성

| 경로 | 내용 |
|---|---|
| `scr-data` | 데이터 파일, 콘텐츠 표, 추가 유닛 확장 API |
| `scr-game` | 유닛, 명령, 전투, 컴퓨터 플레이어, 트리거, 리플레이, 저장 게임 |
| `scr-render` | 게임 화면 렌더러 |
| `scr-net` | LAN과 Beacon.Link 플레이 |
| `scr-client` | 클라이언트: 메뉴, 게임 화면, 오디오, 리플레이 재생 |
| `scr-benchmark` | JMH 벤치마크(`scr.jar`에 없음) |
| `sc-recorder` | 원본 게임의 프레임 기록기, [SCRECBIN 형식](docs/sc-recorder-format.ko.md)(`scr.jar`에 없음) |
| `mod.properties` | `scr.jar`의 모듈과 컴파일러 인자 |
| `build-logic/` | Gradle 규칙 |
| `docs/` | SCRECBIN 형식, [런처 그림](docs/branding/README.ko.md) |
| `licenses/` | 서드파티 라이선스 전문 |

## 게임 데이터

Gradle 속성 `sc4j.data`는 스타크래프트: 리마스터 폴더(`.build.info`와 `x86_64/StarCraft.exe`가 있는 폴더)를 가리킵니다. `%USERPROFILE%\.gradle\gradle.properties`(`sc4j.data=<폴더>`, 슬래시 사용), `-Psc4j.data=<폴더>`, CI에서는 `ORG_GRADLE_PROJECT_sc4j.data`로 설정합니다.

`installMod`는 공유 게임 폴더가 없는 설치본에 이 값을 써 넣습니다. 런처는 Windows에 기록된 위치에서 폴더를 찾고, 없으면 모드 설정에서 묻습니다.

## 빌드와 실행

JDK 25가 필요합니다. 명령은 저장소 루트에서 실행합니다(Linux에서는 `./gradlew`).

```powershell
git clone https://github.com/fogofwarlabs/sc4j-scr.git
cd sc4j-scr
.\gradlew.bat build
```

엔진 버전은 `gradle.properties`의 `sc4jVersion`이며 Maven Central에서 가져옵니다. `../sc4j`(또는 `-Psc4j.engine.dir=<폴더>`)에 엔진 체크아웃이 있으면 그것을 쓰고, `-Psc4j.engine.local=false`로 끕니다.

| 명령 | 하는 일 |
|---|---|
| `.\gradlew.bat jar` | `build/libs/scr.jar` |
| `..\sc4j\gradlew.bat -p ..\sc4j devInstall` | 엔진 개발용 설치본 `../sc4j/build/dev-install/` |
| `.\gradlew.bat installMod` | `scr.jar`를 그 설치본 또는 `-Psc4j.install=<폴더>`의 `mods/`에 복사 |
| `.\gradlew.bat :run` | 그 설치본의 사용자 데이터로 `scr.jar` 실행 |
| `..\sc4j\gradlew.bat -p ..\sc4j runDevLauncher` | 개발용 설치본의 런처 열기 |

## 테스트

| 명령 | 실행 | 필요 |
|---|---|---|
| `.\gradlew.bat build` | 단위 테스트와 검사 | 없음 |
| `.\gradlew.bat integrationTest` | `@RequiresInstallation` 테스트, GPU 테스트 | `sc4j.data`, GPU(`-Psc4j.render.smoke=false`면 GPU 테스트 건너뜀) |
| `.\gradlew.bat corpusTest` | `@CorpusTest` 장시간 실행 | `sc4j.data`와 아래 폴더. 폴더가 없는 테스트는 건너뜀 |

| 속성 | 폴더 |
|---|---|
| `sc4j.replays` | 리플레이 코퍼스 루트(`sc4j.replays.limit`: 파싱할 파일 수) |
| `sc4j.maps` | 큰 EUD 맵이 있는 맵 폴더 |
| `sc4j.corpus` | 코퍼스 조사가 재생하는 리플레이 |

## 기여

엔진의 [기여 안내](https://github.com/fogofwarlabs/sc4j/blob/main/CONTRIBUTING.ko.md)를 따릅니다. 설치본이 있으면 `integrationTest`도 실행합니다. 게임 데이터나 거기서 뽑아낸 것은 커밋하지 않습니다.

## 릴리즈

`develop`에서 `gradle.properties`에 `version=X.Y.Z`와 릴리즈된 `sc4jVersion`을 정한 뒤, `develop`에서 `main`으로 ``Release version `X.Y.Z` `` 제목의 풀 리퀘스트를 열어 merge commit으로 병합합니다. `main`에 푸시되면 `scr.jar`와 `scr.jar.sha256`이 붙은 GitHub 릴리즈 `vX.Y.Z`가 만들어집니다.

## 라이선스

[Apache License 2.0](LICENSE). 서드파티 자료: [NOTICE](NOTICE), [`licenses/`](licenses/).

StarCraft는 Blizzard Entertainment, Inc.의 상표입니다. SC4J는 Blizzard Entertainment와 제휴하거나 승인받지 않았습니다.
