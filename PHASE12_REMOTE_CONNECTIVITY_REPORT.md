# ASTER — PHASE 12: SECURE REMOTE CONNECTIVITY & EXTERNAL-NETWORK VALIDATION REPORT

**Date:** 2026-10-10  
**Repository:** `/home/saad/Aster`  
**Application ID:** `com.aster.app`  
**Current Git Commit:** `ca6ca22 feat: add durable sqlite mailbox` (Dirty working tree preserved intact; uncommitted changes untouched)  
**Evaluator Roles:** Senior Android Engineer, Backend Engineer, Network Architect, Security Auditor  

---

## 1. EXECUTIVE VERDICT

### **Final Phase 12 Verdict: BLOCKED (Awaiting Deployment Authorization & External Device Setup)**

Aster has been comprehensively audited for remote connectivity readiness. While local two-client playtesting on LAN/Waydroid succeeded with 100% pass rates, **local success does not establish public internet reachability**.

To achieve genuine, secure internet connectivity for remote beta testers outside the local network without compromising Aster's security posture, external network routing and public TLS infrastructure must be established. Because project constraints prohibit purchasing domains, provisioning external infrastructure, or altering router configurations without explicit authorization, the external deployment is documented, prepared, and formally gated.

---

## 2. CURRENT ARCHITECTURE & VERIFIED NETWORK TOPOLOGY

### A. Host & Local Gateway Inspection
* **Host Platform:** Ubuntu Linux x86_64 (`wlp1s0`)
* **Host LAN IP:** `192.168.0.114/24` (RFC 1918 private address)
* **Local Gateway:** D-Link router at `192.168.0.1` (DNS stub `127.0.0.53` routing upstream to `192.168.0.1`)
* **Public WAN IP (Observed via `api.ipify.org`):** `103.121.242.65`
* **Upstream Traceroute:**
  1. `192.168.0.1` (Local D-Link router, RTT 2.1 ms)
  2. `103.121.242.134` (ISP Gateway, RTT 5.5 ms)
  3. `103.121.242.129` (ISP Core, RTT 7.1 ms)
  4. `72.14.216.40` (Google peering exchange in Mumbai, RTT 5.3 ms)
* **NAT Topology:** Standard dynamic broadband NAT. No RFC 6598 Carrier-Grade NAT (`100.64.0.0/10`) was detected between the host router and the ISP gateway, but the WAN IPv4 address is dynamic and consumer-grade.
* **Inbound Reachability:** Direct inbound connections on port 8443 (or 443) from external internet IP addresses fail because the D-Link router does not forward external ports to `192.168.0.114`.

### B. Device Environment Status
* **Waydroid Container (`192.168.240.112:5555`):** Stopped following system reboot (`Session: STOPPED`).
* **Samsung Galaxy M21 (`SM-M215F`, `RZ8N70Z9BEZ`):** Physically disconnected / offline in ADB (`adb devices` reports empty).
* **Backend Server Process:** Stopped following system reboot (previously bound to `0.0.0.0:8443`).

---

## 3. DEPLOYMENT OPTIONS & TRADE-OFF EVALUATION

To provide secure WebSocket connectivity (`wss://`) for ~100 remote beta users, four deployment paths were audited:

