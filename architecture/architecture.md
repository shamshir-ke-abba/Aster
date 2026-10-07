# ASTER — Architecture Source of Truth

## 1. Architecture Goal

Aster is a private messaging application intended for approximately 100 users.

The architecture is simple, zero-cost to operate, modular, testable, and strictly compatible with future E2EE.

Initial infrastructure target: **₹0/month**

---

## 2. High-Level Realtime Architecture

```
Android Client (User A)         Aster Backend Relay (Ktor CIO)        Android Client (User B)
       │                                     │                                    │
       │────── WebSocket (ws://host:8080/ws) ┼ ────── WebSocket (ws://host:8080/ws) │
       │                                     │                                    │
       ▼                                     ▼                                    ▼
Room SQLite Database                   In-Memory Sessions                   Room SQLite Database
 (Local-first state)                    (Ephemeral Relay)                    (Local-first state)
```

The backend handles:
- Developer/Client authentication handshakes (`AUTH` / `AUTH_OK`)
- WebSocket connection lifecycle & heartbeat ping/pongs
- Message identity verification and deduplication
- Instant sender acknowledgements (`MESSAGE_ACCEPTED`)
- Ephemeral realtime message relaying (`MESSAGE`) to recipient active sessions

Persistent message state is **local-first**; Android Room remains the authoritative store.

---

## 3. Client Architecture

**Platform**: Android (Kotlin, Jetpack Compose, Room, Hilt, Coroutines, Flow).

### Layered Separation
```
Compose UI (HomeScreen / ChatScreen)
    ↓
ViewModel (HomeViewModel / ChatViewModel)
    ↓
Domain Repository (MessageRepository, UserRepository, ConversationRepository)
    ↓                                              ↓
Local Data Source (Room Database & DAOs)    Network Layer (AsterWebSocketClient, OkHttp)
```

### Local-First Network Principles
1. **Never block UI or Room writes on network availability**: When sending a message, it is immediately written to Room with status `SENDING` (`·`), and UI observers update instantaneously.
2. **WebSocket sits strictly behind `MessageRepository`**: ViewModels and Compose screens never directly talk to WebSockets or HTTP endpoints.
3. **Message Identity**: Every message ID is generated on the client device before transmission and preserved by the backend and receiving clients.
4. **Reconciliation & ACK**:
   - Sender receives `MESSAGE_ACCEPTED` -> `MessageRepository` updates Room message status to `SENT` (`✦`).
   - Receiver receives `MESSAGE` -> `MessageRepository` persists it into Room and updates conversation unread count and preview.
5. **Offline Queue & Reconnection**: If the network is disconnected, messages remain in `SENDING` state. Upon WebSocket reconnection and authentication, `MessageRepository` queries pending messages (`status == SENDING`) and replays them over the wire.

---

## 4. Backend Relay Architecture

**Technology**: Kotlin 2.0.21, Ktor 2.3.12 (CIO Engine), Kotlinx Serialization.

- **Lightweight Footprint**: Memory footprint < 60 MB JVM, single-jar or containerized deployment.
- **Session Management (`SessionManager`)**: In-memory concurrent maps indexing `userId -> Set<WebSocketSession>` and `WebSocketSession -> userId`.
- **Deduplication**: Bounded LRU memory cache tracking the last 10,000 processed message IDs. Re-sent messages receive immediate `MESSAGE_ACCEPTED` ACKs without duplicate relay.
- **Endpoints**:
  - `GET /health`: JSON status reporting active user and connection counts.
  - `GET /ws`: Duplex WebSocket endpoint for Aster protocol.
- **Hardware / Edge Deployment**: Validated persistent deployment on ARMv7 devices (Samsung SM-T285, Android 7.1.2) utilizing an isolated userspace chroot (OpenJDK 17), Magisk `service.d` boot hooks, and an automatic process supervisor (`aster-backend`).

---

## 5. Security & Future E2EE Compatibility

1. **Envelope Structure**: The JSON protocol defines `body` as a generic container string. When E2EE is activated, this string becomes a base64-encoded ciphertext envelope with cryptographic headers without requiring changes to routing (`messageId`, `conversationId`, `recipientId`).
2. **Server Trust Boundary**: The server does not store user private keys or require message plaintext inspection.

---

## 6. Definition of Layers & Interfaces

| Component | Responsibility |
| :--- | :--- |
| `AsterWebSocketClient` | Manages raw OkHttp WebSocket connection, auto-reconnect with exponential backoff, and state flows. |
| `MessageRepositoryImpl` | Coordinates between `MessageDao`, `ConversationDao`, and `AsterWebSocketClient`. |
| `SessionManager` | Manages active server-side sessions, connection tracking, and deduplication. |
| `MessageRelay` | Validates wire messages, returns ACKs, and forwards messages to target recipient sessions. |
