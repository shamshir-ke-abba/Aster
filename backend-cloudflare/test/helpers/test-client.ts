import { unstable_dev, Unstable_DevWorker } from "wrangler";
import WebSocket from "ws";

export const TEST_ADMIN_KEY = "test_secret_admin_key_123";

let globalWorker: Unstable_DevWorker | null = null;

export async function getTestWorker(): Promise<Unstable_DevWorker> {
  if (!globalWorker) {
    globalWorker = await unstable_dev("src/index.ts", {
      config: "wrangler.jsonc",
      experimental: { disableExperimentalWarning: true },
      vars: {
        ASTER_ADMIN_KEY: TEST_ADMIN_KEY,
      },
    });
  }
  return globalWorker;
}

export async function stopTestWorker(): Promise<void> {
  if (globalWorker) {
    await globalWorker.stop();
    globalWorker = null;
  }
}

export interface ProvisionResult {
  userId: string;
  deviceId: string;
  token: string;
}

export async function provisionUser(
  worker: Unstable_DevWorker,
  userId: string,
  deviceId: string = "test_device"
): Promise<ProvisionResult> {
  const res = await worker.fetch("/api/provision", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Aster-Admin-Key": TEST_ADMIN_KEY,
    },
    body: JSON.stringify({ userId, deviceId }),
  });
  if (res.status !== 201) {
    const txt = await res.text();
    throw new Error(`Provision failed (${res.status}): ${txt}`);
  }
  return (await res.json()) as ProvisionResult;
}

export async function provisionFreshUser(
  worker: Unstable_DevWorker,
  prefix: string = "user",
  deviceId: string = "test_dev"
): Promise<ProvisionResult> {
  const uniqueId = `${prefix}_${Math.random().toString(36).substring(2, 9)}`;
  return provisionUser(worker, uniqueId, `${deviceId}_${Math.random().toString(36).substring(2, 6)}`);
}

export class TestWsClient {
  public ws: WebSocket | null = null;
  public messages: any[] = [];
  public isClosed = false;
  public closeCode: number | null = null;
  public closeReason: string | null = null;

  private messageResolvers: ((msg: any) => void)[] = [];

  constructor(public url: string) {}

  async connect(): Promise<void> {
    this.ws = new WebSocket(this.url);

    return new Promise((resolve, reject) => {
      this.ws!.on("open", () => resolve());
      this.ws!.on("error", (err) => reject(err));
      this.ws!.on("message", (data) => {
        try {
          const parsed = JSON.parse(data.toString());
          this.messages.push(parsed);
          if (this.messageResolvers.length > 0) {
            const resolver = this.messageResolvers.shift()!;
            resolver(parsed);
          }
        } catch (_) {}
      });
      this.ws!.on("close", (code, reason) => {
        this.isClosed = true;
        this.closeCode = code;
        this.closeReason = reason.toString();
      });
    });
  }

  send(msg: any): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error("Cannot send on closed WebSocket");
    }
    this.ws.send(typeof msg === "string" ? msg : JSON.stringify(msg));
  }

  async sendAndAwaitAuth(token: string): Promise<any> {
    this.send({ type: "AUTH", token });
    return this.waitForMessage((m) => m.type === "AUTH_OK" || m.type === "AUTH_ERROR");
  }

  async waitForMessage(predicate: (msg: any) => boolean, timeoutMs: number = 3000): Promise<any> {
    // Check if already in queue
    const existingIdx = this.messages.findIndex(predicate);
    if (existingIdx !== -1) {
      const found = this.messages[existingIdx];
      this.messages.splice(existingIdx, 1);
      return found;
    }

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        const idx = this.messageResolvers.indexOf(listener);
        if (idx !== -1) this.messageResolvers.splice(idx, 1);
        reject(new Error(`Timeout (${timeoutMs}ms) waiting for message`));
      }, timeoutMs);

      const listener = (msg: any) => {
        if (predicate(msg)) {
          clearTimeout(timer);
          resolve(msg);
        } else {
          // Keep listening
          this.messageResolvers.push(listener);
        }
      };

      this.messageResolvers.push(listener);
    });
  }

  async waitForClose(timeoutMs: number = 3000): Promise<{ code: number | null; reason: string | null }> {
    if (this.isClosed) {
      return { code: this.closeCode, reason: this.closeReason };
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`Timeout waiting for close frame`));
      }, timeoutMs);

      this.ws!.on("close", (code, reason) => {
        clearTimeout(timer);
        resolve({ code, reason: reason.toString() });
      });
    });
  }

  close(): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.close();
    }
  }
}
