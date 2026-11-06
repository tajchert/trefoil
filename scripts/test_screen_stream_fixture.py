"""Run with: python3 -m unittest discover -s scripts -p 'test_screen_stream_fixture.py'."""

import base64
import http.client
import importlib.util
import io
import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import time
import unittest

spec = importlib.util.spec_from_file_location("screen_stream_fixture", Path(__file__).with_name("screen-stream-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class WireClient:
    def __init__(self, port):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=3)
        self.stream = self.socket.makefile("rb")
        self.socket.sendall((
            "GET /api/preview-stream/ws?threadId=isolated&tabId=fixture&wsTicket=never-store-this-ticket HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{port}\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
        ).encode())
        status = self.stream.readline()
        if not status.startswith(b"HTTP/1.1 101"):
            raise AssertionError("Fixture rejected a valid WebSocket upgrade")
        headers = {}
        while True:
            line = self.stream.readline()
            if line == b"\r\n":
                break
            key, value = line.decode().split(":", 1)
            headers[key.lower()] = value.strip()
        if headers.get("sec-websocket-accept") != "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=":
            raise AssertionError("Fixture produced a non-RFC6455 handshake")

    def read(self):
        first, second = self.stream.read(2)
        if second & 128:
            raise AssertionError("Server frames must not be masked")
        length = second & 127
        if length == 126:
            length = struct.unpack("!H", self.stream.read(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self.stream.read(8))[0]
        payload = self.stream.read(length)
        return first & 15, json.loads(payload) if first & 15 == 1 else payload

    def send(self, message, opcode=1, final=True):
        payload = json.dumps(message).encode() if isinstance(message, dict) else message
        # Fixed mask for deterministic protocol fixtures, unrelated to production.
        mask = b"\x37\xfa\x21\x3d"
        length = len(payload)
        if length < 126:
            header = bytes(((128 if final else 0) | opcode, 128 | length))
        else:
            header = bytes(((128 if final else 0) | opcode, 128 | 126)) + struct.pack("!H", length)
        self.socket.sendall(header + mask + bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))

    def close(self):
        try:
            self.send(struct.pack("!H", 1000), opcode=8)
        finally:
            self.stream.close()
            self.socket.close()


class ScreenStreamFixtureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.stats_file = Path(self.directory.name) / "stats.json"
        self.state = fixture.FixtureState(self.stats_file)
        self.server = fixture.FixtureServer(("127.0.0.1", 0), fixture.Handler)
        self.server.fixture = self.state
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.clients = []

    def tearDown(self):
        for client in self.clients:
            client.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.directory.cleanup()

    def client(self):
        client = WireClient(self.server.server_port)
        self.clients.append(client)
        return client

    def assert_counter(self, name, value):
        deadline = time.monotonic() + 2
        while self.state.snapshot()[name] != value and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertEqual(value, self.state.snapshot()[name])

    def initial_frames(self, client):
        self.assertEqual((1, {"type": "control", "canOperate": True, "controller": "you", "generation": 1, "dialog": None}), client.read())
        self.assertEqual((1, {"type": "viewport", "width": 540, "height": 960}), client.read())
        opcode, jpeg = client.read()
        self.assertEqual(2, opcode)
        self.assertTrue(jpeg.startswith(b"\xff\xd8"))
        self.assertTrue(jpeg.endswith(b"\xff\xd9"))
        self.assertGreater(len(jpeg), 1000)

    def test_handshake_jpeg_ack_and_demand_frame(self):
        client = self.client()
        self.initial_frames(client)
        client.send({"type": "ack"})
        self.assert_counter("acks", 1)
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request("POST", "/fixture/frame")
        response = connection.getresponse()
        self.assertEqual(200, response.status)
        response.read()
        self.assertEqual(2, client.read()[0])
        self.assert_counter("framesSent", 2)
        connection.close()

    def test_resize_probe_and_input_only_persist_counters(self):
        client = self.client()
        self.initial_frames(client)
        client.send({"type": "resize", "width": 390, "height": 780})
        self.assertEqual((1, {"type": "viewport", "width": 390, "height": 780}), client.read())
        self.assertEqual(2, client.read()[0])
        client.send({"type": "probe", "x": 100, "y": 320})
        self.assertEqual((1, {"type": "probe", "x": 100, "y": 320, "editable": True}), client.read())
        client.send({"type": "probe", "x": 100, "y": 600})
        self.assertEqual((1, {"type": "probe", "x": 100, "y": 600, "editable": False}), client.read())
        for message, counter in (
            ({"type": "mouse", "action": "down", "x": 100, "y": 320}, "mouseDown"),
            ({"type": "mouse", "action": "up", "x": 100, "y": 320}, "mouseUp"),
            ({"type": "wheel", "x": 100, "y": 320, "deltaX": 0, "deltaY": 50}, "wheel"),
            ({"type": "text", "text": "never-store-this-input"}, "text"),
            ({"type": "navigate", "url": "https://never-store-this-url.test"}, "navigate"),
        ):
            client.send(message)
            self.assertEqual(2, client.read()[0])
            self.assert_counter(counter, 1)
        stored = self.stats_file.read_text()
        for sensitive in ("never-store-this-ticket", "never-store-this-input", "never-store-this-url"):
            self.assertNotIn(sensitive, stored)
        self.assert_counter("resize", 1)
        self.assert_counter("probe", 2)

    def test_fragmented_text_and_ping_do_not_break_ack_processing(self):
        client = self.client()
        self.initial_frames(client)
        client.send(b'{"type":', final=False)
        client.send(b"ping", opcode=9)
        self.assertEqual((10, b"ping"), client.read())
        client.send(b'"ack"}', opcode=0)
        self.assert_counter("acks", 1)
        self.assertEqual(0, self.state.snapshot()["protocolErrors"])

    def test_rejects_unmasked_clients_without_recording_payload(self):
        client = self.client()
        self.initial_frames(client)
        client.socket.sendall(b'\x81\x02{}')
        self.assertEqual((8, struct.pack("!H", 1002)), client.read())
        self.assert_counter("protocolErrors", 1)

    def test_rfc6455_handshake_and_parser_limits(self):
        self.assertEqual("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", fixture.websocket_accept("dGhlIHNhbXBsZSBub25jZQ=="))
        with self.assertRaises(ValueError):
            fixture.websocket_accept(base64.b64encode(b"short").decode())
        oversized = b"\x81\xff" + struct.pack("!Q", fixture.MAX_MESSAGE_BYTES + 1)
        with self.assertRaises(ValueError):
            fixture.read_client_frame(io.BytesIO(oversized))
        with self.assertRaises(ValueError):
            fixture.read_client_frame(io.BytesIO(b"\x09\x80"))


if __name__ == "__main__":
    unittest.main()
