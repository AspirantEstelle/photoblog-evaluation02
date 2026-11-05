# PhotoBlog 공통평가 02

2022105748 조연흠 · 모바일/웹서비스 프로젝트

- 제출 저장소: https://github.com/AspirantEstelle/photoblog-evaluation02
- 클라우드 서비스: https://gyusik.pythonanywhere.com/
- 공개 API: https://gyusik.pythonanywhere.com/api_root/Post/
- 보고서: 루트의 `모바일웹서비스 프로젝트_공통평가 02_수행 결과 보고서.docx`

Ch08의 YOLOv5 클래스 출현 감지를 Django 이미지 게시글과 Android PhotoViewer에 연결한다. 공통평가 01과 별도 저장소이며, 해당 프로젝트의 서버·앱을 확장했다. YOLOv5는 기기에서 추론하고, PythonAnywhere는 게시글 API와 웹 서비스를 제공한다.

```text
ROOT/
├── PhotoBlogServer/   Django 서버와 API 테스트
├── PhotoViewer/       Android 앱과 캐시 테스트
├── YOLOv5/            원본 검출기, 변경 감지, 전송 대기열
├── evidence/          실제 실행 캡처와 검증 기록
└── 모바일웹서비스 프로젝트_공통평가 02_수행 결과 보고서.docx
```

## 서버

Python 3.12 이상에서 가상환경을 만든 뒤 실행한다.

```sh
cd PhotoBlogServer
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
python -m pip install -r requirements-dev.txt
python manage.py migrate
python manage.py seed_demo
python manage.py runserver 127.0.0.1:8000
```

로컬 웹은 http://127.0.0.1:8000/ 이며, `seed_demo`가 만든 로그인 정보와 API 토큰은 Git에서 제외한 `.demo-credentials.json`에 있다. 읽기는 공개하고, 작성은 `Authorization: Token <본인의 토큰>`으로 인증한다. 교재의 JWT 문자열을 재사용하지 않는다. 관리자 계정은 `python manage.py createsuperuser`로 만든다.

## YOLOv5

`YOLOv5/README_EVALUATION02.md`에 별도 Python 환경 설치, 모델 다운로드와 실행 절차가 있다. Python 3.12 및 PyTorch 2.5.1 CPU로 검증했다. 공식 v7.0 소스와 라이선스를 포함하며, 원본 `detect.py`는 변경하지 않았다. 모델 파일은 `python download_model.py`로 내려받아 출처와 SHA-256을 확인한다.

```sh
cd YOLOv5
python detect.py --source 0 --weights yolov5s.pt --device cpu
```

수정 검출기는 `detect_intrusion.py`이다. 실행 전에 `PHOTOBLOG_SERVER`와 `PHOTOBLOG_TOKEN` 환경변수를 본인의 서버 정보로 설정한다. PowerShell에서는 `$env:PHOTOBLOG_SERVER="http://127.0.0.1:8000"` 형식이다. 실제 토큰을 소스나 Git에 기록하지 않는다.

```sh
python detect_intrusion.py --source 0 --weights yolov5s.pt --device cpu --max-frames 120
```

이전 프레임에 없던 COCO 클래스가 새로 나타날 때만 바운딩 박스가 표시된 JPEG를 게시한다. 클래스가 계속 보이거나 사라지는 상황에서는 새 글을 만들지 않는다. 여러 입력의 이전 상태는 따로 보관한다. 클라우드는 서버 주소를 `https://gyusik.pythonanywhere.com`으로 바꿔 실행한다.

## Android

Android Studio에서 `PhotoViewer` 폴더를 열고 앱을 실행한다. JDK 17 이상, Android SDK 35, Android 11 이상 기기가 필요하다. 앱 ID는 `com.example.photoviewer02`로 평가 01 앱과 구분된다.

