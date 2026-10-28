# Aster — Cloudflare Backend Feasibility Audit

**Document Date:** October 20, 2026  
**Target Project:** Aster (`com.aster.app` Android Client & Ktor Backend Relay)  
**Scope:** Feasibility study for migrating Aster's Ktor + SQLite backend to Cloudflare Workers + Durable Objects + Durable Object SQLite.  
**Constraint Enforcement:** AUDIT ONLY. Zero modifications made to existing source code, schemas, Gradle files, Android code, tests, certificates, or networking configurations.  

---

## Executive Verdict

**Verdict:** **`GO WITH ARCHITECTURAL CHANGES`**

### Summary Justification
Cloudflare Workers combined with **SQLite-backed Durable Objects** and the **WebSocket Hibernation API** is technically viable and capable of hosting Aster's backend for its target scale of ~100 users, friend chat, 1:1 direct messaging, small groups, and durable offline mailboxing.

However, a direct 1:1 port of Aster's monolithic Ktor JVM daemon is neither possible nor desirable without deliberate architectural adaptations:
1. **Concurrency & Lifecycle Paradigm:** The Ktor backend relies on long-running JVM threads, synchronized in-memory hash maps (`SessionManager.userSession`), and blocking JDBC calls (`DriverManager.getConnection`). Cloudflare runs V8 isolates where Durable Objects act as single-threaded transactional actors. State cannot rely on volatile JVM singleton maps; it must utilize the **Durable Object WebSocket Hibernation API** (`ctx.acceptWebSocket`) and embedded **Durable Object SQLite** (`ctx.storage.sql`).
2. **Heartbeat & Request Quota Tuning:** On the Workers Free plan, Cloudflare imposes a strict ceiling of **100,000 requests/day**, where *every inbound WebSocket frame counts as a billable request*. Aster's current Android client ping interval (15–30s) would exhaust the free daily quota with just 35–50 continuously connected idle users if unadjusted. Because Cloudflare's edge proxy manages low-level TCP keep-alives and WebSocket ping/pong automatically, application-level PING frames must be reduced or eliminated during idle periods.
3. **Partitioning Architecture:** Aster requires a clean actor partitioning model. For ~100 users, a **Hybrid Architecture** (`UserDO` for personal mailbox/session + `GroupDO` for group fan-out + Stateless Worker for public routing) or a transitional **Single-DO Aster Hub** (`AsterHubDO`) is required to preserve Aster's strict single-session replacement, sender identity enforcement, and group fan-out invariants.

Every wire frame, state transition, and cryptographic invariant of Aster Protocol v0.2 can be preserved with **100% fidelity**.

---

## Current Aster Backend

### Current Baseline Verification
- **Git Commit:** `6dec69d` (*Stable Build*) directly following baseline `ca6ca22` (*feat: add durable sqlite mailbox*).
- **Automated Test Suite Status:**
  - **Backend Tests:** **74/74 passing** (0 failures, 0 errors, 0 skipped across 5 suites: `AsterServerTest` [26], `AsterAuthenticationTest` [19], `MailboxDatabaseTest` [13], `GroupServerTest` [12], `TlsServerTest` [4]).
  - **Android Unit Tests:** **81/81 passing** across debug and release variants (0 failures, 0 errors, 0 skipped across 10 suites: `RepositoryTest` [14], `AsterSecurityTest` [14], `AsterNotificationTest` [11], `OnboardingAndIdentityTest` [8], `ViewModelTest` [7], `AsterApiClientAndSocialTest` [7], `DomainModelTest` [7], `AsterWebSocketTest` [7], `AsterRoomPersistenceTest` [5], `AsterThemeTest` [1]).
  - **Total Repository Automated Tests:** **155 passing**.

### Component Map

| Component | Source File | Current Responsibility | Persistent State? | Concurrency Model | WebSocket Dependency? | Can Run in Worker? | Can Run in Durable Object? | Requires Redesign? |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Server Engine** | `com.aster.backend.Main.kt` | Netty/CIO embedded engine setup, TLS keystore loading (`aster_server.p12`), port binding (`8443`). | No | Multi-threaded Netty event loop | Indirect (hosts engine) | No (replaced by Workers runtime) | No (replaced by Workers runtime) | **Yes** (Cloudflare manages runtime) |
| **HTTP Routing** | `com.aster.backend.AsterServer.kt` | REST API routes (`/health`, `/api/register`, `/api/profile`, `/api/friends/*`, `/api/push/register`, admin endpoints). | No | Coroutines (`Dispatchers.IO`) | No | **Yes** (via `fetch()`) | **Yes** (via DO `fetch()`) | **No** (Direct endpoint mapping) |
| **Session Manager** | `com.aster.backend.session.SessionManager.kt` | Tracks active WebSocket sessions, user-to-session maps, single session replacement, LRU deduplication cache (10k items). | In-Memory only (`ConcurrentHashMap`, `LinkedHashMap`) | Thread-safe locks / concurrent maps | **Critical** (holds `WebSocketSession` objects) | No | **Yes** (via `ctx.acceptWebSocket` & `ctx.getWebSockets`) | **Yes** (adapted to Hibernation API) |
| **Message Relay** | `com.aster.backend.relay.MessageRelay.kt` | Wire frame dispatch, bounds checking, sender authentication enforcement, 1:1 and group fan-out, offline queue routing. | Stateless mediator (delegates to DB & SessionManager) | Suspend functions / Coroutines | **Critical** (reads/writes text frames) | No | **Yes** (core DO logic) | **No** (business logic maps directly to TS) |
| **Mailbox Database** | `com.aster.backend.database.MailboxDatabase.kt` | SQLite database repository: users, auth tokens, friend requests, groups, group members, push tokens, and durable `mailbox_messages`. | **Yes** (Disk SQLite file with WAL) | Synchronized connection lock (`synchronized(lock)`) | Indirect (queried upon connect/ACK) | No | **Yes** (via `ctx.storage.sql`) | **Yes** (JDBC -> `ctx.storage.sql.exec`) |
| **Push Gateway** | `com.aster.backend.push.PushGateway.kt` | Dispatches wakeup notifications to offline recipients. Currently `NoOpPushGateway`. | No | Async Coroutine (`Dispatchers.IO`) | No | **Yes** (Worker `fetch` to FCM/WebPush) | **Yes** (DO `fetch` out) | **No** (Interface preserved) |

---

## Existing Protocol

