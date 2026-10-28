import { Env, ProtocolLimits } from "./protocol/types";
import { AsterRelayDO } from "./objects/AsterRelayDO";
import { GroupDO } from "./objects/GroupDO";
import { constantTimeCompare } from "./utils/crypto";

export { AsterRelayDO, GroupDO };

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
    },
  });
}

function errorResponse(error: string, status = 400): Response {
  return jsonResponse({ error }, status);
}

function checkAdminAccess(request: Request, env: Env): Response | null {
  const allowedIps = env.ASTER_ADMIN_ALLOWED_IPS;
  if (allowedIps && allowedIps.trim()) {
    const clientIp = request.headers.get("cf-connecting-ip") || "";
    const list = allowedIps.split(",").map((s) => s.trim());
    if (!list.includes("*") && !list.includes(clientIp)) {
      return errorResponse("Forbidden: administrative routes restricted to trusted host boundary", 403);
    }
  }

  const configuredKey = env.ASTER_ADMIN_KEY;
  if (!configuredKey || !configuredKey.trim()) {
    return errorResponse("Unauthorized", 401);
  }

  const headerKey = request.headers.get("X-Aster-Admin-Key") || "";
  if (!headerKey || !constantTimeCompare(headerKey, configuredKey)) {
    return errorResponse("Unauthorized", 401);
  }

  return null;
}

async function extractAuthToken(request: Request): Promise<string | null> {
  const authHeader = request.headers.get("Authorization");
  if (authHeader && authHeader.startsWith("Bearer ")) {
    const t = authHeader.substring(7).trim();
    if (t) return t;
  }
  const xToken = request.headers.get("X-Aster-Token");
  if (xToken && xToken.trim()) {
    return xToken.trim();
  }
  return null;
}

