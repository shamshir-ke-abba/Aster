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

- Android foundation established and verified (Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.10.01, Hilt, Room 2.6.1).
- Local-first messaging vertical slice implemented: Room database persistence, Conversation list, Chat screen, message creation and local delivery state transitions.
- Realtime WebSocket relay backend implemented (Ktor CIO, ₹0 cost) and verified end-to-end with two-client messaging, server ACKs, offline queueing, and automatic reconnection.

Next major milestone:

**Phase 2 — Group messaging & multi-user constellations.**

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
