#!/usr/bin/env python3
"""
Comprehensive Milestone 7 Validation Script: Conversation & User Experience Playtest.

Validates the complete 19-step scenario:
1. Launch Aster.
2. Open Home.
3. Tap New Chat.
4. Select another known user (Sam).
5. Verify conversation is created/opened.
6. Verify it appears in Home conversation list.
7. Verify selecting same user again opens same conversation rather than creating duplicate.
8. Enter chat.
9. Send message.
10. Verify SENDING -> SENT.
11. Verify recipient (live WebSocket client) receives it.
12. Return Home.
13. Verify latest-message preview updates.
14. Reopen conversation.
15. Verify message persists.
16. Force-stop Android app.
17. Relaunch it.
18. Verify conversation and message remain.
19. Verify existing reconnect/outbox functionality still works.
"""

import asyncio
import json
import os
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
import websockets

TABLET_WS_URL = "ws://192.168.0.159:8080/ws"
TABLET_HEALTH_URL = "http://192.168.0.159:8080/health"
WAYDROID_SERIAL = "192.168.240.112:5555"

def log(tag: str, msg: str):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] [{tag}] {msg}")

def run_adb(cmd: str) -> str:
    full_cmd = f"adb -s {WAYDROID_SERIAL} {cmd}"
    res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True)
    return res.stdout.strip()

def safe_screenshot(local_path: str):
    run_adb("shell screencap -p /sdcard/screenshot.png")
    run_adb(f"pull /sdcard/screenshot.png {local_path}")
    run_adb("shell rm /sdcard/screenshot.png")
    # Validate magic header
    res = subprocess.run(f"file {local_path}", shell=True, capture_output=True, text=True)
    assert "PNG image data" in res.stdout, f"Corrupted screenshot at {local_path}: {res.stdout}"

def dump_ui() -> ET.Element:
    run_adb("shell uiautomator dump /sdcard/ui.xml")
    xml_str = run_adb("shell cat /sdcard/ui.xml")
    # Clean possible non-xml prefixes
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
    # Hide soft keyboard if active
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
    # Verify or ensure Home screen is foreground
    ui = dump_ui()
    if find_node_contains(ui, "text", "Saad") is None:
        run_adb("shell am start -n com.aster.app/.MainActivity")
        time.sleep(1.5)

def type_text(text: str):
    escaped = text.replace(" ", "%s").replace("'", "\\'").replace('"', '\\"')
    run_adb(f"shell input text '{escaped}'")

def check_logcat(filter_pattern: str, lines: int = 30) -> str:
    out = run_adb(f"logcat -d -s AsterWebSocketClient MessageRepository | tail -n {lines}")
    return out

class SamWebSocketClient:
    def __init__(self):
        self.ws = None
        self.received_messages = []

    async def connect(self):
        self.ws = await websockets.connect(TABLET_WS_URL)
        await self.ws.send(json.dumps({"type": "AUTH", "userId": "user_sam"}))
        raw = await asyncio.wait_for(self.ws.recv(), timeout=5.0)
        data = json.loads(raw)
        assert data.get("type") == "AUTH_OK", f"Auth failed: {data}"
        log("SAM-WS", "Authenticated as user_sam on tablet backend")
        # Drain pre-existing durable mailbox replays
        while True:
            try:
                raw_frame = await asyncio.wait_for(self.ws.recv(), timeout=1.0)
                frame = json.loads(raw_frame)
                if frame.get("type") == "MESSAGE":
                    log("SAM-WS", f"Drained queued mailbox replay: '{frame.get('body')}'")
                    await self.ws.send(json.dumps({"type": "ACK_RECEIVED", "messageId": frame["messageId"]}))
            except asyncio.TimeoutError:
                break

    async def close(self):
        if self.ws:
            await self.ws.close()
            self.ws = None

    async def wait_for_message(self, expected_body: str, timeout: float = 10.0) -> dict:
        start = time.time()
        while time.time() - start < timeout:
            raw = await asyncio.wait_for(self.ws.recv(), timeout=timeout)
            data = json.loads(raw)
            if data.get("type") == "MESSAGE":
                self.received_messages.append(data)
                # Send ACK_RECEIVED
                ack = {"type": "ACK_RECEIVED", "messageId": data["messageId"]}
                await self.ws.send(json.dumps(ack))
                log("SAM-WS", f"Received message '{data.get('body')}' from {data.get('senderId')}; ACK sent")
                if data.get("body") == expected_body:
                    return data
        raise TimeoutError(f"Timed out waiting for MESSAGE frame with body: {expected_body}")

