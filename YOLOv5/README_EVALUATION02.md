# 공통평가 02 Edge Client

Ultralytics YOLOv5 v7.0 전체 소스와 GPL-3.0 라이선스를 포함한다. 공식 저장소의 커밋, 원본 `detect.py` 해시, 모델 출처는 `UPSTREAM.json`과 `MODEL.json`에 기록했다. `detect.py`는 변경하지 않았으며, 과제용 연결 코드는 `detect_intrusion.py`, `changedetection.py`, `edge_outbox.py`에 있다. 원본 소스의 사용법과 라이선스는 `README.md`, `LICENSE`를 참고한다.

## 설치

검증 환경은 Python 3.12, Windows x64, CPU이다. Windows용 Python 3.12를 설치한 뒤 별도 가상환경에서 실행한다.

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install torch==2.5.1 torchvision==0.20.1 --index-url https://download.pytorch.org/whl/cpu
python -m pip install -r requirements-edge.txt
python download_model.py
$env:YOLOv5_AUTOINSTALL="false"
```

`requirements-edge-lock.txt`는 실제 검증 환경의 전체 패키지 버전이다. 모델은 공식 릴리스에서 다운로드하고 해시를 검사하므로 저장소에 큰 가중치 파일을 중복 저장하지 않는다. 기본 예제 사진 `data/images/bus.jpg`, `zidane.jpg`는 upstream 제공 파일이다.

## 기본 검출기와 수정 검출기

```powershell
# 평가 3: 변경 없는 기본 detect.py + 실제 웹캠
python detect.py --source 0 --weights yolov5s.pt --device cpu --imgsz 640

# 테스트용 예제 사진에 실제 모델 추론
python detect.py --source data/images/bus.jpg --weights yolov5s.pt --device cpu

# 평가 4~9: 서버 토큰은 환경변수로 전달하며 저장소에 기록하지 않는다.
$env:PHOTOBLOG_SERVER="http://127.0.0.1:8000"
$env:PHOTOBLOG_TOKEN=(Get-Content -Raw ..\PhotoBlogServer\.demo-credentials.json | ConvertFrom-Json).token
python detect_intrusion.py --source 0 --weights yolov5s.pt --device cpu --max-frames 120

# 클라우드 전환: 로컬 토큰은 지우고 해당 클라우드 계정으로 인증한다.
$env:PHOTOBLOG_SERVER="https://gyusik.pythonanywhere.com"
Remove-Item Env:PHOTOBLOG_TOKEN -ErrorAction SilentlyContinue
$cloudLogin=Get-Credential -Message "PythonAnywhere에 배포한 Django 작성자 계정"
$env:PHOTOBLOG_USERNAME=$cloudLogin.UserName
$env:PHOTOBLOG_PASSWORD=$cloudLogin.GetNetworkCredential().Password
python detect_intrusion.py --source 0 --weights yolov5s.pt --device cpu --max-frames 120
Remove-Item Env:PHOTOBLOG_USERNAME,Env:PHOTOBLOG_PASSWORD -ErrorAction SilentlyContinue
```

기본 검출은 원본 YOLOv5 추론 결과를 사용한다. 수정 검출기는 COCO 80개 클래스의 출현 여부를 프레임마다 비교한다. 이전에 없던 클래스가 나타나는 `0 → 1` 변화에서만 바운딩 박스가 표시된 JPEG를 저장하고 전송한다. 같은 클래스가 계속 보이거나 사라지는 경우에는 새 게시글을 만들지 않는다. 여러 영상 입력은 각각의 이전 상태를 사용한다. 강의자료와 같이 전송 사진은 320×240이며 클래스 이름과 시각을 게시글에 기록한다.

## 추가 기능 2 SQLite 전송 대기열과 중복 방지

연결이 끊겨도 검출 이벤트를 먼저 SQLite에 저장한다. 각 이벤트는 UUID, 캡처 시각, 제목, 본문, JPEG 원본 바이트를 유지한다. 별도 작업 스레드가 전송하므로 네트워크 대기 때문에 추론이 멈추지 않는다. 재시작 시 대기 이벤트를 복원하고, 전송 중 종료된 작업은 60초 임대가 만료되면 다시 처리한다.

네트워크 오류, HTTP 408/425/429 및 서버 오류는 2초부터 증가하는 지연으로 재시도한다. 기본 지연은 최대 300초, 자동 시도는 최대 8회다. 서버가 Retry-After로 더 긴 대기를 요청하면 최대 24시간까지 반영한다. 잘못된 인증·입력·키 충돌 등 영구 오류는 실패 상태로 보존하고 사용자가 문제를 해결한 뒤 재시도한다. `Idempotency-Key`에 같은 UUID를 보내며 서버는 응답 유실 후 재전송에도 같은 게시글을 반환한다. 실제 성공 전에는 사진을 지우지 않으며, 성공 확인 후 대기열의 사진 바이트를 해제한다.

감지 이벤트 저장 즉시 전송 스레드를 깨운다. 사진 한 장 또는 마지막 프레임에서 종료해도 전송할 시각이 된 이벤트를 최대 8건 더 확인하며, 종료 대기는 최대 5초다. 전송 실패 시 다음 재시도 시각을 앞당기지 않는다. 남은 이벤트는 다음 실행 또는 `drain` 명령에서 이어서 전송한다. 대기 이벤트 최대 500개 또는 128MiB에서 새 이벤트 저장을 거부한다. `detected/` 보관 폴더에 쓸 수 없어도 SQLite에 저장한 사진은 전송할 수 있다.

```powershell
# 네트워크 없이 검출·대기열 저장만 실행
python detect_intrusion.py --source data/images/bus.jpg --queue-only

# 실제 대기열 상태를 브라우저에서 보기: http://127.0.0.1:8765
python outbox_status.py --port 8765

# 상태 확인: pending / inflight / sent / failed, 시도 횟수, 게시글 번호
python edge_outbox.py status

# 시간이 된 대기 이벤트 전송
python edge_outbox.py drain

# 오류 원인 수정 후 즉시 재시도 (서버별 대기열 분리)
python edge_outbox.py retry
python edge_outbox.py retry --event "해당-이벤트-UUID"

# 자동 검증
python -m unittest discover -s tests -p "test_edge.py" -v
```

기본 대기열은 `state/outbox.sqlite3`이며 `PHOTOBLOG_OUTBOX` 또는 `--outbox`로 변경할 수 있다. 상태 확인 명령에서는 `--db`를 사용한다. 인증 정보는 환경변수에만 있고 SQLite·로그·저장소에 저장하지 않는다. `runs/` 검출 사진과 `state/` 운영 데이터는 개인 촬영물이 포함될 수 있어 Git 추적에서 제외했다. 제출용 증거는 검토한 캡처만 상위 `evidence/`에 포함한다.
