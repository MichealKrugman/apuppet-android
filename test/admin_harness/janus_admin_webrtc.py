#!/usr/bin/env python3
"""Real-datachannel test admin for room-n2krmvf6q3, run ON the server.

The test browser machine is behind a symmetric multi-egress NAT and the
web-admin has no STUN/TURN, so its textroom datachannel cannot complete
ICE. This script joins from the server itself (localhost, no NAT) with a
real WebRTC datachannel via aiortc -- exactly the flow a browser admin
performs -- so the device sees a genuine admin join and runs its consent
flow. It also sends the "ping,<n>" chat keepalives a real web-admin sends,
so the supervisor's ping watchdog stays happy.

Run as michael:
  /home/michael/apuppet-test-venv/bin/python /tmp/janus_admin_webrtc.py
"""
import asyncio, json, logging, os, secrets, ssl, sys
import websockets
from aiortc import RTCPeerConnection, RTCSessionDescription

# aiortc hardcodes an ECDHE-ECDSA-only DTLS cipher list, but Janus's DTLS
# certificate is the RSA Let's Encrypt cert (janus.jcfg points at
# fullchain.pem, observed as RSASSA-PSS in the TLS handshake) -> the server
# reported NO_SHARED_CIPHER. Widen the list with the ECDHE-RSA suites.
import aiortc.rtcdtlstransport as _dt

_orig_create_ctx = _dt.RTCCertificate._create_ssl_context


def _patched_create_ctx(self, srtp_profiles):
    ctx = _orig_create_ctx(self, srtp_profiles)
    ctx.set_cipher_list(
        b"ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:"
        b"ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-SHA:"
        b"ECDHE-ECDSA-AES256-SHA:"
        b"ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-CHACHA20-POLY1305:"
        b"ECDHE-RSA-AES256-GCM-SHA384"
    )
    return ctx


_dt.RTCCertificate._create_ssl_context = _patched_create_ctx

URL = "wss://127.0.0.1:8989/"
SECRET_FILE = "/opt/apuppet/deploy/dist/credentials/janus_api_secret"
# Pairing credentials come from the environment so this harness can live in
# git without the device's room id / build PIN (the PIN is known-weak, see
# DECISIONS.md; it is replaced in Phase 5).
ROOM = os.environ.get("APUPPET_ROOM", "")
PIN = os.environ.get("APUPPET_PIN", "")
HOLD_SECONDS = int(os.environ.get("APUPPET_HOLD", "360"))
if not ROOM or not PIN:
    sys.exit("set APUPPET_ROOM and APUPPET_PIN (device pairing room id and PIN)")


def txn():
    return secrets.token_hex(8)


def cand_sdp(c):
    s = c.to_sdp() if hasattr(c, "to_sdp") else str(c)
    return s[3:] if s.startswith("a=") else s


