# 스텝드라이브 (StepDrive)

> 차량 신호와 내비 안내를 읽어 회전·주차 체크리스트를 안내하고, 운전자별 연습 기록을 쌓는 **차 안 운전 연수 앱 프로토타입**.
> 현대자동차그룹 차량용 앱 개발 공모전 출품용 **Pleos Connect(Android Automotive OS) 앱**입니다.
> 이 저장소에는 참가자 실명·계정 정보를 넣지 않습니다(익명 심사 규정).


## 제출용 Release: contest1 (2026-11-07)

- 앱 파일: `apk/StepDrive_스텝드라이브_release.apk` (제출 묶음에 포함).
- 표시 이름: **스텝드라이브 제출**. 패키지: `kr.stepdrive.app.contest`.
- 버전: **0.2.5-contest1 / versionCode 17**. 제공된 0.2.5 소스에 fixed-test4까지의 수정을 반영한 제출 빌드입니다. 제공 APK 0.2.7의 소스를 복원한 버전은 아닙니다.
- 전용 개인 키로 서명하며 `debuggable=false`, `FORCE_DEMO=false`로 빌드합니다. 기존 앱과 검증 앱에 영향을 주지 않고 함께 설치됩니다. 앱별 프로필과 기록은 공유하지 않습니다.
- 개인 키·비밀번호·로컬 SDK 경로·캐시·사용자 기록은 제출 파일에 포함하지 않습니다. 최신 검사 결과는 `docs/RELEASE_VERIFICATION.txt`를 확인하세요.

### 먼저 실행하기

1. Pleos Connect 에뮬레이터를 실행합니다. 아래 명령은 Android SDK의 `platform-tools`가 PATH에 있는 경우입니다.
2. `adb install -r apk/StepDrive_스텝드라이브_release.apk`
3. 앱 목록에서 **스텝드라이브 제출**을 열고 위치·마이크·차량 속도·알림 권한을 직접 처리합니다. 차량 속도 권한이 위치 권한으로 표시될 수 있습니다.
4. 새 연습 프로필과 PIN을 만듭니다. 정차/P 상태에서 **모의 내비 켜기**를 선택하고 코스를 시작하면 에뮬레이터 차량 신호에 따라 시연 경로가 진행됩니다.
5. `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`의 Virtual Console로 차량 입력을 조절합니다. 실제 내비와 모의 내비를 구분하세요.

### 제출 빌드 재생성

JDK 17, Android SDK Platform 35, Build Tools 35.0.0과 Pleos SDK 저장소 접근이 필요합니다. `local.properties`에 각 PC의 SDK 경로를 설정하세요.

```powershell
.\gradlew.bat :core:test :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease "-Pstepdrive.contest=true"
```

출력은 `app/build/outputs/apk/release/app-release.apk`입니다. `signing/keystore.properties`와 개인 키가 있어야 Release 빌드가 진행됩니다. 서명이 없으면 실패하며 디버그 키로 자동 대체하지 않습니다. 원 소유자는 별도 보관한 `signing` 폴더를 복원하고, 다른 개발자는 자신의 키를 만듭니다. 다른 키로 빌드한 APK는 배포된 APK 위에 업데이트할 수 없습니다.

`signing/keystore.properties`의 형식(실제 값은 비공개로 보관):

```properties
storeFile=signing/stepdrive-contest-release.jks
storePassword=<개인 서명키 비밀번호>
keyAlias=stepdrive-contest
keyPassword=<개인 키 비밀번호>
```

서명키 없이 코드를 확인하려면 `.\gradlew.bat :app:assembleDebug "-Pstepdrive.validation=true"`로 별도 검증 앱을 빌드하세요. `stepdrive.contest`와 `stepdrive.validation`은 함께 사용할 수 없고, 제출 빌드에서는 `stepdrive.forceDemo=true`를 허용하지 않습니다.

## 반영된 기능: fixed-test4 (2026-11-07)

