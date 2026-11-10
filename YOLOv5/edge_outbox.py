"""Durable, bounded event queue for authenticated PhotoBlog uploads.

No credentials are stored in SQLite. Every retry retains the original UUID,
payload, timestamp and JPEG bytes, including after a process restart.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

import requests

LOG = logging.getLogger("edge.outbox")
DEFAULT_DB = Path(__file__).resolve().parent / "state" / "outbox.sqlite3"


def normalize_server(value: str) -> str:
    value = value.strip().rstrip("/")
    parsed = urlsplit(value)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("Server must be an http(s) URL without credentials, query or fragment")
    if parsed.scheme == 'http' and parsed.hostname.lower() not in ('localhost', '127.0.0.1', '::1', '10.0.2.2'):
        raise ValueError('Non-local servers require HTTPS to protect authentication credentials')
    return parsed.scheme + '://' + parsed.netloc.lower() + parsed.path.rstrip('/')


@dataclass(frozen=True)
class Connection:
    server: str
    token: str = ""
    username: str = ""
    password: str = ""

    @classmethod
    def from_env(cls, server=None):
        return cls(normalize_server(server or os.getenv("PHOTOBLOG_SERVER", "http://127.0.0.1:8000")),
                   os.getenv("PHOTOBLOG_TOKEN", ""), os.getenv("PHOTOBLOG_USERNAME", ""),
                   os.getenv("PHOTOBLOG_PASSWORD", ""))


class QueueFull(RuntimeError):
    pass


class Outbox:
    def __init__(self, path=DEFAULT_DB, *, clock=time.time, max_pending=500, max_bytes=128 * 1024 * 1024):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.clock, self.max_pending, self.max_bytes = clock, max_pending, max_bytes
        with self._db() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
              CREATE TABLE IF NOT EXISTS events (
                event_id TEXT PRIMARY KEY, server TEXT NOT NULL,
                title TEXT NOT NULL, text TEXT NOT NULL, captured_at TEXT NOT NULL,
                image BLOB NOT NULL, image_size INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'pending'
                  CHECK(state IN ('pending','inflight','sent','failed')),
                attempts INTEGER NOT NULL DEFAULT 0, next_attempt REAL NOT NULL DEFAULT 0,
                lease_until REAL NOT NULL DEFAULT 0, lease_token TEXT NOT NULL DEFAULT '',
                last_error TEXT NOT NULL DEFAULT '',
                post_id INTEGER, created_at REAL NOT NULL, sent_at REAL
              );
              CREATE INDEX IF NOT EXISTS event_due ON events(server,state,next_attempt);
            """)
            if 'lease_token' not in {row[1] for row in db.execute('PRAGMA table_info(events)')}:
                db.execute("ALTER TABLE events ADD COLUMN lease_token TEXT NOT NULL DEFAULT ''")

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=10000")
        db.execute("PRAGMA synchronous=FULL")
        try:
            with db:
                yield db
        finally:
            db.close()

    def enqueue(self, server, title, text, image, *, captured_at=None, event_id=None):
        server = normalize_server(server)
        event_id = str(uuid.UUID(event_id)) if event_id else str(uuid.uuid4())
        captured_at = captured_at or datetime.now(timezone.utc).isoformat()
        image = bytes(image)
        if not image or not title or len(title) > 200:
            raise ValueError("A JPEG payload and a title of 1..200 characters are required")
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            count, size = db.execute("SELECT COUNT(*),COALESCE(SUM(image_size),0) FROM events WHERE state != 'sent'").fetchone()
            if count >= self.max_pending or size + len(image) > self.max_bytes:
                raise QueueFull("Outbox limit reached; pending images were preserved. Retry or inspect the queue.")
            db.execute("INSERT INTO events(event_id,server,title,text,captured_at,image,image_size,created_at) VALUES(?,?,?,?,?,?,?,?)",
                       (event_id, server, title, text, captured_at, image, len(image), self.clock()))
        return event_id

    def claim(self, server, *, lease_seconds=60):
        now = self.clock()
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("UPDATE events SET state='pending',lease_until=0,lease_token='' WHERE state='inflight' AND lease_until<=?", (now,))
            row = db.execute("SELECT * FROM events WHERE server=? AND state='pending' AND next_attempt<=? ORDER BY created_at,event_id LIMIT 1",
                             (normalize_server(server), now)).fetchone()
            if row is None:
                return None
            db.execute("UPDATE events SET state='inflight',attempts=attempts+1,lease_until=?,lease_token=? WHERE event_id=?",
                       (now + lease_seconds, str(uuid.uuid4()), row['event_id']))
            return dict(db.execute("SELECT * FROM events WHERE event_id=?", (row['event_id'],)).fetchone())

    def sent(self, event, post_id):
        with self._db() as db:
            # A stale worker may not overwrite the outcome of a newer claim.
            updated = db.execute("UPDATE events SET state='sent',post_id=?,sent_at=?,lease_until=0,lease_token='',last_error='',image=X'',image_size=0 WHERE event_id=? AND state='inflight' AND lease_token=?",
                                 (int(post_id), self.clock(), event['event_id'], event['lease_token'])).rowcount
        return bool(updated)

    def failure(self, event, message, *, retryable, max_attempts=8, retry_after=0):
        retry = retryable and event['attempts'] < max_attempts
        # Local backoff is bounded; a server throttling delay may extend it up to one day.
        delay = max(min(300, 2 ** min(event['attempts'], 8)), min(86400, retry_after))
        state = 'pending' if retry else 'failed'
        with self._db() as db:
            updated = db.execute("UPDATE events SET state=?,next_attempt=?,lease_until=0,lease_token='',last_error=? WHERE event_id=? AND state='inflight' AND lease_token=?",
                                 (state, self.clock() + delay if retry else 0, message[:240], event['event_id'], event['lease_token'])).rowcount
        return state if updated else 'stale'

    def retry(self, server, event_id=None):
        # Explicit operator action after repairing connectivity or credentials.
        with self._db() as db:
            sql = "UPDATE events SET state='pending',attempts=0,next_attempt=0,lease_until=0,lease_token='',last_error='' WHERE server=? AND state IN ('pending','failed')"
            args = [normalize_server(server)]
            if event_id:
                sql += " AND event_id=?"
                args.append(str(uuid.UUID(event_id)))
            return db.execute(sql, args).rowcount

    def status(self, server=None):
        with self._db() as db:
            where, args = (" WHERE server=?", [normalize_server(server)]) if server else ("", [])
            rows = db.execute("SELECT event_id,server,title,state,attempts,next_attempt,last_error,post_id,image_size FROM events" + where + " ORDER BY created_at DESC LIMIT 50", args).fetchall()
            counts = {row[0]: row[1] for row in db.execute("SELECT state,COUNT(*) FROM events" + where + " GROUP BY state", args)}
        return {'counts': {key: counts.get(key, 0) for key in ('pending', 'inflight', 'sent', 'failed')},
                'events': [dict(row) for row in rows]}


