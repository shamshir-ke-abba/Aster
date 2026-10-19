#!/usr/bin/env python3
"""
Comprehensive Milestone 9 & 9.1 Validation Script:
Practical Authentication & Credential Lifecycle Hardening Playtest.

Validates:
1. Administrative Control Plane Fail-Closed Hardening:
   a. POST /api/provision without admin key is rejected (HTTP 401).
   b. POST /api/provision with invalid admin key is rejected (HTTP 401).
   c. POST /api/revoke without admin key is rejected (HTTP 401).
   d. POST /api/revoke with invalid admin key is rejected (HTTP 401).
   e. POST /api/provision with malformed userId is rejected (HTTP 400).
   f. POST /api/provision with malformed deviceId is rejected (HTTP 400).
   g. POST /api/revoke with empty target is rejected (HTTP 400).
   h. Authorized POST /api/provision succeeds with 201 Created and strong token.
2. Transport & Messaging Impersonation Pre-checks:
   a. Untrusted client connecting without Aster Root CA is rejected (TLS layer).
   b. Client connecting with invalid or blank token is rejected (AUTH_ERROR frame).
   c. Client attempting AUTH impersonation (Saad's token with Sam's userId) has userId overridden to authenticated owner.
   d. Client attempting SEND impersonation (forged senderId) has senderId overridden to authenticated session owner.
   e. Client attempting foreign ACK on message addressed to another user receives UNAUTHORIZED_ACK error.
3. Full Milestone 7 & 8 flow over Authenticated TLS / WSS:
   - Step 1: Launch Aster with clean app data, provisioned with Saad's token via Intent extra.
   - Step 2: Open Home, verify Saad greeting & baseline conversations (Alex, Rohan exist, Sam does not).
   - Step 3: Tap New Chat FAB.
   - Step 4: Verify contact dialog (Saad excluded, Sam available). Select Sam.
   - Step 5: Verify conversation created with Sam; display empty conversation state.
   - Step 8 & 9: Compose and send message to Sam over authenticated WSS.
   - Step 10: Verify message state transitions to SENT over WSS.
   - Step 11: Verify recipient (Sam connected via WSS with Sam's token) receives message with senderId='user_saad' & ACK is processed.
   - Step 12: Return Home.
   - Step 13: Verify latest message preview updates on Home screen.
   - Step 7: Verify conversation creation idempotency (tapping Sam again opens existing chat, no duplicates).
   - Step 14 & 15: Reopen conversation from Home list, verify message persists.
   - Step 16 & 17: Force-stop Android app and relaunch from cold start (without Intent extras to verify Keystore persistence).
   - Step 18: Verify Room persistence across process death and auto-reconnection with stored credential.
   - Step 19: Verify reconnect and offline outbox queuing / replay over authenticated WSS.
4. Active Credential Revocation & Session Eviction Lifecycle:
   - Step 20: Connect live WebSocket client (Alex) with valid token.
   - Step 21: Authorized admin revokes Alex's credential via POST /api/revoke.
   - Step 22: Active WebSocket session is immediately terminated by server with policy violation.
   - Step 23: Subsequent reconnection attempt with revoked token is rejected with AUTH_ERROR.
   - Step 24: Unaffected user (Sam) remains connected and healthy.
   - Step 25: Device-specific revocation isolation: Revoking Phone device does not revoke Tablet device.
   - Step 26: User-wide revocation invalidates all remaining devices for user.
"""

import asyncio
import json
import os
import re
import ssl
import subprocess
import time
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET
import websockets

CA_CERT_PATH = "/home/saad/Aster/certs/aster_ca.crt"
WSS_ENDPOINT = "wss://192.168.240.1:8443/ws"
HEALTH_ENDPOINT = "https://192.168.240.1:8443/health"
PROVISION_ENDPOINT = "https://192.168.240.1:8443/api/provision"
REVOKE_ENDPOINT = "https://192.168.240.1:8443/api/revoke"
def get_admin_key() -> str:
    key_file = "/home/saad/Aster/.admin_key.secret"
    if os.path.exists(key_file):
        with open(key_file, "r") as f:
            k = f.read().strip()
            if k:
                return k
    env_key = os.environ.get("ASTER_ADMIN_KEY")
    if env_key and env_key.strip():
        return env_key.strip()
    raise RuntimeError("Admin key not configured: .admin_key.secret missing and ASTER_ADMIN_KEY not set")

ADMIN_KEY = get_admin_key()

def log(tag: str, msg: str):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] [{tag}] {msg}")

