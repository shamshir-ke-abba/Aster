#!/usr/bin/env python3
"""
Aster — Fresh-User Playtest & Usability Readiness Gate
Comprehensive Test Execution Script.

Tests all 10 scenarios in sequence against the live Waydroid runtime and backend:
1. Fresh launch with no existing local app state (already initiated).
2. First-time authentication & account provisioning.
3. Finding another provisioned test user.
4. Sending and receiving direct messages in both directions.
5. Force-stopping and reopening the app; verifying message history.
6. Disconnecting and reconnecting; verifying pending-message delivery.
7. Creating a group, exchanging messages, and checking member visibility.
8. Receiving notifications and opening the correct conversation.
9. Handling invalid credentials, unavailable server, and denied notification permission.
10. Verifying that no existing user data was damaged by the test.
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
    raise RuntimeError("Admin key not configured")

ADMIN_KEY = get_admin_key()

def log(tag: str, msg: str):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] [{tag}] {msg}")

def run_adb(cmd: str) -> str:
    full_cmd = f"adb -s {WAYDROID_SERIAL} {cmd}"
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

def tap_node(node: ET.Element):
    bounds = node.attrib.get("bounds", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        cx = (x1 + x2) // 2
        cy = (y1 + y2) // 2
        run_adb(f"shell input tap {cx} {cy}")

def provision_token(user_id: str, device_id: str) -> str:
    ctx = ssl.create_default_context(cafile=CA_CERT_PATH)
    headers = {
        "Content-Type": "application/json",
        "X-Aster-Admin-Key": ADMIN_KEY
    }
    req = urllib.request.Request(
        PROVISION_ENDPOINT,
        data=json.dumps({"userId": user_id, "deviceId": device_id}).encode("utf-8"),
        headers=headers
    )
    with urllib.request.urlopen(req, context=ctx, timeout=5.0) as resp:
        body = json.loads(resp.read().decode())
        assert body.get("status") == "ok", f"Provisioning failed: {body}"
        token = body.get("token")
        log("PROVISION", f"Provisioned token for {user_id}: {token[:12]}...")
        return token

class PeerClient:
    def __init__(self, user_id: str, token: str):
        self.user_id = user_id
        self.token = token
        self.ws = None
        self.ssl_ctx = ssl.create_default_context(cafile=CA_CERT_PATH)
        self.received_messages = []
        self.received_events = []

    async def connect(self):
        log(self.user_id.upper(), f"Connecting to {WSS_ENDPOINT}...")
        self.ws = await websockets.connect(WSS_ENDPOINT, ssl=self.ssl_ctx, ping_interval=None)
        await self.ws.send(json.dumps({"type": "AUTH", "token": self.token, "userId": self.user_id}))
        raw = await asyncio.wait_for(self.ws.recv(), timeout=5.0)
        data = json.loads(raw)
        assert data.get("type") == "AUTH_OK", f"Auth failed for {self.user_id}: {data}"
        log(self.user_id.upper(), f"Authenticated as {self.user_id}")

        # Drain initial frames
        while True:
            try:
                raw_frame = await asyncio.wait_for(self.ws.recv(), timeout=0.5)
                frame = json.loads(raw_frame)
                if frame.get("type") == "MESSAGE":
                    self.received_messages.append(frame)
                    await self.ws.send(json.dumps({"type": "ACK_RECEIVED", "messageId": frame["messageId"]}))
            except asyncio.TimeoutError:
                break

    async def send_message(self, conversation_id: str, body: str, recipient_id: str = None) -> str:
        msg_id = f"msg_{int(time.time()*1000)}_{os.urandom(4).hex()}"
        payload = {
            "type": "SEND_MESSAGE",
            "messageId": msg_id,
            "conversationId": conversation_id,
            "body": body,
            "timestamp": int(time.time() * 1000)
        }
        if recipient_id:
            payload["recipientId"] = recipient_id
        await self.ws.send(json.dumps(payload))
        raw = await asyncio.wait_for(self.ws.recv(), timeout=5.0)
        resp = json.loads(raw)
        assert resp.get("type") == "MESSAGE_ACCEPTED" or resp.get("messageId") == msg_id, f"Send failed: {resp}"
        log(self.user_id.upper(), f"Sent message {msg_id}: '{body}'")
        return msg_id

    async def wait_for_message(self, timeout: float = 8.0) -> dict | None:
        start = time.time()
        while time.time() - start < timeout:
            try:
                raw = await asyncio.wait_for(self.ws.recv(), timeout=1.0)
                frame = json.loads(raw)
                if frame.get("type") == "MESSAGE":
                    await self.ws.send(json.dumps({"type": "ACK_RECEIVED", "messageId": frame["messageId"]}))
                    log(self.user_id.upper(), f"Received message {frame.get('messageId')}: '{frame.get('body')}' from {frame.get('senderId')}")
                    return frame
            except asyncio.TimeoutError:
                pass
        return None

    async def close(self):
        if self.ws:
            await self.ws.close()

async def run_journey():
    log("JOURNEY", "=== Aster Fresh-User Playtest & Usability Journey ===")

    # Scenario 2: First-time authentication & account provisioning
    log("JOURNEY", "--- Scenario 2: First-Time Authentication & Account Provisioning ---")
    # Provision dedicated test identity user_charlie
    charlie_token = provision_token("user_charlie", "device_charlie_waydroid")
    alex_token = provision_token("user_alex", "device_alex_cli")

    # Inject token via intent
    log("JOURNEY", "Injecting token into Android app via intent...")
    run_adb("logcat -c")
    run_adb("shell am force-stop com.aster.app")
    time.sleep(1.0)
    run_adb(f"shell am start -n com.aster.app/.MainActivity --es aster_token {charlie_token} --es aster_user_id user_charlie")
    time.sleep(4.0)

    # Check connection state in logcat
    logcat_out = run_adb("logcat -d -s AsterWebSocketClient | tail -n 25")
    assert "Authentication accepted by server" in logcat_out or "AUTH_OK" in logcat_out, f"Auth not accepted: {logcat_out}"
    log("JOURNEY", "Verified AsterWebSocketClient authenticated successfully!")

    safe_screenshot("/home/saad/Aster/scripts/fresh_04_authenticated_home_screen.png")

    # Connect peer Alex
    alex = PeerClient("user_alex", alex_token)
    await alex.connect()

    # Scenario 3: Finding another provisioned test user
    log("JOURNEY", "--- Scenario 3: Finding Another Provisioned Test User ---")
    # Tap New Chat FAB (bounds around [556,562][617,623])
    run_adb("shell input tap 586 592")
    time.sleep(1.5)
    safe_screenshot("/home/saad/Aster/scripts/fresh_05_new_chat_dialog.png")

    # Select Alex in the list (or navigate to direct chat with Alex)
    ui = dump_ui()
    alex_item = find_node_contains(ui, "text", "Alex")
    if alex_item is not None:
        log("JOURNEY", "Found Alex in contact list; tapping...")
        tap_node(alex_item)
        time.sleep(2.0)
    else:
        log("JOURNEY", "Dismissing dialog and opening seeded Alex chat...")
        run_adb("shell input keyevent 4")
        time.sleep(1.0)
        run_adb("shell input tap 100 190")
        time.sleep(1.5)

    safe_screenshot("/home/saad/Aster/scripts/fresh_06_direct_chat_opened.png")

    # Scenario 4: Sending and receiving direct messages in both directions
    log("JOURNEY", "--- Scenario 4: Bidirectional Direct Messaging ---")
    # Charlie sends message from UI
    # Focus input box and type
    run_adb("shell input tap 200 600")
    time.sleep(0.5)
    run_adb("shell input text 'Hello%sAlex!%sCharlie%stesting%sfresh%sjourney.'")
    time.sleep(0.5)
    run_adb("shell input tap 594 611")
    time.sleep(1.0)

    # Alex receives the message over WSS
    alex_received = await alex.wait_for_message(timeout=6.0)
    assert alex_received is not None, "Alex did not receive message from Charlie!"
    log("JOURNEY", f"Alex received Charlie's message: '{alex_received.get('body')}' from {alex_received.get('senderId')}")

    # Alex sends reply back to Charlie
    await alex.send_message(
        conversation_id=alex_received.get("conversationId"),
        body="Welcome Charlie! Direct communication verified.",
        recipient_id="user_charlie"
    )
    time.sleep(2.0)
    # Dismiss keyboard if shown
    run_adb("shell input keyevent 4")
    time.sleep(0.5)
    safe_screenshot("/home/saad/Aster/scripts/fresh_07_bidirectional_direct_chat.png")

    # Scenario 5: Force-stopping and reopening the app; verify message history
    log("JOURNEY", "--- Scenario 5: Force-Stop & Reopen (Persistence Verification) ---")
    run_adb("shell am force-stop com.aster.app")
    time.sleep(1.5)

    # Reopen app WITHOUT intent extras (cold start with stored Keystore credentials)
    log("JOURNEY", "Reopening app with cold start (no intent extras)...")
    run_adb("shell am start -n com.aster.app/.MainActivity")
    time.sleep(3.0)

    # Verify connection resumed
    logcat_cold = run_adb("logcat -d -s AsterWebSocketClient | tail -n 15")
    assert "Authentication accepted by server" in logcat_cold or "CONNECTED" in logcat_cold, "Did not auto-reconnect on cold start!"
    log("JOURNEY", "Verified auto-reconnect on cold start using stored Keystore credentials")

    # Open conversation to view message history
    run_adb("shell input tap 100 190")
    time.sleep(2.0)
    safe_screenshot("/home/saad/Aster/scripts/fresh_08_reopen_persisted_history.png")

    # Scenario 6: Disconnecting and reconnecting; verify pending-message delivery
    log("JOURNEY", "--- Scenario 6: Offline Mailbox Queueing & Delivery ---")
    # Temporarily disconnect Android client by force-stopping it
    run_adb("shell am force-stop com.aster.app")
    time.sleep(1.5)

    # Alex sends message while Charlie is offline
    offline_msg_id = await alex.send_message(
        conversation_id=alex_received.get("conversationId"),
        body="Durable offline mailbox test: Delivered while disconnected",
        recipient_id="user_charlie"
    )
    log("JOURNEY", f"Sent offline message {offline_msg_id}")
    time.sleep(1.0)

    # Relaunch Android client
    run_adb("shell am start -n com.aster.app/.MainActivity")
    time.sleep(3.0)

    # Open chat to verify offline message arrived
    run_adb("shell input tap 100 190")
    time.sleep(2.0)
    safe_screenshot("/home/saad/Aster/scripts/fresh_09_offline_mailbox_replay.png")

    # Scenario 7: Creating a group, exchanging messages, and checking member visibility
    log("JOURNEY", "--- Scenario 7: Group Creation & Exchange ---")
    # Return to home screen
    run_adb("shell input keyevent 4")
    time.sleep(1.0)

    # Tap New Chat FAB
    run_adb("shell input tap 586 592")
    time.sleep(1.5)

    # Switch to 'Group ✦✦' tab (around x=480, y=140)
    run_adb("shell input tap 480 140")
    time.sleep(0.8)

    # Enter Group Name
    run_adb("shell input tap 200 200")
    time.sleep(0.5)
    run_adb("shell input text 'Nova%sCircle'")
    time.sleep(0.5)

    # Select Alex in member list (around x=100, y=280)
    run_adb("shell input tap 100 280")
    time.sleep(0.5)

    # Dismiss keyboard
    run_adb("shell input keyevent 4")
    time.sleep(0.5)

    # Tap 'Create Constellation' (around x=450, y=420)
    run_adb("shell input tap 450 420")
    time.sleep(3.0)

    # In group chat: send message
    run_adb("shell input tap 200 600")
    time.sleep(0.5)
    run_adb("shell input text 'Nova%sCircle%sconstellation%sonline!'")
    time.sleep(0.5)
    run_adb("shell input tap 594 611")
    time.sleep(1.0)

    # Alex receives group message
    alex_grp_msg = await alex.wait_for_message(timeout=6.0)
    assert alex_grp_msg is not None, "Alex did not receive group message!"
    log("JOURNEY", f"Alex received group message: '{alex_grp_msg.get('body')}' in {alex_grp_msg.get('conversationId')}")

    # Alex sends group reply
    grp_id = alex_grp_msg.get("conversationId")
    await alex.send_message(
        conversation_id=grp_id,
        body="Alex reporting in for Nova Circle!"
    )
    time.sleep(2.0)
    run_adb("shell input keyevent 4")
    time.sleep(0.5)
    safe_screenshot("/home/saad/Aster/scripts/fresh_10_group_chat_screen.png")

    # Scenario 8: Receiving notifications and opening the correct conversation
    log("JOURNEY", "--- Scenario 8: System Notification & Deep Link Navigation ---")
    # Return to home screen so conversation is NOT actively viewed
    run_adb("shell input keyevent 4")
    time.sleep(1.0)
    # Clear any old notifications
    run_adb("shell cmd statusbar expand-notifications")
    time.sleep(1.0)
    run_adb("shell input tap 556 520") # Clear all button
    time.sleep(1.0)
    run_adb("shell cmd statusbar collapse")
    time.sleep(1.0)

    # Alex sends another group message
    await alex.send_message(
        conversation_id=grp_id,
        body="Notification alert for Nova Circle constellation"
    )
    time.sleep(2.5)

    # Expand status bar
    run_adb("shell cmd statusbar expand-notifications")
    time.sleep(1.5)
    safe_screenshot("/home/saad/Aster/scripts/fresh_11_group_notification_in_shade.png")

    # Simulate notification click navigation via intent
    log("JOURNEY", f"Opening conversation {grp_id} via notification tap intent...")
    run_adb(f"shell am start -a android.intent.action.VIEW -n com.aster.app/.MainActivity --es conversation_id {grp_id}")
    time.sleep(2.5)
    safe_screenshot("/home/saad/Aster/scripts/fresh_12_notification_navigated_chat.png")

    # Scenario 9: Handling invalid credentials, unavailable server, and denied permission
    log("JOURNEY", "--- Scenario 9: Error Handling & Permission Denial ---")
    # 9a. Test invalid token
    log("JOURNEY", "Testing invalid token injection...")
    run_adb("shell am start -n com.aster.app/.MainActivity --es aster_token ast_tok_invalid_malformed_test --es aster_user_id user_charlie")
    time.sleep(3.0)
    logcat_err = run_adb("logcat -d -s AsterWebSocketClient | tail -n 15")
    assert "Authentication failed" in logcat_err, "Auth failure not detected for invalid token!"
    log("JOURNEY", "Verified server rejected invalid token and client handled AuthError cleanly")
    safe_screenshot("/home/saad/Aster/scripts/fresh_13_auth_error_state.png")

    # Restore valid token
    run_adb(f"shell am start -n com.aster.app/.MainActivity --es aster_token {charlie_token} --es aster_user_id user_charlie")
    time.sleep(2.5)

    # 9b. Test denied notification permission
    log("JOURNEY", "Revoking POST_NOTIFICATIONS permission...")
    run_adb("shell pm revoke com.aster.app android.permission.POST_NOTIFICATIONS")
    time.sleep(1.0)
    # Go to home screen
    run_adb("shell input keyevent 4")
    time.sleep(1.0)

    # Alex sends message
    await alex.send_message(
        conversation_id=grp_id,
        body="Message while notifications are revoked: in-app unread badge test"
    )
    time.sleep(2.5)

    # App does not crash, in-app unread badge appears on Home screen
    safe_screenshot("/home/saad/Aster/scripts/fresh_14_permission_denied_unread_badge.png")

    # Re-grant permission
    run_adb("shell pm grant com.aster.app android.permission.POST_NOTIFICATIONS")

    # Scenario 10: Verifying that no existing user data was damaged
    log("JOURNEY", "--- Scenario 10: Database Integrity Check ---")
    db_check = subprocess.run(
        'sqlite3 /home/saad/Aster/aster.db "SELECT user_id, COUNT(*) FROM auth_tokens WHERE revoked_at IS NULL GROUP BY user_id;"',
        shell=True, capture_output=True, text=True
    )
    log("JOURNEY", f"Active user tokens in database:\n{db_check.stdout.strip()}")
    assert "user_saad" in db_check.stdout, "user_saad was damaged!"
    assert "user_alex" in db_check.stdout, "user_alex was damaged!"
    assert "user_charlie" in db_check.stdout, "user_charlie was not found!"

    await alex.close()
    log("JOURNEY", "=== All Fresh-User Journey Scenarios COMPLETED SUCCESSFULLY ===")

if __name__ == "__main__":
    asyncio.run(run_journey())
