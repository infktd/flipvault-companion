# FlipVault RuneLite Plugin v4 — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a complete RuneLite sidebar plugin that reads GE state, authenticates via API key, fetches suggestions from FlipVault backend, highlights GE widgets, auto-fills offers, tracks flips, and displays session stats.

**Architecture:** MVC with Guice DI. 27 Java files across 5 packages (`model`, `manager`, `controller`, `ui`, root). Thin client — all intelligence is server-side. API key auth via `X-API-Key` header to `https://api.flipvault.app/api/plugin/*`.

**Tech Stack:** Java 11 target (compiled on Java 25), Gradle 9.3.1, RuneLite client 1.10.+, Lombok 1.18.40, Gson (RuneLite-bundled, older version), OkHttp (RuneLite-bundled).

**Master Spec:** `docs/flipvault-plugin-architecture-v4.md` — all API contracts, widget IDs, UI layouts, and threading rules are there. Reference it for details not repeated here.

---

## Task 1: Project Scaffold + Build Config

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `gradle.properties`
- Create: `src/main/resources/net/runelite/client/plugins/flipvault/runelite-plugin.properties`
- Create: directory structure for all packages

**Step 1: Initialize Gradle wrapper**

```bash
# Use gradle init or download wrapper manually
gradle wrapper --gradle-version 9.3.1
```

If `gradle` is not installed globally, download the wrapper JAR and scripts manually:
```bash
mkdir -p gradle/wrapper
curl -L -o gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v9.3.1/gradle/wrapper/gradle-wrapper.jar
```

Or use SDKMAN/Homebrew to install gradle first, then run `gradle wrapper`.

**Step 2: Create build files**

`settings.gradle`:
```groovy
rootProject.name = 'flipvault-companion'
```

`build.gradle`:
```groovy
plugins {
    id 'java'
}

repositories {
    mavenCentral()
    maven { url 'https://repo.runelite.net' }
}

dependencies {
    compileOnly 'net.runelite:client:1.10.+'
    compileOnly 'net.runelite:client-api:1.10.+'
    compileOnly 'com.squareup.okhttp3:okhttp:4.12.0'
    compileOnly 'org.projectlombok:lombok:1.18.40'
    annotationProcessor 'org.projectlombok:lombok:1.18.40'

    testImplementation 'junit:junit:4.13.2'
    testCompileOnly 'org.projectlombok:lombok:1.18.40'
    testAnnotationProcessor 'org.projectlombok:lombok:1.18.40'
}

group = 'com.flipvault'
version = '0.1.0'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
}

tasks.withType(JavaCompile).configureEach {
    options.release.set(11)
    options.encoding = 'UTF-8'
}

jar {
    manifest {
        attributes 'Implementation-Title': 'FlipVault Companion'
        attributes 'Implementation-Version': archiveVersion
    }
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx512m
```

**Step 3: Create package directories**

```
src/main/java/com/flipvault/plugin/
src/main/java/com/flipvault/plugin/model/
src/main/java/com/flipvault/plugin/manager/
src/main/java/com/flipvault/plugin/controller/
src/main/java/com/flipvault/plugin/ui/
src/main/resources/net/runelite/client/plugins/flipvault/
```

**Step 4: Create runelite-plugin.properties**

```properties
displayName=FlipVault
author=FlipVault
description=AI-powered GE flipping assistant with 24 trading signals
tags=grand,exchange,flip,flipping,ge,trade,money,trading,flipvault
plugins=com.flipvault.plugin.FlipVaultPlugin
```

**Step 5: Create minimal FlipVaultPlugin.java stub** (just enough to compile)

```java
package com.flipvault.plugin;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
    name = "FlipVault",
    description = "AI-powered GE flipping assistant",
    tags = {"grand", "exchange", "flip", "trading", "flipvault"}
)
public class FlipVaultPlugin extends Plugin {
    @Override
    protected void startUp() {}

    @Override
    protected void shutDown() {}
}
```

