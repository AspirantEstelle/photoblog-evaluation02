import tempfile
import threading
import unittest
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np
import requests

from changedetection import ChangeDetection
from edge_outbox import Connection, Outbox, QueueFull, Uploader, UploadWorker, normalize_server
from outbox_status import snapshot, render


class Response:
    def __init__(self, status=201, post_id=7, headers=None):
        self.status_code, self.post_id = status, post_id
        self.headers = headers or {}

    def json(self):
        return {'id': self.post_id}


class Transport:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = []

    def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


class EdgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.time = 1000.0
        self.queue = Outbox(self.root / 'queue.sqlite3', clock=lambda: self.time)
        self.conn = Connection('http://127.0.0.1:8000', 'test-only-token')
        self.image = cv2.imencode('.jpg', np.zeros((24, 32, 3), dtype=np.uint8))[1].tobytes()

    def enqueue(self, queue=None, **kwargs):
        return (queue or self.queue).enqueue(self.conn.server, 'person', 'person appeared', self.image, **kwargs)

    def idle_worker(self, uploader):
        idle = threading.Event()
        original = uploader.flush_once
        def observed_attempt():
            result = original()
            if result is None:
                idle.set()
            return result
        uploader.flush_once = observed_attempt
        worker = UploadWorker(uploader, poll_interval=60).start()
        self.addCleanup(worker.close)
        self.assertTrue(idle.wait(3), 'Worker did not reach an empty queue')
        return worker

    def test_new_detection_wakes_an_idle_worker_immediately(self):
        posted = threading.Event()
        class RecordingTransport(Transport):
            def post(inner, url, **kwargs):
                response = super().post(url, **kwargs)
                posted.set()
                return response
        with patch.dict(os.environ, {'PHOTOBLOG_TOKEN': self.conn.token}):
            detector = ChangeDetection(['person'], server=self.conn.server,
                                       outbox=self.root / 'normal.sqlite3', start_worker=False)
        transport = RecordingTransport(Response(201))
        detector.uploader.session = transport
        detector.worker = self.idle_worker(detector.uploader)
        eid = detector.add(['person'], [1], self.root, np.zeros((32,32,3), dtype=np.uint8))
        self.assertTrue(posted.wait(3), 'Committed event waited for the 60-second idle poll')
        detector.close()
        self.assertEqual(transport.calls[0][1]['headers']['Idempotency-Key'], eid)
        self.assertEqual(detector.queue.status()['counts']['sent'], 1)

    def test_close_attempts_ready_events_but_does_not_drain_an_arbitrary_backlog(self):
        transport = Transport(*[Response(201) for _ in range(UploadWorker.CLOSE_MAX_EVENTS)])
        worker = self.idle_worker(Uploader(self.queue, self.conn, session=transport))
        for _ in range(UploadWorker.CLOSE_MAX_EVENTS + 2):
            self.enqueue()
        worker.close()
        counts = self.queue.status()['counts']
        self.assertEqual(counts['sent'], UploadWorker.CLOSE_MAX_EVENTS)
        self.assertEqual(counts['pending'], 2)

    def test_offline_close_preserves_backoff_and_stops_after_first_failure(self):
        transport = Transport(requests.ConnectionError('offline'))
        worker = self.idle_worker(Uploader(self.queue, self.conn, session=transport))
        for _ in range(3):
            self.enqueue()
        worker.close()
        events = self.queue.status()['events']
        self.assertEqual(len(transport.calls), 1)
        self.assertEqual(sum(event['attempts'] for event in events), 1)
        self.assertTrue(all(event['state'] == 'pending' and event['image_size'] for event in events))
        self.assertEqual(max(event['next_attempt'] for event in events), self.time + 2)

    def test_shutdown_deadline_preserves_an_inflight_upload_without_second_sender(self):
        entered, release = threading.Event(), threading.Event()
        class SlowTransport(Transport):
            def post(inner, url, **kwargs):
                entered.set()
                release.wait(4)
                return super().post(url, **kwargs)
        transport = SlowTransport(Response(201))
        worker = self.idle_worker(Uploader(self.queue, self.conn, session=transport))
        try:
            self.enqueue(); worker.notify_committed()
            self.assertTrue(entered.wait(3))
            worker.close(grace_seconds=0.05)
            self.assertTrue(worker.thread.is_alive())
            row = self.queue.status()['events'][0]
            self.assertEqual(row['state'], 'inflight'); self.assertGreater(row['image_size'], 0)
        finally:
            release.set(); worker.thread.join(3)
        self.assertEqual(len(transport.calls), 1)
        self.assertEqual(self.queue.status()['counts']['sent'], 1)

    def test_unavailable_optional_archive_still_preserves_detection_image(self):
        detector = ChangeDetection(['person'], outbox=self.root / 'archive.sqlite3', queue_only=True)
        blocked = self.root / 'not-a-directory'; blocked.write_bytes(b'occupied')
        image = np.zeros((24,32,3), dtype=np.uint8)
        event = detector.add(['person'], [1], blocked, image)
        self.assertIsNotNone(event)
        self.assertIsNone(detector.add(['person'], [1], blocked, image))
        row = detector.queue.claim(detector.connection.server)
        self.assertEqual(row['event_id'], event)
        self.assertEqual(cv2.imdecode(np.frombuffer(row['image'], np.uint8), cv2.IMREAD_COLOR).shape[:2], (240,320))

    def test_class_appearance_repeated_presence_disappearance_and_reappearance(self):
        detector = ChangeDetection(['person', 'cat'], outbox=self.root / 'change.sqlite3', queue_only=True)
        frame = np.zeros((48, 64, 3), dtype=np.uint8)
        add = lambda values: detector.add(detector.names, values, self.root / 'runs', frame)
        self.assertIsNone(add([0, 0]))
        first = add([1, 0]); self.assertIsNotNone(first)
        self.assertIsNone(add([1, 0]))
        self.assertIsNone(add([0, 0]))
        second = add([1, 1]); self.assertNotEqual(first, second)
        self.assertEqual(detector.queue.status()['counts']['pending'], 2)
        self.assertIn('person, cat', detector.queue.status()['events'][0]['text'] if 'text' in detector.queue.status()['events'][0] else detector.queue.status()['events'][0]['title'])
        with detector.queue._db() as db:
            row = db.execute('SELECT image FROM events LIMIT 1').fetchone()
            decoded = cv2.imdecode(np.frombuffer(row[0], dtype=np.uint8), cv2.IMREAD_COLOR)
            self.assertEqual(decoded.shape[:2], (240, 320))

    def test_camera_histories_are_independent(self):
        detector = ChangeDetection(['person'], outbox=self.root / 'cameras.sqlite3', queue_only=True)
        frame = np.zeros((10, 10, 3), dtype=np.uint8)
        for source in ['a', 'b']:
            self.assertIsNotNone(detector.add(['person'], [1], self.root, frame, source=source))
        self.assertIsNone(detector.add(['person'], [1], self.root, frame, source='a'))

    def test_restart_preserves_uuid_timestamp_and_jpeg_payload(self):
        event_id = self.enqueue(captured_at='2026-11-05T01:02:03+00:00')
        restarted = Outbox(self.queue.path, clock=lambda: self.time)
        event = restarted.claim(self.conn.server)
        self.assertEqual((event['event_id'], event['captured_at'], event['image']),
                         (event_id, '2026-11-05T01:02:03+00:00', self.image))

    def test_network_failure_is_delayed_then_replayed_with_identical_key_payload(self):
        self.enqueue()
        transport = Transport(requests.ConnectionError('offline'), Response(200, headers={'Idempotency-Replayed': 'true'}))
        uploader = Uploader(self.queue, self.conn, session=transport)
        self.assertEqual(uploader.flush_once()['state'], 'pending')
        self.assertIsNone(uploader.flush_once())
        self.time += 2
        self.assertEqual(uploader.flush_once()['state'], 'sent')
        first, second = [call[1] for call in transport.calls]
        self.assertEqual(first['headers']['Idempotency-Key'], second['headers']['Idempotency-Key'])
        self.assertEqual(first['data'], second['data'])
        self.assertEqual(first['files'], second['files'])
        self.assertEqual(self.queue.status()['counts']['sent'], 1)

    def test_expired_worker_lease_recovers_after_crash(self):
        event_id = self.enqueue()
        self.assertEqual(self.queue.claim(self.conn.server)['event_id'], event_id)
        restarted = Outbox(self.queue.path, clock=lambda: self.time)
        self.assertIsNone(restarted.claim(self.conn.server))
        self.time += 61
        event = restarted.claim(self.conn.server)
        self.assertEqual((event['event_id'], event['attempts']), (event_id, 2))

    def test_two_workers_do_not_claim_same_event(self):
        self.enqueue()
        other = Outbox(self.queue.path, clock=lambda: self.time)
        barrier = threading.Barrier(2)
        def claim(queue):
            barrier.wait()
            return queue.claim(self.conn.server)
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(claim, [self.queue, other]))
        self.assertEqual(sum(item is not None for item in results), 1)

    def test_old_worker_cannot_override_new_lease_result(self):
        self.enqueue()
        first = self.queue.claim(self.conn.server)
        self.time += 61
        second = self.queue.claim(self.conn.server)
        self.queue.sent(second, 9)
        self.queue.failure(first, 'late error', retryable=True)
        self.assertEqual(self.queue.status()['counts']['sent'], 1)

    def test_expired_worker_cannot_modify_explicitly_retried_event(self):
        event_id = self.enqueue()
        old = self.queue.claim(self.conn.server)
        self.time += 61
        newer = self.queue.claim(self.conn.server)
        self.queue.failure(newer, 'HTTP 401', retryable=False)
        self.queue.retry(self.conn.server, event_id)
        current = self.queue.claim(self.conn.server)
        self.assertEqual(old['attempts'], current['attempts'])
        self.assertNotEqual(old['lease_token'], current['lease_token'])
        self.queue.failure(old, 'late failure', retryable=False)
        self.queue.sent(old, 999)
        self.assertEqual(self.queue.status()['counts']['inflight'], 1)
        self.queue.sent(current, 5)
        self.assertEqual(self.queue.status()['events'][0]['post_id'], 5)

    def test_external_http_and_embedded_credentials_are_rejected(self):
        for server in ['http://example.com', 'https://user:secret@example.com', 'https://example.com?token=secret']:
            with self.subTest(server=server), self.assertRaises(ValueError):
                normalize_server(server)
        self.assertEqual(normalize_server('https://EXAMPLE.com/'), 'https://example.com')

    def test_queue_size_limit_preserves_existing_event(self):
        queue = Outbox(self.root / 'small.sqlite3', max_pending=1)
        first = self.enqueue(queue)
        with self.assertRaises(QueueFull):
            self.enqueue(queue)
        self.assertEqual(queue.status()['events'][0]['event_id'], first)
        tiny = Outbox(self.root / 'tiny.sqlite3', max_bytes=len(self.image)-1)
        with self.assertRaises(QueueFull):
            self.enqueue(tiny)

    def test_permanent_http_failures_require_explicit_retry(self):
        for code in [400, 401, 403, 409, 410, 302]:
            with self.subTest(code=code):
                self.enqueue()
                uploader = Uploader(self.queue, self.conn, session=Transport(Response(code)))
                self.assertEqual(uploader.flush_once()['state'], 'failed')
        self.assertEqual(self.queue.status()['counts']['failed'], 6)
        event_id = self.queue.status()['events'][0]['event_id']
        self.assertEqual(self.queue.retry(self.conn.server, event_id), 1)
        self.assertEqual(self.queue.status()['counts']['pending'], 1)

    def test_transient_http_retry_and_attempt_limit(self):
        self.enqueue()
        uploader = Uploader(self.queue, self.conn, session=Transport(Response(503), Response(429)), max_attempts=2)
        self.assertEqual(uploader.flush_once()['state'], 'pending')
        self.time += 2
        self.assertEqual(uploader.flush_once()['state'], 'failed')

    def test_retry_after_is_bounded(self):
        self.enqueue()
        Uploader(self.queue, self.conn, session=Transport(Response(429, headers={'Retry-After': '99999'}))).flush_once()
        self.assertEqual(self.queue.status()['events'][0]['next_attempt'], self.time + 86400)

    def test_success_releases_blob_and_server_queues_are_isolated(self):
        self.enqueue()
        self.queue.enqueue('https://example.invalid', 'other', 'other', self.image)
        Uploader(self.queue, self.conn, session=Transport(Response())).flush_once()
        self.assertIsNone(self.queue.claim(self.conn.server))
        with self.queue._db() as db:
            self.assertEqual(db.execute("SELECT length(image) FROM events WHERE state='sent'").fetchone()[0], 0)
        self.assertEqual(self.queue.status()['counts']['pending'], 1)

    def test_status_page_reads_real_state_and_escapes_text(self):
        self.queue.enqueue(self.conn.server, '<script>alert(1)</script>', 'test', self.image)
        data = snapshot(self.queue.path)
        self.assertEqual(data['counts']['pending'], 1)
        self.assertEqual(data['pending_bytes'], len(self.image))
        page = render(data)
        self.assertIn('&lt;script&gt;', page)
        self.assertNotIn('<script>alert(1)</script>', page)
        self.assertNotIn(self.conn.token, page)


if __name__ == '__main__':
    unittest.main(verbosity=2)
