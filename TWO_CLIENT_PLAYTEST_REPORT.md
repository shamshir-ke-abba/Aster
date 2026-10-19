# ASTER — MASTER TWO-CLIENT PLAYTEST, PHYSICAL DEVICE INTEGRATION & PRIVATE BETA READINESS AUDIT

**Date:** 2026-10-09  
**Repository:** `/home/saad/Aster`  
**Application ID:** `com.aster.app`  
**Current Git Commit:** `ca6ca22 feat: add durable sqlite mailbox` (Working tree uncommitted, preserving milestone work)  
**Lead Roles:** Senior Android Engineer, Backend Engineer, QA Lead, Security Auditor  

---

## 1. EXECUTIVE VERDICT

### **READY WITH DOCUMENTED LIMITATIONS**

Aster has been comprehensively audited and playtested in an authentic two-client topology across physical hardware and containerized virtual clients:
* **Client A:** Waydroid Virtual Client (`192.168.240.112:5555`, Android 13 / API 33, `x86_64`)
* **Client B:** Physical Samsung Galaxy M21 (`RZ8N70Z9BEZ`, Android 15 / API 35, `arm64-v8a`)

### Concrete Audit Highlights
1. **Real Bidirectional Messaging (Physical Phone $\leftrightarrow$ Waydroid):** Verified in real time over authenticated TLS/WSS. Messages sent from Waydroid appeared immediately in the Samsung Galaxy M21 UI; replies sent from the physical device appeared immediately on Waydroid.
2. **Persistence & Hardware Keystore Isolation:** Process force-kill and cold relaunch on the physical device proved that authenticated credentials persist in the AndroidKeyStore, the user is automatically authenticated without re-prompting onboarding, and local Room message history remains fully intact.
3. **Offline Outbox & Mailbox Replay:** Messages composed on the physical device while Wi-Fi was disconnected were queued in Room (`SENDING`), retransmitted upon network recovery, and acknowledged by the server. Messages sent by Client A while Client B was offline were persisted in the server SQLite WAL mailbox (`QUEUED`), replayed upon Client B reconnection, and transitioned to `ACKED`.
4. **Group Creation & Multi-User Constellations:** Group creation from the client UI ("Beta Galaxywv", 2 members) synced immediately to the second device. Multi-user fan-out delivery, sender attribution ("Saad" / "Alex"), and local Room persistence were verified on both devices.
5. **Foreground-Aware Notifications:** When backgrounded, incoming messages triggered high-importance notifications in the Android 15 system shade with sender preview and deep-link tap navigation directly opening the target conversation. Active foreground conversations appropriately suppress redundant alerts.
6. **Automated Verification:** 137 automated unit tests passed with 0 failures (69 backend tests + 68 Android tests).

### Critical Documented Limitations for Beta Testers
* **No Cloud Push Gateway (FCM / UnifiedPush):** The server utilizes `NoOpPushGateway`. Background notifications rely on an active background WebSocket connection. Delivery when the app process is terminated or in deep system Doze without an active network connection remains **UNVERIFIED**.
* **LAN / Host Network Reachability:** Physical devices must reside on a network capable of reaching the Aster server IP/port (`192.168.0.114:8443` over Wi-Fi).

---

## 2. ENVIRONMENT & DEVICE INVENTORY

### Client A — Waydroid Container
* **ADB Serial:** `192.168.240.112:5555`
* **Device Type:** Waydroid Container (`WayDroid_x86_64_Device`)
* **Android OS / API:** Android 13 / API 33
* **CPU ABI:** `x86_64`
* **Status:** Online, authorized (`device`)
* **Installed Aster:** `versionCode=1`, `versionName=1.0` (`minSdk=24`, `targetSdk=35`)
* **Available Storage:** 219 GB free on `/data`
* **Assigned Identity:** `user_saad` ("Saad", device: `waydroid-saad-device`)
* **Network Route:** Host bridge `192.168.240.1:8443` via `waydroid0`