| Option | Architecture | Feasibility | Security & Operational Trade-offs | Verdict |
| :--- | :--- | :--- | :--- | :--- |
| **1. Direct Host Port Forwarding** | D-Link router forwards WAN `103.121.242.65:8443` $\rightarrow$ `192.168.0.114:8443`. Requires DDNS + Let's Encrypt. | Low | **Cons:** Exposes home IP; dynamic IP changes break connections; requires router admin access and DDNS daemon. Vulnerable to residential ISP port blocking. | **BLOCKED** (Requires router credentials & DDNS domain) |
| **2. Cloudflare Tunnel (Recommended)** | Outbound TLS tunnel from host to Cloudflare Edge (`cloudflared`). Remote clients connect to `wss://<custom-domain>`. | **High** | **Pros:** 100% free; zero router port forwarding or firewall holes; automatic Cloudflare edge TLS certificates; native WebSocket support; handles 100+ users easily. **Cons:** Requires a registered domain in Cloudflare (or temporary `trycloudflare.com` quick tunnel). | **RECOMMENDED VIABLE PATH** (Requires tool authorization) |
| **3. ngrok Outbound Tunnel** | Outbound tunnel via `/home/saad/.local/bin/ngrok` (installed v3.39.11). | Medium | **Pros:** Binary is already installed on the laptop. **Cons:** Free tier requires an account authtoken; free endpoints have rate limits and dynamic URLs that change on restart unless on a paid plan. | **VIABLE ALTERNATIVE** (Requires user ngrok authtoken) |
| **4. Dedicated Cloud VPS (Hetzner / DO)** | Host backend on a $4/mo Linux VPS with static public IPv4/IPv6, running Aster as a systemd service. | High (Long-term) | **Pros:** 24/7 uptime without laptop dependency; static public IP; standard Certbot Let's Encrypt TLS renewal; optimal production posture for 100 beta users. **Cons:** Financial cost; requires infrastructure setup. | **BEST PRODUCTION PATH** (Requires cloud account & authorization) |

---

## 4. TLS TRUST MODEL & CERTIFICATE VALIDATION

