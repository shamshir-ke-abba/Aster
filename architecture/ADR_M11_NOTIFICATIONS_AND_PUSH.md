# ADR 001: Milestone 11 — Notifications, Unread State & Push Architecture

**Status**: Accepted  
**Date**: 2026-10-09  
**Target Deployment**: Private messaging app for ~100 users, ₹0/month operating budget  

---

## 1. Context & Problem Statement

Aster requires a production-minded notification and unread tracking system for direct and group conversations. The existing stack comprises:
- Android client (Kotlin, Jetpack Compose, Room SQLite local-first persistence, Hilt, OkHttp TLS/WSS)
- Backend relay (Kotlin, Ktor CIO, SQLite durable mailbox, token authentication)

The core challenges are:
1. Supporting modern Android notification standards (API 33+ `POST_NOTIFICATIONS` runtime permission, notification channels, grouping, lock-screen privacy).
2. Distinguishing notification presentation from authoritative message unread state.
3. Operating honestly within modern Android background restrictions (Doze, App Standby, restricted background execution) without using battery-hostile foreground services or continuous polling.
4. Supporting a ₹0/month deployment model where third-party paid infrastructure is unavailable and Firebase credentials are not assumed to exist in the repository.

---

## 2. Decision & Architectural Distinction

### 2.1 Four Operational Environments

| Scenario | Transport & Wakeup Behavior | Notification Behavior | Unread State Behavior |
| :--- | :--- | :--- | :--- |
| **1. App Foreground & Connected** | Active TLS/WSS socket connection receives real-time `MESSAGE` frame. | If active conversation matches incoming message: **SUPPRESSED** (no banner/sound). If viewing home or another chat: **POSTED** to system shade. | If viewing conversation: marked `READ` immediately. If on home/other chat: stored as `DELIVERED` and increments unread badge. |
| **2. App Backgrounded / Process Dead** | OS limits socket retention. Without push credentials, socket is closed to conserve battery. Message safely held in backend SQLite mailbox. | No local notification generated while process is terminated without push provider wakeup. | Preserved durably in Room. Upon next app open, mailbox drain fetches messages, Room stores them, and unread counts update. |
| **3. Device Offline / No Network** | No network connectivity; outbox queues outgoing messages locally in Room. | No notifications can be generated. Existing notifications in shade remain dismissible. | Unread counts and message states remain persistent and durable in Room. |
| **4. Mailbox Replay & Reconnection** | Client connects and authenticates; server replays queued mailbox messages in order. | Suppressed or aggregated: replayed messages do not produce repetitive audio alerts for already known messages. | Deduplicated via Room `insertMessageIfNotExists`. Unread count increments once per unread message. |

### 2.2 Notification Presentation vs. Authoritative Unread State

- **System Notifications are Ephemeral**: A notification in the Android shade is purely an informational alert. Dismissing a notification or receiving one does **not** change message read state.
- **Authoritative Unread State Lives in Room**:
  - `MessageEntity.status`: Incoming messages are inserted with `MessageStatus.DELIVERED`.
  - Unread Count: Computed via `SELECT COUNT(*) FROM messages WHERE conversation_id = :id AND sender_id != :localUserId AND status != 'READ'`.
  - Read Transition: Occurs strictly when the user views the conversation in `ChatScreen` via `markMessagesAsRead(conversationId, localUserId)`.
- **Active Screen Suppression**:
  - `ActiveConversationTracker` maintains `currentActiveConversationId`.
  - When incoming message `conversationId == currentActiveConversationId`, notification generation is bypassed and messages are marked `READ` immediately.

### 2.3 Push Strategy Selection & Honesty

- **Selected Architecture**: Hybrid connected-socket + extensible push gateway provider (`PushGateway` interface).
- **Backend Provider Design**:
  - The backend accepts push registrations (`POST /api/push/register`) associating authenticated `userId` and `deviceId` with a push token.
  - When messages are durably accepted into the mailbox (`DeliveryState.QUEUED`), the server dispatches a lightweight push event to offline recipients.
  - Payloads are **minimal and privacy-preserving**: `{ "conversationId": "...", "senderId": "...", "isGroup": boolean, "timestamp": ... }`.
  - Message bodies are **never** transmitted through third-party push payloads; authoritative body content is fetched strictly over authenticated TLS/WSS.
  - Push delivery failure never blocks durable mailboxing or direct WSS relay.
- **Zero-Cost Private Deployment Reality**:
  - In the absence of bundled Firebase credentials, a mock/logging `NoOpPushGateway` is active by default.
  - UnifiedPush / FCM provider hooks are provided as pluggable implementations.
  - Background push delivery is explicitly labeled **UNVERIFIED** until configured on a device with external push credentials.
  - No permanent foreground service, sticky notification, or aggressive polling loop is introduced.

---

## 3. Security, Privacy & Reliability Properties

1. **Lock-Screen Privacy**: Notifications set `NotificationCompat.VISIBILITY_PRIVATE` to prevent message leaks on locked devices.
2. **Channel Configuration**: Channel `aster_messages` created with `IMPORTANCE_HIGH` for incoming messages, user-controllable in Android system settings.
3. **No Secret Leaks**: No tokens, passwords, or decrypted message bodies are included in logs or third-party push payloads.
4. **Idempotency**: Notification IDs are keyed deterministically by `conversationId.hashCode()`, collapsing multiple messages into structured `MessagingStyle` or `InboxStyle` notifications to avoid notification spam.