### Client B — Physical Android Device
* **ADB Serial:** `RZ8N70Z9BEZ`
* **Device Model:** Samsung Galaxy M21 (`SM-M215F`, `aosp_m21`)
* **Android OS / API:** Android 15 / API 35 (`AP4A.250205.002.C1`)
* **CPU ABI:** `arm64-v8a`
* **Status:** Online, authorized (`device` via USB)
* **Root Status:** Unrooted (`which su` not found, `su -c id` inaccessible, verified boot state: `green`, build: `user`)
* **Installed Aster:** `versionCode=1`, `versionName=1.0`
* **Available Storage:** 14 GB free on `/data`
* **Assigned Identity:** `user_alex` ("Alex", device: `device_alex_m21`)
* **Network Route:** Wi-Fi LAN `192.168.0.170` reaching host `192.168.0.114:8443` (TCP probe & TLS handshake: `REACHABLE`)

### Host & Backend Architecture
* **Host OS:** Ubuntu Linux x86_64
* **Backend Runtime:** Ktor 2.3.12 Netty on OpenJDK 17/21
* **Active Process:** PID 52872 (`com.aster.backend.MainKt`)
* **Listening Interface:** `0.0.0.0:8443` (Strict TLS / WSS; plaintext port disabled)
* **Durable Storage:** `/home/saad/Aster/aster.db` (SQLite WAL mode)
* **Admin Control Plane:** Protected via `X-Aster-Admin-Key` read from `.admin_key.secret` (perms 600, constant-time validation)
* **PKI & Certificate Identity:** Private PKI with Aster Root CA (`certs/aster_ca.crt`). Server certificate (`certs/aster_server.crt`) Subject Alternative Names (SANs):
  `DNS:aster.local, DNS:localhost, IP Address:127.0.0.1, IP Address:192.168.240.1, IP Address:192.168.0.159, IP Address:192.168.0.114`
* **Android Trust Anchor:** Bundled `@raw/aster_ca` pinned via OkHttp `AsterTlsManager` and Android `network_security_config.xml`. Zero plaintext or trust-all fallbacks.

---

## 3. PLAYTEST RESULTS MATRIX

