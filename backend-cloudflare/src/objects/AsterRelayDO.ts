import { DurableObject } from "cloudflare:workers";
import {
  AuthTokenRecord,
  ClientMessage,
  DeliveryState,
  Env,
  FriendRequestSummary,
  GroupMemberSummary,
  GroupSummary,
  MailboxMessageRecord,
  ProtocolLimits,
  ServerMessage,
  UserRecord,
  UserSummary,
} from "../protocol/types";
import { parseAndValidateFrame } from "../protocol/validation";
import {
  generateAsterId,
  generateSecureToken,
  hashToken,
  hashPassword,
  verifyPassword,
  generateRecoveryCode,
  hashRecoveryCode,
  verifyRecoveryCode,
  constantTimeCompare,
} from "../utils/crypto";

interface SocketAttachment {
  userId?: string;
  deviceId?: string;
}

export class AsterRelayDO extends DurableObject<Env> {
  private firstRow(query: string, ...params: any[]): any {
    const rows = this.ctx.storage.sql.exec(query, ...params).toArray();
    return rows.length > 0 ? rows[0] : null;
  }
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.initDatabase();
  }

  private initDatabase(): void {
    this.ctx.storage.sql.exec(`
      PRAGMA foreign_keys = ON;

      CREATE TABLE IF NOT EXISTS auth_tokens (
          token_hash TEXT PRIMARY KEY NOT NULL,
          user_id TEXT NOT NULL,
          device_id TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          revoked_at INTEGER
      );
      CREATE INDEX IF NOT EXISTS idx_auth_tokens_user ON auth_tokens (user_id);

      CREATE TABLE IF NOT EXISTS mailbox_messages (
          message_id TEXT NOT NULL,
          conversation_id TEXT NOT NULL,
          sender_user_id TEXT NOT NULL,
          recipient_user_id TEXT NOT NULL,
          body TEXT NOT NULL,
          client_timestamp INTEGER NOT NULL,
          server_received_timestamp INTEGER NOT NULL,
          delivery_state TEXT NOT NULL,
          reply_to_id TEXT,
          effect TEXT,
          PRIMARY KEY (message_id, recipient_user_id)
      );
      CREATE INDEX IF NOT EXISTS idx_mailbox_recipient_state ON mailbox_messages (recipient_user_id, delivery_state);
      CREATE INDEX IF NOT EXISTS idx_mailbox_order ON mailbox_messages (server_received_timestamp, message_id);

      CREATE TABLE IF NOT EXISTS message_reactions (
          message_id TEXT NOT NULL,
          conversation_id TEXT NOT NULL,
          user_id TEXT NOT NULL,
          emoji TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          PRIMARY KEY (message_id, user_id, emoji)
      );
      CREATE INDEX IF NOT EXISTS idx_reactions_msg ON message_reactions (message_id);
      CREATE INDEX IF NOT EXISTS idx_reactions_conv ON message_reactions (conversation_id);

      CREATE TABLE IF NOT EXISTS mailbox_reactions (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          message_id TEXT NOT NULL,
          conversation_id TEXT NOT NULL,
          user_id TEXT NOT NULL,
          emoji TEXT NOT NULL,
          action TEXT NOT NULL,
          recipient_user_id TEXT NOT NULL,
          timestamp INTEGER NOT NULL,
          delivery_state TEXT NOT NULL DEFAULT 'QUEUED'
      );
      CREATE INDEX IF NOT EXISTS idx_mb_reactions_rec ON mailbox_reactions (recipient_user_id, delivery_state);

      CREATE TABLE IF NOT EXISTS users (
          user_id TEXT PRIMARY KEY NOT NULL,
          aster_id TEXT UNIQUE NOT NULL,
          username TEXT UNIQUE NOT NULL,
          display_name TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          is_active INTEGER NOT NULL DEFAULT 1,
          password_hash TEXT,
          password_salt TEXT,
          recovery_hash TEXT,
          recovery_salt TEXT,
          failed_login_attempts INTEGER NOT NULL DEFAULT 0,
          last_failed_login INTEGER NOT NULL DEFAULT 0,
          failed_recovery_attempts INTEGER NOT NULL DEFAULT 0,
          last_failed_recovery INTEGER NOT NULL DEFAULT 0,
          last_seen INTEGER NOT NULL DEFAULT 0,
          is_deleted INTEGER NOT NULL DEFAULT 0
      );
      CREATE INDEX IF NOT EXISTS idx_users_aster_id ON users (aster_id);
      CREATE INDEX IF NOT EXISTS idx_users_username ON users (username);

      CREATE TABLE IF NOT EXISTS friend_requests (
          id TEXT PRIMARY KEY NOT NULL,
          sender_user_id TEXT NOT NULL,
          recipient_user_id TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'PENDING',
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          UNIQUE (sender_user_id, recipient_user_id)
      );
      CREATE INDEX IF NOT EXISTS idx_friend_requests_recipient ON friend_requests (recipient_user_id, status);
      CREATE INDEX IF NOT EXISTS idx_friend_requests_sender ON friend_requests (sender_user_id, status);

      CREATE TABLE IF NOT EXISTS blocked_users (
          blocker_user_id TEXT NOT NULL,
          blocked_user_id TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          PRIMARY KEY (blocker_user_id, blocked_user_id)
      );
      CREATE INDEX IF NOT EXISTS idx_blocked_users_blocker ON blocked_users (blocker_user_id);

      CREATE TABLE IF NOT EXISTS push_tokens (
          user_id TEXT NOT NULL,
          device_id TEXT NOT NULL,
          push_token TEXT NOT NULL,
          endpoint TEXT,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (user_id, device_id)
      );
      CREATE INDEX IF NOT EXISTS idx_push_tokens_user ON push_tokens (user_id);

      CREATE TABLE IF NOT EXISTS user_groups (
          group_id TEXT NOT NULL,
          user_id TEXT NOT NULL,
          PRIMARY KEY (group_id, user_id)
      );
      CREATE INDEX IF NOT EXISTS idx_user_groups_user ON user_groups (user_id);

      CREATE TABLE IF NOT EXISTS message_reads (
          message_id TEXT NOT NULL,
          conversation_id TEXT NOT NULL,
          reader_user_id TEXT NOT NULL,
          read_at INTEGER NOT NULL,
          PRIMARY KEY (message_id, reader_user_id)
      );
      CREATE INDEX IF NOT EXISTS idx_message_reads_conv ON message_reads (conversation_id);

      CREATE TABLE IF NOT EXISTS unsent_messages (
          message_id TEXT PRIMARY KEY NOT NULL,
          conversation_id TEXT NOT NULL,
          sender_user_id TEXT NOT NULL,
          unsent_at INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_unsent_messages_conv ON unsent_messages (conversation_id);
    `);

    // Migrate mailbox_messages and users table columns if upgrading from earlier schema
    try {
      const mbColumns = this.ctx.storage.sql
        .exec("PRAGMA table_info(mailbox_messages)")
        .toArray()
        .map((c: any) => c.name);
      if (!mbColumns.includes("reply_to_id")) {
        this.ctx.storage.sql.exec("ALTER TABLE mailbox_messages ADD COLUMN reply_to_id TEXT");
      }
      if (!mbColumns.includes("effect")) {
        this.ctx.storage.sql.exec("ALTER TABLE mailbox_messages ADD COLUMN effect TEXT");
      }
    } catch {
      // Ignored
    }

    try {
      const userColumns = this.ctx.storage.sql
        .exec("PRAGMA table_info(users)")
        .toArray()
        .map((c: any) => c.name);
      if (!userColumns.includes("password_hash")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN password_hash TEXT");
      }
      if (!userColumns.includes("password_salt")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN password_salt TEXT");
      }
      if (!userColumns.includes("recovery_hash")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN recovery_hash TEXT");
      }
      if (!userColumns.includes("recovery_salt")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN recovery_salt TEXT");
      }
      if (!userColumns.includes("failed_login_attempts")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0");
      }
      if (!userColumns.includes("last_failed_login")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN last_failed_login INTEGER NOT NULL DEFAULT 0");
      }
      if (!userColumns.includes("failed_recovery_attempts")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN failed_recovery_attempts INTEGER NOT NULL DEFAULT 0");
      }
      if (!userColumns.includes("last_failed_recovery")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN last_failed_recovery INTEGER NOT NULL DEFAULT 0");
      }
      if (!userColumns.includes("last_seen")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN last_seen INTEGER NOT NULL DEFAULT 0");
      }
      if (!userColumns.includes("is_deleted")) {
        this.ctx.storage.sql.exec("ALTER TABLE users ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0");
      }
    } catch {
      // Ignored if PRAGMA is not supported or columns exist
    }
  }

  // ---------------------------------------------------------------------------
  // WebSocket Handlers (Hibernation API)
  // ---------------------------------------------------------------------------

  async fetch(request: Request): Promise<Response> {
    const webSocketPair = new WebSocketPair();
    const [client, server] = Object.values(webSocketPair);

    this.ctx.acceptWebSocket(server);

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "MALFORMED_JSON",
        message: "Binary frames are not supported",
      });
      return;
    }

    const { message: clientMsg, error } = parseAndValidateFrame(message);
    if (error) {
      this.sendServerMessage(ws, error);
      return;
    }

    if (!clientMsg) return;

    switch (clientMsg.type) {
      case "AUTH":
        await this.handleAuth(ws, clientMsg);
        break;
      case "PING":
        this.handlePing(ws, clientMsg);
        break;
      case "SEND_MESSAGE":
        await this.handleSendMessage(ws, clientMsg);
        break;
      case "ACK_RECEIVED":
        await this.handleAckReceived(ws, clientMsg);
        break;
      case "CREATE_GROUP":
        await this.handleCreateGroup(ws, clientMsg);
        break;
      case "GET_GROUPS":
        await this.handleGetGroups(ws, clientMsg);
        break;
      case "GET_GROUP_MEMBERS":
        await this.handleGetGroupMembers(ws, clientMsg);
        break;
      case "ADD_GROUP_MEMBER":
        await this.handleAddGroupMember(ws, clientMsg);
        break;
      case "REMOVE_GROUP_MEMBER":
        await this.handleRemoveGroupMember(ws, clientMsg);
        break;
      case "LEAVE_GROUP":
        await this.handleLeaveGroup(ws, clientMsg);
        break;
      case "RENAME_GROUP":
        await this.handleRenameGroup(ws, clientMsg);
        break;
      case "MARK_READ":
        await this.handleMarkRead(ws, clientMsg);
        break;
      case "UNSEND_MESSAGE":
        await this.handleUnsendMessage(ws, clientMsg);
        break;
      case "TOGGLE_REACTION":
        await this.handleToggleReaction(ws, clientMsg);
        break;
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    const attach = this.getSocketAttachment(ws);
    try {
      ws.close(code, reason);
    } catch (_) {
      // Ignored
    }
    if (attach.userId) {
      this.checkAndUpdatePresenceOnDisconnect(attach.userId, ws);
    }
  }

  async webSocketError(ws: WebSocket, error: unknown): Promise<void> {
    const attach = this.getSocketAttachment(ws);
    try {
      ws.close(1006, "WebSocket error");
    } catch (_) {
      // Ignored
    }
    if (attach.userId) {
      this.checkAndUpdatePresenceOnDisconnect(attach.userId, ws);
    }
  }

  findSocketsForUser(userId: string): WebSocket[] {
    const sockets: WebSocket[] = [];
    for (const ws of this.ctx.getWebSockets()) {
      const attach = this.getSocketAttachment(ws);
      if (attach.userId === userId) {
        sockets.push(ws);
      }
    }
    return sockets;
  }

  isUserOnline(userId: string): boolean {
    return this.findSocketsForUser(userId).length > 0;
  }

  checkAndUpdatePresenceOnDisconnect(userId: string, closingWs?: WebSocket): void {
    const remaining = this.findSocketsForUser(userId).filter((s) => s !== closingWs);
    if (remaining.length === 0) {
      const now = Date.now();
      this.ctx.storage.sql.exec(`UPDATE users SET last_seen = ? WHERE user_id = ?`, now, userId);
      this.broadcastPresence(userId, false, now);
    }
  }

  broadcastPresence(userId: string, isOnline: boolean, lastSeen: number, timestamp?: number): void {
    const ts = timestamp || Date.now();
    const msg: ServerMessage = {
      type: "PRESENCE_UPDATE",
      userId,
      isOnline,
      lastSeen,
      timestamp: ts,
    };
    for (const ws of this.ctx.getWebSockets()) {
      this.sendServerMessage(ws, msg);
    }
  }

  broadcastUserDeleted(userId: string): void {
    const msg: ServerMessage = {
      type: "USER_DELETED",
      userId,
    };
    for (const ws of this.ctx.getWebSockets()) {
      this.sendServerMessage(ws, msg);
    }
  }

  private sendServerMessage(ws: WebSocket, msg: ServerMessage): void {
    try {
      ws.send(JSON.stringify(msg));
    } catch (_) {
      // Ignored if socket closed
    }
  }

  private getSocketAttachment(ws: WebSocket): SocketAttachment {
    try {
      return (ws.deserializeAttachment() as SocketAttachment) || {};
    } catch (_) {
      return {};
    }
  }

  private setSocketAttachment(ws: WebSocket, attachment: SocketAttachment): void {
    ws.serializeAttachment(attachment);
  }

  // ---------------------------------------------------------------------------
  // Protocol Handlers
  // ---------------------------------------------------------------------------

  private async handleAuth(ws: WebSocket, auth: ClientMessage & { type: "AUTH" }): Promise<void> {
    const token = auth.token.trim();
    if (!token) {
      this.sendServerMessage(ws, {
        type: "AUTH_ERROR",
        reason: "Missing or blank authentication token",
      });
      return;
    }

    const tokenRecord = await this.validateToken(token);
    if (!tokenRecord) {
      this.sendServerMessage(ws, {
        type: "AUTH_ERROR",
        reason: "Invalid or revoked authentication token",
      });
      return;
    }

    const authenticatedUserId = tokenRecord.user_id;
    const wasOnline = this.isUserOnline(authenticatedUserId);

    // Enforce device-level socket replacement:
    // Only close existing active socket if it comes from the same device_id
    for (const otherWs of this.ctx.getWebSockets()) {
      if (otherWs !== ws) {
        const attach = this.getSocketAttachment(otherWs);
        if (attach.userId === authenticatedUserId && attach.deviceId === tokenRecord.device_id) {
          try {
            otherWs.close(4002, "Replaced by new connection on same device");
          } catch (_) {}
        }
      }
    }

    this.setSocketAttachment(ws, {
      userId: authenticatedUserId,
      deviceId: tokenRecord.device_id,
    });

    this.sendServerMessage(ws, {
      type: "AUTH_OK",
      userId: authenticatedUserId,
    });

    if (!wasOnline) {
      this.broadcastPresence(authenticatedUserId, true, 0);
    }

    // Deliver queued messages from SQLite mailbox
    await this.deliverPendingMailboxMessages(authenticatedUserId, ws);
  }

  private handlePing(ws: WebSocket, _ping: ClientMessage & { type: "PING" }): void {
    this.sendServerMessage(ws, {
      type: "PONG",
      timestamp: Date.now(),
    });
  }

  getReactionsForMessage(messageId: string): Record<string, string[]> {
    try {
      const rows = this.ctx.storage.sql.exec(
        `SELECT user_id, emoji FROM message_reactions WHERE message_id = ? ORDER BY created_at ASC`,
        messageId
      ).toArray() as { user_id: string; emoji: string }[];

      const result: Record<string, string[]> = {};
      for (const row of rows) {
        if (!result[row.emoji]) {
          result[row.emoji] = [];
        }
        if (!result[row.emoji].includes(row.user_id)) {
          result[row.emoji].push(row.user_id);
        }
      }
      return result;
    } catch {
      return {};
    }
  }

  private async deliverPendingMailboxMessages(userId: string, ws: WebSocket): Promise<void> {
    const pending = this.ctx.storage.sql.exec(
      `SELECT message_id, conversation_id, sender_user_id, recipient_user_id,
              body, client_timestamp, server_received_timestamp, delivery_state,
              reply_to_id, effect
       FROM mailbox_messages
       WHERE recipient_user_id = ? AND delivery_state IN ('QUEUED', 'DELIVERED')
       ORDER BY server_received_timestamp ASC, message_id ASC`,
      userId
    ).toArray() as any[];

    for (const m of pending) {
      if (m.conversation_id.startsWith("group_")) {
        // Verify active membership in group
        const groupStub = this.getGroupStub(m.conversation_id);
        const isMember = await groupStub.isMember(userId);
        if (!isMember) {
          continue;
        }
      } else {
        // Direct 1:1 message: Sever communication if sender is blocked by recipient (userId)
        if (this.isUserBlocked(userId, m.sender_user_id)) {
          this.ctx.storage.sql.exec(
            `DELETE FROM mailbox_messages WHERE message_id = ? AND recipient_user_id = ?`,
            m.message_id,
            userId
          );
          continue;
        }
      }

      this.sendServerMessage(ws, {
        type: "MESSAGE",
        messageId: m.message_id,
        conversationId: m.conversation_id,
        senderId: m.sender_user_id,
        body: m.body,
        timestamp: m.client_timestamp,
        replyToId: m.reply_to_id || undefined,
        effect: m.effect || undefined,
        reactions: this.getReactionsForMessage(m.message_id),
      });

      this.ctx.storage.sql.exec(
        `UPDATE mailbox_messages SET delivery_state = 'DELIVERED'
         WHERE message_id = ? AND recipient_user_id = ?`,
        m.message_id,
        userId
      );
    }

    try {
      const pendingReactions = this.ctx.storage.sql.exec(
        `SELECT id, message_id, conversation_id, user_id, emoji, action, timestamp
         FROM mailbox_reactions
         WHERE recipient_user_id = ? AND delivery_state IN ('QUEUED', 'DELIVERED')
         ORDER BY timestamp ASC, id ASC`,
        userId
      ).toArray() as any[];

      for (const pr of pendingReactions) {
        if (!pr.conversation_id.startsWith("group_") && this.isUserBlocked(userId, pr.user_id)) {
          this.ctx.storage.sql.exec(
            `DELETE FROM mailbox_reactions WHERE id = ?`,
            pr.id
          );
          continue;
        }
        const reactions = this.getReactionsForMessage(pr.message_id);
        this.sendServerMessage(ws, {
          type: "REACTION_UPDATE",
          messageId: pr.message_id,
          conversationId: pr.conversation_id,
          userId: pr.user_id,
          emoji: pr.emoji,
          action: pr.action,
          reactions,
          timestamp: pr.timestamp,
        });

        this.ctx.storage.sql.exec(
          `UPDATE mailbox_reactions SET delivery_state = 'ACKED' WHERE id = ?`,
          pr.id
        );
      }
    } catch {
      // Ignored if table not yet accessed
    }
  }

  private async handleSendMessage(ws: WebSocket, msg: ClientMessage & { type: "SEND_MESSAGE" }): Promise<void> {
    const attachment = this.getSocketAttachment(ws);
    const senderId = attachment.userId;
    if (!senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session must be authenticated before sending messages",
      });
      return;
    }

    // Validate messageId
    if (
      !msg.messageId ||
      msg.messageId.length > ProtocolLimits.MAX_MESSAGE_ID_LENGTH ||
      !ProtocolLimits.MESSAGE_ID_REGEX.test(msg.messageId)
    ) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "INVALID_MESSAGE_ID",
        message: "messageId must match ^[a-zA-Z0-9_-]{1,128}$",
      });
      return;
    }

    // Validate conversationId
    if (
      !msg.conversationId ||
      msg.conversationId.length > ProtocolLimits.MAX_CONVERSATION_ID_LENGTH ||
      !ProtocolLimits.CONVERSATION_ID_REGEX.test(msg.conversationId)
    ) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "INVALID_CONVERSATION_ID",
        message: "conversationId must match ^[a-zA-Z0-9_-]{1,64}$",
      });
      return;
    }

    // Validate body
    if (!msg.body || msg.body.trim().length === 0 || msg.body.length > ProtocolLimits.MAX_BODY_LENGTH) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "INVALID_BODY",
        message: `body must be 1-${ProtocolLimits.MAX_BODY_LENGTH} non-blank characters`,
      });
      return;
    }

    // Validate timestamp
    const now = Date.now();
    if (msg.timestamp < ProtocolLimits.MIN_TIMESTAMP || msg.timestamp > now + ProtocolLimits.MAX_FUTURE_DRIFT_MS) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "INVALID_TIMESTAMP",
        message: "timestamp must be between 2020-01-01 and 24 hours into future",
      });
      return;
    }

    const isGroup = msg.conversationId.startsWith("group_");

    if (isGroup) {
      // Group Flow
      const groupStub = this.getGroupStub(msg.conversationId);
      const isMember = await groupStub.isMember(senderId);
      if (!isMember) {
        this.sendServerMessage(ws, {
          type: "ERROR",
          code: "UNAUTHORIZED_GROUP_ACCESS",
          message: "You are not an active member of this group",
        });
        return;
      }

      // Idempotency check: has message already been accepted?
      const existing = this.firstRow(
        `SELECT 1 FROM mailbox_messages WHERE message_id = ? LIMIT 1`,
        msg.messageId
      );

      if (existing) {
        this.sendServerMessage(ws, {
          type: "MESSAGE_ACCEPTED",
          messageId: msg.messageId,
          timestamp: Date.now(),
        });
        return;
      }

      const activeMemberIds = await groupStub.getActiveMemberIds();
      const recipientIds = activeMemberIds.filter((id: string) => id !== senderId);

      const serverReceivedTimestamp = Date.now();
      for (const recId of recipientIds) {
        this.ctx.storage.sql.exec(
          `INSERT OR IGNORE INTO mailbox_messages (
             message_id, conversation_id, sender_user_id, recipient_user_id,
             body, client_timestamp, server_received_timestamp, delivery_state,
             reply_to_id, effect
           ) VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)`,
          msg.messageId,
          msg.conversationId,
          senderId,
          recId,
          msg.body,
          msg.timestamp,
          serverReceivedTimestamp,
          msg.replyToId ?? null,
          msg.effect ?? null
        );
      }

      // Immediately ACK to sender
      this.sendServerMessage(ws, {
        type: "MESSAGE_ACCEPTED",
        messageId: msg.messageId,
        timestamp: serverReceivedTimestamp,
      });

      // Relay in real-time to active online members
      const relayPayload: ServerMessage = {
        type: "MESSAGE",
        messageId: msg.messageId,
        conversationId: msg.conversationId,
        senderId,
        body: msg.body,
        timestamp: msg.timestamp,
        replyToId: msg.replyToId || undefined,
        effect: msg.effect || undefined,
        reactions: {},
      };

      for (const recId of recipientIds) {
        const recSockets = this.findSocketsForUser(recId);
        for (const recSocket of recSockets) {
          this.sendServerMessage(recSocket, relayPayload);
        }
        if (recSockets.length > 0) {
          this.ctx.storage.sql.exec(
            `UPDATE mailbox_messages SET delivery_state = 'DELIVERED'
             WHERE message_id = ? AND recipient_user_id = ?`,
            msg.messageId,
            recId
          );
        }
      }
      return;
    }

    // Direct 1:1 Flow
    const recipientId = msg.recipientId;
    if (
      !recipientId ||
      recipientId.length > ProtocolLimits.MAX_USER_ID_LENGTH ||
      !ProtocolLimits.USER_ID_REGEX.test(recipientId)
    ) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "INVALID_RECIPIENT_ID",
        message: "recipientId must match ^user_[a-zA-Z0-9_]{1,64}$",
      });
      return;
    }

    if (recipientId === senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "SELF_MESSAGE_NOT_ALLOWED",
        message: "Cannot send message to yourself",
      });
      return;
    }

    if (this.isUserBlocked(recipientId, senderId)) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "BLOCKED",
        message: "Cannot deliver message: communication blocked by recipient",
      });
      return;
    }

    if (this.isUserBlocked(senderId, recipientId)) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "BLOCKED",
        message: "Cannot deliver message: recipient is blocked by sender",
      });
      return;
    }

    // Idempotency check: has message already been recorded?
    const existing = this.firstRow(
      `SELECT 1 FROM mailbox_messages WHERE message_id = ? LIMIT 1`,
      msg.messageId
    );

    if (existing) {
      this.sendServerMessage(ws, {
        type: "MESSAGE_ACCEPTED",
        messageId: msg.messageId,
        timestamp: Date.now(),
      });
      return;
    }

    const serverReceivedTimestamp = Date.now();
    this.ctx.storage.sql.exec(
      `INSERT OR IGNORE INTO mailbox_messages (
         message_id, conversation_id, sender_user_id, recipient_user_id,
         body, client_timestamp, server_received_timestamp, delivery_state,
         reply_to_id, effect
       ) VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)`,
      msg.messageId,
      msg.conversationId,
      senderId,
      recipientId,
      msg.body,
      msg.timestamp,
      serverReceivedTimestamp,
      msg.replyToId ?? null,
      msg.effect ?? null
    );

    this.sendServerMessage(ws, {
      type: "MESSAGE_ACCEPTED",
      messageId: msg.messageId,
      timestamp: serverReceivedTimestamp,
    });

    const recSockets = this.findSocketsForUser(recipientId);
    for (const recSocket of recSockets) {
      this.sendServerMessage(recSocket, {
        type: "MESSAGE",
        messageId: msg.messageId,
        conversationId: msg.conversationId,
        senderId,
        body: msg.body,
        timestamp: msg.timestamp,
        replyToId: msg.replyToId || undefined,
        effect: msg.effect || undefined,
        reactions: {},
      });
    }
    if (recSockets.length > 0) {
      this.ctx.storage.sql.exec(
        `UPDATE mailbox_messages SET delivery_state = 'DELIVERED'
         WHERE message_id = ? AND recipient_user_id = ?`,
        msg.messageId,
        recipientId
      );
    }
  }

  private async handleAckReceived(ws: WebSocket, ack: ClientMessage & { type: "ACK_RECEIVED" }): Promise<void> {
    const attachment = this.getSocketAttachment(ws);
    const receiverId = attachment.userId;
    if (!receiverId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session must be authenticated before sending ACKs",
      });
      return;
    }

    if (!ack.messageId || ack.messageId.length > ProtocolLimits.MAX_MESSAGE_ID_LENGTH) {
      return;
    }

    const msg = this.firstRow(
      `SELECT recipient_user_id, sender_user_id, conversation_id FROM mailbox_messages WHERE message_id = ? AND recipient_user_id = ?`,
      ack.messageId,
      receiverId
    );

    if (!msg) {
      // Check if message exists for another recipient
      const other = this.firstRow(
        `SELECT 1 FROM mailbox_messages WHERE message_id = ? LIMIT 1`,
        ack.messageId
      );
      if (other) {
        this.sendServerMessage(ws, {
          type: "ERROR",
          code: "UNAUTHORIZED_ACK",
          message: "Cannot acknowledge message intended for another user",
        });
      }
      return;
    }

    this.ctx.storage.sql.exec(
      `UPDATE mailbox_messages SET delivery_state = 'ACKED'
       WHERE message_id = ? AND recipient_user_id = ?`,
      ack.messageId,
      receiverId
    );

    if (msg.sender_user_id && msg.sender_user_id !== receiverId) {
      const senderSockets = this.findSocketsForUser(msg.sender_user_id);
      for (const senderSocket of senderSockets) {
        this.sendServerMessage(senderSocket, {
          type: "MESSAGE_DELIVERED",
          messageId: ack.messageId,
        });
      }
    }
  }

  private async handleMarkRead(ws: WebSocket, msg: ClientMessage & { type: "MARK_READ" }): Promise<void> {
    const attachment = this.getSocketAttachment(ws);
    const readerId = attachment.userId;
    if (!readerId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session must be authenticated before marking messages as read",
      });
      return;
    }

    if (!msg.messageId || !msg.conversationId) {
      return;
    }

    const readAt = Date.now();
    this.ctx.storage.sql.exec(
      `INSERT OR IGNORE INTO message_reads (message_id, conversation_id, reader_user_id, read_at)
       VALUES (?, ?, ?, ?)`,
      msg.messageId,
      msg.conversationId,
      readerId,
      readAt
    );

    const isGroup = msg.conversationId.startsWith("group_");
    if (isGroup) {
      const groupStub = this.getGroupStub(msg.conversationId);
      const isMember = await groupStub.isMember(readerId);
      if (!isMember) return;

      const activeMembers: string[] = await groupStub.getActiveMemberIds();
      const readBroadcast: ServerMessage = {
        type: "MESSAGE_READ",
        messageId: msg.messageId,
        conversationId: msg.conversationId,
        readerId,
        timestamp: readAt,
      };

      for (const mId of activeMembers) {
        if (mId !== readerId) {
          const recSockets = this.findSocketsForUser(mId);
          for (const recSocket of recSockets) {
            this.sendServerMessage(recSocket, readBroadcast);
          }
        }
      }
    } else {
      const row = this.firstRow(
        `SELECT sender_user_id FROM mailbox_messages WHERE message_id = ? AND recipient_user_id = ?`,
        msg.messageId,
        readerId
      );
      const senderId = row?.sender_user_id;
      if (senderId && senderId !== readerId && !this.isUserBlocked(readerId, senderId) && !this.isUserBlocked(senderId, readerId)) {
        const senderSockets = this.findSocketsForUser(senderId);
        for (const senderSocket of senderSockets) {
          this.sendServerMessage(senderSocket, {
            type: "MESSAGE_READ",
            messageId: msg.messageId,
            conversationId: msg.conversationId,
            readerId,
            timestamp: readAt,
          });
        }
      }
    }
  }

  async getMessageReads(conversationId: string): Promise<any[]> {
    return this.ctx.storage.sql.exec(
      `SELECT message_id, conversation_id, reader_user_id, read_at
       FROM message_reads
       WHERE conversation_id = ?
       ORDER BY read_at ASC`,
      conversationId
    ).toArray();
  }

  private async handleUnsendMessage(
    ws: WebSocket,
    msg: ClientMessage & { type: "UNSEND_MESSAGE" }
  ): Promise<void> {
    const attachment = this.getSocketAttachment(ws);
    const senderId = attachment.userId;
    if (!senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session must be authenticated before unsending messages",
        requestId: msg.requestId,
      });
      return;
    }

    if (!msg.messageId || !msg.conversationId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "INVALID_ARGUMENT",
        message: "messageId and conversationId are required",
        requestId: msg.requestId,
      });
      return;
    }

    // Check if already unsent (idempotency)
    const alreadyUnsent = this.firstRow(
      `SELECT message_id, conversation_id, sender_user_id, unsent_at
       FROM unsent_messages
       WHERE message_id = ?
       LIMIT 1`,
      msg.messageId
    ) as any;

    if (alreadyUnsent) {
      this.sendServerMessage(ws, {
        type: "MESSAGE_UNSENT",
        messageId: msg.messageId,
        conversationId: alreadyUnsent.conversation_id,
        senderId: alreadyUnsent.sender_user_id,
        timestamp: alreadyUnsent.unsent_at,
      });
      return;
    }

    const messageRow = this.firstRow(
      `SELECT conversation_id, sender_user_id, recipient_user_id, server_received_timestamp
       FROM mailbox_messages
       WHERE message_id = ?
       LIMIT 1`,
      msg.messageId
    ) as any;

    if (!messageRow) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "MESSAGE_NOT_FOUND",
        message: "Message does not exist",
        requestId: msg.requestId,
      });
      return;
    }

    // Enforce conversation match
    if (messageRow.conversation_id !== msg.conversationId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "INVALID_CONVERSATION",
        message: "Message does not belong to specified conversation",
        requestId: msg.requestId,
      });
      return;
    }

    // Enforce sender ownership: only sender can unsend
    if (messageRow.sender_user_id !== senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "PERMISSION_DENIED",
        message: "You can only unsend your own messages",
        requestId: msg.requestId,
      });
      return;
    }

    // Enforce 5-minute server window
    const now = Date.now();
    const ageMs = now - messageRow.server_received_timestamp;
    if (ageMs > 5 * 60 * 1000) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNSEND_EXPIRED",
        message: "Messages can only be unsent within 5 minutes of sending",
        requestId: msg.requestId,
      });
      return;
    }

    // Persist to unsent_messages
    this.ctx.storage.sql.exec(
      `INSERT OR IGNORE INTO unsent_messages (message_id, conversation_id, sender_user_id, unsent_at)
       VALUES (?, ?, ?, ?)`,
      msg.messageId,
      msg.conversationId,
      senderId,
      now
    );

    // Neutralize mailbox records
    this.ctx.storage.sql.exec(
      `UPDATE mailbox_messages
       SET body = '', delivery_state = 'UNSENT'
       WHERE message_id = ?`,
      msg.messageId
    );

    const unsentPayload: ServerMessage = {
      type: "MESSAGE_UNSENT",
      messageId: msg.messageId,
      conversationId: msg.conversationId,
      senderId,
      timestamp: now,
    };

    const isGroup = msg.conversationId.startsWith("group_");
    if (isGroup) {
      const groupStub = this.getGroupStub(msg.conversationId);
      const activeMembers: string[] = await groupStub.getActiveMemberIds();
      for (const mId of activeMembers) {
        const memberSockets = this.findSocketsForUser(mId);
        for (const memberSocket of memberSockets) {
          this.sendServerMessage(memberSocket, unsentPayload);
        }
      }
    } else {
      // 1:1 conversation: notify sender and all recipient records
      this.sendServerMessage(ws, unsentPayload);
      const recipients = this.ctx.storage.sql.exec(
        `SELECT DISTINCT recipient_user_id FROM mailbox_messages WHERE message_id = ?`,
        msg.messageId
      ).toArray() as any[];
      for (const r of recipients) {
        if (r.recipient_user_id !== senderId) {
          const recSockets = this.findSocketsForUser(r.recipient_user_id);
          for (const recSocket of recSockets) {
            this.sendServerMessage(recSocket, unsentPayload);
          }
        }
      }
    }
  }

  private async handleToggleReaction(
    ws: WebSocket,
    msg: ClientMessage & { type: "TOGGLE_REACTION" }
  ): Promise<void> {
    const attachment = this.getSocketAttachment(ws);
    const userId = attachment.userId;
    if (!userId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session must be authenticated before reacting to messages",
      });
      return;
    }

    if (!msg.messageId || !msg.conversationId || !msg.emoji) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "INVALID_ARGUMENT",
        message: "messageId, conversationId, and emoji are required",
      });
      return;
    }

    const emoji = msg.emoji.trim();
    if (!emoji) return;

    const now = Date.now();
    const existing = this.firstRow(
      `SELECT 1 FROM message_reactions WHERE message_id = ? AND user_id = ? AND emoji = ?`,
      msg.messageId,
      userId,
      emoji
    );

    let action: "ADDED" | "REMOVED";
    if (existing) {
      this.ctx.storage.sql.exec(
        `DELETE FROM message_reactions WHERE message_id = ? AND user_id = ? AND emoji = ?`,
        msg.messageId,
        userId,
        emoji
      );
      action = "REMOVED";
    } else {
      this.ctx.storage.sql.exec(
        `INSERT OR REPLACE INTO message_reactions (message_id, conversation_id, user_id, emoji, created_at)
         VALUES (?, ?, ?, ?, ?)`,
        msg.messageId,
        msg.conversationId,
        userId,
        emoji,
        now
      );
      action = "ADDED";
    }

    const reactions = this.getReactionsForMessage(msg.messageId);

    const reactionPayload: ServerMessage = {
      type: "REACTION_UPDATE",
      messageId: msg.messageId,
      conversationId: msg.conversationId,
      userId,
      emoji,
      action,
      reactions,
      timestamp: now,
    };

    const isGroup = msg.conversationId.startsWith("group_");
    if (isGroup) {
      const groupStub = this.getGroupStub(msg.conversationId);
      const activeMembers: string[] = await groupStub.getActiveMemberIds();
      for (const mId of activeMembers) {
        const memberSockets = this.findSocketsForUser(mId);
        for (const memberSocket of memberSockets) {
          this.sendServerMessage(memberSocket, reactionPayload);
        }
        if (memberSockets.length === 0 && mId !== userId) {
          this.ctx.storage.sql.exec(
            `INSERT INTO mailbox_reactions (message_id, conversation_id, user_id, emoji, action, recipient_user_id, timestamp, delivery_state)
             VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED')`,
            msg.messageId,
            msg.conversationId,
            userId,
            emoji,
            action,
            mId,
            now
          );
        }
      }
    } else {
      // 1:1 conversation: notify the sender (all sockets) and recipient
      const msgRow = this.firstRow(
        `SELECT sender_user_id, recipient_user_id FROM mailbox_messages WHERE message_id = ? LIMIT 1`,
        msg.messageId
      ) as any;

      const recipientId = msgRow
        ? (msgRow.sender_user_id === userId ? msgRow.recipient_user_id : msgRow.sender_user_id)
        : null;

      if (recipientId && (this.isUserBlocked(recipientId, userId) || this.isUserBlocked(userId, recipientId))) {
        this.sendServerMessage(ws, {
          type: "ERROR",
          code: "BLOCKED",
          message: "Cannot react to message: communication blocked",
        });
        return;
      }

      // Broadcast to sender sockets
      const senderSockets = this.findSocketsForUser(userId);
      for (const s of senderSockets) {
        this.sendServerMessage(s, reactionPayload);
      }

      // Deliver/queue to recipient
      if (recipientId && recipientId !== userId) {
        const recSockets = this.findSocketsForUser(recipientId);
        for (const r of recSockets) {
          this.sendServerMessage(r, reactionPayload);
        }
        if (recSockets.length === 0) {
          this.ctx.storage.sql.exec(
            `INSERT INTO mailbox_reactions (message_id, conversation_id, user_id, emoji, action, recipient_user_id, timestamp, delivery_state)
             VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED')`,
            msg.messageId,
            msg.conversationId,
            userId,
            emoji,
            action,
            recipientId,
            now
          );
        }
      }
    }
  }

  async getUnsentMessages(conversationId: string): Promise<any[]> {
    return this.ctx.storage.sql.exec(
      `SELECT message_id, conversation_id, sender_user_id, unsent_at
       FROM unsent_messages
       WHERE conversation_id = ?
       ORDER BY unsent_at ASC`,
      conversationId
    ).toArray();
  }

  // ---------------------------------------------------------------------------
  // Group Operation Handlers
  // ---------------------------------------------------------------------------

  private getGroupStub(groupId: string): any {
    const id = (this.env.GROUP_DO as any).idFromName(groupId);
    return (this.env.GROUP_DO as any).get(id);
  }

  private async handleCreateGroup(ws: WebSocket, msg: ClientMessage & { type: "CREATE_GROUP" }): Promise<void> {
    const senderId = this.getSocketAttachment(ws).userId;
    if (!senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session not authenticated",
        requestId: msg.requestId,
      });
      return;
    }

    const name = msg.name.trim();
    if (name.length < ProtocolLimits.MIN_GROUP_NAME_LENGTH || name.length > ProtocolLimits.MAX_GROUP_NAME_LENGTH) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "INVALID_GROUP_NAME",
        message: `Group name must be 1-${ProtocolLimits.MAX_GROUP_NAME_LENGTH} characters`,
        requestId: msg.requestId,
      });
      return;
    }

    for (const memberId of msg.memberIds || []) {
      if (!ProtocolLimits.USER_ID_REGEX.test(memberId)) {
        this.sendServerMessage(ws, {
          type: "ERROR",
          code: "INVALID_MEMBER_ID",
          message: `Member ID '${memberId}' is invalid`,
          requestId: msg.requestId,
        });
        return;
      }
    }

    if ((msg.memberIds?.length || 0) > ProtocolLimits.MAX_GROUP_MEMBERS) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "TOO_MANY_MEMBERS",
        message: `Group cannot have more than ${ProtocolLimits.MAX_GROUP_MEMBERS} members`,
        requestId: msg.requestId,
      });
      return;
    }

    const groupId = `group_${crypto.randomUUID()}`;
    const groupStub = this.getGroupStub(groupId);
    const summary: GroupSummary = await groupStub.create(groupId, name, senderId, msg.memberIds || []);

    // Track user groups index
    this.ctx.storage.sql.exec(
      `INSERT OR IGNORE INTO user_groups (group_id, user_id) VALUES (?, ?)`,
      groupId,
      senderId
    );
    for (const mId of msg.memberIds || []) {
      this.ctx.storage.sql.exec(
        `INSERT OR IGNORE INTO user_groups (group_id, user_id) VALUES (?, ?)`,
        groupId,
        mId
      );
    }

    this.sendServerMessage(ws, {
      type: "GROUP_OPERATION_OK",
      requestId: msg.requestId || "",
      groupId,
      action: "CREATE_GROUP",
      name: summary.name,
    });

    const event: ServerMessage = {
      type: "GROUP_EVENT",
      groupId,
      eventType: "CREATED",
      actorId: senderId,
      name: summary.name,
      timestamp: Date.now(),
    };
    await this.broadcastToGroup(groupId, event, senderId);
  }

  private async handleGetGroups(ws: WebSocket, msg: ClientMessage & { type: "GET_GROUPS" }): Promise<void> {
    const senderId = this.getSocketAttachment(ws).userId;
    if (!senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session not authenticated",
        requestId: msg.requestId,
      });
      return;
    }

    const groupRows = this.ctx.storage.sql.exec(
      `SELECT group_id FROM user_groups WHERE user_id = ?`,
      senderId
    ).toArray() as any[];

    const summaries: GroupSummary[] = [];
    for (const r of groupRows) {
      const stub = this.getGroupStub(r.group_id);
      const s = await stub.getSummary(senderId);
      if (s) summaries.push(s);
    }

    this.sendServerMessage(ws, {
      type: "GROUP_LIST",
      requestId: msg.requestId || "",
      groups: summaries,
    });
  }

  private async handleGetGroupMembers(ws: WebSocket, msg: ClientMessage & { type: "GET_GROUP_MEMBERS" }): Promise<void> {
    const senderId = this.getSocketAttachment(ws).userId;
    if (!senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session not authenticated",
        requestId: msg.requestId,
      });
      return;
    }

    const stub = this.getGroupStub(msg.groupId);
    const isMember = await stub.isMember(senderId);
    if (!isMember) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHORIZED_GROUP_ACCESS",
        message: "Not a member of this group",
        requestId: msg.requestId,
      });
      return;
    }

    const members: GroupMemberSummary[] = await stub.getMembers();
    this.sendServerMessage(ws, {
      type: "GROUP_MEMBERS_LIST",
      requestId: msg.requestId || "",
      groupId: msg.groupId,
      members,
    });
  }

  private async handleAddGroupMember(ws: WebSocket, msg: ClientMessage & { type: "ADD_GROUP_MEMBER" }): Promise<void> {
    const senderId = this.getSocketAttachment(ws).userId;
    if (!senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session not authenticated",
        requestId: msg.requestId,
      });
      return;
    }

    const stub = this.getGroupStub(msg.groupId);
    const res = await stub.addMember(senderId, msg.userId);
    if (!res.success) {
      const code = res.error?.includes("creator or admins")
        ? "PERMISSION_DENIED"
        : res.error?.includes("Invalid user ID")
        ? "INVALID_USER_ID"
        : "OPERATION_FAILED";
      this.sendServerMessage(ws, {
        type: "ERROR",
        code,
        message: res.error || "Failed to add member",
        requestId: msg.requestId,
      });
      return;
    }

    this.ctx.storage.sql.exec(
      `INSERT OR IGNORE INTO user_groups (group_id, user_id) VALUES (?, ?)`,
      msg.groupId,
      msg.userId
    );

    this.sendServerMessage(ws, {
      type: "GROUP_OPERATION_OK",
      requestId: msg.requestId || "",
      groupId: msg.groupId,
      action: "ADD_MEMBER",
    });

    const event: ServerMessage = {
      type: "GROUP_EVENT",
      groupId: msg.groupId,
      eventType: "MEMBER_ADDED",
      actorId: senderId,
      targetUserId: msg.userId,
      timestamp: Date.now(),
    };
    await this.broadcastToGroup(msg.groupId, event, senderId);
  }

  private async handleRemoveGroupMember(ws: WebSocket, msg: ClientMessage & { type: "REMOVE_GROUP_MEMBER" }): Promise<void> {
    const senderId = this.getSocketAttachment(ws).userId;
    if (!senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session not authenticated",
        requestId: msg.requestId,
      });
      return;
    }

    const stub = this.getGroupStub(msg.groupId);
    const res = await stub.removeMember(senderId, msg.userId);
    if (!res.success) {
      const code = res.error?.includes("creator from group")
        ? "CANNOT_REMOVE_CREATOR"
        : res.error?.includes("creator or admins")
        ? "PERMISSION_DENIED"
        : "OPERATION_FAILED";
      this.sendServerMessage(ws, {
        type: "ERROR",
        code,
        message: res.error || "Failed to remove member",
        requestId: msg.requestId,
      });
      return;
    }

    this.ctx.storage.sql.exec(
      `DELETE FROM user_groups WHERE group_id = ? AND user_id = ?`,
      msg.groupId,
      msg.userId
    );

    this.sendServerMessage(ws, {
      type: "GROUP_OPERATION_OK",
      requestId: msg.requestId || "",
      groupId: msg.groupId,
      action: "REMOVE_MEMBER",
    });

    const event: ServerMessage = {
      type: "GROUP_EVENT",
      groupId: msg.groupId,
      eventType: "MEMBER_REMOVED",
      actorId: senderId,
      targetUserId: msg.userId,
      timestamp: Date.now(),
    };
    await this.broadcastToGroup(msg.groupId, event, senderId);

    // Also notify target user sockets if connected
    for (const targetSocket of this.findSocketsForUser(msg.userId)) {
      this.sendServerMessage(targetSocket, event);
    }
  }

  private async handleLeaveGroup(ws: WebSocket, msg: ClientMessage & { type: "LEAVE_GROUP" }): Promise<void> {
    const senderId = this.getSocketAttachment(ws).userId;
    if (!senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session not authenticated",
        requestId: msg.requestId,
      });
      return;
    }

    const stub = this.getGroupStub(msg.groupId);
    const res = await stub.leaveGroup(senderId);
    if (!res.success) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "OPERATION_FAILED",
        message: res.error || "Failed to leave group",
        requestId: msg.requestId,
      });
      return;
    }

    this.ctx.storage.sql.exec(
      `DELETE FROM user_groups WHERE group_id = ? AND user_id = ?`,
      msg.groupId,
      senderId
    );

    this.sendServerMessage(ws, {
      type: "GROUP_OPERATION_OK",
      requestId: msg.requestId || "",
      groupId: msg.groupId,
      action: "LEAVE_GROUP",
    });

    const event: ServerMessage = {
      type: "GROUP_EVENT",
      groupId: msg.groupId,
      eventType: "MEMBER_LEFT",
      actorId: senderId,
      timestamp: Date.now(),
    };
    await this.broadcastToGroup(msg.groupId, event, senderId);
  }

  private async handleRenameGroup(ws: WebSocket, msg: ClientMessage & { type: "RENAME_GROUP" }): Promise<void> {
    const senderId = this.getSocketAttachment(ws).userId;
    if (!senderId) {
      this.sendServerMessage(ws, {
        type: "ERROR",
        code: "UNAUTHENTICATED",
        message: "Session not authenticated",
        requestId: msg.requestId,
      });
      return;
    }

    const stub = this.getGroupStub(msg.groupId);
    const res = await stub.renameGroup(senderId, msg.newName);
    if (!res.success) {
      const code = res.error?.includes("creator or admins")
        ? "PERMISSION_DENIED"
        : res.error?.includes("Group name must be")
        ? "INVALID_GROUP_NAME"
        : "OPERATION_FAILED";
      this.sendServerMessage(ws, {
        type: "ERROR",
        code,
        message: res.error || "Failed to rename group",
        requestId: msg.requestId,
      });
      return;
    }

    this.sendServerMessage(ws, {
      type: "GROUP_OPERATION_OK",
      requestId: msg.requestId || "",
      groupId: msg.groupId,
      action: "RENAME_GROUP",
      name: msg.newName.trim(),
    });

    const event: ServerMessage = {
      type: "GROUP_EVENT",
      groupId: msg.groupId,
      eventType: "RENAMED",
      actorId: senderId,
      name: msg.newName.trim(),
      timestamp: Date.now(),
    };
    await this.broadcastToGroup(msg.groupId, event, senderId);
  }

  private async broadcastToGroup(groupId: string, serverMsg: ServerMessage, excludeUserId?: string): Promise<void> {
    const stub = this.getGroupStub(groupId);
    const activeMemberIds = await stub.getActiveMemberIds();
    for (const memberId of activeMemberIds) {
      if (memberId !== excludeUserId) {
        for (const s of this.findSocketsForUser(memberId)) {
          this.sendServerMessage(s, serverMsg);
        }
      }
    }
  }

  private findSocketForUser(userId: string): WebSocket | null {
    const sockets = this.findSocketsForUser(userId);
    return sockets.length > 0 ? sockets[0] : null;
  }

  // ---------------------------------------------------------------------------
  // Data Access & Management Methods (Invoked by Worker Router or DO)
  // ---------------------------------------------------------------------------

  async createUserWithPassword(
    username: string,
    displayName: string,
    password: string,
    deviceId: string
  ): Promise<{ user: UserRecord; token: string; recoveryCode: string }> {
    const un = username.trim().toLowerCase();
    const dn = displayName.trim() || un;

    if (!ProtocolLimits.USERNAME_REGEX.test(un)) {
      throw new Error("Invalid username: must be 3-24 alphanumeric or underscore characters");
    }
    if (dn.length < ProtocolLimits.MIN_DISPLAY_NAME_LENGTH || dn.length > ProtocolLimits.MAX_DISPLAY_NAME_LENGTH) {
      throw new Error("Invalid displayName: must be 1-32 non-blank characters");
    }
    if (!password || password.length < ProtocolLimits.MIN_PASSWORD_LENGTH) {
      throw new Error("Password must be at least 8 characters");
    }
    if (password.length > ProtocolLimits.MAX_PASSWORD_LENGTH) {
      throw new Error("Password too long");
    }

    const existing = this.firstRow(`SELECT 1 FROM users WHERE username = ?`, un);
    if (existing) {
      throw new Error(`Username '${un}' is already taken`);
    }

    let asterId = generateAsterId();
    let collisionCheck = this.firstRow(`SELECT 1 FROM users WHERE aster_id = ?`, asterId);
    while (collisionCheck) {
      asterId = generateAsterId();
      collisionCheck = this.firstRow(`SELECT 1 FROM users WHERE aster_id = ?`, asterId);
    }

    const userId = `user_${crypto.randomUUID().replace(/-/g, "").slice(0, 16)}`;
    const now = Date.now();

    const { hash: passwordHash, salt: passwordSalt } = await hashPassword(password);
    const recoveryCode = generateRecoveryCode();
    const { hash: recoveryHash, salt: recoverySalt } = await hashRecoveryCode(recoveryCode);

    this.ctx.storage.sql.exec(
      `INSERT INTO users (
         user_id, aster_id, username, display_name, created_at, updated_at, is_active,
         password_hash, password_salt, recovery_hash, recovery_salt,
         failed_login_attempts, last_failed_login, failed_recovery_attempts, last_failed_recovery
       ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 0, 0, 0, 0)`,
      userId,
      asterId,
      un,
      dn,
      now,
      now,
      passwordHash,
      passwordSalt,
      recoveryHash,
      recoverySalt
    );

    const token = await this.createToken(userId, deviceId);
    return {
      user: {
        user_id: userId,
        aster_id: asterId,
        username: un,
        display_name: dn,
        created_at: now,
        updated_at: now,
        is_active: 1,
        password_hash: passwordHash,
        password_salt: passwordSalt,
        recovery_hash: recoveryHash,
        recovery_salt: recoverySalt,
      },
      token,
      recoveryCode,
    };
  }

  async createUser(username: string, displayName: string, deviceId: string): Promise<{ user: UserRecord; token: string }> {
    const un = username.trim().toLowerCase();
    const dn = displayName.trim() || un;

    if (!ProtocolLimits.USERNAME_REGEX.test(un)) {
      throw new Error("Invalid username: must be 3-24 alphanumeric or underscore characters");
    }
    if (dn.length < ProtocolLimits.MIN_DISPLAY_NAME_LENGTH || dn.length > ProtocolLimits.MAX_DISPLAY_NAME_LENGTH) {
      throw new Error("Invalid displayName: must be 1-32 non-blank characters");
    }

    const existing = this.firstRow(`SELECT 1 FROM users WHERE username = ?`, un);
    if (existing) {
      throw new Error(`Username '${un}' is already taken`);
    }

    let asterId = generateAsterId();
    let collisionCheck = this.firstRow(`SELECT 1 FROM users WHERE aster_id = ?`, asterId);
    while (collisionCheck) {
      asterId = generateAsterId();
      collisionCheck = this.firstRow(`SELECT 1 FROM users WHERE aster_id = ?`, asterId);
    }

    const userId = `user_${crypto.randomUUID().replace(/-/g, "").slice(0, 16)}`;
    const now = Date.now();

    this.ctx.storage.sql.exec(
      `INSERT INTO users (user_id, aster_id, username, display_name, created_at, updated_at, is_active)
       VALUES (?, ?, ?, ?, ?, ?, 1)`,
      userId,
      asterId,
      un,
      dn,
      now,
      now
    );

    const token = await this.createToken(userId, deviceId);
    return {
      user: {
        user_id: userId,
        aster_id: asterId,
        username: un,
        display_name: dn,
        created_at: now,
        updated_at: now,
        is_active: 1,
      },
      token,
    };
  }

  async loginUser(
    username: string,
    password: string,
    deviceId: string
  ): Promise<{ user: UserRecord; token: string }> {
    const un = username.trim().toLowerCase();
    const user = this.firstRow(`SELECT * FROM users WHERE username = ?`, un) as UserRecord | null;

    if (!user || user.is_active !== 1) {
      // Timing attack mitigation with dummy hash
      await verifyPassword(password, "0".repeat(64), "0".repeat(32));
      throw new Error("Invalid username or password");
    }

    const now = Date.now();
    if (user.failed_login_attempts && user.failed_login_attempts >= 5) {
      const timeSinceLastFailed = now - (user.last_failed_login || 0);
      if (timeSinceLastFailed < 60000) {
        throw new Error("RATE_LIMIT: Too many failed login attempts. Please wait 1 minute before trying again.");
      }
    }

    if (!user.password_hash || !user.password_salt) {
      throw new Error("ACCOUNT_MIGRATION_REQUIRED");
    }

    const isValid = await verifyPassword(password, user.password_hash, user.password_salt);
    if (!isValid) {
      this.ctx.storage.sql.exec(
        `UPDATE users SET failed_login_attempts = failed_login_attempts + 1, last_failed_login = ? WHERE user_id = ?`,
        now,
        user.user_id
      );
      throw new Error("Invalid username or password");
    }

    this.ctx.storage.sql.exec(
      `UPDATE users SET failed_login_attempts = 0 WHERE user_id = ?`,
      user.user_id
    );

    const token = await this.createToken(user.user_id, deviceId);
    return { user, token };
  }

  async recoverPassword(
    username: string,
    recoveryCode: string,
    newPassword: string,
    deviceId: string
  ): Promise<{ user: UserRecord; token: string; newRecoveryCode: string }> {
    const un = username.trim().toLowerCase();
    const user = this.firstRow(`SELECT * FROM users WHERE username = ?`, un) as UserRecord | null;

    if (!user || user.is_active !== 1 || !user.recovery_hash || !user.recovery_salt) {
      await verifyRecoveryCode(recoveryCode, "0".repeat(64), "0".repeat(32));
      throw new Error("Invalid username or recovery code");
    }

    const now = Date.now();
    if (user.failed_recovery_attempts && user.failed_recovery_attempts >= 5) {
      const timeSinceLastFailed = now - (user.last_failed_recovery || 0);
      if (timeSinceLastFailed < 60000) {
        throw new Error("RATE_LIMIT: Too many failed recovery attempts. Please wait 1 minute before trying again.");
      }
    }

    if (!newPassword || newPassword.length < ProtocolLimits.MIN_PASSWORD_LENGTH) {
      throw new Error("New password must be at least 8 characters");
    }
    if (newPassword.length > ProtocolLimits.MAX_PASSWORD_LENGTH) {
      throw new Error("Password too long");
    }

    const isValid = await verifyRecoveryCode(recoveryCode, user.recovery_hash, user.recovery_salt);
    if (!isValid) {
      this.ctx.storage.sql.exec(
        `UPDATE users SET failed_recovery_attempts = failed_recovery_attempts + 1, last_failed_recovery = ? WHERE user_id = ?`,
        now,
        user.user_id
      );
      throw new Error("Invalid username or recovery code");
    }

    const { hash: newPasswordHash, salt: newPasswordSalt } = await hashPassword(newPassword);
    const newRecoveryCode = generateRecoveryCode();
    const { hash: newRecoveryHash, salt: newRecoverySalt } = await hashRecoveryCode(newRecoveryCode);

    this.ctx.storage.sql.exec(
      `UPDATE users SET
         password_hash = ?,
         password_salt = ?,
         recovery_hash = ?,
         recovery_salt = ?,
         failed_recovery_attempts = 0,
         failed_login_attempts = 0,
         updated_at = ?
       WHERE user_id = ?`,
      newPasswordHash,
      newPasswordSalt,
      newRecoveryHash,
      newRecoverySalt,
      now,
      user.user_id
    );

    // Invalidate all existing sessions on password recovery
    await this.revokeTokensForUser(user.user_id);

    // Establish fresh session
    const token = await this.createToken(user.user_id, deviceId);
    return {
      user: {
        ...user,
        password_hash: newPasswordHash,
        password_salt: newPasswordSalt,
        recovery_hash: newRecoveryHash,
        recovery_salt: newRecoverySalt,
        updated_at: now,
      },
      token,
      newRecoveryCode,
    };
  }

  async migrateAccount(
    username: string,
    newPassword: string,
    deviceId: string
  ): Promise<{ user: UserRecord; token: string; recoveryCode: string }> {
    const un = username.trim().toLowerCase();
    const user = this.firstRow(`SELECT * FROM users WHERE username = ?`, un) as UserRecord | null;

    if (!user || user.is_active !== 1) {
      throw new Error("User not found");
    }

    if (user.password_hash) {
      throw new Error("Account already migrated. Please sign in or use password recovery.");
    }

    if (!newPassword || newPassword.length < ProtocolLimits.MIN_PASSWORD_LENGTH) {
      throw new Error("Password must be at least 8 characters");
    }
    if (newPassword.length > ProtocolLimits.MAX_PASSWORD_LENGTH) {
      throw new Error("Password too long");
    }

    const now = Date.now();
    const { hash: passwordHash, salt: passwordSalt } = await hashPassword(newPassword);
    const recoveryCode = generateRecoveryCode();
    const { hash: recoveryHash, salt: recoverySalt } = await hashRecoveryCode(recoveryCode);

    this.ctx.storage.sql.exec(
      `UPDATE users SET
         password_hash = ?,
         password_salt = ?,
         recovery_hash = ?,
         recovery_salt = ?,
         failed_login_attempts = 0,
         failed_recovery_attempts = 0,
         updated_at = ?
       WHERE user_id = ?`,
      passwordHash,
      passwordSalt,
      recoveryHash,
      recoverySalt,
      now,
      user.user_id
    );

    const token = await this.createToken(user.user_id, deviceId);
    return {
      user: {
        ...user,
        password_hash: passwordHash,
        password_salt: passwordSalt,
        recovery_hash: recoveryHash,
        recovery_salt: recoverySalt,
        updated_at: now,
      },
      token,
      recoveryCode,
    };
  }

  async getUser(userId: string): Promise<UserRecord | null> {
    return this.firstRow(
      `SELECT user_id, aster_id, username, display_name, created_at, updated_at, is_active, last_seen, is_deleted
       FROM users WHERE user_id = ?`,
      userId
    );
  }

  async getUserByAsterId(asterId: string): Promise<UserRecord | null> {
    return this.firstRow(
      `SELECT user_id, aster_id, username, display_name, created_at, updated_at, is_active, last_seen, is_deleted
       FROM users WHERE aster_id = ?`,
      asterId
    );
  }

  formatUserSummary(u: any): UserSummary {
    const isDeleted = u.is_deleted === 1 || u.is_active === 0;
    const isOnline = isDeleted ? false : this.isUserOnline(u.user_id);
    const lastSeen = isDeleted ? 0 : (isOnline ? 0 : (u.last_seen || 0));
    return {
      userId: u.user_id,
      asterId: isDeleted ? "AST-DELETED" : u.aster_id,
      username: isDeleted ? "deleted" : u.username,
      displayName: isDeleted ? "Deleted User" : u.display_name,
      isOnline,
      lastSeen,
      isDeleted,
    };
  }

  async getPresence(userIds: string[]): Promise<Record<string, { isOnline: boolean; lastSeen: number; isDeleted: boolean }>> {
    const result: Record<string, { isOnline: boolean; lastSeen: number; isDeleted: boolean }> = {};
    for (const uid of userIds) {
      const u = await this.getUser(uid);
      if (!u || u.is_deleted === 1 || u.is_active === 0) {
        result[uid] = { isOnline: false, lastSeen: 0, isDeleted: true };
      } else {
        const isOnline = this.isUserOnline(uid);
        const lastSeen = isOnline ? 0 : (u.last_seen || 0);
        result[uid] = { isOnline, lastSeen, isDeleted: false };
      }
    }
    return result;
  }

  async searchUsers(query: string, currentUserId: string): Promise<UserSummary[]> {
    const q = query.trim().toLowerCase();
    if (!q) return [];

    const rows = this.ctx.storage.sql.exec(
      `SELECT user_id, aster_id, username, display_name, last_seen, is_deleted, is_active
       FROM users
       WHERE is_active = 1 AND is_deleted = 0 AND user_id != ? AND (username LIKE ? OR display_name LIKE ? OR aster_id LIKE ? OR user_id LIKE ?)
       ORDER BY created_at DESC
       LIMIT 50`,
      currentUserId,
      `%${q}%`,
      `%${q}%`,
      `%${q}%`,
      `%${q}%`
    ).toArray() as any[];

    // Filter out blocked users
    return rows
      .filter((r) => !this.isUserBlocked(r.user_id, currentUserId) && !this.isUserBlocked(currentUserId, r.user_id))
      .map((r) => this.formatUserSummary(r));
  }

  async updateDisplayName(userId: string, displayName: string): Promise<boolean> {
    const dn = displayName.trim();
    if (dn.length < ProtocolLimits.MIN_DISPLAY_NAME_LENGTH || dn.length > ProtocolLimits.MAX_DISPLAY_NAME_LENGTH) {
      return false;
    }
    const now = Date.now();
    this.ctx.storage.sql.exec(
      `UPDATE users SET display_name = ?, updated_at = ? WHERE user_id = ?`,
      dn,
      now,
      userId
    );
    return true;
  }

  async updateUsername(userId: string, newUsername: string): Promise<boolean> {
    const un = newUsername.trim().toLowerCase();
    if (!ProtocolLimits.USERNAME_REGEX.test(un)) {
      return false;
    }
    const existing = this.firstRow(
      `SELECT user_id FROM users WHERE username = ?`,
      un
    );

    if (existing && existing.user_id !== userId) {
      return false;
    }

    const now = Date.now();
    this.ctx.storage.sql.exec(
      `UPDATE users SET username = ?, updated_at = ? WHERE user_id = ?`,
      un,
      now,
      userId
    );
    return true;
  }

  async deleteAccount(userId: string): Promise<boolean> {
    const now = Date.now();
    const deletedUsername = `deleted_${userId.replace(/^user_/, "")}_${now}`;

    this.ctx.storage.sql.exec(
      `UPDATE users 
       SET is_active = 0,
           is_deleted = 1,
           display_name = 'Deleted User',
           username = ?,
           password_hash = NULL,
           password_salt = NULL,
           recovery_hash = NULL,
           recovery_salt = NULL,
           last_seen = 0,
           updated_at = ?
       WHERE user_id = ?`,
      deletedUsername,
      now,
      userId
    );

    this.ctx.storage.sql.exec(
      `UPDATE auth_tokens SET revoked_at = ? WHERE user_id = ?`,
      now,
      userId
    );

    this.ctx.storage.sql.exec(
      `DELETE FROM friend_requests WHERE sender_user_id = ? OR recipient_user_id = ?`,
      userId,
      userId
    );

    this.ctx.storage.sql.exec(
      `DELETE FROM push_tokens WHERE user_id = ?`,
      userId
    );

    this.ctx.storage.sql.exec(
      `DELETE FROM blocked_users WHERE blocker_user_id = ? OR blocked_user_id = ?`,
      userId,
      userId
    );

    const groupRows = this.ctx.storage.sql.exec(
      `SELECT group_id FROM user_groups WHERE user_id = ?`,
      userId
    ).toArray() as any[];

    this.ctx.storage.sql.exec(
      `DELETE FROM user_groups WHERE user_id = ?`,
      userId
    );

    for (const gr of groupRows) {
      try {
        const groupStub = this.getGroupStub(gr.group_id);
        await groupStub.removeMember(gr.group_id, userId, userId);
      } catch (_) {}
    }

    for (const ws of this.ctx.getWebSockets()) {
      const attach = this.getSocketAttachment(ws);
      if (attach.userId === userId) {
        try {
          this.sendServerMessage(ws, {
            type: "ERROR",
            code: "ACCOUNT_DELETED",
            message: "Your account has been deleted",
          });
          ws.close(4000, "Account deleted");
        } catch (_) {}
      }
    }

    this.broadcastUserDeleted(userId);
    this.broadcastPresence(userId, false, 0);

    return true;
  }

  async purgeAccount(userId: string): Promise<boolean> {
    const groupRows = this.ctx.storage.sql.exec(
      `SELECT group_id FROM user_groups WHERE user_id = ?`,
      userId
    ).toArray() as any[];

    for (const gr of groupRows) {
      try {
        const groupStub = this.getGroupStub(gr.group_id);
        await groupStub.removeMember(gr.group_id, userId, userId);
      } catch (_) {}
    }

    for (const ws of this.ctx.getWebSockets()) {
      const attach = this.getSocketAttachment(ws);
      if (attach.userId === userId) {
        try {
          this.sendServerMessage(ws, {
            type: "ERROR",
            code: "ACCOUNT_DELETED",
            message: "Your account has been purged",
          });
          ws.close(4000, "Account purged");
        } catch (_) {}
      }
    }

    this.ctx.storage.sql.exec(`DELETE FROM auth_tokens WHERE user_id = ?`, userId);
    this.ctx.storage.sql.exec(`DELETE FROM friend_requests WHERE sender_user_id = ? OR recipient_user_id = ?`, userId, userId);
    this.ctx.storage.sql.exec(`DELETE FROM push_tokens WHERE user_id = ?`, userId);
    this.ctx.storage.sql.exec(`DELETE FROM blocked_users WHERE blocker_user_id = ? OR blocked_user_id = ?`, userId, userId);
    this.ctx.storage.sql.exec(`DELETE FROM user_groups WHERE user_id = ?`, userId);
    this.ctx.storage.sql.exec(`DELETE FROM mailbox_messages WHERE sender_user_id = ? OR recipient_user_id = ?`, userId, userId);
    this.ctx.storage.sql.exec(`DELETE FROM message_reactions WHERE user_id = ?`, userId);
    this.ctx.storage.sql.exec(`DELETE FROM mailbox_reactions WHERE recipient_user_id = ? OR user_id = ?`, userId, userId);
    this.ctx.storage.sql.exec(`DELETE FROM message_reads WHERE reader_user_id = ?`, userId);
    this.ctx.storage.sql.exec(`DELETE FROM unsent_messages WHERE sender_user_id = ?`, userId);
    this.ctx.storage.sql.exec(`DELETE FROM users WHERE user_id = ?`, userId);

    return true;
  }

  async listAllUsersAdmin(): Promise<any[]> {
    return this.ctx.storage.sql.exec(
      `SELECT user_id, aster_id, username, display_name, is_active, is_deleted, created_at FROM users`
    ).toArray();
  }

  async createToken(userId: string, deviceId: string): Promise<string> {
    const rawToken = generateSecureToken();
    const tokenHash = await hashToken(rawToken);
    const now = Date.now();

    const existingUser = this.firstRow(`SELECT user_id FROM users WHERE user_id = ?`, userId);
    if (!existingUser) {
      const cleanName = userId.replace(/^user_/, "").replace(/[^a-zA-Z0-9_]/g, "_").slice(0, 14);
      const uname = `${cleanName || "user"}_${Math.random().toString(36).substring(2, 6)}`;
      const asterId = generateAsterId();
      this.ctx.storage.sql.exec(
        `INSERT OR IGNORE INTO users (user_id, aster_id, username, display_name, created_at, updated_at, is_active)
         VALUES (?, ?, ?, ?, ?, ?, 1)`,
        userId,
        asterId,
        uname,
        userId,
        now,
        now
      );
    }

    this.ctx.storage.sql.exec(
      `INSERT INTO auth_tokens (token_hash, user_id, device_id, created_at, revoked_at)
       VALUES (?, ?, ?, ?, NULL)`,
      tokenHash,
      userId,
      deviceId,
      now
    );
    return rawToken;
  }

  async validateToken(rawToken: string): Promise<AuthTokenRecord | null> {
    const tokenHash = await hashToken(rawToken.trim());
    return this.firstRow(
      `SELECT t.token_hash, t.user_id, t.device_id, t.created_at, t.revoked_at
       FROM auth_tokens t
       JOIN users u ON u.user_id = t.user_id
       WHERE t.token_hash = ? AND t.revoked_at IS NULL AND u.is_active = 1 AND u.is_deleted = 0`,
      tokenHash
    );
  }

  async revokeToken(rawToken: string): Promise<boolean> {
    const tokenHash = await hashToken(rawToken.trim());
    const row = this.firstRow(
      `SELECT user_id FROM auth_tokens WHERE token_hash = ? AND revoked_at IS NULL`,
      tokenHash
    );

    if (!row) return false;

    const now = Date.now();
    this.ctx.storage.sql.exec(
      `UPDATE auth_tokens SET revoked_at = ? WHERE token_hash = ?`,
      now,
      tokenHash
    );
    await this.closeSession(row.user_id, "Credential revoked");
    return true;
  }

  async revokeTokensForUser(userId: string): Promise<number> {
    const now = Date.now();
    const unrevoked = this.firstRow(
      `SELECT COUNT(*) as count FROM auth_tokens WHERE user_id = ? AND revoked_at IS NULL`,
      userId
    );

    this.ctx.storage.sql.exec(
      `UPDATE auth_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`,
      now,
      userId
    );
    await this.closeSession(userId, "User credentials revoked");
    return unrevoked?.count ?? 0;
  }

  async revokeTokensForDevice(deviceId: string): Promise<boolean> {
    const now = Date.now();
    const userRows = this.ctx.storage.sql.exec(
      `SELECT DISTINCT user_id FROM auth_tokens WHERE device_id = ? AND revoked_at IS NULL`,
      deviceId
    ).toArray() as any[];

    this.ctx.storage.sql.exec(
      `UPDATE auth_tokens SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL`,
      now,
      deviceId
    );

    for (const r of userRows) {
      await this.closeSession(r.user_id, "Device credentials revoked");
    }
    return userRows.length > 0;
  }

  async closeDeviceSession(userId: string, deviceId: string, reason: string = "Session terminated"): Promise<void> {
    for (const ws of this.ctx.getWebSockets()) {
      const attach = this.getSocketAttachment(ws);
      if (attach.userId === userId && attach.deviceId === deviceId) {
        try {
          ws.close(4000, reason);
        } catch (_) {}
      }
    }
    this.checkAndUpdatePresenceOnDisconnect(userId);
  }

  async closeSession(userId: string, reason: string = "Session terminated"): Promise<void> {
    for (const ws of this.ctx.getWebSockets()) {
      const attach = this.getSocketAttachment(ws);
      if (attach.userId === userId) {
        try {
          ws.close(4000, reason);
        } catch (_) {}
      }
    }
    this.checkAndUpdatePresenceOnDisconnect(userId);
  }

  // ---------------------------------------------------------------------------
  // Friends & Social
  // ---------------------------------------------------------------------------

  async sendFriendRequest(senderUserId: string, recipientUserId: string): Promise<FriendRequestSummary> {
    if (this.isUserBlocked(recipientUserId, senderUserId) || this.isUserBlocked(senderUserId, recipientUserId)) {
      throw new Error("Cannot send friend request: communication prohibited due to user block");
    }

    const existingReq = this.firstRow(
      `SELECT id, status FROM friend_requests
       WHERE (sender_user_id = ? AND recipient_user_id = ?)
          OR (sender_user_id = ? AND recipient_user_id = ?)`,
      senderUserId,
      recipientUserId,
      recipientUserId,
      senderUserId
    );

    if (existingReq) {
      if (existingReq.status === "ACCEPTED") {
        throw new Error("Users are already friends");
      }
      if (existingReq.status === "PENDING") {
        throw new Error("Friend request already pending");
      }
    }

    const id = `freq_${crypto.randomUUID()}`;
    const now = Date.now();
    this.ctx.storage.sql.exec(
      `INSERT INTO friend_requests (id, sender_user_id, recipient_user_id, status, created_at, updated_at)
       VALUES (?, ?, ?, 'PENDING', ?, ?)
       ON CONFLICT(sender_user_id, recipient_user_id) DO UPDATE SET
         status = 'PENDING', updated_at = excluded.updated_at`,
      id,
      senderUserId,
      recipientUserId,
      now,
      now
    );

    const recUser = await this.getUser(recipientUserId);
    return {
      id,
      senderUserId,
      recipientUserId,
      status: "PENDING",
      recipient: recUser
        ? {
            userId: recUser.user_id,
            asterId: recUser.aster_id,
            username: recUser.username,
            displayName: recUser.display_name,
          }
        : null,
      createdAt: now,
    };
  }

  async respondFriendRequest(requestId: string, recipientUserId: string, accept: boolean): Promise<boolean> {
    const req = this.firstRow(
      `SELECT id, sender_user_id, recipient_user_id, status
       FROM friend_requests
       WHERE id = ? AND recipient_user_id = ? AND status = 'PENDING'`,
      requestId,
      recipientUserId
    );

    if (!req) return false;

    const newStatus = accept ? "ACCEPTED" : "REJECTED";
    const now = Date.now();
    this.ctx.storage.sql.exec(
      `UPDATE friend_requests SET status = ?, updated_at = ? WHERE id = ?`,
      newStatus,
      now,
      requestId
    );
    return true;
  }

  async cancelFriendRequest(requestId: string, senderUserId: string): Promise<boolean> {
    const req = this.firstRow(
      `SELECT id FROM friend_requests
       WHERE id = ? AND sender_user_id = ? AND status = 'PENDING'`,
      requestId,
      senderUserId
    );

    if (!req) return false;

    this.ctx.storage.sql.exec(`DELETE FROM friend_requests WHERE id = ?`, requestId);
    return true;
  }

  async getIncomingFriendRequests(userId: string): Promise<FriendRequestSummary[]> {
    const rows = this.ctx.storage.sql.exec(
      `SELECT id, sender_user_id, recipient_user_id, status, created_at
       FROM friend_requests
       WHERE recipient_user_id = ? AND status = 'PENDING'
       ORDER BY created_at DESC`,
      userId
    ).toArray() as any[];

    const result: FriendRequestSummary[] = [];
    for (const r of rows) {
      const sender = await this.getUser(r.sender_user_id);
      result.push({
        id: r.id,
        senderUserId: r.sender_user_id,
        recipientUserId: r.recipient_user_id,
        status: r.status,
        sender: sender ? this.formatUserSummary(sender) : null,
        createdAt: r.created_at,
      });
    }
    return result;
  }

  async getOutgoingFriendRequests(userId: string): Promise<FriendRequestSummary[]> {
    const rows = this.ctx.storage.sql.exec(
      `SELECT id, sender_user_id, recipient_user_id, status, created_at
       FROM friend_requests
       WHERE sender_user_id = ? AND status = 'PENDING'
       ORDER BY created_at DESC`,
      userId
    ).toArray() as any[];

    const result: FriendRequestSummary[] = [];
    for (const r of rows) {
      const rec = await this.getUser(r.recipient_user_id);
      result.push({
        id: r.id,
        senderUserId: r.sender_user_id,
        recipientUserId: r.recipient_user_id,
        status: r.status,
        recipient: rec ? this.formatUserSummary(rec) : null,
        createdAt: r.created_at,
      });
    }
    return result;
  }

  async getFriends(userId: string): Promise<UserSummary[]> {
    const rows = this.ctx.storage.sql.exec(
      `SELECT CASE WHEN sender_user_id = ? THEN recipient_user_id ELSE sender_user_id END as friend_id
       FROM friend_requests
       WHERE (sender_user_id = ? OR recipient_user_id = ?) AND status = 'ACCEPTED'`,
      userId,
      userId,
      userId
    ).toArray() as any[];

    const friends: UserSummary[] = [];
    for (const r of rows) {
      const u = await this.getUser(r.friend_id);
      if (u && !this.isUserBlocked(userId, u.user_id) && !this.isUserBlocked(u.user_id, userId)) {
        friends.push(this.formatUserSummary(u));
      }
    }
    return friends;
  }

  isUserBlocked(blockerUserId: string, targetUserId: string): boolean {
    const row = this.firstRow(
      `SELECT 1 FROM blocked_users WHERE blocker_user_id = ? AND blocked_user_id = ?`,
      blockerUserId,
      targetUserId
    );
    return row !== null;
  }

  async blockUser(blockerUserId: string, blockedUserId: string): Promise<boolean> {
    const now = Date.now();
    // 1. Cancel active friendship / requests between blocker and blocked user
    this.ctx.storage.sql.exec(
      `UPDATE friend_requests SET status = 'CANCELLED', updated_at = ?
       WHERE (sender_user_id = ? AND recipient_user_id = ?)
          OR (sender_user_id = ? AND recipient_user_id = ?)`,
      now,
      blockerUserId,
      blockedUserId,
      blockedUserId,
      blockerUserId
    );

    // 2. Insert into blocked_users
    this.ctx.storage.sql.exec(
      `INSERT OR IGNORE INTO blocked_users (blocker_user_id, blocked_user_id, created_at)
       VALUES (?, ?, ?)`,
      blockerUserId,
      blockedUserId,
      now
    );

    // 3. Purge queued/undelivered offline messages sent by blockedUserId to blockerUserId
    this.ctx.storage.sql.exec(
      `DELETE FROM mailbox_messages
       WHERE recipient_user_id = ? AND sender_user_id = ? AND delivery_state IN ('QUEUED', 'DELIVERED')`,
      blockerUserId,
      blockedUserId
    );

    // 4. Purge queued/undelivered offline reactions sent by blockedUserId to blockerUserId
    this.ctx.storage.sql.exec(
      `DELETE FROM mailbox_reactions
       WHERE recipient_user_id = ? AND user_id = ? AND delivery_state IN ('QUEUED', 'DELIVERED')`,
      blockerUserId,
      blockedUserId
    );

    return true;
  }

  async unblockUser(blockerUserId: string, blockedUserId: string): Promise<boolean> {
    this.ctx.storage.sql.exec(
      `DELETE FROM blocked_users WHERE blocker_user_id = ? AND blocked_user_id = ?`,
      blockerUserId,
      blockedUserId
    );
    return true;
  }

  async getBlockedUsers(blockerUserId: string): Promise<UserSummary[]> {
    const rows = this.ctx.storage.sql.exec(
      `SELECT blocked_user_id FROM blocked_users WHERE blocker_user_id = ?`,
      blockerUserId
    ).toArray() as any[];

    const result: UserSummary[] = [];
    for (const r of rows) {
      const u = await this.getUser(r.blocked_user_id);
      if (u) {
        result.push({
          userId: u.user_id,
          asterId: u.aster_id,
          username: u.username,
          displayName: u.display_name,
        });
      }
    }
    return result;
  }

  async registerPushToken(userId: string, deviceId: string, pushToken: string, endpoint?: string): Promise<void> {
    const now = Date.now();
    this.ctx.storage.sql.exec(
      `INSERT INTO push_tokens (user_id, device_id, push_token, endpoint, updated_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(user_id, device_id) DO UPDATE SET
         push_token = excluded.push_token,
         endpoint = excluded.endpoint,
         updated_at = excluded.updated_at`,
      userId,
      deviceId,
      pushToken,
      endpoint || null,
      now
    );
  }

  async getStats(): Promise<{ users: number; connections: number }> {
    const sockets = this.ctx.getWebSockets();
    const activeUsers = new Set<string>();
    for (const s of sockets) {
      const a = this.getSocketAttachment(s);
      if (a.userId) activeUsers.add(a.userId);
    }
    return {
      users: activeUsers.size,
      connections: sockets.length,
    };
  }
}
