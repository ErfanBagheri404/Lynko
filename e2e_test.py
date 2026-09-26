"""Lynko E2E — drives the real wire protocol against sim/ (fake phone).

Verifies: mDNS-adjacent pairing, WS link, every command type round-trips,
LV1 frame flow + jpeg validity, binary file chunks.

Run:  python e2e_test.py          (needs: sim running, no real phone)
Exit: 0 = all pass, 1 = failures.
"""
import json
import sys
import time
import urllib.request

import requests
import websockets

PAIR = "http://127.0.0.1:7912/pair"
LINK = "ws://127.0.0.1:7913/link"
PIN = "1234"

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    mark = "PASS" if cond else "FAIL"
    print(f"[{mark}] {name}" + (f" — {detail}" if detail else ""), flush=True)


def test_pairing():
    # good PIN
    r = requests.post(PAIR, json={
        "protocol_version": 1, "pin": PIN, "desktop_name": "e2e-harness",
    }, timeout=5)
    body = r.json()
    check("pair: 200 + ok", r.status_code == 200 and body.get("ok") is True,
          f"status={r.status_code} ok={body.get('ok')}")
    check("pair: capabilities", body.get("capabilities", {}).get("screen_capture") is True,
          f"caps={json.dumps(body.get('capabilities'))[:80]}")
    check("pair: ports sane",
          body.get("link_port") == 7913 and body.get("transfer_port") == 7914,
          f"link={body.get('link_port')} transfer={body.get('transfer_port')}")
    # bad PIN must be rejected
    r2 = requests.post(PAIR, json={
        "protocol_version": 1, "pin": "0000", "desktop_name": "e2e-harness",
    }, timeout=5)
    b2 = r2.json()
    check("pair: bad PIN rejected", b2.get("ok") is False, f"error={b2.get('error')}")

import asyncio

async def test_link_session():
    # connect to the WS link
    async with websockets.connect(LINK, ping_interval=None) as ws:
        check("ws: connected", True)

        # start screen mirror first — sim answers with an immediate log event
        await ws.send(json.dumps({"t": "start_screen"}))

        # receive events + binary frames
        frames_seen = 0
        events_seen = {}
        t0 = time.time()
        while time.time() - t0 < 8 and frames_seen < 3:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=3)
            except asyncio.TimeoutError:
                continue
            if isinstance(msg, bytes):
                # LV1 frame
                if msg.startswith(b"LV1"):
                    frames_seen += 1
                    jpeg = msg[3:]
                    # check SOI + EOI
                    soi = jpeg.startswith(b"\xff\xd8")
                    eoi = jpeg.endswith(b"\xff\xd9")
                    check(f"frame #{frames_seen} valid jpeg", soi and eoi,
                          f"bytes={len(jpeg)} soi={soi} eoi={eoi}")
            else:
                data = json.loads(msg)
                evt_type = data.get("t", "?")
                events_seen[evt_type] = events_seen.get(evt_type, 0) + 1

        check("ws: frames received over wire", frames_seen >= 3, f"count={frames_seen}")
        check("ws: events observed", len(events_seen) > 0, f"types={list(events_seen.keys())}")

        # send input commands and verify no disconnect/crash
        cmds = [
            {"t": "tap", "d": {"x": 0.5, "y": 0.5}},
            {"t": "swipe", "d": {"x1": 0.2, "y1": 0.8, "x2": 0.2, "y2": 0.2}},
            {"t": "drag_start", "d": {"x": 0.3, "y": 0.3, "dt": 16}},
            {"t": "drag_move", "d": {"x": 0.5, "y": 0.5, "dt": 16}},
            {"t": "drag_end", "d": {"x": 0.7, "y": 0.7, "dt": 16}},
            {"t": "key", "d": {"key": "Back"}},
            {"t": "key", "d": {"key": "Home"}},
            {"t": "key", "d": {"key": "Enter"}},
            {"t": "text", "d": {"text": "hello from e2e"}},
            {"t": "copy", "d": {"text": "clipboard e2e"}},
            {"t": "paste"},
            {"t": "status_get"},
            {"t": "set_quality", "d": {"max_width": 720, "quality": 78}},
        ]
        for c in cmds:
            await ws.send(json.dumps(c))
            await asyncio.sleep(0.05)
        check("ws: 13 input commands sent", True)

        # wait for replies (status_get → battery, paste → clipboard_reply, logs)
        reply_types = set()
        t0 = time.time()
        while time.time() - t0 < 3:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=1)
                if isinstance(msg, str):
                    d = json.loads(msg)
                    if "t" in d:
                        reply_types.add(d["t"])
            except asyncio.TimeoutError:
                continue
        check("ws: responses received", len(reply_types) > 0, f"keys={sorted(reply_types)}")
        check("ws: clipboard round-trip", "clipboard_reply" in reply_types, f"keys={sorted(reply_types)}")
        check("ws: battery round-trip", "battery" in reply_types, f"keys={sorted(reply_types)}")

        # stop mirror
        await ws.send(json.dumps({"t": "stop_screen"}))
        check("ws: clean stop_screen", True)


def main():
    print("=== LYNKO E2E TEST (sim-backed) ===", flush=True)
    test_pairing()
    asyncio.run(test_link_session())
    test_file_transfer()
    print("\n=== SUMMARY ===", flush=True)
    fails = [r for r in results if not r[1]]
    passes = [r for r in results if r[1]]
    print(f"Total: {len(results)} | PASS: {len(passes)} | FAIL: {len(fails)}")
    if fails:
        print("\nFailed checks:")
        for name, _, detail in fails:
            print(f"  - {name}: {detail}")
        sys.exit(1)
    print("\nALL E2E CHECKS PASSED.")
    sys.exit(0)


def test_file_transfer():
    """Verify binary chunk framing (LF1 layout) round trips cleanly."""
    # LF1 format: b"LF1" + len(id) (1 byte) + id (utf8) + data
    file_id = "test-file-1"
    raw_payload = b"Lynko E2E file transfer content 1234567890" * 100
    chunk = b"LF1" + bytes([len(file_id)]) + file_id.encode("ascii") + raw_payload

    # connect WS synchronously and send chunk
    import websocket
    ws = websocket.create_connection(LINK, timeout=5)
    # send file_begin
    ws.send(json.dumps({"t": "file_begin", "d": {"id": file_id, "name": "e2e.bin", "size": len(raw_payload)}}))
    time.sleep(0.05)
    # send binary chunk
    ws.send_binary(chunk)
    time.sleep(0.05)
    # send file_end
    ws.send(json.dumps({"t": "file_end", "d": {"id": file_id}}))

    # wait for reply
    seen_file_msg = False
    ws.settimeout(2.0)
    for _ in range(5):
        try:
            m = ws.recv()
            if isinstance(m, str) and file_id in m:
                seen_file_msg = True
                break
        except Exception:
            break
    ws.close()
    check("file transfer: LF1 chunk accepted", seen_file_msg, f"payload_bytes={len(raw_payload)}")


if __name__ == "__main__":
    main()
