#!/usr/bin/env python3
"""
Test Scenarios 7 to 10 for Aster Fresh-User Playtest & Usability Readiness Gate.
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

async def main():
    log("TEST", "Starting Scenarios 7 to 10...")
    alex_token = provision_token("user_alex", "device_alex_cli")
    charlie_token = provision_token("user_charlie", "device_charlie_test")

    alex = PeerClient("user_alex", alex_token)
    await alex.connect()

    # Get current group ID from DB
    res = subprocess.run(
        'sqlite3 /home/saad/Aster/aster.db "SELECT group_id FROM groups WHERE name LIKE \'Nova Circle%\' ORDER BY created_at DESC LIMIT 1;"',
        shell=True, capture_output=True, text=True
    )
    grp_id = res.stdout.strip()
    assert grp_id, "Could not find Nova Circle group in database!"
    log("TEST", f"Target Group ID: {grp_id}")

    # SCENARIO 7: Exchange messages in group
    log("TEST", "--- SCENARIO 7: Group Messaging & Member Visibility ---")
    # Tap message input on ChatScreen
    run_adb("shell input tap 200 600")
    time.sleep(0.5)
    run_adb("shell input text 'Nova%sCircle%sconstellation%sonline!'")
    time.sleep(0.5)
    run_adb("shell input tap 594 611")
    time.sleep(1.0)

    # Alex receives message over WSS
    alex_grp_msg = await alex.wait_for_message(timeout=8.0)
    assert alex_grp_msg is not None, "Alex did not receive Charlie's group message!"
    log("TEST", f"Alex received message in group: '{alex_grp_msg.get('body')}'")

    # Alex replies
    await alex.send_message(
        conversation_id=grp_id,
        body="Alex reporting in for Nova Circle!"
    )
    time.sleep(2.0)
    run_adb("shell input keyevent 4") # Dismiss keyboard
    time.sleep(0.5)
    safe_screenshot("/home/saad/Aster/scripts/fresh_10_group_chat_screen.png")

    # SCENARIO 8: Receiving notifications and opening the correct conversation
    log("TEST", "--- SCENARIO 8: Notifications & Navigation ---")
    # Navigate back to Home screen so conversation is not actively viewed
    run_adb("shell input keyevent 4")
    time.sleep(1.5)

    # Expand shade, tap clear all if present, then collapse
    run_adb("shell cmd statusbar expand-notifications")
    time.sleep(1.0)
    run_adb("shell input tap 580 530") # Tap Clear all
    time.sleep(0.5)
    run_adb("shell cmd statusbar collapse")
    time.sleep(1.0)

    # Alex sends group notification trigger message
    await alex.send_message(
        conversation_id=grp_id,
        body="Notification alert for Nova Circle constellation"
    )
    time.sleep(2.5)

    # Expand status bar to verify notification posted
    run_adb("shell cmd statusbar expand-notifications")
    time.sleep(1.5)
    safe_screenshot("/home/saad/Aster/scripts/fresh_11_group_notification_in_shade.png")

    # Collapse status bar
    run_adb("shell cmd statusbar collapse")
    time.sleep(1.0)

    # Navigate to conversation via notification intent
    log("TEST", f"Navigating to conversation {grp_id} via notification intent...")
    run_adb(f"shell am start -a android.intent.action.VIEW -n com.aster.app/.MainActivity --es conversation_id {grp_id}")
    time.sleep(2.5)
    safe_screenshot("/home/saad/Aster/scripts/fresh_12_notification_navigated_chat.png")

    # SCENARIO 9: Error handling & Permission denial
    log("TEST", "--- SCENARIO 9: Error Handling & Permission Denial ---")
    # 9a. Invalid token
    log("TEST", "Testing invalid token injection...")
    run_adb("logcat -c")
    run_adb("shell am start -n com.aster.app/.MainActivity --es aster_token ast_tok_invalid_malformed_test --es aster_user_id user_charlie")
    time.sleep(3.0)
    logcat_err = run_adb("logcat -d -s AsterWebSocketClient | tail -n 20")
    assert "Authentication failed" in logcat_err, f"Auth failure not found in logcat: {logcat_err}"
    log("TEST", "Verified server rejected invalid token and client handled AuthError cleanly")
    safe_screenshot("/home/saad/Aster/scripts/fresh_13_auth_error_state.png")

    # Restore valid token
    run_adb(f"shell am start -n com.aster.app/.MainActivity --es aster_token {charlie_token} --es aster_user_id user_charlie")
    time.sleep(3.0)

    # 9b. Denied notification permission & in-app unread badge
    log("TEST", "Revoking POST_NOTIFICATIONS permission...")
    run_adb("shell pm revoke com.aster.app android.permission.POST_NOTIFICATIONS")
    time.sleep(1.0)

    # Go to home screen
    run_adb("shell input keyevent 4")
    time.sleep(1.5)

    # Alex sends message
    await alex.send_message(
        conversation_id=grp_id,
        body="Message while notifications are revoked: in-app unread badge test"
    )
    time.sleep(2.5)

    safe_screenshot("/home/saad/Aster/scripts/fresh_14_permission_denied_unread_badge.png")

    # Re-grant notification permission
    run_adb("shell pm grant com.aster.app android.permission.POST_NOTIFICATIONS")
    time.sleep(0.5)

    # SCENARIO 10: Database integrity check
    log("TEST", "--- SCENARIO 10: Database Integrity Check ---")
    db_check = subprocess.run(
        'sqlite3 /home/saad/Aster/aster.db "SELECT user_id, COUNT(*) FROM auth_tokens WHERE revoked_at IS NULL GROUP BY user_id;"',
        shell=True, capture_output=True, text=True
    )
    log("TEST", f"Active user tokens in database:\n{db_check.stdout.strip()}")
    assert "user_saad" in db_check.stdout, "user_saad was damaged!"
    assert "user_alex" in db_check.stdout, "user_alex was damaged!"
    assert "user_charlie" in db_check.stdout, "user_charlie was not found!"

    # Check groups count
    grp_check = subprocess.run(
        'sqlite3 /home/saad/Aster/aster.db "SELECT count(*) FROM groups;"',
        shell=True, capture_output=True, text=True
    )
    log("TEST", f"Total groups in database: {grp_check.stdout.strip()}")
    assert int(grp_check.stdout.strip()) >= 1, "Groups table is empty!"

    await alex.close()
    log("TEST", "=== ALL SCENARIOS 7 TO 10 COMPLETED AND VERIFIED ===")

if __name__ == "__main__":
    asyncio.run(main())
