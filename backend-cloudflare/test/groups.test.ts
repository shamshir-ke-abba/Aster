import { describe, it, expect, beforeAll, afterAll } from "vitest";
import {
  getTestWorker,
  stopTestWorker,
  provisionFreshUser,
  TestWsClient,
} from "./helpers/test-client";
import { Unstable_DevWorker } from "wrangler";

describe("Aster Cloudflare Backend - Group Messaging & Management", () => {
  let worker: Unstable_DevWorker;

  beforeAll(async () => {
    worker = await getTestWorker();
  });

  afterAll(async () => {
    await stopTestWorker();
  });

  it("Creates a group, lists user's groups, and inspects members via WebSocket frames", async () => {
    const creator = await provisionFreshUser(worker, "user_creator");
    const member1 = await provisionFreshUser(worker, "user_mem1");
    const member2 = await provisionFreshUser(worker, "user_mem2");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientCreator = new TestWsClient(wsUrl);
    await clientCreator.connect();
    await clientCreator.sendAndAwaitAuth(creator.token);

    // 1. Creator creates group
    const reqCreate = `req_create_${Date.now()}`;
    clientCreator.send({
      type: "CREATE_GROUP",
      requestId: reqCreate,
      name: "Test DO Group",
      memberIds: [member1.userId, member2.userId],
    });

    const createOk = await clientCreator.waitForMessage(
      (m) => m.type === "GROUP_OPERATION_OK" && m.requestId === reqCreate
    );
    expect(createOk.action).toBe("CREATE_GROUP");
    expect(createOk.groupId).toMatch(/^group_/);
    expect(createOk.name).toBe("Test DO Group");
    const groupId = createOk.groupId;

    // 2. Creator lists groups
    const reqList = `req_list_${Date.now()}`;
    clientCreator.send({
      type: "GET_GROUPS",
      requestId: reqList,
    });

    const groupList = await clientCreator.waitForMessage(
      (m) => m.type === "GROUP_LIST" && m.requestId === reqList
    );
    expect(Array.isArray(groupList.groups)).toBe(true);
    const found = groupList.groups.find((g: any) => g.id === groupId);
    expect(found).toBeDefined();
    expect(found.name).toBe("Test DO Group");
    expect(found.role).toBe("CREATOR");

    // 3. Member 1 connects and inspects group members
    const clientMem1 = new TestWsClient(wsUrl);
    await clientMem1.connect();
    await clientMem1.sendAndAwaitAuth(member1.token);

    const reqMembers = `req_mem_${Date.now()}`;
    clientMem1.send({
      type: "GET_GROUP_MEMBERS",
      requestId: reqMembers,
      groupId,
    });

    const membersList = await clientMem1.waitForMessage(
      (m) => m.type === "GROUP_MEMBERS_LIST" && m.requestId === reqMembers
    );
    expect(membersList.groupId).toBe(groupId);
    expect(membersList.members.length).toBe(3);
    const creatorRole = membersList.members.find((m: any) => m.userId === creator.userId);
    expect(creatorRole.role).toBe("CREATOR");

    clientCreator.close();
    clientMem1.close();
  });

  it("Group member addition, role verification, and group renaming", async () => {
    const creator = await provisionFreshUser(worker, "user_grp_admin");
    const memberA = await provisionFreshUser(worker, "user_grp_a");
    const newMember = await provisionFreshUser(worker, "user_grp_new");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientCreator = new TestWsClient(wsUrl);
    await clientCreator.connect();
    await clientCreator.sendAndAwaitAuth(creator.token);

    // 1. Create group
    const reqCreate = `req_create_${Date.now()}`;
    clientCreator.send({
      type: "CREATE_GROUP",
      requestId: reqCreate,
      name: "Alpha Team",
      memberIds: [memberA.userId],
    });
    const createOk = await clientCreator.waitForMessage((m) => m.type === "GROUP_OPERATION_OK");
    const groupId = createOk.groupId;

    // 2. Add new member
    const reqAdd = `req_add_${Date.now()}`;
    clientCreator.send({
      type: "ADD_GROUP_MEMBER",
      requestId: reqAdd,
      groupId,
      userId: newMember.userId,
    });
    const addOk = await clientCreator.waitForMessage(
      (m) => m.type === "GROUP_OPERATION_OK" && m.requestId === reqAdd
    );
    expect(addOk.action).toBe("ADD_MEMBER");

    // 3. Rename group
    const reqRename = `req_rename_${Date.now()}`;
    clientCreator.send({
      type: "RENAME_GROUP",
      requestId: reqRename,
      groupId,
      newName: "Alpha Squad",
    });
    const renameOk = await clientCreator.waitForMessage(
      (m) => m.type === "GROUP_OPERATION_OK" && m.requestId === reqRename
    );
    expect(renameOk.action).toBe("RENAME_GROUP");
    expect(renameOk.name).toBe("Alpha Squad");

    // 4. Verify new name and 3 members via GET_GROUP_MEMBERS
    const reqVerify = `req_verify_${Date.now()}`;
    clientCreator.send({
      type: "GET_GROUP_MEMBERS",
      requestId: reqVerify,
      groupId,
    });
    const membersList = await clientCreator.waitForMessage(
      (m) => m.type === "GROUP_MEMBERS_LIST" && m.requestId === reqVerify
    );
    expect(membersList.members.length).toBe(3);

    clientCreator.close();
  });

  it("Real-time group chat fan-out to online members", async () => {
    const creator = await provisionFreshUser(worker, "user_fan_creator");
    const mem1 = await provisionFreshUser(worker, "user_fan_1");
    const mem2 = await provisionFreshUser(worker, "user_fan_2");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientCreator = new TestWsClient(wsUrl);
    const clientMem1 = new TestWsClient(wsUrl);
    const clientMem2 = new TestWsClient(wsUrl);

    await clientCreator.connect();
    await clientMem1.connect();
    await clientMem2.connect();

    await clientCreator.sendAndAwaitAuth(creator.token);
    await clientMem1.sendAndAwaitAuth(mem1.token);
    await clientMem2.sendAndAwaitAuth(mem2.token);

    // Create group
    const reqCreate = `req_create_${Date.now()}`;
    clientCreator.send({
      type: "CREATE_GROUP",
      requestId: reqCreate,
      name: "Fanout Group",
      memberIds: [mem1.userId, mem2.userId],
    });
    const createOk = await clientCreator.waitForMessage((m) => m.type === "GROUP_OPERATION_OK");
    const groupId = createOk.groupId;

    const messageId = `grp_fanout_${Date.now()}`;
    const timestamp = Date.now();

    // Creator sends group message
    clientCreator.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId: groupId,
      body: "Hello squad from Cloudflare DO!",
      timestamp,
    });

    // Creator receives MESSAGE_ACCEPTED
    const accepted = await clientCreator.waitForMessage(
      (m) => m.type === "MESSAGE_ACCEPTED" && m.messageId === messageId
    );
    expect(accepted.messageId).toBe(messageId);

    // Both online members receive the group message
    const recv1 = await clientMem1.waitForMessage(
      (m) => m.type === "MESSAGE" && m.messageId === messageId
    );
    expect(recv1.conversationId).toBe(groupId);
    expect(recv1.senderId).toBe(creator.userId);
    expect(recv1.body).toBe("Hello squad from Cloudflare DO!");

    const recv2 = await clientMem2.waitForMessage(
      (m) => m.type === "MESSAGE" && m.messageId === messageId
    );
    expect(recv2.conversationId).toBe(groupId);
    expect(recv2.senderId).toBe(creator.userId);
    expect(recv2.body).toBe("Hello squad from Cloudflare DO!");

    // Both ACK
    clientMem1.send({ type: "ACK_RECEIVED", messageId });
    clientMem2.send({ type: "ACK_RECEIVED", messageId });

    clientCreator.close();
    clientMem1.close();
    clientMem2.close();
  });

  it("Offline group messaging queues in mailbox and replays on reconnect", async () => {
    const creator = await provisionFreshUser(worker, "user_grp_off_c");
    const onlineMem = await provisionFreshUser(worker, "user_grp_on_m");
    const offlineMem = await provisionFreshUser(worker, "user_grp_off_m");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    // Creator connects and creates group with offline member
    const clientCreator = new TestWsClient(wsUrl);
    await clientCreator.connect();
    await clientCreator.sendAndAwaitAuth(creator.token);

    const reqCreate = `req_create_${Date.now()}`;
    clientCreator.send({
      type: "CREATE_GROUP",
      requestId: reqCreate,
      name: "Offline Queue Test",
      memberIds: [onlineMem.userId, offlineMem.userId],
    });
    const createOk = await clientCreator.waitForMessage((m) => m.type === "GROUP_OPERATION_OK");
    const groupId = createOk.groupId;

    // Online member connects
    const clientOnline = new TestWsClient(wsUrl);
    await clientOnline.connect();
    await clientOnline.sendAndAwaitAuth(onlineMem.token);

    const messageId = `grp_offline_${Date.now()}`;

    // Creator sends message to group (offlineMem is not connected)
    clientCreator.send({
      type: "SEND_MESSAGE",
      messageId,
      conversationId: groupId,
      body: "Important message while offlineMem is away",
      timestamp: Date.now(),
    });

    await clientCreator.waitForMessage(
      (m) => m.type === "MESSAGE_ACCEPTED" && m.messageId === messageId
    );

    // Online member receives it immediately
    const onRecv = await clientOnline.waitForMessage(
      (m) => m.type === "MESSAGE" && m.messageId === messageId
    );
    expect(onRecv.messageId).toBe(messageId);

    clientCreator.close();
    clientOnline.close();

    // Now offlineMem connects for the first time
    const clientOffline = new TestWsClient(wsUrl);
    await clientOffline.connect();
    await clientOffline.sendAndAwaitAuth(offlineMem.token);

    // offlineMem receives the queued group message
    const offRecv = await clientOffline.waitForMessage(
      (m) => m.type === "MESSAGE" && m.messageId === messageId
    );
    expect(offRecv.messageId).toBe(messageId);
    expect(offRecv.conversationId).toBe(groupId);
    expect(offRecv.senderId).toBe(creator.userId);

    // offlineMem ACKs
    clientOffline.send({ type: "ACK_RECEIVED", messageId });
    await new Promise((r) => setTimeout(r, 100));
    clientOffline.close();
  });

  it("Non-members are rejected with UNAUTHORIZED_GROUP_ACCESS and leave endpoint works", async () => {
    const creator = await provisionFreshUser(worker, "user_grp_rej_c");
    const member = await provisionFreshUser(worker, "user_grp_rej_m");
    const nonMember = await provisionFreshUser(worker, "user_grp_rej_outsider");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientCreator = new TestWsClient(wsUrl);
    await clientCreator.connect();
    await clientCreator.sendAndAwaitAuth(creator.token);

    const reqCreate = `req_create_${Date.now()}`;
    clientCreator.send({
      type: "CREATE_GROUP",
      requestId: reqCreate,
      name: "Private Group",
      memberIds: [member.userId],
    });
    const createOk = await clientCreator.waitForMessage((m) => m.type === "GROUP_OPERATION_OK");
    const groupId = createOk.groupId;

    // Non-member attempts to send message to group
    const outsiderWs = new TestWsClient(wsUrl);
    await outsiderWs.connect();
    await outsiderWs.sendAndAwaitAuth(nonMember.token);

    outsiderWs.send({
      type: "SEND_MESSAGE",
      messageId: `msg_unauth_${Date.now()}`,
      conversationId: groupId,
      body: "I am trying to breach this group",
      timestamp: Date.now(),
    });

    const err = await outsiderWs.waitForMessage((m) => m.type === "ERROR");
    expect(err.code).toBe("UNAUTHORIZED_GROUP_ACCESS");
    outsiderWs.close();

    // Member connects and leaves group
    const clientMember = new TestWsClient(wsUrl);
    await clientMember.connect();
    await clientMember.sendAndAwaitAuth(member.token);

    const reqLeave = `req_leave_${Date.now()}`;
    clientMember.send({
      type: "LEAVE_GROUP",
      requestId: reqLeave,
      groupId,
    });
    const leaveOk = await clientMember.waitForMessage(
      (m) => m.type === "GROUP_OPERATION_OK" && m.requestId === reqLeave
    );
    expect(leaveOk.action).toBe("LEAVE_GROUP");

    // Member attempts to send message now -> rejected
    clientMember.send({
      type: "SEND_MESSAGE",
      messageId: `msg_after_leave_${Date.now()}`,
      conversationId: groupId,
      body: "I shouldn't be allowed to send anymore",
      timestamp: Date.now(),
    });
    const leaveErr = await clientMember.waitForMessage((m) => m.type === "ERROR");
    expect(leaveErr.code).toBe("UNAUTHORIZED_GROUP_ACCESS");

    clientCreator.close();
    clientMember.close();
  });

  it("Enforces group administration permissions for member removal and admin succession", async () => {
    const creator = await provisionFreshUser(worker, "user_adm_creator");
    const member1 = await provisionFreshUser(worker, "user_adm_mem1");
    const member2 = await provisionFreshUser(worker, "user_adm_mem2");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const clientCreator = new TestWsClient(wsUrl);
    const clientMem1 = new TestWsClient(wsUrl);
    const clientMem2 = new TestWsClient(wsUrl);

    await clientCreator.connect();
    await clientMem1.connect();
    await clientMem2.connect();

    await clientCreator.sendAndAwaitAuth(creator.token);
    await clientMem1.sendAndAwaitAuth(member1.token);
    await clientMem2.sendAndAwaitAuth(member2.token);

    // 1. Creator creates group with member1 and member2
    const reqCreate = `req_create_${Date.now()}`;
    clientCreator.send({
      type: "CREATE_GROUP",
      requestId: reqCreate,
      name: "Governance Group",
      memberIds: [member1.userId, member2.userId],
    });
    const createOk = await clientCreator.waitForMessage((m) => m.type === "GROUP_OPERATION_OK");
    const groupId = createOk.groupId;

    // 2. Member1 (non-admin) tries to remove Member2 -> MUST BE REJECTED with PERMISSION_DENIED
    const reqUnauthRemove = `req_rem_fail_${Date.now()}`;
    clientMem1.send({
      type: "REMOVE_GROUP_MEMBER",
      requestId: reqUnauthRemove,
      groupId,
      userId: member2.userId,
    });
    const unauthErr = await clientMem1.waitForMessage(
      (m) => m.type === "ERROR" && m.requestId === reqUnauthRemove
    );
    expect(unauthErr.code).toBe("PERMISSION_DENIED");

    // 3. Creator (admin) removes member2 -> SUCCESS
    const reqAuthRemove = `req_rem_ok_${Date.now()}`;
    clientCreator.send({
      type: "REMOVE_GROUP_MEMBER",
      requestId: reqAuthRemove,
      groupId,
      userId: member2.userId,
    });
    const removeOk = await clientCreator.waitForMessage(
      (m) => m.type === "GROUP_OPERATION_OK" && m.requestId === reqAuthRemove
    );
    expect(removeOk.action).toBe("REMOVE_MEMBER");

    // Member2 receives real-time MEMBER_REMOVED event
    const member2Event = await clientMem2.waitForMessage(
      (m) => m.type === "GROUP_EVENT" && m.eventType === "MEMBER_REMOVED" && m.targetUserId === member2.userId
    );
    expect(member2Event.groupId).toBe(groupId);

    // Member2 attempts to send message -> rejected
    clientMem2.send({
      type: "SEND_MESSAGE",
      messageId: `msg_removed_${Date.now()}`,
      conversationId: groupId,
      body: "Am I still here?",
      timestamp: Date.now(),
    });
    const sendErr = await clientMem2.waitForMessage((m) => m.type === "ERROR");
    expect(sendErr.code).toBe("UNAUTHORIZED_GROUP_ACCESS");

    // 4. Admin succession: Creator leaves group, leaving member1 as sole member -> promoted to ADMIN
    const reqCreatorLeave = `req_leave_creator_${Date.now()}`;
    clientCreator.send({
      type: "LEAVE_GROUP",
      requestId: reqCreatorLeave,
      groupId,
    });
    await clientCreator.waitForMessage(
      (m) => m.type === "GROUP_OPERATION_OK" && m.requestId === reqCreatorLeave
    );

    // Query members list via Member1
    const reqCheckMembers = `req_check_m_${Date.now()}`;
    clientMem1.send({
      type: "GET_GROUP_MEMBERS",
      requestId: reqCheckMembers,
      groupId,
    });
    const memList = await clientMem1.waitForMessage(
      (m) => m.type === "GROUP_MEMBERS_LIST" && m.requestId === reqCheckMembers
    );
    expect(memList.members.length).toBe(1);
    expect(memList.members[0].userId).toBe(member1.userId);
    expect(memList.members[0].role).toBe("ADMIN");

    clientCreator.close();
    clientMem1.close();
    clientMem2.close();
  });
});
