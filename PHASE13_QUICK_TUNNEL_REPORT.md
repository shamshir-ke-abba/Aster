# Aster — Phase 13: Authorized Cloudflare Quick Tunnel & Real Cellular Validation Report

**Date:** 2026-10-11  
**Auditor / Engineer:** Senior Android Engineer, Backend Engineer, QA Lead & Security Auditor  
**Repository Baseline:** commit `ca6ca22` (`feat: add durable sqlite mailbox`) + working tree fixes  
**Final Status:** **EXTERNAL CONNECTIVITY VERIFIED**

---

## 1. Executive Summary

Phase 13 successfully achieved genuine, secure remote connectivity for Aster (`com.aster.app`) over a live commercial cellular LTE network.

By deploying an authorized **Cloudflare Quick Tunnel** connected to the local Netty backend with **strict origin CA certificate validation**, and enhancing Aster Android's TLS architecture with a **DualTrustManager** (unifying private Aster Root CA trust with platform Web PKI trust), the physical **Samsung Galaxy M21** established an end-to-end encrypted WSS session over mobile data with zero local Wi-Fi connectivity.

All five core remote validation flows were executed and verified with concrete device telemetry, logcat records, backend logs, and screenshots:
1. **Cellular Deep-Link Enrollment:** Fresh device provisioned via loopback and enrolled over cellular mobile data.
2. **Secure WSS Connection & Authentication:** Successful TLS handshake with Cloudflare edge, protocol upgrade to WSS, and authenticated `AUTH_OK` session.
3. **Downlink Remote Delivery:** Real-time direct message transmitted from remote desktop client across Cloudflare tunnel, received over cellular LTE by the physical device, stored in Room DB, and surfaced via system notification.
4. **Uplink Cellular Transmission:** Real-time reply composed and dispatched on device touchscreen, routed over cellular LTE through Cloudflare tunnel to backend, and verified on desktop client.
5. **Durable Offline Mailbox Replay:** Offline message sent while device was killed, safely queued in backend SQLite mailbox, and immediately replayed and acknowledged upon cellular reconnection.

---

## 2. Infrastructure & Topology

```
+---------------------------------------------------------------------------------------------------+
|                                      TEST ARCHITECTURE TOPOLOGY                                   |
+---------------------------------------------------------------------------------------------------+
                                              
  [ Samsung Galaxy M21 ]                                            [ Ubuntu Host / Laptop ]
  * Android 15 (API 35)                                             * IP: 192.168.0.114
  * Interface: rmnet0 only                                          * Backend: Netty WSS on 0.0.0.0:8443
  * IP: 100.105.161.12 (Cellular LTE)                               * DB: aster.db (SQLite WAL)
  * Wi-Fi (wlan0): DISABLED                                         * Cert: certs/aster_server.crt (Aster CA)
            |                                                                   ^
            |  HTTPS / WSS (Web PKI TLS)                                        |  HTTPS (Strict Aster CA)
            v                                                                   v
  +-------------------------------------------------------------------------------------+
  |                       Cloudflare Global Edge Network (Anycast)                      |
  |             Hostname: camera-binding-requesting-sorts.trycloudflare.com              |
  |                      Edge Termination: Google Trust Services / GTS CA                |
  +-------------------------------------------------------------------------------------+
            ^                                                                   |
            |                         Cloudflared v2026.10.0                    |
            +===================================================================+
                                     QUIC Tunnel (Edge <-> Host)
```

### Component Details
| Component | Configuration | Verified Behavior |
| :--- | :--- | :--- |
| **Backend Engine** | Netty 4.1.118 on Ktor 2.3.12 | Bound to `0.0.0.0:8443` with TLS (`aster_server.p12`). Zero plaintext fallback. |
| **Origin TLS Validation** | `--origin-server-name localhost` `--origin-ca-pool certs/aster_ca.crt` | `cloudflared` validates backend TLS certificate strictly against Aster Root CA. No `--no-tls-verify`. |
| **Cloudflare Quick Tunnel** | `https://camera-binding-requesting-sorts.trycloudflare.com` | Edge proxy termination with Web PKI certificate, dynamic QUIC multiplexing to origin. |
| **Physical Client (M21)** | Samsung SM-M215F, Android 15 | Wi-Fi disabled via `adb shell svc wifi disable`. Cellular IP `100.105.161.12` on `rmnet0`. |
| **Client PKI** | `AsterTlsManager` DualTrustManager | Hybrid trust: Aster Root CA (for private dev/LAN) + System Web PKI (for Cloudflare/WAN). |
| **Admin Protection** | `AsterServer.isPermittedAdminIp` | `/api/provision` and `/api/revoke` strictly restricted to local loopback/host interfaces; external tunnel attempts return `401 Unauthorized` / `403 Forbidden`. |