class Uploader:
    def __init__(self, queue, connection, *, session=None, max_attempts=8):
        self.queue, self.connection = queue, connection
        self.session = session or requests.Session()
        self.max_attempts = max_attempts
        self.token = connection.token

    def _authenticate(self):
        if self.token:
            return
        if not self.connection.username or not self.connection.password:
            raise PermissionError("Set PHOTOBLOG_TOKEN or PHOTOBLOG_USERNAME and PHOTOBLOG_PASSWORD")
        response = self.session.post(self.connection.server + '/api-token-auth/',
            data={'username': self.connection.username, 'password': self.connection.password},
            timeout=(5, 15), allow_redirects=False)
        if response.status_code >= 500 or response.status_code in (408, 425, 429):
            raise requests.ConnectionError("Authentication service temporarily unavailable")
        if response.status_code != 200:
            raise PermissionError("Authentication rejected; check credentials")
        self.token = response.json()['token']

    def flush_once(self):
        event = self.queue.claim(self.connection.server)
        if event is None:
            return None
        try:
            self._authenticate()
            response = self.session.post(self.connection.server + '/api_root/Post/',
                data={'title': event['title'], 'text': event['text'], 'published_date': event['captured_at']},
                files={'image': (event['event_id'] + '.jpg', event['image'], 'image/jpeg')},
                headers={'Authorization': 'Token ' + self.token, 'Accept': 'application/json',
                         'Idempotency-Key': event['event_id']}, timeout=(5, 15), allow_redirects=False)
            if response.status_code in (200, 201):
                post_id = int(response.json()['id'])
                if not self.queue.sent(event, post_id):
                    LOG.info('STALE event=%s result ignored after lease expiration', event['event_id'])
                    return {'event_id': event['event_id'], 'state': 'stale'}
                LOG.info("SENT event=%s post=%s replay=%s http=%s", event['event_id'], post_id,
                         response.headers.get('Idempotency-Replayed', 'unknown'), response.status_code)
                return {'event_id': event['event_id'], 'state': 'sent', 'post_id': post_id}
            retryable = response.status_code in (408, 425, 429) or response.status_code >= 500
            try:
                retry_after = max(0, float(response.headers.get('Retry-After', 0)))
            except ValueError:
                retry_after = 0
            message = 'HTTP ' + str(response.status_code)
        except PermissionError as error:
            message, retryable, retry_after = str(error), False, 0
        except (requests.RequestException, ValueError, KeyError, TypeError) as error:
            # Do not persist response bodies, URLs containing secrets or exception request headers.
            message, retryable, retry_after = type(error).__name__, True, 0
        state = self.queue.failure(event, message, retryable=retryable,
                                   max_attempts=self.max_attempts, retry_after=retry_after)
        LOG.warning("%s event=%s attempt=%s reason=%s", state.upper(), event['event_id'], event['attempts'], message)
        return {'event_id': event['event_id'], 'state': state, 'error': message}

    def drain(self, limit=100):
        results = []
        for _ in range(limit):
            result = self.flush_once()
            if result is None:
                break
            results.append(result)
        return results


