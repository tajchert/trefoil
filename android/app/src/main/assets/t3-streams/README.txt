T3 Code trusted pixel viewers (MIT, copyright 2026 T3 Tools Inc.). See LICENSE.
Bundled Effect runtime/Schema code: MIT, Copyright (c) 2023 Effectful Technologies Inc.
See LICENSE.effect and THIRD_PARTY_NOTICES.effect.txt.

The .js assets come from the IIFE strings exported by T3 mobile's generated
CommonJS viewer modules. The device bundle has one recorded exact-marker export
patch: T3DeviceStream.text(text) calls the original shared client.sendText(text)
for Unicode-aware Android text. Decoder and transport code is unchanged. They share T3's H.264/WebCodecs, JPEG/MJPEG decoders,
letterbox/orientation coordinate mapping, single-pointer device input, browser
mouse/wheel/keyboard input and browser control arbitration. Device pinch/multi-
touch is not supported by this wrapper. No remote page HTML is loaded here.

provenance.json pins the source revision, source entry points, upstream and final
asset SHA-256, and exact before/after markers for the device text export.
Regenerate T3 mobile's viewer modules in its source checkout, then run:
  python3 scripts/sync-t3-streams.py /path/to/t3code
The sync script does not execute JavaScript or contact/reconfigure a T3 server.

StreamDocument.kt adapts T3's RN documents and postMessage bridge. Android device
and browser viewers use https://localhost/ as the synthetic secure document origin
(WebCodecs requirement), matching Android RN. An exact random /trefoil-stream/ document route is served from memory by native interception; every other main-frame navigation is blocked. iOS simulator device viewing uses
the validated environment origin, matching RN, to permit approved private-LAN
HTTP MJPEG without disguising its origin. Plain HTTP requires shared URL policy
consent before constructing the viewer. File/content access, remote documents,
popups, remote scripts, frames, workers and storage are disabled. CSP restricts
network media to the exact validated environment origin; native interception also
restricts HTTP resources to the selected API root. Only memory-only short-lived
media tickets enter configuration; the environment bearer token never enters JS.
WebRTC is disabled because ICE/STUN/TURN endpoints cannot be validated by the
shared HTTP/WS URL policy. A host requiring WebRTC must expose the pinned socket
transport to work in this viewer. No stream input is queued for reconnection.