async def run_m7_full_validation():
    print("=" * 75)
    print("ASTER MILESTONE 7: FULL RUNTIME VALIDATION (19-STEP SCENARIO)")
    print("=" * 75)

    # Pre-check: Health of tablet backend
    log("INIT", "Checking physical tablet backend health...")
    req = urllib.request.urlopen(TABLET_HEALTH_URL, timeout=3.0)
    health = json.loads(req.read().decode())
    log("INIT", f"Physical tablet backend health: {health}")
    assert health.get("status") == "ok"

    # Connect live WebSocket client as recipient Sam
    sam_client = SamWebSocketClient()
    await sam_client.connect()

    try:
        # Step 1: Launch Aster & Reset DB state cleanly for full reproducibility
        log("STEP 1", "Clearing app data to test initial clean seed state...")
        run_adb("shell pm clear com.aster.app")
        time.sleep(1.0)
        run_adb("shell am start -n com.aster.app/.MainActivity")
        time.sleep(2.5)

        # Step 2: Open Home
        log("STEP 2", "Verifying Home screen is loaded...")
        ui = dump_ui()
        header = find_node_contains(ui, "text", "Saad")
        assert header is not None, "Home screen greeting for Saad not found"
        safe_screenshot("/home/saad/Aster/m7_home_clean.png")
        log("STEP 2", "Home screen open. Verified user Saad.")

        # Verify only seeded conversations (Alex, Rohan) exist; Sam must NOT exist yet
        conv_alex = find_node(ui, text="Alex")
        conv_rohan = find_node(ui, text="Rohan")
        conv_sam = find_node(ui, text="Sam")
        assert conv_alex is not None, "Alex conversation should be seeded"
        assert conv_rohan is not None, "Rohan conversation should be seeded"
        assert conv_sam is None, "Sam conversation should NOT exist prior to creation"
        log("STEP 2", "Confirmed baseline: Alex and Rohan exist, Sam does not exist.")

        # Step 3: Tap New Chat
        log("STEP 3", "Finding and tapping New Chat FAB...")
        fab = find_node(ui, content_desc="Start new conversation")
        assert fab is not None, "New Chat FAB not found on Home screen"
        tap_node(fab)
        time.sleep(1.0)

        # Step 4: Select another known user
        log("STEP 4", "Verifying New Connection dialog...")
        ui = dump_ui()
        dialog_title = find_node(ui, text="New Connection")
        assert dialog_title is not None, "New Connection dialog failed to open"

        # Verify Saad (local user) is NOT in the contact selection list
        saad_entry = find_node(ui, text="Saad")
        assert saad_entry is None, "Local user Saad must NOT be selectable in New Chat dialog"

        sam_entry = find_node(ui, text="Sam")
        assert sam_entry is not None, "Sam not found in contact list"
        safe_screenshot("/home/saad/Aster/m7_dialog.png")

        log("STEP 4", "Selecting Sam from contact list...")
        tap_node(sam_entry)
        time.sleep(1.5)

        # Step 5: Verify conversation is created/opened
        log("STEP 5", "Verifying direct conversation with Sam opened...")
        ui = dump_ui()
        chat_title = find_node(ui, text="Sam")
        assert chat_title is not None, "Chat screen for Sam failed to open"

        # Step 4 (Empty conversation state): Verify empty state
        empty_title = find_node_contains(ui, "text", "Connected with Sam")
        assert empty_title is not None, "Empty conversation state not displayed"
        safe_screenshot("/home/saad/Aster/m7_empty_chat_sam.png")
        log("STEP 5", "Verified conversation created and empty state displayed cleanly.")

        # Step 8 & 9: Send a message in ChatScreen
        msg_text = f"Hello Sam! Milestone 7 verification at {time.strftime('%H:%M:%S')}"
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

        # Step 10: Verify SENDING -> SENT in logcat / Room
        log("STEP 10", "Checking message transition to SENT...")
        logs = check_logcat("MessageRepository", lines=25)
        log("STEP 10", f"Logcat excerpt:\n{logs}")
        assert "status to SENT" in logs or "Server accepted message" in logs, "Message did not reach SENT"
        log("STEP 10", "Verified message state transitioned to SENT.")

        # Step 11: Verify recipient receives it over WebSocket
        log("STEP 11", "Verifying Sam received message over WebSocket...")
        received_frame = await sam_client.wait_for_message(msg_text, timeout=10.0)
        assert received_frame.get("body") == msg_text, f"Body mismatch: {received_frame}"
        assert received_frame.get("senderId") == "user_saad"
        log("STEP 11", f"Verified recipient Sam received message: {received_frame.get('messageId')}")
        safe_screenshot("/home/saad/Aster/m7_chat_sent_sam.png")

        # Step 12: Return Home
        log("STEP 12", "Navigating back to Home screen...")
        navigate_back_to_home()

        # Step 6 & 13: Verify Sam appears in Home conversation list with latest-message preview
        log("STEP 6 & 13", "Verifying Home conversation list updated...")
        ui = dump_ui()
        home_sam = find_node(ui, text="Sam")
        assert home_sam is not None, "Sam conversation must appear in Home conversation list"
        preview = find_node_contains(ui, "text", "Hello Sam!")
        assert preview is not None, "Latest message snippet preview not displayed on Home screen"
        safe_screenshot("/home/saad/Aster/m7_home_with_sam.png")
        log("STEP 6 & 13", "Verified Sam is in Home conversation list with updated preview.")

        # Step 7: Verify selecting the same user again opens the same conversation rather than duplicating
        log("STEP 7", "Testing idempotency: tapping New Chat and selecting Sam again...")
        fab = find_node(ui, content_desc="Start new conversation")
        tap_node(fab)
        time.sleep(1.0)

        ui = dump_ui()
        sam_entry = find_node(ui, text="Sam")
        assert sam_entry is not None, "Sam not in contact dialog"
        tap_node(sam_entry)
        time.sleep(1.5)

        # Verify chat opened and has our previous message
        ui = dump_ui()
        existing_msg = find_node_contains(ui, "text", "Hello Sam!")
        assert existing_msg is not None, "Existing message missing from reopened conversation"
        log("STEP 7", "Verified reopened conversation displays the existing message.")

        # Return Home and check conversation count
        navigate_back_to_home()

        ui = dump_ui()
        # Count conversations: Sam, Alex, Rohan
        sam_count = len([n for n in ui.iter() if n.attrib.get("text") == "Sam"])
        assert sam_count == 1, f"Expected exactly 1 Sam conversation, found {sam_count}"
        log("STEP 7", "Verified idempotency: No duplicate conversations created in Home list.")

        # Step 14 & 15: Reopen conversation and verify persistence
        log("STEP 14 & 15", "Tapping Sam conversation item on Home...")
        sam_item = find_node(ui, text="Sam")
        tap_node(sam_item)
        time.sleep(1.5)

        ui = dump_ui()
        msg_in_chat = find_node_contains(ui, "text", "Hello Sam!")
        assert msg_in_chat is not None, "Message not persisted in ChatScreen"
        log("STEP 14 & 15", "Verified message persists when reopened from Home list.")

        # Step 16 & 17: Force-stop Android app and relaunch
        log("STEP 16 & 17", "Force stopping Android app...")
        run_adb("shell am force-stop com.aster.app")
        time.sleep(1.5)

        log("STEP 16 & 17", "Relaunching app from cold start...")
        run_adb("shell am start -n com.aster.app/.MainActivity")
        time.sleep(2.5)

        # Step 18: Verify conversation and message remain after restart
        log("STEP 18", "Verifying Room persistence across process restart...")
        ui = dump_ui()
        restarted_sam = find_node(ui, text="Sam")
        assert restarted_sam is not None, "Sam conversation vanished after app restart!"
        restarted_preview = find_node_contains(ui, "text", "Hello Sam!")
        assert restarted_preview is not None, "Latest message preview vanished after app restart!"
        safe_screenshot("/home/saad/Aster/m7_relaunch_home.png")

        # Open chat after restart
        tap_node(restarted_sam)
        time.sleep(1.5)
        ui = dump_ui()
        restarted_msg = find_node_contains(ui, "text", "Hello Sam!")
        assert restarted_msg is not None, "Message vanished from ChatScreen after app restart!"
        safe_screenshot("/home/saad/Aster/m7_relaunch_chat.png")
        log("STEP 18", "Verified Room database completely preserved conversation and messages.")

        # Step 19: Verify existing reconnect/outbox functionality still works
        log("STEP 19", "Testing reconnect/outbox resilience in new conversation...")
        # Evict saad connection using temporary session
        evictor = await websockets.connect(TABLET_WS_URL)
        await evictor.send(json.dumps({"type": "AUTH", "userId": "user_saad"}))
        await evictor.recv()  # AUTH_OK
        log("STEP 19", "Temporary WebSocket session evicted Android client.")

        # Android client is now offline. Compose and send outbox message
        outbox_body = f"Outbox queue test at {time.strftime('%H:%M:%S')}"
        input_box = find_node(ui, content_desc="Message input")
        tap_node(input_box)
        time.sleep(0.3)
        type_text(outbox_body)
        time.sleep(0.5)

        ui = dump_ui()
        send_btn = find_node(ui, content_desc="Send message")
        tap_node(send_btn)
        time.sleep(1.0)

        # Close evictor to allow Android client to reconnect
        await evictor.close()
        log("STEP 19", "Released evictor; awaiting Android client automatic reconnect & outbox drain...")

        # Sam should receive the outbox message once Android reconnects
        replayed_frame = await sam_client.wait_for_message(outbox_body, timeout=12.0)
        assert replayed_frame.get("body") == outbox_body
        log("STEP 19", f"Recipient Sam received replayed outbox message: '{outbox_body}'")
        safe_screenshot("/home/saad/Aster/m7_outbox_delivered.png")

        print("=" * 75)
        print("ALL 19 STEPS OF MILESTONE 7 RUNTIME VALIDATION COMPLETED AND PASSED!")
        print("=" * 75)

    finally:
        await sam_client.close()

if __name__ == "__main__":
    asyncio.run(run_m7_full_validation())
