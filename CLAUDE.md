# FlipVault Companion — RuneLite Plugin

## Overview
- Sidebar plugin for Old School RuneScape (via RuneLite) that provides AI-powered Grand Exchange flipping suggestions
- Thin client: all ML/pricing intelligence lives server-side at FlipVault.app
- Flow: plugin reads GE state from game client → sends snapshots to `api.flipvault.app` → receives suggestions → renders overlay + sidebar UI
- Auth model: API keys (X-API-Key header), each key bound to one OSRS player name

## Tech Stack
- **Java 11** bytecode target (`options.release.set(11)` in build.gradle), built on Java 25 (Temurin)
- **Gradle 9.3.1** — no Maven
- **RuneLite client**: `latest.release` (compileOnly)
- **OkHttp 4.12.0**: compileOnly, provided by RuneLite at runtime. Sync calls only.
- **Lombok 1.18.40**: `@Data`, `@Builder`, `@Slf4j` throughout
- **Gson**: Use `new JsonParser().parse()`, NOT `JsonParser.parseString()` — RuneLite bundles an older Gson

## Build & Run
- `./gradlew build` — output: `build/libs/flipvault-companion-0.1.0.jar`
- `./gradlew run` — launches RuneLite dev mode with plugin loaded (uses `FlipVaultPluginTest` as launcher)
- To sideload: place project in RuneLite's external plugins directory; JAR sideloading no longer works

## Architecture

### Package Structure
```
com.flipvault.plugin/
  FlipVaultPlugin.java       — Main orchestrator, event subscriptions, lifecycle
  FlipVaultConfig.java       — @ConfigGroup("flipvault"), all user-facing settings
  controller/                — Business logic + HTTP + overlay
    ApiClient.java           — OkHttp sync POST calls, all 7 API endpoints
    AuthController.java      — Login/key activation state machine (AuthState enum)
    SuggestionController.java— Rate-limited suggestion requests, retry/backoff
    HighlightController.java — GE widget overlay (extends Overlay), auto-fill hotkey
    GEOfferHandler.java      — Processes GE offer changes, infers transactions
    WebhookController.java   — Discord session summary on shutdown
  manager/                   — Stateful, persisted managers
    OfferManager.java        — 8-slot GE state, JSON persistence
    FlipManager.java         — Active flips, buy→sell matching
    SessionManager.java      — Profit/flip tracking, session restore (<6h)
    FlipLogger.java          — Append-only JSONL flip history
  model/                     — Pure data classes (Lombok @Data/@Builder)
    Suggestion, ActiveFlip, Transaction, GESlotState, AccountState, etc.
  ui/                        — Swing panels (minimal state)
    FlipVaultPanel.java      — Main PluginPanel, CardLayout routing
    SuggestionPanel.java     — 7-state CardLayout (LOADING/SUGGESTION/WAIT/ERROR/EMPTY/COLLECT/CANCEL)
    ActiveFlipsPanel.java, StatsPanel.java, LoginPanel.java, KeySelectionPanel.java
```

### Data Flow
1. `onGameTick` (every 0.6s on ClientThread) → `snapshotGameState()` reads GE offers, cash, world
2. State diffed against `lastAccountState`; if changed → `SuggestionController.requestSuggestion()`
3. Suggestion request runs on `flipvaultExecutor` (2-thread pool) → HTTP POST to `/suggest`
4. Response updates `currentSuggestion` → `SwingUtilities.invokeLater()` updates sidebar panels
5. `HighlightController.render()` reads `currentSuggestion` each frame → highlights GE widgets
6. `onGrandExchangeOfferChanged` → `GEOfferHandler` → `FlipManager` match buy→sell → `ApiClient.reportTransaction()`

## Threading Model (Critical)

| Context | Used For | Game State Access |
|---------|----------|-------------------|
| **ClientThread** | `@Subscribe` handlers, `snapshotGameState()` | YES — only thread that can read `client.getVarbitValue()`, GE offers, widgets |
| **flipvaultExecutor** (2 daemon threads) | All API calls (sync OkHttp) | NO — never read game state here |
| **scheduledExecutor** (RuneLite injected) | 30s periodic save, 60s heartbeat | NO — use `clientThread.invokeLater()` to read game state |
| **Swing EDT** | UI event handlers, panel updates | NO — use `SwingUtilities.invokeLater()` from other threads |

Key patterns:
- `clientThread.invokeLater(this::snapshotGameState)` — schedule game state reads
- `flipvaultExecutor.submit(() -> apiClient.requestSuggestion(...))` — API calls off client thread
- `SwingUtilities.invokeLater(() -> panel.update(...))` — UI updates from background
- Manager methods are `synchronized` for cross-thread safety

