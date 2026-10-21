# admin_harness — aiortc test admin for the consent E2E

A headless stand-in for the web-admin: joins the device's Janus textroom over a
**real WebRTC datachannel** (aiortc), so the device takes the headless consent
path exactly as it would for a real operator. Used to verify the Phase 3 gate
(consent notification → tap → MediaProjection approve → streaming → clean stop).

Run it **on the server** (localhost → no NAT; the test machine's network cannot
complete ICE, see DECISIONS.md 2026-10-22):

```sh
scp -i ~/.ssh/id_ed25519_android test/admin_harness/janus_admin_webrtc.py \
    michael@<server>:/tmp/janus_admin_webrtc.py
ssh -i ~/.ssh/id_ed25519_android michael@<server> \
    'APUPPET_ROOM=<room id> APUPPET_PIN=<build PIN> \
     /home/michael/apuppet-test-venv/bin/python /tmp/janus_admin_webrtc.py'
```

- The Janus api secret is read at runtime from
  `/opt/apuppet/deploy/dist/credentials/janus_api_secret` — never put it in git.
- Room id / PIN come from env (`APUPPET_ROOM`, `APUPPET_PIN`); hold time via
  `APUPPET_HOLD` (default 360 s).
- Required venv packages: `websockets`, `aiortc` (server venv
  `/home/michael/apuppet-test-venv`).

## Facts baked into this script (from real failures)

1. **DTLS cipher mismatch**: aiortc's `RTCCertificate._create_ssl_context`
   hardcodes ECDHE-ECDSA suites; Janus uses the RSA LE cert → `NO_SHARED_CIPHER`.
   The script monkeypatches the cipher list to add ECDHE-RSA suites; the
   handshake then negotiates `SRTP_AEAD_AES_256_GCM`.
2. **The answerer must create the datachannel**: Janus's textroom waits for the
   *answerer* to call `createDataChannel("JanusDataChannel")` (janus.js does the
   same). Without it the SCTP association never opens.
3. **Join-wait race**: a per-message waiter registered *after* `dc.send(join)`
   can miss the success response (same-batch SCTP dispatch). A persistent
   `dc.on("message")` handler must be registered *before* sending.
4. **Pings are mandatory**: the device's supervisor ping watchdog
   (`session_timeout=20`) rebuilds the room if the first ping doesn't arrive
   within 20 s of join. This script pings immediately and every 8 s,
   regardless of join detection.

## Known harness limitation

The python websockets keepalive to :8989 has timed out twice ~2.5 min into a
session (`sent 1011 keepalive ping timeout`), ending the run early. The device
reacts correctly (admin left → room rebuild → sharing stops), so this does not
affect the product path; lengthen runs via `APUPPET_HOLD` + rerun if needed.
