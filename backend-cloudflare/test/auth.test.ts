import { describe, it, expect, beforeAll, afterAll } from "vitest";
import {
  getTestWorker,
  stopTestWorker,
  provisionUser,
  TestWsClient,
  TEST_ADMIN_KEY,
} from "./helpers/test-client";
import { Unstable_DevWorker } from "wrangler";

describe("Aster Cloudflare Backend - Authentication & Sessions", () => {
  let worker: Unstable_DevWorker;

  beforeAll(async () => {
    worker = await getTestWorker();
  });

  afterAll(async () => {
    await stopTestWorker();
  });

  it("GET /health reports ok and connection stats", async () => {
    const res = await worker.fetch("/health");
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.status).toBe("ok");
    expect(typeof body.users).toBe("number");
    expect(typeof body.connections).toBe("number");
  });

  it("POST /api/register creates a new user and token", async () => {
    const username = `alice_${Date.now()}`.slice(0, 20);
    const res = await worker.fetch("/api/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username,
        displayName: "Alice Test",
        deviceId: "dev_alice_1",
      }),
    });

    expect(res.status).toBe(201);
    const data = (await res.json()) as any;
    expect(data.status).toBe("ok");
    expect(data.userId).toMatch(/^user_/);
    expect(data.asterId).toMatch(/^AST-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}$/);
    expect(data.username).toBe(username);
    expect(data.displayName).toBe("Alice Test");
    expect(data.token).toMatch(/^ast_tok_/);
  });

  it("POST /api/provision creates credentials with admin key", async () => {
    const prov = await provisionUser(worker, "user_saad", "dev_saad_test");
    expect(prov.userId).toBe("user_saad");
    expect(prov.deviceId).toBe("dev_saad_test");
    expect(prov.token).toMatch(/^ast_tok_/);
  });

  it("WebSocket authenticates successfully with valid token", async () => {
    const prov = await provisionUser(worker, "user_saad", "dev_saad_ws");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;
    const client = new TestWsClient(wsUrl);

    await client.connect();
    const authOk = await client.sendAndAwaitAuth(prov.token);
    expect(authOk.type).toBe("AUTH_OK");
    expect(authOk.userId).toBe("user_saad");

    client.send({ type: "PING" });
    const pong = await client.waitForMessage((m) => m.type === "PONG");
    expect(pong.type).toBe("PONG");

    client.close();
  });

  it("WebSocket rejects invalid or revoked tokens with AUTH_ERROR", async () => {
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;
    const client = new TestWsClient(wsUrl);

    await client.connect();
    const authError = await client.sendAndAwaitAuth("ast_tok_invalid_garbage_token_123");
    expect(authError.type).toBe("AUTH_ERROR");
    expect(authError.reason).toContain("Invalid or revoked");

    client.close();
  });

  it("Enforces single active session replacement (code 4002)", async () => {
    const prov = await provisionUser(worker, "user_saad", "dev_saad_replace");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const client1 = new TestWsClient(wsUrl);
    await client1.connect();
    const auth1 = await client1.sendAndAwaitAuth(prov.token);
    expect(auth1.type).toBe("AUTH_OK");

    // Client 2 connects for the same user
    const client2 = new TestWsClient(wsUrl);
    await client2.connect();
    const auth2 = await client2.sendAndAwaitAuth(prov.token);
    expect(auth2.type).toBe("AUTH_OK");

    // Client 1 should receive clean close with code 4002
    const closeInfo = await client1.waitForClose(3000);
    expect(closeInfo.code).toBe(4002);
    expect(closeInfo.reason).toContain("Replaced by new connection");

    client2.close();
  });

  it("POST /api/revoke revokes token and terminates active session", async () => {
    const prov = await provisionUser(worker, "user_saad", "dev_saad_revoke");
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;

    const client = new TestWsClient(wsUrl);
    await client.connect();
    await client.sendAndAwaitAuth(prov.token);

    // Revoke token via admin API
    const revRes = await worker.fetch("/api/revoke", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Aster-Admin-Key": TEST_ADMIN_KEY,
      },
      body: JSON.stringify({ token: prov.token }),
    });
    expect(revRes.status).toBe(200);

    // Active session should be terminated
    const closeInfo = await client.waitForClose(3000);
    expect(closeInfo.code).toBe(4000);

    // Reconnecting with revoked token should fail
    const client2 = new TestWsClient(wsUrl);
    await client2.connect();
    const authError = await client2.sendAndAwaitAuth(prov.token);
    expect(authError.type).toBe("AUTH_ERROR");
    client2.close();
  });

  it("POST /api/auth/register creates user with password and returns recovery code", async () => {
    const username = `bob_${Date.now()}`.slice(0, 20);
    const res = await worker.fetch("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username,
        displayName: "Bob Test",
        password: "SuperSecretPassword123!",
      }),
    });

    expect(res.status).toBe(201);
    const data = (await res.json()) as any;
    expect(data.status).toBe("ok");
    expect(data.userId).toMatch(/^user_/);
    expect(data.username).toBe(username);
    expect(data.displayName).toBe("Bob Test");
    expect(data.token).toMatch(/^ast_tok_/);
    expect(data.recoveryCode).toMatch(/^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/);
  });

  it("POST /api/auth/login succeeds with valid credentials and establishes session", async () => {
    const username = `clara_${Date.now()}`.slice(0, 20);
    const password = "ClaraPassword2026!";

    // Register
    const regRes = await worker.fetch("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, displayName: "Clara", password }),
    });
    expect(regRes.status).toBe(201);

    // Login with correct password
    const loginRes = await worker.fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    expect(loginRes.status).toBe(200);
    const loginData = (await loginRes.json()) as any;
    expect(loginData.status).toBe("ok");
    expect(loginData.username).toBe(username);
    expect(loginData.token).toMatch(/^ast_tok_/);

    // Verify session token connects over WebSocket
    const wsUrl = `ws://${worker.address}:${worker.port}/ws`;
    const client = new TestWsClient(wsUrl);
    await client.connect();
    const authOk = await client.sendAndAwaitAuth(loginData.token);
    expect(authOk.type).toBe("AUTH_OK");
    client.close();
  });

  it("POST /api/auth/login rejects invalid password and unknown user with generic message", async () => {
    const username = `dave_${Date.now()}`.slice(0, 20);
    const password = "DavePassword2026!";

    await worker.fetch("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });

    // Incorrect password
    const badPassRes = await worker.fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password: "WrongPassword999!" }),
    });
    expect(badPassRes.status).toBe(401);
    const badPassData = (await badPassRes.json()) as any;
    expect(badPassData.error).toBe("Invalid username or password");

    // Unknown username
    const unknownRes = await worker.fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "unknown_user_99999", password }),
    });
    expect(unknownRes.status).toBe(401);
    const unknownData = (await unknownRes.json()) as any;
    expect(unknownData.error).toBe("Invalid username or password");
  });

  it("Password recovery flow resets password, accepts normalized recovery code, and invalidates old code", async () => {
    const username = `eva_${Date.now()}`.slice(0, 20);
    const oldPassword = "OldPassword123!";
    const newPassword = "BrandNewPassword456!";

    // Register
    const regRes = await worker.fetch("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password: oldPassword }),
    });
    const regData = (await regRes.json()) as any;
    const initialRecoveryCode = regData.recoveryCode;
    expect(initialRecoveryCode).toBeDefined();

    // Recover using normalized recovery code (e.g. lowercase with spaces or hyphens)
    const formattedCode = initialRecoveryCode.toLowerCase().replace(/-/g, " ");
    const recoverRes = await worker.fetch("/api/auth/recover", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username,
        recoveryCode: formattedCode,
        newPassword,
      }),
    });
    expect(recoverRes.status).toBe(200);
    const recoverData = (await recoverRes.json()) as any;
    expect(recoverData.status).toBe("ok");
    expect(recoverData.newRecoveryCode).toBeDefined();
    expect(recoverData.newRecoveryCode).not.toBe(initialRecoveryCode);

    // Old password must now be rejected
    const oldLoginRes = await worker.fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password: oldPassword }),
    });
    expect(oldLoginRes.status).toBe(401);

    // New password must be accepted
    const newLoginRes = await worker.fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password: newPassword }),
    });
    expect(newLoginRes.status).toBe(200);

    // Old recovery code must be rejected if reused
    const reuseRecoveryRes = await worker.fetch("/api/auth/recover", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username,
        recoveryCode: initialRecoveryCode,
        newPassword: "AnotherPassword789!",
      }),
    });
    expect(reuseRecoveryRes.status).toBe(400);
  });

  it("Legacy account without password prompts migration and can be migrated", async () => {
    // Create an unmigrated legacy user without password
    const legacyUsername = `legacy_${Date.now()}`.slice(0, 20);
    const regRes = await worker.fetch("/api/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: legacyUsername, displayName: "Legacy User" }),
    });
    expect(regRes.status).toBe(201);

    const loginRes = await worker.fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: legacyUsername, password: "SomePassword123!" }),
    });
    expect(loginRes.status).toBe(403);
    const loginData = (await loginRes.json()) as any;
    expect(loginData.requiresMigration).toBe(true);

    // Migrate account by setting initial password
    const migrateRes = await worker.fetch("/api/auth/migrate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: legacyUsername, newPassword: "LegacyMigratedPassword2026!" }),
    });
    expect(migrateRes.status).toBe(200);
    const migrateData = (await migrateRes.json()) as any;
    expect(migrateData.status).toBe("ok");
    expect(migrateData.username).toBe(legacyUsername);
    expect(migrateData.recoveryCode).toBeDefined();

    // Now login succeeds with the migrated password
    const afterMigrateLogin = await worker.fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: legacyUsername, password: "LegacyMigratedPassword2026!" }),
    });
    expect(afterMigrateLogin.status).toBe(200);

    // Attempting migration a second time is rejected
    const secondMigrate = await worker.fetch("/api/auth/migrate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: legacyUsername, newPassword: "AnotherPassword123!" }),
    });
    expect(secondMigrate.status).toBe(400);
  });
});