The source of truth is `backend/src/main/kotlin/com/aster/backend/protocol/Protocol.kt`, `protocol/protocol.md`, and `android/app/src/main/kotlin/com/aster/app/data/network/AsterProtocol.kt`.

### Protocol Boundaries & Limits
- **Max Frame Size:** 64 KB (`65,536` bytes).
- **Max Message Body Length:** 4,096 characters (`MIN_BODY_LENGTH = 1`).
- **User ID Format:** `^user_[a-zA-Z0-9_]{1,64}$`.
- **Aster ID Format:** `^AST-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}$` (Crockford-like base32, excluding confusing characters `0, 1, I, L, O`).
- **Username Format:** `^[a-zA-Z0-9_]{3,24}$`.
- **Display Name Length:** 1 to 32 characters.
- **Conversation ID Format:** `^[a-zA-Z0-9_-]{1,64}$`.
- **Group ID Format:** `^group_[a-zA-Z0-9_-]{1,64}$`.
- **Group Name Length:** 1 to 64 characters; max 100 members per group.
- **Message ID Format:** `^[a-zA-Z0-9_-]{1,128}$` (UUID/KSUID generated by client).
- **Timestamp Constraints:** Must be positive (`>= 1`) and within 24 hours of future drift (`MAX_FUTURE_DRIFT_MS = 86,400,000`).
- **Self-Messaging:** Strictly prohibited (`senderId == recipientId` returns `SELF_MESSAGE_NOT_ALLOWED`).
- **Sender Forgery Guard:** Any `senderId` field supplied in `SEND_MESSAGE` is overridden by the server using the authenticated session user ID.

### Wire Frames (JSON Discriminator: `"type"`)

#### Client → Server Frames
1. `AUTH`: `{ "type": "AUTH", "token": "ast_tok_...", "userId": "user_...", "deviceId": "..." }`
2. `SEND_MESSAGE`: `{ "type": "SEND_MESSAGE", "messageId": "...", "conversationId": "...", "recipientId": "...", "body": "...", "timestamp": 17... }`
3. `ACK_RECEIVED`: `{ "type": "ACK_RECEIVED", "messageId": "..." }`
4. `PING`: `{ "type": "PING", "timestamp": 17... }`
5. `CREATE_GROUP`: `{ "type": "CREATE_GROUP", "requestId": "...", "name": "...", "memberIds": ["user_..."] }`
6. `GET_GROUPS`: `{ "type": "GET_GROUPS", "requestId": "..." }`
7. `GET_GROUP_MEMBERS`: `{ "type": "GET_GROUP_MEMBERS", "requestId": "...", "groupId": "group_..." }`
8. `ADD_GROUP_MEMBER`: `{ "type": "ADD_GROUP_MEMBER", "requestId": "...", "groupId": "...", "userId": "..." }`
9. `REMOVE_GROUP_MEMBER`: `{ "type": "REMOVE_GROUP_MEMBER", "requestId": "...", "groupId": "...", "userId": "..." }`
10. `LEAVE_GROUP`: `{ "type": "LEAVE_GROUP", "requestId": "...", "groupId": "..." }`
11. `RENAME_GROUP`: `{ "type": "RENAME_GROUP", "requestId": "...", "groupId": "...", "newName": "..." }`

#### Server → Client Frames
1. `AUTH_OK`: `{ "type": "AUTH_OK", "userId": "user_..." }`
2. `AUTH_ERROR`: `{ "type": "AUTH_ERROR", "reason": "..." }`
3. `MESSAGE_ACCEPTED`: `{ "type": "MESSAGE_ACCEPTED", "messageId": "...", "timestamp": 17... }`
4. `MESSAGE_DELIVERED`: `{ "type": "MESSAGE_DELIVERED", "messageId": "..." }`
5. `MESSAGE`: `{ "type": "MESSAGE", "messageId": "...", "conversationId": "...", "senderId": "...", "body": "...", "timestamp": 17... }`
6. `ERROR`: `{ "type": "ERROR", "code": "...", "message": "...", "requestId": "..." }`
7. `PONG`: `{ "type": "PONG", "timestamp": 17... }`
8. `GROUP_OPERATION_OK`: `{ "type": "GROUP_OPERATION_OK", "requestId": "...", "groupId": "...", "action": "...", "name": "..." }`
9. `GROUP_LIST`: `{ "type": "GROUP_LIST", "requestId": "...", "groups": [...] }`
10. `GROUP_MEMBERS_LIST`: `{ "type": "GROUP_MEMBERS_LIST", "requestId": "...", "groupId": "...", "members": [...] }`
11. `GROUP_EVENT`: `{ "type": "GROUP_EVENT", "groupId": "...", "eventType": "CREATED|MEMBER_ADDED|MEMBER_REMOVED|MEMBER_LEFT|RENAMED", "actorId": "...", "targetUserId": "...", "name": "...", "timestamp": 17... }`

### Delivery State Transitions
- **Client Message States (Room):** `CREATED` -> `SENDING` -> `SENT` -> `DELIVERED` -> `READ` (monotonic, duplicate frames ignored via `insertMessageIfNotExists`).
- **Server Mailbox States (`mailbox_messages.delivery_state`):**
  1. `QUEUED`: Written durably to SQLite upon receipt. Sender receives `MESSAGE_ACCEPTED`.
  2. `DELIVERED`: Relayed across active WebSocket connection to online recipient.
  3. `ACKED`: Recipient confirms persistence via `ACK_RECEIVED`. ACKed messages are never redelivered on reconnection.

---

## HTTP API Mapping

Aster features 17 HTTP endpoints in `AsterServer.kt`. Every endpoint maps cleanly to Cloudflare Worker `fetch(request, env, ctx)` handler:

