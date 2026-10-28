import { describe, it, expect, beforeAll, afterAll } from "vitest";
import {
  getTestWorker,
  stopTestWorker,
  provisionFreshUser,
  TestWsClient,
} from "./helpers/test-client";
import { Unstable_DevWorker } from "wrangler";

describe("Aster Cloudflare Backend - Security, Bounds & Abuse Prevention", () => {
  let worker: Unstable_DevWorker;

  beforeAll(async () => {
    worker = await getTestWorker();
  });

  afterAll(async () => {
    await stopTestWorker();
  });

  it("Rejects oversized payloads exceeding 64KB with PAYLOAD_TOO_LARGE", async () => {
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;
    const client = new TestWsClient(wsUrl);
    await client.connect();

    // Send payload > 65,536 bytes
    const giantPayload = JSON.stringify({
      type: "PING",
      padding: "X".repeat(70000),
    });
    client.ws?.send(giantPayload);

    const err = await client.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("PAYLOAD_TOO_LARGE");

    client.close();
  });

  it("Rejects malformed JSON syntax with MALFORMED_JSON", async () => {
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;
    const client = new TestWsClient(wsUrl);
    await client.connect();

    client.ws?.send("{not_valid_json: 1234");

    const err = await client.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("MALFORMED_JSON");

    client.close();
  });

  it("Rejects messages missing type or with unknown type", async () => {
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;
    const client = new TestWsClient(wsUrl);
    await client.connect();

    // Missing type
    client.ws?.send(JSON.stringify({ foo: "bar" }));
    const err1 = await client.waitForMessage((m) => m.type === "ERROR");
    expect(err1.code).toBe("MISSING_TYPE");

    // Unknown type
    client.ws?.send(JSON.stringify({ type: "HACK_DATABASE" }));
    const err2 = await client.waitForMessage((m) => m.type === "ERROR" && m.code === "UNKNOWN_TYPE");
    expect(err2.code).toBe("UNKNOWN_TYPE");

    client.close();
  });

  it("Enforces protocol field bounds (body, id regex, timestamp)", async () => {
    const user = await provisionFreshUser(worker, "user_bounds");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const client = new TestWsClient(wsUrl);
    await client.connect();
    await client.sendAndAwaitAuth(user.token);

    // 1. Invalid messageId
    client.send({
      type: "SEND_MESSAGE",
      messageId: "invalid/id!@#$",
      conversationId: "conv_valid",
      recipientId: "user_other",
      body: "Test",
      timestamp: Date.now(),
    });
    const errId = await client.waitForMessage((m) => m.type === "ERROR" && m.code === "INVALID_MESSAGE_ID");
    expect(errId.code).toBe("INVALID_MESSAGE_ID");

    // 2. Blank body
    client.send({
      type: "SEND_MESSAGE",
      messageId: `msg_${Date.now()}`,
      conversationId: "conv_valid",
      recipientId: "user_other",
      body: "   ",
      timestamp: Date.now(),
    });
    const errBody = await client.waitForMessage((m) => m.type === "ERROR" && m.code === "INVALID_BODY");
    expect(errBody.code).toBe("INVALID_BODY");

    // 3. Body exceeding 4096 characters
    client.send({
      type: "SEND_MESSAGE",
      messageId: `msg_${Date.now()}`,
      conversationId: "conv_valid",
      recipientId: "user_other",
      body: "A".repeat(4097),
      timestamp: Date.now(),
    });
    const errBodyTooLong = await client.waitForMessage((m) => m.type === "ERROR" && m.code === "INVALID_BODY");
    expect(errBodyTooLong.code).toBe("INVALID_BODY");

    // 4. Timestamp drifted too far into the future (> 24 hours)
    client.send({
      type: "SEND_MESSAGE",
      messageId: `msg_${Date.now()}`,
      conversationId: "conv_valid",
      recipientId: "user_other",
      body: "Time travel message",
      timestamp: Date.now() + 48 * 3600 * 1000,
    });
    const errTime = await client.waitForMessage((m) => m.type === "ERROR" && m.code === "INVALID_TIMESTAMP");
    expect(errTime.code).toBe("INVALID_TIMESTAMP");

    client.close();
  });

  it("Rejects unauthenticated operations with UNAUTHENTICATED", async () => {
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;
    const client = new TestWsClient(wsUrl);
    await client.connect();

    // Client sends SEND_MESSAGE without authenticating first
    client.send({
      type: "SEND_MESSAGE",
      messageId: `unauth_msg_${Date.now()}`,
      conversationId: "conv_target",
      recipientId: "user_target",
      body: "Sneaky message",
      timestamp: Date.now(),
    });

    const err = await client.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("UNAUTHENTICATED");

    client.close();
  });

  it("Enforces server-side sender identity over spoofed client fields", async () => {
    const userA = await provisionFreshUser(worker, "user_alice");
    const userB = await provisionFreshUser(worker, "user_bob");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);

    await clientA.connect();
    await clientB.connect();

    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    const messageId = `spoof_test_${Date.now()}`;

    // Alice attempts to send a message claiming senderId is "admin_root"
    (clientA.ws as any).send(
      JSON.stringify({
        type: "SEND_MESSAGE",
        messageId,
        conversationId: `conv_${userB.userId}`,
        recipientId: userB.userId,
        senderId: "admin_root",
        body: "Attempting to spoof sender identity",
        timestamp: Date.now(),
      })
    );

    await clientA.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");

    // Bob receives the message — senderId MUST be userA.userId, NOT "admin_root"
    const received = await clientB.waitForMessage((m) => m.type === "MESSAGE");
    expect(received.senderId).toBe(userA.userId);
    expect(received.senderId).not.toBe("admin_root");

    clientA.close();
    clientB.close();
  });

  it("Rejects unauthorized ACK from a user who is not the designated recipient", async () => {
    const userA = await provisionFreshUser(worker, "user_sender_ack");
    const userB = await provisionFreshUser(worker, "user_recip_ack");
    const attacker = await provisionFreshUser(worker, "user_attacker_ack");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    const clientAttacker = new TestWsClient(wsUrl);

    await clientA.connect();
    await clientAttacker.connect();

    await clientA.sendAndAwaitAuth(userA.token);
    await clientAttacker.sendAndAwaitAuth(attacker.token);

    const messageId = `priv_msg_${Date.now()}`;

    // A sends to offline B
    clientA.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Confidential message for B only",
      timestamp: Date.now(),
    });
    await clientA.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");

    // Attacker attempts to ACK B's message
    clientAttacker.send({
      type: "ACK_RECEIVED",
      messageId,
    });

    const err = await clientAttacker.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("UNAUTHORIZED_ACK");

    clientA.close();
    clientAttacker.close();
  });

  it("Prohibits self-messaging (recipientId === senderId)", async () => {
    const user = await provisionFreshUser(worker, "user_lonely");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const client = new TestWsClient(wsUrl);
    await client.connect();
    await client.sendAndAwaitAuth(user.token);

    client.send({
      type: "SEND_MESSAGE",
      messageId: `self_msg_${Date.now()}`,
      conversationId: `conv_self`,
      recipientId: user.userId,
      body: "Talking to myself",
      timestamp: Date.now(),
    });

    const err = await client.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("SELF_MESSAGE_NOT_ALLOWED");

    client.close();
  });

  it("Secures administrative endpoints against unauthorized access", async () => {
    // 1. Missing admin key -> 401
    const unauthRes = await worker.fetch("/api/provision", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId: "user_evil", deviceId: "dev_evil" }),
    });
    expect(unauthRes.status).toBe(401);

    // 2. Wrong admin key -> 401
    const wrongKeyRes = await worker.fetch("/api/provision", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Aster-Admin-Key": "wrong_and_fake_key",
      },
      body: JSON.stringify({ userId: "user_evil", deviceId: "dev_evil" }),
    });
    expect(wrongKeyRes.status).toBe(401);
  });
});
