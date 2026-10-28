export interface Env {
  ASTER_RELAY_DO: DurableObjectNamespace;
  GROUP_DO: DurableObjectNamespace;
  ASTER_ADMIN_KEY?: string;
  ASTER_ADMIN_ALLOWED_IPS?: string;
  ASTER_REGISTRATION_RATE_LIMIT?: string;
}

export const ProtocolLimits = {
  MAX_FRAME_SIZE: 65536, // 64 KB
  MAX_BODY_LENGTH: 4096,
  MIN_BODY_LENGTH: 1,
  MAX_USER_ID_LENGTH: 64,
  MAX_CONVERSATION_ID_LENGTH: 64,
  MAX_MESSAGE_ID_LENGTH: 128,
  MAX_REQUEST_ID_LENGTH: 128,
  MAX_GROUP_NAME_LENGTH: 64,
  MIN_GROUP_NAME_LENGTH: 1,
  MAX_GROUP_MEMBERS: 100,
  MIN_TIMESTAMP: 1, // Must be positive
  MAX_FUTURE_DRIFT_MS: 86400000, // 24 hours

  MAX_TOKEN_LENGTH: 256,
  MIN_TOKEN_LENGTH: 16,

  MIN_USERNAME_LENGTH: 3,
  MAX_USERNAME_LENGTH: 24,
  MIN_DISPLAY_NAME_LENGTH: 1,
  MAX_DISPLAY_NAME_LENGTH: 32,
  MIN_PASSWORD_LENGTH: 8,
  MAX_PASSWORD_LENGTH: 128,

  USER_ID_REGEX: /^user_[a-zA-Z0-9_]{1,64}$/,
  DEVICE_ID_REGEX: /^[a-zA-Z0-9_-]{1,128}$/,
  CONVERSATION_ID_REGEX: /^[a-zA-Z0-9_-]{1,64}$/,
  GROUP_ID_REGEX: /^group_[a-zA-Z0-9_-]{1,64}$/,
  MESSAGE_ID_REGEX: /^[a-zA-Z0-9_-]{1,128}$/,
  REQUEST_ID_REGEX: /^[a-zA-Z0-9_-]{1,128}$/,
  USERNAME_REGEX: /^[a-zA-Z0-9_]{3,24}$/,
  ASTER_ID_REGEX: /^AST-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}$/,
} as const;

export interface UserSummary {
  userId: string;
  asterId: string;
  username: string;
  displayName: string;
  isOnline?: boolean;
  lastSeen?: number;
  isDeleted?: boolean;
}

export interface FriendRequestSummary {
  id: string;
  senderUserId: string;
  recipientUserId: string;
  status: string;
  sender?: UserSummary | null;
  recipient?: UserSummary | null;
  createdAt: number;
}

export interface GroupSummary {
  id: string;
  name: string;
  creatorId: string;
  memberCount: number;
  role: string;
  createdAt: number;
  updatedAt: number;
}

export interface GroupMemberSummary {
  userId: string;
  role: string;
  joinedAt: number;
}

export type DeliveryState = "QUEUED" | "DELIVERED" | "ACKED";

export interface MailboxMessageRecord {
  message_id: string;
  conversation_id: string;
  sender_user_id: string;
  recipient_user_id: string;
  body: string;
  client_timestamp: number;
  server_received_timestamp: number;
  delivery_state: DeliveryState;
  reply_to_id?: string | null;
  effect?: string | null;
}

export interface UserRecord {
  user_id: string;
  aster_id: string;
  username: string;
  display_name: string;
  created_at: number;
  updated_at: number;
  is_active: number;
  password_hash?: string | null;
  password_salt?: string | null;
  recovery_hash?: string | null;
  recovery_salt?: string | null;
  failed_login_attempts?: number;
  last_failed_login?: number;
  failed_recovery_attempts?: number;
  last_failed_recovery?: number;
  last_seen?: number;
  is_deleted?: number;
}

export interface AuthTokenRecord {
  token_hash: string;
  user_id: string;
  device_id: string;
  created_at: number;
  revoked_at: number | null;
}

export interface GroupRecord {
  group_id: string;
  name: string;
  creator_user_id: string;
  created_at: number;
  updated_at: number;
  is_active: number;
}

export interface GroupMemberRecord {
  group_id: string;
  user_id: string;
  role: string;
  joined_at: number;
  is_active: number;
  left_at: number | null;
}

// Client -> Server Frames
export interface AuthClientMessage {
  type: "AUTH";
  token: string;
  userId?: string | null;
  deviceId?: string | null;
}

export interface SendMessageClientMessage {
  type: "SEND_MESSAGE";
  messageId: string;
  conversationId: string;
  recipientId?: string | null;
  body: string;
  timestamp: number;
  senderId?: string | null;
  replyToId?: string | null;
  effect?: string | null;
}

export interface ToggleReactionClientMessage {
  type: "TOGGLE_REACTION";
  messageId: string;
  conversationId: string;
  emoji: string;
}

export interface AckReceivedClientMessage {
  type: "ACK_RECEIVED";
  messageId: string;
}

