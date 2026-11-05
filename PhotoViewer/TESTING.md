# PhotoViewer 공통평가 02

앱 ID는 `com.example.photoviewer02`이고 Java 네임스페이스는 `com.example.photoviewer`이다. 공통평가 01 앱을 덮어쓰지 않는다. Django의 `/api_root/Post/` JSON 배열과 사진 URL을 읽으므로 YOLO Edge Client가 올린 사진도 동일한 목록과 상세 화면에서 확인할 수 있다.

## 추가 기능 오프라인 증거 캐시

- 서버별 저장 공간을 구분하고 모든 서버를 합쳐 32 MiB와 120개 항목으로 제한한다. 한 항목의 본문은 최대 5 MiB이다.
- 목록과 ETag를 하나의 버전 2 파일에 함께 저장한다. 파일에는 서버 주소, 키, 검증 시각, 본문과 SHA-256 검증 값이 포함된다. SHA-256은 파일 손상을 검출하는 용도이며 암호화는 아니다.
- 임시 파일을 끝까지 쓰고 동기화한 다음 원자적으로 교체한다. 읽을 때 검증에 실패하면 해당 항목을 폐기한다.
- `If-None-Match`를 전송해 변경이 없을 때 HTTP 304와 검증된 목록을 사용한다. 손상된 목록의 ETag는 보내지 않는다. 변경·삭제를 반영한 HTTP 200 응답은 기존 목록을 교체하고 더 이상 사용하지 않는 사진을 정리한다.
- 저장 한도에 도달하면 오래 사용하지 않은 사진부터 비운다. 작은 목록은 가능한 한 사진보다 우선 보존한다. 조회하지 않아 다운로드되지 않았거나 이미 제거된 사진은 오프라인에서 표시할 수 없다.
- 통신에 실패하면 무결성이 확인된 보관 목록을 표시하고 오프라인 상태와 마지막 서버 확인 시각을 알린다. 보관함에서 사용량을 보고 현재 서버의 캐시만 비울 수 있다.

## JVM 회귀 테스트

JDK 17 이상과 Android SDK 35를 준비한 뒤 프로젝트 폴더에서 실행한다.

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

Windows에서는 `gradlew.bat`를 사용한다. `EvidenceCacheTest`는 재시작 복원, 서버 격리, 손상·잘림 검출, 원자적 교체, 쓰기 실패 시 기존 데이터 보존, LRU, 전체 용량 제한, 삭제된 사진 정리, 서버별 비우기를 검사한다. `FeedClientTest`는 200/304 처리, 변경·삭제 반영, 네트워크 실패, 잘못된 응답, 손상 캐시 복구, 서버별 ETag 격리를 검사한다.

## 실제 Android 실행과 캡처

디버그 앱과 Android 테스트 APK를 기기 또는 에뮬레이터에 설치한다. PC 서버는 에뮬레이터에서 `http://10.0.2.2:8000`으로 접근한다.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e mode edge -e server http://10.0.2.2:8000 -e expected YOLO com.example.photoviewer02.test/com.example.photoviewer.EvidenceRunner
adb shell am instrument -w -e mode cacheaudit -e server http://10.0.2.2:8000 com.example.photoviewer02.test/com.example.photoviewer.EvidenceRunner
```

실행 모드를 바꿀 때 앱을 강제 종료한 뒤 다시 실행한다. `edge` 모드는 서버 목록에 `expected` 문자열을 포함한 감지 기록이 있는지와 사진 표시를 확인한다. `cloudedge`도 같은 검사이며 서버 주소를 클라우드 HTTPS 주소로 지정한다.

`cacheaudit` 모드는 실제 서버를 대상으로 다음을 실행한다.

1. 목록과 사진을 받은 뒤 다시 동기화하여 HTTP 304 확인
2. 캐시 파일을 의도적으로 손상시키고 HTTP 200 재수신으로 복구 확인
3. 보관함 버튼으로 현재 서버 캐시를 비우고 목록·사진을 다시 받는지 확인

오프라인 검사는 먼저 `cacheaudit`를 완료한 뒤 PC의 테스트 서버를 종료하고 실행한다.

```sh
adb shell am force-stop com.example.photoviewer02
adb shell am instrument -w -e mode offline -e server http://10.0.2.2:8000 com.example.photoviewer02.test/com.example.photoviewer.EvidenceRunner
```

`offline`은 프로세스 재시작 후 목록 복원, 실제 네트워크 실패, 마지막 확인 시각 표시, 저장된 사진 표시를 확인한다. 캡처는 테스트가 실제 기기 화면에서 가져오며 다음 폴더에 저장된다.

```text
/sdcard/Android/data/com.example.photoviewer02/files/evidence/
```

`cacheaudit`의 파일 손상은 테스트 전용 동작이다. 일반 앱에는 캐시를 손상시키는 기능이 없다. 테스트는 평가 02 앱의 데이터만 사용하며 평가 01 앱의 데이터는 사용하지 않는다.
