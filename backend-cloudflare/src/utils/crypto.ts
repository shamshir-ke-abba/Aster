const ASTER_ID_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

export async function hashToken(token: string): Promise<string> {
  const msgUint8 = new TextEncoder().encode(token);
  const hashBuffer = await crypto.subtle.digest("SHA-256", msgUint8);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function generateSecureToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  const hex = Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return `ast_tok_${hex}`;
}

export function generateAsterId(): string {
  let id = "AST-";
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  for (let i = 0; i < 6; i++) {
    const idx = bytes[i] % ASTER_ID_ALPHABET.length;
    id += ASTER_ID_ALPHABET[idx];
  }
  return id;
}

export function constantTimeCompare(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let result = 0;
  for (let i = 0; i < a.length; i++) {
    result |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return result === 0;
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function hexToBytes(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return bytes;
}

/**
 * PBKDF2 with SHA-256 (100,000 iterations, 16-byte random salt, 256-bit derived key)
 */
export async function hashPassword(
  password: string,
  saltHex?: string
): Promise<{ hash: string; salt: string }> {
  const saltBytes = saltHex ? hexToBytes(saltHex) : new Uint8Array(16);
  if (!saltHex) {
    crypto.getRandomValues(saltBytes);
  }
  const salt = bytesToHex(saltBytes);

  const passwordKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    { name: "PBKDF2" },
    false,
    ["deriveBits"]
  );

  const derivedBits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt: saltBytes,
      iterations: 100000,
      hash: "SHA-256",
    },
    passwordKey,
    256
  );

  const hash = bytesToHex(new Uint8Array(derivedBits));
  return { hash, salt };
}

export async function verifyPassword(
  password: string,
  expectedHash: string,
  salt: string
): Promise<boolean> {
  const computed = await hashPassword(password, salt);
  return constantTimeCompare(computed.hash, expectedHash);
}

/**
 * Generates an unambiguous, human-readable 16-character recovery code:
 * Format: XXXX-XXXX-XXXX-XXXX
 */
export function generateRecoveryCode(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  let code = "";
  for (let i = 0; i < 16; i++) {
    if (i > 0 && i % 4 === 0) code += "-";
    code += ASTER_ID_ALPHABET[bytes[i] % ASTER_ID_ALPHABET.length];
  }
  return code;
}

/**
 * Normalizes user-entered recovery codes by removing non-alphanumeric characters and converting to uppercase.
 */
export function normalizeRecoveryCode(code: string): string {
  return code.replace(/[^A-Za-z0-9]/g, "").toUpperCase();
}

export async function hashRecoveryCode(
  code: string,
  saltHex?: string
): Promise<{ hash: string; salt: string }> {
  const normalized = normalizeRecoveryCode(code);
  return hashPassword(normalized, saltHex);
}

export async function verifyRecoveryCode(
  code: string,
  expectedHash: string,
  salt: string
): Promise<boolean> {
  const normalized = normalizeRecoveryCode(code);
  return verifyPassword(normalized, expectedHash, salt);
}
