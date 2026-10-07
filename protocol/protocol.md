# ASTER — Messaging Protocol & State Machine Specification

## Status

Implemented & Hardened Wire Protocol (v0.1) — Realtime WebSocket Relay & State Machine.

---

## 1. Protocol Goals & Architecture Principles

- **Local-First & Offline Resilience**: The client creates and commits messages to the local database before transmission. The UI immediately reflects local creations.
- **Client Message Identity**: Every message ID is client-generated (UUID/KSUID) and canonical. It is strictly preserved across the network, backend, and recipient client. No server-side ID aliasing occurs.
- **Canonical Invariant**: *"One logical message ID represents one logical message."* Duplicate, retransmitted, or racing frames never produce duplicate records or state regression.
- **Explicit JSON Schema & Bounds Enforcement**: All WebSocket messages are typed JSON objects with a mandatory `type` discriminator field and strict size limits.
- **Zero-Cost Relay**: The server acts as an in-memory routing and acknowledgement coordinator. Room on Android is the single source of truth for message persistence.
- **Future E2EE Compatibility**: The message payload structure is designed so `body` can seamlessly transition to an encrypted ciphertext container without changing message routing headers.

---

## 2. Protocol Boundaries & Payload Limits

To protect against memory exhaustion, malformed payloads, and denial-of-service vulnerabilities, the following boundaries are strictly enforced across backend and clients:

| Parameter | Limit | Enforcement / Error Code |
| :--- | :--- | :--- |
| **Max Frame Size** | 64 KB (65,536 bytes) | Disconnect / `PAYLOAD_TOO_LARGE` |
| **Max Message Body Length** | 4,096 characters | `INVALID_BODY` |
| **Max User ID Length** | 64 characters | `INVALID_AUTH` / `INVALID_RECIPIENT_ID` |
| **User ID Format** | `^user_[a-zA-Z0-9_-]{1,64}$` | `INVALID_AUTH` / `INVALID_RECIPIENT_ID` |
| **Conversation ID Format** | `^[a-zA-Z0-9_-]{1,64}$` | `INVALID_CONVERSATION_ID` |
| **Message ID Format** | `^[a-zA-Z0-9_-]{1,128}$` | `INVALID_MESSAGE_ID` |
| **Timestamp Range** | `> 0` and within 24h skew | `INVALID_TIMESTAMP` |
| **Self-Messaging** | Disallowed (`senderId == recipientId`) | `SELF_MESSAGE_NOT_ALLOWED` |
| **LRU Deduplication Capacity** | 10,000 message IDs | In-memory thread-safe LRU cache |

---

## 3. Wire Format Specification (JSON)

All communication occurs over UTF-8 WebSocket text frames.

### 3.1 Client → Server Messages

#### A. AUTH
Sent immediately when a WebSocket connection opens to bind the session to an authenticated user ID.
```json
{
  "type": "AUTH",
  "userId": "user_saad"
}
```
- `userId` *(string, required)*: Matches `^user_[a-zA-Z0-9_-]{1,64}$`.

#### B. SEND_MESSAGE
Dispatched when a user creates or retries sending a message.
```json
{
  "type": "SEND_MESSAGE",
  "messageId": "87a3fae7-cbd8-42af-93d2-6dcc217759e8",
  "conversationId": "conv_alex",
  "recipientId": "user_alex",
  "body": "Hello Alex!",
  "timestamp": 1791367574976
}
```
- `messageId` *(string, 1-128 chars)*: Canonical client UUID.
- `conversationId` *(string, 1-64 chars)*: Target conversation ID.
- `recipientId` *(string, 1-64 chars)*: Target recipient user ID.
- `body` *(string, 1-4096 chars)*: Message content.
- `timestamp` *(long, positive)*: Client epoch timestamp in milliseconds.

#### C. ACK_RECEIVED
Sent by a recipient client upon receiving and successfully persisting an incoming message.
```json
{
  "type": "ACK_RECEIVED",
  "messageId": "87a3fae7-cbd8-42af-93d2-6dcc217759e8"
}
```

#### D. PING
Client keep-alive heartbeat probe.
```json
{
  "type": "PING",
  "timestamp": 1791367574976
}
```

---

### 3.2 Server → Client Messages

#### A. AUTH_OK
Confirms session authentication.
```json
{
  "type": "AUTH_OK",
  "userId": "user_saad"
}
```

#### B. AUTH_ERROR
Indicates authentication rejection. The socket is subsequently closed with code `4001`.
```json
{
  "type": "AUTH_ERROR",
  "reason": "Invalid userId format"
}
```

#### C. MESSAGE_ACCEPTED (Sender Acknowledgement)
Confirms message acceptance by the server relay. Emitted idempotently if re-transmitted.
```json
{
  "type": "MESSAGE_ACCEPTED",
  "messageId": "87a3fae7-cbd8-42af-93d2-6dcc217759e8",
  "timestamp": 1791367574990
}
```

#### D. MESSAGE (Relayed Delivery)
Forwarded to the recipient's active session.
```json
{
  "type": "MESSAGE",
  "messageId": "87a3fae7-cbd8-42af-93d2-6dcc217759e8",
  "conversationId": "conv_alex",
  "senderId": "user_saad",
  "body": "Hello Alex!",
  "timestamp": 1791367574976
}
```

#### E. ERROR
Returned when validation, payload bounds, or routing checks fail.
```json
{
  "type": "ERROR",
  "code": "INVALID_BODY",
  "message": "Message body cannot be empty or blank"
}
```

