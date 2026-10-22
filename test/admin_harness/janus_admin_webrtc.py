#!/usr/bin/env python3
"""Real-datachannel test admin, run ON the server.

Joins the device's Janus textroom over a real WebRTC datachannel (aiortc),
exactly the flow a browser admin performs, so the device runs its consent
flow. Sends the "ping,<n>" keepalives a real web-admin sends so the device's
ping watchdog stays happy.

Also watches the device's streaming mountpoint (janus.plugin.streaming,
id = room id) the same way web-admin's remote-video.js does, and reports:
  VIDEO track/frame counts           -- admin really receives the screen
  VIDEO-ICE-PAIR local/remote        -- selected ICE candidate pair
  VIDEO-PORT-CHECK                   -- Janus-side port within 10000-10500

The test browser machine is behind a symmetric multi-egress NAT and the
web-admin has no STUN/TURN, so its WebRTC cannot complete ICE; this script
joins from the server itself (localhost, no NAT).

Run as michael:
  APUPPET_ROOM=... APUPPET_PIN=... \
    /home/michael/apuppet-test-venv/bin/python /tmp/janus_admin_webrtc.py
"""
import asyncio, json, logging, os, re, secrets, ssl, sys
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

# Janus rtp_port_range (janus.jcfg:205) -- the ports Janus assigns for RTP
# (streaming mountpoint legs and WebRTC ICE sockets) must fall in this range.
RTP_PORT_MIN, RTP_PORT_MAX = 10000, 10500


def txn():
    return secrets.token_hex(8)


def cand_sdp(c):
    s = c.to_sdp() if hasattr(c, "to_sdp") else str(c)
    return s[3:] if s.startswith("a=") else s


