# ADR 002: Transport Security Boundary, Encryption Architecture & E2EE Roadmap

**Status**: Accepted  
**Date**: 2026-10-11  
**Target Deployment**: Private messaging app for ~100 users, self-hosted / low-cost infrastructure  

---

## 1. Context & Problem Statement

Aster is currently a private messaging system built for a small community of trusted users (~100 users). As Aster approaches private beta release, it is critical to state the security model with complete technical honesty:

- **Current Implementation**: Hop-by-hop transport security using TLS 1.3 / 1.2 over WebSockets (`wss://`) and HTTPS (`https://`).
  - The Android client utilizes `AsterTlsManager`, an automated dual-trust manager supporting both pinned private CA authorities (for local LAN / internal host testing) and standard system CAs (for remote Cloudflare tunnel or public domain routing).
  - The backend terminates TLS and routes messages to recipients or stores them in an SQLite database (`aster.db`) using Write-Ahead Logging (WAL).
- **Misconception vs. Reality**:
  - *Misconception*: "Private messaging implies end-to-end encryption (E2EE) by default."
  - *Reality*: Aster currently relies on **Transport Layer Security (TLS)**. Message bodies are encrypted in transit over the network wire, but decrypted at the server boundary before routing or mailbox storage.
- **Problem Statement**:
  - While TLS defends against external network eavesdroppers, hostile ISPs, and rogue Wi-Fi hotspots, the server administrator and the hosting environment possess access to message payloads stored in the mailbox.
  - A definitive architectural roadmap is required to transition Aster from server-terminated TLS to true End-to-End Encryption (E2EE) without breaking existing transport reliability or offline mailboxing.

---

## 2. Current Transport Security Boundary & Limitations

### 2.1 Threat Model & Protections Under Current TLS Stack

| Adversary / Threat | Protected? | Technical Explanation |
| :--- | :---: | :--- |
| **Passive Network Wiretapper** | **YES** | TLS 1.3 / 1.2 with AES-256-GCM encrypts all TCP frames between client and server. |
| **ISP / Mobile Carrier Snooping** | **YES** | Payloads and WebSocket frames are completely opaque to transit carriers. |
| **Public Wi-Fi Rogue AP / MITM** | **YES** | Strict certificate pinning and dual-trust validation in `AsterTlsManager` reject untrusted MITM certificates. |
| **Cloudflare Quick Tunnel (External)** | **YES** | Ingress traffic to `trycloudflare.com` is protected via Cloudflare edge TLS; origin communication uses encrypted tunnels. |
| **Server Administrator Inspection** | **NO** | The server process decodes JSON frames into memory and inserts message text into `aster.db`. |
| **Host Database Compromise** | **NO** | If an attacker gains read access to `aster.db` or server disk, queued and historical messages in the mailbox can be read. |

### 2.2 Why TLS Was Implemented First

1. **Protocol Reliability**: Establishing robust WebSocket reconnects, offline outboxes, ACK state machines, and multi-client group fan-out requires observable protocol frames during initial development.
2. **Device Interoperability**: Validating heterogeneous environments (Waydroid x86_64 and Samsung ARM64 over LAN and cellular) required isolating transport issues from cryptographic handshake bugs.
3. **Foundation for Mailbox**: The server-side SQLite mailbox handles store-and-forward queuing. Once E2EE is applied, the mailbox treats payloads as opaque ciphertext without needing architectural redesign.

---

## 3. End-to-End Encryption (E2EE) Architectural Roadmap

### 3.1 Target Cryptographic Foundation

Aster will implement the **Double Ratchet Algorithm** with **Extended Triple Diffie-Hellman (X3DH)**, modeled on the Signal Protocol:

```
[ Alice Client ]                                              [ Bob Client ]
      |                                                            |
      | 1. Publish Identity & Prekeys                              | 1. Publish Identity & Prekeys
      v                                                            v
+------------------------------------------------------------------------+
|                          Aster Key Directory                           |
+------------------------------------------------------------------------+
      |                                                            |
      | 2. Fetch Bob's Prekey Bundle                               |
      +----------------------------------------------------------->|
      |                                                            |
      | 3. X3DH Shared Secret Derivation                           | 3. X3DH Shared Secret Derivation
      |                                                            |
      |================= Pairwise Double Ratchet ==================|
      | 4. Encrypted Frame (AES-256-GCM / HKDF)                    |
      |-----------------(Opaque to Server)------------------------>|
```

