#!/usr/bin/env python3
"""
Simulated Client 'user_alex' for Aster Realtime WebSocket Verification.
Connects to ws://127.0.0.1:8080/ws, authenticates as user_alex,
records messages, ACKs received messages, and can send messages to user_saad.
"""

import asyncio
import json
import os
import sys
import time
import websockets

DEFAULT_URI = os.environ.get("ASTER_SERVER_URI", "ws://127.0.0.1:8080/ws")

async def run_alex(server_uri: str = DEFAULT_URI, auto_reply: bool = False, reply_text: str = "Hey Saad! Got your message in realtime! 🚀"):
    print(f"[ALEX] Connecting to {server_uri}...")
    async with websockets.connect(server_uri) as ws:
        # 1. Send AUTH
        auth_msg = {"type": "AUTH", "userId": "user_alex"}
        await ws.send(json.dumps(auth_msg))
        print(f"[ALEX] Sent: {auth_msg}")

        # 2. Wait for AUTH_OK
        resp = await ws.recv()
        auth_resp = json.loads(resp)
        print(f"[ALEX] Received: {auth_resp}")
        if auth_resp.get("type") != "AUTH_OK":
            print(f"[ALEX] Authentication failed! Exiting.")
            return

        print(f"[ALEX] Authenticated successfully as user_alex. Listening for incoming messages...")

        while True:
            raw_msg = await ws.recv()
            data = json.loads(raw_msg)
            msg_type = data.get("type")
            print(f"[ALEX] <<< Received message: {data}")

            if msg_type == "MESSAGE":
                sender = data.get("senderId")
                msg_id = data.get("messageId")
                body = data.get("body")
                conv_id = data.get("conversationId")
                print(f"[ALEX] Received chat message from '{sender}': '{body}' (id={msg_id})")

                # Send ACK_RECEIVED back
                ack = {"type": "ACK_RECEIVED", "messageId": msg_id}
                await ws.send(json.dumps(ack))
                print(f"[ALEX] >>> Sent ACK_RECEIVED for {msg_id}")

                if auto_reply and sender == "user_saad":
                    await asyncio.sleep(0.5)
                    reply_id = f"msg_alex_{int(time.time() * 1000)}"
                    send_msg = {
                        "type": "SEND_MESSAGE",
                        "messageId": reply_id,
                        "conversationId": conv_id,
                        "recipientId": "user_saad",
                        "body": reply_text,
                        "timestamp": int(time.time() * 1000)
                    }
                    await ws.send(json.dumps(send_msg))
                    print(f"[ALEX] >>> Sent reply: {send_msg}")

if __name__ == "__main__":
    auto = "--auto-reply" in sys.argv
    reply = "Hey Saad! Received your message over WebSocket!" 
    uri = DEFAULT_URI
    for arg in sys.argv:
        if arg.startswith("--reply="):
            reply = arg.split("=", 1)[1]
        elif arg.startswith("--uri="):
            uri = arg.split("=", 1)[1]
    asyncio.run(run_alex(server_uri=uri, auto_reply=auto, reply_text=reply))
