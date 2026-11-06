#!/usr/bin/env python3
"""Copy T3's generated, trusted viewer IIFEs without executing the JS. One pinned device text export is applied.

First run T3's apps/mobile scripts/generate-device-stream.mts generators using its
normal mobile prebuild. Then: python3 scripts/sync-t3-streams.py /path/to/t3code
No npm/network/build or remote T3 server operations are performed here.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

source = Path(sys.argv[1]).resolve()
destination = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/t3-streams"
destination.mkdir(parents=True, exist_ok=True)
provenance = {"repository": "https://github.com/tajchert/t3code", "upstream": "https://github.com/pingdotgg/t3code", "revision": subprocess.check_output(
    ["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip(), "bundles": {}}
for name in ("device", "preview"):
    relative = f"apps/mobile/.generated/{name}-stream/index.js"
    encoded = (source / relative).read_text()
    prefix = "module.exports = "
    if not encoded.startswith(prefix) or not encoded.rstrip().endswith(";"):
        raise ValueError(f"Unexpected T3 generated module format: {relative}")
    script = json.loads(encoded[len(prefix):].rstrip()[:-1])
    if not isinstance(script, str) or f"T3{name.title()}Stream" not in script:
        raise ValueError("Unexpected viewer bundle")
    upstream_hash = hashlib.sha256(script.encode()).hexdigest()
    patch = None
    if name == "device":
        # Export the shared client's Unicode-aware, size-limited sendText implementation.
        # Exact markers fail closed when upstream's generated wrapper changes.
        before = "return e.command=rd,e.start=id,e.stop=nd,e})({});"
        after = "return e.text=function(text){td?.sendText(text)},e.command=rd,e.start=id,e.stop=nd,e})({});"
        active_client_marker = "var td=null;function nd(){td?.stop(),td=null}"
        if script.count(before) != 1 or script.count(active_client_marker) != 1:
            raise ValueError("Device text export patch requires upstream review")
        script = script.replace(before, after)
        patch = {"name": "device-unicode-text-export-v1", "before": before, "after": after,
                 "activeClientMarker": active_client_marker}
    raw = script.encode()
    (destination / f"{name}-stream.js").write_bytes(raw)
    provenance["bundles"][f"{name}-stream.js"] = {"generatedSource": relative,
        "sha256": hashlib.sha256(raw).hexdigest(), "upstreamSha256": upstream_hash, "patch": patch,
        "entryPoint": f"apps/mobile/src/features/{'devices' if name == 'device' else 'browser'}/{name}-stream.browser.ts"}
(destination / "LICENSE").write_bytes((source / "LICENSE").read_bytes())
(destination / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")

# The trusted viewers bundle Effect runtime/Schema code; ship its MIT and embedded notices.
effect = source / "node_modules/effect"
if not effect.exists():
    effect = source / "node_modules/.pnpm/effect@4.1.2/node_modules/effect"
for source_name, destination_name in (("LICENSE", "LICENSE.effect"), ("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.effect.txt")):
    (destination / destination_name).write_bytes((effect / source_name).read_bytes())
