# Discord Login Endpoint Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement `GET /api/plugin/discord-login` — a binary-streaming endpoint that lets the FC-based plugin authenticate via Discord OAuth.

**Architecture:** The plugin opens a long-lived GET request. The server immediately streams back a Discord OAuth URL (binary-encoded), holds the connection open, and when the user completes Discord auth in their browser, streams back an API key + userId (binary-encoded). An in-memory Map links the OAuth `state` parameter to the waiting stream's resolver. A separate callback route handles the Discord redirect and resolves the waiting connection.

**Tech Stack:** Next.js App Router (streaming `ReadableStream`), Drizzle ORM, Discord OAuth2, binary DataView encoding

**Repos:**
- Backend: `/Users/jayne/Desktop/codingProjects/arbit`
- Plugin: `/Users/jayne/Desktop/codingProjects/flipvault-companion` (already updated)

---

## Binary Protocol Reference

The plugin reads the response as a `DataInputStream` (big-endian):

**Phase 1 — OAuth URL (immediate):**
| Field | Type | Description |
|-------|------|-------------|
| urlLength | int32 BE | Length of OAuth URL in bytes |
| url | byte[urlLength] | UTF-8 encoded Discord OAuth URL |

**Phase 2 — Login result (after user completes OAuth):**
| Field | Type | Description |
|-------|------|-------------|
| tokenLength | int32 BE | Length of API key / JWT |
| token | byte[tokenLength] | UTF-8 encoded raw API key |
| userId | int32 BE | Numeric user ID (can be 1, plugin uses it for flip sync) |
| errorLength | int32 BE | Length of error string (0 if success) |
| error | byte[errorLength] | UTF-8 error message |

---

### Task 1: Update plugin-auth.ts to accept Bearer tokens

The FC plugin sends `Authorization: Bearer <apiKey>` on all authenticated requests. Currently `authenticatePluginRequest` only reads `X-API-Key`. This must work for both.

**Files:**
- Modify: `/Users/jayne/Desktop/codingProjects/arbit/src/lib/plugin-auth.ts`

**Step 1: Update authenticatePluginRequest**

Change the key extraction to check both headers:

```typescript
export async function authenticatePluginRequest(
  req: Request
): Promise<PluginUser | null> {
  // Accept X-API-Key header (native FV plugin) or Authorization: Bearer (FC plugin)
  let apiKey = req.headers.get("x-api-key");
  if (!apiKey) {
    const authHeader = req.headers.get("authorization");
    if (authHeader?.startsWith("Bearer ")) {
      apiKey = authHeader.slice(7);
    }
  }
  if (!apiKey || !apiKey.startsWith(PLUGIN_KEY_PREFIX)) return null;

  // ... rest unchanged
```

**Step 2: Verify build**

Run: `cd /Users/jayne/Desktop/codingProjects/arbit && npm run build`

**Step 3: Commit**

```bash
git add src/lib/plugin-auth.ts
git commit -m "feat: accept Bearer auth in addition to X-API-Key for FC plugin compat"
```

---

### Task 2: Update discord.ts to support custom redirect URI

The existing `exchangeCode()` hardcodes the redirect URI for the web callback. The plugin Discord login needs a different callback URL.

**Files:**
- Modify: `/Users/jayne/Desktop/codingProjects/arbit/src/lib/discord.ts`

**Step 1: Add redirectUri parameter to exchangeCode**

```typescript
export async function exchangeCode(code: string, redirectUri?: string): Promise<DiscordTokenResponse> {
  const res = await fetch(`${DISCORD_API}/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: process.env.DISCORD_CLIENT_ID!,
      client_secret: process.env.DISCORD_CLIENT_SECRET!,
      grant_type: "authorization_code",
      code,
      redirect_uri: redirectUri ?? getRedirectUri(),
    }),
  });
  if (!res.ok) {
    throw new Error(`Discord token exchange failed: ${res.status}`);
  }
  return res.json();
}
```

**Step 2: Add helper to build plugin callback redirect URI**

```typescript
export function getPluginDiscordRedirectUri(): string {
  const base = (process.env.APP_URL ?? "").replace(/\/+$/, "");
  return `${base}/api/plugin/discord-login/callback`;
}
```

**Step 3: Commit**

```bash
git add src/lib/discord.ts
git commit -m "feat: support custom redirect URI in Discord code exchange"
```

---

### Task 3: Create in-memory pending logins state

An in-memory Map that holds `state → { resolve, reject, timer }` for connecting the streaming GET response to the callback.

**Files:**
- Create: `/Users/jayne/Desktop/codingProjects/arbit/src/lib/plugin-discord-state.ts`

**Step 1: Write the module**

```typescript
export interface DiscordLoginResult {
  jwt: string;
  userId: number;
  error: string;
}

interface PendingLogin {
  resolve: (data: DiscordLoginResult) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

const pendingLogins = new Map<string, PendingLogin>();

const LOGIN_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

export function registerPendingLogin(
  state: string
): Promise<DiscordLoginResult> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pendingLogins.delete(state);
      reject(new Error("Discord login timed out"));
    }, LOGIN_TIMEOUT_MS);

    pendingLogins.set(state, { resolve, reject, timer });
  });
}

