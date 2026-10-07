#!/usr/bin/env python3
"""
Performance & Connection Leak Sanity Test for Aster WebSocket Relay.
Tests:
- 10 concurrent clients
- 100 sequential messages between client pairs
- Verify no duplicate message delivery
- Verify 100% clean session cleanup on disconnect (0 lingering sessions)
"""

import asyncio
import json
import time
import urllib.request
import websockets

SERVER_URI = "ws://127.0.0.1:8080/ws"
HEALTH_URL = "http://127.0.0.1:8080/health"

def get_health():
    with urllib.request.urlopen(HEALTH_URL) as resp:
        return json.loads(resp.read().decode())

async def test_performance_sanity():
    print("=== Aster Performance & Session Sanity Check ===")
    
    # 1. Initial health
    initial_health = get_health()
    print(f"Initial Health: {initial_health}")

    # 2. Connect 10 concurrent clients
    num_clients = 10
    connections = {}
    print(f"\n1. Connecting {num_clients} concurrent clients...")
    for i in range(1, num_clients + 1):
        uid = f"user_c{i}"
        ws = await websockets.connect(SERVER_URI)
        await ws.send(json.dumps({"type": "AUTH", "userId": uid}))
        auth_resp = json.loads(await ws.recv())
        assert auth_resp["type"] == "AUTH_OK", f"Auth failed for {uid}"
        connections[uid] = ws

    health_after_connect = get_health()
    print(f"Health after connect: {health_after_connect}")
    assert health_after_connect["users"] >= num_clients
    assert health_after_connect["connections"] >= num_clients

    # 3. Send 100 sequential messages between user_c1 and user_c2
    c1 = connections["user_c1"]
    c2 = connections["user_c2"]
    num_messages = 100
    latencies = []

    print(f"\n2. Sending {num_messages} sequential messages (user_c1 -> user_c2)...")
    start_all = time.time()

    for seq in range(1, num_messages + 1):
        msg_id = f"perf_seq_{seq}_{int(time.time() * 1000)}"
        t0 = time.time()
        
        # c1 sends
        payload = {
            "type": "SEND_MESSAGE",
            "messageId": msg_id,
            "conversationId": "conv_perf",
            "recipientId": "user_c2",
            "body": f"Sequential test message #{seq}",
            "timestamp": int(time.time() * 1000)
        }
        await c1.send(json.dumps(payload))

        # c1 gets ACK
        ack_raw = await c1.recv()
        ack = json.loads(ack_raw)
        assert ack["type"] == "MESSAGE_ACCEPTED", f"Expected ACK, got {ack}"
        assert ack["messageId"] == msg_id

        # c2 receives relayed message
        relay_raw = await c2.recv()
        relay = json.loads(relay_raw)
        assert relay["type"] == "MESSAGE", f"Expected MESSAGE, got {relay}"
        assert relay["messageId"] == msg_id
        assert relay["body"] == f"Sequential test message #{seq}"

        # c2 sends ACK_RECEIVED
        await c2.send(json.dumps({"type": "ACK_RECEIVED", "messageId": msg_id}))

        t_elapsed = (time.time() - t0) * 1000
        latencies.append(t_elapsed)

    total_time = time.time() - start_all
    avg_latency = sum(latencies) / len(latencies)
    p95_latency = sorted(latencies)[int(len(latencies) * 0.95)]

    print(f"Completed {num_messages} round-trips in {total_time:.3f}s")
    print(f"Average latency: {avg_latency:.2f}ms | p95: {p95_latency:.2f}ms | Throughput: {num_messages / total_time:.1f} msg/sec")

    # 4. Disconnect all clients and verify session cleanup
    print(f"\n3. Disconnecting all {num_clients} clients...")
    for uid, ws in connections.items():
        await ws.close()

    await asyncio.sleep(0.5)
    final_health = get_health()
    print(f"Final Health: {final_health}")
    assert final_health["connections"] == 0, f"Expected 0 connections, got {final_health['connections']}"
    assert final_health["users"] == 0, f"Expected 0 users, got {final_health['users']}"

    print("\n✅ Performance & Session Sanity Check: PASSED with ZERO connection leaks!")

if __name__ == "__main__":
    asyncio.run(test_performance_sanity())
