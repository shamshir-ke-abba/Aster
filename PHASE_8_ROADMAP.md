# Aster — Phase 8: Motion, Expressiveness & Interaction

> **Authoritative Specification & Master Roadmap**  
> *Status:* Ready for Implementation  
> *Target Repository:* Aster Android (Kotlin / Jetpack Compose)  
> *Scope:* Major post-roadmap feature phase following the completion and verification of Phases 1–7.

---

## 1. Project Context & Locked Foundation

Aster is a sovereign, secure private messaging platform built with:
- 1:1 and Group real-time messaging over encrypted WebSockets
- Offline mailbox queueing and replay backed by Cloudflare Durable Objects
- Delivery and read receipts (`DELIVERED`, `READ`) with reliable ACK drainage
- Context actions (View Info, Copy Text, 5-minute unsend, local Delete for You)
- Account deletion, session revocation, and deleted-user identity handling
- Server-authoritative presence tracking (`ACTIVE`, `OFFLINE`, and `Deleted User`)
- Constellation Map as the primary chat canvas with Conversation List as secondary mode

**Phases 1 through 7 are 100% COMPLETE and LOCKED.**
- **Do not rebuild the application.**
- **Do not modify or reopen Phases 1–7.**
- **Do not alter existing database schemas, networking protocols, or security invariants unless explicitly specified herein for Phase 8 data persistence.**

Phase 8 elevates Aster from a fully functional, rock-solid messaging app into a **physically alive, expressive, and tactile communication experience**.

---

## 2. Core Philosophy & Design Identity

### 2.1 The Guiding Principle
> **"Aster should feel physically alive, not merely animated."**

Animations in Aster are never decorative gimmicks randomly layered onto screens. Motion is an active communication channel that conveys:
- **Spatial relationships:** Where an element originated and where it goes.
- **State changes:** Why an item transitioned from one status to another.
- **Focus and hierarchy:** What requires attention and what recedes.
- **Physicality and direct manipulation:** The user's touch has weight, spring, and momentum.

### 2.2 Target Aesthetic
- **Fluid iOS-grade interaction quality**, completely native to Android Jetpack Compose.
- **Distinctly Aster:** Does not copy Apple’s visual identity or iMessage tropes. The motion language deeply honors Aster’s cosmic metaphor:
  - Constellations, stars, and celestial nodes
  - Gravitational relationships and orbital connections
  - Aster Violet (`#8B5CF6`), Aster Warm (`#F59E0B`), Deep Void (`#0A0B0E`), and Obsidian surfaces (`#12131A`)
  - Subtle glowing rims, soft atmospheric halos, and organic spatial distributions.

### 2.3 Anti-Patterns — What to Avoid
- **No excessive bouncing or cartoony physics.**
- **No gratuitous full-screen particle storms.**
- **No perpetual background motion** that drains battery or consumes GPU cycles.
- **No sluggish, overly long transitions** that delay communication or impede quick tasks.
- **No animation for animation's sake.** Motion must strictly serve usability and spatial comprehension.

---

## 3. The Authoritative Transition Principle

> **Aster should not merely show state changes. Aster should animate the transition between states.**

| Event | Conventional UI (Before) | Aster Motion Language (Phase 8) |
|---|---|---|
| **Presence Transition** | Abrupt switch from `Offline` to `Active` | Presence indicator softly fades in with a gentle single-cycle breathing pulse. |
| **Incoming Message** | Instant appearance or abrupt scroll pop | Subtle upward slide + spring settle + soft alpha bloom into the timeline. |
| **Outgoing Message** | Bubble snaps into list | Bubble projects smoothly from composer into list; delivery checkmarks transition reactively. |
| **Message Unsend** | Text replaced abruptly | Message body softly contracts into dust/retraction aura, transforming into retracted placeholder. |
| **Delete for You** | Item vanishes instantly | Bubble gently slides away with fading alpha; adjacent bubbles smoothly close the gap. |
| **Constellation to Chat** | Screen crossfade or slide | Selected node expands outward, surrounding nodes gently de-emphasize, and chat canvas emerges from the node. |
| **Dialog / Context** | Generic modal pop | Bubble physically lifts with elevation glow; background softens into midnight scrim; actions emerge from touch point. |

---

## 4. Master Implementation Sequence

