# ASTER — Canonical Product & Release Roadmap

## Product Vision & Goal

Build a polished, highly reliable, and delightful private messaging application for a trusted friend group of approximately 100 users, operating with a ₹0 infrastructure cost on a modern serverless edge architecture.

Aster is anchored in a distinctive aesthetic: **a private constellation of people**.
- People are points of light.
- Conversations are connections.
- Groups are constellations.
- Aster is the calm, midnight space connecting them.

The development philosophy follows four strict stages:
1. **Simple first**
2. **Reliable second**
3. **Polished third**
4. **Scale when needed**

---

## Current Verified Status (October 2026)

Aster has successfully completed its foundational engineering and passed live production verification against the deployed Cloudflare backend (`https://aster-backend-cloudflare.kmsaadbinafzal.workers.dev` and `wss://aster-backend-cloudflare.kmsaadbinafzal.workers.dev/ws`):

- **Automated Verification:** 85/85 Android unit and repository tests passing (`RepositoryTest`: 17/17).
- **Production Backend:** Cloudflare Workers + Durable Objects (`AsterRelayDO`, `GroupDO`) + D1 SQLite operational.
- **Two-Client Production Playtest:** **PASS**
  - **Client A (Waydroid `192.168.240.112:5555`):** User A on local Wi-Fi.
  - **Client B (Samsung Galaxy M21 `RZ8N70Z9BEZ`):** User B on Airtel 4G/LTE cellular with Wi-Fi **strictly disabled**.
  - Verified across public Internet: fresh registration (`/api/register`), permanent Aster IDs (`AST-28S4C9`, `AST-JKTC2B`), user discovery, friend requests & acceptance, mutual 1:1 conversation initialization, Room foreign-key defensive creation, bidirectional real-time messaging, bidirectional offline mailbox queueing & replay, recipient ACK drainage (`ACK_RECEIVED`), duplicate replay prevention, and multi-message cold-start persistence.

---

## Canonical Release Roadmap (Phases 1 — 11)

```
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 1: Core Messaging Foundation                         [ COMPLETE ]│
│   Registration, WSS, 1:1 Chat, Cloudflare DO, LTE Playtest             │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 2: Product Completeness                       [ NEXT / IN PROG ] │
│   Profile/Settings, Contact Management, Conversation UX Polish         │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 3: Constellation Navigation Canvas                  [ PLANNED ]  │
│   Binary stars (1:1), clusters (groups), pan/zoom, hit targets         │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 4: Full End-to-End Groups & Constellations          [ PLANNED ]  │
│   Cloudflare GroupDO integration, lifecycle, member roles, fanout      │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 5: Notifications & Background Operation             [ PLANNED ]  │
│   System shade, lock screen, deep links, lifecycle, cellular wakeups   │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 6: Security & Privacy Pre-Release Audit             [ PLANNED ]  │
│   Keystore tokens, TLS pinning, authorization, abuse/rate limits       │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 7: Reliability & Deliberate Failure Testing         [ PLANNED ]  │
│   Network drops, process death during send/recv, reconnect fuzzing     │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 8: Beta & Scale Readiness (~100 Users)              [ PLANNED ]  │
│   10 → 25 → 50 → 100 concurrent benchmarking, DO CPU/memory, ₹0 cost   │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 9: Release Candidate Preparation                    [ PLANNED ]  │
│   App icon, splash, signing, AAB, ProGuard/R8, device matrix           │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 10: Private Beta Rollout                            [ PLANNED ]  │
│   Staged friends rollout (5 → 10 → 25 → 50+), feedback loop            │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│ Phase 11: Aster 1.0 Release Gate                          [ PLANNED ]  │
│   Final verification checklist & General Availability release          │
└────────────────────────────────────────────────────────────────────────┘
```

---

### Phase 1 — Core Messaging Foundation

**Status:** `COMPLETE`  
**Verified:** 2026-10-21 (Automated: 85/85 tests; Live Production: Two-Client Playtest PASS)

