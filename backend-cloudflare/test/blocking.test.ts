import { describe, it, expect, beforeAll, afterAll } from "vitest";
import {
  getTestWorker,
  stopTestWorker,
  provisionFreshUser,
  TestWsClient,
} from "./helpers/test-client";
import { Unstable_DevWorker } from "wrangler";

describe("Aster Cloudflare Backend - Blocking Enforcement & Integrity", () => {
  let worker: Unstable_DevWorker;

  beforeAll(async () => {
    worker = await getTestWorker();
  });

  afterAll(async () => {
    await stopTestWorker();
  });

  it("1. Blocked sender cannot send direct message (real-time BLOCKED error)", async () => {
    const userA = await provisionFreshUser(worker, "user_blocker_a");
    const userB = await provisionFreshUser(worker, "user_blocked_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // A blocks B via REST
    const blockRes = await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });
    expect(blockRes.status).toBe(200);

    const clientB = new TestWsClient(wsUrl);
    await clientB.connect();
    await clientB.sendAndAwaitAuth(userB.token);

    // B attempts to send direct message to A
    const msgId = `msg_b_to_a_${Date.now()}`;
    clientB.send({
      type: "SEND_MESSAGE",
      messageId: msgId,
      conversationId: `conv_${userA.userId}`,
      recipientId: userA.userId,
      body: "Unwanted direct message",
      timestamp: Date.now(),
    });

    const err = await clientB.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("BLOCKED");
    expect(err.message).toContain("blocked");

    clientB.close();
  });

  it("2. Blocked sender cannot bypass restriction through reactions or invalid paths", async () => {
    const userA = await provisionFreshUser(worker, "user_react_a");
    const userB = await provisionFreshUser(worker, "user_react_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientB.connect();
    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    // A sends legitimate message to B first (before block)
    const initialMsgId = `msg_initial_${Date.now()}`;
    clientA.send({
      type: "SEND_MESSAGE",
      messageId: initialMsgId,
      conversationId: `conv_${userA.userId}_${userB.userId}`,
      recipientId: userB.userId,
      body: "Initial conversation message",
      timestamp: Date.now(),
    });
    await clientA.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");
    await clientB.waitForMessage((m) => m.type === "MESSAGE");

    // A blocks B
    await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });

    // B attempts to toggle reaction on A's message -> BLOCKED error
    clientB.send({
      type: "TOGGLE_REACTION",
      messageId: initialMsgId,
      conversationId: `conv_${userA.userId}_${userB.userId}`,
      emoji: "❤️",
    });

    const reactErr = await clientB.waitForMessage((m) => m.type === "ERROR");
    expect(reactErr.code).toBe("BLOCKED");

    clientA.close();
    clientB.close();
  });

  it("3. Blocked sender cannot queue offline message in blocker's mailbox", async () => {
    const userA = await provisionFreshUser(worker, "user_off_a");
    const userB = await provisionFreshUser(worker, "user_off_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // A blocks B while A is offline
    await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });

    // B connects and tries to send to offline A
    const clientB = new TestWsClient(wsUrl);
    await clientB.connect();
    await clientB.sendAndAwaitAuth(userB.token);

    const msgId = `msg_off_blocked_${Date.now()}`;
    clientB.send({
      type: "SEND_MESSAGE",
      messageId: msgId,
      conversationId: `conv_${userA.userId}`,
      recipientId: userA.userId,
      body: "Trying to reach offline blocker",
      timestamp: Date.now(),
    });

    const err = await clientB.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("BLOCKED");
    clientB.close();

    // Now A connects: A must NOT receive any message from B
    const clientA = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientA.sendAndAwaitAuth(userA.token);

    // Wait 200ms to verify no messages are delivered
    let deliveredToA = false;
    clientA.waitForMessage((m) => m.type === "MESSAGE", 500)
      .then(() => { deliveredToA = true; })
      .catch(() => {});
    await new Promise((r) => setTimeout(r, 250));
    expect(deliveredToA).toBe(false);

    clientA.close();
  });

  it("4. Pre-existing queued message is purged and not delivered after block (Scenario A)", async () => {
    const userA = await provisionFreshUser(worker, "user_scen_a");
    const userB = await provisionFreshUser(worker, "user_scen_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // 1. A and B are unblocked. A is offline.
    // 2. B sends a message to A while A is offline -> successfully queued in mailbox
    const clientB = new TestWsClient(wsUrl);
    await clientB.connect();
    await clientB.sendAndAwaitAuth(userB.token);

    const queuedMsgId = `msg_queued_before_block_${Date.now()}`;
    clientB.send({
      type: "SEND_MESSAGE",
      messageId: queuedMsgId,
      conversationId: `conv_${userA.userId}`,
      recipientId: userA.userId,
      body: "Sent while A was offline, before block",
      timestamp: Date.now(),
    });

    const accepted = await clientB.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");
    expect(accepted.messageId).toBe(queuedMsgId);
    clientB.close();

    // 3. A blocks B BEFORE connecting to WebSocket
    const blockRes = await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });
    expect(blockRes.status).toBe(200);

    // 4. A connects now. The previously queued message must NOT appear.
    const clientA = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientA.sendAndAwaitAuth(userA.token);

    let delivered = false;
    clientA.waitForMessage((m) => m.type === "MESSAGE" && m.messageId === queuedMsgId, 500)
      .then(() => { delivered = true; })
      .catch(() => {});
    await new Promise((r) => setTimeout(r, 300));
    expect(delivered).toBe(false);

    clientA.close();
  });

  it("5. Block survives reconnect across sessions", async () => {
    const userA = await provisionFreshUser(worker, "user_reconn_a");
    const userB = await provisionFreshUser(worker, "user_reconn_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // A blocks B
    await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });

    // Both clients connect in a fresh session
    const clientB = new TestWsClient(wsUrl);
    await clientB.connect();
    await clientB.sendAndAwaitAuth(userB.token);

    clientB.send({
      type: "SEND_MESSAGE",
      messageId: `msg_after_reconnect_${Date.now()}`,
      conversationId: `conv_${userA.userId}`,
      recipientId: userA.userId,
      body: "Testing block persistence across sessions",
      timestamp: Date.now(),
    });

    const err = await clientB.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("BLOCKED");

    clientB.close();
  });

  it("6. Reverse-direction blocking semantics are correct (directional, not mutual)", async () => {
    const userA = await provisionFreshUser(worker, "user_dir_a");
    const userB = await provisionFreshUser(worker, "user_dir_b");

    // A blocks B
    await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });

    // Check A's blocked list: contains B
    const aBlockedRes = await worker.fetch("/api/friends/blocked", {
      headers: { Authorization: `Bearer ${userA.token}` },
    });
    const aBlocked = (await aBlockedRes.json()) as any;
    expect(aBlocked.blocked.some((u: any) => u.userId === userB.userId)).toBe(true);

    // Check B's blocked list: does NOT contain A (blocking is not mutual)
    const bBlockedRes = await worker.fetch("/api/friends/blocked", {
      headers: { Authorization: `Bearer ${userB.token}` },
    });
    const bBlocked = (await bBlockedRes.json()) as any;
    expect(bBlocked.blocked.some((u: any) => u.userId === userA.userId)).toBe(false);
  });

  it("7. Blocker sending to blocked user is also rejected at server authorization layer", async () => {
    const userA = await provisionFreshUser(worker, "user_blocker_self_a");
    const userB = await provisionFreshUser(worker, "user_blocked_target_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // A blocks B
    await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });

    // If client A maliciously attempts SEND_MESSAGE to B:
    const clientA = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientA.sendAndAwaitAuth(userA.token);

    clientA.send({
      type: "SEND_MESSAGE",
      messageId: `msg_a_to_b_${Date.now()}`,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Outdated client trying to send to blocked user",
      timestamp: Date.now(),
    });

    const err = await clientA.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("BLOCKED");

    clientA.close();
  });

  it("8. Blocked user cannot send friend requests to blocker", async () => {
    const userA = await provisionFreshUser(worker, "user_freq_a");
    const userB = await provisionFreshUser(worker, "user_freq_b");

    // A blocks B
    await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });

    // B tries to send friend request to A
    const freqRes = await worker.fetch("/api/friends/request", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userB.token}`,
      },
      body: JSON.stringify({ targetUserId: userA.userId }),
    });

    expect(freqRes.status).toBe(403);
    const body = (await freqRes.json()) as any;
    expect(body.error).toContain("unavailable");
  });

  it("9. Group messaging works normally and does not bypass 1:1 direct block", async () => {
    const userA = await provisionFreshUser(worker, "user_grp_a");
    const userB = await provisionFreshUser(worker, "user_grp_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // A blocks B in 1:1 direct relationship
    await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });

    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientB.connect();
    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    // A creates a group containing B
    clientA.send({
      type: "CREATE_GROUP",
      requestId: `req_cg_${Date.now()}`,
      name: "Shared Constellation",
      memberIds: [userB.userId],
    });
    const groupCreated = await clientA.waitForMessage((m) => m.type === "GROUP_OPERATION_OK" && m.action === "CREATE_GROUP");
    const groupId = groupCreated.groupId;

    // B sends a message to the GROUP -> A receives it as a group message
    const groupMsgId = `msg_group_${Date.now()}`;
    clientB.send({
      type: "SEND_MESSAGE",
      messageId: groupMsgId,
      conversationId: groupId,
      body: "Hello squad members",
      timestamp: Date.now(),
    });

    const accepted = await clientB.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");
    expect(accepted.messageId).toBe(groupMsgId);

    const aRecvGroupMsg = await clientA.waitForMessage((m) => m.type === "MESSAGE" && m.messageId === groupMsgId);
    expect(aRecvGroupMsg.conversationId).toBe(groupId);

    // But direct 1:1 message from B to A is STILL BLOCKED
    clientB.send({
      type: "SEND_MESSAGE",
      messageId: `msg_direct_bypass_${Date.now()}`,
      conversationId: `conv_${userA.userId}`,
      recipientId: userA.userId,
      body: "Trying 1:1 direct bypass",
      timestamp: Date.now(),
    });
    const directErr = await clientB.waitForMessage((m) => m.type === "ERROR");
    expect(directErr.code).toBe("BLOCKED");

    clientA.close();
    clientB.close();
  });

  it("10. Unblocking restores communication immediately", async () => {
    const userA = await provisionFreshUser(worker, "user_unblock_a");
    const userB = await provisionFreshUser(worker, "user_unblock_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // A blocks B
    await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });

    // A unblocks B
    const unblockRes = await worker.fetch("/api/friends/unblock", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userA.token}`,
      },
      body: JSON.stringify({ targetUserId: userB.userId }),
    });
    expect(unblockRes.status).toBe(200);

    // B connects and sends message to A -> delivered successfully!
    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientB.connect();
    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    const restoredMsgId = `msg_restored_${Date.now()}`;
    clientB.send({
      type: "SEND_MESSAGE",
      messageId: restoredMsgId,
      conversationId: `conv_${userA.userId}`,
      recipientId: userA.userId,
      body: "Communications restored after unblock",
      timestamp: Date.now(),
    });

    const accepted = await clientB.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");
    expect(accepted.messageId).toBe(restoredMsgId);

    const delivered = await clientA.waitForMessage((m) => m.type === "MESSAGE" && m.messageId === restoredMsgId);
    expect(delivered.body).toBe("Communications restored after unblock");

    clientA.close();
    clientB.close();
  });
});