def add_h264_fmtp(sdp):
    """Declare packetization-mode=1 for H264 payloads the offer leaves bare.

    The device's streaming mountpoint is a raw-RTP (libstreaming) publisher,
    so Janus's watch offer carries `rtpmap H264/90000` with NO fmtp line --
    effective packetization-mode 0. aiortc's local H264 codecs are mode 1
    only (codecs.py: packetization-mode "1"), so find_common_codecs returns
    [] and setRemoteDescription raises OperationError("... send parameters").
    libstreaming's H264Packetizer sends FU-A/STAP-A (non-interleaved = mode
    1), so declaring mode 1 is truthful about the wire format; browsers
    advertise the same and view this stream fine.
    """
    lines = sdp.splitlines()
    have_fmtp = set()
    for line in lines:
        m = re.match(r"a=fmtp:(\d+) ", line)
        if m:
            have_fmtp.add(m.group(1))
    out = []
    for line in lines:
        out.append(line)
        m = re.match(r"a=rtpmap:(\d+) H264/", line)
        if m and m.group(1) not in have_fmtp:
            out.append("a=fmtp:%s packetization-mode=1;profile-level-id=42E01F" % m.group(1))
    return "\r\n".join(out) + "\r\n"


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
        # rejoin: set on hard evidence we are out of the room (a "destroyed"
        # event or a 422/"Not in room"/"No such room" error); the hold loop
        # answers it with exactly one join attempt, and a fresh failure
        # re-arms it -- so retries are bounded while healthy and persistent
        # only while actually out.
        join_state = {"joined": False, "rejoin": False}

        @local_dc.on("message")
        def _join_watcher(raw):
            try:
                d = json.loads(raw)
            except Exception:
                return
            if d.get("transaction") == jt or d.get("textroom") == "success":
                join_state["joined"] = True
            # Pings sent while out of the room are answered 422 forever,
            # which starves the device's datachannel of traffic and lets the
            # NAT kill the mapping (the 2026-10-22 death spiral: flap ->
            # room destroyed -> harness evicted -> no pings -> idle death ->
            # flap), so prompt rejoining is load-bearing.
            if d.get("textroom") == "destroyed":
                join_state["joined"] = False
                join_state["rejoin"] = True
                print("REJOIN flagged: room destroyed", flush=True)
            elif d.get("textroom") == "error":
                err = str(d.get("error", ""))
                if d.get("error_code") == 422 or "Not in room" in err or \
                        "No such room" in err:
                    join_state["joined"] = False
                    join_state["rejoin"] = True
                    print("REJOIN flagged: %s" % (err or d.get("error_code")),
                          flush=True)

        dc.send(json.dumps({"textroom": "join", "room": ROOM, "pin": PIN,
                            "username": "admin:apitest", "display": "apitest",
                            "transaction": jt}))
        # Ping IMMEDIATELY: the supervisor's watchdog fires 20s after the
        # admin is seen with no ping, and the hold loop only starts after
        # video-leg setup (observed 2026-10-22: "ping timeout" at join+20s
        # stopped sharing before the first loop ping could land).
        dc.send(json.dumps({"textroom": "message", "room": ROOM,
                            "text": "ping,0", "transaction": txn()}))
        await asyncio.sleep(3)
        joined = join_state["joined"]
        print("JOIN-RESULT: joined=%s" % joined, flush=True)

        # ---- post-join ws dispatcher --------------------------------------
        # From here on a single reader owns the socket: streaming-plugin
        # events go to vq (video leg), everything else to allq (attach
        # responses, keepalive acks, trickle acks).
        loop = asyncio.get_event_loop()
        deadline = loop.time() + HOLD_SECONDS
        allq = asyncio.Queue()
        vq = asyncio.Queue()

        async def reader():
            while True:
                try:
                    raw = await ws.recv()
                except Exception as e:
                    print("ws reader closed:", type(e).__name__, flush=True)
                    break
                try:
                    m = json.loads(raw)
                except Exception:
                    continue
                pd = m.get("plugindata") or {}
                if pd.get("plugin") == "janus.plugin.streaming":
                    await vq.put(m)
                else:
                    if m.get("janus") not in ("ack", "keepalive"):
                        print("<<", json.dumps(m)[:160], flush=True)
                    await allq.put(m)

        asyncio.ensure_future(reader())

        async def wait_msg(q, pred, timeout):
            end = loop.time() + timeout
            while True:
                left = end - loop.time()
                if left <= 0:
                    return None
                try:
                    m = await asyncio.wait_for(q.get(), left)
                except asyncio.TimeoutError:
                    return None
                if pred(m):
                    return m

        # ---- video leg -----------------------------------------------------
        # Diagnostic hook: log the RTP payload structure of the first packets
        # (codec, payload length, NAL type, depacketized length) so decode
        # failures can be attributed with evidence rather than guesswork.
        import aiortc.rtcrtpreceiver as _rr
        _orig_depayload = _rr.depayload
        nal_log = []

        def _dbg_depayload(codec, payload):
            data = _orig_depayload(codec, payload)
            if len(nal_log) < 48:
                nal_log.append((codec.name, len(payload),
                                payload[0] & 0x1F if payload else -1, len(data)))
            return data

        _rr.depayload = _dbg_depayload

        vstats = {"frames": 0, "packets": None, "bytes": None, "res": None,
                  "legs": 0, "pairs": [], "track_ended": False, "pc": None}

        async def trickle(h, c):
            if c is None:
                await send({"janus": "trickle", "session_id": sid, "handle_id": h,
                            "transaction": txn(), "candidate": None})
            else:
                await send({"janus": "trickle", "session_id": sid, "handle_id": h,
                            "transaction": txn(),
                            "candidate": {"candidate": cand_sdp(c),
                                          "sdpMid": c.sdpMid if getattr(c, "sdpMid", None) is not None else "0",
                                          "sdpMLineIndex": c.sdpMLineIndex if getattr(c, "sdpMLineIndex", None) is not None else 0}})

        async def video_leg():
            """One attach -> watch -> answer cycle against the device's mountpoint.

            Returns the RTCPeerConnection once ICE is up; frame counting runs
            in the background until the track ends."""
            vpc = RTCPeerConnection()

            @vpc.on("icecandidate")
            async def v_cand(ev):
                await trickle(hid2, getattr(ev, "candidate", None))

            @vpc.on("connectionstatechange")
            def v_state():
                print("video pc state:", vpc.connectionState, flush=True)

            @vpc.on("track")
            def v_track(track):
                print("VIDEO track received:", track.kind, flush=True)

                async def count():
                    while True:
                        try:
                            frame = await asyncio.wait_for(track.recv(), 60)
                        except asyncio.TimeoutError:
                            # A static screen legitimately produces no new
                            # frames (Android virtual displays are damage-
                            # driven); only give up when the connection is gone.
                            if vpc.connectionState in ("closed", "failed"):
                                print("VIDEO track: pc %s after %d frames" %
                                      (vpc.connectionState, vstats["frames"]),
                                      flush=True)
                                break
                            continue
                        except Exception as e:
                            vstats["track_ended"] = True
                            print("VIDEO track ended: %s after %d frames" %
                                  (type(e).__name__, vstats["frames"]), flush=True)
                            break
                        vstats["frames"] += 1
                        vstats["res"] = (frame.width, frame.height)
                        if vstats["frames"] in (1, 30, 60, 120):
                            print("VIDEO frame %d: %dx%d" %
                                  (vstats["frames"], frame.width, frame.height),
                                  flush=True)

                asyncio.ensure_future(count())

            hid2 = None
            await send({"janus": "attach", "plugin": "janus.plugin.streaming",
                        "transaction": txn(), "session_id": sid})
            m = await wait_msg(allq, lambda x: x.get("janus") == "success", 15)
            if not m:
                raise RuntimeError("streaming attach timeout")
            hid2 = m["data"]["id"]
            print("video handle", hid2, flush=True)

            # Watch with retries. The mountpoint is created when the device's
            # engine connects (i.e. when an admin joins), and destroyed +
            # recreated on engine reconnect -- events from earlier handles are
            # filtered out by sender id.
            offer = None
            while loop.time() < deadline:
                await send({"janus": "message", "session_id": sid, "handle_id": hid2,
                            "transaction": txn(),
                            "body": {"request": "watch", "id": ROOM, "pin": PIN}})
                m = await wait_msg(vq, lambda x: x.get("sender") == hid2, 10)
                if m is None:
                    continue
                pd = m["plugindata"]["data"]
                if pd.get("error"):
                    print("VIDEO watch: %s" % pd["error"], flush=True)
                    await asyncio.sleep(3)
                    continue
                if m.get("jsep"):
                    offer = m["jsep"]
                    print("VIDEO offer received", flush=True)
                    break
            if not offer:
                raise RuntimeError("no mountpoint offer")

            # The video leg must never take the textroom session down with it.
            await vpc.setRemoteDescription(
                RTCSessionDescription(sdp=add_h264_fmtp(offer["sdp"]), type=offer["type"]))
            ans = await vpc.createAnswer()
            await vpc.setLocalDescription(ans)
            await send({"janus": "message", "session_id": sid, "handle_id": hid2,
                        "transaction": txn(), "body": {"request": "start"},
                        "jsep": {"type": "answer", "sdp": vpc.localDescription.sdp}})

            for _ in range(100):
                if vpc.iceConnectionState in ("connected", "completed"):
                    break
                await asyncio.sleep(0.2)
            print("VIDEO ice:", vpc.iceConnectionState, flush=True)
            if vpc.iceConnectionState not in ("connected", "completed"):
                raise RuntimeError("video ICE did not connect")

            # Selected ICE candidate pair -- aiortc has no public accessor;
            # getTransceivers() -> receiver -> DTLS -> ICE transport ->
            # aioice's nominated pair (component 1 = RTP).
            try:
                tr = vpc.getTransceivers()[0]
                ice_conn = tr.receiver.transport._transport.iceGatherer._connection
                # aioice 0.10.2: _nominated is dict[int, CandidatePair]; pairs
                # expose local_candidate/remote_candidate (no .local/.remote).
                nom = ice_conn._nominated
                pair = nom.get(1) or (list(nom.values())[0] if nom else None)
                if pair is None:
                    print("VIDEO-ICE-PAIR: none nominated", flush=True)
                else:
                    L, R = pair.local_candidate, pair.remote_candidate
                    # aioice 0.10.2: Candidate.protocol was renamed to
                    # Candidate.transport ("udp") -- tolerate either.
                    lp = getattr(L, "transport", getattr(L, "protocol", "?"))
                    rp = getattr(R, "transport", getattr(R, "protocol", "?"))
                    print("VIDEO-ICE-PAIR local=%s:%d(%s,%s) remote=%s:%d(%s,%s)" % (
                        L.host, L.port, L.type, lp,
                        R.host, R.port, R.type, rp), flush=True)
                    inrange = RTP_PORT_MIN <= R.port <= RTP_PORT_MAX
                    print("VIDEO-PORT-CHECK janus-side port %d in %d-%d: %s" % (
                        R.port, RTP_PORT_MIN, RTP_PORT_MAX, inrange), flush=True)
                    vstats["pairs"].append((L.port, R.port, inrange))
            except Exception as e:
                print("VIDEO-ICE-PAIR: lookup failed: %s %s" %
                      (type(e).__name__, str(e)[:120]), flush=True)
            return vpc

        async def leg_manager():
            # Run (and re-run) the video leg for the whole session: the
            # mountpoint is destroyed/recreated whenever the device's engine
            # reconnects, so a dead leg is re-watched instead of ending video
            # for the rest of the session. Failures never touch the textroom.
            pc = None
            while loop.time() < deadline:
                try:
                    pc = await asyncio.wait_for(video_leg(), 150)
                    vstats["legs"] += 1
                    vstats["pc"] = pc
                except Exception as e:
                    print("VIDEO leg failed (textroom unaffected): %s %s" %
                          (type(e).__name__, str(e)[:160]), flush=True)
                # Follow this leg until it dies (mountpoint destroyed,
                # hangup) or the session deadline arrives.
                while loop.time() < deadline and pc is not None and \
                        pc.connectionState in ("new", "connecting", "connected"):
                    await asyncio.sleep(5)
                if pc is not None and loop.time() < deadline:
                    print("VIDEO leg ended (state=%s), re-watching" %
                          pc.connectionState, flush=True)
                    try:
                        await pc.close()
                    except Exception:
                        pass
                    pc = None
                await asyncio.sleep(2)

        asyncio.ensure_future(leg_manager())

        # ---- hold: keep the admin alive until the deadline -----------------
        print("holding admin session %ss (joined=%s)" % (HOLD_SECONDS, joined), flush=True)
        n = 0
        last_frames = 0
        while loop.time() < deadline:
            # Ping BEFORE sleeping (not after an 8s sleep): with the watchdog
            # window at 20s and video-leg setup eating the first ~15s, the
            # first loop ping must fire as soon as the loop starts.
            n += 1
            await send({"janus": "keepalive", "session_id": sid, "transaction": txn()})
            if dc.readyState == "open":
                if join_state["rejoin"]:
                    join_state["rejoin"] = False
                    print("REJOIN: re-sending join", flush=True)
                    dc.send(json.dumps({"textroom": "join", "room": ROOM,
                                        "pin": PIN, "username": "admin:apitest",
                                        "display": "apitest",
                                        "transaction": txn()}))
                # chat keepalive the real web-admin sends; keeps the device's
                # ping watchdog (20s) from tearing the session down
                dc.send(json.dumps({"textroom": "message", "room": ROOM,
                                    "text": "ping,%d" % n, "transaction": txn()}))
            if vstats["frames"] != last_frames:
                print("VIDEO progress: %d frames total" % vstats["frames"], flush=True)
                last_frames = vstats["frames"]
            await asyncio.sleep(8)

        # ---- final evidence -------------------------------------------------
        pc = vstats.get("pc")
        if pc is not None:
            try:
                # aiortc RTCStatsReport subclasses dict; entries are plain
                # dataclasses (no to_dict()), so read attributes directly.
                report = await pc.getStats()
                for s in report.values():
                    if getattr(s, "type", None) == "inbound-rtp":
                        vstats["packets"] = getattr(s, "packetsReceived", None)
                        vstats["bytes"] = getattr(s, "bytesReceived", None)
            except Exception as e:
                print("receiver stats unavailable: %s %s" %
                      (type(e).__name__, str(e)[:120]), flush=True)
        print("VIDEO-SUMMARY legs=%d frames=%d packetsReceived=%s bytes=%s "
              "resolution=%s pairs=%s" % (
                  vstats["legs"], vstats["frames"], vstats["packets"],
                  vstats["bytes"], vstats["res"], vstats["pairs"]), flush=True)
        print("VIDEO-NAL-LOG first %d packets (codec, payload_len, nal_type, "
              "depayloaded_len): %s" % (len(nal_log), nal_log[:24]), flush=True)
        print("done", flush=True)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as e:
        print("ERROR:", type(e).__name__, str(e)[:200], file=sys.stderr, flush=True)
        sys.exit(1)
