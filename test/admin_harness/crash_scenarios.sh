#!/usr/bin/env bash
# Repeatable regression tests for the four crash paths found 2026-10-22 during
# pre-Phase-4 verification (STATUS.md entry "Phase 3 / pre-Phase-4
# verification", commits aac5522 / 7c2f040 / the bounded-wait fix).
#
#   1. Late consent after stop  -> "codec is released already" process crash
#      (also covers the follow-on "Virtual display width must be positive"
#      redelivered-intent crash, since the late grant is what used to start it)
#   2. Stale/redelivered start intent (fresh process, no projection token,
#      no metrics) -> must refuse, never crash or loop
#   3. Connection-lost storm while idle -> overlapping connect() flows used to
#      NPE on janusSession (AsyncTask dereferenced a field nulled by reset())
#   4. Datachannel death mid-connect -> dataChannelCreated() field capture used
#      to race reset() on a WebRTC thread (NPE on createRoom)
#
# Prerequisites (see also admin_harness/README.md):
#   - device attached via adb, screen ON and UNLOCKED, app installed and
#     launched at least once; after every adb install re-grant accessibility:
#       adb shell settings put secure enabled_accessibility_services \
#           com.hmdm.control/com.hmdm.control.GestureDispatchService
#       adb shell settings put secure accessibility_enabled 1
#   - aiortc harness deployed on the server (see README) and reachable via ssh
#   - scenarios 1 needs an active admin session (the script launches the harness)
#
# Usage:
#   ./crash_scenarios.sh all        # run every scenario
#   ./crash_scenarios.sh 1 2        # run selected scenarios
#
# Environment overrides:
#   HARNESS_SSH     default michael@164.68.105.96
#   HARNESS_PYTHON  default /home/michael/apuppet-test-venv/bin/python
#   HARNESS_SCRIPT  default /tmp/janus_admin_webrtc.py
#   ROOM            default room-n2krmvf6q3
#   PIN             default 1455329441
#   TAP_X / TAP_Y   "Start now" button on the consent dialog (A04: 553/1395)
#
# Exit code 0 = all selected scenarios passed.

set -u

HARNESS_SSH=${HARNESS_SSH:-michael@164.68.105.96}
HARNESS_PYTHON=${HARNESS_PYTHON:-/home/michael/apuppet-test-venv/bin/python}
HARNESS_SCRIPT=${HARNESS_SCRIPT:-/tmp/janus_admin_webrtc.py}
ROOM=${ROOM:-room-n2krmvf6q3}
PIN=${PIN:-1455329441}
TAP_X=${TAP_X:-553}
TAP_Y=${TAP_Y:-1395}
APP=com.hmdm.control
FAILURES=0
PASS() { echo "PASS: $1"; }
FAIL() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

pid()       { adb shell pidof $APP | tr -d '\r'; }
fatals()    { adb logcat -d 2>/dev/null | grep -c "FATAL EXCEPTION"; }
wait_log() { # wait_log <pattern> <timeout_s>
  local i
  for i in $(seq 1 $(( $2 * 2 ))); do
    if adb logcat -d 2>/dev/null | grep -q "$1"; then return 0; fi
    sleep 0.5
  done
  return 1
}

start_harness() { # start_harness <hold_s>
  ssh -i ~/.ssh/id_ed25519_android "$HARNESS_SSH" \
    "APUPPET_ROOM=$ROOM APUPPET_PIN=$PIN APUPPET_HOLD=$1 nohup $HARNESS_PYTHON -u $HARNESS_SCRIPT \
     > /tmp/harness_crash_scenarios.log 2>&1 & echo started \$!" 2>/dev/null
}
stop_harness() {
  ssh -i ~/.ssh/id_ed25519_android "$HARNESS_SSH" "pkill -f janus_admin_webrtc || true" 2>/dev/null
  sleep 1
}

wait_connected() { # wait_connected <timeout_s>
  echo "  waiting for room connection (max $1 s)..."
  wait_log "connected to room" "$1"
}

# --------------------------------------------------------------------------
scenario_1() {
  echo "== Scenario 1: late consent after stop must be refused, not started =="
  local pid_before f_before
  pid_before=$(pid); f_before=$(fatals)
  adb logcat -c
  if ! wait_connected 120; then FAIL "s1: app did not reach the room"; return; fi
  start_harness 240 >/dev/null
  if ! wait_log "admin joined" 30; then FAIL "s1: device never saw the admin join"; stop_harness; return; fi
  sleep 2   # let the consent dialog render
  stop_harness
  if ! wait_log "got command: stop" 30; then FAIL "s1: admin-leave never stopped sharing"; return; fi
  echo "  tapping Start now against a torn-down session..."
  adb shell input tap "$TAP_X" "$TAP_Y"
  sleep 6
  if adb logcat -d 2>/dev/null | grep -q "consent arrived after stop, ignoring"; then
    PASS "s1: late grant logged as refused"
  else FAIL "s1: refusal log missing"; fi
  if adb logcat -d -v time 2>/dev/null | grep "Display device added" | grep -q "virtual:$APP"; then
    FAIL "s1: a virtual display was created despite the refusal"
  else PASS "s1: no virtual display created"; fi
  if [ "$(fatals)" -gt "$f_before" ]; then FAIL "s1: FATAL EXCEPTION occurred"; else PASS "s1: no crash"; fi
  if [ "$(pid)" = "$pid_before" ]; then PASS "s1: process survived"; else FAIL "s1: process died and was restarted"; fi
}

