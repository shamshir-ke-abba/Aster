import { describe, it, expect, beforeAll, afterAll } from "vitest";
import {
  getTestWorker,
  stopTestWorker,
  provisionFreshUser,
  TestWsClient,
} from "./helpers/test-client";
import { Unstable_DevWorker } from "wrangler";

describe("Aster Cloudflare Backend - Direct Messaging & Social", () => {
  let worker: Unstable_DevWorker;

  beforeAll(async () => {
    worker = await getTestWorker();
  });

  afterAll(async () => {
    await stopTestWorker();
  });

  it("Real-time direct messaging between online users (A -> B)", async () => {
    const userA = await provisionFreshUser(worker, "user_saad");
    const userB = await provisionFreshUser(worker, "user_alex");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);

    await clientA.connect();
    await clientB.connect();

    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    const messageId = `msg_${Date.now()}`;
    const timestamp = Date.now();

    // A sends message to B
    clientA.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Hello Alex from Cloudflare DO!",
      timestamp,
    });

    // A should receive MESSAGE_ACCEPTED
    const accepted = await clientA.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");
    expect(accepted.messageId).toBe(messageId);

    // B should receive MESSAGE
    const received = await clientB.waitForMessage((m) => m.type === "MESSAGE");
    expect(received.messageId).toBe(messageId);
    expect(received.conversationId).toBe(`conv_${userB.userId}`);
    expect(received.senderId).toBe(userA.userId);
    expect(received.body).toBe("Hello Alex from Cloudflare DO!");
    expect(received.timestamp).toBe(timestamp);

    // B ACKs receipt
    clientB.send({
      type: "ACK_RECEIVED",
      messageId,
    });

    clientA.close();
    clientB.close();
  });

  it("Bidirectional reply messaging (B -> A)", async () => {
    const userA = await provisionFreshUser(worker, "user_saad");
    const userB = await provisionFreshUser(worker, "user_alex");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);

    await clientA.connect();
    await clientB.connect();

    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    const replyId = `reply_${Date.now()}`;
    const timestamp = Date.now();

    // B sends reply to A
    clientB.send({
      type: "SEND_MESSAGE",
      messageId: replyId,
      conversationId: `conv_${userA.userId}`,
      recipientId: userA.userId,
      body: "Got your message loud and clear!",
      timestamp,
    });

    const accepted = await clientB.waitForMessage((m) => m.type === "MESSAGE_ACCEPTED");
    expect(accepted.messageId).toBe(replyId);

    const received = await clientA.waitForMessage((m) => m.type === "MESSAGE");
    expect(received.messageId).toBe(replyId);
    expect(received.senderId).toBe(userB.userId);
    expect(received.body).toBe("Got your message loud and clear!");

    clientA.close();
    clientB.close();
  });

  it("User profile management (/api/profile)", async () => {
    const user = await provisionFreshUser(worker, "user_rohan");

    // GET /api/profile
    const getRes = await worker.fetch("/api/profile", {
      headers: { Authorization: `Bearer ${user.token}` },
    });
    expect(getRes.status).toBe(200);
    const profile = (await getRes.json()) as any;
    expect(profile.userId).toBe(user.userId);
    expect(profile.displayName).toBe(user.userId);

    // POST /api/profile (update displayName)
    const postRes = await worker.fetch("/api/profile", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${user.token}`,
      },
      body: JSON.stringify({ displayName: "Rohan Updated" }),
    });
    expect(postRes.status).toBe(200);
    const updated = (await postRes.json()) as any;
    expect(updated.displayName).toBe("Rohan Updated");
  });

  it("User search (/api/users/search)", async () => {
    const user = await provisionFreshUser(worker, "user_searcher");
    const target = await provisionFreshUser(worker, `user_srch_${Date.now()}`);

    const searchRes = await worker.fetch(`/api/users/search?q=${target.userId.slice(0, 10)}`, {
      headers: { Authorization: `Bearer ${user.token}` },
    });
    expect(searchRes.status).toBe(200);
    const searchData = (await searchRes.json()) as any;
    expect(Array.isArray(searchData.users)).toBe(true);
    const found = searchData.users.find((u: any) => u.userId === target.userId);
    expect(found).toBeDefined();
  });

  it("Friend requests, acceptance, and friend listing", async () => {
    const userSaad = await provisionFreshUser(worker, "user_saad");
    const userPriya = await provisionFreshUser(worker, "user_priya");

    // Saad sends friend request to Priya
    const reqRes = await worker.fetch("/api/friends/request", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userSaad.token}`,
      },
      body: JSON.stringify({ targetUserId: userPriya.userId }),
    });
    expect(reqRes.status).toBe(201);
    const reqData = (await reqRes.json()) as any;
    expect(reqData.status).toBe("PENDING");
    expect(reqData.senderUserId).toBe(userSaad.userId);
    expect(reqData.recipientUserId).toBe(userPriya.userId);

    // Priya views incoming requests
    const incRes = await worker.fetch("/api/friends/requests", {
      headers: { Authorization: `Bearer ${userPriya.token}` },
    });
    expect(incRes.status).toBe(200);
    const incData = (await incRes.json()) as any;
    const incomingReq = incData.incoming.find((r: any) => r.id === reqData.id);
    expect(incomingReq).toBeDefined();

    // Priya accepts request
    const respondRes = await worker.fetch("/api/friends/respond", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userPriya.token}`,
      },
      body: JSON.stringify({ requestId: reqData.id, action: "ACCEPT" }),
    });
    expect(respondRes.status).toBe(200);

    // Both now see each other in /api/friends
    const friendsRes = await worker.fetch("/api/friends", {
      headers: { Authorization: `Bearer ${userSaad.token}` },
    });
    expect(friendsRes.status).toBe(200);
    const friendsData = (await friendsRes.json()) as any;
    const isFriend = friendsData.friends.some((f: any) => f.userId === userPriya.userId);
    expect(isFriend).toBe(true);
  });

  it("User blocking and unblocking", async () => {
    const userSaad = await provisionFreshUser(worker, "user_saad");
    const userRohan = await provisionFreshUser(worker, "user_rohan");

    // Saad blocks Rohan
    const blockRes = await worker.fetch("/api/friends/block", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userSaad.token}`,
      },
      body: JSON.stringify({ targetUserId: userRohan.userId }),
    });
    expect(blockRes.status).toBe(200);

    // Saad checks blocked list
    const blockedRes = await worker.fetch("/api/friends/blocked", {
      headers: { Authorization: `Bearer ${userSaad.token}` },
    });
    expect(blockedRes.status).toBe(200);
    const blockedData = (await blockedRes.json()) as any;
    expect(blockedData.blocked.some((u: any) => u.userId === userRohan.userId)).toBe(true);

    // Rohan attempts to send message to Saad -> rejected with BLOCKED error
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;
    const clientRohan = new TestWsClient(wsUrl);
    await clientRohan.connect();
    await clientRohan.sendAndAwaitAuth(userRohan.token);

    clientRohan.send({
      type: "SEND_MESSAGE",
      messageId: `msg_blocked_${Date.now()}`,
      conversationId: `conv_${userSaad.userId}`,
      recipientId: userSaad.userId,
      body: "Hey Saad!",
      timestamp: Date.now(),
    });

    const err = await clientRohan.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("BLOCKED");

    clientRohan.close();

    // Saad unblocks Rohan
    const unblockRes = await worker.fetch("/api/friends/unblock", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${userSaad.token}`,
      },
      body: JSON.stringify({ targetUserId: userRohan.userId }),
    });
    expect(unblockRes.status).toBe(200);
  });

  it("Cross-device reply and celestial effect synchronization (A -> B)", async () => {
    const userA = await provisionFreshUser(worker, "user_saad");
    const userB = await provisionFreshUser(worker, "user_alex");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);

    await clientA.connect();
    await clientB.connect();

    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    const parentMsgId = `msg_parent_${Date.now()}`;
    const replyMsgId = `msg_reply_${Date.now()}`;

    // A sends parent message
    clientA.send({
      type: "SEND_MESSAGE",
      messageId: parentMsgId,
      conversationId: `conv_${userB.userId}`,
      recipientId: userB.userId,
      body: "Initial message to be replied to",
      timestamp: Date.now(),
    });
    await clientB.waitForMessage((m) => m.type === "MESSAGE" && m.messageId === parentMsgId);

    // B sends reply to parent message with celestial effect 'nebula'
    clientB.send({
      type: "SEND_MESSAGE",
      messageId: replyMsgId,
      conversationId: `conv_${userA.userId}`,
      recipientId: userA.userId,
      body: "Quoting you with a nebula effect!",
      replyToId: parentMsgId,
      effect: "nebula",
      timestamp: Date.now(),
    });

    // A receives message with replyToId and effect intact
    const receivedA = await clientA.waitForMessage(
      (m) => m.type === "MESSAGE" && m.messageId === replyMsgId
    );
    expect(receivedA.replyToId).toBe(parentMsgId);
    expect(receivedA.effect).toBe("nebula");
    expect(receivedA.body).toBe("Quoting you with a nebula effect!");

    clientA.close();
    clientB.close();
  });

  it("Cross-device reaction synchronization and multi-user counts", async () => {
    const userA = await provisionFreshUser(worker, "user_saad");
    const userB = await provisionFreshUser(worker, "user_alex");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientA = new TestWsClient(wsUrl);
    const clientB = new TestWsClient(wsUrl);

    await clientA.connect();
    await clientB.connect();

    await clientA.sendAndAwaitAuth(userA.token);
    await clientB.sendAndAwaitAuth(userB.token);

    const messageId = `msg_react_${Date.now()}`;
    const conversationId = `conv_${userB.userId}`;

    // A sends message to B
    clientA.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId,
      recipientId: userB.userId,
      body: "React to this message!",
      timestamp: Date.now(),
    });
    await clientB.waitForMessage((m) => m.type === "MESSAGE" && m.messageId === messageId);

    // 1. A adds reaction "❤️"
    clientA.send({
      type: "TOGGLE_REACTION",
      messageId,
      conversationId,
      emoji: "❤️",
    });

    // Both A and B receive REACTION_UPDATE
    const update1A = await clientA.waitForMessage(
      (m) => m.type === "REACTION_UPDATE" && m.messageId === messageId
    );
    const update1B = await clientB.waitForMessage(
      (m) => m.type === "REACTION_UPDATE" && m.messageId === messageId
    );
    expect(update1A.reactions["❤️"]).toEqual([userA.userId]);
    expect(update1B.reactions["❤️"]).toEqual([userA.userId]);

    // 2. B also reacts "❤️" -> multi-user count 2
    clientB.send({
      type: "TOGGLE_REACTION",
      messageId,
      conversationId: `conv_${userA.userId}`,
      emoji: "❤️",
    });

    const update2A = await clientA.waitForMessage(
      (m) => m.type === "REACTION_UPDATE" && m.messageId === messageId && m.reactions["❤️"]?.length === 2
    );
    const update2B = await clientB.waitForMessage(
      (m) => m.type === "REACTION_UPDATE" && m.messageId === messageId && m.reactions["❤️"]?.length === 2
    );
    expect(update2A.reactions["❤️"]).toContain(userA.userId);
    expect(update2A.reactions["❤️"]).toContain(userB.userId);
    expect(update2B.reactions["❤️"]).toContain(userA.userId);
    expect(update2B.reactions["❤️"]).toContain(userB.userId);

    // 3. A removes "❤️" -> count drops to 1 with only userB
    clientA.send({
      type: "TOGGLE_REACTION",
      messageId,
      conversationId,
      emoji: "❤️",
    });

    const update3A = await clientA.waitForMessage(
      (m) => m.type === "REACTION_UPDATE" && m.messageId === messageId && m.action === "REMOVED"
    );
    const update3B = await clientB.waitForMessage(
      (m) => m.type === "REACTION_UPDATE" && m.messageId === messageId && m.action === "REMOVED"
    );
    expect(update3A.reactions["❤️"]).toEqual([userB.userId]);
    expect(update3B.reactions["❤️"]).toEqual([userB.userId]);

    clientA.close();
    clientB.close();
  });
});
