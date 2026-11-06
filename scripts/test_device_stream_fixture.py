"""Isolated real-wire H.264 fixture tests; no T3 instance or real threads."""
import http.client
import importlib.util
import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import time
import unittest


class DeviceStreamFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).with_name('device-stream-fixture.py')
        if not path.exists():
            raise AssertionError('Device H.264 fixture must exist before native lifecycle testing')
        spec = importlib.util.spec_from_file_location('device_stream_fixture', path)
        cls.fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fixture)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.stats_file = Path(self.directory.name) / 'stats.json'
        self.state = self.fixture.DeviceFixtureState(self.stats_file)
        self.server = self.fixture.FixtureServer(('127.0.0.1', 0), self.fixture.Handler)
        self.server.fixture = self.state
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
        self.thread.start()
        self.clients = []

    def tearDown(self):
        for sock, stream in self.clients:
            stream.close()
            sock.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.directory.cleanup()

    def connect(self, query='frame-meta=1'):
        sock = socket.create_connection(('127.0.0.1', self.server.server_port), timeout=3)
        stream = sock.makefile('rb')
        sock.sendall((f'GET /api/device-hub/vendor/serve-emu/ws?device=isolated&wsTicket=never-save-ticket&{query} HTTP/1.1\r\n'
                      f'Host: 127.0.0.1:{self.server.server_port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                      'Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n').encode())
        self.assertTrue(stream.readline().startswith(b'HTTP/1.1 101'))
        while stream.readline() != b'\r\n':
            pass
        self.clients.append((sock, stream))
        return sock, stream

    def read(self, stream):
        first, second = self.fixture.read_exact(stream, 2)
        size = second & 127
        if size == 126:
            size = struct.unpack('!H', self.fixture.read_exact(stream, 2))[0]
        elif size == 127:
            size = struct.unpack('!Q', self.fixture.read_exact(stream, 8))[0]
        return first & 15, self.fixture.read_exact(stream, size)

    def send(self, sock, value, opcode=1):
        payload = json.dumps(value).encode() if isinstance(value, dict) else value
        self.assertLess(len(payload), 126)
        mask = b'abcd'
        sock.sendall(bytes((128 | opcode, 128 | len(payload))) + mask +
                     bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload)))

    def wait_counter(self, name, expected):
        deadline = time.monotonic() + 2
        while self.state.snapshot()[name] != expected and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertEqual(expected, self.state.snapshot()[name])

    def test_android_routes_deliver_sps_pps_idr_with_semu_timestamp(self):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        connection.request('GET', '/api/device-hub/vendor/serve-emu/api?device=isolated&wsTicket=never-save-ticket')
        response = connection.getresponse()
        self.assertEqual(200, response.status)
        self.assertEqual({}, json.loads(response.read()))
        connection.close()
        sock, stream = self.connect()
        opcode, data = self.read(stream)
        self.assertEqual((1, {'type': 'video-session'}), (opcode, json.loads(data)))
        opcode, data = self.read(stream)
        self.assertEqual(2, opcode)
        self.assertEqual(b'SEMU\x01\x01\x00\x00', data[:8])
        self.assertEqual(0, struct.unpack('!Q', data[8:16])[0])
        self.assertTrue({7, 8, 5}.issubset(self.fixture.nal_types(data[16:])))
        opcode, delta = self.read(stream)
        self.assertEqual(2, opcode)
        self.assertEqual(0, delta[5])
        self.assertEqual(100000, struct.unpack('!Q', delta[8:16])[0])
        self.assertIn(1, self.fixture.nal_types(delta[16:]))
        self.wait_counter('activeVideoConnections', 1)
        self.wait_counter('activeControlConnections', 1)

    def test_reset_restarts_keyframe_and_close_releases_connections(self):
        sock, stream = self.connect()
        self.read(stream)
        self.read(stream)
        self.send(sock, {'type': 'reset-video', 'ack': False})
        self.wait_counter('resetRequests', 1)
        # An in-flight frame may precede the reset; the next GOP starts within 200ms.
        for _ in range(3):
            opcode, data = self.read(stream)
            if opcode == 2 and data[5] & 1:
                break
        else:
            self.fail('reset-video did not emit a fresh Annex B keyframe')
        self.send(sock, {'type': 'text', 'text': 'never-save-input'})
        self.wait_counter('inputs', 1)
        self.send(sock, struct.pack('!H', 1000), opcode=8)
        for _ in range(3):
            if self.read(stream)[0] == 8:
                break
        self.wait_counter('activeVideoConnections', 0)
        self.wait_counter('activeControlConnections', 0)
        stats = json.loads(self.stats_file.read_text())
        self.assertTrue(all(isinstance(value, int) for value in stats.values()))
        self.assertNotIn('never-save', self.stats_file.read_text())

    def test_control_only_socket_never_starts_video(self):
        sock, stream = self.connect('video=0')
        self.wait_counter('activeControlConnections', 1)
        self.assertEqual(0, self.state.snapshot()['activeVideoConnections'])
        self.assertEqual(0, self.state.snapshot()['framesSent'])
        self.send(sock, b'ping', opcode=9)
        self.assertEqual((10, b'ping'), self.read(stream))
        self.send(sock, b'', opcode=8)
        self.assertEqual(8, self.read(stream)[0])
        self.wait_counter('activeControlConnections', 0)

    def test_close_during_startup_delay_emits_no_frames(self):
        self.state.first_frame_delay = 1
        sock, stream = self.connect()
        self.assertEqual(1, self.read(stream)[0])
        self.send(sock, b'', opcode=8)
        self.assertEqual(8, self.read(stream)[0])
        self.wait_counter('activeVideoConnections', 0)
        self.wait_counter('activeControlConnections', 0)
        self.assertEqual(0, self.state.snapshot()['framesSent'])


if __name__ == '__main__':
    unittest.main()
