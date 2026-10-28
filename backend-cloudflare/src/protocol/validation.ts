import {
  ClientMessage,
  ErrorServerMessage,
  ProtocolLimits,
} from "./types";

export const KNOWN_TYPES = new Set([
  "AUTH",
  "SEND_MESSAGE",
  "ACK_RECEIVED",
  "PING",
  "CREATE_GROUP",
  "GET_GROUPS",
  "GET_GROUP_MEMBERS",
  "ADD_GROUP_MEMBER",
  "REMOVE_GROUP_MEMBER",
  "LEAVE_GROUP",
  "RENAME_GROUP",
  "MARK_READ",
  "UNSEND_MESSAGE",
  "TOGGLE_REACTION",
]);

export interface ParseResult {
  message?: ClientMessage;
  error?: ErrorServerMessage;
}

export function parseAndValidateFrame(raw: string): ParseResult {
  if (raw.length > ProtocolLimits.MAX_FRAME_SIZE) {
    return {
      error: {
        type: "ERROR",
        code: "PAYLOAD_TOO_LARGE",
        message: `Frame exceeds maximum size of ${ProtocolLimits.MAX_FRAME_SIZE} bytes`,
      },
    };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (e: any) {
    return {
      error: {
        type: "ERROR",
        code: "MALFORMED_JSON",
        message: `Invalid JSON syntax: ${e.message}`,
      },
    };
  }

  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return {
      error: {
        type: "ERROR",
        code: "MALFORMED_JSON",
        message: "Payload must be a JSON object",
      },
    };
  }

  const obj = parsed as Record<string, unknown>;
  const type = obj["type"];
  if (typeof type !== "string" || !type) {
    return {
      error: {
        type: "ERROR",
        code: "MISSING_TYPE",
        message: "Field 'type' is required",
      },
    };
  }

  if (!KNOWN_TYPES.has(type)) {
    return {
      error: {
        type: "ERROR",
        code: "UNKNOWN_TYPE",
        message: `Unknown message type: '${type}'`,
      },
    };
  }

  try {
    switch (type) {
      case "AUTH": {
        const token = String(obj["token"] || "");
        const userId = obj["userId"] !== undefined ? String(obj["userId"]) : undefined;
        const deviceId = obj["deviceId"] !== undefined ? String(obj["deviceId"]) : undefined;
        return {
          message: {
            type: "AUTH",
            token,
            userId,
            deviceId,
          },
        };
      }
      case "SEND_MESSAGE": {
        const messageId = String(obj["messageId"] || "");
        const conversationId = String(obj["conversationId"] || "");
        const recipientId = obj["recipientId"] !== undefined ? String(obj["recipientId"]) : null;
        const body = String(obj["body"] || "");
        const timestamp = Number(obj["timestamp"] || 0);
        const senderId = obj["senderId"] !== undefined ? String(obj["senderId"]) : null;
        const replyToId = obj["replyToId"] !== undefined && obj["replyToId"] !== null ? String(obj["replyToId"]) : null;
        const effect = obj["effect"] !== undefined && obj["effect"] !== null ? String(obj["effect"]) : null;
        return {
          message: {
            type: "SEND_MESSAGE",
            messageId,
            conversationId,
            recipientId,
            body,
            timestamp,
            senderId,
            replyToId,
            effect,
          },
        };
      }
      case "ACK_RECEIVED": {
        const messageId = String(obj["messageId"] || "");
        return {
          message: {
            type: "ACK_RECEIVED",
            messageId,
          },
        };
      }
      case "PING": {
        const timestamp = obj["timestamp"] !== undefined ? Number(obj["timestamp"]) : Date.now();
        return {
          message: {
            type: "PING",
            timestamp,
          },
        };
      }
      case "CREATE_GROUP": {
        const requestId = String(obj["requestId"] || "");
        const name = String(obj["name"] || "");
        const memberIds = Array.isArray(obj["memberIds"])
          ? (obj["memberIds"] as unknown[]).map(String)
          : [];
        return {
          message: {
            type: "CREATE_GROUP",
            requestId,
            name,
            memberIds,
          },
        };
      }
      case "GET_GROUPS": {
        const requestId = String(obj["requestId"] || "");
        return {
          message: {
            type: "GET_GROUPS",
            requestId,
          },
        };
      }
      case "GET_GROUP_MEMBERS": {
        const requestId = String(obj["requestId"] || "");
        const groupId = String(obj["groupId"] || "");
        return {
          message: {
            type: "GET_GROUP_MEMBERS",
            requestId,
            groupId,
          },
        };
      }
      case "ADD_GROUP_MEMBER": {
        const requestId = String(obj["requestId"] || "");
        const groupId = String(obj["groupId"] || "");
        const userId = String(obj["userId"] || "");
        return {
          message: {
            type: "ADD_GROUP_MEMBER",
            requestId,
            groupId,
            userId,
          },
        };
      }
      case "REMOVE_GROUP_MEMBER": {
        const requestId = String(obj["requestId"] || "");
        const groupId = String(obj["groupId"] || "");
        const userId = String(obj["userId"] || "");
        return {
          message: {
            type: "REMOVE_GROUP_MEMBER",
            requestId,
            groupId,
            userId,
          },
        };
      }
      case "LEAVE_GROUP": {
        const requestId = String(obj["requestId"] || "");
        const groupId = String(obj["groupId"] || "");
        return {
          message: {
            type: "LEAVE_GROUP",
            requestId,
            groupId,
          },
        };
      }
      case "RENAME_GROUP": {
        const requestId = String(obj["requestId"] || "");
        const groupId = String(obj["groupId"] || "");
        const newName = String(obj["newName"] || "");
        return {
          message: {
            type: "RENAME_GROUP",
            requestId,
            groupId,
            newName,
          },
        };
      }
      case "MARK_READ": {
        const messageId = String(obj["messageId"] || "");
        const conversationId = String(obj["conversationId"] || "");
        return {
          message: {
            type: "MARK_READ",
            messageId,
            conversationId,
          },
        };
      }
      case "UNSEND_MESSAGE": {
        const messageId = String(obj["messageId"] || "");
        const conversationId = String(obj["conversationId"] || "");
        const requestId = obj["requestId"] ? String(obj["requestId"]) : undefined;
        return {
          message: {
            type: "UNSEND_MESSAGE",
            requestId,
            messageId,
            conversationId,
          },
        };
      }
      case "TOGGLE_REACTION": {
        const messageId = String(obj["messageId"] || "");
        const conversationId = String(obj["conversationId"] || "");
        const emoji = String(obj["emoji"] || "");
        return {
          message: {
            type: "TOGGLE_REACTION",
            messageId,
            conversationId,
            emoji,
          },
        };
      }
    }
  } catch (e: any) {
    return {
      error: {
        type: "ERROR",
        code: "MALFORMED_JSON",
        message: `Missing or invalid fields for ${type}: ${e.message}`,
      },
    };
  }

  return {
    error: {
      type: "ERROR",
      code: "UNKNOWN_TYPE",
      message: `Unknown message type: '${type}'`,
    },
  };
}