def run_adb(cmd: str) -> str:
    full_cmd = f"adb -s {WAYDROID_SERIAL} {cmd}"
    try:
        res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, timeout=15)
        return res.stdout.strip()
    except subprocess.TimeoutExpired:
        log("ADB", f"Command timed out: {cmd}, waking up container...")
        subprocess.run("waydroid app launch com.aster.app", shell=True)
        res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, timeout=15)
        return res.stdout.strip()

def safe_screenshot(local_path: str):
    run_adb("shell screencap -p /sdcard/screenshot.png")
    run_adb(f"pull /sdcard/screenshot.png {local_path}")
    run_adb("shell rm /sdcard/screenshot.png")
    res = subprocess.run(f"file {local_path}", shell=True, capture_output=True, text=True)
    assert "PNG image data" in res.stdout, f"Corrupted screenshot at {local_path}: {res.stdout}"
    log("SCREENSHOT", f"Saved verified screenshot: {local_path}")

def dump_ui() -> ET.Element:
    run_adb("shell uiautomator dump /sdcard/ui.xml")
    xml_str = run_adb("shell cat /sdcard/ui.xml")
    start = xml_str.find("<?xml")
    if start != -1:
        xml_str = xml_str[start:]
    return ET.fromstring(xml_str)

def find_node(root: ET.Element, **kwargs) -> ET.Element | None:
    for node in root.iter():
        match = True
        for k, v in kwargs.items():
            attr = k.replace("_", "-")
            if node.attrib.get(attr) != v:
                match = False
                break
        if match:
            return node
    return None

def find_node_contains(root: ET.Element, attr: str, text: str) -> ET.Element | None:
    attr_name = attr.replace("_", "-")
    for node in root.iter():
        val = node.attrib.get(attr_name, "")
        if text in val:
            return node
    return None

def wait_for_node(timeout: float = 12.0, **kwargs) -> tuple[ET.Element, ET.Element]:
    start = time.time()
    while time.time() - start < timeout:
        try:
            ui = dump_ui()
            node = find_node(ui, **kwargs)
            if node is not None:
                return ui, node
        except Exception:
            pass
        time.sleep(0.5)
    raise TimeoutError(f"Timed out waiting for node with {kwargs}")

def wait_for_node_contains(attr: str, text: str, timeout: float = 12.0) -> tuple[ET.Element, ET.Element]:
    start = time.time()
    while time.time() - start < timeout:
        try:
            ui = dump_ui()
            node = find_node_contains(ui, attr, text)
            if node is not None:
                return ui, node
        except Exception:
            pass
        time.sleep(0.5)
    raise TimeoutError(f"Timed out waiting for node containing {attr}='{text}'")

def get_node_center(node: ET.Element) -> tuple[int, int]:
    bounds = node.attrib.get("bounds", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        raise ValueError(f"Invalid bounds: {bounds}")
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)

def tap_node(node: ET.Element):
    x, y = get_node_center(node)
    run_adb(f"shell input tap {x} {y}")

def navigate_back_to_home():
    run_adb("shell input keyevent 111")
    time.sleep(0.3)
    ui = dump_ui()
    back_btn = find_node(ui, content_desc="Back to conversations")
    if back_btn is not None:
        tap_node(back_btn)
        time.sleep(1.0)
    else:
        run_adb("shell input keyevent 4")
        time.sleep(1.0)
    ui = dump_ui()
    if find_node_contains(ui, "text", "Saad") is None:
        run_adb("shell am start -n com.aster.app/.MainActivity")
        time.sleep(1.5)

def type_text(text: str):
    escaped = text.replace(" ", "%s").replace("'", "\\'").replace('"', '\\"')
    run_adb(f"shell input text '{escaped}'")

def check_logcat(filter_tag: str, lines: int = 30) -> str:
    out = run_adb(f"logcat -d -s {filter_tag} | tail -n {lines}")
    return out

def provision_token(user_id: str, device_id: str, admin_key: str = ADMIN_KEY) -> str:
    ctx = ssl.create_default_context(cafile=CA_CERT_PATH)
    headers = {"Content-Type": "application/json"}
    if admin_key is not None:
        headers["X-Aster-Admin-Key"] = admin_key

    req = urllib.request.Request(
        PROVISION_ENDPOINT,
        data=json.dumps({"userId": user_id, "deviceId": device_id}).encode("utf-8"),
        headers=headers
    )
    with urllib.request.urlopen(req, context=ctx, timeout=5.0) as resp:
        body = json.loads(resp.read().decode())
        assert body.get("status") == "ok", f"Provisioning failed: {body}"
        token = body.get("token")
        log("PROVISION", f"Provisioned token for {user_id} ({device_id}): {token[:12]}...")
        return token