| Endpoint | Method | Auth Required | Current Implementation (`AsterServer.kt`) | Cloudflare Worker Mapping | Target Execution Context |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `/health` | `GET` | No | Lines 111–114 | Return `{ status: "ok", users: ..., connections: ... }` | Stateless Worker / DO fetch |
| `/api/provision` | `POST` | Admin Key + IP Allowlist | Lines 116–152 | Validates `X-Aster-Admin-Key` secret + Cloudflare Access / IP | Worker -> `UserDO` / Directory |
| `/api/revoke` | `POST` | Admin Key + IP Allowlist | Lines 154–220 | Revokes token/user/device credentials, drops active socket | Worker -> `UserDO.closeSession()` |
| `/api/register` | `POST` | No (Rate limited) | Lines 261–317 | Validates username/display name, generates Aster ID & token | Worker -> Directory / `UserDO` |
| `/api/profile` | `GET` | Bearer Token | Lines 319–332 | Validates token, returns user profile record | Worker -> `UserDO` |
| `/api/profile` | `POST` | Bearer Token | Lines 334–378 | Updates displayName or username (uniqueness check) | Worker -> `UserDO` / Directory |
| `/api/users/search` | `GET` | Bearer Token | Lines 380–400 | Searches users by query, filters blocked users | Worker -> Directory Index |
| `/api/friends/request` | `POST` | Bearer Token | Lines 402–454 | Checks blocks/self, inserts friend request record | Worker -> `UserDO(recipient)` |
| `/api/friends/respond` | `POST` | Bearer Token | Lines 456–483 | Updates friend request to `ACCEPTED` or `REJECTED` | Worker -> `UserDO` |
| `/api/friends/cancel` | `POST` | Bearer Token | Lines 485–510 | Cancels pending outgoing request | Worker -> `UserDO` |
| `/api/friends/requests` | `GET` | Bearer Token | Lines 512–547 | Returns incoming and outgoing requests | Worker -> `UserDO` |
| `/api/friends` | `GET` | Bearer Token | Lines 549–560 | Returns list of accepted friends | Worker -> `UserDO` |
| `/api/friends/block` | `POST` | Bearer Token | Lines 562–595 | Inserts block record into `blocked_users` | Worker -> `UserDO` |
| `/api/friends/unblock` | `POST` | Bearer Token | Lines 597–618 | Deletes block record | Worker -> `UserDO` |
| `/api/friends/blocked` | `GET` | Bearer Token | Lines 620–631 | Lists blocked users | Worker -> `UserDO` |
| `/api/logout` | `POST` | Bearer Token | Lines 633–644 | Revokes active token, terminates active WebSocket | Worker -> `UserDO.logout()` |
| `/api/account/delete` | `POST` | Bearer Token | Lines 646–657 | Deactivates user, revokes tokens, closes session | Worker -> `UserDO.deleteAccount()` |
| `/api/push/register` | `POST` | Bearer Token | Lines 222–250 | Registers FCM/push token and device ID | Worker -> `UserDO` |

### Administrative Host Security Mapping
- **Current Behavior:** `AsterServer.isPermittedAdminIp` checks `InetAddress.isLoopbackAddress` and host network interfaces (`AsterServer.kt#L58-L75`).
- **Cloudflare Adaptation:** Because Workers run on Cloudflare's Anycast edge without direct host loopback interfaces, admin routes (`/api/provision`, `/api/revoke`) must be secured by:
  1. Strict constant-time validation of `X-Aster-Admin-Key` from Worker environment secrets (`env.ASTER_ADMIN_KEY`).
  2. Optional Cloudflare Access (Zero Trust application token) or IP restriction via `request.headers.get("cf-connecting-ip")` matching `env.ASTER_ADMIN_ALLOWED_IPS`.

---

## WebSocket Mapping

### Connection Upgrade & Lifecycle
- **Ktor Current Implementation:**
  ```kotlin
  webSocket("/ws") {
      for (frame in incoming) { ... }
  }
  ```
- **Cloudflare Durable Object Implementation:**
  Cloudflare Workers intercept the WebSocket upgrade request and forward it directly to the designated Durable Object:
  ```typescript
  // In Worker fetch():
  if (request.headers.get("Upgrade") === "websocket") {
      const id = env.USER_DO.idFromName(userId);
      const stub = env.USER_DO.get(id);
      return stub.fetch(request);
  }
  ```
  Inside the Durable Object:
  ```typescript
  async fetch(request: Request) {
      const pair = new WebSocketPair();
      const [clientSocket, serverSocket] = Object.values(pair);
      
      // Use the WebSocket Hibernation API:
      this.ctx.acceptWebSocket(serverSocket, [tag]);
      
      return new Response(null, { status: 101, webSocket: clientSocket });
  }
  ```

### Session Replacement & Multi-Device Management
- **Aster Invariant:** *"Each user ID is permitted at most one active WebSocket connection on the relay. When a new connection authenticates with user ID U, any existing active session for U is cleanly closed with code 4002 ('Replaced by new connection')."* (`SessionManager.kt#L39-L52`, `protocol.md#L248-L260`).
- **Durable Object Implementation:**
  When a new WebSocket authenticates in `UserDO(userId)`:
  ```typescript
  const existingSockets = this.ctx.getWebSockets();
  for (const socket of existingSockets) {
      if (socket !== currentSocket) {
          socket.close(4002, "Replaced by new connection");
      }
  }
  ```
  This is 100% deterministic and atomic because all operations on `UserDO(userId)` are serialized within its single-threaded isolate.

### WebSocket Hibernation Compatibility
- Traditional WebSockets keep the server process pinned in memory, incurring billing for 100% of wall-clock duration.
- With Cloudflare's **WebSocket Hibernation API**:
  - Sockets that are idle hibernate automatically after ~10 seconds.
  - When hibernating, **0 GB-s duration is billed** on Cloudflare Free.
  - The runtime wakes the Durable Object instantly upon receiving an inbound client frame, calling `webSocketMessage(ws, message)`.
  - State attached to the socket across hibernation cycles is safely persisted using `ws.serializeAttachment(attachment)` and `ws.deserializeAttachment()`.

---

## SQLite / Mailbox Mapping

### Existing SQLite Schema & Invariants

```sql
-- Schema Version 3 (MailboxDatabase.kt#L118-L348)
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
    PRIMARY KEY (message_id, recipient_user_id)
);
CREATE INDEX IF NOT EXISTS idx_mailbox_recipient_state ON mailbox_messages (recipient_user_id, delivery_state);
CREATE INDEX IF NOT EXISTS idx_mailbox_order ON mailbox_messages (server_received_timestamp, message_id);

CREATE TABLE IF NOT EXISTS groups (
    group_id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    creator_user_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    role TEXT NOT NULL,
    joined_at INTEGER NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    left_at INTEGER,
    PRIMARY KEY (group_id, user_id),
    FOREIGN KEY (group_id) REFERENCES groups(group_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members (user_id, is_active);
CREATE INDEX IF NOT EXISTS idx_group_members_group ON group_members (group_id, is_active);

CREATE TABLE IF NOT EXISTS users (
    user_id TEXT PRIMARY KEY NOT NULL,
    aster_id TEXT UNIQUE NOT NULL,
    username TEXT UNIQUE NOT NULL,
    display_name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1
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

CREATE TABLE IF NOT EXISTS push_tokens (
    user_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    push_token TEXT NOT NULL,
    endpoint TEXT,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, device_id)
);
```

