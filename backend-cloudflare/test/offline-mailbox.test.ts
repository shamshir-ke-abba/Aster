import { describe, it, expect, beforeAll, afterAll } from "vitest";
import {
  getTestWorker,
  stopTestWorker,
  provisionFreshUser,
  TestWsClient,
} from "./helpers/test-client";
import { Unstable_DevWorker } from "wrangler";

describe("Aster Cloudflare Backend - Durable Offline Mailbox & Replay", () => {
  let worker: Unstable_DevWorker;

  beforeAll(async () => {
    worker = await getTestWorker();
  });

  afterAll(async () => {
    await stopTestWorker();
  });

  it("Queues messages for offline recipient and replays them upon connect", async () => {
    const userA = await provisionFreshUser(worker, "user_sender");
    const userB = await provisionFreshUser(worker, "user_offline");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // Client A connects, Client B is offline
    const clientA = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientA.sendAndAwaitAuth(userA.token);

    const msgId = `offline_msg_${Date.now()}`;
    const timestamp = Date.now();

    // A sends to offline B
    clientA.send({
      type: "SEND_MESSAGE",
      messageId: msgId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Hello offline B! This should be stored in Durable Object SQLite mailbox.",
      timestamp,
    });

    // A gets MESSAGE_ACCEPTED
    const accepted = await clientA.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");
    expect(accepted.messageId).toBe(msgId);

    // Client A closes
    clientA.close();

    // Now recipient B connects for the first time
    const clientB = new TestWsClient(wsUrl);
    await clientB.connect();
    await clientB.sendAndAwaitAuth(userB.token);

    // B should immediately receive the queued message
    const replayed = await clientB.waitForMessage((m) => m.type === "MESSAGE");
    expect(replayed.messageId).toBe(msgId);
    expect(replayed.senderId).toBe(userA.userId);
    expect(replayed.body).toBe("Hello offline B! This should be stored in Durable Object SQLite mailbox.");

    // B ACKs the message
    clientB.send({
      type: "ACK_RECEIVED",
      messageId: msgId,
    });

    // Wait a brief moment for ACK to persist in DO SQLite
    await new Promise((r) => setTimeout(r, 100));
    clientB.close();

    // B reconnects in a new session — already ACKed message must NOT be replayed
    const clientB2 = new TestWsClient(wsUrl);
    await clientB2.connect();
    await clientB2.sendAndAwaitAuth(userB.token);

    // Wait 500ms to verify no messages are replayed
    await new Promise((r) => setTimeout(r, 500));
    const replayedAgain = clientB2.messages.filter((m) => m.type === "MESSAGE");
    expect(replayedAgain.length).toBe(0);

    clientB2.close();
  });

  it("Preserves chronological ordering for multiple offline messages across sessions", async () => {
    const userA = await provisionFreshUser(worker, "user_sender_multi");
    const userB = await provisionFreshUser(worker, "user_offline_multi");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientA.sendAndAwaitAuth(userA.token);

    const msgCount = 5;
    const msgIds: string[] = [];

    for (let i = 0; i < msgCount; i++) {
      const id = `seq_msg_${Date.now()}_${i}`;
      msgIds.push(id);
      clientA.send({
        type: "SEND_MESSAGE",
        messageId: id,
        conversationId: `conv_${userB.userId}`,
        recipientId: userB.userId,
        body: `Ordered message #${i}`,
        timestamp: Date.now() + i,
      });

      const accepted = await clientA.waitForMessage(
        (m) => m.type === "MESSAGE_ACCEPTED" && m.messageId === id
      );
      expect(accepted.messageId).toBe(id);
      // Ensure slight separation between server received timestamps
      await new Promise((r) => setTimeout(r, 20));
    }

    clientA.close();

    // Offline B connects and receives all messages
    const clientB = new TestWsClient(wsUrl);
    await clientB.connect();
    await clientB.sendAndAwaitAuth(userB.token);

    for (let i = 0; i < msgCount; i++) {
      const received = await clientB.waitForMessage(
        (m) => m.type === "MESSAGE" && m.messageId === msgIds[i]
      );
      expect(received.messageId).toBe(msgIds[i]);
      expect(received.body).toBe(`Ordered message #${i}`);

      // ACK each received message
      clientB.send({
        type: "ACK_RECEIVED",
        messageId: received.messageId,
      });
    }

    await new Promise((r) => setTimeout(r, 100));
    clientB.close();
  });

  it("Replays unacknowledged DELIVERED messages if client disconnected before ACKing", async () => {
    const userA = await provisionFreshUser(worker, "user_sender_unack");
    const userB = await provisionFreshUser(worker, "user_offline_unack");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // A sends message while B is offline
    const clientA = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientA.sendAndAwaitAuth(userA.token);

    const msgId = `unack_msg_${Date.now()}`;
    clientA.send({
      type: "SEND_MESSAGE",
      messageId: msgId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Will disconnect before acking",
      timestamp: Date.now(),
    });
    await clientA.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");
    clientA.close();

    // B connects, receives message (transitions to DELIVERED in DO SQLite), but disconnects WITHOUT acking
    const clientB1 = new TestWsClient(wsUrl);
    await clientB1.connect();
    await clientB1.sendAndAwaitAuth(userB.token);
    const msg = await clientB1.waitForMessage((m) => m.type === "MESSAGE");
    expect(msg.messageId).toBe(msgId);

    // Disconnect without ACKing
    clientB1.close();

    // B reconnects — since status is DELIVERED (not ACKED), it MUST be replayed again per Aster mailbox semantics
    const clientB2 = new TestWsClient(wsUrl);
    await clientB2.connect();
    await clientB2.sendAndAwaitAuth(userB.token);

    const replayed = await clientB2.waitForMessage((m) => m.type === "MESSAGE");
    expect(replayed.messageId).toBe(msgId);

    // Now B ACKs
    clientB2.send({
      type: "ACK_RECEIVED",
      messageId: msgId,
    });

    await new Promise((r) => setTimeout(r, 100));
    clientB2.close();

    // B reconnects again -> no message
    const clientB3 = new TestWsClient(wsUrl);
    await clientB3.connect();
    await clientB3.sendAndAwaitAuth(userB.token);
    await new Promise((r) => setTimeout(r, 400));
    expect(clientB3.messages.filter((m) => m.type === "MESSAGE").length).toBe(0);
    clientB3.close();
  });

  it("Offline mailbox preserves replyToId, effect, and replays offline reaction updates", async () => {
    const userA = await provisionFreshUser(worker, "user_sender_fx");
    const userB = await provisionFreshUser(worker, "user_offline_fx");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // A is online, B is offline
    const clientA = new TestWsClient(wsUrl);
    await clientA.connect();
    await clientA.sendAndAwaitAuth(userA.token);

    const msgId = `offline_fx_${Date.now()}`;
    const parentId = `parent_msg_${Date.now()}`;

    // A sends message with replyToId and effect 'pulse' to offline B
    clientA.send({
      type: "SEND_MESSAGE",
      messageId: msgId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Pulsing reply while you were away",
      replyToId: parentId,
      effect: "pulse",
      timestamp: Date.now(),
    });
    await clientA.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");

    // A also reacts to the message while B is offline
    clientA.send({
      type: "TOGGLE_REACTION",
      messageId: msgId,
      conversationId: `conv_${userB.userId}`,
      emoji: "✨",
    });
    await clientA.waitForMessage((m) => m.type === "REACTION_UPDATE");

    clientA.close();

    // Now B comes online
    const clientB = new TestWsClient(wsUrl);
    await clientB.connect();
    await clientB.sendAndAwaitAuth(userB.token);

    // B should receive the replayed message with replyToId and effect
    const replayedMsg = await clientB.waitForMessage((m) => m.type === "MESSAGE" && m.messageId === msgId);
    expect(replayedMsg.replyToId).toBe(parentId);
    expect(replayedMsg.effect).toBe("pulse");

    // B should also receive the queued reaction update
    const replayedReaction = await clientB.waitForMessage(
      (m) => m.type === "REACTION_UPDATE" && m.messageId === msgId
    );
    expect(replayedReaction.reactions["✨"]).toEqual([userA.userId]);

    clientB.close();
  });
});