#### F. PONG
Heartbeat reply from server.
```json
{
  "type": "PONG",
  "timestamp": 1791367574990
}
```

---

## 4. Message State Machine Specification

### 4.1 State Diagram

```
                 ┌───────────────┐
                 │    CREATED    │
                 └───────┬───────┘
                         │ (persisted locally in Room)
                         ▼
     ┌───────────────────────────────────────┐
     │                SENDING                │◄──────────────┐
     │              (UI dot: ·)              │               │
     └───────┬───────────────────────┬───────┘               │
             │                       │                       │
 (MESSAGE_ACCEPTED)           (Network Timeout /      (User Tap Retry)
             │                 Max Backoff Exceeded)         │
             ▼                       │                       │
     ┌───────────────┐               ▼                       │
     │     SENT      │       ┌───────────────┐               │
     │ (UI star: ✦)  │       │    FAILED     │───────────────┘
     └───────┬───────┘       │  (UI red: !)  │
             │               └───────────────┘
      (Future Delivery Receipt)
             ▼
     ┌───────────────┐
     │   DELIVERED   │
     └───────┬───────┘
             │ (Future Read Receipt / Opened)
             ▼
     ┌───────────────┐
     │     READ      │
     └───────────────┘
```

### 4.2 State Transition Matrix & Invariants

| Current State | Permitted Next States | Rejected Transitions | Enforcement Mechanism |
| :--- | :--- | :--- | :--- |
| **SENDING** | `SENT`, `FAILED` | `READ`, `DELIVERED` | `MessageStatus.canTransitionTo` + Room conditional update |
| **SENT** | `DELIVERED`, `READ` | `SENDING`, `FAILED` | State machine validation prevents regress on reconnect |
| **FAILED** | `SENDING` | `SENT`, `DELIVERED`, `READ` | Explicit retry required |
| **DELIVERED** | `READ` | `SENDING`, `SENT`, `FAILED` | Unidirectional monotonic progression |
| **READ** | *(Terminal)* | `SENDING`, `SENT`, `DELIVERED`, `FAILED` | Read status is never overwritten by duplicate relays |

**Regression Protection**:
Incoming relayed messages are inserted via `insertMessageIfNotExists` (`OnConflictStrategy.IGNORE`). If an incoming duplicate packet arrives for a message already marked `READ`, SQLite ignores the insertion, preserving the current state.

---

## 5. Idempotency & Deduplication Engine

### 5.1 Server-Side Deduplication
- **Mechanism**: The backend maintains a thread-safe synchronized LRU cache (`Collections.synchronizedMap` with `LinkedHashMap(10000, 0.75f, true)` overriding `removeEldestEntry`).
- **Capacity**: 10,000 recently processed message IDs.
- **Handling of Duplicates**:
  - When `SEND_MESSAGE(messageId = X)` arrives and `X` is already in the LRU cache:
    1. Server immediately responds with `MESSAGE_ACCEPTED(messageId = X)` to ensure the sender achieves convergence to `SENT`.
    2. Server suppresses redundant relay to recipient sessions.
- **Eviction Behavior**: Oldest message IDs are evicted FIFO once cache exceeds 10,000 entries.

### 5.2 Client-Side Idempotency
- **Room Uniqueness**: Primary key on `messages(id)` guarantees uniqueness at the storage engine level.
- **Monotonic Clock**: Rapid message sends enforce `lastSentTimestamp = maxOf(now, lastSentTimestamp + 1)` so consecutive messages in the same millisecond receive strictly increasing timestamps.
- **Tie-Breaker Query**: Message sorting in queries uses `ORDER BY created_at ASC, id ASC`, ensuring deterministic order across reconnects and process restarts.

---

## 6. Session Replacement & Connection Management

- **Single Active Session Rule**: Each user ID is permitted at most one active WebSocket connection on the relay.
- **Graceful Eviction**: When a new connection authenticates with user ID `U`:
  1. Any existing active session for `U` is cleanly closed with WebSocket close code `4002 ("Replaced by new connection")`.
  2. The session manager replaces the user session mapping atomically.
  3. Reverse mapping (`Session -> UserId`) is updated, preventing memory leaks.
- **Client Reconnection Management**:
  - `AsterWebSocketClient` maintains an atomic instance reference.
  - Previous socket connections are cancelled before opening a new socket.
  - Callbacks from stale or superseded WebSocket instances are discarded, preventing race conditions during reconnection flurries.
  - Exponential backoff: Base 1,000ms, Multiplier 2.0x, Cap 30,000ms.

---

## 7. Comparison: Implemented vs. Future Architecture

| Feature | Implemented (v0.1 Hardened) | Future Roadmap |
| :--- | :--- | :--- |
| **Transport** | Direct WebSocket (Ktor CIO) | WebSocket + HTTP/3 Fallback |
| **Message Ordering** | Strictly monotonic timestamps + UUID tie-break | Lamport / Vector Timestamps |
| **Deduplication** | In-memory 10k LRU Cache + SQLite PK IGNORE | Distributed Key-Value Store / Persistent Store |
| **Server Persistence** | Ephemeral Relay (Zero server-side message storage) | Durable Mailbox for Offline Users |
| **Security / Encryption** | Plaintext UTF-8 over TLS-capable WebSocket | End-to-End Encryption (Signal / MLS Protocol) |
| **State Machine** | `SENDING -> SENT` with regression guards | Full `DELIVERED` & `READ` Receipts |
| **Presence & Typing** | In-memory active session presence | Realtime typing indicators & heartbeat presence |