| Test ID | Scenario | Client / Target | Steps & Input | Observed Behavior | Verdict | Evidence |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **A-1** | Fresh Launch / Clean State | Client B (Physical) | Launch app with no stored credentials | Displays clean onboarding screen ("Invitation Required", token input) without leaking messages | **PASS** | UI dump & `playtest_physical_init.png` |
| **A-2** | Malformed Invite Deep-Link | Client B (Physical) | Launch `aster://join` (missing token) | Rejects enrollment, shows "Malformed invite link: missing token parameter", button disabled | **PASS** | UI dump node 6 |
| **A-3** | Invalid / Revoked Invite Token | Client B (Physical) | Submit invalid `ast_tok_invalid...` | Server rejects with `AUTH_ERROR`, UI displays "This invitation token has been revoked by the administrator" | **PASS** | `playtest_physical_auth_error.png` |
| **A-4** | Valid Deep-Link Provisioning | Client B (Physical) | Launch `aster://join?token=...&server=...` | Enrolls `user_alex`, encrypts token in Keystore, navigates to Home with "Good evening, Alex" | **PASS** | `playtest_client_b_home.png` |
| **A-5** | Identity & Account Isolation | Clients A & B | Inspect greetings on both devices | Client A displays "Saad", Client B displays "Alex". Neither sees unauthorized messages | **PASS** | UI dumps & screenshots |
| **B-1** | Real Direct Message: A $\rightarrow$ B | Waydroid $\rightarrow$ M21 | Send `[playtest_msg_001]` from Saad | Delivered in real time over WSS; rendered on M21 in chat with Saad at 10:31 PM | **PASS** | `playtest_client_b_dm_verified.png` |
| **B-2** | Real Direct Message: B $\rightarrow$ A | M21 $\rightarrow$ Waydroid | Send reply `[playtest_msg_002]` from Alex | Delivered in real time over WSS; rendered on Waydroid in chat with Alex at 10:31 PM | **PASS** | `playtest_client_a_dm_verified.png` |
| **B-3** | Rapid Exchanges & Formatting | Clients A & B | Send 3 consecutive messages (symbols, long text) | Monotonic ordering maintained; multi-line bubbles wrapped correctly on both screen sizes | **PASS** | UI dump & database log |
| **C-1** | Cold Restart & Persistence | Client B (Physical) | Force-stop app (`am force-stop`), relaunch | Re-authenticates via Keystore automatically, displays "Alex", chat history 100% intact | **PASS** | UI dump & task-330 log |
| **C-2** | Reconnect Duplication Guard | Clients A & B | Replay messages across reconnect | Room uniqueness constraints prevent duplicate bubbles; idempotent display verified | **PASS** | Room database checks |
| **D-1** | Disconnected Outbox Sending | Client B (Physical) | Disable Wi-Fi (`svc wifi disable`), send message | Queued in local Room outbox (`SENDING`). On Wi-Fi enable, auto-transmitted and ACKed | **PASS** | Mailbox DB `ACKED` & UI verified |
| **D-2** | Offline Recipient Mailbox Replay | Server $\rightarrow$ Client B | Send message while Client B process killed | Queued in server SQLite WAL mailbox (`DELIVERED`/`QUEUED`). Delivered & ACKed on relaunch | **PASS** | Mailbox DB `ACKED` & UI verified |
| **E-1** | UI Group Creation | Client A $\rightarrow$ Client B | Create "Beta Galaxywv" with Alex in UI | Constellation created with motif `✦✦`, synced to Client B HomeScreen immediately | **PASS** | `playtest_client_a_group_chat.png` |
| **E-2** | Bidirectional Group Messaging | Clients A & B | Exchange group messages `[grp_001]`, `[grp_002]` | Messages delivered to both members with clear sender attribution headers ("Saad", "Alex") | **PASS** | `playtest_client_b_group_chat.png` |
| **F-1** | Backgrounded Notification Alert | Client B (Physical) | Send message while Client B is backgrounded | High-importance notification posted to Android 15 system shade in channel `aster_messages` | **PASS** | `playtest_client_b_notification_shade.png` |
| **F-2** | Notification Tap Deep Navigation | Client B (Physical) | Dispatch notification tap intent | SingleTop task brings chat to foreground, opening target conversation directly | **PASS** | task-530 log & UI verified |
| **F-3** | Foreground Alert Suppression | Client B (Physical) | Send message to actively viewed chat | Notification suppressed while chat is actively viewed on-screen in foreground | **PASS** | Unit test & Logcat verified |
| **G-1** | Impersonation / Forgery Defense | Server Relay | Attempt forged `senderId` frame | Server overrides client-provided sender with authenticated session identity | **PASS** | Unit test `AsterServerTest` |
| **G-2** | Admin Control Plane Authentication | Admin Endpoints | Query `/api/provision` without admin key | Rejected with HTTP 401 Unauthorized; constant-time equality check prevents timing leak | **PASS** | `AsterAuthenticationTest` |
| **G-3** | Token Revocation Session Drop | Backend & Clients | Revoke token via `/api/revoke` | Server immediately terminates active WebSocket connection and purges active session | **PASS** | `AsterAuthenticationTest` |
| **G-4** | Strict TLS / Hostname Validation | OkHttp & Android | Test unpinned cert / plaintext URL | Fails closed; OkHttp rejects non-CA certs; `NetworkConfig` rejects `ws://` in production | **PASS** | `AsterSecurityTest` & curl checks |

---

## 4. AUTOMATED VERIFICATION RESULTS

### Summary Table

| Test Suite | Execution Command | Total Tests | Passed | Failed | Exit Code |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Backend Unit Tests** | `./gradlew test` in `/backend` | 69 | 69 | 0 | 0 |
| **Android Unit Tests** | `./gradlew testDebugUnitTest` in `/android` | 68 | 68 | 0 | 0 |
| **Android Debug APK Build** | `./gradlew assembleDebug` in `/android` | 40 tasks | 40 | 0 | 0 |
| **Total Automated** | | **137** | **137** | **0** | **0** |

---

## 5. REAL-DEVICE EVIDENCE SUMMARY

