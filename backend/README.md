# Aster Realtime WebSocket Relay Backend

A lightweight, zero-cost, Kotlin/Ktor-based WebSocket relay server for the Aster messaging platform.

## Overview

The Aster backend is designed according to the Aster architecture guidelines:
- **Lightweight**: Embedded Ktor CIO server with minimal memory footprint.
- **Cost**: ₹0 operating cost, runnable locally or on free/low-spec VPS nodes.
- **Target Scale**: Optimized for private groups (~100 users).
- **Local-First Alignment**: Room on Android remains the single source of truth for message persistence. The server acts as an ephemeral realtime relay and ACK coordinator.
- **Message Identity**: The server strictly preserves client-generated message IDs (`messageId`) to ensure reliable end-to-end reconciliation across offline/reconnection cycles.

## Technology Stack

- **Language**: Kotlin 2.0.21 (JVM 17)
- **Framework**: Ktor Server 2.3.12 (CIO Engine)
- **WebSockets**: Ktor WebSockets plugin with automatic ping/pong keepalive
- **Serialization**: Kotlinx Serialization JSON
- **Build System**: Gradle 8.11 with Kotlin DSL

## Endpoints

### 1. Health Check
- **Route**: `GET /health`
- **Response**: `{"status":"ok","users":1,"connections":1}`

### 2. Realtime WebSocket
- **Route**: `GET /ws`
- **Protocol**: Duplex JSON messages (UTF-8 text frames).

## Wire Protocol Summary

### Client → Server Messages

1. **AUTH**
   ```json
   {
     "type": "AUTH",
     "userId": "user_saad"
   }
   ```
2. **SEND_MESSAGE**
   ```json
   {
     "type": "SEND_MESSAGE",
     "messageId": "msg_01JB0E4Z7...",
     "conversationId": "conv_alex",
     "recipientId": "user_alex",
     "body": "Hello Alex!",
     "timestamp": 1728300000000
   }
   ```
3. **ACK_RECEIVED**
   ```json
   {
     "type": "ACK_RECEIVED",
     "messageId": "msg_01JB0E4Z7..."
   }
   ```
4. **PING**
   ```json
   {
     "type": "PING",
     "timestamp": 1728300000000
   }
   ```

### Server → Client Messages

1. **AUTH_OK**
   ```json
   {
     "type": "AUTH_OK",
     "userId": "user_saad"
   }
   ```
2. **AUTH_ERROR**
   ```json
   {
     "type": "AUTH_ERROR",
     "reason": "Invalid userId format"
   }
   ```
3. **MESSAGE_ACCEPTED** (Sender ACK: transitions client message to `SENT`)
   ```json
   {
     "type": "MESSAGE_ACCEPTED",
     "messageId": "msg_01JB0E4Z7...",
     "timestamp": 1728300000100
   }
   ```
4. **MESSAGE** (Relayed to recipient)
   ```json
   {
     "type": "MESSAGE",
     "messageId": "msg_01JB0E4Z7...",
     "conversationId": "conv_alex",
     "senderId": "user_saad",
     "body": "Hello Alex!",
     "timestamp": 1728300000000
   }
   ```
5. **ERROR**
   ```json
   {
     "type": "ERROR",
     "code": "MALFORMED_JSON",
     "message": "Detailed error message"
   }
   ```
6. **PONG**
   ```json
   {
     "type": "PONG",
     "timestamp": 1728300000100
   }
   ```

## Development & Testing

### Running Tests
```bash
cd backend
./gradlew test
```

### Running the Server
```bash
cd backend
ASTER_HOST=0.0.0.0 ASTER_PORT=8080 ./gradlew run
```

### Health Verification
```bash
curl http://127.0.0.1:8080/health
```
