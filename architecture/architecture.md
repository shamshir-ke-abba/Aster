# ASTER — Architecture Source of Truth

## 1. Architecture Goal

Aster is a private messaging application intended for approximately 100 users.

The architecture should be simple, cheap to operate, replaceable, testable and compatible with future E2EE.

Initial infrastructure target: **₹0/month**

## 2. High-Level Architecture

```
Android Client
    │
    │ WebSocket
    ▼
Relay Backend
    │
    ▼
Database
```

The backend handles authentication, connections, routing and temporary offline delivery.

## 3. Client

Initial target: Android.

Preferred stack:
- Kotlin
- Jetpack Compose
- Coroutines
- Room
- Android Keystore
- WebSockets
- Android notification APIs

## 4. Backend

Responsibilities:
- Authentication
- User discovery
- WebSocket connections
- Message routing
- Offline message queue
- Delivery acknowledgement
- Basic rate limiting
- Group membership
- Minimal metadata

The backend should remain intentionally small.

## 5. Database

Potential entities:
- users
- devices
- conversations
- conversation_members
- messages
- delivery_state
- sessions

When E2EE is enabled, message content stored server-side should be ciphertext.

## 6. Client Database

The client should maintain local state for conversations, messages, users, groups, delivery state, read state and local settings.

Room is the preferred implementation.

## 7. Message Flow

Initial:
```
Client A → Backend → Client B → Local Database → UI
```

Future E2EE:
```
Client A → Encrypt → Ciphertext → Backend → Ciphertext → Client B → Decrypt
```

## 8. Realtime

Primary realtime mechanism: WebSocket.

Requirements:
- Connection establishment
- Authentication
- Heartbeat
- Reconnection
- Message acknowledgement
- Duplicate protection
- Ordered handling where required

## 9. Offline Behaviour

Messages must not disappear because a device is temporarily offline.

The backend may temporarily retain undelivered messages and remove them after confirmed delivery according to retention policy.

## 10. Backend Abstraction

Conceptual client layers:

UI → ViewModel → Repository → Messaging Service → Network / Local DB

This allows backend infrastructure to be replaced later.

## 11. Scaling Target

Initial intended maximum: approximately 100 users.

Do not prematurely introduce Kubernetes, microservices, service meshes, multi-region deployments or large cloud infrastructure.

## 12. Cost Principle

Prefer free infrastructure during development and private testing.

Do not introduce paid infrastructure unless the free infrastructure becomes insufficient or reliability requires it.

## 13. Security Evolution

The architecture must allow E2EE to be introduced later.

The server should never require user private keys.

## 14. Architecture Rule

Prefer the simplest architecture that reliably solves the current problem. Complexity must have a measurable benefit.
