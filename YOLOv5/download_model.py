"""Download only the pinned official YOLOv5 v7.0 model, verifying its digest."""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

root = Path(__file__).resolve().parent
spec = json.loads((root / 'MODEL.json').read_text(encoding='utf-8'))
target = root / 'yolov5s.pt'
if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == spec['sha256']:
    print('Official model already present; SHA256 verified.')
else:
    with urlopen(spec['url'], timeout=120) as response:
        content = response.read()
    if hashlib.sha256(content).hexdigest() != spec['sha256']:
        raise RuntimeError('Model digest mismatch; no file was installed')
    temporary = root / 'yolov5s.pt.download'
    temporary.write_bytes(content)
    temporary.replace(target)
    print('Official model downloaded and SHA256 verified.')
