import { describe, it, expect, beforeAll, afterAll } from "vitest";
import {
  getTestWorker,
  stopTestWorker,
  provisionFreshUser,
  TestWsClient,
} from "./helpers/test-client";
import { Unstable_DevWorker } from "wrangler";

describe("Aster Cloudflare Backend - Idempotency & Duplicate Rejection", () => {
  let worker: Unstable_DevWorker;

  beforeAll(async () => {
    worker = await getTestWorker();
  });

  afterAll(async () => {
    await stopTestWorker();
  });

  it("Duplicate SEND_MESSAGE returns MESSAGE_ACCEPTED idempotently without duplicate delivery", async () => {
    const userA = await provisionFreshUser(worker, "user_idemp_a");
    const userB = await provisionFreshUser(worker, "user_idemp_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);

    await clientA.connect();
    await clientB.connect();

    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    const messageId = `dup_msg_${Date.now()}`;
    const timestamp = Date.now();

    // 1st SEND_MESSAGE
    clientA.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Important unique instruction",
      timestamp,
    });

    const accepted1 = await clientA.waitForMessage(
      (m) => m.type === "MESSAGE_ACCEPTED" && m.messageId === messageId
    );
    expect(accepted1.messageId).toBe(messageId);

    const received1 = await clientB.waitForMessage(
      (m) => m.type === "MESSAGE" && m.messageId === messageId
    );
    expect(received1.messageId).toBe(messageId);

    // 2nd duplicate SEND_MESSAGE with exact same messageId (simulating network retry)
    clientA.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Important unique instruction",
      timestamp,
    });

    // Sender MUST receive MESSAGE_ACCEPTED again (idempotent ACK)
    const accepted2 = await clientA.waitForMessage(
      (m) => m.type === "MESSAGE_ACCEPTED" && m.messageId === messageId
    );
    expect(accepted2.messageId).toBe(messageId);

    // Wait to ensure recipient does NOT receive a duplicate MESSAGE frame
    await new Promise((r) => setTimeout(r, 400));
    const recipientMessages = clientB.messages.filter(
      (m) => m.type === "MESSAGE" && m.messageId === messageId
    );
    expect(recipientMessages.length).toBe(1);

    clientA.close();
    clientB.close();
  });

  it("Duplicate ACK_RECEIVED is handled idempotently without error", async () => {
    const userA = await provisionFreshUser(worker, "user_ack_a");
    const userB = await provisionFreshUser(worker, "user_ack_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);

    await clientA.connect();
    await clientB.connect();

    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    const messageId = `ack_dup_${Date.now()}`;
    clientA.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "To be acked multiple times",
      timestamp: Date.now(),
    });

    await clientA.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");
    await clientB.waitForMessage((m) => m.type === "MESSAGE");

    // 1st ACK
    clientB.send({
      type: "ACK_RECEIVED",
      messageId,
    });

    // 2nd duplicate ACK
    clientB.send({
      type: "ACK_RECEIVED",
      messageId,
    });

    // 3rd duplicate ACK
    clientB.send({
      type: "ACK_RECEIVED",
      messageId,
    });

    // Wait to confirm connection remains healthy and no ERROR frame is returned
    await new Promise((r) => setTimeout(r, 300));
    const errors = clientB.messages.filter((m) => m.type === "ERROR");
    expect(errors.length).toBe(0);

    clientA.close();
    clientB.close();
  });

  it("Retry after client disconnect before receiving MESSAGE_ACCEPTED", async () => {
    const userA = await provisionFreshUser(worker, "user_retry_a");
    const userB = await provisionFreshUser(worker, "user_retry_b");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientB = new TestWsClient(wsUrl);
    await clientB.connect();
    await clientB.sendAndAwaitAuth(userB.token);

    const messageId = `disconnect_retry_${Date.now()}`;

    // Client A connects, sends, immediately drops without reading
    const clientA1 = new TestWsClient(wsUrl);
    await clientA1.connect();
    await clientA1.sendAndAwaitAuth(userA.token);

    clientA1.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Message before disconnect",
      timestamp: Date.now(),
    });
    clientA1.close();

    // Client B received it
    const bRecv1 = await clientB.waitForMessage(
      (m) => m.type === "MESSAGE" && m.messageId === messageId
    );
    expect(bRecv1.messageId).toBe(messageId);

    // Client A reconnects and retries sending the same messageId
    const clientA2 = new TestWsClient(wsUrl);
    await clientA2.connect();
    await clientA2.sendAndAwaitAuth(userA.token);

    clientA2.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Message before disconnect",
      timestamp: Date.now(),
    });

    // Client A gets MESSAGE_ACCEPTED
    const accepted = await clientA2.waitForMessage(
      (m) => m.type === "MESSAGE_ACCEPTED" && m.messageId === messageId
    );
    expect(accepted.messageId).toBe(messageId);

    // Client B must still have only 1 copy of the message
    await new Promise((r) => setTimeout(r, 300));
    const bCopies = clientB.messages.filter(
      (m) => m.type === "MESSAGE" && m.messageId === messageId
    );
    expect(bCopies.length).toBe(1);

    clientA2.close();
    clientB.close();
  });
});