async def main():
    logging.basicConfig(level=logging.DEBUG,
                        format="%(asctime)s %(name)s %(levelname)s %(message)s")
    logging.getLogger("asyncio").setLevel(logging.WARNING)
    secret = open(SECRET_FILE).read().strip()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    async with websockets.connect(URL, ssl=ctx, subprotocols=["janus-protocol"],
                                  max_size=2 ** 23, open_timeout=15) as ws:
        async def send(o):
            o["apisecret"] = secret
            await ws.send(json.dumps(o))

        async def recv(t=15):
            m = json.loads(await asyncio.wait_for(ws.recv(), t))
            print("<<", json.dumps(m)[:200], flush=True)
            return m

        pc = RTCPeerConnection()
        dc_holder = {}
        dc_open = asyncio.Event()

        # Like janus.js does when answering, create the datachannel ourselves:
        # Janus's textroom waits for the answerer to open it and never creates
        # one, so waiting for a remote channel would hang forever.
        local_dc = pc.createDataChannel("JanusDataChannel")
        dc_holder["ch"] = local_dc

        @local_dc.on("open")
        def on_local_open():
            print("datachannel OPEN (local)", flush=True)
            dc_open.set()

        @local_dc.on("message")
        def on_local_message(raw):
            print("DC <<", raw[:180], flush=True)

        @pc.on("datachannel")
        def on_datachannel(channel):
            print("datachannel received:", channel.label, flush=True)
            dc_holder["ch"] = channel

            @channel.on("open")
            def on_open():
                print("datachannel OPEN", flush=True)
                dc_open.set()

            @channel.on("message")
            def on_message(raw):
                print("DC <<", raw[:180], flush=True)

        @pc.on("connectionstatechange")
        def on_state():
            print("pc state:", pc.connectionState, flush=True)

        @pc.on("iceconnectionstatechange")
        def on_ice():
            print("pc ice state:", pc.iceConnectionState, flush=True)

        @pc.on("icecandidate")
        async def on_candidate(ev):
            c = getattr(ev, "candidate", None)
            if c is None:
                await send({"janus": "trickle", "session_id": sid, "handle_id": hid,
                            "transaction": txn(), "candidate": None})
            else:
                await send({"janus": "trickle", "session_id": sid, "handle_id": hid,
                            "transaction": txn(),
                            "candidate": {"candidate": cand_sdp(c),
                                          "sdpMid": c.sdpMid if getattr(c, "sdpMid", None) is not None else "0",
                                          "sdpMLineIndex": c.sdpMLineIndex if getattr(c, "sdpMLineIndex", None) is not None else 0}})

        await send({"janus": "create", "transaction": txn()})
        m = await recv()
        sid = m["data"]["id"]
        await send({"janus": "attach", "plugin": "janus.plugin.textroom",
                    "transaction": txn(), "session_id": sid})
        m = await recv()
        hid = m["data"]["id"]
        print("session", sid, "handle", hid, flush=True)

        # setup -> Janus sends the offer
        await send({"janus": "message", "session_id": sid, "handle_id": hid,
                    "transaction": txn(), "body": {"request": "setup"}})
        offer = None
        for _ in range(5):
            m = await recv(t=10)
            if m.get("janus") == "event" and m.get("jsep"):
                offer = m["jsep"]
                break
        if not offer:
            print("ERROR: no offer received", flush=True)
            return

        await pc.setRemoteDescription(RTCSessionDescription(sdp=offer["sdp"], type=offer["type"]))
        answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        await send({"janus": "message", "session_id": sid, "handle_id": hid,
                    "transaction": txn(), "body": {"request": "setup"},
                    "jsep": {"type": "answer", "sdp": pc.localDescription.sdp}})

        try:
            await asyncio.wait_for(dc_open.wait(), timeout=30)
        except asyncio.TimeoutError:
            print("DC TIMEOUT: ice=%s conn=%s gather=%s" % (
                pc.iceConnectionState, pc.connectionState, pc.iceGatheringState), flush=True)
            raise
        print("datachannel is open -- joining room", flush=True)
        dc = dc_holder["ch"]
        jt = txn()
        # Watch for the join result with a PERSISTENT handler registered
        # BEFORE sending (a per-message waiter registered after dc.send can
        # miss responses dispatched in the same I/O batch -- that happened:
        # the success response was seen by the logger but joined stayed
        # False). Ping the device IMMEDIATELY and continuously regardless of
        # detection: the supervisor's ping watchdog (20s) tears the session
        # down otherwise (observed: "ping timeout ... rebuilding session").
        join_state = {"joined": False}

        @local_dc.on("message")
        def _join_watcher(raw):
            try:
                d = json.loads(raw)
            except Exception:
                return
            if d.get("transaction") == jt or d.get("textroom") == "success":
                join_state["joined"] = True

        dc.send(json.dumps({"textroom": "join", "room": ROOM, "pin": PIN,
                            "username": "admin:apitest", "display": "apitest",
                            "transaction": jt}))
        await asyncio.sleep(3)
        joined = join_state["joined"]
        print("JOIN-RESULT: joined=%s" % joined, flush=True)

        print("holding admin session %ss (joined=%s)" % (HOLD_SECONDS, joined), flush=True)
        loop = asyncio.get_event_loop()
        end = loop.time() + HOLD_SECONDS
        n = 0
        while loop.time() < end:
            await asyncio.sleep(8)
            n += 1
            await send({"janus": "keepalive", "session_id": sid, "transaction": txn()})
            if dc.readyState == "open":
                # chat keepalive the real web-admin sends; keeps the device's
                # ping watchdog (20s) from tearing the session down
                dc.send(json.dumps({"textroom": "message", "room": ROOM,
                                    "text": "ping,%d" % n, "transaction": txn()}))
        print("done", flush=True)


_dc_queue = None


async def _dc_next(dc):
    fut = asyncio.get_event_loop().create_future()

    @dc.on("message")
    def _m(raw):
        if not fut.done():
            fut.set_result(raw)

    return await fut


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as e:
        print("ERROR:", type(e).__name__, str(e)[:200], file=sys.stderr, flush=True)
        sys.exit(1)