- [x] **Registration:** User onboarding via production Cloudflare `/api/register` with cryptographic password hashing.
- [x] **Authentication & Session Restoration:** Hardware-backed AndroidKeyStore credential storage, token persistence, auto-connect on launch, server-side `AUTH` validation, and single active session enforcement.
- [x] **Permanent Aster IDs:** Deterministic assignment and persistent mapping of unique IDs (`AST-XXXXXX`).
- [x] **User Discovery & Search:** In-app search by username and Aster ID via `/api/users/search`.
- [x] **Friend Request Flow:** Mutual friend discovery, request dispatch, incoming request badges, accept/decline API handling.
- [x] **1:1 Direct Conversations:** Deterministic conversation ID generation (`conv_{sortedId1}_{sortedId2}`) and relational Room schema.
- [x] **Real-Time Messaging:** Instant WebSocket message dispatch over `wss://aster-backend-cloudflare.kmsaadbinafzal.workers.dev/ws` with immediate sender acknowledgements (`MESSAGE_ACCEPTED`).
- [x] **Offline Mailbox:** Server-side durable SQLite queueing in `AsterRelayDO` for disconnected recipients with deterministic chronological replay on reconnect.
- [x] **Recipient Delivery & ACK Handling:** Recipient emission of `ACK_RECEIVED` upon Room persistence, triggering server-side mailbox cleanup.
- [x] **Idempotency & Duplicate Prevention:** DB-enforced message ID uniqueness (`insertMessageIfNotExists`) preventing duplicate rows during retries or network replays.
- [x] **Local Persistence & Outbox Recovery:** Room SQLite database storing all messages locally; automatic replay of unacknowledged `SENDING` messages on connection recovery.
- [x] **Reconnection Architecture:** Exponential backoff with jitter, non-blocking coroutine streams, and connection state observation.
- [x] **Production Cloudflare Backend:** Production-grade deployment using Cloudflare Workers, Durable Objects (`AsterRelayDO`, `GroupDO`), and D1 SQLite.
- [x] **Strict Transport Security:** Mandatory HTTPS and authenticated WSS with standard Android system CA validation; zero plaintext fallback; no trust-all bypasses.
- [x] **Cross-Network Verification:** Verified end-to-end between Client A (Waydroid x86_64 on local Wi-Fi) and Client B (Samsung Galaxy M21 SM-M215F on Airtel 4G/LTE cellular with Wi-Fi disabled).
- [x] **Room Foreign-Key Fix:** `MessageRepositoryImpl.ensureConversationExists()` hardened to create missing conversation and member records before inserting outgoing messages.

---

### Phase 2 — Product Completeness

**Status:** `NEXT / IN PROGRESS`  
**Focus:** Elevate the verified messaging core into a complete, polished, user-ready client experience.

#### 1. Profile & Settings
- [ ] **Profile Screen:**
  - Dedicated Profile view displaying user's avatar badge, Display Name, Username (`@handle`), and permanent Aster ID (`AST-XXXXXX`).
  - Copy-to-clipboard shortcut for Aster ID with toast notification.
  - Session and account metadata (created date, device identity).
- [ ] **Settings Screen:**
  - Navigation entry point in bottom bar.
  - Live Connection Status indicator (`CONNECTED`, `CONNECTING`, `OFFLINE`) with server endpoint info.
  - Clear cache and local storage management options.
  - Account Reset / Logout flow: revoke server token, clear AndroidKeyStore credentials, purge local database, and return to Onboarding.
  - "About Aster" dialog, open-source acknowledgements, and build version code/name display.

#### 2. Contact Management
- [ ] **Contacts & Connections Screen:**
  - Dedicated screen with tabbed sections: **Contacts**, **Requests**, and **Blocked**.
  - Badge counters on tabs reflecting real-time request counts.
- [ ] **Request Interactions:**
  - Incoming requests list with prominent "Accept" and "Decline" buttons.
  - Pending outgoing requests list with "Cancel Request" action.
  - Live feedback banner on action success or failure.