export function resolvePendingLogin(
  state: string,
  result: DiscordLoginResult
): boolean {
  const pending = pendingLogins.get(state);
  if (!pending) return false;
  clearTimeout(pending.timer);
  pendingLogins.delete(state);
  pending.resolve(result);
  return true;
}

export function rejectPendingLogin(state: string, error: Error): boolean {
  const pending = pendingLogins.get(state);
  if (!pending) return false;
  clearTimeout(pending.timer);
  pendingLogins.delete(state);
  pending.reject(error);
  return true;
}
```

**Step 2: Commit**

```bash
git add src/lib/plugin-discord-state.ts
git commit -m "feat: add in-memory state for plugin Discord login flow"
```

---

### Task 4: Create GET /api/plugin/discord-login streaming endpoint

This is the main endpoint the plugin calls. It streams binary data on a single long-lived GET.

**Files:**
- Create: `/Users/jayne/Desktop/codingProjects/arbit/src/app/api/plugin/discord-login/route.ts`

**Step 1: Write the route**

```typescript
import crypto from "crypto";
import {
  registerPendingLogin,
  type DiscordLoginResult,
} from "@/lib/plugin-discord-state";
import { getPluginDiscordRedirectUri } from "@/lib/discord";

const DISCORD_AUTHORIZE_URL = "https://discord.com/api/v10/oauth2/authorize";

function buildOAuthUrl(state: string): string {
  const params = new URLSearchParams({
    client_id: process.env.DISCORD_CLIENT_ID!,
    redirect_uri: getPluginDiscordRedirectUri(),
    response_type: "code",
    scope: "identify",
    state,
  });
  return `${DISCORD_AUTHORIZE_URL}?${params}`;
}

function writeInt32(value: number): Uint8Array {
  const buf = new ArrayBuffer(4);
  new DataView(buf).setInt32(0, value, false); // big-endian
  return new Uint8Array(buf);
}

function writeString(str: string): Uint8Array[] {
  const bytes = new TextEncoder().encode(str);
  return [writeInt32(bytes.length), bytes];
}

export const dynamic = "force-dynamic";

export async function GET() {
  const state = crypto.randomUUID();
  const oauthUrl = buildOAuthUrl(state);

  const stream = new ReadableStream({
    async start(controller) {
      try {
        // Phase 1: Write OAuth URL immediately
        for (const chunk of writeString(oauthUrl)) {
          controller.enqueue(chunk);
        }

        // Phase 2: Wait for Discord callback to resolve
        let result: DiscordLoginResult;
        try {
          result = await registerPendingLogin(state);
        } catch {
          result = { jwt: "", userId: 0, error: "Discord login timed out" };
        }

        // Write token
        for (const chunk of writeString(result.jwt)) {
          controller.enqueue(chunk);
        }
        // Write userId
        controller.enqueue(writeInt32(result.userId));
        // Write error
        for (const chunk of writeString(result.error)) {
          controller.enqueue(chunk);
        }
      } finally {
        controller.close();
      }
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "application/octet-stream",
      "Cache-Control": "no-cache, no-store",
      "X-Accel-Buffering": "no",
    },
  });
}
```

**Step 2: Commit**

```bash
git add src/app/api/plugin/discord-login/route.ts
git commit -m "feat: add streaming GET /api/plugin/discord-login endpoint"
```

---

### Task 5: Create Discord OAuth callback route

This route handles the Discord redirect after the user authorizes. It exchanges the code for tokens, looks up the user by Discord ID, creates/finds an API key, and resolves the waiting stream.

**Files:**
- Create: `/Users/jayne/Desktop/codingProjects/arbit/src/app/api/plugin/discord-login/callback/route.ts`

**Step 1: Write the route**

```typescript
import crypto from "crypto";
import { db } from "@/lib/db";
import { user, pluginApiKeys } from "@/lib/db/schema";
import { eq, and } from "drizzle-orm";
import {
  exchangeCode,
  fetchDiscordUser,
  getPluginDiscordRedirectUri,
} from "@/lib/discord";
import { resolvePendingLogin, rejectPendingLogin } from "@/lib/plugin-discord-state";
import { PLUGIN_KEY_PREFIX } from "@/lib/plugin-key-prefix";

function hashKey(rawKey: string): string {
  return crypto.createHash("sha256").update(rawKey).digest("hex");
}

function generateKey(): { raw: string; prefix: string; hash: string } {
  const raw = `${PLUGIN_KEY_PREFIX}${crypto.randomBytes(16).toString("hex")}`;
  return { raw, prefix: raw.slice(0, 16), hash: hashKey(raw) };
}

function successHtml(): Response {
  return new Response(
    `<!DOCTYPE html>
<html><head><title>FlipVault</title>
<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#1a1a2e;color:#e0e0e0}
.box{text-align:center;padding:2rem;border-radius:12px;background:#16213e}h1{color:#0f0}p{color:#aaa}</style></head>
<body><div class="box"><h1>Login Successful</h1><p>You can close this tab and return to RuneLite.</p></div></body></html>`,
    { headers: { "Content-Type": "text/html" } }
  );
}