Phase 8 is divided into **seven sequential sub-phases**. Implementation must follow this exact order. Each sub-phase must be built, tested, physically verified on hardware/emulator, and reported before beginning the next.

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ Phase 8.1 — Motion System Foundation (Tokens, Curves, Springs, Scrims)  │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│ Phase 8.2 — Fluid Navigation Transitions (Shared Elements, Canvas-to-Chat)
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│ Phase 8.3 — Messaging Motion & Interaction (Send, Receive, Context, Reply)
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│ Phase 8.4 — Expressive Messaging (Reactions, Celestial Effects, Text)   │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│ Phase 8.5 — Rich Communication (Voice Messages, Media Hero Transitions) │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│ Phase 8.6 — Living Constellation Map (Repositioning, Highlight, Context)│
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│ Phase 8.7 — Final Motion Polish, Haptics & Accessibility Assurance      │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Detailed Sub-Phase Specifications

### Phase 8.1 — Motion System Foundation

The goal of Phase 8.1 is to build a centralized, reusable motion token architecture in `com.aster.app.ui.theme.motion` rather than writing ad-hoc `animate*AsState` calls with arbitrary numbers.

#### 5.1.1 Semantic Durations
Define standard semantic duration tokens:
- **Micro (`AsterMotion.Duration.Micro`):** 100ms – 150ms. Button taps, checkmark state ticks, ripple triggers, icon toggles.
- **Short (`AsterMotion.Duration.Short`):** 200ms – 250ms. Message bubble arrival, composer expand/contract, tooltip fade.
- **Standard (`AsterMotion.Duration.Standard`):** 300ms – 350ms. Screen transitions, bottom sheet entrances, context card reveals.
- **Emphasis (`AsterMotion.Duration.Emphasis`):** 450ms – 500ms. Hero transitions, node-to-chat expansion, media full-screen open.
- **Complex (`AsterMotion.Duration.Complex`):** 600ms – 700ms. Constellation layout settling, multi-node organic re-arrangement.

#### 5.1.2 Easing Curves & Springs
- **Cubic Bezier Curves:**
  - `Emphasized`: `CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)` (Android 14 standard deceleration)
  - `EmphasizedAccelerate`: `CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)`
  - `EmphasizedDecelerate`: `CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)`
- **Spring Specifications:**
  - `PressSpring`: `stiffness = StiffnessMediumLow`, `dampingRatio = DampingRatioLowBouncy` (tactile button compression)
  - `ReleaseSpring`: `stiffness = StiffnessMedium`, `dampingRatio = DampingRatioNoBouncy`
  - `ContentSettleSpring`: `stiffness = StiffnessLow`, `dampingRatio = 0.85f` (smooth organic landing without jitter)
  - `DragFollowSpring`: High responsiveness with minimal lag.

#### 5.1.3 Interaction Scales & Scrims
- **Scale Tokens:** Pressed (`0.96f`), Focused (`1.04f`), Elevated (`1.08f`), Diminished (`0.92f`).
- **Alpha Tokens:** Active (`1.0f`), De-emphasized (`0.35f`), Muted (`0.15f`), Ghost (`0.0f`).
- **Scrim Motion:** Standardized midnight overlay (`Void` with `0.75f` opacity) with unified fade-in and smooth blur transition.

#### 5.1.4 Accessibility & Reduced Motion
- Provide a `LocalReducedMotion` composition local observing `Settings.Global.TRANSITION_ANIMATION_SCALE` and `ANIMATOR_DURATION_SCALE`.
- When reduced motion is requested:
  - Translations and scale changes collapse to zero.
  - Duration collapses to micro fades or instant state cuts.
  - Functional feedback (colors, text indicators) remains completely clear.

---

### Phase 8.2 — Fluid Navigation Transitions

Eliminate jarring screen crossfades or instantaneous pop-ups between Aster's primary surfaces.

#### 5.2.1 Constellation → Chat Transition
- When a user taps a constellation node (1:1 or Group):
  1. Selected star node brightens and expands slightly (`scale = 1.15f`).
  2. Surrounding constellation lines and companion nodes fade to de-emphasized alpha (`0.2f`).
  3. The node smoothly translates toward the top app-bar position, expanding into the chat avatar/title header.
  4. The chat conversation surface rises upward with an organic spring settle.
  5. Back navigation reverses the sequence: conversation folds smoothly back into the glowing star on the canvas.

#### 5.2.2 Chat → Profile / User Info Transition
- Tapping the chat header avatar smoothly expands the avatar into the Profile screen header using shared spatial bounds.

#### 5.2.3 Message → Message Info Dialog
- Tapping "View Info" animates the selected message bubble directly into the info card header, anchoring the metadata context.

#### 5.2.4 Dialog & Bottom-Sheet Physics
- Dialogs must not pop into view. They enter with a subtle vertical translation (16dp) coupled with scale (`0.95f` → `1.0f`) and soft alpha.
- Bottom sheets implement fluid velocity-aware drag-to-dismiss with spring-back threshold.

