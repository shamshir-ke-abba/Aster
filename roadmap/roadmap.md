# ASTER — Roadmap

## Product Goal

Build a polished, highly reliable private messaging application for a trusted friend group of approximately 100 users, with a ₹0 operating-cost target during the initial stage.

The product prioritizes functional, reliable private messaging first before introducing advanced cryptographic features.

---

## E2EE Milestone Decision

### Status: Deferred / Optional

**Reason:**
Aster is currently a small, privately hosted friends chat system. The immediate priority is reliable messaging, persistence, reconnect behavior, usability, and deployment stability.

E2EE may be introduced later if the threat model requires protection of message contents from the server operator or untrusted server infrastructure. E2EE is NOT a prerequisite for Aster to become fully functional and useful.

---

## Canonical Roadmap & Milestone Order

1. **Android messaging foundation** — `DONE`
   - Android Compose client foundation, package identity, Room local persistence, theme system, navigation.
2. **Realtime WebSocket transport** — `DONE`
   - Ktor CIO backend relay, WebSocket connection lifecycle, client message identity, instant server ACKs (`MESSAGE_ACCEPTED`).
3. **Reconnect/outbox reliability** — `DONE`
   - Outbox pattern, Room `SENDING` state persistence, auto-replay on reconnect, exponential backoff, monotonic timestamps.
4. **Persistent tablet backend** — `DONE`
   - Samsung SM-T285 ARMv7 tablet deployment, isolated OpenJDK 17 chroot, Magisk `service.d` auto-start, crash supervisor, `aster-backend` CLI.
5. **Durable SQLite mailbox** — `DONE`
   - Server-side SQLite WAL mailbox, offline queueing (`QUEUED`), restart persistence, deterministic chronological replay, `ACK_RECEIVED` confirmation (`ACKED`).
6. **End-to-end real-user reliability** — `DONE`
   - Two-user end-to-end playtesting (`user_saad`, `user_alex`), offline/reconnection UX, network failure recovery, UI status auditing, unknown conversation handling, manual retry.
7. **Conversation/user experience completion** — `DONE`
   - New conversation creation flow, contact discovery, participant display, conversation deletion/clearing, search.
8. **Transport security hardening** — `DONE`
   - Authenticated TLS/WSS transport, dedicated private PKI (Aster Root CA + server keystore), Netty SSL engine, Android network security configuration & X.509 pinning, zero plaintext downgrade fallback, security regression test suite.
9. **Practical authentication/identity & control plane hardening (M9 & M9.1)** — `DONE`
   - Pre-shared cryptographically random tokens (CSPRNG 256-bit entropy), server SHA-256 hash storage, device-bound sessions, hardware-backed AndroidKeyStore AES-GCM credential storage, server-derived session identity, impersonation prevention in AUTH/SEND/ACK, fail-closed administrative control plane (`/api/provision`, `/api/revoke`) requiring `X-Aster-Admin-Key` with constant-time equality check, mandatory server boot guard (refuses to start without admin key $\ge 16$ chars), strict regex input validation for `userId` and `deviceId`, multi-target revocation (token, user, device) with active WebSocket connection termination, deterministic stale token lifecycle cleanup (`purgeRevokedTokens`), 26-step automated live runtime validation.
10. **Groups / richer conversations** — `DONE`
    - Multi-user constellations, persistent `groups` and `group_members` relational schema with Room migration, server-enforced role authorization (`CREATOR`/`ADMIN`), atomic group lifecycle (create, list, add, remove, leave, rename), backend message fan-out with per-recipient mailbox queueing, deduplicated offline replay, real-time `GROUP_EVENT` broadcasts, Compose New Chat dialog group creation UI with member multi-selection, distinct `✦✦` visual motif, sender display name bubble headers, Room process-death persistence, and automated 20-step playtest over authenticated TLS/WSS on real Waydroid client.
11. **Notifications, Unread State & Push Architecture** — `DONE`
    - Production-minded notification system for direct and constellation group messaging:
      - Android notification foundation: dedicated notification channel (`aster_messages`, `IMPORTANCE_HIGH`), runtime `POST_NOTIFICATIONS` permission handling (API 33+), system shade integration, group aggregation and summary (`com.aster.app.MESSAGES`), privacy-conscious lockscreen preview policy (`VISIBILITY_PRIVATE`), and deep-link tap navigation to target conversation.
      - Dynamic unread count architecture: computed per conversation via Room message status (`DELIVERED` vs `READ`), conversation-level unread badges on HomeScreen, active-chat notification and unread suppression, and continuous read updates.
      - Push strategy & provider abstraction: zero-cost `PushGateway` interface with `NoOpPushGateway`, server-side device push token registry (`push_tokens` table) with automated cleanup on user/device revocation, authenticated endpoint `POST /api/push/register`, and honest classification of background wakeups without external FCM credentials as UNVERIFIED.
11.1. **Fresh-User Usability Gate & Invite Onboarding** — `DONE`
    - First-time user onboarding and authenticated identity repair completed:
      - Authenticated identity binding: eliminated hardcoded `user_saad` assumptions; Room local identity dynamically derived from encrypted Keystore credentials; conversation member filtering and two-account identity isolation verified on device.
      - Minimal invite-token onboarding flow: Aster-native `OnboardingScreen` with celestial theme; support for pasted tokens and `aster://join?token=...` deep links; immediate server credential verification over TLS/WSS; clear error banners for invalid tokens, revoked tokens, malformed links, and unreachable servers; unauthenticated message sending prevention.
      - 68/68 Android unit/regression tests passed, backend test suite passed (0 failures), and 100% automated live verification on Waydroid runtime container.
      - Verified readiness verdict: `READY FOR INVITE-BASED PRIVATE BETA`.
12. **Media/files** — `NEXT`
    - Image compression, file attachment transfers, local media caching and storage management.
13. **E2EE** — `OPTIONAL / FUTURE`
    - Signal/MLS protocol implementation, identity keys, pre-keys, session ratchet, forward secrecy, ciphertext envelopes.

---

## Priority Rule

1. Reliability & data integrity
2. Core messaging & offline outbox
3. Usability & transparent connection UX
4. UI polish & design system
5. Transport security & practical auth
6. Groups & constellations
7. Media & attachments
8. E2EE (optional/future)

Do not add features simply because they are possible. Aster should become a reliable chat application before it becomes an elaborate security research project.

---

## Release Polish — Constellation Navigation UI

Aster will optionally enhance the conversation list with a constellation-based social navigation system once functional reliability is established:

- Direct conversations are represented as connected star pairs.
- Group conversations are represented as small constellations.
- Star positions are deterministic so conversations retain a recognizable location across launches.
- Unread/activity states are represented through subtle glow and restrained visual cues.
- The existing conversation, Room, WebSocket, and backend data models remain underneath the visual system.
- Accessibility and readable conversation names remain available alongside the visual representation.