**Verify:** `./gradlew build` succeeds, JAR produced in `build/libs/`.

**Commit:** `feat: project scaffold with Gradle build config`

---

## Task 2: Model Classes

Create all 8 model classes. These are pure data — no logic, no imports from other packages.

**Files:**
- Create: `src/main/java/com/flipvault/plugin/model/AuthState.java`
- Create: `src/main/java/com/flipvault/plugin/model/ApiKey.java`
- Create: `src/main/java/com/flipvault/plugin/model/SlotStatus.java`
- Create: `src/main/java/com/flipvault/plugin/model/GESlotState.java`
- Create: `src/main/java/com/flipvault/plugin/model/AccountState.java`
- Create: `src/main/java/com/flipvault/plugin/model/Suggestion.java`
- Create: `src/main/java/com/flipvault/plugin/model/ActiveFlip.java`
- Create: `src/main/java/com/flipvault/plugin/model/Transaction.java`
- Create: `src/main/java/com/flipvault/plugin/model/SessionStats.java`

**Implementation details:**

`AuthState.java` — enum with states: `NO_KEY`, `VALIDATING`, `VALID`, `KEY_CONFLICT`, `EXPIRED`, `LOGGING_IN`, `SELECTING_KEY`, `ERROR`

`ApiKey.java` — Lombok `@Data`: `String id`, `String label`, `String maskedKey`, `String boundTo`, `String createdAt`

`SlotStatus.java` — enum: `EMPTY`, `BUYING`, `SELLING`, `BUY_COMPLETE`, `SELL_COMPLETE`, `CANCELLED`

`GESlotState.java` — Lombok `@Data @AllArgsConstructor`: `int slotIndex`, `SlotStatus status`, `int itemId`, `int price`, `int totalQuantity`, `int quantityFilled`, `int spent`. Add a second constructor for empty slots: `GESlotState(int slotIndex, SlotStatus status)` that zeros out other fields.

`AccountState.java` — Lombok `@Data @Builder`: `String playerName`, `long cashStack`, `boolean isMembers`, `GESlotState[] geSlots`, `int freeSlots`, `long totalWealth`, `long timestamp`

`Suggestion.java` — Lombok `@Data`: `String action`, `int itemId`, `String itemName`, `int price`, `int quantity`, `long estimatedProfit`, `long estimatedGpPerHour`, `String reason`, `int confidence`, `String signal`, `Integer slotIndex` (nullable, for CANCEL action)

`ActiveFlip.java` — Lombok `@Data @AllArgsConstructor`: `int itemId`, `String itemName`, `int buyPrice`, `int quantity`, `long boughtAt` (epoch ms)

`Transaction.java` — Lombok `@Data @Builder`: `int itemId`, `int buyPrice`, `int sellPrice`, `int quantity`, `long profit`, `long buyTimestamp`, `long sellTimestamp`

`SessionStats.java` — Lombok `@Data`: `long totalProfit`, `int flipsDone`, `int flipsWon`, `double avgMarginPercent`, `long sessionStartTime`. Add methods: `double getWinRate()`, `String getFormattedProfit()`, `long getGpPerHour()`, `String getTimeActive()`.

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add all model classes`

---

## Task 3: FlipVaultConfig

**Files:**
- Create: `src/main/java/com/flipvault/plugin/FlipVaultConfig.java`

**Implementation:** See spec Section 8. Config interface with:
- `apiKey()` — `@ConfigItem(secret = true)`, default `""`
- `keyLabel()` — default `""`
- `boundTo()` — default `""`
- `autoFillHotkey()` — default `new Keybind(KeyEvent.VK_F5, 0)`

Use `@ConfigGroup("flipvault")`.

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add FlipVaultConfig`

---

## Task 4: ApiClient

**Files:**
- Create: `src/main/java/com/flipvault/plugin/controller/ApiClient.java`

**Implementation:**

The central HTTP client. Uses OkHttp (bundled with RuneLite). All methods execute synchronously — callers are responsible for running them on the ExecutorService.