### A. Current Private CA Model
* **Mechanism:** Aster generates a private Root CA (`certs/aster_ca.crt`) and server certificate (`certs/aster_server.crt`) with SANs matching local IPs (`192.168.240.1`, `192.168.0.114`).
* **Android Enforcement:** [`AsterTlsManager.kt`](file:///home/saad/Aster/android/app/src/main/kotlin/com/aster/app/data/network/AsterTlsManager.kt) initializes an OkHttp `SSLSocketFactory` pinned to `@raw/aster_ca`.
* **Remote Limitation:** Private CA pinning works seamlessly for local IPs and private domains where the CA is pre-installed in the client APK. However, for a public internet hostname (e.g. `chat.aster.app` or `<subdomain>.ngrok-free.app`), the edge terminates TLS using a trusted public Web PKI CA (Let's Encrypt, Cloudflare, DigiCert).

### B. Production-Safe Dual Trust Model
To support both remote public endpoints and local development without weakening security:
1. **Network Security Config:** [`network_security_config.xml`](file:///home/saad/Aster/android/app/src/main/res/xml/network_security_config.xml) already declares:
   ```xml
   <trust-anchors>
       <certificates src="system" />
       <certificates src="@raw/aster_ca" />
   </trust-anchors>
   ```
2. **OkHttp TrustManager Integration:** [`AsterTlsManager`](file:///home/saad/Aster/android/app/src/main/kotlin/com/aster/app/data/network/AsterTlsManager.kt) can be structured to trust the Android system trust store **and** `@raw/aster_ca`.
   - Connections to remote public domains validated against standard Web PKI.
   - Connections to local test servers validated against `@raw/aster_ca`.
   - Hostname verification strictly enforced via OkHttp `OkHostnameVerifier`.
   - Self-signed, invalid, expired, or MITM certificates remain strictly rejected.
   - Zero cleartext traffic allowed (`cleartextTrafficPermitted="false"`).

---

## 5. EXTERNAL-NETWORK TEST MATRIX

Per Phase 12 requirements, genuine external reachability must be validated using a real external client (e.g., physical Galaxy M21 on mobile data with Wi-Fi disabled):

| Test ID | Verification Vector | Test Conditions | Observed State | Verdict | Dependency / Blocker |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **EXT-01** | **Public DNS Resolution** | Query public hostname from external network | No public hostname configured | **BLOCKED** | Public domain / tunnel hostname not yet provisioned. |
| **EXT-02** | **External TCP Reachability** | SYN packet to port 8443/443 from mobile data | Inbound connections dropped by router NAT | **BLOCKED** | Router port forwarding or tunnel required. |
| **EXT-03** | **TLS Chain & Hostname Validation** | Full handshake against public certificate SANs | Endpoint not reachable externally | **BLOCKED** | Requires public TLS certificate deployment. |
| **EXT-04** | **WSS WebSocket Upgrade** | RFC 6455 upgrade over public TLS | Endpoint not reachable externally | **BLOCKED** | Requires working public WSS transport. |
| **EXT-05** | **Remote Invite Provisioning** | Deep link `aster://join?token=...&server=wss://...` | Endpoint not reachable externally | **BLOCKED** | Requires public WSS transport. |
| **EXT-06** | **Cross-Network 1:1 Direct Messaging** | Client A (Home LAN) $\leftrightarrow$ Client B (Mobile Data) | M21 offline; public tunnel not active | **BLOCKED** | Requires external client connectivity. |
| **EXT-07** | **Remote Group Messaging & Fan-out** | Multi-user exchange across distinct networks | Endpoint not reachable externally | **BLOCKED** | Requires external client connectivity. |
| **EXT-08** | **Offline Mailbox Replay via Cellular** | Cellular client offline during send $\rightarrow$ reconnects | Endpoint not reachable externally | **BLOCKED** | Requires external client connectivity. |
| **EXT-09** | **Cellular Network Interruption Recovery** | Toggle cellular data off $\rightarrow$ on during active chat | Endpoint not reachable externally | **BLOCKED** | Requires external client connectivity. |
| **EXT-10** | **Invalid Certificate Rejection** | Connect to mismatched SAN / untrusted cert | Unit test verified (`AsterSecurityTest`) | **PASS** (Local) / **BLOCKED** (Remote) | Tested in unit suite; remote verification blocked. |
| **EXT-11** | **Sender ID Spoofing Defense** | Attempt forged `senderId` over remote socket | Unit test verified (`AsterServerTest`) | **PASS** (Local) / **BLOCKED** (Remote) | Tested in unit suite; remote verification blocked. |
| **EXT-12** | **Backend Restart & Queue Durability** | Restart backend during remote client session | SQLite WAL verified locally | **PASS** (Local) / **BLOCKED** (Remote) | Tested in unit suite; remote verification blocked. |

---

## 6. AUTOMATED TEST & BUILD BASELINE (PRE-CHANGE VALIDATION)

```
================================================================================
                    PHASE 12 AUTOMATED VERIFICATION RESULTS
================================================================================
Backend Unit Suite:     ./gradlew test (in /backend)
                        Result: 69 / 69 PASSED (Exit code 0, 53s)

Android Unit Suite:     ./gradlew testDebugUnitTest (in /android)
                        Result: 73 / 73 PASSED (Exit code 0, 16s)

Android Debug APK:      ./gradlew assembleDebug (in /android)
                        Result: 40 tasks executed, 11 MB APK (Exit code 0, 26s)

Android Release APK:    ./gradlew assembleRelease (in /android)
                        Result: 52 tasks executed, 7.6 MB unsigned APK (Exit code 0)

TOTAL AUTOMATED TESTS:  142 / 142 PASSED (0 FAILURES, 0 ERRORS, 0 SKIPPED)
================================================================================
```

---

## 7. REMAINING DEPENDENCIES & SMALLEST ACTIONABLE NEXT STEP

### Concrete Blockers
1. **Public Endpoint Tunnel / Host Selection:** No public domain, port forward, or outbound tunnel is currently running or authorized.
2. **Physical Device Connectivity:** Samsung Galaxy M21 is currently disconnected from USB/ADB. Connecting via mobile data requires the physical phone to be connected and running with Wi-Fi disabled.

### Recommended Next Actions (Awaiting User Decision)
To proceed safely with genuine external-network testing, choose one of the following two deployment paths:

* **Choice A (Fastest Zero-Cost Path — Cloudflare Quick Tunnel / ngrok):**
  - Authorize running an outbound tunnel (`ngrok` with your auth token, or downloading `cloudflared` for an ephemeral HTTPS/WSS URL).
  - Plug in the physical Samsung Galaxy M21 via USB, disconnect its Wi-Fi (switching to cellular data), and execute the real-time E2E exchange across cellular and home networks.
* **Choice B (Permanent Production Path — Small VPS or Custom Domain):**
  - Provide a small Linux VPS or configure DNS records for an existing domain.
  - Deploy Aster backend with automated Let's Encrypt TLS.

---

## 8. GIT WORKING-TREE STATUS

* **Commits Executed:** **0** (Working tree strictly preserved).
* **Pushes Executed:** **0**.
* **Modifications:** Uncommitted changes from Milestones 8–11.1, playtest bugfixes, and pre-commit audit reports remain completely intact.