- [ ] **Friend Actions:**
  - Direct "Message" action from contact card navigating straight into the 1:1 conversation.
  - "Remove Friend" and "Block User" actions with confirmation dialogs.
  - "Blocked Users" management tab with "Unblock" capability.
- [ ] **Edge Case & Defensive Handling:**
  - Helpful empty states ("No connections yet. Search for friends above to connect!").
  - Defensive rejection of self-friend requests and duplicate pending requests.

#### 3. Conversation UX Polish
- [ ] **Conversation List:**
  - Real-time conversation list displaying other member's display name, Aster ID, and online indicator dot.
  - Snippet preview of last message, format-relative timestamp (e.g. `2:06 PM`, `Yesterday`), and unread message counter badge.
  - Swipe or long-press actions to delete or clear local conversation history.
- [ ] **New Conversation Flow:**
  - Polished Floating Action Button (FAB) or header action to start a chat.
  - User selection dialog listing confirmed friends.
- [ ] **Message Composer & Keyboard Management:**
  - Smooth Compose text input with clear button and send action.
  - Keyboard visibility handling preventing occlusion of recent messages.
  - Input trim validation (prevent sending whitespace-only messages).
- [ ] **Message State UX:**
  - Clear visual states on every message bubble: Sending (`·`), Sent (`✦`), Failed (`!`).
  - Inline retry button on tap for failed messages.
  - Offline banner when network is unavailable indicating messages will send automatically upon reconnection.
  - Automatic `markConversationAsRead` dispatch when user views an active chat.

---

### Phase 3 — Constellation Navigation

**Status:** `PLANNED`  
**Focus:** Solidify Aster’s signature visual identity—the constellation navigation system—into an intuitive, accessible, and delightful spatial communication canvas.

- [ ] **Constellation Design System:**
  - Represent 1:1 conversations as binary-star pairs orbiting a common barycenter.
  - Represent group conversations as star clusters with connecting celestial lines.
  - Assign deterministic coordinates derived from conversation IDs so spatial positions remain recognizable across app restarts.
- [ ] **Interactive Spatial Canvas:**
  - Smooth pan and pinch-to-zoom gestures on the Compose celestial canvas.
  - Generous touch hit targets around star nodes for effortless selection on mobile touchscreens.
  - Node hover / tap highlights showing participant name, Aster ID, and latest snippet preview.
  - Tapping a constellation node opens the conversation directly.
- [ ] **Dynamic Activity Indicators:**
  - Subtle glowing pulse animation on stars with unread messages.
  - Visual distinction between online and offline contacts (brightness and star magnitude).
- [ ] **Empty State & Accessibility:**
  - Calm, welcoming empty constellation canvas with guided prompts to connect with friends.
  - Seamless toggle between **Constellation Map** and **List View** to ensure 100% usability and accessibility for users who prefer standard list navigation.

---

### Phase 4 — Groups & Constellations

**Status:** `PLANNED`  
**Focus:** Complete end-to-end multi-user group chat leveraging the existing Cloudflare `GroupDO` backend implementation.

- [ ] **Group Lifecycle & Creation:**
  - Compose New Chat dialog group creation mode: enter group name, select multiple friends from contacts list.
  - Client sends `CREATE_GROUP` protocol frame; server provisions persistent `GroupDO` instance.
  - Group given unique identifier (`group_<uuid>`) and timestamp.
- [ ] **Group Management & Administration:**
  - Server-enforced role hierarchy: `CREATOR`, `ADMIN`, `MEMBER`.
  - Group Info screen: display member list with roles, creation date, and group name.
  - Admin controls: Add Members, Remove Members, Rename Group.
  - Member actions: Leave Group.
  - Broadcast of `GROUP_EVENT` frames to all members updating local Room membership in real time.
- [ ] **Group Messaging & Delivery Fan-out:**
  - Outgoing group message sent to Cloudflare `GroupDO`.
  - Backend fans out message to each member's active WebSocket or queues in their `AsterRelayDO` offline mailbox.
  - Distinct sender display name headers on chat bubbles inside group conversations.
  - Individual recipient `ACK_RECEIVED` tracking.