```java
package com.flipvault.plugin.controller;

public class ApiClient {
    private static final String BASE_URL = "https://api.flipvault.app/api/plugin";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int TIMEOUT_SECONDS = 10;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private String apiKey; // Set after auth

    public ApiClient() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
        this.gson = new Gson();
    }

    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void clearApiKey() { this.apiKey = null; }

    // Returns parsed JSON response or throws
    public JsonObject login(String email, String password) throws ApiException { ... }
    public JsonObject activateKey(String keyId, String playerName) throws ApiException { ... }
    public JsonObject validateKey(String playerName) throws ApiException { ... }
    public void heartbeat(String playerName, int world, String sessionStarted) throws ApiException { ... }
    public Suggestion requestSuggestion(AccountState state) throws ApiException { ... }
    public void reportTransaction(Transaction tx) throws ApiException { ... }
}
```

**ApiException** — custom exception with `int statusCode`, `String message`, `String responseBody`. Throw on non-2xx responses.

Key implementation notes:
- `login()` does NOT send X-API-Key header (credentials only)
- `activateKey()` does NOT send X-API-Key header (keyId in body)
- All other methods send `X-API-Key` header
- Use `new JsonParser().parse(responseBody)` NOT `JsonParser.parseString()` (older Gson in RuneLite)
- Parse Suggestion from JSON manually or via Gson

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add ApiClient with all endpoint methods`

---

## Task 5: AuthController

**Files:**
- Create: `src/main/java/com/flipvault/plugin/controller/AuthController.java`

**Implementation:**

Manages auth state machine (spec Section 8.1). Holds current `AuthState` and transitions between states.

```java
public class AuthController {
    private volatile AuthState state = AuthState.NO_KEY;
    private final ApiClient apiClient;
    private final FlipVaultConfig config;
    private final ConfigManager configManager;
    private List<ApiKey> availableKeys; // Populated after login

    // State transitions
    public void checkStoredKey() {
        String key = config.apiKey();
        if (key == null || key.isEmpty()) {
            setState(AuthState.NO_KEY);
        } else {
            setState(AuthState.VALIDATING);
            // Caller should invoke validateKeyAsync() on executor
        }
    }

    public void login(String email, String password, Runnable onSuccess, Consumer<String> onError) {
        // Called from LoginPanel button click
        // Sets state to LOGGING_IN, calls apiClient.login() on executor
        // On success: populate availableKeys, set SELECTING_KEY (or auto-activate if 1 key)
        // On error: set ERROR state
    }

    public void activateKey(ApiKey key, String playerName, Runnable onSuccess, Consumer<String> onError) {
        // Calls apiClient.activateKey(), stores result in config
    }

    public void validateKey(String playerName, Runnable onSuccess, Consumer<String> onError) {
        // Calls apiClient.validateKey(), transitions to VALID or EXPIRED/KEY_CONFLICT
    }

    public void onUnauthorized() {
        // Any 401 response: clear stored key, set EXPIRED
        clearStoredKey();
        setState(AuthState.EXPIRED);
    }

    private void clearStoredKey() {
        configManager.setConfiguration("flipvault", "apiKey", "");
        configManager.setConfiguration("flipvault", "keyLabel", "");
        configManager.setConfiguration("flipvault", "boundTo", "");
        apiClient.clearApiKey();
    }

    private void storeKey(String apiKey, String label, String boundTo) {
        configManager.setConfiguration("flipvault", "apiKey", apiKey);
        configManager.setConfiguration("flipvault", "keyLabel", label);
        configManager.setConfiguration("flipvault", "boundTo", boundTo);
        apiClient.setApiKey(apiKey);
    }
}
```

Add a listener interface for UI updates:
```java
public interface AuthStateListener {
    void onAuthStateChanged(AuthState newState);
}
```

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add AuthController with state machine`

---

## Task 6: Auth UI Panels

**Files:**
- Create: `src/main/java/com/flipvault/plugin/ui/LoginPanel.java`
- Create: `src/main/java/com/flipvault/plugin/ui/KeySelectionPanel.java`

