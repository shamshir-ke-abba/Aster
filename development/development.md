# ASTER — Development Rules

## 1. General Principle

Build the smallest reliable system that solves the current requirement.

Do not over-engineer. Do not add infrastructure merely because it is fashionable.

## 2. Code Quality

Prefer small modules, clear names, explicit responsibilities, testable logic, predictable state and minimal coupling.

Avoid giant classes, giant ViewModels, hidden global state, duplicated business logic, magic constants and unnecessary abstractions.

## 3. Android

Preferred:
- Kotlin
- Jetpack Compose
- Coroutines
- Room
- Android Keystore
- Lifecycle-aware state

UI should remain separate from data/network logic.

## 4. State Management

Preferred flow:

Repository → ViewModel → UI State → Compose

## 5. Networking

Networking must handle connection failure, reconnection, timeouts, duplicate messages, offline operation and server errors.

Never assume a network connection is permanent.

## 6. Local Data

Local database operations must not block the main thread.

## 7. Testing

Priority:
1. Messaging logic
2. Database
3. Authentication
4. Reconnection
5. Offline delivery
6. Group membership
7. UI state
8. E2EE protocol

## 8. Git

Preferred commit style:

feat(chat): add conversation list
fix(sync): handle duplicate messages
refactor(network): isolate websocket client
test(chat): add offline delivery coverage

## 9. Design Compliance

Before implementing UI, consult design/design.md.

Do not introduce arbitrary colors, typography, animation or component styles.

If a new design pattern is needed, update the design source of truth.

## 10. Architecture Compliance

Before introducing backend or application architecture changes, consult architecture/architecture.md.

## 11. Security

Security is not the initial development blocker. However, implementations must not intentionally prevent future E2EE.

Do not store private cryptographic keys on the backend or design APIs that permanently require plaintext.

When E2EE begins, use established libraries and protocols.

## 12. Performance

The intended scale is approximately 100 users.

Optimize for responsive UI, low memory usage, efficient database access, reliable realtime communication and battery-conscious networking.

Do not prematurely optimize for millions of users.

## 13. Error Handling

Errors must be explicit, recoverable where possible, logged appropriately and understandable to users when surfaced.

Never silently swallow important failures.

## 14. Documentation

Canonical documents:

- design/design.md
- roadmap/roadmap.md
- architecture/architecture.md
- protocol/protocol.md
- development/development.md

## 15. Definition of Done

A feature is complete when implementation works, relevant tests pass, failure cases are considered, UI follows the design system and documentation is updated where necessary.

## 16. Development Philosophy

Build Aster as a real product, not a disposable prototype.

But do not build infrastructure for problems that do not exist yet.

Simple first.
Reliable second.
Polished third.
Scale when needed.