- 차량의 속도·기어·깜빡이·내비 안내는 기존 Pleos/AAOS SDK로 동작합니다. 기본 기능에 공공데이터 인증키는 필요하지 않습니다.
- 재탐색 시작 때 이전 안내를 비우고 완료 후 새 TBT를 기다립니다. 30초간 안내가 없으면 실패 상태를 표시합니다. 재탐색 전 회전을 새 경로에서 완료 처리하지 않습니다. 음성 명령은 `경로 재탐색`입니다.
- 실제 경로 형상과 정확도 있는 위치를 받았을 때만 지속적인 경로 이탈을 판정하는 모듈을 추가했습니다. 현재 연결된 NaviHelper에는 전체 경로 형상 공급자를 붙이지 않았으므로 자동 이탈 판정은 활성화되지 않습니다. 실제 지도상의 이탈→재탐색 성공 검증은 남아 있습니다.
- `PerceptionSource`와 신선도·신뢰도 검사를 갖춘 판정기를 추가했습니다. 차로 이탈, 안내 반대 차로 이동, 충돌 위험, 주차 정렬 확인 신호를 처리합니다. 현재 운영 공급자는 `UnavailablePerceptionSource`로, 실제 센서 인식 기능이 완성됐다는 뜻이 아닙니다. TEST 출처는 운영 판정기에서 거부합니다. 정렬 신호만으로 주차를 완료하지 않습니다.
- 실제 이동 뒤 5초마다 중간 기록을 원자적으로 저장합니다. 앱 재실행 시 24시간 이내 기록을 중단 기록으로 복구하고 점수는 누적하지 않습니다. 프로필 삭제·중복 저장·이전 세션의 지연 쓰기를 방어하며, 손상 파일은 분리 보관합니다. 자동 주행 재개는 하지 않습니다. 마지막 저장 이후 최대 약 5초와 저장 실패 구간은 복구되지 않을 수 있습니다.
- 긴 실행에서 이벤트 상세는 최근 2,000건으로 제한하고 평가용 누적 수치는 별도로 유지합니다. 12시간 입력 자동 검사는 실제 12시간 에뮬레이터 실행이나 메모리 압박 검사를 대신하지 않습니다.
- 음성 `도움말`, `다시`, `경로 재탐색`, `연습 종료`와 종료 확인을 처리합니다. 10초 안의 `네`만 종료 확인으로 사용하고 `아니요`는 취소합니다. 주차 중 빠르거나 오래된 속도에서는 `다음`을 거부합니다. STT 초기화 중 요청 보존, 10초 제한, 늦은 이전 콜백 무시를 추가했습니다. 모든 문장의 실제 발화·소음 인식률 검증은 별도입니다.
- OS 제한 또는 P 이외 기어·이동·속도 미수신이면 설정·코스·프로필을 잠급니다. 연습 중에는 안내·속도·말하기 버튼만 표시합니다. 중단 기록은 홈/리포트에서 `평가 보류`로 표시합니다.
- 정차 후 홈의 `데이터 연결`에서 공공데이터 인증키, 통계 연도, 시군구 법정동 코드 5자리를 입력합니다. 키는 저장하지 않으므로 갱신할 때 다시 입력합니다. API 신청 대상: [한국도로교통공단 지자체별 교통사고 다발지역](https://www.data.go.kr/data/15057467/openapi.do). 해당 통계는 지자체별 선정 지점이며 모든 위험을 포함하지 않습니다. 제공 기준 반경 150m로 취급하며 도로별 회피 경로를 보장하지 않습니다.
- 공공 API는 페이지 누락·중복·오류 응답·좌표 오류를 검사하고 갱신 실패 시 기존 캐시를 유지합니다. 검증용 응답을 이용한 시험과 실제 키를 사용한 다운로드를 구분합니다. 실시간 주행에서는 공공 통계가 없다는 이유로 시연 지점을 사용하지 않습니다.
- 실제 목적지에서 임의로 빠른 길/쉬운 길 두 개를 만들어 표시하던 부분을 제거했습니다. 도로 상세가 없으면 난이도·시간·위험 회피를 미확인으로 표시합니다. 수신 TBT 개수는 전체 도로 분석이 아닌 현재 받은 안내에 한정합니다.

재현 도구의 `features`, `recovery-seed`, `recovery-check`, `recovery-cleanup` 모드를 추가했습니다. 복구 시험은 검증 패키지에 전용 프로필을 만든 뒤 프로세스를 종료하며, 마지막 cleanup 검사에서 해당 프로필만 삭제합니다. 원래 `kr.stepdrive.app`은 조작하지 않습니다. 최신 정확한 검사 수와 APK 체크섬은 동봉한 결과 보고서와 manifest를 기준으로 보세요. 아래 fixed-test1~3 절은 당시 이력입니다.

## 1. 앱 이름 및 한 줄 소개

- **앱 이름**: 스텝드라이브 (StepDrive)
- **한 줄 소개**: 차량 신호와 내비 안내로 단계별 연습을 돕는 앱. 공공 통계는 선택적으로 연결하며, 실제 차로·장애물 인식에는 별도 센서 공급자가 필요합니다.

## 2. 실행 방법

### 수정본 안내

제출 묶음에는 수정된 소스 코드와 서명된 APK가 함께 들어 있습니다. 최신 검사 결과와 제한 사항은 `docs/RELEASE_VERIFICATION.txt`를 참고하세요.

- JDK 17, Android SDK Platform 35 및 해당 Android 빌드 도구가 필요합니다.
- 소스 확인용 Debug 빌드는 `-Pstepdrive.validation=true`로 기존 앱과 분리합니다. 제출할 파일은 상단에 안내된 서명된 Release APK입니다.

### 소스에서 빌드

```bash
# local.properties 에 sdk.dir=<Android SDK 경로> 를 적어 주세요 (Android Studio가 자동 생성).
./gradlew :core:test            # 규칙 엔진 및 운전자 조작 실수 검사
./gradlew :app:testDebugUnitTest # 저장·세션·음성·내비 방향 변환 검사
./gradlew :app:assembleDebug -Pstepdrive.validation=true
./gradlew :app:assembleRelease -Pstepdrive.contest=true # 개인 서명키 필요
```

- Pleos Connect SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받습니다(`settings.gradle.kts`).
- `-Pstepdrive.forceDemo=true`를 붙이면 Pleos 서비스가 없어도 항상 **시연 모드**로 동작하는 빌드가 됩니다.

### 기존 앱과 나란히 설치하는 검증 APK

제공된 소스는 0.2.5(versionCode 13) 기반이고, 제공 APK와 기존 에뮬레이터 앱은 0.2.7(versionCode 15)입니다. 기존 앱과 기록을 보존하기 위해 별도 패키지로 검증합니다. 이 수정본이 0.2.7의 모든 변경을 포함한다고 볼 수는 없습니다.

```powershell
.\gradlew.bat :app:assembleDebug "-Pstepdrive.validation=true"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- 검증 버전: **0.2.5-fixed-test4 / versionCode 16**.
- 검증 앱 이름: **스텝드라이브 검증**, 패키지: `kr.stepdrive.app.validation`.
- 일반 빌드는 기존 `kr.stepdrive.app` 패키지를 유지합니다. 낮은 버전 번호이므로 기존 0.2.7 위에 업데이트 설치할 수 없습니다.
- 최초 실행 시 권한을 처리하고 새 테스트 프로필을 만드세요. 기존 앱의 프로필과 기록은 공유하지 않습니다.

### 두 가지 동작 모드

| 모드 | 언제 | 차량 신호 | 내비 | 음성 |
|---|---|---|---|---|
| **Pleos** | Pleos Vehicle Service(`ai.pleos.playground.service.vehicle`)가 설치된 차량·에뮬레이터 | Vehicle SDK + AAOS CarPropertyManager | NaviHelper SDK | TextToSpeech / SpeechToText SDK (ON_DEVICE) |
| **시연** | 그 밖의 Android 기기·에뮬레이터 | 2분짜리 스크립트 재생(`core/demo/DemoScenario.kt`) | 같은 스크립트 | Android 기본 TTS |

시연 시나리오: 출발 → 우회전(깜빡이 확인) → 사고다발 교차로 사전 경고 → 고속도로 합류 → 도착 → 주차 체크리스트 음성 코치 → 리포트.
에뮬레이터에서는 Virtual Console(`adb forward tcp:8088 tcp:8088` → `http://127.0.0.1:8088/`)로 기어·브레이크·핸들 각도·방향지시등을 직접 바꿔 Pleos 모드 동작을 확인할 수 있습니다.

**모의 내비(Pleos 모드, 에뮬레이터 테스트용)**: 에뮬레이터 내비는 위치가 고정되어 회전 안내가 나오지 않습니다. 홈 화면의 「모의 내비 켜기」를 누르면 대본 경로(우회전 → 사고다발 교차로 → 연속 회전 → 합류 → 출구 → 도착, 약 3.2 km)가 Virtual Console의 **실제 속도값**만큼 진행되어, 운전자의 깜빡이·브레이크·핸들 조작을 그대로 코칭합니다(`app/navi/SimulatedNavSource.kt`). 실차에서는 끄고 씁니다.

**자가 점검 로그**: `adb logcat -s STEPCHECK`로 13개 항목(차량서비스·속도·기어·방향지시등·핸들·브레이크·주행거리·내비연결·내비안내·경로요청·목적지·음성·코칭)이 정상/실패/대체/대기로 15초마다 요약됩니다.

## 3. 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| 대상 | Pleos Connect Emulator v2.1.0 (Android 14 / API 34, Automotive, **Cold boot**) |
| 앱 SDK | minSdk 28 · targetSdk 34 · compileSdk 35 |
| 언어·UI | Kotlin 2.0.21 · Jetpack Compose (BOM 2024.12) · Material 3 |
| 빌드 | Gradle 8.10.2 (wrapper) · AGP 8.8.2 · JDK 17 이상 |
| Pleos SDK | Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TextToSpeech 3.3.1 · SpeechToText 3.3.1 |
| 차량 권한 | `pleos.car.permission.CAR_DRIVE`(브레이크·핸들 각도·주행거리), `CAR_LIGHTS`(방향지시등), `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `FUSED_LOCATION`(런타임 허용 필요), `TTS_SERVICE`, `STT_SERVICE` |
| AAOS 권한 | `android.car.permission.CAR_SPEED`(런타임 요청), `CAR_POWERTRAIN`(기어) |
| 기타 권한 | `RECORD_AUDIO`(음성 명령 "다음/다시/출발"), `INTERNET`(사고다발지역 공공데이터 갱신) |
| 데이터 | 운전자 프로필·점수·연습 포인트만 차량 내 DataStore에 저장. 가입·서버·휴대폰 앱 없음 |

## 4. APK 빌드 결과

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/`에서 생성 파일을 확인합니다.
- 서명 키 설정이 없거나 불완전하면 Release 빌드는 실패합니다. `signing/keystore.properties`로 개인 서명키를 설정합니다.
- 제출 Release의 서명·설치·검사 결과는 `docs/RELEASE_VERIFICATION.txt`에 기록합니다. 과거 Debug 검사 이력과 구분합니다.

---

## 기획서 기능 ↔ 코드

| 기획서 | 구현 위치 | 상태 |
|---|---|---|
| ① 운전자별 연습 시작 · ⑩ 프로필과 기록 관리(PIN, 180일 자동 삭제) | `core/profile/DriverProfile.kt`, `app/data/ProfileStore.kt` | 구현 |
| ② 목적지와 연습 코스 | `core/course/*`, `app/navi/PleosNavSource.kt`, `app/ui/MainViewModel.kt` | 실제 내비 목적지와 명시적 모의 코스를 구분. 확인되지 않은 도로 난이도·빠른 길·위험 회피를 단정하지 않음. 전체 도로 형상 공급자 연동은 미완료 |
| ③ 위험 구간 사전 경고(공공데이터 + 내비 주의 지점) | `core/hazard/*`, `app/hazard/HazardRepository.kt`, `core/coach/CoachEngine.kt` | 내비 주의 지점 사용. 선택형 공공 API 입력·검증·갱신·캐시 구현, 실제 인증키 다운로드는 미검증 |
| ④ 회전 구간 실행 확인 코칭 | `core/coach/CoachEngine.kt`, `core/score/ScoreEngine.kt` | 구현. 에뮬레이터 SDK 수신과 전체 모의 경로 5회 평가 확인 |
| ⑤ 잘못된 조작 경고 | `ScoreEngine`(제한속도·급가감속·위험 구간 진입 속도), `ParkingCoach`(후진 속도), `CoachEngine`(깜빡이 없는 핸들 조작·너무 느린 주행 참고 안내), `core/coach/OperationWatch.kt`(주행 중 변속·브레이크 없는 변속·정차 후 P 미설정) | 구현. 참고 안내는 점수에 반영하지 않고 주행 기록에만 남김. 기준값은 임시 |
| ⑥ 주차 음성 코치(체크리스트: 주변 확인 → 진입 준비 → 차량 위치 확인 → 마무리 점검, 카메라 미사용, 핸들 지시 없음) | `core/parking/ParkingCoach.kt`, `app/ui/screens/DriveScreen.kt` | 구현. 핸들 각도는 참고 정보로만 읽어 줌. 에뮬레이터에서 기어·속도·핸들 각도 수신 확인 완료 |
| ⑦ 주행 점수와 난이도 단계(감점 합산, 가중치 누적, 해금) | `core/score/ScoreEngine.kt`, `core/score/SkillState.kt` | 구현, 기준값은 임시 |
| ⑧ 실력이 오르면 줄어드는 피드백 | `core/score/SkillState.kt`(feedbackLevel 3→2→1), `core/coach/CoachEngine.kt`(단계별 음성 필터) | 구현. 90점 이상 2회 연속이면 한 단계 줄고, 80점 미만이면 한 단계 복구. 1단계는 경고·회전 안내·참고 안내만 |
| 앱 실행 시 지난 주행 요약(점수·가장 큰 감점) | `core/report/TripSummary.kt`, 홈 화면 | 구현 |
| 내비가 앞으로 와도 연습 유지(포그라운드 서비스 + 알림) | `app/service/PracticeService.kt` | 구현. 알림에 현재 코칭 문장 표시, 탭하면 앱 복귀 |
| 관측 부족 점수 보류(평가 회전·위험 구간 0회이며, 1 km 미만 또는 유효 속도 관측 2회 미만) | `core/score/ScoreEngine.kt` (`provisional`) | 구현. 점수는 보여 주되 누적·단계에는 반영 안 함 |
| ⑨ 리포트·다음 연습 포인트·마무리(졸업 리포트) | `core/practice/PracticePointGenerator.kt`, `core/score/SkillState.kt`, `app/ui/screens/ReportScreen.kt` | 구현 |
| 내비 업데이트 사이 거리 보정(속도 적분) | `core/coach/DistanceSmoother.kt` | 구현 |
| 주행 중 화면 제한(음성 우선) | `app/driving/UxRestrictionsMonitor.kt`, `DriveScreen.kt` | 구현 |

## 구조

```
core/   순수 Kotlin 규칙 엔진 (Android 의존 없음, JUnit 테스트 포함)
  model/ score/ coach/ parking/ course/ practice/ profile/ hazard/ report/ demo/
app/    Pleos Connect 앱
  vehicle/   VehicleSignalSource: PleosVehicleSource(Vehicle SDK + android.car) / DemoVehicleSource
  navi/      NavSource: PleosNavSource(NaviHelper SDK) / SimulatedNavSource(모의 내비) / DemoNavSource
  service/   PracticeService: 연습 중 포그라운드 서비스 + 알림
  location/  PleosLocationSource(FusedLocation SDK)
  speech/    Speaker(TTS) · VoiceCommandSource(STT) — Pleos / Android 기본 구현
  hazard/    내비 사고 지점 + 선택형 공공 API/캐시 + 명시적 시연용 샘플
  data/      ProfileStore (DataStore, 차량 내 저장)
  driving/   CarUxRestrictions 모니터
  session/   DriveSessionController: 신호 → 점수·코칭·주차 → 음성 → 리포트
  ui/        Compose 화면 5개 (프로필 / 홈 / 코스 / 주행·주차 / 리포트)
```

## 구현 및 검증 범위의 한계

- 핸들 각도·기어·속도·방향지시등은 에뮬레이터 SDK에서 실제 수신을 확인했습니다. 실차 수신 정확도는 별도입니다.
- NaviHelper의 `onCautionPointInfo`(ACCIDENT_PRONE_AREA)와 `onTBTInfo`가 에뮬레이터 내비에서 실제로 발생하는지.
- 사고다발지역 공공데이터 API 응답 필드명(`HazardRepository.refreshFromPublicData`의 매핑) — 서비스 키 발급 후 확인.
- 코스 후보: 실제 내비 목적지와 사용자가 명시적으로 켠 모의 코스를 구분합니다. 도로 상세가 없으면 난이도·소요 시간·위험 회피를 미확인으로 표시합니다.
- 참고 안내(깜빡이 없는 핸들 조작 90°/30 km/h, 느린 주행 제한속도−20 km/h·20초, 주차 기어 실수)의 기준값은 에뮬레이터 기준이며 실측으로 조정.
- 점수 가중치·통과 기준·주차 안내 기준값은 모두 `SkillRules`, `ParkingCoach` 생성자 인자로 분리되어 있어 테스트 결과로 조정.

모든 안내는 참고용이며 운전 책임은 운전자에게 있습니다. 앱은 차량을 제어하지 않는 읽기 전용 구조입니다.

## 2026-11-06 1차 수정 이력 (당시 검증: 42개)

### 수정한 동작

- 방향지시등 미수신을 `UNKNOWN`으로 구분하고, 관측이 충분하지 않은 회전에는 미사용 감점을 주지 않습니다.
- 다음 회전이 사라질 때 같은 회전을 반복 집계하던 문제와, 안내 취소를 완료된 회전처럼 집계하던 문제를 수정했습니다.
- 속도의 수신 시각을 별도로 사용해 다른 차량 신호의 갱신으로 급가감속이 잘못 계산되지 않도록 했습니다.
- 경고에 밀린 회전 안내를 보관하고, 이름이 같은 연속 회전도 각각 안내합니다. 차로 정보 없이 특정 차로로 이동하라는 문구를 제거했습니다.
- 권한 허용 후 속도·위치·음성 연결을 다시 시도합니다.
- 주행 제한 상태가 확인되기 전에는 제한 화면을 표시하고, 프로필·홈·코스·리포트 화면 및 관련 동작에 정차 조건을 적용합니다.
- P 기어를 주차 체크리스트에 전달합니다. 음성 “다음”만으로 주차 완료를 선언하지 않으며 완료 이벤트는 한 번 기록합니다.
- 프로필 변경을 단일 DataStore 트랜잭션 안에서 처리합니다. 최신 저장 기록에 주행을 합치고 중복 주행 저장 및 삭제된 프로필의 재생성을 막습니다. 저장 실패는 리포트에 표시합니다.
- 이전 연습 지점의 통과 위치와 관측 데이터를 확인해 연습 목표 달성 여부를 갱신합니다. 근거가 부족하면 기존 목표를 유지합니다.
- 추정 코스와 샘플 위험 지점을 화면에 명시하고, 내비 경로 요청 실패 시 세션을 시작하지 않습니다.
- 에뮬레이터 실행 중 발견한 코스 시작 직후 종료 문제를 수정했습니다. 서비스 종료는 STOP 시작 요청 대신 `stopService()`를 사용하며, 포그라운드 전환 실패 시 서비스를 즉시 정리합니다.

### 실행한 검증

- Windows에서 JDK 17, Gradle 8.10.2, Kotlin 2.0.21, Android SDK Platform 35로 전체 Android 앱을 빌드했습니다.
- `:core:test :app:testDebugUnitTest :app:assembleDebug` 성공. core **39개**, app **3개**, 총 **42개 테스트 통과**, 실패·오류·건너뜀 0개.
- 앱 단위 테스트는 메모리 DataStore를 사용합니다. 이 결과만으로 실제 저장 장치나 모든 Pleos 기능의 정상 동작을 보장하지는 않습니다.
- 검증 APK를 Pleos Connect API 34 에뮬레이터에 설치했습니다. 사용자 권한 허용 후 속도 수신, 차량 서비스·내비 연결, 홈·코스 화면과 포그라운드 서비스 실행을 확인했습니다.
- 모의 내비와 에뮬레이터 차량 신호로 D 주행 → R 저속 후진 → 핸들 회전·복원 → 정지 → P 전환을 실행했습니다. 회전·위험 구간 코칭, 주차 1~4단계와 P 전환 후 5단계 완료, 리포트 저장을 확인했습니다. 짧은 주행은 누적 점수 0으로 보류됐습니다.
- 내비가 앞에 떠 있는 동안에도 주행 서비스는 `isForeground=true`로 유지됐고, 수정본 프로세스에 치명적 종료 로그가 없었습니다. 주행 종료 시 서비스가 정리됐으며 앱을 강제 종료 후 재실행해도 주행 기록과 요약이 보존됐습니다.
- 이 에뮬레이터에서는 D 상태에서 Pleos 시스템의 ‘주행 중에는 사용할 수 없습니다’ 화면이 표시됐습니다. 백그라운드 코칭은 확인했지만 앱 자체의 축소 주행 화면 표시는 검증되지 않았습니다. 제출 전 대상 플랫폼의 주행 중 화면 허용 동작을 추가 확인해야 합니다.
- 실제 마이크 음성 인식, 권한을 거부한 뒤 설정에서 재허용하는 과정, 차량 서비스 재연결, 모든 연속 회전 시나리오와 실차 검증은 별도로 필요합니다.

PIN 해시는 원본부터 salt와 SHA-256 반복 20,000회를 사용하고 있어 변경하지 않았습니다. 실제 도로 경로 분석과 사고다발지역 공공데이터 연동은 기존의 예정 범위로 남아 있습니다.


## 2026-11-07 추가 수정 및 검사 (fixed-test2 이력)

- 동일 속도 신호가 다른 신호 갱신으로 중복 집계되던 과속 비율 계산을 수정했습니다. 제한속도 미수신 구간은 과속 비율의 분모에 넣지 않습니다.
- 오래된 속도·위치·내비 안내를 만료시킵니다. 속도가 없으면 정차나 안전한 위험 구간 통과로 판정하지 않고, 주차 완료를 기다립니다.
- 주행거리계가 갱신되지 않으면 신선한 속도 관측 사이의 거리를 추정합니다. 수신 단절 구간은 적분하지 않으며 리포트에 ‘추정’을 표시합니다. 정차 주행을 임의로 0.1 km라고 표시하지 않습니다.
- 차량·내비 구독을 재시도하고, 권한 회수 후 늦게 들어오는 속도 콜백을 무시합니다. 화면 제한 서비스 연결이 끊기거나 중지되면 제한 상태로 돌아갑니다.
- 파일 저장 실패와 손상된 프로필 데이터를 사용자에게 알리고 기존 파일을 보존합니다. 삭제된 프로필이나 이전 세션의 지연 저장이 새 세션 상태를 덮어쓰지 않게 했습니다.
- 짧은 음성 명령은 전체 문장으로 비교합니다. 부정 표현이나 대화의 일부를 ‘다음’으로 오인하지 않습니다. 초기화 중 대기 음성의 수·시간을 제한하고 중지 시 비웁니다.
- 내비 검색 화면 API에 필요한 NAVI_CUSTOM_MAP 일반 권한을 선언했습니다. 알림 권한은 Android 13 이상에서만 요청합니다.
- Android 12 이상의 클라우드 백업·기기 이전에서 앱 데이터가 제외되도록 XML 규칙을 명시했습니다. 규칙 문법은 Android 공식 문서를 참고했고, 실제 기기 이전은 실행하지 않았습니다: https://developer.android.com/identity/data/autobackup

### 검증 방법

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug "-Pstepdrive.validation=true"
```

- JVM 자동 검사: core 52개 + app 17개 = **69개**, 실패·오류·건너뜀 0개.
- Android Lint: 오류 0개. 남은 경고는 의존성·대상 SDK 버전, 가로 화면 및 아이콘·리소스 관련입니다. 공모전 SDK 호환성을 확인하지 않은 일괄 업그레이드는 하지 않았습니다.
- 실제 파일 DataStore의 동시 저장·중복 저장·재개방, 삭제 프로필·만료 데이터·손상 JSON, 저장 실패·지연 취소와 반복 세션을 검사했습니다.
- 에뮬레이터 검사 코드는 `app/src/androidTest/`에 있습니다. 검사 전용 데이터로 실행하며 기존 프로필을 덮어쓰지 않습니다.
- 차량 권한 검사 중 ContextWrapper로 거부/허용 상태를 주입한 검사는 Android 설정에서 실제 권한을 누르는 검사와 구분해야 합니다.
- 전체 모의 경로는 실제 Pleos 차량 신호와 모의 내비를 결합합니다. 실제 지도에서 목적지까지 이동한 결과가 아닙니다. Virtual Console의 계단식 속도 입력으로 급가감속이 기록될 수 있으며 점수 기준의 실차 정확도를 입증하지 않습니다.
- PC 스피커의 안내 음성은 사용자 청취로 확인했고, PC 마이크 연결 후 Pleos 엔진이 ‘다음’을 인식하는 것을 확인했습니다. 모든 명령·소음 환경·주차 중 음성 조작까지 검증한 것은 아닙니다.

### 재현용 에뮬레이터 검사

`verification/run_emulator_checks.py`가 제공된 경우 ADB 경로와 기기 일련번호를 환경에 맞게 설정하세요. 검사 APK와 앱 APK를 먼저 설치하고 Virtual Console의 포트를 연결합니다.

```powershell
adb forward tcp:8088 tcp:8088
python verification/run_emulator_checks.py adapters
python verification/run_emulator_checks.py lifecycle
python verification/run_emulator_checks.py route
```

이 도구는 에뮬레이터 전용이며 실제 차량에 사용하지 않습니다. 결과와 남은 미확인 항목은 동봉한 `StepDrive_검증결과_20261107.txt`를 기준으로 확인하세요. 이전 42개 검사 기록은 당시 결과로 보존한 것입니다.


### 추가 에뮬레이터 확인 완료 (2026-11-07)

- 기본 연결·저장·유휴 화면 6항목, 전체 경로 1항목, 백그라운드 주행 1항목, 실제 Android 권한 거부/재허용 2항목: 총 10개 계측 검사 통과.
- 전체 모의 경로의 방향지시등 평가 5회, 주차 완료 1회, 기록 1회 저장. 거리 추정값 3.301 km.
- 홈 화면으로 전환하고 주행 화면을 3회 재생성해도 주행이 유지됐습니다. 알림 표시 및 주행 종료 후 서비스 정리도 확인했습니다. 계측 도구가 실행된 조건으로, 장시간 메모리 압박 시 생존을 보장하는 결과는 아닙니다.
- 사용자가 실제 설정에서 속도 권한을 거부한 상태에서 속도 미수신을 확인하고, 재허용 후 새 속도 수신을 확인했습니다. 이 에뮬레이터는 CAR_SPEED를 위치 권한 그룹에 표시합니다.
- PC 마이크 입력의 ‘다음’ 인식 결과를 확인했습니다. 마이크 접근 스위치는 사용자가 직접 설정했습니다.

검사 도구가 ADB를 찾지 못하면 `ADB` 환경 변수에 adb 실행 파일의 전체 경로를 지정하세요. 실제 권한 검사 모드는 사용자가 해당 상태를 먼저 설정한 후 각각 `permission-denied`, `permission-restored`로 실행합니다. 도구가 시스템 권한을 대신 변경하지는 않습니다.

## 2026-11-07 운전자 조작 실수 보완 (fixed-test3)

- 경로 안내와 반대 방향지시등이 주행 중 접근 구간에서 1초 이상 지속되면 어느 쪽이 다른지 짧게 안내합니다. 동일한 지속 상태에는 한 번 안내하고, 수정 후 재발하면 다시 안내합니다. 모든 피드백 단계에서 동작하며 사고 구간 경고가 우선합니다.
- 조작 수정·신호 미수신·경로 취소 시 대기 중인 불일치 안내를 지웁니다. 반대로 바뀐 후 이전 칭찬이 뒤늦게 나오지 않게 했습니다.
- 올바른 방향지시등을 잠깐 켠 뒤 반대로 바꾸거나 30m 전에 꺼도 성공으로 남던 평가를 수정했습니다. 30m 전에 수정하면 다시 평가하고, 늦게 수정한 것은 사전 사용 성공으로 바꾸지 않습니다.
- 왼쪽/오른쪽 출구를 구분합니다. 중앙·방향 불명 출구는 오른쪽으로 가정하지 않습니다. 회전 지점에 도달하기 전 경로가 바뀐 경우 이전 회전을 완료로 집계하지 않습니다.
- 후진 시작과 마무리 단계에서도 5km/h 초과 안내를 합니다. 속도가 없거나 오래되면 정지/P 전환을 판단하지 않고, 핸들 신호 누락을 핸들 복원으로 취급하지 않습니다. 별개의 움직이는 중 변속 실수는 짧은 간격 제한 후 다시 안내합니다.
- 주차 화면의 속도 미수신을 ‘확인 중’으로 표시합니다. 핸들 각도는 회전량(도)만 표시하며 좌우 방향이나 바퀴 정렬을 단정하지 않습니다. 현재 에뮬레이터의 Virtual Console 입력과 SDK 관측 각도는 부호가 반대였습니다.
- 주차 결과는 체크리스트 완료를 뜻합니다. 실제 차로 위치, 반대 차로 진입, 주차면 정렬, 장애물 충돌은 판단하지 않습니다.

APK와 함께 제공하는 이번 검증 결과 파일에는 자동 검사 수, 실수 시나리오 계측 결과, 정상 경로 재검사와 남은 범위를 구분해 기록했습니다. 이전 절의 69개 검사·10개 계측 결과는 fixed-test2 당시 이력입니다.

```powershell
# 검증 앱과 Android 검사 APK를 설치하고 에뮬레이터를 실행한 상태에서:
adb forward tcp:8088 tcp:8088
python verification/run_emulator_checks.py mistakes
python verification/run_emulator_checks.py route
```

`mistakes`는 실제 Pleos 에뮬레이터 차량 신호와 검사에서 지정한 내비 안내를 조합합니다. 실제 지도상의 경로 이탈 검사가 아닙니다. 검사 도구는 에뮬레이터 여부를 확인한 뒤 입력을 제어하고, 종료 시 정지/P/깜빡이 꺼짐으로 복원합니다.
