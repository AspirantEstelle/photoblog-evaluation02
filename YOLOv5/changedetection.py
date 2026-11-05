"""Ch08 class-appearance detector with a durable background upload queue."""
import logging
import os
from datetime import datetime, timezone
from pathlib import Path

import cv2

from edge_outbox import Connection, DEFAULT_DB, Outbox, Uploader, UploadWorker

LOG = logging.getLogger('edge.changes')


class ChangeDetection:
    def __init__(self, names, *, server=None, outbox=None, queue_only=False, start_worker=True):
        self.names = names
        self.previous = {}
        self.connection = Connection.from_env(server)
        self.queue = Outbox(outbox or os.getenv('PHOTOBLOG_OUTBOX', DEFAULT_DB))
        self.uploader = Uploader(self.queue, self.connection)
        self.worker = None
        if not queue_only:
            if not self.connection.token and not (self.connection.username and self.connection.password):
                raise ValueError('Set PHOTOBLOG_TOKEN or PHOTOBLOG_USERNAME/PHOTOBLOG_PASSWORD; use --queue-only to capture offline')
            if start_worker:
                self.worker = UploadWorker(self.uploader).start()
        self.queue_only = queue_only

    def add(self, names, detected_current, save_dir, image, *, source='default'):
        """Only 0 -> 1 changes create events; disappearance is just remembered.

        Separate stream histories prevent one camera from resetting another.
        We advance state only after the new event has been committed to SQLite.
        """
        if len(detected_current) != len(self.names):
            raise ValueError('The presence vector must contain every model class')
        current = [int(bool(value)) for value in detected_current]
        previous = self.previous.get(str(source), [0] * len(current))
        appeared = [str(names[i]) for i, value in enumerate(current) if value and not previous[i]]
        event_id = None
        if appeared:
            event_id = self.send(save_dir, image, appeared)
        self.previous[str(source)] = current.copy()
        return event_id

    def send(self, save_dir, image, appeared):
        now = datetime.now(timezone.utc)
        folder = Path(save_dir) / 'detected' / str(now.year) / f'{now.month:02d}' / f'{now.day:02d}'
        folder.mkdir(parents=True, exist_ok=True)
        # The lecture sends the annotated frame at 320 x 240.
        frame = cv2.resize(image, (320, 240), interpolation=cv2.INTER_AREA)
        ok, encoded = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 92])
        if not ok:
            raise RuntimeError('Could not encode the annotated detection image')
        title = ('YOLO 감지: ' + ', '.join(appeared))[:200]
        text = '새로 출현한 객체: ' + ', '.join(appeared)
        event_id = self.queue.enqueue(self.connection.server, title, text, encoded.tobytes(),
                                      captured_at=now.isoformat())
        # This archive is a convenience copy; SQLite already owns the durable payload.
        try:
            (folder / (event_id + '.jpg')).write_bytes(encoded.tobytes())
        except OSError:
            LOG.warning('Archive copy unavailable; event %s remains safely queued', event_id)
        LOG.info('QUEUED event=%s classes=%s', event_id, ','.join(appeared))
        return event_id

    def close(self):
        if self.worker:
            self.worker.close()
            self.worker = None
        # Any remaining committed events are resumed by the next run or CLI drain.
        LOG.info('OUTBOX %s', self.queue.status(self.connection.server)['counts'])
