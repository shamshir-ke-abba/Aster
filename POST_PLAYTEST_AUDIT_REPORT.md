# ASTER — POST-PLAYTEST FIX AUDIT & PRIVATE BETA RELEASE GATE REPORT

**Date:** 2026-10-10  
**Repository:** `/home/saad/Aster`  
**Current Git Commit:** `ca6ca22 feat: add durable sqlite mailbox` (Dirty working tree preserved; uncommitted changes intact)  
**Evaluator Roles:** Senior Android Engineer, Backend Engineer, QA Lead, Security Auditor  

---

## 1. EXECUTIVE SUMMARY & VERDICT

### **Final Release Gate Verdict: READY WITH DOCUMENTED LIMITATIONS**

Following the successful execution of the two-client playtest across physical Android hardware (Samsung Galaxy M21, Android 15 / API 35) and virtual container (Waydroid, Android 13 / API 33), a comprehensive read-first, evidence-driven audit was conducted on the uncommitted working state, the three fix groups, the background message delivery model, and the system security posture.

### Key Audit Findings
1. **Fix Group Validation:** All three applied fix groups (LAN endpoint persistence, activity-lifecycle-tracked notification suppression, and `singleTop` notification navigation) are verified functional and resilient across process cold restarts, activity recreation, and network reconnects.
2. **Security Defect Identified & Remediated:** An audit of deep-link handling revealed that incoming `aster://join?server=...` links did not guard against execution on already-provisioned devices. This could have allowed external browsable links to overwrite the configured server endpoint of an authenticated session. A strict guard (`if (authStorage.isProvisioned()) return`) and strict URI parsing (`java.net.URI`) were introduced to guarantee authenticated traffic cannot be redirected to arbitrary hosts.
3. **Automated Verification:** 138 automated unit tests passed with 0 failures:
   - **Backend:** 69 / 69 passed (Protocol, Auth, Sessions, SQLite WAL Mailbox, Relays, Groups)
   - **Android:** 69 / 69 passed (Unit tests including newly added regression suites for background notifications and server URL persistence)
   - **Debug APK Build:** 40 / 40 Gradle tasks successful (`android/app/build/outputs/apk/debug/app-debug.apk`, 11 MB).
4. **Evidence Authenticity:** All claimed physical device and Waydroid test cases in `TWO_CLIENT_PLAYTEST_REPORT.md` correspond directly to durable records in SQLite WAL database `aster.db` (97 verified message rows) and 10 valid, uncorrupted PNG screenshots on disk.
5. **Documented Beta Limitations:** Zero push notifications will occur when the Aster application process is dead or in deep system Doze because Aster uses `NoOpPushGateway`. Real-time notifications operate when the app process is alive (foreground or background with active WebSocket session).

---

## 2. EXACT WORKING-TREE BASELINE

### Git State
* **Branch:** `main`
* **Head Commit:** `ca6ca22 feat: add durable sqlite mailbox`
* **Preceding Commits:**
  - `cb73756 feat: make tablet backend persistent`
  - `5b59a82 docs: add canonical Aster design sheet`
* **Working Tree:** Dirty (preserved intact; no commits or resets executed).

### Inventory of Modified and Untracked Files

#### Modified Tracked Files
1. `android/app/build.gradle.kts`: Gradle build configuration, test dependencies (`mockwebserver`, `okhttp-tls`, Robolectric).
2. `android/app/src/main/AndroidManifest.xml`: Added `POST_NOTIFICATIONS` permission, `launchMode="singleTop"`, and `networkSecurityConfig`.
3. `android/app/src/main/kotlin/com/aster/app/AsterApplication.kt`: Application lifecycle entry point; restores persisted `serverUrl` on cold start and initializes notification channels.
4. `android/app/src/main/kotlin/com/aster/app/MainActivity.kt`: Single activity; handles intent routing, deep-link invite validation, notification tap navigation, and lifecycle-driven foreground tracking.
5. `android/app/src/main/kotlin/com/aster/app/data/network/NetworkConfig.kt`: Dynamic device endpoint resolution (`defaultEndpointForDevice()`), strict `wss://` enforcement in `PRODUCTION`, and LAN IP endpoint constants.
6. `android/app/src/main/kotlin/com/aster/app/ui/navigation/AsterNavigation.kt`: Jetpack Compose navigation host; handles onboarding vs home routing and singleTop intent consumption.
7. `android/app/src/test/kotlin/com/aster/app/AsterNotificationTest.kt`: Added regression test for background notification delivery when active chat is backgrounded.
8. `android/app/src/test/kotlin/com/aster/app/OnboardingAndIdentityTest.kt`: Added regression assertions for server URL persistence and deep-link query parameter parsing.
9. Repositories, DAOs, ViewModels, Protocol, Server Relay files modified during Milestones 8–11.1.

