// Tells the user's phones that their drafts changed, through Firebase Cloud Messaging (docs/adr/0006). The message
// carries no draft content: each phone syncs and shows its notifications from its own copy of the drafts.

/** The parts of a Firebase service account key this needs. */
export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

/** Where the user's phones' tokens live, and how to forget one Firebase no longer knows. */
export interface Devices {
  tokens(userId: string): Promise<string[]>;
  forget(userId: string, tokens: string[]): Promise<void>;
}

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const TOKEN_URI = "https://oauth2.googleapis.com/token";

const base64url = (bytes: Uint8Array) =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const encoded = (value: unknown) => base64url(new TextEncoder().encode(JSON.stringify(value)));

/** A signed JWT asking Google for an access token as the service account. */
export async function assertion(account: ServiceAccount, now: number): Promise<string> {
  const pem = account.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (char) => char.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, [
    "sign",
  ]);
  const seconds = Math.floor(now / 1000);
  const unsigned = `${encoded({ alg: "RS256", typ: "JWT" })}.${
    encoded({
      iss: account.client_email,
      scope: SCOPE,
      aud: account.token_uri ?? TOKEN_URI,
      iat: seconds,
      exp: seconds + 3600,
    })
  }`;
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  return `${unsigned}.${base64url(new Uint8Array(signature))}`;
}

/** Sends Firebase messages as one service account, reusing its access token until shortly before it expires. */
export class Messenger {
  private cached?: { token: string; until: number };

  constructor(
    private readonly account: ServiceAccount,
    private readonly fetcher: typeof fetch = fetch,
    private readonly clock: () => number = Date.now,
  ) {}

  private async accessToken(): Promise<string> {
    const now = this.clock();
    if (this.cached && this.cached.until > now) return this.cached.token;
    const response = await this.fetcher(this.account.token_uri ?? TOKEN_URI, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: await assertion(this.account, now),
      }),
    });
    if (!response.ok) {
      throw new Error(`Google refused the service account: ${response.status} ${await response.text()}`);
    }
    const { access_token, expires_in } = await response.json();
    this.cached = { token: access_token, until: now + (Number(expires_in) - 60) * 1000 };
    return access_token;
  }

  /** Sends "drafts changed" to [token]. False when Firebase no longer knows the token, so it can be forgotten. */
  async draftsChanged(token: string): Promise<boolean> {
    const response = await this.fetcher(
      `https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${await this.accessToken()}`, "Content-Type": "application/json" },
        // High priority, so a phone in Doze wakes to sync and show the draft.
        body: JSON.stringify({ message: { token, data: { type: "drafts" }, android: { priority: "HIGH" } } }),
      },
    );
    if (response.ok) return true;
    const body = await response.text();
    if (response.status === 404 || body.includes("UNREGISTERED")) return false;
    throw new Error(`Firebase refused the message: ${response.status} ${body}`);
  }
}

/**
 * Tells each of [userId]'s phones that their drafts changed, and forgets the phones Firebase no longer knows.
 * Never throws: a draft is saved whether or not its phones hear about it.
 */
export async function notifyDraftsChanged(userId: string, devices: Devices, messenger: Messenger | null) {
  if (!messenger) return;
  try {
    const tokens = await devices.tokens(userId);
    const results = await Promise.allSettled(tokens.map((token) => messenger.draftsChanged(token)));
    const gone = tokens.filter((_, index) => {
      const result = results[index];
      return result.status === "fulfilled" && !result.value;
    });
    results.forEach((result) => {
      if (result.status === "rejected") console.warn("Couldn't tell a phone about drafts:", result.reason);
    });
    if (gone.length) await devices.forget(userId, gone);
  } catch (error) {
    console.warn("Couldn't tell phones about drafts:", error);
  }
}

/** The messenger for FIREBASE_SERVICE_ACCOUNT, or null until that secret is set. */
export function messengerFromEnv(json: string | undefined): Messenger | null {
  if (!json) return null;
  try {
    return new Messenger(JSON.parse(json) as ServiceAccount);
  } catch (error) {
    console.warn("FIREBASE_SERVICE_ACCOUNT isn't a service account key:", error);
    return null;
  }
}
