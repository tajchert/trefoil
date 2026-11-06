#!/usr/bin/env python3
"""Isolated, loopback-only T3 browser stream fixture (Python standard library).

python3 scripts/screen-stream-fixture.py --port 0 \
    --port-file /tmp/trefoil-screen-port --stats-file /tmp/trefoil-screen-stats.json

Forward its chosen port with `adb reverse tcp:PORT tcp:PORT` before opening the
Android debug viewer. The fixture contains only a synthetic page, never contacts
T3, and never records input values, URLs, query strings, tickets, or credentials.
GET /fixture/stats returns counters; POST /fixture/frame emits another JPEG.
"""

import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MAX_MESSAGE_BYTES = 256 * 1024
FRAME_WIDTH, FRAME_HEIGHT = 540, 960
JPEG_PATH = Path(__file__).with_name("fixtures") / "screen-stream.jpg"


def atomic_write(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", delete=False) as output:
        temporary = output.name
        output.write(data)
    os.replace(temporary, path)


def websocket_accept(key):
    """RFC 6455 server handshake; malformed keys never enter the upgrade path."""
    if len(base64.b64decode(key, validate=True)) != 16:
        raise ValueError("Invalid WebSocket handshake")
    digest = hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def read_exact(stream, size):
    data = bytearray()
    while len(data) < size:
        part = stream.read(size - len(data))
        if not part:
            raise EOFError
        data.extend(part)
    return bytes(data)


def read_client_frame(stream):
    first, second = read_exact(stream, 2)
    opcode = first & 15
    final = bool(first & 128)
    if first & 112 or not second & 128:
        raise ValueError("Invalid client WebSocket frame")
    length = second & 127
    if length == 126:
        length = struct.unpack("!H", read_exact(stream, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", read_exact(stream, 8))[0]
    if length > MAX_MESSAGE_BYTES or (opcode >= 8 and (not final or length > 125)):
        raise ValueError("Oversized WebSocket frame")
    mask = read_exact(stream, 4)
    data = read_exact(stream, length)
    return final, opcode, bytes(value ^ mask[index % 4] for index, value in enumerate(data))


def server_frame(opcode, payload):
    length = len(payload)
    if length < 126:
        header = bytes((128 | opcode, length))
    elif length <= 65535:
        header = bytes((128 | opcode, 126)) + struct.pack("!H", length)
    else:
        header = bytes((128 | opcode, 127)) + struct.pack("!Q", length)
    return header + payload


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


class FixtureState:
    def __init__(self, stats_file=None):
        self.stats_file = stats_file
        self.lock = threading.RLock()
        self.sessions = set()
        self.jpeg = JPEG_PATH.read_bytes()
        self.counters = dict.fromkeys((
            "connections", "activeConnections", "framesSent", "acks", "mouseDown",
            "mouseUp", "mouseMove", "wheel", "text", "keyDown", "keyUp", "navigate",
            "history", "reload", "resize", "viewport", "probe", "takeControl",
            "releaseControl", "dialog", "invalidMessages", "protocolErrors",
        ), 0)
        self.save()

    def snapshot(self):
        with self.lock:
            return {**self.counters, "frameWidth": FRAME_WIDTH, "frameHeight": FRAME_HEIGHT}

    def save(self):
        if self.stats_file:
            atomic_write(self.stats_file, (json.dumps(self.snapshot(), sort_keys=True) + "\n").encode())

    def increment(self, name):
        with self.lock:
            self.counters[name] += 1
            self.save()

    def connect(self, session):
        with self.lock:
            self.sessions.add(session)
            self.counters["connections"] += 1
            self.counters["activeConnections"] = len(self.sessions)
            self.save()

    def disconnect(self, session):
        with self.lock:
            self.sessions.discard(session)
            self.counters["activeConnections"] = len(self.sessions)
            self.save()

    def emit_frame(self):
        with self.lock:
            sessions = tuple(self.sessions)
        for session in sessions:
            try:
                session.frame()
            except (OSError, ValueError):
                pass


class ViewerSession:
    def __init__(self, handler, interactive=True):
        self.handler = handler
        self.state = handler.server.fixture
        self.interactive = interactive
        self.controller = "you" if interactive else "unclaimed"
        self.width, self.height = FRAME_WIDTH, FRAME_HEIGHT
        self.send_lock = threading.Lock()

    def send(self, opcode, data):
        with self.send_lock:
            self.handler.wfile.write(server_frame(opcode, data))
            self.handler.wfile.flush()

    def message(self, value):
        self.send(1, json.dumps(value, separators=(",", ":")).encode())

    def control(self):
        self.message({"type": "control", "canOperate": self.interactive,
                      "controller": self.controller, "generation": 1, "dialog": None})

    def frame(self):
        self.send(2, self.state.jpeg)
        self.state.increment("framesSent")

    def viewport(self):
        self.message({"type": "viewport", "width": self.width, "height": self.height})

    def handle_input(self, payload):
        try:
            message = json.loads(payload)
        except (UnicodeError, ValueError):
            self.state.increment("invalidMessages")
            return
        if not isinstance(message, dict):
            self.state.increment("invalidMessages")
            return
        kind = message.get("type")
        if kind == "ack":
            self.state.increment("acks")
            return
        if not self.interactive:
            return
        if kind == "takeControl":
            self.controller = "you"
            self.state.increment(kind)
            self.control()
            return
        if self.controller != "you":
            return
        if kind == "releaseControl":
            self.controller = "unclaimed"
            self.state.increment(kind)
            self.control()
            return
        if kind == "resize":
            width, height = message.get("width"), message.get("height")
            if not all(number(value) and 0 < value <= 3840 for value in (width, height)):
                self.state.increment("invalidMessages")
                return
            self.width, self.height = int(width), int(height)
            self.state.increment("resize")
            self.viewport()
        elif kind == "viewport":
            self.state.increment("viewport")
        elif kind == "probe" and all(number(message.get(key)) for key in ("x", "y")):
            self.state.increment("probe")
            # Only the synthetic white input field is editable. Coordinates are
            # echoed over the stream to satisfy the source viewer probe protocol;
            # neither coordinates nor entered values are retained in fixture stats.
            self.message({"type": "probe", "x": message["x"], "y": message["y"],
                          "editable": 0.36 <= message["y"] / self.height <= 0.52})
            return
        elif kind == "mouse" and message.get("action") in ("down", "up", "move") and all(number(message.get(key)) for key in ("x", "y")):
            self.state.increment("mouse" + message["action"].title())
        elif kind == "wheel" and all(number(message.get(key)) for key in ("x", "y", "deltaX", "deltaY")):
            self.state.increment("wheel")
        elif kind == "key" and message.get("action") in ("down", "up"):
            self.state.increment("key" + message["action"].title())
        elif kind == "text" and isinstance(message.get("text"), str):
            self.state.increment("text")
        elif kind == "navigate" and isinstance(message.get("url"), str):
            self.state.increment("navigate")
        elif kind == "history" and message.get("delta") in (-1, 1):
            self.state.increment("history")
        elif kind in ("reload", "dialog"):
            self.state.increment(kind)
        else:
            self.state.increment("invalidMessages")
            return
        self.frame()

    def run(self):
        self.state.connect(self)
        try:
            self.control()
            self.viewport()
            self.frame()
            fragments = bytearray()
            fragmented = False
            while True:
                final, opcode, payload = read_client_frame(self.handler.rfile)
                if opcode == 8:
                    self.send(8, payload)
                    break
                if opcode == 9:
                    self.send(10, payload)
                    continue
                if opcode == 10:
                    continue
                if opcode == 1:
                    if fragmented:
                        raise ValueError("Invalid WebSocket continuation")
                    fragments = bytearray(payload)
                    fragmented = not final
                elif opcode == 0 and fragmented:
                    fragments.extend(payload)
                    fragmented = not final
                else:
                    raise ValueError("Expected WebSocket text")
                if len(fragments) > MAX_MESSAGE_BYTES:
                    raise ValueError("Oversized WebSocket message")
                if final:
                    self.handle_input(fragments)
                    fragments.clear()
        except (EOFError, OSError):
            pass
        except ValueError:
            self.state.increment("protocolErrors")
            try:
                self.send(8, struct.pack("!H", 1002))
            except OSError:
                pass
        finally:
            self.state.disconnect(self)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass  # BaseHTTPRequestHandler's default log includes raw query credentials.

    def respond(self, status, value):
        payload = (json.dumps(value, sort_keys=True) + "\n").encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/fixture/stats":
            self.respond(200, self.server.fixture.snapshot())
            return
        if path != "/api/preview-stream/ws":
            self.respond(404, {"error": "Fixture route unavailable"})
            return
        try:
            if self.headers.get("Upgrade", "").lower() != "websocket" or self.headers.get("Sec-WebSocket-Version") != "13":
                raise ValueError("WebSocket upgrade required")
            accept = websocket_accept(self.headers.get("Sec-WebSocket-Key", ""))
        except (ValueError, UnicodeError):
            self.respond(400, {"error": "WebSocket upgrade required"})
            return
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.close_connection = True
        interactive = parse_qs(urlsplit(self.path).query).get("interactive") != ["false"]
        ViewerSession(self, interactive).run()

    def do_POST(self):
        if urlsplit(self.path).path == "/fixture/frame":
            self.server.fixture.emit_frame()
            self.respond(200, {"ok": True})
        else:
            self.respond(404, {"error": "Fixture route unavailable"})


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, _request, _client_address):
        self.fixture.increment("protocolErrors")  # No request or exception text.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--port-file", type=Path, required=True)
    parser.add_argument("--stats-file", type=Path, required=True)
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("--port must be between 0 and 65535")
    state = FixtureState(args.stats_file)
    server = FixtureServer(("127.0.0.1", args.port), Handler)
    server.fixture = state
    atomic_write(args.port_file, (str(server.server_port) + "\n").encode())
    print(f"Synthetic screen fixture listening on 127.0.0.1:{server.server_port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