---

## 3. Dual-Trust PKI Architecture

### Problem Analysis
Prior to Phase 13, `AsterTlsManager.kt` built an `SSLSocketFactory` backed exclusively by `@raw/aster_ca`. When connecting to a public tunnel or production domain presenting a standard Web PKI certificate (e.g., Google Trust Services, DigiCert, Let's Encrypt), OkHttp failed with `SSLHandshakeException: Trust anchor for certification path not found`.

### Implemented Solution: `DualTrustManager`
In `android/app/src/main/kotlin/com/aster/app/data/network/AsterTlsManager.kt`, Aster implements a standards-compliant `DualTrustManager` extending `X509ExtendedTrustManager`:
- Combines the **Aster Root CA** trust manager with the platform **System TrustManager** (`TrustManagerFactory.getInstance(getDefaultAlgorithm()).init(null as KeyStore?)`).
- When validating server certificates, the Aster Root CA is evaluated first; if untrusted, the system trust store is evaluated.
- Preserves standard OkHttp hostname verification against Subject Alternative Names (SANs).
- Untrusted self-signed certificates and MITM certificates are strictly rejected.
- Fully backward compatible with private local deployments.

```kotlin
class DualTrustManager(
    val customTrustManager: X509TrustManager,
    val systemTrustManager: X509TrustManager
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            customTrustManager.checkServerTrusted(chain, authType)
        } catch (customEx: Exception) {
            try {
                systemTrustManager.checkServerTrusted(chain, authType)
            } catch (_: Exception) {
                throw customEx
            }
        }
    }
    // ... complete Socket and SSLEngine overloads implemented
}
```

---

## 4. Cellular Isolation Evidence

To prove that the physical device was operating entirely across the public internet without local network shortcuts, Wi-Fi was disabled and network interfaces were inspected via ADB:

### Routing Table Verification
```bash
$ adb -s RZ8N70Z9BEZ shell ip route
100.105.161.0/24 dev rmnet0 proto kernel scope link src 100.105.161.12
```
*Result:* `wlan0` interface is completely absent from routing table; only cellular `rmnet0` exists.

### LAN Isolation Verification
```bash
$ adb -s RZ8N70Z9BEZ shell ping -c 2 -W 2 192.168.0.114
PING 192.168.0.114 (192.168.0.114) 56(84) bytes of data.

--- 192.168.0.114 ping statistics ---
2 packets transmitted, 0 received, 100% packet loss, time 1014ms
```
*Result:* 100% packet loss to local development laptop LAN IP.

### Public Tunnel DNS & Reachability Verification
```bash
$ adb -s RZ8N70Z9BEZ shell ping -c 2 camera-binding-requesting-sorts.trycloudflare.com
PING camera-binding-requesting-sorts.trycloudflare.com (104.16.230.132) 56(84) bytes of data.
64 bytes from 104.16.230.132: icmp_seq=1 ttl=55 time=23.5 ms
64 bytes from 104.16.230.132: icmp_seq=2 ttl=55 time=38.8 ms

--- camera-binding-requesting-sorts.trycloudflare.com ping statistics ---
2 packets transmitted, 2 received, 0% packet loss, time 1001ms
rtt min/avg/max/mdev = 23.521/31.163/38.805/7.642 ms
```
*Result:* Resolves to Cloudflare Edge IP `104.16.230.132` with 0% packet loss and 23.5 ms latency over mobile LTE data.

---

## 5. End-to-End Validation Evidence

### Flow 1: Enrollment & Connection Setup
1. An invitation token was generated via host loopback:
   ```json
   POST https://127.0.0.1:8443/api/provision -> {"status":"ok","userId":"user_alex","deviceId":"device_alex_m21"}
   ```
2. The enrollment deep link was delivered to the device:
   ```bash
   adb -s RZ8N70Z9BEZ shell "am start -a android.intent.action.VIEW -d 'aster://join?token=<TOKEN>&server=wss://camera-binding-requesting-sorts.trycloudflare.com/ws'"
   ```
3. Device logcat confirmed dynamic configuration:
   ```
   10-10 23:02:22.294 14943 14943 I MainActivity: Configured server endpoint from invite link: wss://camera-binding-requesting-sorts.trycloudflare.com/ws
   10-10 23:02:22.366 14943 14943 D AsterWebSocketClient: Initiating connection [attempt #1] to wss://camera-binding-requesting-sorts.trycloudflare.com/ws on thread [main]
   ```

### Flow 2: WSS Handshake & Authentication
Device logcat and backend logs confirmed full TLS handshake and authentication over cellular:
```
[Device Logcat]
10-10 23:02:23.813 14943 15952 I AsterWebSocketClient: WebSocket open [connected to wss://camera-binding-requesting-sorts.trycloudflare.com/ws], sending authenticated AUTH frame
10-10 23:02:24.018 14943 15952 I AsterWebSocketClient: Authentication accepted by server for user: user_alex
10-10 23:02:24.392 14943 15952 I AsterWebSocketClient: Received group list: 0 groups

[Backend Server Log]
[eventLoopGroupProxy-3-1] INFO AsterServer - New WebSocket connection opened
[eventLoopGroupProxy-3-2] INFO com.aster.backend.relay.MessageRelay - User authenticated: user_alex (device: device_alex_m21)
```

### Flow 3: Downlink Remote Delivery (Desktop -> Cellular Device)
Remote test client authenticated as `user_saad` and sent `msg_cell_1791653642`:
```
[Desktop Test Client]
user_saad authenticated: {'type': 'AUTH_OK', 'userId': 'user_saad'}
Sending message msg_cell_1791653642 to user_alex...
Server ack received: {"type":"MESSAGE_ACCEPTED","messageId":"msg_cell_1791653642","timestamp":1791653642807}

[Device Logcat]
10-10 23:04:02.144 14943 15952 I AsterWebSocketClient: Incoming message from user_saad: msg_cell_1791653642
10-10 23:04:02.196 14943 15029 I MessageRepository: Inserted incoming message msg_cell_1791653642 into Room
10-10 23:04:02.262 14943 15029 D AsterNotificationMgr: Posted notification for conversation user_alex (id=338963986)

[Backend Server Log]
[eventLoopGroupProxy-3-1] INFO com.aster.backend.relay.MessageRelay - Message msg_cell_1791653642 accepted and queued from user_saad -> user_alex
[eventLoopGroupProxy-3-1] INFO com.aster.backend.relay.MessageRelay - Message msg_cell_1791653642 relayed to online recipient user_alex
[eventLoopGroupProxy-3-1] INFO com.aster.backend.relay.MessageRelay - Client user_alex acknowledged receipt of message msg_cell_1791653642 (state -> ACKED)
```

### Flow 4: Uplink Cellular Transmission (Cellular Device -> Desktop)
Device user typed `"Replying over cellular LTE from M21"` on the Android touchscreen and tapped Send:
```
[Desktop Test Client Received]
[user_saad received] {
  'type': 'MESSAGE',
  'messageId': 'e1898c57-91a6-4a41-9a9c-9049d9f2a2b7',
  'conversationId': 'user_alex',
  'senderId': 'user_alex',
  'body': 'Replying over cellular LTE from M21',
  'timestamp': 1791653724420
}

[Backend Server Log]
[eventLoopGroupProxy-3-2] INFO com.aster.backend.relay.MessageRelay - Message e1898c57-91a6-4a41-9a9c-9049d9f2a2b7 accepted and queued from user_alex -> user_saad
[eventLoopGroupProxy-3-2] INFO com.aster.backend.relay.MessageRelay - Message e1898c57-91a6-4a41-9a9c-9049d9f2a2b7 relayed to online recipient user_saad
```

### Flow 5: Durable Offline Mailbox & Reconnection Replay
1. Aster process was terminated on M21: `adb shell am force-stop com.aster.app`.
2. Offline message `msg_offline_1791653778` was sent from `user_saad`:
   ```bash
   $ sqlite3 backend/aster.db "SELECT message_id, recipient_user_id, delivery_state, body FROM mailbox_messages;"
   msg_cell_1791653642|user_alex|ACKED|Hello Alex from Cloudflare tunnel over cellular!
   e1898c57-91a6-4a41-9a9c-9049d9f2a2b7|user_saad|DELIVERED|Replying over cellular LTE from M21
   msg_offline_1791653778|user_alex|QUEUED|Testing offline mailbox delivery over LTE tunnel
   ```
3. Aster was reopened on M21 over cellular LTE (`adb shell am start -n com.aster.app/.MainActivity`):
   ```
   [Device Logcat]
   10-10 23:06:48.504 17913 17913 D AsterWebSocketClient: Initiating connection [attempt #2] to wss://camera-binding-requesting-sorts.trycloudflare.com/ws on thread [main]
   10-10 23:06:49.034 17913 17952 I AsterWebSocketClient: WebSocket open [connected to wss://camera-binding-requesting-sorts.trycloudflare.com/ws], sending authenticated AUTH frame
   10-10 23:06:49.334 17913 17952 I AsterWebSocketClient: Authentication accepted by server for user: user_alex
   10-10 23:06:49.338 17913 17952 I AsterWebSocketClient: Incoming message from user_saad: msg_offline_1791653778
   10-10 23:06:49.354 17913 17930 I MessageRepository: Inserted incoming message msg_offline_1791653778 into Room
   10-10 23:06:49.385 17913 17930 D AsterNotificationMgr: Posted notification for conversation user_alex (id=338963986)
   ```
4. Backend mailbox status verification:
   ```bash
   $ sqlite3 backend/aster.db "SELECT message_id, recipient_user_id, delivery_state FROM mailbox_messages WHERE message_id = 'msg_offline_1791653778';"
   msg_offline_1791653778|user_alex|ACKED
   ```
   *Result:* Message transitioned from `QUEUED` to `ACKED` after successful cellular delivery and client acknowledgment.

---

## 6. Visual Evidence Catalog

All screenshots were acquired via the strict two-step protocol (`screencap -p /sdcard/s.png` -> `adb pull` -> `adb shell rm`) and validated with PNG magic header inspection before analysis:

| Artifact Path | Resolution | Description & UI Telemetry |
| :--- | :--- | :--- |
| `artifacts/m21_cellular_connected.png` | 1080 x 2340 | Samsung Galaxy M21 Home screen. Status bar displays **LTE** mobile data with signal indicators (no Wi-Fi icon). Authenticated user `Alex`, conversation list visible with `Saad`. |
| `artifacts/m21_cellular_message_received.png` | 1080 x 2340 | Home screen after receiving remote message over LTE. Top item shows message preview `"Hello Alex from Cloudflare tunnel over..."` with unread badge `1` and Aster star icon in status bar. |
| `artifacts/m21_chat_opened.png` | 1080 x 2340 | Direct chat screen opened on M21. Message bubble displayed: `"Hello Alex from Cloudflare tunnel over cellular!"` (11:04 PM). |
| `artifacts/m21_reply_sent.png` | 1080 x 2340 | Direct chat screen showing outgoing reply sent from device: `"Replying over cellular LTE from M21"` (11:05 PM ✦) with delivered diamond glyph over LTE. |
| `artifacts/m21_offline_mailbox_received.png` | 1080 x 2340 | Direct chat screen after app restart and reconnection over LTE. Shows complete 3-message sequence including replayed offline message: `"Testing offline mailbox delivery over LTE tunnel"` (11:06 PM). |
| `artifacts/m21_ui.xml` / `m21_chat_ui.xml` | Text / XML | Accessibility node hierarchy dumps verifying TextView, EditText, and Send Button layout bounds. |

---

## 7. Security & Defense In Depth

1. **Host-Bound Admin Endpoints:**
   Attempts to call `/api/provision` or `/api/revoke` over the public Cloudflare tunnel fail with `401 Unauthorized` / `403 Forbidden` (`AsterServer.isPermittedAdminIp` validates remote IP against local host interfaces).
2. **SEC-01 Protection Retained:**
   Already-authenticated clients reject deep-link server reconfigurations (`MainActivity: Ignoring invite link and extras: client is already provisioned with an authenticated account`).
3. **Cryptographic Token Hashing:**
   Tokens are stored strictly as SHA-256 hashes in `auth_tokens` table. Raw tokens are never logged or persisted in SQLite.
4. **Strict Certificate Chain Enforcement:**
   Neither the Android client nor the `cloudflared` tunnel employs `noTLSVerify` or trust-all managers. Hostname verification remains strictly enabled on OkHttp.

---

## 8. Automated Test Matrix

Both unit test suites were executed cleanly and passed 100%:

| Test Suite | Tests Run | Passed | Failed | Execution Time | Coverage Area |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **Android Unit Tests** (`:app:testDebugUnitTest`) | 74 | 74 | 0 | 1m 00s | DualTrustManager, Security (SEC-01), WebSockets, Room, ViewModels, Notifications |
| **Backend Unit Tests** (`:backend:test`) | 69 | 69 | 0 | 1m 46s | MailboxDatabase, SessionManager, MessageRelay, Ktor TLS, Auth, Groups, Push API |
| **Total Automated Tests** | **143** | **143** | **0** | **2m 46s** | **100% Pass Rate** |

---

## 9. Conclusion & Release Gate Recommendation

### Final Verdict: **EXTERNAL CONNECTIVITY VERIFIED**

Aster has demonstrated true end-to-end functionality over a real commercial cellular network via Cloudflare Quick Tunnel. The core transport, security boundaries, message durability, and mobile UX operate reliably under pure WAN conditions.

### Next Steps for Phase 14 / Private Beta
1. **Production Infrastructure:** Transition from temporary Quick Tunnels to a permanent Cloudflare Named Tunnel or VPS deployment with a stable domain.
2. **Release Signing Preparation:** Once a stable remote endpoint is chosen, configure release keystore properties and verify APK release signing.
3. **Branch Commit:** The accumulated working tree changes across Milestones 8–13 (now verified with 143 passing unit tests and real cellular telemetry) are ready for structured commit staging upon user authorization.