#### Untracked Implementation Artifacts
1. `android/app/src/main/kotlin/com/aster/app/data/auth/AsterAuthStorage.kt`: AndroidKeyStore AES-256-GCM secure token and server URL storage.
2. `android/app/src/main/kotlin/com/aster/app/data/network/AsterTlsManager.kt`: Strict X.509 certificate trust pinning against `@raw/aster_ca`.
3. `android/app/src/main/kotlin/com/aster/app/ui/notification/AsterNotificationManager.kt`: System notification dispatcher with foreground awareness and channel management.
4. `android/app/src/main/res/xml/network_security_config.xml`: Prohibits cleartext traffic; pins `@raw/aster_ca`.
5. `certs/aster_ca.crt`, `certs/aster_server.crt`, `certs/aster_server.p12`: Private Aster PKI CA and server certificate with SANs.
6. `TWO_CLIENT_PLAYTEST_REPORT.md`: Baseline two-client playtest report with empirical test transcripts.
7. `playtest_*.png`: 10 verified screenshot artifacts from physical and virtual devices.

---

## 3. AUDIT OF THE THREE FIX GROUPS

### Fix Group 1: LAN Endpoint Selection & Persistence Across Cold Restarts
* **Files:**
  - `android/app/src/main/kotlin/com/aster/app/data/network/NetworkConfig.kt`
  - `android/app/src/main/kotlin/com/aster/app/data/auth/AsterAuthStorage.kt`
  - `android/app/src/main/kotlin/com/aster/app/AsterApplication.kt`
  - `android/app/src/main/kotlin/com/aster/app/MainActivity.kt`
* **Audit Checks & Findings:**
  1. *Persistence Across Cold Restarts:* `AsterAuthStorage.saveServerUrl(url)` persists the endpoint into private SharedPreferences (`aster_auth_secure_prefs`). During `AsterApplication.onCreate()`, `authStorage.getServerUrl()` is retrieved and assigned to `NetworkConfig.serverUrl`. Verified intact across process death (`am force-stop`).
  2. *Plaintext Downgrade Protection:* `NetworkConfig.validateUrlForEnvironment` strictly requires URLs to begin with `wss://` when `currentEnvironment == NetworkEnvironment.PRODUCTION`. Attempting to set `ws://` throws `IllegalArgumentException`. Furthermore, `network_security_config.xml` sets `cleartextTrafficPermitted="false"`. Verified via `AsterSecurityTest.productionRejectsPlaintextWsUrl`.
  3. *Arbitrary Host Redirection Guard:* **Remediated during audit.** `MainActivity.handleInviteIntent` was enhanced to verify `!authStorage.isProvisioned()` before altering server configuration and parses the host via `java.net.URI` to ensure a non-blank hostname and `wss` scheme.
* **Verdict:** **VERIFIED & SECURED**

### Fix Group 2: Foreground / Background Notification Suppression
* **Files:**
  - `android/app/src/main/kotlin/com/aster/app/ui/notification/AsterNotificationManager.kt`
  - `android/app/src/main/kotlin/com/aster/app/MainActivity.kt`
* **Audit Checks & Findings:**
  1. *Lifecycle Tracking:* `MainActivity.onStart()` sets `notificationManager.isAppForeground = true`; `MainActivity.onStop()` sets `notificationManager.isAppForeground = false`.
  2. *Suppression Condition:* Evaluates `if (isAppForeground && getActiveConversation() == conversationId)`. When the user is in another app or on the home screen (`isAppForeground == false`), notifications are dispatched even if the conversation was left open.
  3. *Activity Recreation:* During orientation change or theme update, `onStop()` temporarily sets `isAppForeground = false` followed by `onStart()` restoring `true`. No notification drops or duplicate alerts occur.
  4. *Regression Test Added:* Added `showMessageNotification_whenAppBackgrounded_postsNotificationEvenIfConversationIsActive` in `AsterNotificationTest.kt`. Passed.
* **Verdict:** **VERIFIED & SECURED**

### Fix Group 3: SingleTop Notification Navigation & Consumed State Cleanup
* **Files:**
  - `android/app/src/main/AndroidManifest.xml`
  - `android/app/src/main/kotlin/com/aster/app/MainActivity.kt`
  - `android/app/src/main/kotlin/com/aster/app/ui/navigation/AsterNavigation.kt`