### Durable Object SQLite (`ctx.storage.sql`) Compatibility Analysis

1. **Synchronous Execution:**
   - In Cloudflare Durable Objects, `ctx.storage.sql.exec(query, ...params)` executes **synchronously** within the isolate.
   - It returns a cursor with helper methods `.one()`, `.toArray()`, or iterable evaluation.
   - Example mapping of Aster's pending replay query:
     ```typescript
     // Current Ktor: MailboxDatabase.kt#L1150
     // Cloudflare DO SQLite:
     const pending = this.ctx.storage.sql.exec(`
         SELECT message_id, conversation_id, sender_user_id, recipient_user_id,
                body, client_timestamp, server_received_timestamp, delivery_state
         FROM mailbox_messages
         WHERE recipient_user_id = ? AND delivery_state IN ('QUEUED', 'DELIVERED')
         ORDER BY server_received_timestamp ASC, message_id ASC
     `, userId).toArray();
     ```
2. **Compound Primary Key Support:**
   - `PRIMARY KEY (message_id, recipient_user_id)` is fully supported by DO SQLite.
   - Group fan-out batch inserts using `INSERT OR IGNORE` work identically to the JDBC implementation.
3. **Foreign Keys & PRAGMAs:**
   - Standard SQLite requires foreign keys to be explicitly enabled. In DO SQLite constructor:
     ```typescript
     this.ctx.storage.sql.exec("PRAGMA foreign_keys = ON;");
     ```
   - Cloudflare manages WAL mode and synchronization automatically; manual execution of `PRAGMA journal_mode = WAL` is unnecessary.
4. **Transaction Integrity:**
   - `ctx.storage.transactionSync(() => { ... })` provides explicit ACID transactions matching Aster's multi-statement commits.

---

## Durable Object Partitioning Options

| Partitioning Model | Option A: Monolithic DO (`AsterHubDO`) | Option B: Per-User DO (`UserDO`) | Option C: Per-Conversation DO (`ConversationDO`) | Option D: Per-Group DO (`GroupDO`) | Option E: Hybrid Architecture (`UserDO` + `GroupDO` + Worker) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Description** | Single global DO instance for all 100 users, groups, and messages. | One DO per user. Holds user's socket, mailbox, friends, tokens. | One DO per conversation thread. | One DO per group chat thread. | `UserDO` owns user session + mailbox; `GroupDO` owns group membership & fan-out. |
| **WebSocket Ownership** | Single DO holds all 100 client WebSockets. | Each user connects to their own `UserDO(userId)`. | **Broken**: User is in N chats; cannot open N WebSockets per client. | **Broken**: User is in N groups; cannot open N WebSockets per client. | Each user connects to their own `UserDO(userId)`. Single active socket per user. |
| **Message Routing** | Trivial in-memory socket dispatch. | Cross-DO RPC: `senderDO` -> `recipientDO.deliver(msg)`. | Complex client multi-routing. | Complex client multi-routing. | 1:1: `senderDO` -> `recipientDO.deliver(msg)`.<br>Group: `senderDO` -> `groupDO.fanout(msg)` -> `memberDOs`. |
| **Offline Mailbox** | Single global `mailbox_messages` table. | Local to recipient's `UserDO` SQLite. | Scattered across conversation DOs. | Stored in group DO. | Local to recipient's `UserDO` SQLite. High storage locality. |
| **Direct Messages** | Instant single-isolate relay. | 1 direct DO-to-DO RPC. | Multiplexed through conversation DO. | N/A | 1 direct DO-to-DO RPC. |
| **Group Fan-Out** | In-memory loop over connected sockets. | Sender DO calls N recipient DOs. | Conversation DO relays to members. | Group DO relays to members. | `GroupDO` resolves active membership, dispatches RPC to member `UserDO`s. |
| **Concurrent Writes** | Fully serialized on single thread. Bottleneck under high load. | Independent per user. Zero write contention between users. | Independent per chat. | Independent per group. | Isolated per user and group. Zero cross-user write contention. |
| **Ordering & Tie-Break** | Global monotonic clock + SQLite. | Preserved per recipient mailbox (`timestamp ASC, id ASC`). | Preserved per conversation. | Preserved per group. | Preserved per recipient mailbox (`timestamp ASC, id ASC`). |
| **Idempotency** | Global LRU cache or SQLite query. | Checked at sender `UserDO` + recipient `UserDO` PK. | Checked in Conversation DO. | Checked in `GroupDO`. | Checked at sender `UserDO` and recipient `UserDO`. |
| **Contention Risk** | High if active user count or traffic surges. | Zero global contention. | Low | Low | Zero global contention. |
| **Storage Locality** | All data in 1 SQLite DB (5 GB free limit shared). | User data strictly in user's DB. | Chat data strictly in chat's DB. | Group data strictly in group's DB. | Clean domain isolation across user and group boundaries. |
| **Implementation Complexity** | **Lowest** (direct line-by-line port of Ktor classes). | Medium | High | High | **Medium-Clean** (Idiomatic Cloudflare pattern). |
| **Suitability for ~100 Users** | **Viable for initial prototype**, but architectural anti-pattern. | Viable for DMs, clumsy for groups. | Not viable for unified client WebSocket. | Not viable for unified client WebSocket. | **Strongly Recommended** for production stability. |

---

## Recommended Cloudflare Architecture

### Selected Topology: Option E (Hybrid Architecture)

```
                                 [ Android Client ]
                                         │
                                         │ HTTPS / WSS (Web PKI TLS)
                                         ▼
                   ┌───────────────────────────────────────────┐
                   │        Cloudflare Edge (Stateless)        │
                   │           Global Anycast Network          │
                   └─────────────────────┬─────────────────────┘
                                         │
                   ┌─────────────────────┴─────────────────────┐
                   │             Cloudflare Worker             │
                   │         (fetch Handler / Router)          │
                   └──────┬──────────────────────┬─────────────┘
                          │                      │
           HTTP Auth / Profile / REST            │ Upgrade: websocket
                          │                      │
                          ▼                      ▼
                 ┌─────────────────┐    ┌─────────────────┐
                 │  Directory D1 / │    │ UserDO(userId)  │◄── Owns Client WebSocket
                 │   Global Index  │    │ (Hibernating)   │    (Single Session Rule)
                 └─────────────────┘    └────────┬────────┘
                                                 │
                                                 ├────────────────────────┐
                                    1:1 DM Relay │ RPC                    │ Group Message RPC
                                                 ▼                        ▼
                                        ┌─────────────────┐      ┌─────────────────┐
                                        │UserDO(recipient)│      │ GroupDO(groupId)│
                                        │SQLite: Mailbox  │      │ SQLite: Members │
                                        └─────────────────┘      └────────┬────────┘
                                                                          │ Fan-out RPC
                                                                          ▼
                                                                 ┌─────────────────┐
                                                                 │ Member UserDOs  │
                                                                 └─────────────────┘
```