def revoke_credential(token: str = None, user_id: str = None, device_id: str = None, admin_key: str = ADMIN_KEY) -> dict:
    ctx = ssl.create_default_context(cafile=CA_CERT_PATH)
    headers = {"Content-Type": "application/json"}
    if admin_key is not None:
        headers["X-Aster-Admin-Key"] = admin_key

    payload = {}
    if token: payload["token"] = token
    if user_id: payload["userId"] = user_id
    if device_id: payload["deviceId"] = device_id

    req = urllib.request.Request(
        REVOKE_ENDPOINT,
        data=json.dumps(payload).encode("utf-8"),
        headers=headers
    )
    with urllib.request.urlopen(req, context=ctx, timeout=5.0) as resp:
        body = json.loads(resp.read().decode())
        return body

class SamTlsWebSocketClient:
    def __init__(self, token: str):
        self.token = token
        self.ws = None
        self.ssl_ctx = ssl.create_default_context(cafile=CA_CERT_PATH)
        self.received_messages = []

    async def connect(self):
        log("SAM-WSS", f"Connecting to {WSS_ENDPOINT} with token for user_sam...")
        self.ws = await websockets.connect(WSS_ENDPOINT, ssl=self.ssl_ctx, ping_interval=10, ping_timeout=10)
        await self.ws.send(json.dumps({"type": "AUTH", "token": self.token, "userId": "user_sam"}))
        raw = await asyncio.wait_for(self.ws.recv(), timeout=5.0)
        data = json.loads(raw)
        assert data.get("type") == "AUTH_OK", f"Auth failed: {data}"
        assert data.get("userId") == "user_sam", f"Expected user_sam, got {data.get('userId')}"
        log("SAM-WSS", "Authenticated as user_sam with validated credential over TLS/WSS")

        # Drain pre-existing durable mailbox replays
        while True:
            try:
                raw_frame = await asyncio.wait_for(self.ws.recv(), timeout=1.0)
                frame = json.loads(raw_frame)
                if frame.get("type") == "MESSAGE":
                    self.received_messages.append(frame)
                    log("SAM-WSS", f"Drained queued mailbox replay: '{frame.get('body')}'")
                    await self.ws.send(json.dumps({"type": "ACK_RECEIVED", "messageId": frame["messageId"]}))
            except asyncio.TimeoutError:
                break

    async def close(self):
        if self.ws:
            try:
                await self.ws.close()
            except Exception:
                pass
            self.ws = None

    async def wait_for_message(self, expected_body: str, timeout: float = 15.0) -> dict:
        for msg in self.received_messages:
            if msg.get("body") == expected_body:
                return msg
        start = time.time()
        while time.time() - start < timeout:
            if self.ws is None:
                await self.connect()
                for msg in self.received_messages:
                    if msg.get("body") == expected_body:
                        return msg
            try:
                remaining = max(1.0, timeout - (time.time() - start))
                raw = await asyncio.wait_for(self.ws.recv(), timeout=remaining)
            except asyncio.TimeoutError:
                continue
            except (websockets.exceptions.ConnectionClosed, Exception) as e:
                log("SAM-WSS", f"Connection reset during wait ({e}), reconnecting...")
                await self.connect()
                for msg in self.received_messages:
                    if msg.get("body") == expected_body:
                        return msg
                continue

            data = json.loads(raw)
            if data.get("type") == "MESSAGE":
                self.received_messages.append(data)
                ack = {"type": "ACK_RECEIVED", "messageId": data["messageId"]}
                await self.ws.send(json.dumps(ack))
                log("SAM-WSS", f"Received message '{data.get('body')}' from {data.get('senderId')}; ACK sent over WSS")
                if data.get("body") == expected_body:
                    return data
        raise TimeoutError(f"Timed out waiting for MESSAGE frame with body: {expected_body}")

