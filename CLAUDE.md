# FlipVault Companion — RuneLite Plugin

## Overview
- Sidebar plugin for Old School RuneScape (via RuneLite) that provides AI-powered Grand Exchange flipping suggestions
- Thin client: all ML/pricing intelligence lives server-side at FlipVault.app
- Flow: plugin reads GE state from game client → sends snapshots to `api.flipvault.app` → receives suggestions → renders overlay + sidebar UI
- Auth model: Bearer JWT tokens issued by the FlipVault login flow; API keys bound to one OSRS player name
- Codebase is a rebranded fork of [Flipping Copilot](https://github.com/flippingcopilot/flipping-copilot) — package root is `com.flippingcopilot`

## Tech Stack
- **Java 11** bytecode target (`options.release.set(11)` in build.gradle), built on Java 25 (Temurin)
- **Gradle 9.3.1** — no Maven
- **RuneLite client**: `latest.release` (compileOnly)
- **OkHttp 4.12.0**: compileOnly, provided by RuneLite at runtime. Async calls via `enqueue()`
- **Lombok 1.18.40**: `@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor(onConstructor_ = @Inject)` throughout
- **Gson**: Use `new JsonParser().parse()`, NOT `JsonParser.parseString()` — RuneLite bundles an older Gson
- **Guice**: Dependency injection via `@Singleton` + `@Inject`; all singletons wired through RuneLite's injector

## Build & Run
- `./gradlew build` — output: `build/libs/flipvault-companion-0.1.0.jar`
- `./gradlew run` — launches RuneLite dev mode with plugin loaded (uses `FlippingCopilotPluginTest` as launcher)
- To sideload: place project in RuneLite's external plugins directory; JAR sideloading no longer works
- Override API host: set env var `FLIPVAULT_HOST` (defaults to `https://api.flipvault.app/api/plugin`)

## Architecture

### Package Structure
```
com.flippingcopilot/
  controller/
    FlipVaultPlugin.java            — Main plugin, event subscriptions, lifecycle
    ApiRequestHandler.java          — All HTTP endpoints (OkHttp async)
    FVLoginController.java          — Login/auth state machine
    SuggestionController.java       — Rate-limited suggestion requests, retry/backoff
    HighlightController.java        — GE widget overlay (extends Overlay), auto-fill
    OfferHandler.java               — Processes GE offer changes, infers transactions
    GrandExchangeOfferEventHandler.java
    GrandExchangeCollectHandler.java
    WebHookController.java          — Discord session summary on shutdown
    ItemController.java             — Item name/price lookups
    SlotProfitColorizer.java
    Persistance.java                — File I/O helpers, ~/.runelite/flipvault/ paths
    ... (other controllers)
  model/
    FlipV2.java                     — Core flip record; 84-byte binary wire format
    FlipManager.java                — Flip cache, weekly buckets, profit stats
    TransactionManager.java         — Unacked transaction queue, sync scheduling
    SessionManager.java             — Session profit/duration tracking (<6h restore)
    AccountStatusManager.java       — AccountStatus cache (avoids EDT game-state reads)
    OfferManager.java               — 8-slot GE state, JSON persistence
    SuggestionManager.java
    Transaction.java, Offer.java, Suggestion.java, Stats.java
    SessionData.java                — startTime, durationMillis, averageCash, sessionProfit
    ... (other models)
  rs/
    FVLoginRS.java                  — Login state, account ID mapping (displayName → int32)
  ui/
    FVPanel.java                    — Main PluginPanel, CardLayout routing
    SuggestionPanel.java            — Suggestion display; uses AccountStatusManager.getCachedStatus()
    StatsPanelV2.java               — Stats + session profit display; refreshed via callback
    LoginPanel.java, PreferencesPanel.java
    ... (other panels)
  util/
    ProfitCalculator.java           — GE tax formula + exempt item list
    UIUtilities.java
```

### Data Flow
1. `onGameTick` (every 0.6s on ClientThread) → reads GE offers, cash, world
2. State diffed → `SuggestionController.requestSuggestion()` on background executor
3. HTTP POST to `/suggestion` → response updates `currentSuggestion` → `SwingUtilities.invokeLater()` updates sidebar
4. `HighlightController.render()` reads suggestion each frame → highlights GE widgets
5. `onGrandExchangeOfferChanged` → `OfferHandler` → `TransactionManager.addTransaction()` → queues for server sync
6. `TransactionManager` sends transactions to `/profit-tracking/client-transactions` → receives `FlipV2` binary records → `FlipManager.mergeFlips()`
7. Background delta poller calls `/profit-tracking/client-flips-delta` every ~5s to keep flip state in sync

## Threading Model (Critical)

| Context | Used For | Game State Access |
|---------|----------|-------------------|
| **ClientThread** | `@Subscribe` handlers, game state reads | YES — only thread that can read `client.getVarbitValue()`, GE offers, widgets |
| **Background executor** | All API calls (async OkHttp) | NO — never read game state here |
| **scheduledExecutor** (RuneLite injected) | Periodic save, heartbeat | NO — use `clientThread.invokeLater()` to read game state |
| **Swing EDT** | UI event handlers, panel updates | NO — use `SwingUtilities.invokeLater()` from other threads |

Key patterns:
- `AccountStatusManager.getCachedStatus()` — use in panels (EDT-safe); `getAccountStatus()` is for ClientThread only
- `SwingUtilities.invokeLater(() -> panel.update(...))` — UI updates from background
- Manager methods are `synchronized` for cross-thread safety

## Profit Tracking System

### Client-side
- `TransactionManager.addTransaction()` tracks BUY transactions via `FlipManager.trackLocalBuy()` and estimates SELL profit via `FlipManager.estimateLocalProfit()` — works without server account data
- Profit is credited to `SessionManager.addSessionProfit()` immediately on sell, before server confirmation
- `SessionData.sessionProfit` persists to disk and is restored if session is <6 hours old

### Server-side (stubs — see `docs/profit-tracking-api-contract.md`)
Three endpoints in `ApiRequestHandler` are currently stub implementations server-side:
- `GET /profit-tracking/rs-account-names` — must return `{displayName: int32 accountId}` map; **nothing works until this returns data**
- `POST /profit-tracking/client-transactions` — stores transactions, matches flips, returns `FlipV2` binary list
- `POST /profit-tracking/client-flips-delta` — delta sync; returns `[int32 timestamp][FlipV2 list]`

Binary wire format: each `FlipV2` is exactly **84 bytes**, big-endian. Empty responses must return `[int32 BE: 0]` (4 bytes) — never 0-length.

## RuneLite Specifics
- `FlipVaultPlugin extends Plugin` — entry point (`@PluginDescriptor(name = "FlipVault")`)
- `FVPanel extends PluginPanel` — sidebar via `NavigationButton`
- `HighlightController extends Overlay` — `ABOVE_WIDGETS` layer, `HIGH` priority
- Login burst protection: skips first ticks after login to avoid reading transitional GE state

## API Endpoints
Base URL: `https://api.flipvault.app/api/plugin` (or `$FLIPVAULT_HOST`)

| Endpoint | Auth | Purpose |
|----------|------|---------|
| POST `/login` | None | Email/password → JWT + keys[] |
| POST `/suggestion` | Bearer JWT | AccountState → Suggestion |
| GET `/profit-tracking/rs-account-names` | Bearer JWT | displayName → int32 account ID map |
| POST `/profit-tracking/client-transactions` | Bearer JWT | Send transactions, receive FlipV2 records |
| POST `/profit-tracking/client-flips-delta` | Bearer JWT | Poll for flip changes since last sync |
| POST `/profit-tracking/delete-flip` | Bearer JWT | Soft-delete a flip |
| POST `/profit-tracking/delete-account` | Bearer JWT | Remove an OSRS account |
| POST `/profit-tracking/orphan-transaction` | Bearer JWT | Mark transaction as orphaned |
| POST `/browser-auth/poll` | None | Poll Discord OAuth flow |

## Data Persistence
Directory: `~/.runelite/flipvault/`
- `{hash}_session_data.jsonl` — session stats (startTime, durationMillis, averageCash, sessionProfit); restored if <6h old
- Unacked transactions persisted per display name
- API keys stored via RuneLite `ConfigManager`

## Conventions
- Lombok `@Slf4j` on every class; log via `log.info/debug/warn/error`
- Model classes use `@Data` + `@Builder` or `@AllArgsConstructor`
- `@RequiredArgsConstructor(onConstructor_ = @Inject)` for Guice constructor injection
- Controllers suffix: `*Controller`, managers: `*Manager`, panels: `*Panel`
- All HTTP is async OkHttp `enqueue()` — callbacks run on OkHttp's thread pool

## Things to Avoid
- **Never read game state off ClientThread** — `client.getVarbitValue()`, GE offers, widget reads all require ClientThread
- **Never call `getAccountStatus()` from Swing EDT** — use `getCachedStatus()` instead (avoids null inventory)
- **Never block ClientThread with HTTP** — always async via OkHttp `enqueue()`
- **Never update Swing from background threads** — always `SwingUtilities.invokeLater()`
- **Don't use `JsonParser.parseString()`** — RuneLite's bundled Gson is too old; use `new JsonParser().parse()`
- **Binary responses need ≥4 bytes** — the client throws if `client-transactions` or `client-flips-delta` return fewer than 4 bytes

## Current State
- **Stable**: Auth flow (email + Discord OAuth), suggestion fetch/display, GE overlay highlights, session stats + profit tracking (client-side), JSONL logging, Discord webhooks
- **Server stubs blocking**: Full profit tracking history requires server-side implementation of the three `/profit-tracking/*` endpoints (see `docs/profit-tracking-api-contract.md`)
- **Known limitations**:
  - Premature sell suggestion: server suggests selling an item while its buy order is still open — server needs to check active BUYING offers before suggesting sells
  - Active Flips panel shows "Invested" not "Unrealized P&L" (needs real-time price data)
  - No unit test coverage beyond the launcher class

Keep this file updated when architecture changes.