class UploadWorker:
    CLOSE_MAX_EVENTS = 8

    def __init__(self, uploader, *, poll_interval=1):
        self.uploader = uploader
        self.poll_interval = poll_interval
        self.stop_event = threading.Event()
        self.wake_event = threading.Event()
        self.close_deadline = 0
        self.thread = threading.Thread(target=self._run, name='durable-edge-uploader', daemon=True)

    def start(self):
        self.thread.start()
        return self

    def notify_committed(self):
        self.wake_event.set()

    def _attempt(self):
        try:
            return self.uploader.flush_once()
        except Exception:
            LOG.exception("Outbox worker paused; committed events remain on disk")
            return None

    def _run(self):
        while not self.stop_event.is_set():
            self.wake_event.clear()
            if self.stop_event.is_set():
                break
            result = self._attempt()
            if not result or result['state'] != 'sent':
                self.wake_event.wait(self.poll_interval)

        # A short input can finish between the idle poll and the first upload.
        # Let this same worker (and Session) attempt already-due records before
        # exit; don't override backoff or wait through an entire offline queue.
        for _ in range(self.CLOSE_MAX_EVENTS):
            if time.monotonic() >= self.close_deadline:
                break
            result = self._attempt()
            if not result or result['state'] != 'sent':
                break

    def close(self, grace_seconds=5):
        self.close_deadline = time.monotonic() + grace_seconds
        self.stop_event.set()
        self.wake_event.set()
        self.thread.join(timeout=grace_seconds)
        if self.thread.is_alive():
            LOG.warning('Shutdown deadline reached; the in-flight event remains recoverable')


def main():
    parser = argparse.ArgumentParser(description='Persistent YOLO event queue: inspect and retry uploads')
    parser.add_argument('command', choices=('status', 'drain', 'retry'))
    parser.add_argument('--db', type=Path, default=Path(os.getenv('PHOTOBLOG_OUTBOX', DEFAULT_DB)))
    parser.add_argument('--server', default=os.getenv('PHOTOBLOG_SERVER', 'http://127.0.0.1:8000'))
    parser.add_argument('--event', help='Retry one event UUID instead of every pending/failed event')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(levelname)s %(message)s')
    queue, connection = Outbox(args.db), Connection.from_env(args.server)
    if args.command == 'retry':
        print(json.dumps({'scheduled_for_retry': queue.retry(connection.server, args.event)}))
    if args.command in ('retry', 'drain'):
        print(json.dumps({'uploads': Uploader(queue, connection).drain()}, ensure_ascii=False))
    print(json.dumps(queue.status(connection.server), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