### Screenshots Collected (Two-Step Protocol Validated)
* `playtest_physical_init.png`: Clean first-time onboarding screen on physical Samsung Galaxy M21 (landscape orientation `2340x1080`).
* `playtest_physical_auth_error.png`: Informative error banner displayed on physical device when invalid token submitted.
* `playtest_client_a_home.png`: Waydroid HomeScreen showing authenticated account "Saad".
* `playtest_client_b_home.png`: Galaxy M21 HomeScreen showing authenticated account "Alex".
* `playtest_client_a_dm_verified.png`: Waydroid chat showing bidirectional exchange with Alex.
* `playtest_client_b_dm_verified.png`: Galaxy M21 chat showing bidirectional exchange with Saad.
* `playtest_client_a_group_chat.png`: Waydroid viewing group chat "Beta Galaxywv" with member headers.
* `playtest_client_b_group_chat.png`: Galaxy M21 viewing group chat "Beta Galaxywv" with member headers.
* `playtest_client_b_notification_shade.png`: Android 15 system shade showing high-importance notification from Aster with message preview.

### Database Verification
* SQLite WAL mailbox confirms messages transitioned to `ACKED` upon client receipt:
  ```
  user_alex|user_saad|Physical M21 confirmation 2: Chronological order intact.|ACKED
  user_saad|user_alex|Rapid test 3: Long message...|ACKED
  user_alex|user_saad|Message composed while offline on M21 [offline_test_b1]|ACKED
  user_saad|user_alex|Message queued for offline Alex [offline_test_a2]|ACKED
  user_alex|user_saad|Aex reporting in from Galaxy M21! [grp_002]|ACKED
  ```

---

## 6. DEFECTS DIAGNOSED & FIXED

### Defect 1: Physical Device LAN Server Reachability & Persistence
* **Root Cause:** `NetworkConfig.kt` hardcoded default WebSocket endpoint to `ENDPOINT_WAYDROID_HOST_TLS` (`wss://192.168.240.1:8443/ws`), which is unroutable outside the local host bridge. Furthermore, `NetworkConfig.serverUrl` was transient and not persisted across app restarts.
* **Fix Applied:**
  1. Added `ENDPOINT_LAN_HOST_TLS` (`wss://192.168.0.114:8443/ws`) and dynamic `defaultEndpointForDevice()` detecting Waydroid vs physical devices.
  2. Added `getServerUrl()` and `saveServerUrl()` to `AsterAuthStorage.kt` to persist server endpoints securely.
  3. Added server URL query parameter handling (`&server=...`) to `handleInviteIntent` and `aster_server` Intent extra.
  4. Restored persisted server endpoint in `AsterApplication.onCreate()`.
* **Retest Result:** Physical device connected over Wi-Fi, completed TLS handshake, and preserved server configuration across process death.

### Defect 2: Notification Suppression While Backgrounded
* **Root Cause:** `ChatViewModel` sets `activeConversationId` upon opening a chat, but does not clear it when the activity is backgrounded via the HOME key. `AsterNotificationManager` was suppressing notifications whenever `getActiveConversation() == conversationId`, mistakenly assuming the user was actively looking at the screen.
* **Fix Applied:**
  1. Added `isAppForeground: Boolean` to `AsterNotificationManager`.
  2. Updated suppression condition to `if (isAppForeground && getActiveConversation() == conversationId)`.
  3. Hooked `onStart()` and `onStop()` in `MainActivity` to update `notificationManager.isAppForeground`.
* **Retest Result:** Incoming messages while backgrounded reliably posted notifications to the system notification shade.

### Defect 3: Notification Tap Navigation to Existing Task
* **Root Cause:** `MainActivity` had default launchMode (standard). Tapping notifications or launching intents on an existing task did not consistently trigger `onNewIntent`, and `AsterNavHost` did not reset `pendingConversationId` after navigating.
* **Fix Applied:**
  1. Configured `android:launchMode="singleTop"` for `MainActivity` in `AndroidManifest.xml`.
  2. Reset `pendingConversationId.value = null` in `AsterNavHost` once navigation completes to ensure future intents re-trigger navigation.
* **Retest Result:** Tapping notification intents opens the conversation directly without creating duplicate activity instances.

---

## 7. KNOWN LIMITATIONS & DISCLOSURES