* **Audit Checks & Findings:**
  1. *SingleTop Activity Mode:* Declared `android:launchMode="singleTop"` on `MainActivity`. PendingIntents dispatch with `FLAG_ACTIVITY_SINGLE_TOP` or `FLAG_ACTIVITY_CLEAR_TOP`, routing notification taps to `onNewIntent` without spawning duplicate activity instances.
  2. *State Cleanup:* `AsterNavHost` observes `pendingConversationId`. Upon navigating to `AsterRoutes.chatRoute(convId)`, it sets `pendingConversationId.value = null`. This prevents repeated navigation on subsequent recompositions or back presses.
* **Verdict:** **VERIFIED & SECURED**

---

## 4. BACKGROUND DELIVERY RELIABILITY MATRIX

Aster implements a local background WebSocket transport coupled with `NoOpPushGateway`. The delivery reliability characteristics are explicitly audited below:

| # | Lifecycle Scenario | Expected Client State | Observed Behavior | Verdict | Evidence / Rationale |
| :- | :--- | :--- | :--- | :--- | :--- |
| **1** | **App Visible in Foreground** | WebSocket open, UI active | Real-time frame receipt; Room database updated; UI recomposed reactively. Notification suppressed for active chat. | **PASS** | Verified on Waydroid and Galaxy M21 (`playtest_client_a_dm_verified.png`, `playtest_client_b_dm_verified.png`). |
| **2** | **App Backgrounded (Process Alive)** | WebSocket open in background | Incoming message received; Room updated; high-importance notification posted to Android 15 shade in channel `aster_messages`. | **PASS** | Verified on Galaxy M21 (`playtest_client_b_notification_shade.png`). |
| **3** | **Activity Recreated / Return from Recents** | Activity recreated; Room re-collected | Chat history restored instantly from Room; WebSocket stays connected or reconnects; UI state remains intact. | **PASS** | Verified on Galaxy M21 (`playtest_client_b_home.png`). |
| **4** | **Process Killed by Android (Low Memory)** | Process terminated; WebSocket closed | Real-time notification cannot fire. Messages persist in server SQLite WAL mailbox (`QUEUED`). When user opens app, messages replay and transition to `ACKED`. | **EXPECTED LIMITATION** | Server uses `NoOpPushGateway`. Process-dead wakeup requires FCM or UnifiedPush integration. |
| **5** | **User Force-Stops App (`am force-stop`)** | Package in stopped state | No background code can run. Server mailbox queues messages. Cold relaunch recovers Keystore session and replays all pending messages. | **PASS** (Replay) / **EXPECTED LIMITATION** (Alert) | Verified in Scenario C-1 (`am force-stop` $\rightarrow$ relaunch $\rightarrow$ messages intact in UI and `aster.db`). |
| **6** | **Device Enters Doze / Battery Restriction** | Network socket suspended | Background TCP socket suspended by OS. No wakeups occur until maintenance window or user interaction. | **EXPECTED LIMITATION** | By Android OS design; high-priority push gateway needed to bypass Doze network freeze. |
| **7** | **Device Reconnects After Network Loss** | Socket dropped $\rightarrow$ recovered | Outbox messages (`SENDING`) auto-deliver on Wi-Fi reconnect; inbound mailbox messages (`QUEUED`) auto-replay and ACK. | **PASS** | Verified in Scenario D-1 / D-2 (`svc wifi disable` $\rightarrow$ enable $\rightarrow$ `ACKED` in `aster.db`). |

---

## 5. SECURITY AUDIT & FINDINGS

### Summary Table

| Finding ID | Severity | Area | Description | Status |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-01** | **HIGH** | Deep-Link Handling | Browsable `aster://join?server=...` link could override server endpoint on already provisioned client | **RESOLVED** (Guard added in `MainActivity.kt`) |
| **SEC-02** | **INFORMATIONAL** | Push Gateway | `NoOpPushGateway` does not wake dead processes; background delivery depends on active socket | **DOCUMENTED LIMITATION** |
| **SEC-03** | **LOW** | Release Proguard | `isMinifyEnabled = false` in release build type | **DOCUMENTED** (Acceptable for private trusted beta) |

### Detailed Evaluations

#### 1. TLS Certificate Validation & CA Pinning
* **Implementation:** `AsterTlsManager.kt` builds an `SSLSocketFactory` and `X509TrustManager` initialized strictly with `R.raw.aster_ca`.
* **Verification:** `AsterSecurityTest.tlsClientRejectsUntrustedServerCertificate` confirms that connections to servers with untrusted certificates fail with `SSLHandshakeException`. Cleartext traffic is disabled in `network_security_config.xml`.