**LoginPanel implementation:**
- Extends `JPanel`
- Email `JTextField`, Password `JPasswordField`, Login `JButton`
- "Don't have an account? flipvault.app/signup" link at bottom
- Use RuneLite `ColorScheme` colors (dark theme)
- On login click: call `authController.login(email, password, onSuccess, onError)`
- Show error messages in red label
- Show spinner/loading text during LOGGING_IN state

**KeySelectionPanel implementation:**
- Extends `JPanel`
- `JComboBox<ApiKey>` dropdown with custom renderer showing label + maskedKey + boundTo
- "Activate Key" button
- "Need more keys? flipvault.app/keys" link
- If only 1 key, show it pre-selected
- On activate: call `authController.activateKey(selectedKey, playerName, ...)`
- Handle 409 conflict: show which player the key is bound to

**Styling for both:**
- Background: `ColorScheme.DARKER_GRAY_COLOR`
- Text: `Color.WHITE`
- Error text: `new Color(255, 107, 107)` (#ff6b6b)
- Buttons: `ColorScheme.BRAND_ORANGE` or similar RuneLite accent
- Use `BorderLayout` and `BoxLayout(Y_AXIS)` for layout

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add LoginPanel and KeySelectionPanel`

---

## Task 7: Managers (SessionManager, FlipManager, OfferManager)

**Files:**
- Create: `src/main/java/com/flipvault/plugin/manager/SessionManager.java`
- Create: `src/main/java/com/flipvault/plugin/manager/FlipManager.java`
- Create: `src/main/java/com/flipvault/plugin/manager/OfferManager.java`

**SessionManager:**
```java
public class SessionManager {
    private long totalProfit;
    private int flipsDone;
    private int flipsWon;
    private long sessionStartTime;

    public synchronized void startSession() { ... }
    public synchronized void recordFlip(long profit) { ... }
    public synchronized SessionStats getStats() { ... }
    public synchronized void reset() { ... }
}
```

**FlipManager:**
```java
public class FlipManager {
    // Pending buys keyed by itemId (FIFO queue per item)
    private final Map<Integer, Queue<ActiveFlip>> pendingBuys = new ConcurrentHashMap<>();
    // Active flips (items we own, waiting to sell)
    private final List<ActiveFlip> activeFlips = new CopyOnWriteArrayList<>();

    public synchronized void recordBuy(int itemId, String itemName, int buyPrice, int qty) { ... }
    public synchronized Transaction matchSell(int itemId, int sellPrice, int qty) { ... }
    public synchronized List<ActiveFlip> getActiveFlips() { ... }
}
```

Match sells to buys using FIFO per itemId. When a sell comes in, dequeue the oldest pending buy for that item. Calculate profit = (sellPrice - buyPrice) * quantity.

**OfferManager:**
```java
public class OfferManager {
    private final GESlotState[] previousStates = new GESlotState[8];
    private final Gson gson = new Gson();

    public synchronized void updateSlot(int slot, GESlotState newState) { ... }
    public synchronized GESlotState getPreviousState(int slot) { ... }
    public synchronized boolean hasStateChanged(GESlotState[] newStates) { ... }

    // Persistence: ~/.runelite/flipvault/offers-{playerName}.json
    public void save(String playerName) { ... }
    public void load(String playerName) { ... }
}
```

Use `RuneLite.RUNELITE_DIR` for the base path (`~/.runelite/`). Create `flipvault/` subdirectory.

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add SessionManager, FlipManager, OfferManager`

---

## Task 8: GEOfferHandler

**Files:**
- Create: `src/main/java/com/flipvault/plugin/controller/GEOfferHandler.java`

**Implementation:**

Implements the state machine from spec Section 4.1. Processes `GrandExchangeOfferChanged` events.

```java
public class GEOfferHandler {
    private final OfferManager offerManager;
    private final FlipManager flipManager;
    private final SessionManager sessionManager;
    private final ApiClient apiClient;
    private final ExecutorService executor;

    public void onOfferChanged(GrandExchangeOfferChanged event) {
        int slot = event.getSlot();
        GrandExchangeOffer offer = event.getOffer();
        GESlotState newState = mapOffer(slot, offer);
        GESlotState prevState = offerManager.getPreviousState(slot);

        // State transition logic (spec Section 4.1)
        if (prevState != null && newState != null) {
            detectTransaction(prevState, newState);
        }

        offerManager.updateSlot(slot, newState);
    }

    private void detectTransaction(GESlotState prev, GESlotState current) {
        // BUY_COMPLETE: record pending buy in FlipManager
        // SELL_COMPLETE: match against pending buy, create Transaction
        // Report transaction to backend on executor
    }

    private GESlotState mapOffer(int slot, GrandExchangeOffer offer) {
        // Map RuneLite's GrandExchangeOfferState to our SlotStatus
        // See spec Section 3.3
    }

    // Map RuneLite enum to our SlotStatus enum
    private SlotStatus mapOfferState(GrandExchangeOfferState state) {
        switch (state) {
            case EMPTY: return SlotStatus.EMPTY;
            case BUYING: return SlotStatus.BUYING;
            case BOUGHT: return SlotStatus.BUY_COMPLETE;
            case SELLING: return SlotStatus.SELLING;
            case SOLD: return SlotStatus.SELL_COMPLETE;
            case CANCELLED_BUY:
            case CANCELLED_SELL: return SlotStatus.CANCELLED;
            default: return SlotStatus.EMPTY;
        }
    }
}
```

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add GEOfferHandler with transaction inference`

---

## Task 9: SuggestionController

**Files:**
- Create: `src/main/java/com/flipvault/plugin/controller/SuggestionController.java`

**Implementation:**

Controls when/how to request suggestions. See spec Section 5.1-5.2.

```java
public class SuggestionController {
    private static final long MIN_REQUEST_INTERVAL_MS = 2000;
    private static final long WAIT_RETRY_MS = 30000;

    private final ApiClient apiClient;
    private final ExecutorService executor;
    private volatile Suggestion currentSuggestion;
    private volatile long lastRequestTime;
    private volatile boolean requestInFlight;

    public interface SuggestionListener {
        void onSuggestionUpdated(Suggestion suggestion);
        void onSuggestionError(String error);
    }

    private SuggestionListener listener;

    public void onStateChanged(AccountState state) {
        if (!shouldRequest(state)) return;
        requestSuggestion(state);
    }

    private boolean shouldRequest(AccountState state) {
        if (state == null || state.getPlayerName() == null) return false;
        if (requestInFlight) return false;
        if (System.currentTimeMillis() - lastRequestTime < MIN_REQUEST_INTERVAL_MS) return false;
        if (state.getFreeSlots() == 0) return false;
        return true;
    }

    private void requestSuggestion(AccountState state) {
        requestInFlight = true;
        lastRequestTime = System.currentTimeMillis();
        executor.submit(() -> {
            try {
                Suggestion s = apiClient.requestSuggestion(state);
                currentSuggestion = s;
                SwingUtilities.invokeLater(() -> {
                    if (listener != null) listener.onSuggestionUpdated(s);
                });
            } catch (ApiException e) {
                SwingUtilities.invokeLater(() -> {
                    if (listener != null) listener.onSuggestionError(e.getMessage());
                });
            } finally {
                requestInFlight = false;
            }
        });
    }

    public void refresh(AccountState state) {
        // Manual refresh, bypasses rate limit
        lastRequestTime = 0;
        onStateChanged(state);
    }

    public Suggestion getCurrentSuggestion() { return currentSuggestion; }
}
```

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add SuggestionController with rate limiting`

---

## Task 10: Content Panels (SuggestionPanel, ActiveFlipsPanel, StatsPanel)

**Files:**
- Create: `src/main/java/com/flipvault/plugin/ui/SuggestionPanel.java`
- Create: `src/main/java/com/flipvault/plugin/ui/ActiveFlipsPanel.java`
- Create: `src/main/java/com/flipvault/plugin/ui/StatsPanel.java`

**SuggestionPanel:**
- Shows current suggestion (spec Section 7.2)
- States: suggestion available, waiting, collect, loading, error
- BUY suggestions: green action label (#38b000)
- SELL suggestions: red action label (#ff6b6b)
- Shows: item name, action, price x quantity, est. profit, est. GP/hr, reason
- "Open FlipVault" link to web dashboard
- Refresh button
- Implements `SuggestionController.SuggestionListener`

**ActiveFlipsPanel:**
- Shows active flips list (spec Section 7.3)
- Each flip: item name, unrealized P&L, status, bought→current price
- Total unrealized P&L at bottom
- Use green for positive P&L, red for negative

**StatsPanel:**
- Key-value stats display (spec Section 7.4)
- Total profit, flips done, win rate, avg margin, GP/hr avg, time active
- Use `GridLayout` or label pairs

**All panels:**
- Extend `JPanel`
- Background: `ColorScheme.DARKER_GRAY_COLOR`
- Implement `void update(...)` method for refreshing display
- Use RuneLite `FontManager` and `ColorScheme`

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add SuggestionPanel, ActiveFlipsPanel, StatsPanel`

---

## Task 11: FlipVaultPanel (Main Container)

**Files:**
- Create: `src/main/java/com/flipvault/plugin/ui/FlipVaultPanel.java`

**Implementation:**

Main sidebar panel. See spec Section 7.1.

```java
public class FlipVaultPanel extends PluginPanel {
    // Layout: BorderLayout
    // NORTH: header (FlipVault + PREMIUM badge) + session stats bar
    // CENTER: CardLayout switching between:
    //   - LoginPanel (when NO_KEY or LOGGING_IN)
    //   - KeySelectionPanel (when SELECTING_KEY)
    //   - Tab container (when VALID) with JTabbedPane or custom tab bar
    // SOUTH: footer (connection status + version)

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel;
    private final LoginPanel loginPanel;
    private final KeySelectionPanel keySelectionPanel;
    private final JPanel tabContainer; // holds tab bar + sub-panels
    private final SuggestionPanel suggestionPanel;
    private final ActiveFlipsPanel activeFlipsPanel;
    private final StatsPanel statsPanel;

    // Header: "FlipVault" bold + "PREMIUM" badge (small green label)
    // Session stats bar: "Session: +4.8M  Flips: 7" — updates from SessionManager
    // Footer: green dot "Connected" + "v0.1.0"

    // Tab bar: 3 buttons (Suggest | Active | Stats) — highlight active tab
    // Use CardLayout to switch between the 3 sub-panels

    public void onAuthStateChanged(AuthState state) {
        // Switch cards based on auth state
    }

    public void updateSessionStats(SessionStats stats) {
        // Update header bar
    }
}
```

Implement `AuthController.AuthStateListener` to react to auth state changes.

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add FlipVaultPanel with tabs and auth-aware switching`

---

## Task 12: HighlightController + Auto-Fill

**Files:**
- Create: `src/main/java/com/flipvault/plugin/controller/HighlightController.java`

**HighlightController implementation:**

Extends `Overlay`. See spec Section 7.6.

```java
@Singleton
public class HighlightController extends Overlay {
    private static final Color HIGHLIGHT_BUY = new Color(56, 176, 0, 180);
    private static final Color HIGHLIGHT_SELL = new Color(255, 107, 107, 180);
    private static final Color HIGHLIGHT_ACT = new Color(34, 211, 238, 180);

    @Inject private Client client;

    private Suggestion currentSuggestion;

    @Override
    public Dimension render(Graphics2D graphics) {
        if (currentSuggestion == null || !isGEOpen()) return null;
        // Highlight based on suggestion action (spec Section 7.6)
        return null;
    }

    private boolean isGEOpen() {
        Widget geWindow = client.getWidget(WidgetInfo.GRAND_EXCHANGE_WINDOW_CONTAINER);
        return geWindow != null && !geWindow.isHidden();
    }

    private void highlightWidget(Graphics2D g, Widget widget, Color color) {
        if (widget == null || widget.isHidden()) return;
        Rectangle bounds = widget.getBounds();
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80));
        g.fill(bounds);
        g.setColor(color);
        g.setStroke(new BasicStroke(2));
        g.draw(bounds);
    }
}
```

**Auto-Fill (in HighlightController or FlipVaultPlugin):**

Hotkey handler for F5 (configurable). When pressed:
1. Check: suggestion exists, GE offer setup screen open
2. On ClientThread: set price and quantity widgets
3. Visual feedback in sidebar ("Filled!" text)

Use `KeyListener` or RuneLite's `HotkeyListener` on the `Keybind` from config.

See spec Section 7.7 for edge cases and anti-cheat considerations.

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: add HighlightController and auto-fill hotkey`

---

## Task 13: FlipVaultPlugin (Main Orchestrator)

**Files:**
- Modify: `src/main/java/com/flipvault/plugin/FlipVaultPlugin.java`

**Implementation:**

Wire everything together. See spec Section 2.

```java
@PluginDescriptor(
    name = "FlipVault",
    description = "AI-powered GE flipping assistant",
    tags = {"grand", "exchange", "flip", "trading", "flipvault"}
)
public class FlipVaultPlugin extends Plugin {
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar clientToolbar;
    @Inject private FlipVaultConfig config;
    @Inject private ConfigManager configManager;
    @Inject private OverlayManager overlayManager;
    @Inject private ScheduledExecutorService scheduledExecutor; // RuneLite-provided

    private ExecutorService flipvaultExecutor; // Our 2-thread pool
    private NavigationButton navButton;

    // Managers
    private SessionManager sessionManager;
    private FlipManager flipManager;
    private OfferManager offerManager;

    // Controllers
    private ApiClient apiClient;
    private AuthController authController;
    private SuggestionController suggestionController;
    private GEOfferHandler geOfferHandler;
    @Inject private HighlightController highlightController;

    // UI
    private FlipVaultPanel panel;

    // State
    private AccountState lastAccountState;
    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> heartbeatFuture;
    private boolean loggedIn;

    @Override
    protected void startUp() {
        // 1. Create executor (2 threads)
        flipvaultExecutor = Executors.newFixedThreadPool(2,
            r -> { Thread t = new Thread(r, "flipvault"); t.setDaemon(true); return t; });

        // 2. Instantiate managers
        sessionManager = new SessionManager();
        flipManager = new FlipManager();
        offerManager = new OfferManager();

        // 3. Instantiate controllers
        apiClient = new ApiClient();
        authController = new AuthController(apiClient, config, configManager, flipvaultExecutor);
        suggestionController = new SuggestionController(apiClient, flipvaultExecutor);
        geOfferHandler = new GEOfferHandler(offerManager, flipManager, sessionManager, apiClient, flipvaultExecutor);

        // 4. Create UI
        panel = new FlipVaultPanel(authController, suggestionController, sessionManager, flipManager, config);
        suggestionController.setListener(panel.getSuggestionPanel());
        authController.addListener(panel);

        // 5. Register sidebar
        navButton = NavigationButton.builder()
            .tooltip("FlipVault")
            .icon(/* plugin icon */)
            .panel(panel)
            .priority(5)
            .build();
        clientToolbar.addNavigation(navButton);

        // 6. Register overlay
        overlayManager.add(highlightController);

        // 7. Check auth on startup
        authController.checkStoredKey();

        // 8. Schedule 1-second tick
        tickFuture = scheduledExecutor.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);

        // 9. Schedule heartbeat (60 seconds)
        heartbeatFuture = scheduledExecutor.scheduleAtFixedRate(this::heartbeat, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    protected void shutDown() {
        if (tickFuture != null) tickFuture.cancel(true);
        if (heartbeatFuture != null) heartbeatFuture.cancel(true);
        flipvaultExecutor.shutdown();
        try { flipvaultExecutor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        offerManager.save(lastAccountState != null ? lastAccountState.getPlayerName() : null);
        overlayManager.remove(highlightController);
        clientToolbar.removeNavigation(navButton);
    }

    private void tick() {
        if (!loggedIn) return;
        clientThread.invokeLater(this::snapshotGameState);
    }

    private void snapshotGameState() {
        // Read GE slots, cash, player name — see spec Section 3
        AccountState newState = buildAccountState();
        if (offerManager.hasStateChanged(newState.getGeSlots())) {
            suggestionController.onStateChanged(newState);
            SwingUtilities.invokeLater(() -> panel.updateSessionStats(sessionManager.getStats()));
        }
        lastAccountState = newState;
    }

    private void heartbeat() {
        if (!loggedIn || authController.getState() != AuthState.VALID) return;
        flipvaultExecutor.submit(() -> {
            try {
                apiClient.heartbeat(
                    lastAccountState != null ? lastAccountState.getPlayerName() : "",
                    client.getWorld(),
                    /* sessionStarted ISO string */
                );
            } catch (Exception e) { /* log and ignore */ }
        });
    }

    // Event subscriptions — see spec Section 2.4
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) { ... }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        geOfferHandler.onOfferChanged(event);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) { ... }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) { ... }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) { ... }
}
```

Wire the auto-fill hotkey handling (register `KeyListener` or use RuneLite's keybind system).

**Verify:** `./gradlew build` succeeds.

**Commit:** `feat: wire FlipVaultPlugin orchestrating all components`

---

## Task 14: Persistence + Polish

**Files:**
- Modify: `src/main/java/com/flipvault/plugin/manager/OfferManager.java` (add save/load)
- Add: plugin icon resource

**Persistence:**
- Save to `~/.runelite/flipvault/offers-{playerName}.json` on shutdown, logout, world hop, every 5 minutes
- Load on login (match playerName)
- Use Gson for serialization (older API: `new JsonParser().parse()`)
- Save session stats to `~/.runelite/flipvault/session-{playerName}.json`
- Append flips to `~/.runelite/flipvault/flips-{playerName}.jsonl`

**Error handling polish:**
- 401 → clear key, show re-login
- 403 → show "Subscription expired" message
- 409 → show key conflict panel
- 500 → show "Server error", retry after 30s
- Timeout → show "Timeout", retry after 5s
- Network failure → show "Connection error", retry after 10s

**Plugin icon:**
- Create or embed a 16x16 and 32x32 icon for the sidebar NavigationButton
- Place in `src/main/resources/com/flipvault/plugin/`

**Final verification:**
- `./gradlew clean build` succeeds
- JAR produced at `build/libs/flipvault-companion-0.1.0.jar`
- All 27 source files present and compiling

**Commit:** `feat: add persistence, error handling, and plugin icon`

---

## Summary

| Task | Files | Description |
|------|-------|-------------|
| 1 | 5 | Project scaffold + Gradle build |
| 2 | 9 | All model classes |
| 3 | 1 | FlipVaultConfig |
| 4 | 1 | ApiClient (HTTP) |
| 5 | 1 | AuthController (state machine) |
| 6 | 2 | LoginPanel + KeySelectionPanel |
| 7 | 3 | SessionManager + FlipManager + OfferManager |
| 8 | 1 | GEOfferHandler (transaction inference) |
| 9 | 1 | SuggestionController |
| 10 | 3 | SuggestionPanel + ActiveFlipsPanel + StatsPanel |
| 11 | 1 | FlipVaultPanel (main container) |
| 12 | 1 | HighlightController + Auto-Fill |
| 13 | 1 | FlipVaultPlugin (orchestrator) |
| 14 | 2+ | Persistence + Polish + Icon |

**Total: 14 tasks, ~32 files, 27 Java source files**

Each task produces a compilable increment. The final build should produce a loadable RuneLite plugin JAR.