# --------------------------------------------------------------------------
scenario_2() {
  echo "== Scenario 2: stale/redelivered start intent cannot start sharing =="
  local pid_before f_before
  pid_before=$(pid); f_before=$(fatals)
  adb logcat -c
  # Simulates Android redelivering ACTION_START_SHARING into a process that has
  # no projection token and no pending consent (the fresh-process redelivery
  # that used to crash on 'width must be positive').
  adb shell am start-foreground-service -n $APP/.ScreenSharingService -a start 2>/dev/null
  sleep 5
  if adb logcat -d 2>/dev/null | grep -q "getMediaProjection returned null"; then
    PASS "s2: start without token logged as refused"
  else FAIL "s2: refusal log missing"; fi
  if adb logcat -d -v time 2>/dev/null | grep "Display device added" | grep -q "virtual:$APP"; then
    FAIL "s2: a virtual display was created from a stale intent"
  else PASS "s2: no virtual display created"; fi
  if [ "$(fatals)" -gt "$f_before" ]; then FAIL "s2: FATAL EXCEPTION occurred"; else PASS "s2: no crash"; fi
  if [ "$(pid)" = "$pid_before" ]; then PASS "s2: process survived"; else FAIL "s2: process died and was restarted"; fi
}

# --------------------------------------------------------------------------
scenario_3() {
  echo "== Scenario 3: connection-lost storm while idle (overlapping connects) =="
  local pid_before f_before i
  pid_before=$(pid); f_before=$(fatals)
  adb logcat -c
  if ! wait_connected 120; then FAIL "s3: app did not reach the room"; return; fi
  echo "  toggling airplane mode 5x (1s apart)..."
  for i in 1 2 3 4 5; do
    adb shell cmd connectivity airplane-mode enable  >/dev/null 2>&1
    sleep 1
    adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1
    sleep 1
  done
  if [ "$(fatals)" -gt "$f_before" ]; then FAIL "s3: FATAL EXCEPTION during the storm"; else PASS "s3: no crash during the storm"; fi
  if [ "$(pid)" = "$pid_before" ]; then PASS "s3: process survived the storm"; else FAIL "s3: process died and was restarted"; fi
  adb logcat -c
  if wait_connected 180; then PASS "s3: reconnected after the storm"; else FAIL "s3: no reconnect within 180s"; fi
}

# --------------------------------------------------------------------------
scenario_4() {
  echo "== Scenario 4: datachannel death mid-connect (flappy transport) =="
  local pid_before f_before
  pid_before=$(pid); f_before=$(fatals)
  adb logcat -c
  if ! wait_connected 120; then FAIL "s4: app did not reach the room"; return; fi
  echo "  killing the network mid-connect (on 3s, off 8s, on)..."
  adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1
  sleep 3
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1
  sleep 8   # connect flow is now in flight (session create / wss / ICE)
  adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1   # kill it mid-flight
  sleep 2
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1
  sleep 5
  if [ "$(fatals)" -gt "$f_before" ]; then FAIL "s4: FATAL EXCEPTION mid-connect"; else PASS "s4: no crash mid-connect"; fi
  if [ "$(pid)" = "$pid_before" ]; then PASS "s4: process survived"; else FAIL "s4: process died and was restarted"; fi
  adb logcat -c
  if wait_connected 180; then PASS "s4: reconnected after mid-connect death"; else FAIL "s4: no reconnect within 180s"; fi
}

# --------------------------------------------------------------------------
SCENARIOS=${*:-all}
for s in $SCENARIOS; do
  case "$s" in
    1) scenario_1 ;;
    2) scenario_2 ;;
    3) scenario_3 ;;
    4) scenario_4 ;;
    all) scenario_1; scenario_2; scenario_3; scenario_4 ;;
    *) echo "unknown scenario: $s (use 1|2|3|4|all)"; exit 2 ;;
  esac
done
stop_harness
echo "-------------------------------------------"
if [ "$FAILURES" -eq 0 ]; then echo "ALL SELECTED SCENARIOS PASSED"; else echo "$FAILURES ASSERTION(S) FAILED"; exit 1; fi