#### 2. Server Certificate SANs & Hostname Verification
* **Certificate:** `certs/aster_server.crt`
* **Subject Alternative Names:** `DNS:aster.local, DNS:localhost, IP Address:127.0.0.1, IP Address:192.168.240.1, IP Address:192.168.0.159, IP Address:192.168.0.114`
* **Validity:** Valid until October 5, 2036.
* **Verification:** Direct TLS handshake over LAN Wi-Fi from Galaxy M21 (`192.168.0.170` $\rightarrow$ `192.168.0.114:8443`) succeeded with strict hostname verification.

#### 3. Invite-Token Lifecycle & Replay Resistance
* **Storage:** Tokens are generated using `SecureRandom` (256-bit entropy) as `ast_tok_<hex>` and stored in SQLite as SHA-256 hashes (`token_hash`).
* **Revocation:** Admin route `/api/revoke` terminates active WebSocket sessions and marks `revoked_at`. Validated in `AsterAuthenticationTest`.

#### 4. Server-Derived Sender Identity & Group Authorization
* **Relay Protection:** `MessageRelay.handleSendMessage` overrides any client-supplied `msg.senderId` with the session's authenticated identity (`sessionManager.getUserForSession(session)`).
* **Group Authorization:** Membership is checked via `mailboxDatabase.isMember(groupId, senderId)`. Non-members receive `UNAUTHORIZED_GROUP_ACCESS`.

#### 5. Admin Endpoint Network Boundary & Authentication
* **Host Restriction:** `checkAdminAccess` restricts `/api/provision` and `/api/revoke` to loopback (`127.0.0.1`) and local host interfaces via `isPermittedAdminIp`. External requests receive `403 Forbidden`.
* **Key Validation:** Protected by `X-Aster-Admin-Key` header with constant-time equality check (`MessageDigest.isEqual`). Fails closed (`401 Unauthorized`) if key is absent. Key stored in `.admin_key.secret` with file permissions `0600`.

#### 6. AndroidKeyStore Credential Handling
* **Key Storage:** `AsterAuthStorage.kt` uses AndroidKeyStore with key alias `AsterAuthKey_v1` using AES-256-GCM. Plaintext tokens are never written to disk in production.

#### 7. Secret Logging Prevention
* **Audit:** Code search confirmed zero tokens, passwords, private keys, or message bodies are logged by Android `Log.*` or backend SLF4J loggers.

---

## 6. TESTS & BUILDS EXECUTED

### Test Results

| Test Target | Execution Command | Tests Run | Passed | Failed | Exit Code | Time |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Backend Unit Suite** | `./gradlew test --rerun-tasks` (in `/backend`) | 69 | 69 | 0 | 0 | 1m 46s |
| **Android Unit Suite** | `./gradlew testDebugUnitTest` (in `/android`) | 69 | 69 | 0 | 0 | 1m 02s |
| **Android Debug APK** | `./gradlew assembleDebug` (in `/android`) | 40 tasks | 40 | 0 | 0 | 26s |
| **Total Automated** | | **138** | **138** | **0** | **0** | |

### APK Build Output
* **File:** `/home/saad/Aster/android/app/build/outputs/apk/debug/app-debug.apk`
* **Size:** 11 MB
* **API Levels:** `minSdk=24` (Android 7.0), `targetSdk=35` (Android 15)
* **Signing:** Android Debug Keystore (v1, v2 signing enabled)

---

## 7. OUTSTANDING BLOCKERS & DOCUMENTED LIMITATIONS

### Blocker Status
* **Zero Critical Blockers:** There are no open functional or security bugs preventing private beta deployment to trusted testers.

### Documented Beta Limitations
1. **Push Gateway Dependency (`NoOpPushGateway`):**
   - Real-time message delivery in the background functions while the Aster app process remains alive in recent apps.
   - If Android terminates the app under extreme memory pressure or deep Doze, incoming messages will be safely queued in the server mailbox and delivered when the app is next opened, but no system chime/shade notification will appear while dead.
2. **Network Reachability:**
   - Beta testers must be on a network path capable of reaching the server's TLS endpoint (host LAN Wi-Fi `192.168.0.114:8443` or a DNS route matching certificate SANs).

---

## 8. SINGLE MOST IMPORTANT NEXT ACTION

**Commit and tag the audited milestone changes in Git.**  
The repository has accumulated verified, passing, and audited code across Milestones 8–11.1 alongside the playtest fixes without committing. Create a clean, structured commit on `main` to lock in this verified private-beta baseline.