---

### Phase 8.3 — Messaging Motion & Interaction

Bring tactile physicality to everyday messaging interactions without compromising high throughput or database performance.

#### 5.3.1 Incoming Message Arrival
- Inbound messages do not pop into the list.
- They arrive with a gentle vertical translation (+12dp), a subtle scale pulse (`0.97f` → `1.0f`), and spring settle.
- Scroll position remains anchored; incoming messages never cause viewport stutter or layout shifts.

#### 5.3.2 Message Send Choreography
1. **Send Button Tap:** Send icon undergoes momentary compression (`0.92f`) and radiates a soft Nebula Blue glow.
2. **Composer Reset:** Input field text clears smoothly; composer shrinks back to single-line height with fluid animation.
3. **Bubble Launch:** Outgoing bubble appears at composer level and springs into the conversation list.
4. **Status Machine:** Bubble status indicator transitions smoothly through actual states:
   `SENDING (clock icon) → SENT (single check) → DELIVERED (double check) → READ (double check filled with Nebula Blue)`.

#### 5.3.3 Enhanced Message Context Actions (Phase 5 Enhancement)
- Enhances the long-press context menu from Phase 5:
  1. Long-press triggers a light haptic pulse.
  2. The selected message bubble lifts off the canvas with elevated shadow and soft starlight glow.
  3. Surrounding conversation messages dim to `0.35f` alpha.
  4. Context actions (View Info, Copy Text, Unsend, Delete for You) blossom out from the bubble touch coordinate.
  5. Tapping outside or pressing back triggers a synchronized collapse back to the natural resting position.

#### 5.3.4 Swipe-to-Reply
- Dragging a message bubble horizontally to the right:
  - Affordance icon (reply arrow inside a glowing circle) emerges proportionally to drag distance.
  - Slight resistance curves prevent accidental triggers.
  - Passing the threshold (64dp) triggers a haptic click.
  - Releasing beyond threshold docks the message into the composer's "Replying to" preview tray.
  - Releasing before threshold springs the bubble back to `x = 0dp`.
  - Must strictly coexist with vertical list scrolling without touch interception conflicts.

#### 5.3.5 Composer Motion
- Smooth vertical resizing as multiline text expands (no abrupt line jumps).
- Smooth keyboard appearance compensation utilizing Android 11+ `WindowInsetsAnimation`.
- "Replying to" banner slides down smoothly above composer; dismissal slides it cleanly away.

---

### Phase 8.4 — Expressive Messaging

Give users the ability to communicate with celestial expression while keeping the interface calm and respectful.

#### 5.4.1 Message Reactions
- Lightweight reaction dock above the focused message bubble:
  - Initial curated set: ❤️, 😂, 😮, 😢, 👍, 🔥.
  - Scalable data model supporting future emoji additions.
- Tapping a reaction:
  - Reaction icon grows and springs onto the lower corner of the message bubble.
  - Counter badge smoothly ticks up or down.
  - Tapping an existing reaction toggles it off with a soft shrink fade.

#### 5.4.2 Aster-Native Message Effects
Optional, user-triggered celestial effects. These are restrained, elegant, and battery-friendly (rendering via efficient canvas shaders, auto-terminating within 1.5 seconds):
- **Nebula:** A soft, atmospheric violet/cyan cloud pulses gently behind the message bubble and dissipates.
- **Stardust:** Fine luminous starlight embers briefly drift upward from the message and fade away.
- **Ripple:** A subtle celestial gravitational wave ripples outwards from the bubble.
- **Pulse:** The bubble gently breathes once with increased starlight intensity.
- **Comet:** A delicate light trail follows the message as it docks into the timeline.
*Rule:* Effects are strictly opt-in per message, never default for ordinary communication.

#### 5.4.3 Animated / Emphasized Text
- Expressive typography treatments for intentional emphasis:
  - Gentle text shimmer for key phrases
  - Soft emphasis pulse for exclamation
  - Subtle shake for urgent alerts
- Applied intentionally by user styling, never forced upon standard body text.

---

### Phase 8.5 — Rich Communication

Expand Aster's communication capabilities beyond text into physical voice notes and media.

#### 5.5.1 Voice Messaging Interaction
- **Physical Hold-to-Record Sequence:**
  1. Press and hold microphone button → button expands (`1.2f`) with glowing pulse; haptic click.
  2. Audio waveform visualizer expands dynamically to the left of the user's thumb.
  3. Recording timer ticks in real-time.
  4. **Slide to Cancel:** Dragging left towards a trash icon cancels and discards the recording.
  5. **Lock Recording:** Dragging upward locks into hands-free mode.
  6. Releasing (or tapping send in locked mode) forms the voice message bubble with integrated waveform.