function errorHtml(message: string): Response {
  return new Response(
    `<!DOCTYPE html>
<html><head><title>FlipVault</title>
<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#1a1a2e;color:#e0e0e0}
.box{text-align:center;padding:2rem;border-radius:12px;background:#16213e}h1{color:#f44}p{color:#aaa}</style></head>
<body><div class="box"><h1>Login Failed</h1><p>${message}</p></div></body></html>`,
    { status: 400, headers: { "Content-Type": "text/html" } }
  );
}

export async function GET(request: Request) {
  const url = new URL(request.url);
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");

  if (!code || !state) {
    return errorHtml("Missing authorization code or state.");
  }

  try {
    // Exchange code for Discord tokens
    const tokens = await exchangeCode(code, getPluginDiscordRedirectUri());
    const discordUser = await fetchDiscordUser(tokens.access_token);

    // Look up FV user by Discord ID
    const [fvUser] = await db
      .select({ id: user.id })
      .from(user)
      .where(eq(user.discordId, discordUser.id))
      .limit(1);

    if (!fvUser) {
      const errorMsg =
        "No FlipVault account linked to this Discord. Sign up at flipvault.app and link your Discord first.";
      rejectPendingLogin(state, new Error(errorMsg));
      return errorHtml(errorMsg);
    }

    // Find an existing active key or create a new one
    const existingKeys = await db
      .select({ id: pluginApiKeys.id })
      .from(pluginApiKeys)
      .where(
        and(
          eq(pluginApiKeys.userId, fvUser.id),
          eq(pluginApiKeys.isActive, true)
        )
      );

    let rawKey: string;

    if (existingKeys.length > 0) {
      // Revoke the oldest key and issue a fresh one (we can't retrieve stored hashes)
      const { raw, prefix, hash } = generateKey();
      rawKey = raw;

      // Deactivate the first key and replace it
      await db
        .update(pluginApiKeys)
        .set({ isActive: false, revokedAt: new Date() })
        .where(eq(pluginApiKeys.id, existingKeys[0].id));

      await db.insert(pluginApiKeys).values({
        userId: fvUser.id,
        key: hash,
        keyPrefix: prefix,
        label: `Discord login`,
      });
    } else {
      // No keys — create one
      const { raw, prefix, hash } = generateKey();
      rawKey = raw;
      await db.insert(pluginApiKeys).values({
        userId: fvUser.id,
        key: hash,
        keyPrefix: prefix,
        label: `Discord login`,
      });
    }

    resolvePendingLogin(state, { jwt: rawKey, userId: 1, error: "" });
    return successHtml();
  } catch (e) {
    const msg = e instanceof Error ? e.message : "Unknown error";
    rejectPendingLogin(state, new Error(msg));
    return errorHtml("An error occurred during login. Please try again.");
  }
}
```

**Step 2: Commit**

```bash
git add src/app/api/plugin/discord-login/callback/route.ts
git commit -m "feat: add Discord OAuth callback for plugin login"
```

---

### Task 6: Register callback URL in Discord Developer Portal

**Manual step** — not code:

1. Go to https://discord.com/developers/applications
2. Select the FlipVault application
3. Go to OAuth2 → Redirects
4. Add: `https://api.flipvault.app/api/plugin/discord-login/callback`
5. (For dev, also add: `http://localhost:3000/api/plugin/discord-login/callback`)
6. Save

---

### Task 7: Verify build and test end-to-end

**Step 1: Build backend**

Run: `cd /Users/jayne/Desktop/codingProjects/arbit && npm run build`
Expected: No errors

**Step 2: Build plugin**

Run: `cd /Users/jayne/Desktop/codingProjects/flipvault-companion && ./gradlew clean && ./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 3: Manual E2E test**

1. Start arbit dev server: `cd /Users/jayne/Desktop/codingProjects/arbit && npm run dev`
2. Start plugin: `cd /Users/jayne/Desktop/codingProjects/flipvault-companion && FLIPPING_COPILOT_HOST=http://localhost:3000/api/plugin ./gradlew run`
3. In RuneLite → FlipVault sidebar → click Discord login
4. Browser should open Discord OAuth
5. After authorizing, browser shows "Login Successful"
6. Plugin should show logged-in state

---

## Notes

- **In-memory state limitation:** The `pendingLogins` Map lives in a single Node.js process. If running on serverless (Vercel), the GET request and callback may hit different instances. For serverless, replace with Redis pub/sub or database polling. For a single-process deployment (VPS/Docker), this works.
- **Key rotation on Discord login:** Each Discord login revokes one existing key and issues a fresh one (since we can't retrieve stored key hashes). This keeps the key count stable. The user's other keys are unaffected.
- **No auto-registration:** Users must sign up at flipvault.app and link their Discord before using Discord login in the plugin. This avoids creating headless accounts.
- **Discord app config:** The callback URL must be registered in the Discord Developer Portal or the OAuth flow will fail with a redirect_uri mismatch.