## RuneLite Specifics
- `FlipVaultPlugin extends Plugin implements KeyListener` — entry point
- `@PluginDescriptor(name = "FlipVault")` — registration
- `FlipVaultConfig extends Config` with `@ConfigGroup("flipvault")`
- `HighlightController extends Overlay` — `@Singleton`, `ABOVE_WIDGETS` layer, `HIGH` priority
- `FlipVaultPanel extends PluginPanel` — sidebar via `NavigationButton`
- Login burst protection: skips first 2 ticks after login (`LOGIN_BURST_TICKS`) to avoid reading transitional GE state

### Config Keys (group: `flipvault`)
- `apiKey` (String, secret) — active API key
- `keyLabel` (String) — display label for active key
- `boundTo` (String, hidden) — player name key is bound to
- `chatNotifications` / `trayNotifications` (boolean, default true)
- `chatTextColor` (Color, default 0x0040FF)
- `discordWebhookUrl` (String) — session summary webhook
- `autoFillHotkey` (Keybind, default F5) — fills suggested price/qty
- `suggestionHighlights` (boolean, default true) — GE widget overlay
- `misClickPrevention` (boolean, default false) — deprioritize Confirm when offer doesn't match

## API Integration
Base URL: `https://api.flipvault.app/api/plugin`

| Endpoint | Auth | Purpose |
|----------|------|---------|
| POST `/login` | None | Email/password → sessionToken + keys[] |
| POST `/activate-key` | None | Bind key to player name → apiKey |
| POST `/validate-key` | X-API-Key | Verify key validity for player |
| POST `/suggest` | X-API-Key | AccountState → Suggestion (rate-limited: 2s min) |
| POST `/transaction` | X-API-Key | Report completed flip with metadata |
| POST `/heartbeat` | X-API-Key | Keep session alive (every 60s) |
| POST `/browser-auth/poll` | None | Poll Discord OAuth flow (3s interval, 5min timeout) |

Error handling in `SuggestionController`: 401/403 → auth failure, 400 → re-snapshot (3s), 500+ → retry (30s), timeout → retry (5s), network error → retry (10s)

## Data Persistence
Directory: `~/.runelite/flipvault/`
- `offers-{playerName}.json` — GE slot states (saved on shutdown + every 5min)
- `session-{playerName}.json` — session stats (restored if <6 hours old)
- `flips-{playerName}.jsonl` — append-only flip history (never cleared)
- API key/label/boundTo stored via RuneLite `ConfigManager`

## Conventions
- Lombok `@Slf4j` on every class; log via `log.info/debug/warn/error`
- Model classes use `@Data` + `@Builder` or `@AllArgsConstructor`
- Controllers suffix: `*Controller`, managers: `*Manager`, panels: `*Panel`
- JSON built manually with `JsonObject`/`JsonArray` (not Gson serialization) for API payloads
- All HTTP is sync `OkHttpClient` — callers are always on `flipvaultExecutor`

## Things to Avoid
- **Never read game state off ClientThread** — `client.getVarbitValue()`, `client.getGrandExchangeOffers()`, widget reads all require ClientThread
- **Never block ClientThread with HTTP** — always submit API calls to `flipvaultExecutor`
- **Never update Swing from background threads** — always `SwingUtilities.invokeLater()`
- **Don't use `JsonParser.parseString()`** — RuneLite's bundled Gson is too old; use `new JsonParser().parse()`
- **Login burst**: GE slots fire rapid EMPTY→real-state transitions on login; the first 2 ticks are skipped
- **Widget IDs are approximate** — current values (465/26 children 51/54/58 for qty/price/confirm) need verification via RuneLite widget inspector
- **Auto-fill uses `widget.setText()`** — may need `client.runScript()` for production reliability
- **Don't use `latest.release` for OkHttp** — it's compileOnly and must match what RuneLite provides at runtime

## Current State
- **Stable**: Auth flow, suggestion fetch/display, GE overlay highlights, session stats, JSONL logging, Discord webhooks
- **In progress**: `WebhookController.java` and `GETax.java` are untracked new files
- **Known limitations**:
  - Active Flips panel shows "Invested" not "Unrealized P&L" (needs real-time price data)
  - Missing `@Subscribe` for `onVarbitChanged` and `onFocusChanged` (low impact)
  - Auto-fill implementation may not survive all GE UI states
  - No unit test coverage beyond the launcher class

Keep this file updated when architecture changes.
