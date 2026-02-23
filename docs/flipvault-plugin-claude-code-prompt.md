# FlipVault RuneLite Plugin -- Claude Code Prompt

## Context

You are building a RuneLite sidebar plugin for FlipVault, an OSRS Grand Exchange flipping intelligence platform. The plugin reads game state (GE slots, cash stack, inventory), sends it to the FlipVault backend, and receives a single personalized trading suggestion. It also tracks flips, highlights GE widgets, auto-fills offer values via hotkey, and displays session stats.

## Master Specification

The attached file `flipvault-plugin-architecture-v4.md` is the complete specification. It contains everything you need: architecture, models, threading, game state collection, transaction inference, suggestion flow, API contracts, UI layouts, widget IDs, highlighting, auto-fill, configuration, persistence, error handling, build config, and implementation order.

**Read the entire spec before writing any code.** Follow it precisely.

## Critical Rules

1. **Build from scratch.** Do not fork, copy, or reference Flipping Copilot's source code. Use the same RuneLite API patterns documented in the spec, but all code must be original.

2. **Section 13 (Backend Endpoints) is NOT your responsibility.** I will personally build all backend API endpoints. Do NOT implement any backend/server code. When you need to test API interactions, use mock responses or hardcoded test data that match the API contracts defined in Section 6.

3. **All plugin API requests go through `https://api.flipvault.app/api/plugin/`**. This is the base URL for every endpoint (login, activate-key, validate-key, heartbeat, suggest, transaction). The `api.` subdomain routes through nginx to the same Next.js backend but is separated for tracking purposes.

4. **Follow the implementation order in Section 12 exactly.** Build and test each step before moving to the next:
   - Step 1: Skeleton + Config + Auth Flow
   - Step 2: Game State Reading
   - Step 3: UI Shell
   - Step 4: Suggestion Flow + GE Highlighting + Auto-Fill
   - Step 5: Transaction Inference
   - Step 6: Persistence + Polish

5. **Java 11, Gradle, RuneLite client 1.10.+**. See Section 11 for full build config.

6. **Threading model matters.** Read Section 2.2 carefully. Game state reads on ClientThread only. API calls on ExecutorService only. UI updates on Swing EDT only. Violating this will crash the plugin or the game client.

7. **API Key auth, not JWT.** The plugin uses `X-API-Key` headers for all requests after initial setup. No Bearer tokens, no token refresh. See Section 6.1 and Section 8 for the full auth flow and state machine.

## File to Attach

Attach this single file when starting the conversation:

- `flipvault-plugin-architecture-v4.md`

That's it. The spec is self-contained. No web app source code, no React mockups, no other project files needed.

## Expected Output

A complete, buildable Gradle project with the package structure defined in Section 1.1, all model/manager/controller/ui classes implemented, and the plugin loadable in RuneLite's developer mode. Backend calls should gracefully handle the endpoints not being live yet (mock/stub responses for testing).