### 3.2 Key Architecture & Primitives

1. **Identity Keys ($IK$)**: Long-term Curve25519 (X25519) key pairs generated upon account registration and stored in Android KeyStore (hardware-backed).
2. **Signed Prekeys ($SPK$)**: Medium-term Curve25519 keys, signed by the user's Identity Key, rotated periodically (e.g., weekly).
3. **One-Time Prekeys ($OPK$)**: Pool of 100 ephemeral Curve25519 keys uploaded to the server and consumed one-by-one to prevent replay attacks during asynchronous handshakes.
4. **Symmetric Encryption**: AES-256-GCM or ChaCha20-Poly1305 with 128-bit authentication tags.
5. **Key Derivation Function (KDF)**: HKDF-SHA256 used in symmetric-key ratchets for Forward Secrecy.

### 3.3 Properties Guaranteed by the Double Ratchet

- **Forward Secrecy (FS)**: Compromise of current keys does not allow decryption of past messages, as symmetric keys are deleted immediately after message decryption.
- **Break-in Recovery (Post-Compromise Security)**: An attacker who temporarily compromises a device's session keys loses access once a new Diffie-Hellman ratchet step occurs.
- **Opaque Server Mailbox**: The server mailbox stores only base64 ciphertext and MAC tags. `senderUserId`, `recipientUserId`, and `conversationId` remain visible for routing, while `body` is completely unreadable to the server.

### 3.4 Group Messaging Cryptography (Sender Keys)

For Aster group conversations (clusters of up to 100 users):
- Pairwise pairwise encryption becomes $O(N^2)$ bandwidth-intensive for fan-out.
- Aster will adopt **Sender Keys**:
  - Each group member generates a symmetric Sender Key chain.
  - The Sender Key is distributed once to other active group members via pairwise E2EE direct channels.
  - Subsequent group messages are encrypted with the sender's current ratchet key and fan-out by the server to all members.
  - Member departures trigger immediate Sender Key rotation to ensure backward and forward privacy.

---

## 4. Phase-by-Phase Implementation Plan

| Milestone | Scope | Deliverables |
| :--- | :--- | :--- |
| **Phase 1 (Current Beta)** | **Transport TLS** | Netty WSS + HTTPS, `AsterTlsManager` dual trust, SQLite WAL mailbox, token auth. Documented as server-accessible. |
| **Phase 2 (Post-Beta v1.1)** | **Key Infrastructure** | Add Prekey bundle schema to backend SQLite (`identity_keys`, `prekeys`). Client KeyStore key generation. |
| **Phase 3 (Post-Beta v1.2)** | **1:1 Double Ratchet** | Implement X3DH handshake and Double Ratchet state machine in Android client. Flag `body_encrypted: true`. |
| **Phase 4 (Post-Beta v1.3)** | **Group Sender Keys** | Sender Key distribution over pairwise channels. Key rotation on member add/remove/leave. |
| **Phase 5 (Post-Beta v1.4)** | **Safety Numbers** | Visual fingerprint verification (QR code / comparison words) in Profile & Chat info for MITM verification. |

---

## 5. Security & Privacy Disclosure for Beta Users

Aster Beta documentation and user settings will explicitly state:

> **Privacy Notice**: Aster Beta encrypts all communications across the internet using modern TLS encryption to protect against network snooping and telecom inspection. However, message content is stored in the self-hosted community server's mailbox to enable offline message delivery. End-to-end encryption with client-side keys is scheduled for Milestone 15. Do not transmit sensitive state secrets or unbacked financial information on the beta build.

---

## 6. Summary

The distinction between **Transport Security (TLS)** and **End-to-End Encryption (E2EE)** is fundamental to cryptographic integrity. Aster's current architecture provides robust transport security and a durable, offline-first mailbox that is structurally ready to accept opaque ciphertexts when the Double Ratchet protocol is layered on top.
