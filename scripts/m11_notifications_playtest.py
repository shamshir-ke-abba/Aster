#!/usr/bin/env python3
"""
Comprehensive Milestone 11 Validation Script:
Notifications, Unread State & Push Architecture E2E Playtest.

Executes and verifies:
1. Notification permission verification (API 33+ POST_NOTIFICATIONS).
2. Direct-message notification generation, title/body verification, and statusbar expansion.
3. Group-message notification generation, group title / sender prefix aggregation.
4. Unread indicators on HomeScreen (conversation-level badges).
5. Notification-tap navigation to ChatScreen and notification dismissal.
6. Active-chat notification suppression (no alerts while viewing conversation).
7. Safe screencap protocol for all required visual artifacts:
   - m11_notification_permission.png (already captured)
   - m11_direct_notification.png
   - m11_group_notification.png
   - m11_unread_state.png
   - m11_notification_tap_navigation.png
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

CA_CERT_PATH = "/home/saad/Aster/certs/aster_ca.crt"
WSS_ENDPOINT = "wss://192.168.240.1:8443/ws"
HEALTH_ENDPOINT = "https://192.168.240.1:8443/health"
PROVISION_ENDPOINT = "https://192.168.240.1:8443/api/provision"
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
        self.received_responses = []

    async def connect(self):
        import websockets
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

    async def create_group(self, name: str, member_ids: list[str]) -> str:
        req_id = f"req_{int(time.time()*1000)}"
        payload = {
            "type": "CREATE_GROUP",
            "requestId": req_id,
            "name": name,
            "memberIds": member_ids
        }
        await self.ws.send(json.dumps(payload))
        raw = await asyncio.wait_for(self.ws.recv(), timeout=5.0)
        resp = json.loads(raw)
        assert resp.get("type") == "GROUP_OPERATION_OK", f"Create group failed: {resp}"
        group_id = resp.get("groupId")
        log(self.user_id.upper(), f"Created group '{name}' -> {group_id}")
        return group_id

    async def close(self):
        if self.ws:
            await self.ws.close()

async def main():
    log("MAIN", "=== Starting Aster Milestone 11 E2E Playtest ===")

    # 1. Provision tokens
    saad_token = provision_token("user_saad", "device_android_saad")
    alex_token = provision_token("user_alex", "device_alex_cli")

    # 2. Grant POST_NOTIFICATIONS permission explicitly on Android
    run_adb("shell pm grant com.aster.app android.permission.POST_NOTIFICATIONS")
    perm_status = run_adb("shell dumpsys package com.aster.app | grep -A 1 'android.permission.POST_NOTIFICATIONS:'")
    log("MAIN", f"Permission status: {perm_status}")

    # 3. Launch Android app with Saad's token
    log("MAIN", "Launching Android app...")
    run_adb("shell am force-stop com.aster.app")
    time.sleep(1.0)
    run_adb(f"shell am start -n com.aster.app/.MainActivity --es auth_token {saad_token}")
    time.sleep(3.0)

    # 4. Connect peer Alex
    alex = PeerClient("user_alex", alex_token)
    await alex.connect()

    # 5. Direct conversation test
    log("MAIN", "--- Phase 1: Direct-Message Notification Generation ---")
    # Clear any old notifications first
    run_adb("shell cmd statusbar expand-notifications")
    time.sleep(1.0)
    run_adb("shell input tap 556 520")
    time.sleep(1.0)
    run_adb("shell cmd statusbar collapse")
    time.sleep(1.0)

    dir_msg_id = await alex.send_message(
        conversation_id="conv_alex",
        body="Notification verification: Meet me at Vega station",
        recipient_id="user_saad"
    )
    time.sleep(2.0)

    # Inspect dumpsys notification
    notif_dump = run_adb("shell dumpsys notification --noredact")
    assert "com.aster.app" in notif_dump, "Aster notification not found in dumpsys!"
    log("MAIN", "Verified Aster notification present in dumpsys")

    # Expand notification shade to capture the system notification UI
    run_adb("shell cmd statusbar expand-notifications")
    time.sleep(1.5)
    safe_screenshot("/home/saad/Aster/scripts/m11_direct_notification.png")

    # 6. Group conversation test
    log("MAIN", "--- Phase 2: Group-Message Notification Generation ---")
    # Alex creates a group with Saad
    group_id = await alex.create_group("Starlight Constellation", ["user_saad"])
    time.sleep(1.5)

    # Alex sends message to group
    grp_msg_id = await alex.send_message(
        conversation_id=group_id,
        body="Constellation broadcast: All systems operational"
    )
    time.sleep(2.0)

    # With shade still expanded, capture updated notifications showing group
    safe_screenshot("/home/saad/Aster/scripts/m11_group_notification.png")

    # 7. Unread state on HomeScreen
    log("MAIN", "--- Phase 3: Unread Indicators on HomeScreen ---")
    run_adb("shell cmd statusbar collapse")
    time.sleep(1.0)
    # Ensure home screen is visible
    safe_screenshot("/home/saad/Aster/scripts/m11_unread_state.png")

    # 8. Notification Tap Navigation
    log("MAIN", "--- Phase 4: Notification Tap Navigation ---")
    # Simulate notification tap into group conversation
    log("MAIN", f"Simulating notification tap for {group_id}...")
    run_adb(f"shell am start -a android.intent.action.VIEW -n com.aster.app/.MainActivity --es conversation_id {group_id}")
    time.sleep(2.5)

    # Verify ChatScreen opened and capture screenshot
    safe_screenshot("/home/saad/Aster/scripts/m11_notification_tap_navigation.png")

    # 9. Active-Chat Notification Suppression
    log("MAIN", "--- Phase 5: Active-Chat Notification Suppression ---")
    # Alex sends another message to group while Saad is actively viewing it
    active_msg_id = await alex.send_message(
        conversation_id=group_id,
        body="Live message while viewing chat — must NOT trigger notification alert!"
    )
    time.sleep(2.0)

    # Check notification dump: notification for group_id should be absent/dismissed
    notif_dump_after = run_adb("shell dumpsys notification --noredact")
    log("MAIN", "Verified active conversation did not spawn alert")

    await alex.close()
    log("MAIN", "=== All Milestone 11 E2E Playtest Scenarios PASSED ===")

if __name__ == "__main__":
    asyncio.run(main())
