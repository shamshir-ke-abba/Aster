# Aster — Phase A: Baseline and Architectural Audit Report

**Date:** 2026-10-11  
**Auditor / Engineer:** Senior Android Engineer, Backend Engineer, QA Lead & Security Auditor  
**Repository:** `/home/saad/Aster` (`com.aster.app`)  
**Git Baseline:** commit `ca6ca22` (`feat: add durable sqlite mailbox`) + Phase 8–13 working-tree changes  
**Automated Test Baseline:**  
- Backend Unit Tests: **69/69 passed** (100%)  
- Android Unit Tests: **74/74 passed** (100%)  
- Total Automated Tests: **143/143 passed** (100%)  

---

## 1. Feature Audit Matrix

| Feature | Classification | Source Files & Handlers | Models & Tables | Existing Tests & Evidence |
| :--- | :---: | :--- | :--- | :--- |
| **1. Constellation chat navigation** | **Partial** | `HomeScreen.kt` (`ConstellationBackground`) | N/A (Canvas rendering only) | Decorative background only; no interactive star pairs for 1:1 or star clusters for groups. |
| **2. Conventional conversation list** | **Implemented** | `HomeScreen.kt` (`ConversationItem`, `LazyColumn`) | `ConversationEntity`, `ConversationDao` | `ViewModelTest.kt`, `HomeScreenTest.kt`, verified in playtests. |
| **3. Open registration** | **Missing** | `OnboardingScreen.kt`, `AsterServer.kt` (`/api/provision`) | `auth_tokens` (SQLite) | Registration currently requires admin invite token (`/api/provision` with `X-Aster-Admin-Key`). |
| **4. Permanent Aster IDs** | **Missing** | N/A | Only arbitrary `user_id` strings (e.g. `user_alex`) | No collision-resistant `AST-XXXXXX` format or unique database constraint. |
| **5. Username/profile management** | **Partial** | `UserEntity.kt`, `UserDao.kt` | Room `users` table | Static sample/seed profiles; no profile editing screen, username check, or backend profile API. |
| **6. Friend search and requests** | **Missing** | `HomeScreen.kt` (`NewChatDialog`) | Only local Room `UserEntity` | New chats only pick from pre-seeded contacts. No backend user search or friend request state machine. |
| **7. Blocking and reporting** | **Missing** | N/A | None | No backend or local blocking tables, no message drop policy for blocked users. |
| **8. Discoverable Settings** | **Missing** | `AsterNavigation.kt` (`AsterRoutes`) | None | `AsterRoutes` only has `ONBOARDING`, `HOME`, `CHAT`. No Settings entry in header or bottom navigation. |
| **9. Account recovery & device mgmt** | **Missing** | `AsterAuthStorage.kt` | `auth_tokens` (SQLite) | Clearing local storage permanently disconnects client without recovery; logout does not revoke server token. |
| **10. Direct and group messaging** | **Implemented** | `MessageRelay.kt`, `AsterWebSocketClient.kt` | `mailbox_messages`, `groups`, `group_members` | 69 backend tests, 74 Android tests, verified live in Phase 13 cellular test. |
| **11. Offline mailbox replay** | **Implemented** | `MailboxDatabase.kt`, `MessageRelay.kt` | `mailbox_messages` (`QUEUED`, `DELIVERED`, `ACKED`) | `MailboxDatabaseTest.kt`, verified over LTE in Phase 13 with durable SQLite recovery. |
| **12. Push / background delivery** | **Partial** | `AsterNotificationManager.kt`, `PushGateway.kt` | `push_tokens` (SQLite) | Local notifications active while process alive; backend uses `NoOpPushGateway` (no zero-budget FCM/distributor). |
| **13. Transport TLS & hostname verify** | **Implemented** | `AsterTlsManager.kt`, `AsterServer.kt` | PKI (`aster_ca.crt`, `aster_server.p12`) | DualTrustManager tested in `AsterSecurityTest.kt`, verified over Cloudflare Quick Tunnel. |
| **14. End-to-end encryption (E2EE)** | **Missing** | `AsterProtocol.kt`, `Protocol.kt` | Plaintext JSON payloads | Messages encrypted in transit via TLS/WSS; server processes plaintext payloads. No ratchet/E2EE protocol. |
| **15. Backups & operational recovery** | **Partial** | `MailboxDatabase.kt` | SQLite WAL (`aster.db`) | WAL journal mode active; server persists across restarts; no automated WAL snapshot or backup script. |

---

## 2. Architecture & Subsystem Inspection

### 2.1 UI & Navigation (`android/app/src/main/kotlin/com/aster/app/ui/`)
- **Theme:** Minimalist dark cosmic palette (`AsterNight = #0F0E17`, `AsterSurface = #161524`, `AsterElevated = #242238`, `AsterViolet = #7F5AF0`, `AsterStar = #FFFFFE`, `AsterGlow = #94A1B2`).
- **Routes:** `AsterRoutes` currently defines only 3 destinations:
  - `ONBOARDING` (`"onboarding"`)
  - `HOME` (`"home"`)
  - `CHAT` (`"chat/{conversationId}"`)