- [ ] **Persistence & Reconnection:**
  - Room `groups` and `group_members` tables populated and updated automatically.
  - Client queries `getGroups` and `getGroupMembers` on WebSocket reconnect to reconcile any membership changes while offline.
- [ ] **Multi-Device Group Verification:**
  - Verification across 3+ clients (Waydroid, physical Samsung M21, and additional test client) confirming fan-out, offline replay, and membership updates.

---

### Phase 5 — Notifications & Background Operation

**Status:** `PLANNED`  
**Focus:** Deliver reliable background message alerts without compromising device battery life or leaking privacy.

- [ ] **System Notifications:**
  - Dedicated Android notification channel (`aster_messages`, `IMPORTANCE_HIGH`).
  - Runtime `POST_NOTIFICATIONS` permission prompt flow on Android 13+ (API 33).
  - Rich notifications displaying sender display name, Aster ID, and message text preview.
  - Group notification stacking using `NotificationCompat.InboxStyle` and conversation summaries.
- [ ] **Deep-Link Navigation:**
  - Tapping a system notification launches `MainActivity` with `EXTRA_CONVERSATION_ID` and opens the target chat immediately.
  - Back stack handling: pressing back returns user to the conversation list, not exiting the app.
- [ ] **Privacy on Lock Screen:**
  - Configure `VISIBILITY_PRIVATE` so sensitive message previews are concealed on locked screens according to user preference.
- [ ] **Background Socket Lifecycle & Power Efficiency:**
  - Detect app foreground/background transitions cleanly.
  - Graceful WebSocket keepalive ping/pong intervals tailored to cellular radio power states.
  - Handle cellular network handoffs (Wi-Fi ↔ LTE) with automatic, quiet session resumption.
  - Offline mailbox fallback: when app wakes from background, automatically drain queued messages via WSS without requiring user intervention.
- [ ] **Hardware Verification:**
  - Thorough testing on physical Samsung M21 running Android 13 to verify background delivery and battery optimization exemptions.

---

### Phase 6 — Security & Privacy Pre-Release Audit

**Status:** `PLANNED`  
**Focus:** Rigorous, zero-compromise security audit of all authentication, communication, and storage layers prior to public availability.

- [ ] **Authentication & Credential Audit:**
  - Verify CSPRNG 256-bit entropy of all issued authentication tokens.
  - Validate encrypted storage inside AndroidKeyStore (AES-256-GCM) with no fallback to plaintext files.
  - Verify token revocation on server immediately severs active WebSocket connections and rejects further requests.
  - Validate fail-closed handling of malformed, expired, or tampered tokens.
- [ ] **Transport & Network Security:**
  - Verify strict TLS 1.3/1.2 on all HTTPS and WSS connections.
  - Confirm hostname verification and CA certificate path validation cannot be bypassed in release builds.
  - Verify rejection of plaintext HTTP or WS attempts.
- [ ] **Authorization & Access Control:**
  - Verify users cannot send messages to conversations they do not belong to.
  - Verify users cannot view or manipulate friends lists or profiles of non-connected users.
  - Verify group administrative actions (add/remove/rename) strictly require `CREATOR` or `ADMIN` roles in `GroupDO`.
  - Validate protection of administrative endpoints with constant-time token comparison (`X-Aster-Admin-Key`).
- [ ] **Input Validation & Abuse Protection:**
  - Strict regex and length validation on usernames, display names, Aster IDs, and message bodies.
  - Cloudflare rate limiting on registration, search, and friend-request endpoints to prevent enumeration and denial-of-service.
- [ ] **Binary & Secrets Review:**
  - Ensure zero hardcoded API keys, private certificates, or development credentials in the codebase or APK.
  - Review ProGuard/R8 release rules to ensure code stripping and obfuscation.
  - Verify no sensitive message contents or auth tokens are written to Android Logcat in release builds.

---

### Phase 7 — Reliability & Deliberate Failure Testing

**Status:** `PLANNED`  
**Focus:** Deliberately stress, break, and recover the system under extreme network and process conditions.