export interface PingClientMessage {
  type: "PING";
  timestamp?: number;
}

export interface CreateGroupClientMessage {
  type: "CREATE_GROUP";
  requestId?: string;
  name: string;
  memberIds?: string[];
}

export interface GetGroupsClientMessage {
  type: "GET_GROUPS";
  requestId?: string;
}

export interface GetGroupMembersClientMessage {
  type: "GET_GROUP_MEMBERS";
  requestId?: string;
  groupId: string;
}

export interface AddGroupMemberClientMessage {
  type: "ADD_GROUP_MEMBER";
  requestId?: string;
  groupId: string;
  userId: string;
}

export interface RemoveGroupMemberClientMessage {
  type: "REMOVE_GROUP_MEMBER";
  requestId?: string;
  groupId: string;
  userId: string;
}

export interface LeaveGroupClientMessage {
  type: "LEAVE_GROUP";
  requestId?: string;
  groupId: string;
}

export interface RenameGroupClientMessage {
  type: "RENAME_GROUP";
  requestId?: string;
  groupId: string;
  newName: string;
}

export interface MarkReadClientMessage {
  type: "MARK_READ";
  messageId: string;
  conversationId: string;
}

export interface UnsendMessageClientMessage {
  type: "UNSEND_MESSAGE";
  requestId?: string;
  messageId: string;
  conversationId: string;
}

export type ClientMessage =
  | AuthClientMessage
  | SendMessageClientMessage
  | AckReceivedClientMessage
  | PingClientMessage
  | CreateGroupClientMessage
  | GetGroupsClientMessage
  | GetGroupMembersClientMessage
  | AddGroupMemberClientMessage
  | RemoveGroupMemberClientMessage
  | LeaveGroupClientMessage
  | RenameGroupClientMessage
  | MarkReadClientMessage
  | UnsendMessageClientMessage
  | ToggleReactionClientMessage;

// Server -> Client Frames
export interface AuthOkServerMessage {
  type: "AUTH_OK";
  userId: string;
}

export interface AuthErrorServerMessage {
  type: "AUTH_ERROR";
  reason: string;
}

export interface MessageAcceptedServerMessage {
  type: "MESSAGE_ACCEPTED";
  messageId: string;
  timestamp: number;
}

export interface MessageDeliveredServerMessage {
  type: "MESSAGE_DELIVERED";
  messageId: string;
}

export interface RelayServerMessage {
  type: "MESSAGE";
  messageId: string;
  conversationId: string;
  senderId: string;
  body: string;
  timestamp: number;
  replyToId?: string;
  effect?: string;
  reactions?: Record<string, string[]>;
}

export interface ReactionUpdateServerMessage {
  type: "REACTION_UPDATE";
  messageId: string;
  conversationId: string;
  userId: string;
  emoji: string;
  action: "ADDED" | "REMOVED";
  reactions: Record<string, string[]>;
  timestamp: number;
}

export interface ErrorServerMessage {
  type: "ERROR";
  code: string;
  message: string;
  requestId?: string | null;
}

export interface PongServerMessage {
  type: "PONG";
  timestamp: number;
}

export interface GroupOperationOkServerMessage {
  type: "GROUP_OPERATION_OK";
  requestId: string;
  groupId: string;
  action: string;
  name?: string | null;
}

export interface GroupListServerMessage {
  type: "GROUP_LIST";
  requestId: string;
  groups: GroupSummary[];
}

export interface GroupMembersListServerMessage {
  type: "GROUP_MEMBERS_LIST";
  requestId: string;
  groupId: string;
  members: GroupMemberSummary[];
}

export interface GroupEventServerMessage {
  type: "GROUP_EVENT";
  groupId: string;
  eventType: string; // "CREATED" | "MEMBER_ADDED" | "MEMBER_REMOVED" | "MEMBER_LEFT" | "RENAMED"
  actorId: string;
  targetUserId?: string | null;
  name?: string | null;
  timestamp: number;
}

export interface MessageReadServerMessage {
  type: "MESSAGE_READ";
  messageId: string;
  conversationId: string;
  readerId: string;
  timestamp: number;
}

export interface MessageUnsentServerMessage {
  type: "MESSAGE_UNSENT";
  messageId: string;
  conversationId: string;
  senderId: string;
  timestamp: number;
}

export interface PresenceUpdateServerMessage {
  type: "PRESENCE_UPDATE";
  userId: string;
  isOnline: boolean;
  lastSeen: number;
  timestamp?: number;
}

export interface UserDeletedServerMessage {
  type: "USER_DELETED";
  userId: string;
}

export type ServerMessage =
  | AuthOkServerMessage
  | AuthErrorServerMessage
  | MessageAcceptedServerMessage
  | MessageDeliveredServerMessage
  | RelayServerMessage
  | ErrorServerMessage
  | PongServerMessage
  | GroupOperationOkServerMessage
  | GroupListServerMessage
  | GroupMembersListServerMessage
  | GroupEventServerMessage
  | MessageReadServerMessage
  | MessageUnsentServerMessage
  | PresenceUpdateServerMessage
  | UserDeletedServerMessage
  | ReactionUpdateServerMessage;