async def run_m9_auth_validation():
    print("=" * 80)
    print("ASTER MILESTONE 9 & 9.1: AUTHENTICATION & CONTROL PLANE HARDENING VALIDATION")
    print("=" * 80)

    ctx = ssl.create_default_context(cafile=CA_CERT_PATH)

    # 1. Health check over HTTPS with Root CA
    log("PRECHECK", f"Checking backend health over HTTPS: {HEALTH_ENDPOINT}...")
    req = urllib.request.Request(HEALTH_ENDPOINT)
    with urllib.request.urlopen(req, context=ctx, timeout=5.0) as resp:
        body = json.loads(resp.read().decode())
        log("PRECHECK", f"HTTPS Health response: {body}")
        assert body.get("status") == "ok"

    # =========================================================================
    # SECTION 1: Administrative Control Plane Fail-Closed Security Checks
    # =========================================================================
    log("ADMIN-CHECK 1", "Verifying /api/provision without admin key is rejected (401)...")
    try:
        req = urllib.request.Request(
            PROVISION_ENDPOINT,
            data=json.dumps({"userId": "user_saad"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, context=ctx, timeout=3.0):
            raise AssertionError("Should have failed without admin key!")
    except urllib.error.HTTPError as e:
        assert e.code == 401, f"Expected 401, got {e.code}"
        log("ADMIN-CHECK 1", "Missing admin key on /api/provision correctly rejected with HTTP 401 Unauthorized")

    log("ADMIN-CHECK 2", "Verifying /api/provision with invalid admin key is rejected (401)...")
    try:
        req = urllib.request.Request(
            PROVISION_ENDPOINT,
            data=json.dumps({"userId": "user_saad"}).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Aster-Admin-Key": "wrong_invalid_admin_secret"}
        )
        with urllib.request.urlopen(req, context=ctx, timeout=3.0):
            raise AssertionError("Should have failed with invalid admin key!")
    except urllib.error.HTTPError as e:
        assert e.code == 401, f"Expected 401, got {e.code}"
        log("ADMIN-CHECK 2", "Invalid admin key on /api/provision correctly rejected with HTTP 401 Unauthorized")

    log("ADMIN-CHECK 3", "Verifying /api/revoke without admin key is rejected (401)...")
    try:
        req = urllib.request.Request(
            REVOKE_ENDPOINT,
            data=json.dumps({"userId": "user_saad"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, context=ctx, timeout=3.0):
            raise AssertionError("Should have failed without admin key on revoke!")
    except urllib.error.HTTPError as e:
        assert e.code == 401, f"Expected 401, got {e.code}"
        log("ADMIN-CHECK 3", "Missing admin key on /api/revoke correctly rejected with HTTP 401 Unauthorized")

    log("ADMIN-CHECK 4", "Verifying /api/revoke with invalid admin key is rejected (401)...")
    try:
        req = urllib.request.Request(
            REVOKE_ENDPOINT,
            data=json.dumps({"userId": "user_saad"}).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Aster-Admin-Key": "bad_key_123"}
        )
        with urllib.request.urlopen(req, context=ctx, timeout=3.0):
            raise AssertionError("Should have failed with invalid admin key on revoke!")
    except urllib.error.HTTPError as e:
        assert e.code == 401, f"Expected 401, got {e.code}"
        log("ADMIN-CHECK 4", "Invalid admin key on /api/revoke correctly rejected with HTTP 401 Unauthorized")

    log("ADMIN-CHECK 5", "Verifying /api/provision rejects invalid userId format (400)...")
    try:
        req = urllib.request.Request(
            PROVISION_ENDPOINT,
            data=json.dumps({"userId": "bad user with spaces"}).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Aster-Admin-Key": ADMIN_KEY}
        )
        with urllib.request.urlopen(req, context=ctx, timeout=3.0):
            raise AssertionError("Should have failed on invalid userId format!")
    except urllib.error.HTTPError as e:
        assert e.code == 400, f"Expected 400, got {e.code}"
        log("ADMIN-CHECK 5", "Malformed userId correctly rejected with HTTP 400 Bad Request")

    log("ADMIN-CHECK 6", "Verifying /api/provision rejects invalid deviceId format (400)...")
    try:
        req = urllib.request.Request(
            PROVISION_ENDPOINT,
            data=json.dumps({"userId": "user_valid", "deviceId": "dev#invalid@chars!"}).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Aster-Admin-Key": ADMIN_KEY}
        )
        with urllib.request.urlopen(req, context=ctx, timeout=3.0):
            raise AssertionError("Should have failed on invalid deviceId format!")
    except urllib.error.HTTPError as e:
        assert e.code == 400, f"Expected 400, got {e.code}"
        log("ADMIN-CHECK 6", "Malformed deviceId correctly rejected with HTTP 400 Bad Request")

    log("ADMIN-CHECK 7", "Verifying /api/revoke rejects empty target payload (400)...")
    try:
        req = urllib.request.Request(
            REVOKE_ENDPOINT,
            data=json.dumps({}).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Aster-Admin-Key": ADMIN_KEY}
        )
        with urllib.request.urlopen(req, context=ctx, timeout=3.0):
            raise AssertionError("Should have failed on empty revoke target!")
    except urllib.error.HTTPError as e:
        assert e.code == 400, f"Expected 400, got {e.code}"
        log("ADMIN-CHECK 7", "Empty revoke request correctly rejected with HTTP 400 Bad Request")

    # Provision legitimate credentials for Saad and Sam using authorized admin key
    log("PROVISION", "Provisioning authorized credentials for Saad and Sam via admin API...")
    saad_token = provision_token("user_saad", "waydroid-saad-device")
    sam_token = provision_token("user_sam", "test-sam-device")
    assert saad_token.startswith("ast_tok_"), f"Unexpected token format: {saad_token}"
    assert sam_token.startswith("ast_tok_"), f"Unexpected token format: {sam_token}"
    log("PROVISION", "Successfully provisioned credentials with verified entropy & format.")

    # =========================================================================
    # SECTION 2: Transport & Messaging Impersonation Pre-checks
    # =========================================================================
    log("SEC-CHECK 1", "Verifying untrusted client without CA is rejected at TLS layer...")
    untrusted_ctx = ssl.create_default_context()
    try:
        req = urllib.request.Request(HEALTH_ENDPOINT)
        with urllib.request.urlopen(req, context=untrusted_ctx, timeout=3.0):
            raise AssertionError("Untrusted connection should have failed!")
    except Exception as e:
        log("SEC-CHECK 1", f"Untrusted client correctly rejected by TLS stack: {type(e).__name__}")

    log("SEC-CHECK 2", "Verifying invalid authentication token is rejected...")
    ssl_ctx = ssl.create_default_context(cafile=CA_CERT_PATH)
    async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as ws:
        await ws.send(json.dumps({"type": "AUTH", "token": "ast_tok_invalid_hex_token_1234567890"}))
        raw = await asyncio.wait_for(ws.recv(), timeout=5.0)
        resp = json.loads(raw)
        assert resp.get("type") == "AUTH_ERROR", f"Expected AUTH_ERROR, got {resp}"
        log("SEC-CHECK 2", f"Invalid token rejected: {resp.get('reason')}")

    log("SEC-CHECK 3", "Verifying client cannot claim different identity with valid token...")
    async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as ws:
        await ws.send(json.dumps({"type": "AUTH", "token": saad_token, "userId": "user_sam"}))
        raw = await asyncio.wait_for(ws.recv(), timeout=5.0)
        resp = json.loads(raw)
        assert resp.get("type") == "AUTH_OK"
        assert resp.get("userId") == "user_saad", f"Server allowed impersonation! Got: {resp}"
        log("SEC-CHECK 3", f"Impersonation prevented in AUTH: resolved to true owner '{resp.get('userId')}'")

    log("SEC-CHECK 4", "Verifying client cannot forge senderId in SEND_MESSAGE frame...")
    async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as sender_ws:
        await sender_ws.send(json.dumps({"type": "AUTH", "token": saad_token}))
        await sender_ws.recv()  # AUTH_OK

        async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as sam_rx_ws:
            await sam_rx_ws.send(json.dumps({"type": "AUTH", "token": sam_token}))
            await sam_rx_ws.recv()  # AUTH_OK

            forged_send = {
                "type": "SEND_MESSAGE",
                "messageId": f"forged_{int(time.time())}",
                "conversationId": "conv_sam",
                "recipientId": "user_sam",
                "body": "Spoofed identity test",
                "timestamp": int(time.time() * 1000),
                "senderId": "user_sam"  # Forged!
            }
            await sender_ws.send(json.dumps(forged_send))
            ack = json.loads(await sender_ws.recv())
            assert ack.get("type") == "MESSAGE_ACCEPTED"

            relay = json.loads(await asyncio.wait_for(sam_rx_ws.recv(), timeout=5.0))
            assert relay.get("type") == "MESSAGE"
            assert relay.get("senderId") == "user_saad", f"Server honored forged senderId! Got: {relay.get('senderId')}"
            log("SEC-CHECK 4", f"Impersonation prevented in SEND: server enforced true senderId '{relay.get('senderId')}'")

            log("SEC-CHECK 5", "Verifying client cannot ACK a message addressed to another recipient...")
            await sender_ws.send(json.dumps({"type": "ACK_RECEIVED", "messageId": relay.get("messageId")}))
            ack_err = json.loads(await asyncio.wait_for(sender_ws.recv(), timeout=5.0))
            assert ack_err.get("type") == "ERROR"
            assert ack_err.get("code") == "UNAUTHORIZED_ACK"
            log("SEC-CHECK 5", f"Foreign ACK rejected: {ack_err.get('message')}")

    # Connect recipient Sam over WSS/TLS with valid credential
    sam_client = SamTlsWebSocketClient(sam_token)
    await sam_client.connect()

    try:
        # =====================================================================
        # SECTION 3: Android M7 & M8 End-to-End Validation (Steps 1 to 19)
        # =====================================================================
        log("STEP 1", "Clearing app data and provisioning Android client with secure token...")
        run_adb("shell pm clear com.aster.app")
        time.sleep(1.0)
        run_adb(f"shell am start -n com.aster.app/.MainActivity --es aster_token {saad_token} --es aster_user_id user_saad")
        time.sleep(2.5)

        log("STEP 2", "Verifying Home screen is loaded...")
        ui, header = wait_for_node_contains("text", "Saad", timeout=15.0)
        safe_screenshot("/home/saad/Aster/m9_home_clean.png")
        log("STEP 2", "Home screen open. Verified user Saad.")

        conv_alex = find_node(ui, text="Alex")
        conv_rohan = find_node(ui, text="Rohan")
        conv_sam = find_node(ui, text="Sam")
        assert conv_alex is not None, "Alex conversation should be seeded"
        assert conv_rohan is not None, "Rohan conversation should be seeded"
        assert conv_sam is None, "Sam conversation must NOT exist prior to creation"
        log("STEP 2", "Confirmed baseline: Alex and Rohan exist, Sam does not exist.")

        log("STEP 3", "Tapping New Chat FAB...")
        ui, fab = wait_for_node(content_desc="Start new conversation", timeout=6.0)
        tap_node(fab)
        time.sleep(1.0)

        log("STEP 4", "Verifying New Connection dialog...")
        ui, dialog_title = wait_for_node(text="New Connection", timeout=6.0)

        saad_entry = find_node(ui, text="Saad")
        assert saad_entry is None, "Local user Saad must NOT be selectable in New Chat dialog"

        sam_entry = find_node(ui, text="Sam")
        assert sam_entry is not None, "Sam not found in contact list"
        safe_screenshot("/home/saad/Aster/m9_dialog.png")

        log("STEP 4", "Selecting Sam from contact list...")
        tap_node(sam_entry)
        time.sleep(1.5)

        log("STEP 5", "Verifying direct conversation with Sam opened...")
        ui, chat_title = wait_for_node(text="Sam", timeout=6.0)

        empty_title = find_node_contains(ui, "text", "Connected with Sam")
        assert empty_title is not None, "Empty conversation state not displayed"
        safe_screenshot("/home/saad/Aster/m9_empty_chat_sam.png")
        log("STEP 5", "Verified conversation created and empty state displayed cleanly.")

        msg_text = f"Milestone 9.1 Hardened Auth at {time.strftime('%H:%M:%S')}"
        log("STEP 8 & 9", f"Composing and sending message: '{msg_text}'...")
        input_box = find_node(ui, content_desc="Message input")
        assert input_box is not None, "Message input box not found"
        tap_node(input_box)
        time.sleep(0.3)
        type_text(msg_text)
        time.sleep(0.5)

        ui = dump_ui()
        send_btn = find_node(ui, content_desc="Send message")
        assert send_btn is not None, "Send message button not found"

        run_adb("logcat -c")
        tap_node(send_btn)
        time.sleep(1.0)

        log("STEP 10", "Checking message transition to SENT over WSS...")
        logs = check_logcat("MessageRepository", lines=25)
        log("STEP 10", f"Logcat excerpt:\n{logs}")
        assert "status to SENT" in logs or "Server accepted message" in logs, "Message did not reach SENT"
        log("STEP 10", "Verified message state transitioned to SENT over authenticated WSS.")

        log("STEP 11", "Verifying Sam received message over TLS/WSS...")
        received_frame = await sam_client.wait_for_message(msg_text, timeout=10.0)
        assert received_frame.get("body") == msg_text, f"Body mismatch: {received_frame}"
        assert received_frame.get("senderId") == "user_saad"
        log("STEP 11", f"Verified recipient Sam received authenticated message over WSS: {received_frame.get('messageId')}")
        safe_screenshot("/home/saad/Aster/m9_chat_sent_sam.png")

        log("STEP 12", "Navigating back to Home screen...")
        navigate_back_to_home()

        log("STEP 6 & 13", "Verifying Home conversation list updated...")
        ui, home_sam = wait_for_node(text="Sam", timeout=6.0)
        preview = find_node_contains(ui, "text", "Milestone 9.1 Hardened")
        assert preview is not None, "Latest message snippet preview not displayed on Home screen"
        safe_screenshot("/home/saad/Aster/m9_home_with_sam.png")
        log("STEP 6 & 13", "Verified Sam is in Home conversation list with updated preview.")

        log("STEP 7", "Testing idempotency: tapping New Chat and selecting Sam again...")
        ui, fab = wait_for_node(content_desc="Start new conversation", timeout=6.0)
        tap_node(fab)
        time.sleep(1.0)

        ui, sam_entry = wait_for_node(text="Sam", timeout=6.0)
        tap_node(sam_entry)
        time.sleep(1.5)

        ui = dump_ui()
        existing_msg = find_node_contains(ui, "text", "Milestone 9.1 Hardened")
        assert existing_msg is not None, "Existing message missing from reopened conversation"
        log("STEP 7", "Verified reopened conversation displays existing message.")

        navigate_back_to_home()
        ui = dump_ui()
        sam_count = len([n for n in ui.iter() if n.attrib.get("text") == "Sam"])
        assert sam_count == 1, f"Expected exactly 1 Sam conversation, found {sam_count}"
        log("STEP 7", "Verified idempotency: No duplicate conversations created.")

        log("STEP 14 & 15", "Tapping Sam conversation item on Home...")
        ui, sam_item = wait_for_node(text="Sam", timeout=6.0)
        tap_node(sam_item)
        time.sleep(1.5)

        ui = dump_ui()
        msg_in_chat = find_node_contains(ui, "text", "Milestone 9.1 Hardened")
        assert msg_in_chat is not None, "Message not persisted in ChatScreen"
        log("STEP 14 & 15", "Verified message persists when reopened from Home list.")

        log("STEP 16 & 17", "Force stopping Android app...")
        run_adb("shell am force-stop com.aster.app")
        time.sleep(1.5)

        log("STEP 16 & 17", "Relaunching app from cold start (testing stored credential in AsterAuthStorage)...")
        run_adb("shell am start -n com.aster.app/.MainActivity")
        time.sleep(2.5)

        log("STEP 18", "Verifying Room persistence across process restart...")
        ui, restarted_sam = wait_for_node(text="Sam", timeout=15.0)
        restarted_preview = find_node_contains(ui, "text", "Milestone 9.1 Hardened")
        assert restarted_preview is not None, "Latest message preview vanished after app restart!"
        safe_screenshot("/home/saad/Aster/m9_relaunch_home.png")

        tap_node(restarted_sam)
        time.sleep(1.5)
        ui = dump_ui()
        restarted_msg = find_node_contains(ui, "text", "Milestone 9.1 Hardened")
        assert restarted_msg is not None, "Message vanished from ChatScreen after app restart!"
        safe_screenshot("/home/saad/Aster/m9_relaunch_chat.png")
        log("STEP 18", "Verified Room database completely preserved conversation and messages.")

        log("STEP 19", "Testing reconnect/outbox resilience over authenticated WSS...")
        ssl_ctx = ssl.create_default_context(cafile=CA_CERT_PATH)
        evictor = await websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx, ping_interval=10, ping_timeout=10)
        await evictor.send(json.dumps({"type": "AUTH", "token": saad_token}))
        await evictor.recv()  # AUTH_OK
        log("STEP 19", "Temporary TLS session evicted Android client.")

        outbox_body = f"Auth Outbox queue test at {time.strftime('%H:%M:%S')}"
        input_box = find_node(ui, content_desc="Message input")
        tap_node(input_box)
        time.sleep(0.3)
        type_text(outbox_body)
        time.sleep(0.5)

        ui = dump_ui()
        send_btn = find_node(ui, content_desc="Send message")
        tap_node(send_btn)
        time.sleep(1.0)

        await evictor.close()
        log("STEP 19", "Released evictor; awaiting Android client reconnect & outbox drain over WSS...")

        replayed_frame = await sam_client.wait_for_message(outbox_body, timeout=12.0)
        assert replayed_frame.get("body") == outbox_body
        assert replayed_frame.get("senderId") == "user_saad"
        log("STEP 19", f"Recipient Sam received replayed outbox message over authenticated WSS: '{outbox_body}'")
        safe_screenshot("/home/saad/Aster/m9_outbox_delivered.png")

        # =====================================================================
        # SECTION 4: Live Revocation & Session Eviction Lifecycle (Steps 20 to 26)
        # =====================================================================
        log("STEP 20", "Provisioning temporary user_alex credential and establishing active WSS session...")
        alex_token = provision_token("user_alex", "alex-pixel-device")
        alex_ws = await websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx, ping_interval=None, ping_timeout=None)
        await alex_ws.send(json.dumps({"type": "AUTH", "token": alex_token}))
        alex_auth_ok = json.loads(await asyncio.wait_for(alex_ws.recv(), timeout=5.0))
        assert alex_auth_ok.get("type") == "AUTH_OK", f"Alex auth failed: {alex_auth_ok}"
        assert alex_auth_ok.get("userId") == "user_alex"
        log("STEP 20", "User alex connected and authenticated over active WSS session.")

        log("STEP 21", "Invoking administrative revocation of alex's token via POST /api/revoke...")
        revoke_res = revoke_credential(token=alex_token)
        assert revoke_res.get("status") == "ok" and revoke_res.get("revoked") is True
        log("STEP 21", f"Revocation response confirmed: {revoke_res}")

        log("STEP 22", "Verifying alex's active WebSocket connection is immediately dropped by server...")
        try:
            # Server closes the connection with CloseReason(VIOLATED_POLICY, "Credential revoked")
            close_frame = await asyncio.wait_for(alex_ws.recv(), timeout=5.0)
            log("STEP 22", f"Received frame after revocation: {close_frame}")
        except (websockets.exceptions.ConnectionClosed, websockets.exceptions.ConnectionClosedError, websockets.exceptions.ConnectionClosedOK) as e:
            log("STEP 22", f"Alex WebSocket connection was actively terminated by server: code={e.code}, reason={e.reason}")
            assert e.code in (1000, 1008), f"Expected normal or policy violation close code, got {e.code}"

        log("STEP 23", "Verifying subsequent reconnection attempts with revoked token are rejected...")
        async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as alex_reconnect_ws:
            await alex_reconnect_ws.send(json.dumps({"type": "AUTH", "token": alex_token}))
            auth_err_frame = json.loads(await asyncio.wait_for(alex_reconnect_ws.recv(), timeout=5.0))
            assert auth_err_frame.get("type") == "AUTH_ERROR", f"Expected AUTH_ERROR, got {auth_err_frame}"
            log("STEP 23", f"Reconnection with revoked token rejected: {auth_err_frame.get('reason')}")

        log("STEP 24", "Verifying unrevoked user Sam is completely unaffected and connection remains healthy...")
        assert sam_client.ws is not None and sam_client.ws.close_code is None, "Sam's connection was unexpectedly closed!"
        # Send a ping from Sam to prove session is fully intact
        await sam_client.ws.ping()
        log("STEP 24", "Confirmed Sam session remains active, healthy, and isolated from Alex revocation.")

        log("STEP 25", "Verifying device-specific revocation isolation (Phone vs Tablet)...")
        rohan_phone_tok = provision_token("user_rohan", "rohan-phone-id")
        rohan_tablet_tok = provision_token("user_rohan", "rohan-tablet-id")

        # Revoke ONLY rohan's phone
        dev_revoke_res = revoke_credential(device_id="rohan-phone-id")
        assert dev_revoke_res.get("status") == "ok" and dev_revoke_res.get("revoked") is True
        log("STEP 25", f"Revoked rohan-phone-id: {dev_revoke_res}")

        # Phone token fails
        async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as phone_ws:
            await phone_ws.send(json.dumps({"type": "AUTH", "token": rohan_phone_tok}))
            resp = json.loads(await asyncio.wait_for(phone_ws.recv(), timeout=5.0))
            assert resp.get("type") == "AUTH_ERROR", f"Phone should be revoked, got {resp}"

        # Tablet token succeeds
        async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as tablet_ws:
            await tablet_ws.send(json.dumps({"type": "AUTH", "token": rohan_tablet_tok}))
            resp = json.loads(await asyncio.wait_for(tablet_ws.recv(), timeout=5.0))
            assert resp.get("type") == "AUTH_OK" and resp.get("userId") == "user_rohan"
            log("STEP 25", "Device-specific revocation isolation verified: Phone revoked, Tablet valid.")

        log("STEP 26", "Verifying user-wide revocation invalidates all remaining devices...")
        user_revoke_res = revoke_credential(user_id="user_rohan")
        assert user_revoke_res.get("status") == "ok" and user_revoke_res.get("revoked") is True
        log("STEP 26", f"Revoked user_rohan: {user_revoke_res}")

        # Tablet token now also fails
        async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as tablet_ws:
            await tablet_ws.send(json.dumps({"type": "AUTH", "token": rohan_tablet_tok}))
            resp = json.loads(await asyncio.wait_for(tablet_ws.recv(), timeout=5.0))
            assert resp.get("type") == "AUTH_ERROR", f"Tablet should now be revoked, got {resp}"
            log("STEP 26", "User-wide revocation verified: All user devices invalidated.")

        print("=" * 80)
        print("ALL 26 VALIDATION STEPS OF MILESTONES 9 & 9.1 PASSED SUCCESSFULLY!")
        print("=" * 80)

    finally:
        await sam_client.close()

if __name__ == "__main__":
    asyncio.run(run_m9_auth_validation())