- [ ] **High-Frequency & Concurrency Testing:**
  - Rapid bursts of 50+ messages in 1:1 and group chats to verify wire ordering and SQLite WAL write concurrency.
  - Concurrent sends from both devices simultaneously to test race conditions.
- [ ] **Network Adversity & Partitioning:**
  - Abrupt Wi-Fi / cellular disconnection midway through message transmission: verify outbox holds `SENDING` state and replays upon reconnection.
  - Rapid alternating network switches (airplane mode toggles): verify WebSocket client does not spawn redundant connections or leak sockets.
  - High packet loss and high latency simulations.
- [ ] **Process Termination & Crash Recovery:**
  - Force-killing the Android application while sending: verify on next launch that unsent messages remain in outbox and transmit successfully.
  - Force-killing the Android application while receiving offline replay: verify unacknowledged messages are safely replayed and not dropped.
- [ ] **Cold Device Reboot:**
  - Rebooting physical devices and verifying that local SQLite databases remain pristine without corruption.
- [ ] **Protocol Fuzzing:**
  - Injecting corrupted JSON, oversized payloads, invalid timestamps, and unsupported frame types: verify both client and Cloudflare DOs fail closed without crashing.

---

### Phase 8 — Beta / Scale Readiness (~100 Users)

**Status:** `PLANNED`  
**Focus:** Validate backend capacity, resource utilization, and operational cost boundaries for Aster’s 100-user target.

- [ ] **Progressive Load Milestones:**
  - Benchmark 10 simulated concurrent users.
  - Benchmark 25 simulated concurrent users.
  - Benchmark 50 simulated concurrent users.
  - Benchmark 100 simulated concurrent users with continuous background messaging.
- [ ] **Performance & Resource Metrics:**
  - Measure 50th, 95th, and 99th percentile end-to-end message latency across Cloudflare edge nodes.
  - Monitor Durable Object CPU time and memory consumption per connection.
  - Verify Cloudflare WebSocket Hibernation API behavior to maximize concurrent connections per DO.
- [ ] **Storage & Retention Hygiene:**
  - Monitor D1 and DO SQLite storage growth.
  - Verify automated pruning of acknowledged mailbox entries (`ACKED`) and expired tokens.
- [ ] **Operating Cost Validation:**
  - Confirm overall resource usage fits squarely within Cloudflare Workers free / low-tier limits, satisfying the **₹0 operating-cost** goal.

---

### Phase 9 — Release Candidate

**Status:** `PLANNED`  
**Focus:** Product finishing, branding, release packaging, and hardware verification.

#### 1. Product & Visual Polish
- [ ] Final vector app icon and Android adaptive icons (monochrome, themed).
- [ ] Refined splash screen adhering to Aster's celestial midnight motif.
- [ ] Complete typography, spacing, and color contrast audit across AMOLED screens.
- [ ] Harmonious empty, loading, and error states for all screens.
- [ ] Accessibility review: screen reader semantics, touch target sizes ($\ge 48\text{dp}$), dynamic text scaling.

#### 2. Android Release Packaging
- [ ] Configure production release signing with dedicated keystore.
- [ ] Build and verify release APK and Android App Bundle (`.aab`).
- [ ] Set definitive `versionCode` and `versionName` (e.g. `1.0.0-rc1`).
- [ ] Verify production endpoint configuration (`NetworkConfig.serverUrl`) is hardcoded to production Cloudflare domain.
- [ ] Strip all development test flags, debug tools, and mock seed data.
- [ ] Verify R8 shrinking and ProGuard mapping files.

#### 3. Device Compatibility Matrix
- [ ] **Samsung Galaxy M21 (SM-M215F):** Android 13 (One UI 5), Exynos 9611 (ARM64), physical cellular LTE.
- [ ] **Waydroid:** Android 11, x86_64 emulator environment.
- [ ] **Additional Physical Device:** Secondary ARM64 Android device on disparate Wi-Fi network.

---

### Phase 10 — Private Beta

