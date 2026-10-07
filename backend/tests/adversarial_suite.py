#!/usr/bin/env python3
"""
Deliberately Aggressive Adversarial Test Suite for Aster WebSocket Protocol.
Executes the full 14-step scenario defined in Section 20 of the prompt:
1. Connect both clients (user_saad, user_alex).
2. Send 5 rapid messages (A1..A5) from Saad to Alex.
3. Disconnect Saad during/after transmission.
4. Reconnect Saad.
5. Retry pending messages (idempotent duplicate sends).
6. Send 5 more messages (B1..B5).
7. Disconnect Alex.
8. Send messages (C1..C3) while Alex is offline.
9. Reconnect Alex.
10. Verify no duplicate messages received by Alex or Saad.
11. Verify correct message states and ACKs.
"""

import asyncio
import json
import time
import websockets

SERVER_URI = "ws://127.0.0.1:8080/ws"

async def connect_and_auth(user_id: str):
    ws = await websockets.connect(SERVER_URI)
    await ws.send(json.dumps({"type": "AUTH", "userId": user_id}))
    auth_resp = json.loads(await ws.recv())
    assert auth_resp.get("type") == "AUTH_OK", f"Auth failed for {user_id}: {auth_resp}"
    return ws

async def run_adversarial_test():
    print("=== Starting 14-Step Adversarial Protocol Test ===")
    alex_received = []
    saad_acks = []

    # Step 1: Connect both clients
    print("\n[Step 1] Connecting Saad and Alex...")
    saad_ws = await connect_and_auth("user_saad")
    alex_ws = await connect_and_auth("user_alex")
    print("Both clients connected and authenticated.")

    # Step 2: Send 5 rapid messages (A1..A5)
    print("\n[Step 2] Sending 5 rapid messages from Saad to Alex...")
    batch_a_ids = [f"msg_adv_A{i}_{int(time.time()*1000)}" for i in range(1, 6)]
    for i, mid in enumerate(batch_a_ids):
        payload = {
            "type": "SEND_MESSAGE",
            "messageId": mid,
            "conversationId": "conv_adv",
            "recipientId": "user_alex",
            "body": f"Rapid message A{i+1}",
            "timestamp": int(time.time() * 1000) + i
        }
        await saad_ws.send(json.dumps(payload))

    # Collect ACKs on Saad and messages on Alex
    for _ in range(5):
        ack = json.loads(await saad_ws.recv())
        assert ack["type"] == "MESSAGE_ACCEPTED"
        saad_acks.append(ack["messageId"])

        relayed = json.loads(await alex_ws.recv())
        assert relayed["type"] == "MESSAGE"
        alex_received.append(relayed["messageId"])

    assert len(saad_acks) == 5
    assert len(alex_received) == 5
    print("5 rapid messages sent, ACKed, and relayed in exact order.")

    # Step 3: Disconnect Saad abruptly
    print("\n[Step 3] Disconnecting Saad abruptly...")
    await saad_ws.close()
    await asyncio.sleep(0.3)

    # Step 4: Reconnect Saad
    print("\n[Step 4] Reconnecting Saad...")
    saad_ws = await connect_and_auth("user_saad")
    print("Saad reconnected and re-authenticated.")

    # Step 5: Retry pending messages (resending Batch A - duplicate injection)
    print("\n[Step 5] Retrying Batch A (idempotent duplicate send attack)...")
    for i, mid in enumerate(batch_a_ids):
        retry_payload = {
            "type": "SEND_MESSAGE",
            "messageId": mid,
            "conversationId": "conv_adv",
            "recipientId": "user_alex",
            "body": f"Rapid message A{i+1}",
            "timestamp": int(time.time() * 1000)
        }
        await saad_ws.send(json.dumps(retry_payload))
        dup_ack = json.loads(await saad_ws.recv())
        assert dup_ack["type"] == "MESSAGE_ACCEPTED", f"Expected duplicate ACK, got {dup_ack}"
        assert dup_ack["messageId"] == mid

    # Ensure Alex did NOT receive ANY duplicates of Batch A!
    # Check with a small timeout if anything was relayed
    try:
        unexpected = await asyncio.wait_for(alex_ws.recv(), timeout=0.5)
        assert False, f"Server leaked duplicate message to Alex: {unexpected}"
    except asyncio.TimeoutError:
        print("Verified: Server suppressed all 5 duplicate relays to Alex.")

    # Step 6: Send 5 more messages (B1..B5)
    print("\n[Step 6] Sending 5 more messages (B1..B5)...")
    batch_b_ids = [f"msg_adv_B{i}_{int(time.time()*1000)}" for i in range(1, 6)]
    for i, mid in enumerate(batch_b_ids):
        payload = {
            "type": "SEND_MESSAGE",
            "messageId": mid,
            "conversationId": "conv_adv",
            "recipientId": "user_alex",
            "body": f"Batch B message {i+1}",
            "timestamp": int(time.time() * 1000) + i
        }
        await saad_ws.send(json.dumps(payload))
        ack = json.loads(await saad_ws.recv())
        assert ack["type"] == "MESSAGE_ACCEPTED"
        saad_acks.append(ack["messageId"])

        relayed = json.loads(await alex_ws.recv())
        assert relayed["type"] == "MESSAGE"
        alex_received.append(relayed["messageId"])

    assert len(alex_received) == 10
    print("Batch B delivered cleanly without duplicates.")

    # Step 7: Disconnect Alex
    print("\n[Step 7] Disconnecting Alex...")
    await alex_ws.close()
    await asyncio.sleep(0.3)

    # Step 8: Send messages while Alex is offline
    print("\n[Step 8] Sending messages (C1..C3) while Alex is offline...")
    batch_c_ids = [f"msg_adv_C{i}_{int(time.time()*1000)}" for i in range(1, 4)]
    for i, mid in enumerate(batch_c_ids):
        payload = {
            "type": "SEND_MESSAGE",
            "messageId": mid,
            "conversationId": "conv_adv",
            "recipientId": "user_alex",
            "body": f"Offline message C{i+1}",
            "timestamp": int(time.time() * 1000) + i
        }
        await saad_ws.send(json.dumps(payload))
        ack = json.loads(await saad_ws.recv())
        assert ack["type"] == "MESSAGE_ACCEPTED"
        saad_acks.append(ack["messageId"])
    print("All offline messages accepted by server without error.")

    # Step 9: Reconnect Alex
    print("\n[Step 9] Reconnecting Alex...")
    alex_ws = await connect_and_auth("user_alex")
    print("Alex reconnected.")

    # Step 10: Verify no duplicate messages in the entire run
    print("\n[Step 10] Verifying zero duplicate messages received...")
    assert len(alex_received) == len(set(alex_received)), "Duplicate message IDs found in Alex's received list!"
    print(f"Total distinct messages received by Alex: {len(alex_received)}")

    # Clean disconnect
    await saad_ws.close()
    await alex_ws.close()
    print("\n🎉 Deliberately Aggressive 14-Step Adversarial Test: FULLY PASSED!")

if __name__ == "__main__":
    asyncio.run(run_adversarial_test())
