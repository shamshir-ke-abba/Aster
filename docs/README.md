# ASTER

## Private Messaging for Your Constellation

Aster is a private messaging application designed for a trusted group of friends, targeting approximately 100 users.

The product goal is a polished, lightweight messaging experience with an initial operating cost target of ₹0.

## Source of Truth

| Document | Purpose |
|---|---|
| design/design.md | Visual identity and UX |
| roadmap/roadmap.md | Product development roadmap |
| architecture/architecture.md | Technical architecture |
| protocol/protocol.md | Messaging and E2EE protocol |
| development/development.md | Engineering rules |

## Core Concept

> A private constellation of people.

People are represented as points of light.
Conversations are connections.
Groups are constellations.
Aster is the space connecting them.

## Initial Target

- Android
- Private friend group
- Approximately 100 users
- Real-time messaging
- One-to-one chat
- Group chat
- Offline delivery
- ₹0 initial infrastructure cost
- E2EE as a planned security layer

## Current Status

- **Phase 1 — Core Messaging Foundation** is `COMPLETE` and verified across separate physical networks (Waydroid on Wi-Fi and Samsung Galaxy M21 on Airtel 4G/LTE cellular with Wi-Fi disabled).
- Android client (Kotlin, Jetpack Compose, Room, Hilt, AndroidKeyStore) fully integrated with the production Cloudflare Workers + Durable Objects (`AsterRelayDO`, `GroupDO`) + D1 SQLite backend.
- Registration, permanent Aster IDs, user discovery, friend requests, 1:1 conversations, bidirectional real-time messaging, durable offline mailboxes, recipient ACKs, and Room cold-start persistence are operational.
- Automated tests: 85/85 Android unit and repository tests passing. Real two-client production playtest: **PASS**.

Next major milestone:

**Phase 2 — Product Completeness (Profile & Settings, Contact Management, Conversation UX polish).**

## Design

Primary visual direction:

- Midnight background
- Periwinkle/violet accent
- Subtle star/constellation language
- Clean typography
- Minimal UI
- Short animations
- Calm atmosphere

## Development Philosophy

Simple first.
Reliable second.
Polished third.
Scale when needed.