**Status:** `PLANNED`  
**Focus:** Controlled rollout to real users in the trusted friend group, iterating rapidly on user feedback.

- [ ] **Staged Cohort Rollout:**
  - **Cohort 1:** 5 core users (verify onboarding, basic messaging, friend discovery).
  - **Cohort 2:** 10 users (verify group creation, notifications, constellation canvas).
  - **Cohort 3:** 25 users (verify daily use, battery observations, cellular transitions).
  - **Cohort 4:** 50+ users (full friend group scale).
- [ ] **Structured Feedback Channels:**
  - Gather real-world user feedback on:
    - Ease of registration and finding friends via Aster IDs.
    - Notification timeliness and reliability on different Android OEM flavors.
    - Constellation navigation feel vs. traditional list navigation.
    - Confusion points, edge cases, and unexpected UI behaviors.
- [ ] **Rapid Bug-Fix Sprints:**
  - Triage, patch, and release hotfix updates for any issues discovered during beta testing.

---

### Phase 11 — Aster 1.0 Release Gate

**Status:** `PLANNED`  
**Focus:** Final gate review and sign-off for Aster 1.0 General Availability.

Before declaring Aster 1.0 ready for general availability, all criteria must be satisfied:

| Release Gate Check | Requirement | Status |
| :--- | :--- | :--- |
| **Registration & Auth** | Real-world account creation, token storage, and session resumption | Verified (Phase 1) |
| **Friend System** | User search, bidirectional requests, acceptance, contact management | Verified (Phase 1) |
| **1:1 Messaging** | Real-time delivery, Room persistence, outbox recovery | Verified (Phase 1) |
| **Offline Delivery** | Cloudflare mailbox storage, ordered replay, ACK drainage | Verified (Phase 1) |
| **Cross-Network** | Verified across separate physical networks (Wi-Fi ↔ LTE) | Verified (Phase 1) |
| **Product Polish** | Profile, Settings, Contacts, and Conversation UX complete | Phase 2 Gate |
| **Constellation** | Spatial canvas interactive, intuitive, with list fallback | Phase 3 Gate |
| **Group Chat** | Full group creation, administration, and fan-out operational | Phase 4 Gate |
| **Notifications** | Background push/alerts functional with deep links | Phase 5 Gate |
| **Security Audit** | Token lifecycle, TLS enforcement, authorization verified | Phase 6 Gate |
| **Reliability** | Zero regressions under failure and stress testing | Phase 7 Gate |
| **Scale** | Stable operation up to 100 concurrent users at ₹0 cost | Phase 8 Gate |
| **Packaging** | Signed Release AAB generated, R8 verified, no debug leaks | Phase 9 Gate |
| **Beta Sign-Off** | Private beta completed with no critical bugs | Phase 10 Gate |

Upon passing all gates, the release tag `v1.0.0` will be minted and Aster 1.0 officially launched.

---

## Historical Milestones Archive (Foundational Development)

The following milestones document the historical implementation track that established Aster's initial foundation:

1. **Android Messaging Foundation (M1)** — `DONE`  
   Android Compose client foundation, package identity, Room local persistence, theme system, navigation.
2. **Realtime WebSocket Transport (M2)** — `DONE`  
   WebSocket connection lifecycle, client message identity, instant server ACKs (`MESSAGE_ACCEPTED`).
3. **Reconnect / Outbox Reliability (M3)** — `DONE`  
   Outbox pattern, Room `SENDING` state persistence, auto-replay on reconnect, exponential backoff, monotonic timestamps.
4. **Persistent Tablet Backend (M4)** — `DONE`  
   Samsung SM-T285 ARMv7 tablet deployment, isolated OpenJDK 17 chroot, Magisk `service.d` auto-start, crash supervisor, `aster-backend` CLI.
5. **Durable SQLite Mailbox (M5)** — `DONE`  
   Server-side SQLite WAL mailbox, offline queueing (`QUEUED`), restart persistence, deterministic chronological replay, `ACK_RECEIVED` confirmation (`ACKED`).
