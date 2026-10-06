# ASTER — Messaging Protocol

## Status

Initial specification.

## 1. Goals

- Reliable message delivery
- Message identification
- Conversation identification
- Delivery acknowledgement
- Offline delivery
- Duplicate protection
- Future E2EE compatibility

## 2. Message Identity

Every message should have a globally unique message ID.

The client should generate the message ID before transmission.

## 3. Logical Message

```
{
  message_id,
  conversation_id,
  sender_id,
  timestamp,
  type,
  payload
}
```

The exact wire format may change.

## 4. Delivery States

Possible states:

- PENDING
- SENT
- DELIVERED
- READ
- FAILED

## 5. Acknowledgements

The backend acknowledges receipt. The recipient acknowledges successful delivery.

## 6. Ordering

Messages include timestamps and unique IDs. The client must tolerate delayed or duplicated network delivery.

Do not assume network arrival order equals conversation order.

## 7. Offline Delivery

The backend may temporarily retain undelivered messages. Once delivery is confirmed, queued messages may be removed according to retention policy.

## 8. Future E2EE

```
Plaintext → Session encryption → Ciphertext envelope → Server → Ciphertext envelope → Session decryption → Plaintext
```

The backend should not require access to plaintext.

## 9. Cryptographic Direction

When E2EE is implemented:

- Use established cryptographic libraries
- Use established protocols where possible
- Never invent cryptographic primitives
- Keep private keys on trusted client devices
- Authenticate ciphertext
- Protect against replay
- Rotate session keys
- Design for forward secrecy

## 10. Key Verification

Future versions may support QR verification, safety numbers and device fingerprints.

## 11. Group Messaging

Group E2EE should not simply reuse one shared static key forever. A proper group-key/session design should be selected when group E2EE is implemented.

## 12. Protocol Source-of-Truth Rule

Any change to message structure, delivery semantics, session establishment or cryptographic behaviour must be documented here before being treated as canonical.