### Component Breakdown

1. **Edge Router (`Worker.fetch`):**
   - Routes public REST endpoints (`/api/register`, `/health`).
   - Routes user-scoped requests to `UserDO(userId)` by parsing `Authorization: Bearer <token>`.
   - Intercepts `/ws` upgrades: validates token, extracts `userId`, binds connection to `UserDO(userId)`.
2. **`UserDO` (Durable Object per User):**
   - **WebSocket Owner:** Accepts and manages the single active WebSocket connection using the Hibernation API (`this.ctx.acceptWebSocket`).
   - **Session Replacement:** On new connection, queries `this.ctx.getWebSockets()` and closes any existing socket with code `4002`.
   - **Private SQLite Storage:**
     - `auth_tokens`: User's active and revoked credentials.
     - `mailbox_messages`: Incoming pending messages where `recipient_user_id = this.userId`.
     - `friends` & `blocked_users`: Relationship lists.
     - `push_tokens`: Device push tokens for wakeups.
   - **Offline Replay:** Upon WebSocket `onOpen` / `AUTH_OK`, queries local SQLite `mailbox_messages` in `ORDER BY server_received_timestamp ASC, message_id ASC` and flushes down the socket.
   - **Acknowledgement:** Upon receiving `ACK_RECEIVED(messageId)`, updates delivery state to `ACKED`.
3. **`GroupDO` (Durable Object per Group):**
   - **Group Metadata & Roles:** Stores group name, creator, roles (`CREATOR`, `ADMIN`, `MEMBER`), and active members in local SQLite.
   - **Group Fan-Out:** Receives `postGroupMessage(senderId, msg)`. Validates sender membership. Fetches active member IDs. Dispatches asynchronous RPC `deliverMessage(msg)` to each member's `UserDO`.
4. **User Directory (D1 or `DirectoryDO`):**
   - Lightweight index mapping `username -> userId` and `asterId -> userId` to enforce uniqueness across registrations and resolve search queries (`GET /api/users/search`).

---

## TLS & Security Migration

### Current Aster Security Model
- Private Certificate Authority (`certs/aster_ca.crt`, CN: `Aster Root CA`).
- Server certificate (`certs/aster_server.crt`, SAN: `DNS:localhost, DNS:aster.local, IP:192.168.0.114, IP:192.168.240.1, IP:192.168.0.159`).
- Android client configured with `DualTrustManager` (`AsterTlsManager.kt#L20-L122`) combining private Aster Root CA with Android platform Web PKI trust anchors.
- Network Security Config (`network_security_config.xml`) explicitly permits system CAs and `@raw/aster_ca` while strictly disabling cleartext traffic.