- **Playback Controls:**
  - Dynamic interactive waveform seeking.
  - Play / Pause with fluid state transition.
  - Speed toggle (`1.0x` → `1.5x` → `2.0x`) with immediate audio rate adjustment.
  - Voice message replies supported via swipe-to-reply.

#### 5.5.2 Media Messaging & Hero Transitions
- Support for images, short videos, and multi-image galleries.
- **Spatial Hero Expansion:**
  - Tapping a thumbnail in chat smoothly expands the image from its exact bubble coordinates into the full-screen media viewer.
  - Swiping down or tapping back smoothly collapses the full-screen viewer back into the exact bubble coordinates in the chat timeline.
- Pinch-to-zoom with double-tap reset and fluid momentum panning.
- Media captions and inline action tray (Save to Device, Share, Reply).

---

### Phase 8.6 — Living Constellation Map (Major Feature)

The Constellation Map is Aster's defining visual identity. Phase 8 makes it feel like an interactive celestial ecosystem rather than a static diagram.

```text
               CONSTELLATION TOUCH INTERACTION
               
                        Long Press
                            │
                            ▼
              ┌───────────────────────────┐
              │ Constellation Gains Focus │
              │   • Visual Elevation      │
              │   • Soft Starlight Halo   │
              │   • Surrounding Nodes Dim │
              └─────────────┬─────────────┘
                            │
             User movement past threshold?
             (Threshold = 12dp deadzone)
                            │
            ┌───────────────┴───────────────┐
            │                               │
         YES (Drag)                      NO (Release)
            │                               │
            ▼                               ▼
┌───────────────────────┐       ┌───────────────────────┐
│   REPOSITION MODE     │       │ CONTEXT CARD REVEAL   │
│ • Follows touch       │       │ • 1:1 or Group card   │
│ • Collision avoidance │       │ • Action tray emerges │
│ • Settle into place   │       │ • Highlight / Mute    │
│ • Persist coordinates │       │ • Relationship checks │
└───────────────────────┘       └───────────────────────┘
```

#### 5.6.1 Living Motion & Ambient Breathing
- Star nodes possess subtle, asynchronous breathing pulses (very slow 4-second period, ±2% scale and slight glow modulation).
- Nodes subtly react to viewport panning: nodes exhibit slight inertia based on their relationship weight.
- Never chaotic or distracting; maintains a calm midnight aesthetic.

#### 5.6.2 Constellation Long-Press & Interaction Branching
- When a user presses and holds any constellation node (1:1 or Group):
  1. Node elevates visually with a soft starlight halo.
  2. Surrounding companion nodes gently dim to `0.4f` alpha.
  3. A 12dp touch deadzone prevents accidental drags.

#### 5.6.3 Branch A: Constellation Repositioning (Drag Detected)
- If touch moves past the 12dp threshold, Aster enters **Reposition Mode**:
  - The node tracks the user's finger smoothly.
  - Surrounding nodes dynamically part to make room while strictly enforcing:
    - Collision avoidance boundaries
    - Golden-angle layout principles
    - Viewport edge padding (nodes never get pushed off-screen)
    - Visual distinction between group clusters and individual nodes
  - Upon release, the node settles into its new equilibrium with a gentle spring.
  - The custom position is saved locally in the database.

#### 5.6.4 Branch B: Constellation Context Card (Release Without Movement)
- If the user releases within the 12dp deadzone without dragging:
  - The **Constellation Context Card** smoothly opens from the node.
  - **1:1 Context Card Actions:**
    - Open Conversation
    - View Profile
    - **Highlight** (Toggle)
    - Remove Contact
    - Block User
  - **Group Context Card Actions:**
    - Open Group
    - **Highlight** (Toggle)
    - Group Info
    - Members / People
    - Exit Group
  - Fully honors Phase 5 relationship rules and Phase 6 deleted-account invariants (deleted users never display invalid actions).

#### 5.6.5 Constellation Highlight — Aster's Version of Pinning
- Aster rejects standard "pin" iconography. Instead, users can **Highlight** key constellations:
  - **Visual Treatment:** Highlighted nodes receive a distinctive inner radiance, a slightly brighter core, a soft permanent outer halo, and a more defined connection strand.
  - **Layout Priority:** The layout engine grants highlighted constellations placement priority near the primary viewing sweet-spot, while strictly honoring the rule: *no central clustering around YOU*.
  - **User-Local Preference:** Highlighting is completely local to the user's device and does not leak to other users.
  - **Persistence:** Highlight status persists across app restarts, navigation, and re-authentication.