export default {
  async fetch(request: Request, env: Env, _ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const pathname = url.pathname;
    const method = request.method.toUpperCase();

    const relayId = env.ASTER_RELAY_DO.idFromName("global");
    const relayStub = env.ASTER_RELAY_DO.get(relayId) as any;

    // -------------------------------------------------------------------------
    // WebSocket Upgrade
    // -------------------------------------------------------------------------
    if (pathname === "/ws") {
      if (request.headers.get("Upgrade") !== "websocket") {
        return errorResponse("Expected Upgrade: websocket", 426);
      }
      return relayStub.fetch(request);
    }

    // -------------------------------------------------------------------------
    // Health Check
    // -------------------------------------------------------------------------
    if (pathname === "/health" && method === "GET") {
      const stats = await relayStub.getStats();
      return jsonResponse({
        status: "ok",
        users: stats.users,
        connections: stats.connections,
      });
    }

    // -------------------------------------------------------------------------
    // Admin Routes
    // -------------------------------------------------------------------------
    if (pathname === "/api/provision" && method === "POST") {
      const adminErr = checkAdminAccess(request, env);
      if (adminErr) return adminErr;

      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const userId = body.userId?.trim();
      const deviceId = body.deviceId?.trim() || crypto.randomUUID();

      if (!userId || !ProtocolLimits.USER_ID_REGEX.test(userId)) {
        return errorResponse("Invalid userId format", 400);
      }
      if (!ProtocolLimits.DEVICE_ID_REGEX.test(deviceId)) {
        return errorResponse("Invalid deviceId format", 400);
      }

      const token = await relayStub.createToken(userId, deviceId);
      return jsonResponse(
        {
          status: "ok",
          userId,
          deviceId,
          token,
        },
        201
      );
    }

    if (pathname === "/api/revoke" && method === "POST") {
      const adminErr = checkAdminAccess(request, env);
      if (adminErr) return adminErr;

      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const token = body.token?.trim();
      const userId = body.userId?.trim();
      const deviceId = body.deviceId?.trim();

      if (token) {
        const revoked = await relayStub.revokeToken(token);
        return jsonResponse({ status: "ok", revoked, target: "token" });
      }
      if (userId) {
        const revokedCount = await relayStub.revokeTokensForUser(userId);
        return jsonResponse({ status: "ok", revoked: true, revokedCount, target: "user" });
      }
      if (deviceId) {
        const revoked = await relayStub.revokeTokensForDevice(deviceId);
        return jsonResponse({ status: "ok", revoked, target: "device" });
      }

      return errorResponse("Must specify token, userId, or deviceId", 400);
    }

    // -------------------------------------------------------------------------
    // Public Authentication & Registration
    // -------------------------------------------------------------------------
    if ((pathname === "/api/register" || pathname === "/api/auth/register") && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const username = body.username?.trim();
      const displayName = body.displayName?.trim() || username;
      const password = body.password ? String(body.password) : null;
      const deviceId = body.deviceId?.trim() || crypto.randomUUID();

      if (!username || !ProtocolLimits.USERNAME_REGEX.test(username.toLowerCase())) {
        return errorResponse("Invalid username: must be 3-24 alphanumeric or underscore characters", 400);
      }
      if (!displayName || displayName.length > ProtocolLimits.MAX_DISPLAY_NAME_LENGTH) {
        return errorResponse("Invalid displayName: must be 1-32 non-blank characters", 400);
      }
      if (!ProtocolLimits.DEVICE_ID_REGEX.test(deviceId)) {
        return errorResponse("Invalid deviceId format", 400);
      }

      if (password !== null) {
        if (password.length < ProtocolLimits.MIN_PASSWORD_LENGTH) {
          return errorResponse("Password must be at least 8 characters", 400);
        }
        if (password.length > ProtocolLimits.MAX_PASSWORD_LENGTH) {
          return errorResponse("Password too long", 400);
        }
      }

      try {
        if (password !== null) {
          const res = await relayStub.createUserWithPassword(username, displayName, password, deviceId);
          return jsonResponse(
            {
              status: "ok",
              userId: res.user.user_id,
              asterId: res.user.aster_id,
              username: res.user.username,
              displayName: res.user.display_name,
              token: res.token,
              recoveryCode: res.recoveryCode,
            },
            201
          );
        } else {
          const res = await relayStub.createUser(username, displayName, deviceId);
          return jsonResponse(
            {
              status: "ok",
              userId: res.user.user_id,
              asterId: res.user.aster_id,
              username: res.user.username,
              displayName: res.user.display_name,
              token: res.token,
            },
            201
          );
        }
      } catch (e: any) {
        if (e.message?.includes("already taken")) {
          return errorResponse(e.message, 409);
        }
        return errorResponse(e.message || "Registration failed", 500);
      }
    }

    if (pathname === "/api/auth/login" && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const username = body.username?.trim();
      const password = body.password ? String(body.password) : "";
      const deviceId = body.deviceId?.trim() || crypto.randomUUID();

      if (!username || !password) {
        return errorResponse("Username and password are required", 400);
      }

      try {
        const res = await relayStub.loginUser(username, password, deviceId);
        return jsonResponse({
          status: "ok",
          userId: res.user.user_id,
          asterId: res.user.aster_id,
          username: res.user.username,
          displayName: res.user.display_name,
          token: res.token,
        });
      } catch (e: any) {
        if (e.message?.includes("ACCOUNT_MIGRATION_REQUIRED")) {
          return jsonResponse(
            {
              error: "ACCOUNT_MIGRATION_REQUIRED",
              requiresMigration: true,
              message: "Account requires password setup to migrate.",
            },
            403
          );
        }
        if (e.message?.includes("RATE_LIMIT")) {
          return errorResponse(e.message, 429);
        }
        return errorResponse("Invalid username or password", 401);
      }
    }

    if (pathname === "/api/auth/recover" && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const username = body.username?.trim();
      const recoveryCode = body.recoveryCode?.trim();
      const newPassword = body.newPassword ? String(body.newPassword) : "";
      const deviceId = body.deviceId?.trim() || crypto.randomUUID();

      if (!username || !recoveryCode || !newPassword) {
        return errorResponse("Username, recoveryCode, and newPassword are required", 400);
      }

      if (newPassword.length < ProtocolLimits.MIN_PASSWORD_LENGTH) {
        return errorResponse("New password must be at least 8 characters", 400);
      }

      try {
        const res = await relayStub.recoverPassword(username, recoveryCode, newPassword, deviceId);
        return jsonResponse({
          status: "ok",
          userId: res.user.user_id,
          asterId: res.user.aster_id,
          username: res.user.username,
          displayName: res.user.display_name,
          token: res.token,
          newRecoveryCode: res.newRecoveryCode,
        });
      } catch (e: any) {
        if (e.message?.includes("RATE_LIMIT")) {
          return errorResponse(e.message, 429);
        }
        return errorResponse("Invalid username or recovery code", 400);
      }
    }

    if (pathname === "/api/auth/migrate" && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const username = body.username?.trim();
      const newPassword = body.newPassword ? String(body.newPassword) : "";
      const deviceId = body.deviceId?.trim() || crypto.randomUUID();

      if (!username || !newPassword) {
        return errorResponse("Username and newPassword are required", 400);
      }

      if (newPassword.length < ProtocolLimits.MIN_PASSWORD_LENGTH) {
        return errorResponse("Password must be at least 8 characters", 400);
      }

      try {
        const res = await relayStub.migrateAccount(username, newPassword, deviceId);
        return jsonResponse({
          status: "ok",
          userId: res.user.user_id,
          asterId: res.user.aster_id,
          username: res.user.username,
          displayName: res.user.display_name,
          token: res.token,
          recoveryCode: res.recoveryCode,
        });
      } catch (e: any) {
        if (e.message?.includes("not found")) {
          return errorResponse("User not found", 404);
        }
        return errorResponse(e.message || "Migration failed", 400);
      }
    }

    // -------------------------------------------------------------------------
    // Authenticated Routes
    // -------------------------------------------------------------------------
    const token = await extractAuthToken(request);
    const tokenRecord = token ? await relayStub.validateToken(token) : null;
    if (!tokenRecord) {
      return errorResponse("Unauthorized", 401);
    }

    const currentUserId = tokenRecord.user_id;

    if (pathname === "/api/profile") {
      if (method === "GET") {
        const user = await relayStub.getUser(currentUserId);
        if (!user || user.is_active !== 1) {
          return errorResponse("User not found or inactive", 404);
        }
        return jsonResponse({
          status: "ok",
          userId: user.user_id,
          asterId: user.aster_id,
          username: user.username,
          displayName: user.display_name,
          createdAt: user.created_at,
          updatedAt: user.updated_at,
        });
      }

      if (method === "POST") {
        let body: any;
        try {
          body = await request.json();
        } catch {
          return errorResponse("Invalid JSON", 400);
        }

        const newDisplayName = body.displayName !== undefined ? body.displayName.trim() : null;
        const newUsername = body.username !== undefined ? body.username.trim() : null;

        if (newDisplayName !== null) {
          if (!newDisplayName || newDisplayName.length > ProtocolLimits.MAX_DISPLAY_NAME_LENGTH) {
            return errorResponse("Invalid displayName", 400);
          }
          await relayStub.updateDisplayName(currentUserId, newDisplayName);
        }

        if (newUsername !== null) {
          if (!ProtocolLimits.USERNAME_REGEX.test(newUsername.toLowerCase())) {
            return errorResponse("Invalid username format", 400);
          }
          const updated = await relayStub.updateUsername(currentUserId, newUsername);
          if (!updated) {
            return errorResponse("Username is already taken", 409);
          }
        }

        const updatedUser = await relayStub.getUser(currentUserId);
        if (!updatedUser) return errorResponse("User not found", 404);

        return jsonResponse({
          status: "ok",
          userId: updatedUser.user_id,
          asterId: updatedUser.aster_id,
          username: updatedUser.username,
          displayName: updatedUser.display_name,
          createdAt: updatedUser.created_at,
          updatedAt: updatedUser.updated_at,
        });
      }
    }

    if (pathname === "/api/users/search" && method === "GET") {
      const q = url.searchParams.get("q") || "";
      const results = await relayStub.searchUsers(q, currentUserId);
      return jsonResponse({ status: "ok", users: results });
    }

    if (pathname === "/api/friends/request" && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const targetUserId = body.targetUserId?.trim();
      const targetAsterId = body.targetAsterId?.trim();

      let targetUser = null;
      if (targetAsterId) {
        targetUser = await relayStub.getUserByAsterId(targetAsterId);
      } else if (targetUserId) {
        targetUser = await relayStub.getUser(targetUserId);
      }

      if (!targetUser || targetUser.is_active !== 1) {
        return errorResponse("Target user not found", 404);
      }
      if (targetUser.user_id === currentUserId) {
        return errorResponse("Cannot send friend request to yourself", 400);
      }

      try {
        const reqSummary = await relayStub.sendFriendRequest(currentUserId, targetUser.user_id);
        return jsonResponse(reqSummary, 201);
      } catch (e: any) {
        if (e.message?.includes("prohibited due to user block")) {
          return errorResponse("Cannot send friend request: user unavailable", 403);
        }
        return errorResponse(e.message || "Failed to send friend request", 409);
      }
    }

    if (pathname === "/api/friends/respond" && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const requestId = body.requestId?.trim();
      const action = body.action?.trim()?.toUpperCase();

      if (!requestId || (action !== "ACCEPT" && action !== "REJECT")) {
        return errorResponse("Must provide requestId and valid action ('ACCEPT' or 'REJECT')", 400);
      }

      const updated = await relayStub.respondFriendRequest(requestId, currentUserId, action === "ACCEPT");
      if (!updated) {
        return errorResponse("Pending friend request not found", 404);
      }
      return jsonResponse({ status: "ok", requestId, action });
    }

    if (pathname === "/api/friends/cancel" && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const requestId = body.requestId?.trim();
      if (!requestId) {
        return errorResponse("Must provide requestId", 400);
      }

      const cancelled = await relayStub.cancelFriendRequest(requestId, currentUserId);
      if (!cancelled) {
        return errorResponse("Pending friend request not found", 404);
      }
      return jsonResponse({ status: "ok", requestId, action: "CANCELLED" });
    }

    if (pathname === "/api/friends/requests" && method === "GET") {
      const incoming = await relayStub.getIncomingFriendRequests(currentUserId);
      const outgoing = await relayStub.getOutgoingFriendRequests(currentUserId);
      return jsonResponse({ status: "ok", incoming, outgoing });
    }

    if (pathname === "/api/friends" && method === "GET") {
      const friends = await relayStub.getFriends(currentUserId);
      return jsonResponse({ status: "ok", friends });
    }

    if (pathname === "/api/friends/block" && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const targetUserId = body.targetUserId?.trim();
      const targetAsterId = body.targetAsterId?.trim();

      let targetUser = null;
      if (targetAsterId) {
        targetUser = await relayStub.getUserByAsterId(targetAsterId);
      } else if (targetUserId) {
        targetUser = await relayStub.getUser(targetUserId);
      }

      if (!targetUser) return errorResponse("Target user not found", 404);
      if (targetUser.user_id === currentUserId) return errorResponse("Cannot block yourself", 400);

      await relayStub.blockUser(currentUserId, targetUser.user_id);
      return jsonResponse({ status: "ok", blockedUserId: targetUser.user_id });
    }

    if (pathname === "/api/friends/unblock" && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const targetUserId = body.targetUserId?.trim();
      if (!targetUserId) return errorResponse("Must provide targetUserId", 400);

      await relayStub.unblockUser(currentUserId, targetUserId);
      return jsonResponse({ status: "ok", unblockedUserId: targetUserId });
    }

    if (pathname === "/api/friends/blocked" && method === "GET") {
      const blocked = await relayStub.getBlockedUsers(currentUserId);
      return jsonResponse({ status: "ok", blocked });
    }

    if (pathname === "/api/messages/reads" && method === "GET") {
      const conversationId = url.searchParams.get("conversationId");
      if (!conversationId) return errorResponse("conversationId required", 400);
      const reads = await relayStub.getMessageReads(conversationId);
      return jsonResponse({ status: "ok", reads });
    }

    if (pathname === "/api/messages/unsent" && method === "GET") {
      const conversationId = url.searchParams.get("conversationId");
      if (!conversationId) return errorResponse("conversationId required", 400);
      const unsent = await relayStub.getUnsentMessages(conversationId);
      return jsonResponse({ status: "ok", unsent });
    }

    if (pathname === "/api/presence" && method === "GET") {
      const userIdsParam = url.searchParams.get("userIds") || "";
      const userIds = userIdsParam.split(",").map((s) => s.trim()).filter(Boolean);
      const presence = await relayStub.getPresence(userIds);
      return jsonResponse({ status: "ok", presence });
    }

    if (pathname.startsWith("/api/users/") && method === "GET") {
      const targetUserId = pathname.slice("/api/users/".length).trim();
      if (!targetUserId) return errorResponse("User ID required", 400);
      const user = await relayStub.getUser(targetUserId);
      if (!user) {
        return errorResponse("User not found", 404);
      }
      const summary = await relayStub.formatUserSummary(user);
      return jsonResponse({ status: "ok", user: summary });
    }

    if (pathname === "/api/logout" && method === "POST") {
      await relayStub.revokeToken(token!);
      await relayStub.closeDeviceSession(currentUserId, tokenRecord.device_id, "User logged out");
      return jsonResponse({ status: "ok", message: "Logged out successfully" });
    }

    if (pathname === "/api/account/delete" && method === "POST") {
      await relayStub.deleteAccount(currentUserId);
      return jsonResponse({ status: "ok", message: "Account deleted successfully" });
    }

    if (pathname === "/api/push/register" && method === "POST") {
      let body: any;
      try {
        body = await request.json();
      } catch {
        return errorResponse("Invalid JSON", 400);
      }

      const pushToken = body.pushToken?.trim();
      const deviceId = body.deviceId?.trim() || tokenRecord.device_id;
      const endpoint = body.endpoint?.trim();

      if (!pushToken) {
        return errorResponse("Must specify pushToken", 400);
      }

      await relayStub.registerPushToken(currentUserId, deviceId, pushToken, endpoint);
      return jsonResponse({ status: "ok", userId: currentUserId });
    }

    return errorResponse("Not Found", 404);
  },
};