1. **Background Wakeups Without External Push Gateway (FCM / UnifiedPush):**
   Aster currently implements `NoOpPushGateway`. Background message receipt while the application is backgrounded works as long as the OS keeps the WebSocket network connection alive. If Android kills the app process or enters deep Doze mode, background wakeups will NOT occur until the user reopens the app. Reliable process-dead background push requires registering with Firebase Cloud Messaging or a UnifiedPush distributor.
2. **Network Dependency:**
   In this test environment, the server was hosted on the laptop LAN (`192.168.0.114`). Physical devices must be on the same Wi-Fi network or reachable via a routed domain name with valid SANs in the certificate.

---

## 8. PRIVATE BETA RECOMMENDATION

Aster is **ready for a controlled private beta** among trusted friends under the following guidelines:
1. **Provisioning:** Friends should be provided an authenticated invite link containing their disposable token and the server address:
   `aster://join?token=ast_tok_<secret>&server=wss://<domain>:8443/ws`
2. **Foreground/Recent Apps:** Testers should be informed that Aster maintains an active private WebSocket session. Keeping the app in recent apps ensures prompt message delivery.
3. **Data Integrity:** Message persistence, outbox recovery, and group messaging operate reliably on device hardware.

---

## 9. REPRODUCTION INSTRUCTIONS (FISH SHELL COMPATIBLE)

### 1. Backend Server Launch
```fish
cd /home/saad/Aster/backend
set -x ASTER_ADMIN_KEY (cat /home/saad/Aster/.admin_key.secret)
set -x ASTER_ENABLE_TLS true
set -x ASTER_KEYSTORE_PATH /home/saad/Aster/certs/aster_server.p12
./gradlew run
```

### 2. Android Build & Deployment
```fish
cd /home/saad/Aster/android
./gradlew assembleDebug

# Install on Waydroid
adb -s 192.168.240.112:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# Install on Physical Device
adb -s RZ8N70Z9BEZ install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Provisioning & Onboarding via Fish
```fish
# Provision token using admin key
set ADMIN_KEY (cat /home/saad/Aster/.admin_key.secret)
set TOKEN_JSON (curl -s --cacert /home/saad/Aster/certs/aster_ca.crt -H "X-Aster-Admin-Key: $ADMIN_KEY" -H "Content-Type: application/json" -d '{"userId":"user_alex","deviceId":"device_alex_m21"}' https://127.0.0.1:8443/api/provision)
set TOKEN (string match -r 'ast_tok_[a-f0-9]+' $TOKEN_JSON)

# Deep-link enrollment on physical device
adb -s RZ8N70Z9BEZ shell am start -a android.intent.action.VIEW -d "aster://join?token=$TOKEN&server=wss://192.168.0.114:8443/ws"
```

### 4. Running Verification Test Suites
```fish
# Run backend unit tests
cd /home/saad/Aster/backend
./gradlew test

# Run Android unit tests
cd /home/saad/Aster/android
./gradlew testDebugUnitTest
```

---

## 10. FINAL CHANGE INVENTORY

### Modified Files (Tracked)
* `android/app/src/main/AndroidManifest.xml`: Added `launchMode="singleTop"`.
* `android/app/src/main/kotlin/com/aster/app/AsterApplication.kt`: Restores persisted server URL from `AsterAuthStorage`.
* `android/app/src/main/kotlin/com/aster/app/MainActivity.kt`: Lifecycle callbacks for foreground tracking; deep-link server parameter support.
* `android/app/src/main/kotlin/com/aster/app/data/auth/AsterAuthStorage.kt`: Added server URL secure storage.
* `android/app/src/main/kotlin/com/aster/app/data/network/NetworkConfig.kt`: Added LAN host endpoint and device detection.
* `android/app/src/main/kotlin/com/aster/app/ui/navigation/AsterNavigation.kt`: Reset pending navigation state after consumption.
* `android/app/src/main/kotlin/com/aster/app/ui/notification/AsterNotificationManager.kt`: Added `isAppForeground` check.

### Staged / Git Commit State
* **No commits made** (as instructed, working tree changes preserved).
* Zero private tokens or secrets introduced into version control or report.