#### 5.6.6 Local Schema Persistence for Constellation State
- To preserve custom node positions and highlight flags, add an Room entity or extend existing metadata:
  ```kotlin
  @Entity(tableName = "constellation_node_prefs")
  data class ConstellationNodePrefEntity(
      @PrimaryKey val conversationId: String,
      val customNormalizedX: Float?,
      val customNormalizedY: Float?,
      val isHighlighted: Boolean = false,
      val updatedAt: Long = System.currentTimeMillis()
  )
  ```
- Backed by DAO operations that hydrate seamlessly during layout computation.

---

### Phase 8.7 — Final Motion Polish, Haptics & Accessibility

Ensure Aster operates with uniform tactile feedback, rock-solid stability, and battery efficiency.

#### 5.7.1 Haptic Feedback Guidelines
Integrate `HapticFeedback` via Android `ViewConfiguration` and `HapticFeedbackConstants`:
- Long-press selection: `KEYBOARD_TAP` / light tick.
- Threshold crossed (swipe-to-reply or drag release): distinct confirmation tick.
- Action confirmation (Unsend, Delete, Highlight): subtle medium feedback.
- Strict constraint: **Never vibrate on ordinary message list scrolling or common button taps.**

#### 5.7.2 Server-Authoritative Presence Presentation
- When a user transitions to `ACTIVE`: indicator fades in and pulses softly once.
- When a user transitions to `OFFLINE`: indicator fades out gradually with updated last seen string.
- **Deleted User Invariant:** Strictly enforced. Deleted accounts always display `Deleted User` and `This user no longer exists`; never animated, never showing active pulses.

#### 5.7.3 Notification → Chat Deep Link Focus
- When launching chat from an incoming message notification:
  - Chat list scrolls immediately to the target message.
  - The target message bubble glows with a brief highlight aura for 1.2 seconds, then naturally fades to normal rest state.
  - Clearly explains to the user which message initiated the deep link.

#### 5.7.4 Performance & Battery Benchmarks
- Constellation canvas and message list must consistently maintain **60fps / 120fps** on target test hardware (Waydroid and Samsung Galaxy M21).
- Strictly clip or cull off-screen animations.
- Release shader resources when surfaces leave the composition.

---

## 6. What NOT to Do (Strict Constraints)

1. **Do not reopen or modify Phases 1–7.** Their code, logic, and tests are complete and release-ready.
2. **Do not redesign Aster from scratch.** The visual foundation established in Phase 2 is authoritative.
3. **Do not replace the Constellation Map.** It is the primary chat canvas; the conversation list is secondary.
4. **Do not turn Aster into a game or screensaver.** Continuous frivolous particle animations are prohibited.
5. **Do not copy Apple's proprietary visual assets, SF Symbols, or iMessage graphics.** Keep Aster's identity original.
6. **Do not fake server states.** Animations must strictly reflect the genuine WebSocket and Room state machines.
7. **Do not break accessibility.** Always provide reduced-motion fallbacks for all transitions.
8. **Do not implement Phase 8 in one monolithic pull request.** Follow the incremental sub-phases (8.1 through 8.7).

---

## 7. Phase 8 Definition of Done & Success Criteria

Phase 8 will be considered complete when:
- [ ] Central motion tokens (`AsterMotion`) are established and adopted across the application.
- [ ] Navigation between Constellation, Chat, and Profile feels unified with spatial continuity.
- [ ] Messaging interactions (send, receive, context actions, swipe-to-reply) feel physical, fluid, and responsive.
- [ ] Message reactions and Aster-native celestial effects function smoothly without performance degradation.
- [ ] Voice messaging and media hero transitions provide rich, tactile communication.
- [ ] Constellation Map supports fluid repositioning, context card reveal, and persistent Highlighting.
- [ ] All automated tests (184+ Android unit tests, 38+ Cloudflare tests) continue to pass with 0 regressions.
- [ ] Verified at 60fps/120fps on physical Samsung Galaxy M21 and Waydroid runtime.
- [ ] Aster feels **physically alive, fluid, and unmistakably Sovereign**.

---

## 8. Authoritative Directive to Future Coding Prompts

> **Notice to all agents and developers:**  
> When tasked with implementing Phase 8 work, **always begin by reading this file (`PHASE_8_ROADMAP.md`)**.  
> Identify the specific sub-phase being requested (e.g., Phase 8.1, Phase 8.2, etc.). Implement only the requested sub-phase, verify it thoroughly on automated and physical testbeds, report the results, and do not jump ahead. This document is the ultimate source of truth for Aster Phase 8.