- **Limitation:** Lacks a coherent multi-section navigation structure (Chats, Connections, Profile, Settings) and lacks view toggling between Constellation and List views.

### 2.2 Client Data & Storage (`android/app/src/main/kotlin/com/aster/app/data/`)
- **Local Persistence:** Room Database v1 (`AsterDatabase.kt`) containing `users`, `conversations`, `conversation_members`, `messages`.
- **Credential Storage:** `AsterAuthStorage.kt` using `EncryptedSharedPreferences` for auth token, user ID, and server URL.
- **WebSocket Transport:** `AsterWebSocketClient.kt` managing OkHttp WebSocket, backoff reconnection, and incoming/outgoing frames.

### 2.3 Backend Subsystem (`backend/src/main/kotlin/com/aster/backend/`)
- **Engine:** Netty on Ktor 2.3.12 with TLS on port 8443.
- **Database:** SQLite WAL (`MailboxDatabase.kt`) at Schema Version 2.
  - Tables: `auth_tokens`, `mailbox_messages`, `groups`, `group_members`, `push_tokens`.
  - **Limitation:** Missing a dedicated `users` table, `friend_requests` table, and `blocked_users` table.

---

## 3. Implementation Plan & Execution Phases

### Stage 2: Constellation Navigation & UI Overhaul (Phase B & E layout)
1. Build `ConstellationView.kt`: Deterministic 2D canvas representing 1:1 chats as star pairs and groups as star clusters with member stars, unread activity glows, crimson accents (`#D9383A`), smooth touch handling, and accessible labels.
2. Update `HomeScreen.kt`: Add view toggle (Constellation vs. List view), conversation search, and filters (All, Direct, Groups).
3. Introduce global bottom/tab navigation:
   - **Chats** (Constellation / List)
   - **Connections** (Friend discovery & requests)
   - **Profile** (Aster ID, avatar, username)
   - **Settings** (Account, Privacy, Notifications, Appearance, Storage, Diagnostics)

### Stage 3: Open Registration & Permanent Aster IDs (Phase C)
1. Backend Migration (Version 2 -> Version 3 in `MailboxDatabase.kt`):
   - Add `users` table: `user_id`, `aster_id` (unique, format `AST-XXXXXX`), `username` (unique), `display_name`, `created_at`, `updated_at`.
   - Seed existing test accounts (`user_saad`, `user_alex`, `user_rohan`, `user_priya`) with permanent Aster IDs to prevent data breakage.
2. Backend HTTP API `/api/register`:
   - Validates username & display name, generates collision-resistant Aster ID, creates user, issues token.
   - Rate limiting: max 5 registrations/minute per IP.
3. Android `OnboardingScreen`:
   - Open registration tab ("Create Account") with username & display name inputs.
   - Retains optional invite token / recovery tab.
   - Stores permanent Aster ID upon successful registration.

### Stage 4: Friend Discovery, Requests & Blocking (Phase D)
1. Backend Migration (Schema Version 3):
   - Add `friend_requests` table (`id`, `sender_user_id`, `recipient_user_id`, `status`, `created_at`, `updated_at`).
   - Add `blocked_users` table (`blocker_user_id`, `blocked_user_id`, `created_at`).
2. Backend APIs / WebSocket operations:
   - `/api/users/search?q=...`: Search by exact Aster ID (`AST-...`) or username.
   - Friend request handling: Send, Accept, Reject, Cancel, List requests, List friends.
   - Block/Unblock handling with server-side message delivery denial for blocked users.
3. Android Connections Screen:
   - Search users, send friend requests, view pending incoming/outgoing requests, manage friend list and blocked list.

### Stage 5: Discoverable Settings & Profile (Phase E & F)
1. Android Settings Screen:
   - Account management (display name, username, permanent Aster ID, copy ID, logout with server revocation, delete account).
   - Privacy & Security (blocked list, security info).
   - Notifications (in-app preferences, channel status).
   - Appearance (Constellation / List view preference, reduced motion).
   - Storage (cache size, clear cache).
   - Diagnostics (connection state, endpoint, reconnect button).
2. Android Profile Screen:
   - Permanent Aster ID card, share/copy actions, display name editing.

### Stage 6: Group Management & Messaging Polish (Phase G & H)
1. Group details dialog / screen: View members, add member, remove member (admin only), leave group, rename group.
2. Real-time constellation update when membership changes.
3. Message status accuracy (`SENDING`, `SENT`, `DELIVERED`, `ACKED`, `FAILED`).

### Stage 7: Background Notification Reliability (Phase I)
1. In-app notification preferences and battery optimization diagnostic.
2. Document no-budget push reality and verify durable mailbox replay.

### Stage 8: TLS/Security Review, E2EE ADR & Operational Backups (Phase J & K)
1. Negative tests for untrusted TLS certs and hostname mismatch.
2. ADR for End-to-End Encryption roadmap.
3. SQLite WAL backup procedure script (`scripts/backup_database.sh`).

### Stage 9: Final Regression Testing & Beta Release Readiness (Phase L, M, N)
1. Run all unit tests (Android + Backend).
2. Produce final release report.