설정에서 로컬 또는 클라우드 서버를 선택하고 동기화하면 YOLO가 등록한 사진이 표시된다. 에뮬레이터의 로컬 주소는 `http://10.0.2.2:8000`이며, 실제 휴대전화에서는 클라우드 HTTPS 주소를 사용한다. 사진을 누르면 상세 기록을 확인할 수 있다. 사진을 직접 게시할 때는 해당 서버의 토큰이 필요하다.

## 추가 기능 두 가지

### 1 오프라인 캐시와 변경 확인

서버가 목록·상세 응답에 ETag를 제공하고 앱이 조건부 요청으로 변경 여부를 확인한다. 변경이 없으면 HTTP 304로 보관 목록을 재사용하고, 변경·삭제가 있으면 새 응답으로 교체한다. 앱 캐시는 서버별로 분리하며, 전체 32 MiB와 120항목으로 제한한다. SHA-256으로 손상을 검사하고 임시 파일을 완전히 쓴 뒤 원자적으로 교체한다. 오래 사용하지 않은 사진부터 정리하고, 보관함 화면에서 사용량 확인과 현재 서버의 캐시 삭제를 지원한다. 네트워크 실패 시 마지막 확인 시각과 오프라인 상태를 표시한다. 내려받지 않았거나 용량 제한으로 제거된 사진까지 오프라인에서 볼 수 있는 것은 아니다.

### 2 감지 사진의 영속 전송 대기열과 중복 방지

Edge는 이벤트 UUID, 시각, 제목·본문·JPEG를 먼저 SQLite에 저장하고 백그라운드에서 전송한다. 네트워크·일시적 서버 오류에는 제한된 지연 재시도를 적용하며 재시작 후에도 이어서 처리한다. 서버는 작성자별 `Idempotency-Key`를 기록해 응답 유실 후 같은 이벤트가 재전송돼도 한 게시글만 유지한다. 동일 키로 내용이 바뀌면 409, 원본이 삭제된 뒤 재전송하면 410을 반환한다. 잘못된 인증 등 영구 오류는 사용자가 수정할 수 있도록 실패 상태로 보관한다.

`python outbox_status.py --db <대기열 파일> --port 8765`를 실행하면 http://127.0.0.1:8765/ 에서 실제 전송 대기·완료·실패 상태를 확인할 수 있다. 상세 제한과 재시도 명령은 Edge 사용법에 있다.

## 검증

```sh
# PhotoBlogServer 폴더
python -m coverage run manage.py test blog -v 2
python -m coverage report
# PhotoViewer 폴더 (Windows는 gradlew.bat)
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
# YOLOv5 폴더
python -m unittest discover -s tests -p "test_edge.py" -v
```

Android 실제 기기 검증 절차는 `PhotoViewer/TESTING.md`에 있다. 제출 캡처와 항목별 결과는 `evidence/` 및 Word 보고서에 포함한다. 검증 기록과 코드 범위에 한정된 결과이며 모델이 모든 물체를 항상 정확히 분류한다는 의미는 아니다.

## 배포와 자료 출처

PythonAnywhere의 기존 게시글·사진·인증 정보는 새 배포 폴더로 복사해 보존한다. 웹 앱은 새 `PhotoBlogServer` 경로의 WSGI 설정과 정적 파일·사진 경로를 사용한다. 서버에서 YOLO 모델을 실행하지 않으므로 클라우드에 PyTorch를 설치할 필요가 없다. `.env`, 토큰, 개인 설정, DB, 운영 대기열과 검출 영상은 Git에서 제외한다. 제출 증거는 검토한 캡처만 별도로 포함한다.

- 강의자료: Ch08 Django와 YOLO를 이용한 침입자 감시 시스템 v1.8
- 기존 블로그 예비 코드: https://github.com/kiokahn/web_blog
- YOLOv5 원본 및 라이선스: https://github.com/ultralytics/yolov5/tree/v7.0 · `YOLOv5/LICENSE`
- YOLO 원본 커밋과 모델 해시: `YOLOv5/UPSTREAM.json`, `YOLOv5/MODEL.json`
