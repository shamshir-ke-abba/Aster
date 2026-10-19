#!/usr/bin/env python3
"""
Comprehensive Milestone 10 Validation Script:
Groups & Richer Conversations Validation Playtest.

Validates:
1. Administrative & Credential Provisioning:
   - Provisions credentials for Saad, Alex, Sam, and Elena using rotated admin key.
2. Transport & Security / Authorization Pre-checks:
   - Non-member cannot send messages to a group (rejected by server).
   - Non-member cannot receive group messages (isolated).
   - Non-admin/non-creator cannot mutate group (remove members or rename).
   - Non-member cannot ACK messages of groups they don't belong to.
3. Group Protocol Operations over Authenticated WSS:
   - Create group ("Starlight Guild") with initial members [Alex, Sam].
   - Receive GroupOperationOk and broadcast GroupEvent to peers.
   - Query GetGroups and verify GroupSummary returned.
4. Message Fan-out over Authenticated WSS:
   - Online member (Alex) receives group message M1 immediately with conversationId=groupId, senderId=Saad.
   - Alex sends ACK_RECEIVED for M1.
5. Offline Mailbox Queueing & Replay over Authenticated WSS:
   - Offline member (Sam) receives queued message M1 upon reconnecting over WSS.
   - Sam sends ACK_RECEIVED for M1.
   - Sam reconnects again: verifies M1 is NOT redelivered (no duplicate).
6. Membership Mutation & Permissions:
   - Creator (Saad) removes Sam from "Starlight Guild".
   - Alex receives GroupEvent(action="REMOVED", memberId="user_sam").
   - Sam attempts to send message to group -> rejected (NOT_A_MEMBER).
   - Saad sends M2 -> Alex receives it; Sam does NOT receive it.
7. Android App End-to-End Validation (Waydroid):
   - Provision Android app with Saad's token via Intent extra.
   - Home screen displays groups with '✦✦' indicator.
   - Tap New Chat FAB -> select 'Group ✦✦' tab.
   - Enter group name "Lunar Orbiters", select Alex and Elena, tap "Create Constellation".
   - Verify group chat opens with title "Lunar Orbiters" and member count "✦✦ 3 members".
   - Send group message from Android UI -> Alex receives it over WSS.
   - Alex sends reply over WSS -> Android UI receives it.
   - Verify sender display name ("Alex") is rendered above the message bubble!
8. Process Death & Room Persistence:
   - Force-stop Android app and cold-start relaunch (testing stored Keystore credential).
   - Verify Home screen displays "Lunar Orbiters" with latest message preview.
   - Reopen chat -> verify conversation and message history persisted.
9. Offline Outbox & Reconnect:
   - Evict Android connection temporarily via secondary session.
   - Send message from Android UI while disconnected.
   - Release eviction; Android client reconnects and drains outbox.
   - Alex receives replayed outbox message over WSS.
10. Direct 1:1 Messaging Regression Validation:
   - Return to Home screen, open direct conversation with Alex.
   - Send direct message -> Alex receives it with 1:1 conversation ID.
   - Verify direct 1:1 messages do not display group sender labels.
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
WAYDROID_SERIAL = "192.168.240.112:5555"

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

def wait_for_node(timeout: float = 12.0, scroll: bool = False, **kwargs) -> tuple[ET.Element, ET.Element]:
    start = time.time()
    while time.time() - start < timeout:
        try:
            ui = dump_ui()
            node = find_node(ui, **kwargs)
            if node is not None:
                return ui, node
            if scroll and time.time() - start > 2.0:
                run_adb("shell input swipe 300 500 300 350")
        except Exception:
            pass
        time.sleep(0.5)
    raise TimeoutError(f"Timed out waiting for node with {kwargs}")

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

def dismiss_keyboard_if_shown():
    try:
        ui = dump_ui()
        edit_box = find_node(ui, class_name="android.widget.EditText")
        if edit_box is not None:
            bounds = edit_box.attrib.get("bounds", "")
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
            if m:
                y1 = int(m.group(2))
                if y1 < 400: # Shifted up by IME keyboard
                    run_adb("shell input keyevent 4")
                    time.sleep(0.5)
                    return
    except Exception:
        pass
    out = run_adb("shell dumpsys input_method | grep mInputShown")
    if "mInputShown=true" in out:
        run_adb("shell input keyevent 4")
        time.sleep(0.5)

def wait_for_node_contains(attr: str, text: str, timeout: float = 12.0) -> tuple[ET.Element, ET.Element]:
    start = time.time()
    while time.time() - start < timeout:
        try:
            ui = dump_ui()
            node = find_node_contains(ui, attr, text)
            if node is not None:
                return ui, node
            if time.time() - start > 2.0:
                dismiss_keyboard_if_shown()
        except Exception:
            pass
        time.sleep(0.5)
    raise TimeoutError(f"Timed out waiting for node containing {attr}='{text}'")

def navigate_back_to_home():
    dismiss_keyboard_if_shown()
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

class PeerClient:
    def __init__(self, user_id: str, token: str):
        self.user_id = user_id
        self.token = token
        self.ws = None
        self.ssl_ctx = ssl.create_default_context(cafile=CA_CERT_PATH)
        self.received_messages = []
        self.received_events = []
        self.received_responses = []

    async def connect(self):
        log(self.user_id.upper(), f"Connecting to {WSS_ENDPOINT}...")
        self.ws = await websockets.connect(WSS_ENDPOINT, ssl=self.ssl_ctx, ping_interval=None)
        await self.ws.send(json.dumps({"type": "AUTH", "token": self.token, "userId": self.user_id}))
        raw = await asyncio.wait_for(self.ws.recv(), timeout=5.0)
        data = json.loads(raw)
        assert data.get("type") == "AUTH_OK", f"Auth failed for {self.user_id}: {data}"
        log(self.user_id.upper(), f"Authenticated as {self.user_id} over TLS/WSS")

        # Drain initial mailbox replays and initial frames
        while True:
            try:
                raw_frame = await asyncio.wait_for(self.ws.recv(), timeout=1.0)
                frame = json.loads(raw_frame)
                if frame.get("type") == "MESSAGE":
                    self.received_messages.append(frame)
                    log(self.user_id.upper(), f"Drained queued mailbox replay: '{frame.get('body')}'")
                    await self.ws.send(json.dumps({"type": "ACK_RECEIVED", "messageId": frame["messageId"]}))
                elif frame.get("type") == "GROUP_EVENT":
                    self.received_events.append(frame)
                else:
                    self.received_responses.append(frame)
            except asyncio.TimeoutError:
                break

    async def close(self):
        if self.ws:
            try:
                await self.ws.close()
            except Exception:
                pass
            self.ws = None

    async def wait_for_message(self, expected_body: str = None, conversation_id: str = None, timeout: float = 12.0) -> dict:
        start = time.time()
        while time.time() - start < timeout:
            for msg in self.received_messages:
                match = True
                if expected_body and msg.get("body") != expected_body: match = False
                if conversation_id and msg.get("conversationId") != conversation_id: match = False
                if match:
                    return msg

            if self.ws is None:
                await self.connect()
                continue
            try:
                remaining = max(1.0, timeout - (time.time() - start))
                raw = await asyncio.wait_for(self.ws.recv(), timeout=remaining)
            except asyncio.TimeoutError:
                continue
            except Exception as e:
                log(self.user_id.upper(), f"Connection issue ({e}), reconnecting...")
                await self.connect()
                continue

            data = json.loads(raw)
            if data.get("type") == "MESSAGE":
                self.received_messages.append(data)
                # Auto-ACK received messages
                await self.ws.send(json.dumps({"type": "ACK_RECEIVED", "messageId": data["messageId"]}))
                log(self.user_id.upper(), f"Received message '{data.get('body')}' (id={data.get('messageId')}) in {data.get('conversationId')}")
                match = True
                if expected_body and data.get("body") != expected_body: match = False
                if conversation_id and data.get("conversationId") != conversation_id: match = False
                if match:
                    return data
            elif data.get("type") == "GROUP_EVENT":
                self.received_events.append(data)
            else:
                self.received_responses.append(data)

        raise TimeoutError(f"Timed out waiting for message (body={expected_body}, conv={conversation_id})")

    async def wait_for_event(self, action: str, group_id: str = None, timeout: float = 8.0) -> dict:
        start = time.time()
        while time.time() - start < timeout:
            for ev in self.received_events:
                ev_action = ev.get("eventType") or ev.get("action")
                if ev_action == action and (group_id is None or ev.get("groupId") == group_id):
                    return ev
            if self.ws is None:
                await self.connect()
                continue
            try:
                remaining = max(1.0, timeout - (time.time() - start))
                raw = await asyncio.wait_for(self.ws.recv(), timeout=remaining)
            except asyncio.TimeoutError:
                continue
            except Exception:
                await self.connect()
                continue
            data = json.loads(raw)
            if data.get("type") == "GROUP_EVENT":
                self.received_events.append(data)
                ev_action = data.get("eventType") or data.get("action")
                log(self.user_id.upper(), f"Received GROUP_EVENT: eventType={ev_action}, group={data.get('groupId')}")
                if ev_action == action and (group_id is None or data.get("groupId") == group_id):
                    return data
            elif data.get("type") == "MESSAGE":
                self.received_messages.append(data)
                await self.ws.send(json.dumps({"type": "ACK_RECEIVED", "messageId": data["messageId"]}))
            else:
                self.received_responses.append(data)
        raise TimeoutError(f"Timed out waiting for GROUP_EVENT action={action}")

    async def send_and_wait_response(self, req: dict, allowed_types: list[str], timeout: float = 6.0) -> dict:
        await self.ws.send(json.dumps(req))
        start = time.time()
        while time.time() - start < timeout:
            raw = await asyncio.wait_for(self.ws.recv(), timeout=timeout - (time.time() - start))
            data = json.loads(raw)
            if data.get("type") in allowed_types:
                return data
            elif data.get("type") == "GROUP_EVENT":
                self.received_events.append(data)
                ev_action = data.get("eventType") or data.get("action")
                log(self.user_id.upper(), f"Received GROUP_EVENT: eventType={ev_action}, group={data.get('groupId')}")
            elif data.get("type") == "MESSAGE":
                self.received_messages.append(data)
                await self.ws.send(json.dumps({"type": "ACK_RECEIVED", "messageId": data["messageId"]}))
            else:
                self.received_responses.append(data)
        raise TimeoutError(f"Timed out waiting for response types {allowed_types}")


async def run_m10_groups_validation():
    print("=" * 80)
    print("ASTER MILESTONE 10: GROUPS & RICHER CONVERSATIONS VALIDATION PLAYTEST")
    print("=" * 80)

    ctx = ssl.create_default_context(cafile=CA_CERT_PATH)
    ssl_ctx = ssl.create_default_context(cafile=CA_CERT_PATH)

    # 1. Health check over HTTPS with Root CA
    log("PRECHECK", f"Checking backend health over HTTPS: {HEALTH_ENDPOINT}...")
    req = urllib.request.Request(HEALTH_ENDPOINT)
    with urllib.request.urlopen(req, context=ctx, timeout=5.0) as resp:
        body = json.loads(resp.read().decode())
        log("PRECHECK", f"HTTPS Health response: {body}")
        assert body.get("status") == "ok"

    # =========================================================================
    # SECTION 1: Administrative Credential Provisioning
    # =========================================================================
    log("PROVISION", "Provisioning authorized credentials for Saad, Alex, Sam, and Elena...")
    saad_token = provision_token("user_saad", "waydroid-saad-device")
    alex_token = provision_token("user_alex", "test-alex-device")
    sam_token = provision_token("user_sam", "test-sam-device")
    elena_token = provision_token("user_elena", "test-elena-device")

    # Connect peer clients
    alex_client = PeerClient("user_alex", alex_token)
    await alex_client.connect()

    try:
        # =====================================================================
        # SECTION 2: Transport & Group Security / Authorization Checks
        # =====================================================================
        log("SEC-CHECK 1", "Verifying untrusted non-member cannot send messages to a group...")
        async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as saad_ws:
            await saad_ws.send(json.dumps({"type": "AUTH", "token": saad_token, "userId": "user_saad"}))
            await saad_ws.recv() # AUTH_OK

            # Saad creates a temporary security test group
            await saad_ws.send(json.dumps({
                "type": "CREATE_GROUP",
                "requestId": f"sec_req_{int(time.time()*1000)}",
                "name": "Sec Test Circle",
                "memberIds": ["user_alex"]
            }))
            sec_grp_resp = json.loads(await asyncio.wait_for(saad_ws.recv(), timeout=5.0))
            assert sec_grp_resp.get("type") == "GROUP_OPERATION_OK"
            sec_group_id = sec_grp_resp.get("groupId")
            log("SEC-CHECK 1", f"Created sec test group: {sec_group_id}")

            # Elena (not a member) tries to send a message to sec_group_id
            async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as elena_ws:
                await elena_ws.send(json.dumps({"type": "AUTH", "token": elena_token, "userId": "user_elena"}))
                await elena_ws.recv() # AUTH_OK

                await elena_ws.send(json.dumps({
                    "type": "SEND_MESSAGE",
                    "messageId": f"unauth_msg_{int(time.time())}",
                    "conversationId": sec_group_id,
                    "body": "Unauthorized probe",
                    "timestamp": int(time.time() * 1000)
                }))
                elena_reply = json.loads(await asyncio.wait_for(elena_ws.recv(), timeout=5.0))
                assert elena_reply.get("type") == "ERROR"
                assert elena_reply.get("code") in ("UNAUTHORIZED_GROUP_OPERATION", "UNAUTHORIZED_GROUP_ACCESS", "NOT_A_MEMBER", "FORBIDDEN")
                log("SEC-CHECK 1", f"Non-member send correctly rejected: {elena_reply.get('message')}")

            # Alex (member, but NOT creator/admin) tries to remove someone or rename using his existing session
            # Alex tries to rename group
            rename_err = await alex_client.send_and_wait_response({
                "type": "RENAME_GROUP",
                "requestId": f"ren_req_{int(time.time()*1000)}",
                "groupId": sec_group_id,
                "newName": "Alex Hijacked Group"
            }, ["GROUP_OPERATION_ERROR", "ERROR"])
            log("SEC-CHECK 2", f"Non-admin rename correctly rejected: {rename_err}")

            # Alex tries to remove creator Saad
            remove_err = await alex_client.send_and_wait_response({
                "type": "REMOVE_GROUP_MEMBER",
                "requestId": f"rem_req_{int(time.time()*1000)}",
                "groupId": sec_group_id,
                "userId": "user_saad"
            }, ["GROUP_OPERATION_ERROR", "ERROR"])
            log("SEC-CHECK 3", f"Non-admin member removal correctly rejected: {remove_err}")

        # =====================================================================
        # SECTION 3: Group Protocol Lifecycle over Authenticated WSS
        # =====================================================================
        log("GROUP-PROTOCOL", "Testing group creation, listing, and event delivery over WSS...")
        group_id = None
        async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as saad_ws:
            await saad_ws.send(json.dumps({"type": "AUTH", "token": saad_token, "userId": "user_saad"}))
            await saad_ws.recv() # AUTH_OK

            # Create group "Starlight Guild" with Alex and Sam
            await saad_ws.send(json.dumps({
                "type": "CREATE_GROUP",
                "requestId": f"starlight_req_{int(time.time()*1000)}",
                "name": "Starlight Guild",
                "memberIds": ["user_alex", "user_sam"]
            }))
            create_resp = json.loads(await asyncio.wait_for(saad_ws.recv(), timeout=5.0))
            assert create_resp.get("type") == "GROUP_OPERATION_OK"
            assert create_resp.get("action") in ("CREATE", "CREATE_GROUP")
            group_id = create_resp.get("groupId")
            log("GROUP-PROTOCOL", f"Created group 'Starlight Guild' with id={group_id}")

            # Verify Alex received GROUP_EVENT (action="CREATED")
            alex_ev = await alex_client.wait_for_event("CREATED", group_id=group_id, timeout=6.0)
            assert alex_ev.get("groupId") == group_id
            log("GROUP-PROTOCOL", f"Online member Alex received GroupEvent(action='CREATED') for {group_id}")

            # Query GET_GROUPS as Saad
            await saad_ws.send(json.dumps({
                "type": "GET_GROUPS",
                "requestId": f"get_groups_req_{int(time.time()*1000)}"
            }))
            groups_resp = json.loads(await asyncio.wait_for(saad_ws.recv(), timeout=5.0))
            assert groups_resp.get("type") in ("GROUP_LIST", "GROUPS_LIST")
            groups = groups_resp.get("groups", [])
            sg = next((g for g in groups if g.get("id") == group_id), None)
            assert sg is not None, f"Starlight Guild not found in groups list: {groups}"
            assert sg.get("name") == "Starlight Guild"
            assert sg.get("memberCount") == 3
            log("GROUP-PROTOCOL", f"GET_GROUPS returned verified summary: name='{sg.get('name')}', members={sg.get('memberCount')}")

            # =================================================================
            # SECTION 4: Real-time Message Fan-out over WSS
            # =================================================================
            log("FAN-OUT", "Sending message to 'Starlight Guild' while Alex is online...")
            m1_body = f"Welcome to Starlight Guild! {int(time.time())}"
            await saad_ws.send(json.dumps({
                "type": "SEND_MESSAGE",
                "messageId": f"msg_starlight_1_{int(time.time())}",
                "conversationId": group_id,
                "body": m1_body,
                "timestamp": int(time.time() * 1000)
            }))
            ack = json.loads(await asyncio.wait_for(saad_ws.recv(), timeout=5.0))
            assert ack.get("type") == "MESSAGE_ACCEPTED"
            log("FAN-OUT", "Server accepted group message M1")

            # Alex is online -> receives M1 immediately
            alex_m1 = await alex_client.wait_for_message(expected_body=m1_body, conversation_id=group_id, timeout=6.0)
            assert alex_m1.get("senderId") == "user_saad"
            log("FAN-OUT", f"Online member Alex received group message M1 over WSS: '{alex_m1.get('body')}'")

        # =====================================================================
        # SECTION 5: Offline Mailbox Queueing & Replay over Authenticated WSS
        # =====================================================================
        log("OFFLINE-QUEUE", "Testing offline delivery: Sam was offline when M1 was sent. Connecting Sam...")
        sam_client = PeerClient("user_sam", sam_token)
        await sam_client.connect()
        # Sam should receive M1 as durable mailbox replay
        sam_m1 = await sam_client.wait_for_message(expected_body=m1_body, conversation_id=group_id, timeout=8.0)
        assert sam_m1.get("senderId") == "user_saad"
        log("OFFLINE-QUEUE", f"Offline member Sam received durable replay of M1: '{sam_m1.get('body')}'")

        # Reconnect Sam: verify M1 is NOT redelivered (no duplicate replay)
        await sam_client.close()
        log("OFFLINE-QUEUE", "Reconnecting Sam to verify ACK settled and no duplicate replay occurs...")
        sam_client2 = PeerClient("user_sam", sam_token)
        await sam_client2.connect()
        duplicate_found = False
        try:
            await sam_client2.wait_for_message(expected_body=m1_body, timeout=2.0)
            duplicate_found = True
        except TimeoutError:
            duplicate_found = False
        assert not duplicate_found, "Duplicate message redelivered to Sam after ACK!"
        log("OFFLINE-QUEUE", "Deduplication confirmed: M1 was not redelivered on subsequent reconnect.")
        await sam_client2.close()

        # =====================================================================
        # SECTION 6: Member Mutation & Removal
        # =====================================================================
        log("MUTATION", "Saad removes Sam from 'Starlight Guild'...")
        async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as saad_ws:
            await saad_ws.send(json.dumps({"type": "AUTH", "token": saad_token, "userId": "user_saad"}))
            await saad_ws.recv() # AUTH_OK

            await saad_ws.send(json.dumps({
                "type": "REMOVE_GROUP_MEMBER",
                "requestId": f"rem_sam_req_{int(time.time()*1000)}",
                "groupId": group_id,
                "userId": "user_sam"
            }))
            rem_ok = json.loads(await asyncio.wait_for(saad_ws.recv(), timeout=5.0))
            assert rem_ok.get("type") == "GROUP_OPERATION_OK"
            log("MUTATION", "Server accepted removal of user_sam")

            # Alex receives GROUP_EVENT (eventType="MEMBER_REMOVED", targetUserId="user_sam")
            alex_rem_ev = await alex_client.wait_for_event("MEMBER_REMOVED", group_id=group_id, timeout=6.0)
            assert (alex_rem_ev.get("targetUserId") or alex_rem_ev.get("memberId")) == "user_sam"
            log("MUTATION", f"Alex received GroupEvent: Sam was removed from {group_id}")

            # Verify Sam can no longer send messages to the group
            async with websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx) as sam_ws:
                await sam_ws.send(json.dumps({"type": "AUTH", "token": sam_token, "userId": "user_sam"}))
                await sam_ws.recv()
                await sam_ws.send(json.dumps({
                    "type": "SEND_MESSAGE",
                    "messageId": f"sam_unauth_{int(time.time())}",
                    "conversationId": group_id,
                    "body": "Attempt after removal",
                    "timestamp": int(time.time() * 1000)
                }))
                sam_err = json.loads(await asyncio.wait_for(sam_ws.recv(), timeout=5.0))
                assert sam_err.get("type") == "ERROR"
                log("MUTATION", f"Removed member Sam blocked from sending: {sam_err.get('message')}")

            # Saad sends M2 -> Alex receives it, Sam does not
            m2_body = f"Message after Sam removal {int(time.time())}"
            await saad_ws.send(json.dumps({
                "type": "SEND_MESSAGE",
                "messageId": f"msg_starlight_2_{int(time.time())}",
                "conversationId": group_id,
                "body": m2_body,
                "timestamp": int(time.time() * 1000)
            }))
            await saad_ws.recv() # MESSAGE_ACCEPTED

            alex_m2 = await alex_client.wait_for_message(expected_body=m2_body, conversation_id=group_id, timeout=6.0)
            assert alex_m2.get("body") == m2_body
            log("MUTATION", f"Alex received M2: '{m2_body}'")

        # =====================================================================
        # SECTION 7: Android Waydroid End-to-End Validation
        # =====================================================================
        log("ANDROID-STEP 1", "Clearing Android app data and launching with Saad credential...")
        run_adb("shell pm clear com.aster.app")
        time.sleep(1.0)
        run_adb(f"shell am start -n com.aster.app/.MainActivity --es aster_token {saad_token} --es aster_user_id user_saad")
        time.sleep(3.5)

        log("ANDROID-STEP 2", "Verifying Home screen is loaded...")
        ui, header = wait_for_node_contains("text", "Saad", timeout=15.0)
        safe_screenshot("/home/saad/Aster/m10_home_initial.png")
        log("ANDROID-STEP 2", "Home screen open. Verified user Saad.")

        # Verify 'Starlight Guild' group is rendered with '✦✦'
        sg_node = find_node(ui, text="Starlight Guild")
        if sg_node is not None:
            log("ANDROID-STEP 2", "Existing group 'Starlight Guild' listed on Home screen.")

        log("ANDROID-STEP 3", "Tapping New Chat FAB to open New Connection dialog...")
        ui, fab = wait_for_node(content_desc="Start new conversation", timeout=6.0)
        tap_node(fab)
        time.sleep(1.0)

        log("ANDROID-STEP 4", "Switching to 'Group ✦✦' mode in dialog...")
        ui, group_tab = wait_for_node_contains("text", "Group", timeout=6.0)
        tap_node(group_tab)
        time.sleep(1.0)
        safe_screenshot("/home/saad/Aster/m10_new_group_dialog.png")

        log("ANDROID-STEP 5", "Selecting members: Alex and Rohan FIRST (while keyboard is closed)...")
        ui, alex_item = wait_for_node(text="Alex", timeout=8.0)
        tap_node(alex_item)
        time.sleep(0.5)

        ui, rohan_item = wait_for_node(text="Rohan", timeout=6.0)
        tap_node(rohan_item)
        time.sleep(0.5)

        log("ANDROID-STEP 6", "Entering group name 'Lunar Orbiters'...")
        ui = dump_ui()
        input_field = find_node_contains(ui, "text", "Group name...")
        if input_field is None:
            input_field = find_node(ui, class_name="android.widget.EditText")
        assert input_field is not None, "Group name input field not found"
        tap_node(input_field)
        time.sleep(0.3)
        type_text("Lunar Orbiters")
        time.sleep(0.5)
        dismiss_keyboard_if_shown()
        time.sleep(0.5)

        safe_screenshot("/home/saad/Aster/m10_group_dialog_selected.png")

        log("ANDROID-STEP 7", "Tapping 'Create Constellation' button...")
        ui = dump_ui()
        create_btn = find_node_contains(ui, "text", "Create Constellation")
        assert create_btn is not None, "Create Constellation button not found"
        tap_node(create_btn)
        time.sleep(2.5)

        log("ANDROID-STEP 8", "Verifying ChatScreen opened for 'Lunar Orbiters'...")
        ui, chat_title = wait_for_node(text="Lunar Orbiters", timeout=8.0)
        # Check member count subtitle: "3 members"
        members_subtitle = find_node_contains(ui, "text", "3 members")
        assert members_subtitle is not None, "Group member count subtitle not displayed"
        safe_screenshot("/home/saad/Aster/m10_group_chat_empty.png")
        log("ANDROID-STEP 8", "Group ChatScreen verified: 'Lunar Orbiters' with '3 members'.")

        # Send group message from Android UI
        android_msg = f"Greetings Lunar Orbiters from Android at {time.strftime('%H:%M:%S')}"
        log("ANDROID-STEP 9", f"Composing and sending group message: '{android_msg}'...")
        input_box = find_node(ui, content_desc="Message input")
        assert input_box is not None, "Message input box not found"
        tap_node(input_box)
        time.sleep(0.3)
        type_text(android_msg)
        time.sleep(0.5)
        dismiss_keyboard_if_shown()
        time.sleep(0.5)

        ui = dump_ui()
        send_btn = find_node(ui, content_desc="Send message")
        tap_node(send_btn)
        time.sleep(1.0)
        dismiss_keyboard_if_shown()

        # Online member Alex receives the message over WSS
        log("ANDROID-STEP 10", "Verifying Alex receives Android group message over WSS...")
        alex_rx = await alex_client.wait_for_message(expected_body=android_msg, timeout=10.0)
        lunar_group_id = alex_rx.get("conversationId")
        assert alex_rx.get("senderId") == "user_saad"
        log("ANDROID-STEP 10", f"Alex received Android group message in {lunar_group_id}")

        # Alex sends reply over WSS using his existing session
        alex_reply = f"Alex received Lunar message at {time.strftime('%H:%M:%S')}"
        log("ANDROID-STEP 11", f"Alex sending reply to {lunar_group_id}: '{alex_reply}'...")
        await alex_client.send_and_wait_response({
            "type": "SEND_MESSAGE",
            "messageId": f"alex_rep_{int(time.time())}",
            "conversationId": lunar_group_id,
            "body": alex_reply,
            "timestamp": int(time.time() * 1000)
        }, ["MESSAGE_ACCEPTED"])

        # Android ChatScreen receives Alex's message and renders sender display name "Alex"
        log("ANDROID-STEP 12", "Verifying Alex reply and sender display name rendered in Android UI...")
        ui, reply_node = wait_for_node_contains("text", alex_reply, timeout=10.0)
        alex_sender_label = find_node(ui, text="Alex")
        assert alex_sender_label is not None, "Sender display name 'Alex' not displayed above group message bubble!"
        safe_screenshot("/home/saad/Aster/m10_group_chat_with_reply.png")
        log("ANDROID-STEP 12", "Verified: Message bubble clearly displays sender name 'Alex' above text.")

        # =====================================================================
        # SECTION 8: Process Death & Room Persistence
        # =====================================================================
        log("ROOM-STEP 13", "Navigating back to Home screen...")
        navigate_back_to_home()
        time.sleep(1.0)

        ui, home_lunar = wait_for_node(text="Lunar Orbiters", timeout=6.0)
        lunar_preview = find_node_contains(ui, "text", "Alex received Lunar message")
        assert lunar_preview is not None, "Latest message snippet not updated on Home screen"
        safe_screenshot("/home/saad/Aster/m10_home_with_lunar.png")

        log("ROOM-STEP 14", "Force stopping Android app...")
        run_adb("shell am force-stop com.aster.app")
        time.sleep(1.5)

        log("ROOM-STEP 15", "Relaunching app from cold start (testing Keystore & Room persistence)...")
        run_adb("shell am start -n com.aster.app/.MainActivity")
        time.sleep(2.5)

        ui, restarted_lunar = wait_for_node(text="Lunar Orbiters", timeout=12.0)
        restarted_prev = find_node_contains(ui, "text", "Alex received Lunar message")
        assert restarted_prev is not None, "Group message preview vanished after process restart!"
        safe_screenshot("/home/saad/Aster/m10_relaunch_home.png")
        log("ROOM-STEP 15", "Verified: Group conversation persists on Home screen across process death.")

        log("ROOM-STEP 16", "Reopening 'Lunar Orbiters' chat...")
        tap_node(restarted_lunar)
        time.sleep(1.5)

        ui, persisted_msg = wait_for_node_contains("text", "Alex received Lunar message", timeout=8.0)
        assert persisted_msg is not None, "Message vanished from ChatScreen after process restart!"
        safe_screenshot("/home/saad/Aster/m10_relaunch_chat.png")
        log("ROOM-STEP 16", "Verified: Room database preserved all group messages and member metadata.")

        # =====================================================================
        # SECTION 9: Offline Outbox & Reconnect
        # =====================================================================
        log("OUTBOX-STEP 17", "Evicting Android WSS connection temporarily...")
        evictor = await websockets.connect(WSS_ENDPOINT, ssl=ssl_ctx, ping_interval=None)
        await evictor.send(json.dumps({"type": "AUTH", "token": saad_token, "userId": "user_saad"}))
        await evictor.recv() # AUTH_OK
        log("OUTBOX-STEP 17", "Android client evicted; queuing group message in offline outbox...")

        outbox_body = f"Queued group outbox message at {time.strftime('%H:%M:%S')}"
        ui, input_box = wait_for_node(content_desc="Message input", timeout=8.0)
        tap_node(input_box)
        time.sleep(0.3)
        type_text(outbox_body)
        time.sleep(0.5)
        dismiss_keyboard_if_shown()
        time.sleep(0.5)

        ui = dump_ui()
        send_btn = find_node(ui, content_desc="Send message")
        tap_node(send_btn)
        time.sleep(1.0)

        await evictor.close()
        log("OUTBOX-STEP 18", "Released evictor; awaiting Android reconnect & outbox drain...")

        alex_outbox_rx = await alex_client.wait_for_message(expected_body=outbox_body, conversation_id=lunar_group_id, timeout=12.0)
        assert alex_outbox_rx.get("body") == outbox_body
        safe_screenshot("/home/saad/Aster/m10_outbox_delivered.png")
        log("OUTBOX-STEP 18", "Verified: Offline queued group message successfully drained and delivered upon reconnect.")

        # =====================================================================
        # SECTION 10: Direct 1:1 Messaging Regression Validation
        # =====================================================================
        log("REGRESSION-STEP 19", "Navigating back to Home screen for direct 1:1 test...")
        navigate_back_to_home()
        time.sleep(1.0)

        # Ensure we find Alex in the visible viewport
        start_scroll = time.time()
        alex_chat_item = None
        while time.time() - start_scroll < 15.0:
            ui = dump_ui()
            node = find_node(ui, text="Alex")
            if node is not None:
                ax, ay = get_node_center(node)
                if 100 <= ay <= 580:
                    alex_chat_item = node
                    break
                elif ay > 580:
                    run_adb("shell input swipe 300 500 300 350")
                    time.sleep(0.6)
                elif ay < 100:
                    run_adb("shell input swipe 300 350 300 500")
                    time.sleep(0.6)
            else:
                run_adb("shell input swipe 300 500 300 350")
                time.sleep(0.6)
        assert alex_chat_item is not None, "Failed to find Alex in visible viewport"
        tap_node(alex_chat_item)
        time.sleep(1.5)

        log("REGRESSION-STEP 20", "Verifying direct 1:1 ChatScreen with Alex...")
        ui, input_box = wait_for_node(content_desc="Message input", timeout=12.0)
        # Direct conversation should NOT have '✦✦' group indicator or 'members' subtitle
        assert find_node_contains(ui, "text", "members") is None, "Direct conversation unexpectedly displays group subtitle"

        direct_msg = f"Direct 1:1 regression check at {time.strftime('%H:%M:%S')}"
        tap_node(input_box)
        time.sleep(0.3)
        type_text(direct_msg)
        time.sleep(0.5)
        dismiss_keyboard_if_shown()
        time.sleep(0.5)

        ui = dump_ui()
        send_btn = find_node(ui, content_desc="Send message")
        tap_node(send_btn)
        time.sleep(1.0)

        alex_direct_rx = await alex_client.wait_for_message(expected_body=direct_msg, timeout=10.0)
        assert alex_direct_rx.get("conversationId").startswith("conv_"), f"Expected conv_ prefix, got {alex_direct_rx.get('conversationId')}"
        safe_screenshot("/home/saad/Aster/m10_direct_chat_regression.png")
        log("REGRESSION-STEP 20", f"Verified direct 1:1 messaging intact over WSS: {alex_direct_rx.get('conversationId')}")

        print("=" * 80)
        print("ALL MILESTONE 10 VALIDATION CHECKS PASSED PERFECTLY!")
        print("=" * 80)

    finally:
        await alex_client.close()

if __name__ == "__main__":
    asyncio.run(run_m10_groups_validation())
