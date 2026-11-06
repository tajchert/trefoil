#!/usr/bin/env python3
"""Loopback-only synthetic H.264 device fixture; Python standard library only.

python3 scripts/device-stream-fixture.py --port-file /tmp/device-port \
    --stats-file /tmp/device-stats.json

Reverse the selected port with adb, then open the debug fixture with
 trefoil-stream-fixture://device?port=PORT&mode=device
The exact pinned Android serve-emu API/WS route serves synthetic Annex B H.264
at 10 fps with SEMU timestamps. Stats contain numeric counters only, never input,
queries, tickets, credentials, or real device/conversation data. No remote T3
instance is used or modified. Decoder counts require native codec diagnostics;
the server observes only connections, frames, and reset requests.

Regenerate the checked-in synthetic video (ffmpeg is not a runtime dependency):
 ffmpeg -f lavfi -i testsrc2=size=320x640:rate=10 -t 2 -an -c:v libx264 \
   -profile:v baseline -pix_fmt yuv420p -preset ultrafast -tune zerolatency \
   -x264-params keyint=10:min-keyint=10:scenecut=0:repeat-headers=1:aud=1:threads=1 \
   -f h264 scripts/fixtures/screen-device.h264
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import struct
import threading
from urllib.parse import parse_qs, urlsplit

# Share the already-tested RFC6455 parser and loopback fixture infrastructure.
_spec = importlib.util.spec_from_file_location('_browser_fixture', Path(__file__).with_name('screen-stream-fixture.py'))
_wire = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_wire)
read_exact = _wire.read_exact
FixtureServer = _wire.FixtureServer
PREFIX = '/api/device-hub/vendor/serve-emu'
FPS = 10


def nals(data):
    starts = list(re.finditer(b'\x00\x00(?:\x00)?\x01', data))
    return [(match.start(), match.end(), starts[i + 1].start() if i + 1 < len(starts) else len(data))
            for i, match in enumerate(starts)]


def nal_types(data):
    return {data[start] & 31 for _, start, end in nals(data) if start < end}


def access_units(data):
    boundaries = [offset for offset, start, end in nals(data) if start < end and data[start] & 31 == 9]
    if not boundaries or boundaries[0] != 0:
        raise ValueError('Synthetic H.264 must begin with an AUD')
    units = [data[offset:(boundaries[i + 1] if i + 1 < len(boundaries) else len(data))]
             for i, offset in enumerate(boundaries)]
    if not {7, 8, 5}.issubset(nal_types(units[0])):
        raise ValueError('Synthetic H.264 needs SPS/PPS/IDR at its beginning')
    return tuple(units)


class DeviceFixtureState:
    def __init__(self, stats_file=None, first_frame_delay=0):
        self.stats_file = stats_file
        self.first_frame_delay = first_frame_delay
        self.lock = threading.RLock()
        self.units = access_units((Path(__file__).with_name('fixtures') / 'screen-device.h264').read_bytes())
        self.counters = dict.fromkeys(('connections', 'activeVideoConnections', 'activeControlConnections',
                                       'framesSent', 'keyframesSent', 'resetRequests', 'inputs', 'apiRequests',
                                       'invalidMessages', 'protocolErrors'), 0)
        self.save()

    def snapshot(self):
        with self.lock:
            return {**self.counters, 'frameWidth': 320, 'frameHeight': 640, 'fps': FPS}

    def save(self):
        if self.stats_file:
            _wire.atomic_write(self.stats_file, (json.dumps(self.snapshot(), sort_keys=True) + '\n').encode())

    def increment(self, name, amount=1):
        with self.lock:
            self.counters[name] += amount
            self.save()


class DeviceSession:
    def __init__(self, handler, video):
        self.handler, self.video = handler, video
        self.state = handler.server.fixture
        self.send_lock = threading.Lock()
        self.position_lock = threading.Lock()
        self.stopped = threading.Event()
        self.position = 0
        self.timestamp = 0

    def send(self, opcode, payload):
        with self.send_lock:
            self.handler.wfile.write(_wire.server_frame(opcode, payload))
            self.handler.wfile.flush()

    def frames(self):
        try:
            if self.stopped.wait(self.state.first_frame_delay):
                return
            while not self.stopped.is_set():
                with self.position_lock:
                    unit = self.state.units[self.position]
                    self.position = (self.position + 1) % len(self.state.units)
                    timestamp = self.timestamp
                    self.timestamp += 1_000_000 // FPS
                key = 5 in nal_types(unit)
                self.send(2, struct.pack('!4sBBHQ', b'SEMU', 1, int(key), 0, timestamp) + unit)
                self.state.increment('framesSent')
                if key:
                    self.state.increment('keyframesSent')
                self.stopped.wait(1 / FPS)
        except OSError:
            self.stopped.set()
            # Release the input reader too when the remote renderer disappears.
            try:
                self.handler.connection.shutdown(2)
            except OSError:
                pass

    def message(self, payload):
        try:
            value = json.loads(payload)
            if not isinstance(value, dict):
                raise ValueError('Expected object')
        except (ValueError, UnicodeError):
            self.state.increment('invalidMessages')
            return
        if value.get('type') == 'reset-video':
            self.state.increment('resetRequests')
            with self.position_lock:
                self.position = 0
        elif value.get('type') in ('touch', 'key', 'text', 'button'):
            self.state.increment('inputs')
        else:
            self.state.increment('invalidMessages')

    def run(self):
        sender = None
        self.state.increment('connections')
        self.state.increment('activeControlConnections')
        if self.video:
            self.state.increment('activeVideoConnections')
        try:
            if self.video:
                self.send(1, b'{"type":"video-session"}')
                sender = threading.Thread(target=self.frames, daemon=True)
                sender.start()
            fragments = bytearray()
            fragmented = False
            while not self.stopped.is_set():
                final, opcode, payload = _wire.read_client_frame(self.handler.rfile)
                if opcode == 8:
                    self.stopped.set()
                    self.send(8, payload)
                    break
                if opcode == 9:
                    self.send(10, payload)
                    continue
                if opcode == 10:
                    continue
                if opcode == 1 and not fragmented:
                    fragments = bytearray(payload)
                    fragmented = not final
                elif opcode == 0 and fragmented:
                    fragments.extend(payload)
                    fragmented = not final
                else:
                    raise ValueError('Invalid text continuation')
                if len(fragments) > _wire.MAX_MESSAGE_BYTES:
                    raise ValueError('Message too large')
                if final:
                    self.message(fragments)
                    fragments.clear()
        except (EOFError, OSError):
            pass
        except ValueError:
            self.state.increment('protocolErrors')
            try:
                self.send(8, struct.pack('!H', 1002))
            except OSError:
                pass
        finally:
            self.stopped.set()
            if sender:
                sender.join(timeout=2)
            self.state.increment('activeControlConnections', -1)
            if self.video:
                self.state.increment('activeVideoConnections', -1)


class Handler(_wire.Handler):
    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/fixture/stats':
            self.respond(200, self.server.fixture.snapshot())
            return
        if path == PREFIX + '/api':
            self.server.fixture.increment('apiRequests')
            self.respond(200, {})  # No WebRTC transport; pinned viewer chooses WebSocket.
            return
        if path != PREFIX + '/ws':
            self.respond(404, {'error': 'Fixture route unavailable'})
            return
        try:
            if self.headers.get('Upgrade', '').lower() != 'websocket' or self.headers.get('Sec-WebSocket-Version') != '13':
                raise ValueError('Upgrade required')
            accept = _wire.websocket_accept(self.headers.get('Sec-WebSocket-Key', ''))
        except (ValueError, UnicodeError):
            self.respond(400, {'error': 'WebSocket upgrade required'})
            return
        self.send_response(101)
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', accept)
        self.end_headers()
        self.close_connection = True
        DeviceSession(self, parse_qs(urlsplit(self.path).query).get('video') != ['0']).run()

    def do_POST(self):
        self.respond(404, {'error': 'Fixture route unavailable'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--port-file', type=Path, required=True)
    parser.add_argument('--stats-file', type=Path, required=True)
    parser.add_argument('--first-frame-delay', type=float, default=0)
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error('--port must be between 0 and 65535')
    if not 0 <= args.first_frame_delay <= 10:
        parser.error('--first-frame-delay must be between 0 and 10 seconds')
    server = FixtureServer(('127.0.0.1', args.port), Handler)
    server.fixture = DeviceFixtureState(args.stats_file, args.first_frame_delay)
    _wire.atomic_write(args.port_file, (str(server.server_port) + '\n').encode())
    print(f'Synthetic H.264 device fixture on 127.0.0.1:{server.server_port}', flush=True)
    try:
        server.serve_forever(poll_interval=.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