6. **End-to-End Real-User Reliability (M6)** — `DONE`  
   Two-user end-to-end playtesting (`user_saad`, `user_alex`), offline/reconnection UX, network failure recovery, UI status auditing, manual retry.
7. **Conversation / User Experience Completion (M7)** — `DONE`  
   New conversation creation flow, contact discovery, participant display, conversation deletion/clearing, search.
8. **Transport Security Hardening (M8)** — `DONE`  
   Authenticated TLS/WSS transport, dedicated private PKI (Aster Root CA + server keystore), Netty SSL engine, Android network security configuration & X.509 pinning, zero plaintext downgrade fallback, security regression test suite.
9. **Practical Authentication / Identity & Control Plane Hardening (M9 & M9.1)** — `DONE`  
   Pre-shared cryptographically random tokens (CSPRNG 256-bit entropy), server SHA-256 hash storage, device-bound sessions, hardware-backed AndroidKeyStore AES-GCM credential storage, server-derived session identity, impersonation prevention in AUTH/SEND/ACK, administrative control plane (`/api/provision`, `/api/revoke`) requiring `X-Aster-Admin-Key` with constant-time equality check, mandatory server boot guard, strict regex input validation, deterministic stale token lifecycle cleanup, 26-step automated live runtime validation.
10. **Groups / Richer Conversations (M10)** — `DONE`  
    Multi-user constellations, persistent `groups` and `group_members` relational schema with Room migration, server-enforced role authorization (`CREATOR`/`ADMIN`), atomic group lifecycle (create, list, add, remove, leave, rename), backend message fan-out with per-recipient mailbox queueing, deduplicated offline replay, real-time `GROUP_EVENT` broadcasts, Compose New Chat dialog group creation UI with member multi-selection, distinct `✦✦` visual motif, sender display name bubble headers, Room process-death persistence, and automated 20-step playtest on real Waydroid client.
11. **Notifications, Unread State & Push Architecture (M11)** — `DONE`  
    Android notification channel (`aster_messages`, `IMPORTANCE_HIGH`), runtime `POST_NOTIFICATIONS` permission handling (API 33+), system shade integration, group aggregation and summary, privacy-conscious lockscreen preview policy, deep-link tap navigation, dynamic unread count architecture, zero-cost `PushGateway` interface, and push token registry.
11.1. **Fresh-User Usability Gate & Invite Onboarding (M11.1)** — `DONE`  
    Eliminated hardcoded user assumptions; dynamic identity binding from encrypted Keystore credentials; minimal invite-token onboarding flow; `OnboardingScreen` with celestial theme; 68/68 Android unit/regression tests passed; 100% automated live verification on Waydroid runtime container.

---

## Post-1.0 / Future Extensions (Backlog)

The following features remain intentional future directions once Aster 1.0 achieves rock-solid real-world adoption:

- **Media & File Attachments:**  
  Client-side image compression, encrypted file transfers, local media caching, and storage quota management.
- **End-to-End Encryption (E2EE):**  
  Signal / MLS protocol implementation, pre-key bundles, cryptographic session ratchets, forward secrecy, and ciphertext envelopes. (See [ADR_E2EE_ROADMAP.md](file:///home/saad/Aster/architecture/ADR_E2EE_ROADMAP.md)).
- **Multi-Device Sync:**  
  Simultaneous multi-device access per user with cryptographic session fan-out and ratchet synchronization.

---

## Priority & Execution Rules

1. **Reliability & Data Integrity First:** Never lose or corrupt a message. Local Room persistence must always be authoritative.
2. **Transparent Networking:** Handle disconnections, reconnections, and cellular transitions gracefully without interrupting the user.
3. **Simple & Focused:** Do not build premature infrastructure. Every feature must serve the 100-user release target.
4. **Distinctive Identity:** The constellation spatial navigation is Aster’s heart; polish it with care and maintain a fast list-view fallback for accessibility.
5. **Fail-Closed Security:** Never weaken TLS, never disable SQLite foreign keys, and never compromise authentication to make tests pass.