### Cloudflare Edge Security Model
- **TLS Termination:** Cloudflare edge terminates public TLS/WSS connections presenting certificates issued by trusted Web PKI authorities (Google Trust Services / Let's Encrypt).
- **Transport Security:** Android connects to `https://<aster-worker>.workers.dev` and `wss://<aster-worker>.workers.dev/ws` using standard TLS 1.3 / TLS 1.2 with perfect forward secrecy.
- **Client PKI Compatibility:** Because Aster's Android client already incorporates `DualTrustManager` which delegates to the Android System TrustManager, **no certificate pinning errors will occur when connecting to Cloudflare**.
- **Worker-to-DO Security:** Inter-isolate communication between the Cloudflare Worker and Durable Objects runs over Cloudflare's private edge fabric. It never traverses the public internet and cannot be intercepted or eavesdropped.
- **Client Secrets:** **Zero Cloudflare API keys or tokens are ever embedded in the Android APK.** Android continues to use standard Aster bearer tokens (`ast_tok_...`) generated by the backend and stored in hardware-backed `AndroidKeyStore`.
- **Sender Forgery Protection:** Preserved exactly as in `MessageRelay.kt#L216-L222`. The Worker extracts `authenticatedUserId` from the validated token and injects it into `msg.senderId`. Any forged client identity is overwritten.
- **Strict Transport Enforcement:** Plaintext `ws://` and `http://` remain strictly forbidden by `NetworkConfig.validateUrlForEnvironment` and Android's `network_security_config.xml`.

---

## Android Client Impact

Inspection of the Android client reveals that the architecture is already exceptionally well-abstracted:

### Unchanged Components
- **Room Local Persistence:** `AppDatabase`, `MessageEntity`, `ConversationEntity`, `UserEntity`, DAOs, and outbox state machine (`SENDING -> SENT -> DELIVERED -> READ`) remain 100% untouched.
- **Auth Storage:** `AsterAuthStorage.kt` uses hardware-backed `AndroidKeyStore` (AES-256-GCM) to encrypt Aster tokens locally. Unchanged.
- **Domain & Repository Layers:** `MessageRepositoryImpl.kt`, `ConversationRepositoryImpl.kt`, `UserRepositoryImpl.kt`. Unchanged.
- **UI & ViewModels:** `ChatViewModel.kt`, `HomeViewModel.kt`, `ConnectionsViewModel.kt`, `OnboardingViewModel.kt`. Unchanged.
- **Notification Manager:** `AsterNotificationManager.kt` and active screen suppression. Unchanged.

### Components That Must Change
- `NetworkConfig.kt`:
  Update `ENDPOINT_PUBLIC_TUNNEL_TLS` (or add `ENDPOINT_PRODUCTION_CLOUDFLARE_TLS`):
  ```kotlin
  const val ENDPOINT_PRODUCTION_CLOUDFLARE_TLS = "wss://aster-backend.<your-subdomain>.workers.dev/ws"
  ```
  And update `defaultEndpointForDevice()` to return this URL for production builds.

### Components That Can Be Cleaned Up / Simplified
- `AsterTlsManager.kt`:
  While `DualTrustManager` already works with Cloudflare's Web PKI, the private `@raw/aster_ca` certificate becomes strictly optional for production builds (it can remain for local offline unit tests).
- `NetworkConfig.kt`:
  Remove temporary hardcoded Quick Tunnel URLs (e.g. `*.trycloudflare.com`) once the permanent Worker hostname is deployed.

---

## Push Notification Impact

### Analysis & Separation of Concerns
Cloudflare Workers hosting does **not** alter the fundamental operating system constraints of Android Doze and background execution:
1. **The Doze / Process Death Constraint:**
   - When an Android device enters Deep Doze or the Aster app process is evicted from RAM by the OS, **any active WebSocket connection is terminated**.
   - Cloudflare cannot "push" down a closed TCP socket.
2. **Current State:**
   - As documented in `ADR_M11_NOTIFICATIONS_AND_PUSH.md`, Aster's backend currently binds `NoOpPushGateway`.
   - Delivery while the app process is terminated relies entirely on **Durable Mailbox Replay** upon subsequent reconnection.
3. **Cloudflare Worker Push Integration:**
   - The `/api/push/register` endpoint stores device push tokens in SQLite (`push_tokens` table).
   - In Cloudflare Workers, implementing a live `PushGateway` is trivial using standard `fetch()`:
     - **FCM HTTP v1 API:** Worker makes an authenticated `POST` to `https://fcm.googleapis.com/v1/projects/.../messages:send` using a Google Cloud service account JSON stored as a Worker secret.
     - **UnifiedPush:** Worker sends a standard HTTP POST to the recipient's chosen UnifiedPush endpoint.
   - Minimal notification payload rule preserved: Payload contains only metadata (`conversationId`, `senderId`, `isGroup`, `timestamp`). No decrypted message bodies are sent through third-party push networks.

---

## Free Tier / Capacity Analysis

### Official Cloudflare Workers & Durable Objects Free Plan Limits
*(Verified against official Cloudflare Documentation at developers.cloudflare.com, October 2026)*

| Resource / Quota | Free Plan Allowance | What Counts Against Quota | Exceeding Action |
| :--- | :--- | :--- | :--- |
| **Requests** | **100,000 / day** (resets 00:00 UTC) | Inbound HTTP requests, DO RPC calls, **inbound WebSocket messages**, and alarm triggers. | Returns HTTP 429 / drops operations until reset. |
| **Compute Duration** | **13,000 GB-s / day** | Wall-clock time while active in memory. (Hibernated sockets consume **0 GB-s**). | Throws limit error. |
| **CPU Time per Request** | **10 ms** (up to 50 ms burst) | Active V8 execution time (excluding I/O wait). | Worker isolate terminated. |
| **Storage per Account** | **5 GB** total | Combined size of all SQLite databases across all DOs. | Throws `SQLITE_FULL`. |
| **Storage per Durable Object** | Up to **10 GB** (capped by 5 GB account limit) | Single SQLite database file size. | Throws `SQLITE_FULL`. |
| **Durable Object Classes** | **100 classes** | Exported DO class definitions in `wrangler.jsonc`. | Build/deploy error. |
| **Number of DO Instances** | **Unlimited** | Individual actor instances (e.g. 1 per user). | None. |
| **Max WebSocket Message** | **32 MiB** | Inbound frame size (Aster limit is 64 KB). | Connection closed. |

### Usage Modeling for ~100 Users

Assuming 100 registered users, where ~30 users are actively online at any given time.

#### Scenario A: Low Activity (Casual Friend Group)
- Active Users: 20
- Messages sent/day: 500
- Group fan-out multiplier (avg group size 5): 2.5x -> 1,250 delivered messages
- Client REST requests/day (profile, friends, sync): 200
- Client keep-alive PINGs: **Zero** (relying on edge WebSocket ping/pong; client sends PING only once every 5 minutes if active) -> ~500 pings/day
- **Total Requests / Inbound Frames:** ~2,450 / day.
- **Free Limit Consumption:** **2.45% of daily allowance**.
- **Compute Duration:** ~2,450 requests × 5ms CPU × 128MB = ~1.5 GB-s / day (**0.01% of 13,000 GB-s limit**).
- **Storage:** ~1,750 messages/day × 500 bytes = ~0.87 MB / day (**0.017% of 5 GB limit**).

#### Scenario B: Medium Activity (Regular Chat & Groups)
- Active Users: 50
- Messages sent/day: 3,000
- Group fan-out multiplier: 4x -> 12,000 delivered messages
- Client REST requests/day: 1,000
- Client keep-alive PINGs (1 probe every 3 minutes while app in foreground): ~4,000 / day
- **Total Requests / Inbound Frames:** ~20,000 / day.
- **Free Limit Consumption:** **20.0% of daily allowance**.
- **Compute Duration:** ~20,000 requests × 8ms × 128MB = ~20.5 GB-s / day (**0.15% of 13,000 GB-s limit**).
- **Storage:** ~15,000 messages/day × 500 bytes = ~7.5 MB / day (**1.8 years to reach 5 GB** before mailbox purge).

#### Scenario C: High Activity / Chatty Groups
- Active Users: 100
- Messages sent/day: 10,000
- Group fan-out multiplier: 5x -> 50,000 delivered messages
- Client REST requests/day: 5,000
- Client keep-alive PINGs (frequent unthrottled pings): 25,000 / day
- **Total Requests / Inbound Frames:** ~90,000 / day.
- **Free Limit Consumption:** **90.0% of daily allowance** (Warning threshold).
- **Compute Duration:** ~90,000 requests × 10ms × 128MB = ~115 GB-s / day (**0.88% of limit**).

> [!WARNING]
> **The Critical Quota Driver: Inbound WebSocket Frames**  
> On Cloudflare, *every inbound text frame from a client counts as 1 request against the 100,000/day limit*. If Aster's Android client sends `PING` frames every 15 seconds 24/7, a single connected phone would generate `(86400 / 15) = 5,760 requests/day`. Just 18 connected phones would completely exhaust the free 100k daily quota!  
> **Mandatory Rule:** Aster Android must suspend application-level PING frames while the socket is idle in background, relying on OkHttp and Cloudflare's native transport-level ping/pong.

---

## Cost Scenarios

| Tier | Base Monthly Cost | Included Requests | Included Compute Duration | Included Storage | Suitability for Aster |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Workers Free** | **$0.00 / mo** | 100,000 / day (~3,000,000 / mo) | 13,000 GB-s / day (~390,000 GB-s / mo) | 5 GB standard SQLite storage | **Suitable for ~100 users** with hibernation and throttled heartbeats. |
| **Workers Paid** | **$5.00 / mo** | 10,000,000 / mo included (+$0.15 per additional 1M) | 400,000 GB-s / mo included (+$12.50 per additional 1M GB-s) | Up to 10 GB per DO + D1 unlimited | **Completely unlimited safety margin**; eliminates daily 100k hard cutoff. |

---

## Existing Test Compatibility

Audit of the 155 automated tests across the repository:

### Backend Test Suite (74 Tests)

| Test File | Test Count | Category | Notes |
| :--- | :--- | :--- | :--- |
| `AsterServerTest.kt` | 26 | **Reusable with Cloudflare Adapter** | Validates HTTP endpoints (`/health`, `/api/register`, `/api/friends/*`). Can run against Cloudflare Miniflare/Vitest integration harness. |
| `AsterAuthenticationTest.kt` | 19 | **Reusable with Cloudflare Adapter** | Validates token creation, revocation, device binding, and auth rejection. Direct equivalent in TypeScript DO. |
| `MailboxDatabaseTest.kt` | 13 | **Must be rewritten in TS (Vitest)** | Validates JDBC SQLite queries, schema migrations, and batch group enqueueing. Maps directly to `ctx.storage.sql` tests. |
| `GroupServerTest.kt` | 12 | **Reusable with Cloudflare Adapter** | Validates group creation, membership changes, events, and fan-out. Runs against Cloudflare test harness. |
| `TlsServerTest.kt` | 4 | **No longer applicable** | Validates JVM Netty keystore loading and private CA cipher suites. In Cloudflare, TLS is managed by the edge platform. |

### Android Test Suite (81 Tests)

| Test File | Test Count | Category | Notes |
| :--- | :--- | :--- | :--- |
| `RepositoryTest.kt` | 14 | **Reusable Unchanged** | Tests Room database operations and repository state transitions. |
| `AsterSecurityTest.kt` | 14 | **Reusable Unchanged** | Tests `DualTrustManager`, keystore encryption, and certificate validation. |
| `AsterNotificationTest.kt` | 11 | **Reusable Unchanged** | Tests notification suppression, channels, and unread badges. |
| `OnboardingAndIdentityTest.kt` | 8 | **Reusable Unchanged** | Tests onboarding flows and identity generation. |
| `ViewModelTest.kt` | 7 | **Reusable Unchanged** | Tests UI view models and state flows. |
| `AsterApiClientAndSocialTest.kt`| 7 | **Reusable Unchanged** | Uses OkHttp MockWebServer to test REST client parsing. |
| `DomainModelTest.kt` | 7 | **Reusable Unchanged** | Tests domain data models and state progression. |
| `AsterWebSocketTest.kt` | 7 | **Reusable Unchanged** | Tests wire frame serialization/deserialization. |
| `AsterRoomPersistenceTest.kt` | 5 | **Reusable Unchanged** | Tests local Room SQLite persistence. |
| `AsterThemeTest.kt` | 1 | **Reusable Unchanged** | Tests Compose theme tokens. |

---

## Required New Tests

A comprehensive Cloudflare-specific test suite must be developed in TypeScript using **Vitest** and **`@cloudflare/vitest-pool-workers`** (Miniflare 3):

1. **Connectivity Suite:**
   - Client WebSocket connection upgrade and HTTP 101 response.
   - Successful `AUTH` token validation and `AUTH_OK` emission.
   - Rejection of invalid tokens with `AUTH_ERROR` and immediate socket closure.
   - Session replacement: Connecting a second socket for `userId` cleanly evicts the first with close code `4002`.
2. **Direct Messaging & Mailbox Replay Suite:**
   - User A sends `SEND_MESSAGE` to online User B: verifies immediate real-time relay and `MESSAGE_ACCEPTED` returned to User A.
   - User A sends to offline User B: verifies message stored in `UserDO(B)` SQLite with state `QUEUED`.
   - User B connects and authenticates: verifies queued message replayed in strict chronological order (`server_received_timestamp ASC`).
   - User B emits `ACK_RECEIVED`: verifies mailbox state transitions to `ACKED`.
   - User B disconnects and reconnects: verifies already-ACKed message is **not** replayed.
3. **Idempotency & Regression Suite:**
   - User A transmits duplicate `SEND_MESSAGE` frames with identical `messageId`: verifies server responds idempotently with `MESSAGE_ACCEPTED` without duplicate relay.
   - User B transmits duplicate `ACK_RECEIVED` frames: verifies state remains `ACKED` without errors.
4. **Group Operations & Fan-Out Suite:**
   - Group creation, member addition, and member removal.
   - Group message dispatch: verifies message is delivered to all active online members and enqueued in the mailboxes of offline members.
   - Member leaves group: verifies subsequent group messages are suppressed for that user.
5. **Durable Object Hibernation & Lifecycle Suite:**
   - Verification that `UserDO` hibernates while idle without dropping the client socket.
   - Inbound client frame wakes hibernated `UserDO` and triggers `webSocketMessage`.
   - Durable Object eviction/restart simulates seamless reconnection and SQLite state retention.
6. **Security & Sender Forgery Suite:**
   - User attempts to forge `senderId` in `SEND_MESSAGE`: verifies backend strictly overrides it with the authenticated session identity.
   - Token revocation via `/api/revoke`: verifies active WebSocket connection is immediately terminated.

---

## Migration Plan

To ensure zero risk to Aster's verified baseline, the migration must follow a strictly decoupled, side-by-side strategy:

```
  Existing Ktor Backend (Netty/JVM)             Cloudflare Worker Backend (TypeScript)
  [ /home/saad/Aster/backend ]                   [ /home/saad/Aster/backend-cloudflare ]
             │                                                     │
             │ Remains working reference implementation             │ Developed in isolation
             ▼                                                     ▼
     Existing 74 Tests Passing                             New Vitest Suite Passing
             │                                                     │
             └───────────────────────┬─────────────────────────────┘
                                     │
                                     ▼
                    Dual-Target End-to-End Compatibility
                         (scripts/e2e_playtest.py)
                                     │
                                     ▼
                    Physical Device Cellular LTE Validation
                         (Samsung Galaxy M21 via WSS)
                                     │
                                     ▼
                      Production DNS Cutover to Worker
```

### Phase Breakdown

- **Phase 1: Isolated Cloudflare Project Scaffolding**
  - Create directory `/home/saad/Aster/backend-cloudflare`.
  - Initialize `wrangler.jsonc`, TypeScript, and Vitest test environment.
  - Port Aster Protocol schemas and limits to TypeScript.
- **Phase 2: Database & Durable Object Core Implementation**
  - Implement SQLite migrations inside `UserDO` and `GroupDO` using `ctx.storage.sql`.
  - Implement WebSocket Hibernation API handlers (`webSocketMessage`, `webSocketClose`).
  - Implement the 3-state mailbox engine (`QUEUED`, `DELIVERED`, `ACKED`).
- **Phase 3: Worker REST Routing & Directory**
  - Implement REST endpoints (`/health`, `/api/register`, `/api/profile`, `/api/friends/*`).
  - Wire administrative authentication (`X-Aster-Admin-Key`).
- **Phase 4: Automated Vitest Verification**
  - Achieve 100% test parity with the 74 backend tests using Miniflare isolate simulation.
- **Phase 5: Dual-Backend Client Verification**
  - Point `scripts/e2e_playtest.py` at the local Miniflare / deployed Cloudflare Worker endpoint.
  - Verify bidirectional messaging, offline replay, and group fan-out.
- **Phase 6: Production Cutover**
  - Update `NetworkConfig.kt` to point to production Worker URL.
  - Verify physical Samsung M21 over commercial LTE network.

---

## Risks

| Risk ID | Category | Description | Severity | Mitigation Strategy |
| :--- | :--- | :--- | :--- | :--- |
| **R-1** | Quota | Daily 100k request exhaustion on Workers Free due to frequent client PING frames. | **High** | Rely on Cloudflare edge TCP keep-alives; throttle client application-level PING frames to max once every 5 minutes while active. |
| **R-2** | Concurrency | SQLite write locks during large group fan-out across multiple DOs. | **Medium** | Batch member RPC dispatches asynchronously; use individual `UserDO`s so writes are distributed across actors. |
| **R-3** | Admin Auth | Admin routes (`/api/provision`) exposed without host loopback protection. | **Medium** | Enforce `X-Aster-Admin-Key` constant-time secret comparison and optional Cloudflare Access Zero Trust policy. |
| **R-4** | State Loss | DO recreation or memory flush losing connection state. | **Low** | Use `ws.serializeAttachment()` for socket metadata and store all delivery states in DO SQLite. |
| **R-5** | Edge Outage | Workers Free plan has no SLA. | **Low** | Acceptable for private ~100 user deployment; upgrading to Workers Paid ($5/mo) provides standard enterprise SLA. |

---

## Recommendation

Proceed with the migration to Cloudflare Workers + Durable Objects SQLite under **Option E (Hybrid Architecture)**. 

### Why Proceed?
1. **Zero Host Infrastructure:** Eliminates the need to maintain an always-on home server, Raspberry Pi, or local tunnel daemon (`cloudflared`).
2. **Global Edge Anycast:** Provides instant low-latency connectivity over cellular LTE anywhere in the world with automatic Web PKI TLS certificates.
3. **True ₹0 Operating Cost:** The ~100 user target easily fits within Cloudflare's Free tier (consuming less than 25% of daily requests and < 2% of compute duration with proper heartbeat throttling).
4. **Preserved Protocol & Security:** 100% of Aster's wire frames, Room state transitions, and Keystore security guarantees remain intact without weakening cryptography.

---

## Final Verdict

**`GO WITH ARCHITECTURAL CHANGES`**

### Direct Answers to the 10 Core Feasibility Questions

1. **Can Cloudflare realistically host Aster's backend?**  
   **Yes.** Cloudflare Workers + Durable Objects + DO SQLite provides the necessary primitives: HTTPS routing, persistent WebSockets, embedded transactional SQLite, and global edge Anycast routing.
2. **Can Durable Objects handle Aster's WebSockets?**  
   **Yes.** Durable Objects provide first-class WebSocket support via the WebSocket Hibernation API (`ctx.acceptWebSocket`), supporting custom close codes (e.g. 4002 for session replacement), attachment serialization, and zero-cost hibernation while idle.
3. **Can Durable Object SQLite replace the current SQLite mailbox/data layer?**  
   **Yes.** DO SQLite (`ctx.storage.sql`) supports Aster's exact tables, compound primary keys (`PRIMARY KEY (message_id, recipient_user_id)`), index definitions, and deterministic ordering queries (`ORDER BY server_received_timestamp ASC, message_id ASC`).
4. **Can we preserve Aster's protocol?**  
   **Yes, 100%.** Every wire frame (`AUTH`, `SEND_MESSAGE`, `ACK_RECEIVED`, `PING`, `MESSAGE_ACCEPTED`, `MESSAGE`, etc.) and all bounds checks will remain identical. No protocol changes are required.
5. **What Android changes are required?**  
   **Minimal configuration changes.** Update `NetworkConfig.kt` server URL to the Cloudflare Worker domain. The UI, ViewModels, Room database, Outbox engine, and Keystore auth storage require zero modifications.
6. **What functionality would be lost or changed?**  
   **Zero user-facing functionality lost.** Internal changes: Ktor Netty JVM daemon replaced by V8 isolate; single-machine JVM loopback IP checks replaced by Worker secret headers (`X-Aster-Admin-Key`); client PING frequency throttled to preserve daily quota.
7. **What security changes are required?**  
   Public TLS terminates at Cloudflare's edge using trusted Web PKI certificates (already supported by Android's `DualTrustManager`). Private CA is no longer needed on the public internet path. Sender identity enforcement is preserved. Zero bypasses (`trustAll`, `hostnameVerifier { true }`) permitted.
8. **Is the Free plan realistically suitable for ~100 users?**  
   **Yes**, provided that WebSocket Hibernation is enabled and client keep-alive PING frames are throttled so total daily frames remain under 100,000/day. (If unthrottled, an upgrade to the $5/month Workers Paid plan is required).
9. **What is the biggest technical risk?**  
   Exhausting the 100,000 requests/day free tier quota if the Android client emits unthrottled background PING frames, as every inbound WebSocket frame is counted as a billable request by Cloudflare.
10. **What should we implement first if we proceed?**  
   Build a separate `backend-cloudflare/` project implementing the core `UserDO` SQLite schema and WebSocket Hibernation handlers, verifying it against Aster's protocol requirements using Vitest and Miniflare before touching Android or deprecating Ktor.
