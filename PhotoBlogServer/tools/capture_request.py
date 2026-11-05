"""Capture ONE anonymous Android GET request and proxy it to local Django.

Run before synchronizing against http://10.0.2.2:8001.
Only loopback is bound; authorization-bearing requests are deliberately refused.
"""
import datetime
import socket
from pathlib import Path

out = Path(__file__).resolve().parents[2] / 'evidence'
out.mkdir(exist_ok=True)
with socket.socket() as server:
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('127.0.0.1', 8001))
    server.listen(1)
    print('Waiting for Android GET on 127.0.0.1:8001', flush=True)
    client, _ = server.accept()
    with client:
        client.settimeout(15)
        request = b''
        while b'\r\n\r\n' not in request:
            chunk = client.recv(8192)
            if not chunk or len(request) + len(chunk) > 65536:
                raise ValueError('Invalid or oversized request')
            request += chunk
        if not request.startswith(b'GET ') or b'authorization:' in request.lower():
            raise ValueError('Only anonymous GET requests may be recorded')
        filename = datetime.datetime.now().strftime('%Y-%m-%d-%H-%M-%S') + '.bin'
        (out / filename).write_bytes(request)
        (out / 'android-request.txt').write_text(request.decode('iso-8859-1').replace('\r\n', '\n'), encoding='utf-8')
        with socket.create_connection(('127.0.0.1', 8000), timeout=15) as django:
            forwarded = request.replace(b'Host: 10.0.2.2:8001', b'Host: 10.0.2.2:8000')
            forwarded = forwarded.replace(b'Connection: Keep-Alive', b'Connection: close')
            django.sendall(forwarded)
            while chunk := django.recv(65536):
                client.sendall(chunk)
        print(f'Saved real request: {filename}')
