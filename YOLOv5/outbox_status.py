"""Read-only localhost view of the actual SQLite upload queue."""
import argparse
import html
import json
import sqlite3
import time
from contextlib import closing
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from edge_outbox import DEFAULT_DB


def snapshot(path):
    with closing(sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True, timeout=5)) as db:
        db.row_factory = sqlite3.Row
        counts = {row[0]: row[1] for row in db.execute('SELECT state,COUNT(*) FROM events GROUP BY state')}
        rows = db.execute('SELECT event_id,server,title,state,attempts,next_attempt,last_error,post_id,image_size,captured_at FROM events ORDER BY created_at DESC LIMIT 50').fetchall()
        pending_bytes = db.execute("SELECT COALESCE(SUM(image_size),0) FROM events WHERE state!='sent'").fetchone()[0]
    return {'counts': {state: counts.get(state, 0) for state in ('pending', 'inflight', 'sent', 'failed')},
            'events': [dict(row) for row in rows], 'pending_bytes': pending_bytes,
            'checked_at': datetime.now().astimezone().isoformat(timespec='seconds')}


def render(data):
    esc = html.escape
    labels = {'pending': '전송 대기', 'inflight': '전송 중', 'sent': '전송 완료', 'failed': '확인 필요'}
    cards = ''.join(f'<div class="card {state}"><span>{label}</span><strong>{data["counts"][state]}</strong></div>' for state, label in labels.items())
    rows = []
    for item in data['events']:
        wait = max(0, int(item['next_attempt'] - time.time()))
        retry = (f'{wait}초 뒤' if wait else '전송 준비') if item['state'] == 'pending' else '-'
        destination = esc(item['server'])
        post = f'게시글 #{item["post_id"]}' if item['post_id'] is not None else '-'
        error = esc(item['last_error']) or '-'
        rows.append(f'<tr><td><b>{esc(item["title"])}</b><small>{esc(item["event_id"])}</small></td><td><span class="badge {item["state"]}">{labels[item["state"]]}</span></td><td>{item["attempts"]}회</td><td>{retry}</td><td>{post}<small>{error}</small></td><td class="server">{destination}</td></tr>')
    body = ''.join(rows) or '<tr><td colspan="6">아직 감지된 사진이 없습니다.</td></tr>'
    return f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="5"><title>감지 사진 전송 현황</title><style>
      *{{box-sizing:border-box}}body{{margin:0;background:#f3f6fa;color:#17283b;font-family:"Malgun Gothic",sans-serif}}header{{background:#152f47;color:white;padding:26px 5vw}}header h1{{font-size:28px;margin:0 0 8px}}header p{{margin:0;color:#d1e0ec}}main{{max-width:1440px;margin:28px auto;padding:0 4vw}}.cards{{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}}.card{{background:white;border-radius:12px;padding:20px;border-top:5px solid #9ba9b7}}.card span{{display:block;color:#52677a}}.card strong{{display:block;font-size:40px;margin-top:8px}}.pending{{border-color:#c88918}}.inflight{{border-color:#347fba}}.sent{{border-color:#198263}}.failed{{border-color:#ba4747}}.toolbar{{display:flex;justify-content:space-between;align-items:center;margin:24px 0 16px;color:#52677a;font-size:14px}}a{{background:#183b55;color:white;padding:10px 16px;border-radius:7px;text-decoration:none}}.table{{overflow:auto;background:white;border-radius:12px}}table{{width:100%;border-collapse:collapse;font-size:14px}}th,td{{padding:16px 12px;text-align:left;border-bottom:1px solid #e4ebf1;vertical-align:top}}th{{background:#e9eff5;white-space:nowrap}}small{{display:block;font-size:11px;color:#6b7b89;margin-top:7px;white-space:nowrap}}.badge{{display:inline-block;border:1px solid #c9d6df;border-radius:5px;padding:4px 7px;white-space:nowrap}}.badge.sent{{background:#e4f5ef;color:#086747}}.badge.pending{{background:#fff1cf;color:#875400}}.badge.failed{{background:#ffe7e7;color:#942929}}.server{{font-size:12px;max-width:230px;overflow-wrap:anywhere}}footer{{margin:18px 0;color:#63778a;font-size:13px}}@media(max-width:700px){{.cards{{grid-template-columns:repeat(2,1fr)}}}}
    </style></head><body><header><h1>감지 사진 전송 현황</h1><p>연결이 끊겨도 보관된 사진을 확인하고, 복구 후 전송 결과를 확인할 수 있습니다.</p></header><main><div class="cards">{cards}</div><div class="toolbar"><span>갱신 {esc(data['checked_at'])} · 대기 사진 {data['pending_bytes']/1024:.1f} KB · 5초마다 새로고침</span><a href="/">새로고침</a></div><div class="table"><table><thead><tr><th>감지 내용 / 이벤트</th><th>상태</th><th>전송 시도</th><th>다음 시도</th><th>결과 / 마지막 오류</th><th>연결 서버</th></tr></thead><tbody>{body}</tbody></table></div><footer>확인 필요 항목은 인증 또는 입력 문제를 해결한 뒤 재시도할 수 있습니다. 최근 50개 기록을 표시합니다.</footer></main></body></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', type=Path, default=DEFAULT_DB)
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in ('/', '/api/status'):
                self.send_error(404)
                return
            try:
                data = snapshot(args.db)
            except sqlite3.Error:
                self.send_error(503, 'Queue is not ready')
                return
            content = (json.dumps(data, ensure_ascii=False, indent=2) if self.path == '/api/status' else render(data)).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', ('application/json' if self.path == '/api/status' else 'text/html') + '; charset=utf-8')
            self.send_header('Content-Length', str(len(content)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('X-Frame-Options', 'DENY')
            self.send_header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(content)

        def log_message(self, fmt, *values):
            print('%s %s' % (self.address_string(), fmt % values))

    print(f'Read-only queue status: http://127.0.0.1:{args.port}/', flush=True)
    ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
