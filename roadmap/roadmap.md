# ASTER — Roadmap

## Product Goal

Build a polished private messaging application for a trusted friend group of approximately 100 users, with a ₹0 operating-cost target during the initial stage.

The product should prioritize usability first and introduce stronger E2EE/security architecture progressively.

---

# Phase 0 — Foundation

- [x] Create Android project
- [x] Establish package/application identity
- [x] Establish Aster design system
- [x] Establish project documentation
- [x] Establish Git repository
- [x] Establish development/build workflow
- [x] Create initial app shell
- [x] Implement theme system
- [x] Implement navigation foundation

# Phase 1 — Core Messaging MVP

## Accounts
- [ ] User registration
- [ ] Login/session persistence
- [ ] Username
- [ ] Display name
- [ ] Avatar
- [ ] Basic profile

## Messaging
- [x] One-to-one conversations (local persistence)
- [x] Send text messages (local persistence)
- [x] Receive text messages (remote)
- [x] Message timestamps
- [x] Conversation list
- [x] Unread indicators
- [x] Message history (Room database)
- [x] Offline message queue (client queue + remote durable SQLite mailbox)
- [x] Server-side SQLite mailbox (WAL mode, crash-recovery, deterministic replay)

## Realtime
- [x] WebSocket connection
- [x] Connection state
- [x] Automatic reconnect
- [x] Server message acknowledgement (MESSAGE_ACCEPTED & ACK_RECEIVED)

# Phase 2 — Groups

- [ ] Create groups
- [ ] Invite members
- [ ] Remove members
- [ ] Group names
- [ ] Group avatars
- [ ] Group message history
- [ ] Group notifications
- [ ] Basic group administration

Target scale: **≤100 total users**

# Phase 3 — Aster Identity

- [ ] Aster star logo
- [ ] App icon
- [ ] Full visual theme
- [ ] Constellation-inspired UI
- [ ] Animated launch
- [ ] Empty states
- [ ] Profile identity
- [ ] Group constellation visuals

# Phase 4 — E2EE

- [ ] Local identity keys
- [ ] Public/private key architecture
- [ ] Device identity
- [ ] Key exchange
- [ ] Encrypted message payloads
- [ ] Message authentication
- [ ] Replay protection
- [ ] Session management
- [ ] Key rotation
- [ ] Forward secrecy
- [ ] Device verification

Use established cryptographic protocols/libraries. Do not invent cryptographic primitives.

# Phase 5 — Media

- [ ] Image messages
- [ ] Image compression
- [ ] File messages
- [ ] Upload/download handling
- [ ] Media caching
- [ ] Media deletion
- [ ] Storage limits

# Phase 6 — Quality

- [ ] Unit tests
- [ ] Integration tests
- [ ] Network failure testing
- [ ] Offline testing
- [ ] Reconnection testing
- [ ] Database migration testing
- [ ] Notification testing
- [ ] Device compatibility testing
- [ ] Performance testing
- [ ] Crash testing

# Phase 7 — Private Release

Target: private friend-group release.

Maximum intended group size: approximately 100 users.

- [ ] Stable messaging
- [ ] Stable authentication
- [ ] Stable notifications
- [ ] Stable group chats
- [ ] Data migration tested
- [ ] Backup/recovery strategy
- [ ] E2EE implementation reviewed
- [ ] Release build tested

# Long-Term Ideas

- Voice messages
- Reactions
- Replies
- Message editing
- Message deletion
- Disappearing messages
- Multiple devices
- Encrypted backups
- Voice calls
- Video calls
- Custom themes
- Stickers
- Rich media

# Priority Rule

1. Reliability
2. Core messaging
3. Usability
4. UI polish
5. E2EE
6. Media
7. Advanced features

Do not add features simply because they are possible.
