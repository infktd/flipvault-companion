# FlipVault RuneLite Plugin -- Architecture Specification v4

## Purpose

Build a RuneLite sidebar plugin that replicates Flipping Copilot's behavioral model exactly: the plugin reads game state, sends it to our backend, and receives a single personalized suggestion. The UI is the Phase 1 layout from our mockup. No dump detection, no multi-suggestion lists, no score circles yet. Just a clean, working, single-suggestion flow.

This document is the complete specification for Claude Code to implement the plugin from scratch. Do not fork or copy Copilot's code. Build from scratch using the same RuneLite API patterns documented below.

---

## 1. Architecture Overview

### 1.1 Layered MVC Structure

```
com.flipvault.plugin/
  ├── FlipVaultPlugin.java          # Main plugin controller (@PluginDescriptor)
  ├── FlipVaultConfig.java          # RuneLite config interface (API key only)
  │
  ├── model/                        # Pure data structures (no logic, leaf dependency)
  │   ├── Suggestion.java           # Single suggestion from backend
  │   ├── AccountState.java         # Aggregated game state snapshot
  │   ├── GESlotState.java          # State of one GE slot
  │   ├── ActiveFlip.java           # A flip in progress
  │   ├── SessionStats.java         # Current session profit/flip count
  │   ├── Transaction.java          # A completed buy or sell
  │   ├── AuthState.java            # Auth state enum (NO_KEY, VALID, EXPIRED, etc.)
  │   └── ApiKey.java               # API key metadata (id, label, maskedKey, boundTo)
  │
  ├── manager/                      # Persisted state (depends only on model)
  │   ├── SessionManager.java       # Session profit, flip count, GP/hr tracking
  │   ├── FlipManager.java          # Active flips, completed transactions
  │   └── OfferManager.java         # GE offer state tracking + persistence
  │
  ├── controller/                   # Event handlers + business logic
  │   ├── GEOfferHandler.java       # Processes GE offer events, infers transactions
  │   ├── SuggestionController.java # Decides when to request suggestions, manages flow
  │   ├── AuthController.java       # Login flow, key selection, key validation
  │   ├── ApiClient.java            # All HTTP to https://api.flipvault.app/api/plugin/*
  │   └── HighlightController.java  # GE widget highlighting to guide user actions
  │
  └── ui/                           # Swing panels (minimal state, display only)
      ├── FlipVaultPanel.java       # Main panel with tabs
      ├── LoginPanel.java           # Email/password login form
      ├── KeySelectionPanel.java    # API key dropdown + activate button
      ├── SuggestionPanel.java      # Shows current single suggestion
      ├── ActiveFlipsPanel.java     # Shows active flips with P&L
      └── StatsPanel.java           # Session statistics
```

### 1.2 Dependency Rules

- **model** classes are pure data. No imports from other packages. They are the leaf.
- **manager** classes depend only on **model**. They manage persisted state.
- **controller** classes depend on **model** and **manager**. They contain business logic.
- **ui** classes depend on **model**, **manager**, and **controller**. They render state.
- **FlipVaultPlugin** orchestrates everything. It wires managers, controllers, and UI together.

### 1.3 Threading Model

The plugin operates across 4 thread contexts. Getting this wrong causes crashes.

| Thread | What Runs Here | Access Rules |
|--------|---------------|-------------|
| **ClientThread** | RuneLite event handlers (`@Subscribe`), game API reads (`client.getVarbitValue()`, etc.) | Only thread that can read game state. Use `clientThread.invoke()` to schedule work here. |
| **ScheduledExecutorService** | 1-second periodic tick. Injected from RuneLite. | Good for polling/checking state changes. Cannot read game state directly -- must schedule onto ClientThread. |
| **ExecutorService (flipvault)** | Custom 2-thread pool for API requests. Created in `startUp()`. | Never read game state here. Run HTTP calls here. Update managers with synchronized methods. |
| **Swing EDT** | UI event handlers (button clicks). | Only thread that can update UI components. Use `SwingUtilities.invokeLater()` from other threads. |

**Critical rule**: Game state reads (inventory, GE offers, bank) MUST happen on ClientThread. API calls MUST happen on the ExecutorService. UI updates MUST happen on Swing EDT. Violating this causes RuneLite crashes or UI freezes.

---

## 2. Plugin Lifecycle

### 2.1 Startup (`startUp()`)

Called by RuneLite when the plugin is enabled. Execute in this order:

1. Create the custom `ExecutorService` (2 threads, named "flipvault")
2. Instantiate all managers: `SessionManager`, `FlipManager`, `OfferManager`
3. Instantiate all controllers: `ApiClient`, `SuggestionController`, `GEOfferHandler`
4. Create UI panels: `FlipVaultPanel` (contains tab container with sub-panels)
5. Register the panel with RuneLite's `ClientToolbar` via `NavigationButton`
6. Load persisted state from disk (previous session's offer states, flip history)
7. Schedule the 1-second tick on `ScheduledExecutorService`

### 2.2 The 1-Second Tick

Every 1 second, the scheduled executor fires. This is the plugin's heartbeat. It should:

1. Schedule a `clientThread.invoke()` to snapshot the current game state
2. Inside that invoke: read GE slots, inventory, bank value, player name
3. Build an `AccountState` object from the snapshot
4. Compare against the previous `AccountState` to detect changes
5. If state changed (new offer, offer completed, slot freed, cash changed):
   - Call `suggestionController.onStateChanged(newState)`
   - This triggers a suggestion request to the backend (on the ExecutorService)
6. If no change: do nothing (don't spam the backend)

### 2.3 Shutdown (`shutDown()`)

1. Cancel the scheduled tick
2. Shutdown the ExecutorService (graceful, 5-second timeout)
3. Persist current state to disk via `OfferManager.save()`
4. Clean up UI (remove NavigationButton)

### 2.4 Key RuneLite Events to Subscribe

```java
@Subscribe
public void onGameStateChanged(GameStateChanged event) {
    // LOGGED_IN: start tracking, authenticate with backend
    // LOGIN_SCREEN: stop tracking, save state
    // HOPPING: pause tracking (world hop)
}

@Subscribe
public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
    // Core event. Fires when any GE slot changes state.
    // Pass to GEOfferHandler for transaction inference.
    geOfferHandler.onOfferChanged(event);
}

@Subscribe
public void onItemContainerChanged(ItemContainerChanged event) {
    // Fires when inventory or bank changes.
    // Update cash stack tracking.
    if (event.getContainerId() == InventoryID.INVENTORY.getId()) {
        // Update inventory state
    }
}

@Subscribe  
public void onVarbitChanged(VarbitChanged event) {
    // Can detect GE interface open/close state
    // Useful for knowing when to show suggestions
}

@Subscribe
public void onWidgetLoaded(WidgetLoaded event) {
    // Detect when GE interface widgets are loaded
    // Group ID 465 = Grand Exchange
}
```

---

## 3. Game State Collection (AccountState)

### 3.1 What to Collect

The `AccountState` is a snapshot of everything the backend needs to make a suggestion. Collect this on the ClientThread:

```java
public class AccountState {
    String playerName;          // client.getLocalPlayer().getName()
    long cashStack;             // Sum of coins in inventory
    boolean isMembers;          // client.getWorldType().contains(WorldType.MEMBERS)
    GESlotState[] geSlots;      // 8 GE slots
    int freeSlots;              // Count of empty GE slots
    long totalWealth;           // Cash + value of pending offers
    long timestamp;             // System.currentTimeMillis()
}
```

### 3.2 GE Slot State

Each of the 8 GE slots has a state. Read from RuneLite's GrandExchangeOffer:

```java
public class GESlotState {
    int slotIndex;              // 0-7
    SlotStatus status;          // EMPTY, BUYING, SELLING, BOUGHT, SOLD, CANCELLED
    int itemId;                 // OSRS item ID (0 if empty)
    int price;                  // Offer price per unit
    int totalQuantity;          // Total quantity in the offer
    int quantityFilled;         // How many have been filled so far
    int spent;                  // Total GP spent/received so far
}

public enum SlotStatus {
    EMPTY,
    BUYING,          // Active buy offer
    SELLING,         // Active sell offer
    BUY_COMPLETE,    // Buy offer fully filled, waiting to collect
    SELL_COMPLETE,   // Sell offer fully filled, waiting to collect
    CANCELLED        // Offer cancelled, waiting to collect
}
```

### 3.3 Reading GE Slots

GE slot data is available via RuneLite's `GrandExchangeOffer` interface:

```java
// On ClientThread only:
for (int slot = 0; slot < 8; slot++) {
    GrandExchangeOffer offer = client.getGrandExchangeOffers()[slot];
    if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) {
        geSlots[slot] = new GESlotState(slot, SlotStatus.EMPTY);
        continue;
    }
    
    geSlots[slot] = new GESlotState(
        slot,
        mapOfferState(offer.getState()),  // Map RuneLite enum to ours
        offer.getItemId(),
        offer.getPrice(),
        offer.getTotalQuantity(),
        offer.getQuantitySold(),  // Actually "quantity filled" for both buy/sell
        offer.getSpent()
    );
}
```

### 3.4 Reading Cash Stack

```java
ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
long cash = 0;
if (inventory != null) {
    for (Item item : inventory.getItems()) {
        if (item.getId() == ItemID.COINS_995) {
            cash = item.getQuantity();
        }
    }
}
```

---

## 4. Transaction Inference (GEOfferHandler)

This is the most complex part. The plugin must detect when a flip completes by watching GE offer state transitions.

### 4.1 State Machine

Track each GE slot's previous state. When `onGrandExchangeOfferChanged` fires, compare:

```
Previous State -> New State -> Action
─────────────────────────────────────
EMPTY          -> BUYING     -> New buy offer placed
BUYING         -> BUYING     -> Partial fill (quantityFilled increased)
BUYING         -> BUY_COMPLETE -> Buy offer fully filled
BUYING         -> CANCELLED  -> Buy offer cancelled
EMPTY          -> SELLING    -> New sell offer placed
SELLING        -> SELLING    -> Partial fill
SELLING        -> SELL_COMPLETE -> Sell offer fully filled
SELLING        -> CANCELLED  -> Sell cancelled
BUY_COMPLETE   -> EMPTY      -> Items collected from completed buy
SELL_COMPLETE  -> EMPTY      -> GP collected from completed sell
CANCELLED      -> EMPTY      -> Cancelled offer collected
```

### 4.2 Detecting Completed Flips

A flip is: BUY item at price X, then SELL same item at price Y. Profit = (Y - X) * quantity - GE tax.

Track this with `FlipManager`:
- When a BUY completes: store `{itemId, buyPrice, quantity, timestamp}` as a pending flip
- When a SELL completes for the same itemId: match it against pending buys (FIFO)
- Calculate profit, create a `Transaction`, update `SessionManager`

### 4.3 Persistence

Store offer states to disk as JSON in `~/.runelite/flipvault/offers.json`. This handles:
- Client crashes (restore offer state on next startup)
- World hops (offers persist across worlds)
- Log out/in cycles

---

## 5. Suggestion Flow (SuggestionController)

This is the core loop. It mirrors Copilot's exact behavioral model.

### 5.1 When to Request a Suggestion

Request a new suggestion from the backend when ANY of these occur:

1. **A GE slot becomes free** (offer completed + collected, or cancelled + collected)
2. **Player first logs in** (initial state)
3. **Player collects items/GP from a completed offer** (state changed)
4. **Player opens the GE interface** (they're ready to act)
5. **Previous suggestion was acted on** (they placed the suggested offer)
6. **Manual refresh** (user clicks refresh button in the panel)

Do NOT request a suggestion when:
- All 8 GE slots are occupied (no room to act)
- Player is not logged in
- A request is already in flight
- Less than 2 seconds since last request (rate limit)

### 5.2 Suggestion Request Flow

```
1. State change detected (ClientThread, via 1-second tick or @Subscribe)
       |
2. SuggestionController.onStateChanged(accountState) called
       |
3. Check: should we request? (has free slots? not rate limited? logged in?)
       |
4. If yes: submit task to ExecutorService
       |
5. On ExecutorService thread:
   a. Build request payload from AccountState
   b. POST to FlipVault API: /api/plugin/suggest
   c. Parse response into Suggestion object
       |
6. On Swing EDT (SwingUtilities.invokeLater):
   a. Update SuggestionPanel with new suggestion
   b. Show item name, action, price, quantity, estimated profit
```

### 5.3 Request Payload (Plugin -> Backend)

Send as JSON to `POST https://api.flipvault.app/api/plugin/suggest`:

```json
{
    "playerName": "Xeliac",
    "cashStack": 510000000,
    "isMembers": true,
    "freeSlots": 3,
    "geSlots": [
        {
            "slotIndex": 0,
            "status": "BUYING",
            "itemId": 560,
            "price": 210,
            "totalQuantity": 5000,
            "quantityFilled": 2300,
            "spent": 483000
        },
        {
            "slotIndex": 1,
            "status": "EMPTY"
        }
        // ... all 8 slots
    ],
    "activeFlips": [
        {
            "itemId": 11802,
            "buyPrice": 28500000,
            "quantity": 1,
            "boughtAt": "2026-02-23T14:30:00Z"
        }
    ],
    "timestamp": "2026-02-23T15:45:00Z"
}
```

### 5.4 Response Payload (Backend -> Plugin)

The backend returns ONE suggestion:

```json
{
    "action": "BUY",
    "itemId": 2,
    "itemName": "Cannonball",
    "price": 170,
    "quantity": 5000,
    "estimatedProfit": 110000,
    "estimatedGpPerHour": 1200000,
    "reason": "Strong buy signal. 22gp margin with high volume.",
    "confidence": 87,
    "signal": "BUY"
}
```

Possible `action` values:
- `"BUY"` -- place a buy offer for this item at this price
- `"SELL"` -- place a sell offer for this item at this price
- `"CANCEL"` -- cancel the offer in slot X (with `slotIndex` field)
- `"WAIT"` -- no good opportunities right now, wait
- `"COLLECT"` -- collect completed offers first, then we'll suggest

### 5.5 "WAIT" and "COLLECT" States

When the backend returns `WAIT`: show "Waiting for opportunities..." in the panel. Re-request after 30 seconds or when state changes.

When the backend returns `COLLECT`: show "Collect your completed offers first" with a list of which slots to collect. Re-request after collection detected.

---

## 6. Backend API Endpoints

All plugin API requests go through `https://api.flipvault.app`. This is routed via nginx to the same Next.js backend -- the web app routes do not change, but the plugin uses the `api.` subdomain for clean separation and request tracking.

**Base URL for all plugin endpoints:** `https://api.flipvault.app/api/plugin/`

These endpoints already exist or need to be created on the FlipVault backend.

### 6.1 Authentication -- API Key Model

FlipVault uses API keys instead of JWT tokens. Each FlipVault Pro subscription includes 1 API key. Users can purchase additional keys at $5.99/key/month for multi-account flipping. Each key binds to one OSRS player name at a time. For reference, Flipping Copilot charges $6.99/month per account, so FlipVault is cheaper across the board ($5.99 for one, $11.98 for two vs Copilot's $13.98 for two).

The authentication flow is a one-time login that exchanges credentials for API key selection. After that, only the API key is stored and used.

#### 6.1.1 Login (one-time credential exchange)

```
POST /api/plugin/login
Body:
{
    "email": "jay@example.com",
    "password": "hunter2"
}

Response 200:
{
    "displayName": "Jay",
    "plan": "premium",
    "maxKeys": 1,
    "additionalKeyPrice": 5.99,
    "keys": [
        {
            "id": "fv_key_abc123",
            "label": "Main Account",
            "maskedKey": "fv_...c123",
            "boundTo": null,
            "createdAt": "2026-01-15T00:00:00Z"
        },
        {
            "id": "fv_key_def456",
            "label": "Alt Account",
            "maskedKey": "fv_...f456",
            "boundTo": "AltPlayer99",
            "createdAt": "2026-02-01T00:00:00Z"
        }
    ]
}

Response 401:
{ "error": "Invalid credentials" }

Response 403:
{ "error": "No active subscription. Subscribe at flipvault.app/pricing" }
```

This endpoint is called ONCE during first-time setup. The email/password are NOT stored by the plugin. Only the selected API key is persisted.

#### 6.1.2 Activate Key (bind key to OSRS account)

After the user selects a key from the dropdown, bind it to the current OSRS player name:

```
POST /api/plugin/activate-key
Body:
{
    "keyId": "fv_key_abc123",
    "playerName": "Xeliac"
}

Response 200:
{
    "apiKey": "fv_key_abc123_full_secret_value",
    "boundTo": "Xeliac",
    "plan": "premium",
    "expiresAt": "2026-03-23T00:00:00Z"
}

Response 409 (key bound to different account):
{
    "error": "This key is active on account 'OtherPlayer'. Unbind it at flipvault.app/keys or use a different key.",
    "boundTo": "OtherPlayer"
}

Response 404:
{ "error": "Key not found" }
```

The full API key value is returned ONCE during activation. The plugin stores it in RuneLite's config (encrypted via @Secret). This is the only secret the plugin ever persists.

#### 6.1.3 Validate Key (every plugin launch)

On every RuneLite startup, validate the stored API key before proceeding:

```
POST /api/plugin/validate-key
Headers: X-API-Key: fv_key_abc123_full_secret_value
Body:
{
    "playerName": "Xeliac"
}

Response 200:
{
    "valid": true,
    "plan": "premium",
    "boundTo": "Xeliac",
    "expiresAt": "2026-03-23T00:00:00Z"
}

Response 401 (key invalid, expired, or revoked):
{
    "valid": false,
    "reason": "Key expired. Renew at flipvault.app/keys"
}

Response 409 (key bound to different account):
{
    "valid": false,
    "reason": "Key bound to 'OtherPlayer'. Unbind at flipvault.app/keys",
    "boundTo": "OtherPlayer"
}
```

If validation fails, the plugin clears the stored key and shows the login flow again.

#### 6.1.4 API Key Binding Rules

- Each API key binds to exactly one OSRS player name at a time
- Binding happens automatically on first use (activate-key) or when validate-key is called with a player name
- Users can unbind/rebind keys from the FlipVault web dashboard at flipvault.app/keys
- Keys auto-unbind after 30 days of inactivity on the bound account
- If a user tries to use a key that's bound to a different player name, the request is rejected with a clear error message
- Free tier users do NOT get API keys -- plugin is Premium only

#### 6.1.5 All Subsequent API Requests

After authentication, ALL plugin API requests use the `X-API-Key` header. No JWTs, no Bearer tokens, no token refresh logic. Simple and stateless.

```
Headers for all requests:
  X-API-Key: fv_key_abc123_full_secret_value
```

If any request returns 401, the plugin clears the stored key and shows the login flow.

### 6.2 Heartbeat

```
POST /api/plugin/heartbeat
Headers: X-API-Key: fv_key_abc123_full_secret_value
Body:
{
    "playerName": "Xeliac",
    "world": 302,
    "sessionStarted": "2026-02-23T14:00:00Z"
}

Response 200:
{ "status": "ok" }
```

Send every 60 seconds while logged in. Backend uses this for:
- Tracking active plugin users
- Session duration analytics
- Keeping the key binding fresh (resets the 30-day inactivity timer)

### 6.3 Suggestion

```
POST /api/plugin/suggest
Headers: X-API-Key: fv_key_abc123_full_secret_value
Body: (See 5.3 above)

Response 200: (See 5.4 above)
```

### 6.4 Transaction Report

When a flip completes, report it:

```
POST /api/plugin/transaction
Headers: X-API-Key: fv_key_abc123_full_secret_value
Body:
{
    "itemId": 2,
    "buyPrice": 170,
    "sellPrice": 192,
    "quantity": 5000,
    "profit": 91500,
    "buyTimestamp": "2026-02-23T14:30:00Z",
    "sellTimestamp": "2026-02-23T14:45:00Z"
}

Response 200:
{ "recorded": true }
```

---

## 7. UI (Phase 1 -- Copilot-Style Simple)

### 7.1 Panel Structure

The main panel is 242px wide (RuneLite standard sidebar). It contains:

```
┌──────────────────────────┐
│ FlipVault       PREMIUM  │  <- Header
├──────────────────────────┤
│ Session: +4.8M  Flips: 7 │  <- Session stats bar
├──────────────────────────┤
│ Suggest │ Active │ Stats  │  <- Tab bar
├──────────────────────────┤
│                          │
│  [Current Tab Content]   │  <- Content area (scrollable)
│                          │
├──────────────────────────┤
│ ● Connected    v0.1.0    │  <- Footer
└──────────────────────────┘
```

### 7.2 Suggest Tab

Shows ONE suggestion at a time. But first, handles authentication states.

**When no API key is stored (first-time setup):**
```
┌──────────────────────────┐
│                          │
│  Welcome to FlipVault    │
│                          │
│  Email:                  │
│  [____________________]  │
│                          │
│  Password:               │
│  [____________________]  │
│                          │
│     [ Login ]            │
│                          │
│  Don't have an account?  │
│  flipvault.app/signup    │
│                          │
└──────────────────────────┘
```

**After login, if user has multiple API keys (key selection dropdown):**
```
┌──────────────────────────┐
│                          │
│  Select API Key:         │
│                          │
│  ▼ [ Main Account    ]   │
│    fv_...c123             │
│    Not bound              │
│                          │
│    [ Alt Account     ]   │
│    fv_...f456             │
│    Bound: AltPlayer99    │
│                          │
│    [ Activate Key ]      │
│                          │
│  Need more keys?         │
│  flipvault.app/keys      │
│                          │
└──────────────────────────┘
```

If user has only 1 key, skip the dropdown and activate it automatically.

**When key is bound to a different player:**
```
┌──────────────────────────┐
│                          │
│  ⚠ Key Conflict          │
│                          │
│  This key is bound to    │
│  "OtherPlayer".          │
│                          │
│  Unbind it at:           │
│  flipvault.app/keys      │
│                          │
│  Or use a different key: │
│  [ Switch Key ]          │
│                          │
└──────────────────────────┘
```

**When suggestion available:**
```
┌──────────────────────────┐
│ Cannonball           BUY │  <- Item name + action (green=buy, red=sell)
│ 170 gp x 5,000          │  <- Price x quantity
│ Est. profit: +110K       │  <- Estimated profit (green)
│ Est. GP/hr: 1.2M         │  <- Estimated GP/hr
├─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤
│ Strong buy signal. 22gp  │  <- Reason text (muted color, smaller font)
│ margin with high volume. │
├──────────────────────────┤
│    [ Open FlipVault → ]  │  <- Link to web dashboard item page
└──────────────────────────┘
```

**When waiting:**
```
┌──────────────────────────┐
│                          │
│  Waiting for             │
│  opportunities...        │
│                          │
│  All slots occupied or   │
│  no strong signals.      │
│                          │
│  [ Refresh ]             │
└──────────────────────────┘
```

**When "COLLECT" state:**
```
┌──────────────────────────┐
│  Collect your offers:    │
│                          │
│  Slot 3: Cannonball ✓    │
│  Slot 5: Dragon bones ✓  │
│                          │
│  Collect, then I'll      │
│  suggest your next flip. │
└──────────────────────────┘
```

### 7.3 Active Tab

Shows all in-progress flips:

```
┌──────────────────────────┐
│ Sara godsword     +600K  │  <- Item name + unrealized P&L
│ SELLING x1  28.5M→29.1M │  <- Status, qty, bought→current
├──────────────────────────┤
│ Dragon knife      +166K  │
│ SELLING x500 10.2K→10.5K│
├──────────────────────────┤
│ Abyssal whip       -60K  │
│ BUYING x2   1.85M→1.82M │
├──────────────────────────┤
│ Unrealized P&L   +706.5K │  <- Total at bottom
└──────────────────────────┘
```

### 7.4 Stats Tab

Simple key-value pairs:

```
Total Profit     +12.4M
Flips Done       23
Win Rate         87%
Avg Margin       2.8%
GP/hr Avg        2.1M
Time Active      5h 42m
```

### 7.5 Swing Implementation Notes

- Use RuneLite's `ColorScheme` for dark theme colors (background: `ColorScheme.DARKER_GRAY_COLOR`)
- Use `BoxLayout(Y_AXIS)` for vertical stacking of panels
- Use `BorderLayout` for the main panel (header=NORTH, tabs+content=CENTER, footer=SOUTH)
- Fonts: RuneLite's `FontManager.getRunescapeSmallFont()` for OSRS feel, or just bold system font
- Panel width is fixed at 242px by the RuneLite sidebar
- Make all panels extend `JPanel` and implement a `void update(AccountState state)` method
- Colors: green `#38b000` for buy/profit, red `#ff6b6b` for sell/loss, muted gray `#8b8b8b` for secondary text

### 7.6 GE Widget Highlighting (HighlightController)

When FlipVault has an active suggestion and the GE interface is open, highlight the relevant GE widgets to guide the user's next action. This is a key UX feature that Copilot uses effectively -- it reduces friction between "seeing a suggestion" and "executing it."

#### How It Works

The HighlightController registers as a RuneLite `Overlay` and draws colored borders/backgrounds on GE interface widgets based on the current suggestion state.

```java
public class HighlightController extends Overlay {
    
    @Override
    public Dimension render(Graphics2D graphics) {
        if (currentSuggestion == null || !isGEOpen()) return null;
        
        switch (currentSuggestion.action) {
            case BUY:
                highlightEmptySlot(graphics);      // Highlight a free GE slot
                highlightBuyButton(graphics);       // Highlight the "Buy" button
                break;
            case SELL:
                highlightItemInInventory(graphics); // Highlight the item to sell
                highlightSellButton(graphics);      // Highlight the "Sell" button
                break;
            case COLLECT:
                highlightCompletedSlots(graphics);  // Highlight slots with completed offers
                break;
            case CANCEL:
                highlightSlotToCancel(graphics);    // Highlight the specific slot to cancel
                break;
        }
        return null;
    }
}
```

#### GE Widget IDs

The Grand Exchange interface uses Widget Group ID **465**. Key child widget IDs (these are stable across RuneLite versions):

```
GE Main Interface:     WidgetInfo.GRAND_EXCHANGE_WINDOW_CONTAINER
GE Offer Slots:        WidgetInfo.GRAND_EXCHANGE_OFFER_CONTAINER
Buy Button:            Widget group 465, child for "Buy" button
Sell Button:           Widget group 465, child for "Sell" button
Confirm Button:        Widget group 465, child for "Confirm" button
Item Search:           Widget group 162 (chatbox search)
Price Input:           Widget group 465, child for price entry
Quantity Input:        Widget group 465, child for quantity entry
```

**Note**: Exact child IDs should be verified against the current RuneLite widget inspector at implementation time. Widget IDs occasionally shift between game updates.

#### Highlight Styles

Keep it simple and non-intrusive for Phase 1:

```java
// Green border for "do this next" actions
private void highlightWidget(Graphics2D g, Widget widget, Color color) {
    if (widget == null || widget.isHidden()) return;
    Rectangle bounds = widget.getBounds();
    g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80));
    g.fill(bounds);
    g.setColor(color);
    g.setStroke(new BasicStroke(2));
    g.draw(bounds);
}

// Colors
Color HIGHLIGHT_BUY   = new Color(56, 176, 0, 180);   // #38b000 green
Color HIGHLIGHT_SELL  = new Color(255, 107, 107, 180); // #ff6b6b red
Color HIGHLIGHT_ACT   = new Color(34, 211, 238, 180);  // #22d3ee cyan (collect/confirm)
```

#### Highlight Flow by Suggestion Type

**BUY suggestion flow:**
1. GE opens -> highlight an empty slot with green border
2. User clicks slot -> highlight "Buy" button with green border
3. User clicks Buy -> suggestion item name appears in search (user types it)
4. User selects item -> highlight price input, show suggested price in sidebar
5. User sets price -> highlight quantity input, show suggested quantity in sidebar
6. User sets quantity -> highlight "Confirm" button with green border

**SELL suggestion flow:**
1. GE opens -> highlight the inventory item to sell with red border
2. User clicks item on slot -> highlight "Sell" button with red border
3. Item loaded -> highlight price input, show suggested price
4. User sets price -> highlight "Confirm" button

**COLLECT suggestion flow:**
1. GE opens -> highlight completed offer slots with cyan border

The highlighting guides the user's eye to the next action. For actually filling the values, see Section 7.7 Auto-Fill.

#### Registration

Register the overlay in the plugin's `startUp()`:

```java
@Override
protected void startUp() {
    overlayManager.add(highlightController);
    // ... rest of startup
}

@Override
protected void shutDown() {
    overlayManager.remove(highlightController);
    // ... rest of shutdown
}
```

#### GE Open Detection

Detect whether the GE interface is open using VarClientInt or widget visibility:

```java
private boolean isGEOpen() {
    Widget geWindow = client.getWidget(WidgetInfo.GRAND_EXCHANGE_WINDOW_CONTAINER);
    return geWindow != null && !geWindow.isHidden();
}
```

Subscribe to `WidgetLoaded` events (group 465) to know when the GE interface appears, and update highlight state accordingly.

### 7.7 Auto-Fill (Hotkey-Triggered)

When the user has an active suggestion and the GE offer setup screen is open, pressing a hotkey (default: `F5`) auto-fills the suggested price and quantity into the GE interface widgets. This eliminates the tedious manual entry while keeping the user in full control of when values are filled.

#### Why Hotkey Instead of Automatic

Auto-filling on offer screen open would be disruptive if the user opens a slot for a different purpose (checking an offer, placing a manual flip, etc.). Hotkey-triggered fill means the user explicitly opts in each time, which is safer and less annoying.

#### How It Works

```java
@Subscribe
public void onFocusChanged(FocusChanged event) {
    // Track if RuneLite window has focus (for hotkey detection)
}

// Register hotkey in FlipVaultConfig (see Section 8)
// Default: F5

public void onHotkeyPressed() {
    if (currentSuggestion == null) return;
    if (!isGEOfferSetupOpen()) return;

    clientThread.invokeLater(() -> {
        // 1. Set the price
        Widget priceWidget = getGEPriceInputWidget();
        if (priceWidget != null) {
            // Use client.runScript() to set the price input value
            // RuneLite's ScriptID.GE_OFFERS_SETUP has the relevant script hooks
            client.runScript(
                ScriptID.GE_OFFERS_SETUP_SET_PRICE,
                currentSuggestion.price
            );
        }

        // 2. Set the quantity
        Widget qtyWidget = getGEQuantityInputWidget();
        if (qtyWidget != null) {
            client.runScript(
                ScriptID.GE_OFFERS_SETUP_SET_QUANTITY,
                currentSuggestion.quantity
            );
        }
    });
}
```

**Important implementation note**: The exact mechanism for setting GE widget values depends on RuneLite's script hooks. The above is pseudocode illustrating the intent. At implementation time, verify the correct approach by examining:
- RuneLite's `GrandExchangePlugin` source for how it interacts with GE widgets
- Copilot's approach (they use widget text manipulation, not script invocation)
- The RuneLite developer Discord for current best practices

The two viable approaches are:
1. **Client script invocation** via `client.runScript()` -- cleaner, mimics actual game input
2. **Widget text manipulation** via `widget.setText()` + triggering the appropriate VarClient update -- more direct but can be fragile

Either way, this must run on the `ClientThread` via `clientThread.invokeLater()`.

#### What Gets Filled

When the hotkey is pressed and conditions are met:

| Suggestion Action | Price Filled | Quantity Filled | Notes |
|---|---|---|---|
| BUY | Suggested buy price | Suggested quantity | User still needs to search/select the item |
| SELL | Suggested sell price | Suggested quantity (or "All" if selling full stack) | User still needs to click the item from inventory |

The hotkey does NOT:
- Open GE slots for you
- Search for or select items
- Click the Confirm button

The user always performs item selection and final confirmation manually. This keeps the interaction feeling natural and avoids any concerns about botting/automation.

#### Visual Feedback

When auto-fill executes successfully, provide brief visual feedback:
- Flash the price/quantity highlight from the normal color to white and back (200ms)
- Show a small "Filled!" text in the sidebar suggestion panel that fades after 2 seconds
- If auto-fill fails (wrong screen, no suggestion), show "Can't fill -- open a GE offer first" in the sidebar

#### Edge Cases

- **No active suggestion**: hotkey does nothing silently
- **GE not open**: hotkey does nothing silently
- **GE open but not on offer setup screen** (e.g. viewing offer list): hotkey does nothing silently
- **Suggestion is COLLECT or CANCEL**: hotkey does nothing (these actions don't have price/qty)
- **Suggestion is WAIT**: hotkey does nothing
- **User already typed a different price**: hotkey overwrites it (user pressed it intentionally)
- **Item not yet selected in offer**: fill price/qty anyway, they'll apply once the item is selected

#### Anti-Cheat Considerations

Auto-filling GE price/quantity via RuneLite plugin is an established pattern. RuneLite's own GE features and many popular plugins (including Flipping Copilot) do this. The key rules:
- Never simulate mouse clicks or keyboard input at the OS level
- Only manipulate widget state through RuneLite's sanctioned API
- Never automatically complete an offer without user confirmation
- The user must always click "Confirm" themselves

---

## 8. Configuration (FlipVaultConfig)

The RuneLite config stores ONLY the API key. Email/password are never persisted by the plugin. They are used once during the login flow and discarded immediately.

```java
@ConfigGroup("flipvault")
public interface FlipVaultConfig extends Config {

    @ConfigItem(
        keyName = "apiKey",
        name = "API Key",
        description = "Your FlipVault API key. Use the login button in the plugin panel to set up.",
        secret = true
    )
    default String apiKey() { return ""; }

    @ConfigItem(
        keyName = "keyLabel",
        name = "Key Label",
        description = "Display label for the active API key"
    )
    default String keyLabel() { return ""; }

    @ConfigItem(
        keyName = "boundTo",
        name = "Bound To",
        description = "OSRS player name this key is bound to"
    )
    default String boundTo() { return ""; }

    @ConfigItem(
        keyName = "autoFillHotkey",
        name = "Auto-Fill Hotkey",
        description = "Hotkey to fill suggested price and quantity into GE offer (default: F5)"
    )
    default Keybind autoFillHotkey() { return new Keybind(KeyEvent.VK_F5, 0); }
}
```

**Important**: The `apiKey` field uses `secret = true` so RuneLite encrypts it in the config file. Users should NOT need to manually edit these fields. The plugin panel's login flow writes them automatically via `configManager.setConfiguration()`.

### 8.1 Auth State Machine

The plugin tracks auth state internally:

```
NO_KEY       -> User has no API key stored. Show login panel.
VALIDATING   -> Startup: validating stored key with backend.
VALID        -> Key is valid and bound to current player. Normal operation.
KEY_CONFLICT -> Key is bound to a different player. Show conflict panel.
EXPIRED      -> Key expired or subscription lapsed. Show re-login panel.
LOGGING_IN   -> Login request in flight. Show spinner.
SELECTING_KEY -> Login succeeded, user picking from key dropdown.
ERROR        -> Network error during auth. Show retry button.
```

Transitions:
```
Plugin launch with stored key   -> VALIDATING -> VALID (or KEY_CONFLICT or EXPIRED)
Plugin launch without stored key -> NO_KEY
Login button clicked             -> LOGGING_IN -> SELECTING_KEY (or ERROR)
Key selected + activated         -> VALIDATING -> VALID
Any 401 from any endpoint        -> EXPIRED (clear stored key)
```

---

## 9. Data Persistence

### 9.1 File Location

Store plugin data in: `~/.runelite/flipvault/`

```
~/.runelite/flipvault/
  ├── offers-{playerName}.json     # Last known GE offer states
  ├── session-{playerName}.json    # Current session stats
  └── flips-{playerName}.jsonl     # Flip history (append-only)
```

### 9.2 Save/Load Timing

- **Save on**: client shutdown, account logout, world hop, every 5 minutes
- **Load on**: account login (match playerName to file)
- Use `Gson` for serialization (available via RuneLite)

---

## 10. Error Handling

### 10.1 Network Errors

- API request fails: show "Connection error" in panel, retry after 10 seconds
- API returns 401: clear stored API key, transition to EXPIRED auth state, show re-login panel
- API returns 403: show "Subscription expired. Renew at flipvault.app/pricing"
- API returns 409 (key conflict): show key conflict panel with bound player name
- API returns 500: show "Server error", retry after 30 seconds
- Timeout (10 seconds): cancel request, show "Timeout", retry after 5 seconds

### 10.2 Game State Errors

- Player not logged in: show "Log in to start" in panel
- GE not available (F2P on members item): backend handles this via `isMembers` field
- All slots full: show "All GE slots in use" + show active flips tab

### 10.3 Thread Safety

- All manager state updates use `synchronized` blocks
- Never hold locks while making API calls (deadlock risk)
- Copy state into immutable snapshots before passing between threads

---

## 11. Build Configuration

### 11.1 build.gradle

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
    compileOnly 'org.projectlombok:lombok:1.18.30'
    annotationProcessor 'org.projectlombok:lombok:1.18.30'
    implementation 'com.google.code.gson:gson:2.10.1'
}

group = 'com.flipvault'
version = '0.1.0'
sourceCompatibility = JavaVersion.VERSION_11
```

### 11.2 runelite-plugin.properties

```properties
displayName=FlipVault
author=FlipVault
description=AI-powered GE flipping assistant with 24 trading signals
tags=grand,exchange,flip,flipping,ge,trade,money,trading,flipvault
```

### 11.3 Plugin Descriptor

```java
@PluginDescriptor(
    name = "FlipVault",
    description = "AI-powered GE flipping assistant",
    tags = {"grand", "exchange", "flip", "trading", "flipvault"}
)
public class FlipVaultPlugin extends Plugin {
    // ...
}
```

---

## 12. Implementation Order

Build and test in this exact order. Each step should be independently testable.

### Step 1: Skeleton + Config + Auth Flow
- FlipVaultPlugin with startUp/shutDown
- FlipVaultConfig with apiKey (secret), keyLabel, boundTo
- AuthState enum and AuthController
- ApiClient with login(), activateKey(), validateKey() methods
- LoginPanel with email/password fields and login button
- KeySelectionPanel with dropdown and activate button
- On launch: check for stored key -> validate -> show login if missing
- Test: plugin loads, login flow works, key gets stored and validated

### Step 2: Game State Reading
- AccountState, GESlotState models
- Read GE slots, cash stack, player name on ClientThread
- 1-second tick that snapshots state
- Test: log game state to RuneLite logger, verify accuracy

### Step 3: UI Shell
- FlipVaultPanel with 3 tabs (Suggest/Active/Stats)
- Header with "FlipVault" + "PREMIUM" badge
- Session stats bar (hardcoded for now)
- Footer with connection status
- Auth-aware: show LoginPanel when NO_KEY, show tabs when VALID
- Test: panel renders correctly in sidebar, auth states transition properly

### Step 4: Suggestion Flow + GE Highlighting + Auto-Fill
- SuggestionController.onStateChanged()
- ApiClient.requestSuggestion(AccountState) with X-API-Key header
- SuggestionPanel updates with response
- Handle WAIT, COLLECT, BUY, SELL states
- HighlightController registered as Overlay
- GE open detection via WidgetLoaded (group 465)
- Highlight empty slots (BUY), inventory items (SELL), completed slots (COLLECT)
- Step-by-step highlight flow: slot -> button -> price -> quantity -> confirm
- Auto-fill hotkey (F5) fills suggested price/qty into GE offer setup widgets
- Visual feedback on fill (flash highlight + "Filled!" text in sidebar)
- Test: suggestion appears when GE slot is free, GE widgets highlight correctly, F5 fills price/qty

### Step 5: Transaction Inference
- GEOfferHandler state machine
- FlipManager tracking pending buys -> completed sells
- SessionManager profit/flip counting
- Active flips panel shows real data
- Test: complete a flip, see profit update

### Step 6: Persistence + Polish
- Save/load offer states across sessions
- Heartbeat every 60 seconds with X-API-Key header
- Transaction reporting to backend
- Error handling for all network states (401 clears key, 409 shows conflict)
- Stats panel with real data

---

## 13. Backend Endpoints to Build

> **NOTE:** I (Jay) will personally build and address all backend API endpoints described in this section. Do NOT implement any backend code. Focus exclusively on the Java plugin. Use mock responses or hardcoded test data when you need to test API interactions before the backend is ready.

The FlipVault backend (Node.js) needs these new endpoints. They will be added to the existing API, served through `https://api.flipvault.app/api/plugin/`:

### /api/plugin/suggest (NEW -- most important)

This is the brain. It receives game state and returns ONE suggestion.

**Logic:**
1. Receive AccountState from plugin
2. Determine which items the player can afford (cashStack)
3. Filter items by membership status (isMembers)
4. Check buy limits (don't suggest items they're already buying)
5. Look at active offers to avoid suggesting conflicting items
6. Query `item_timing_cache` for top-scored items
7. Pick the best item the player can actually act on right now
8. Calculate appropriate quantity based on cash, buy limit, and GE slot availability
9. Calculate price (use latest from `price_snapshots`)
10. Return single suggestion with action, item, price, qty, reason

**Key intelligence**: The backend should account for what the player is ALREADY flipping. If they have 3 active flips, suggest an item that doesn't conflict. If they're buying Cannonballs already, don't suggest buying more Cannonballs. If they just sold Dragon bones and have cash, suggest a new buy.

Until the ML model is ready, use the existing `item_timing_cache` composite scores to rank items. The ML model will eventually replace this ranking, but the API contract stays the same.

---

## 14. What This Spec Does NOT Cover (Future Phases)

- Score circles, signal badges, signal pills (Phase 2 UI)
- Bot dump detection alerts (Phase 2 feature)
- Price graph in sidebar (Phase 2)
- ML model integration (separate spec, March target)
- Plugin Hub submission process (post-beta)
- Multiple suggestion queue (Phase 2)

---

## 15. Reference: How Copilot Does It

For context, here is how Flipping Copilot implements the same flow (from DeepWiki analysis of their codebase at commit 375a7ec7):

**Their plugin lifecycle:**
- `FlippingCopilotPlugin` extends `Plugin`, uses `@PluginDescriptor`
- Guice-injected: `Client`, `ClientThread`, `ConfigManager`, `OverlayManager`
- Custom `ExecutorService` with 2 threads for background API tasks
- `ScheduledExecutorService` for 1-second ticks

**Their game state aggregation:**
- `AccountStatus` class aggregates: inventory, GE offers, bank, cash, preferences
- Sent to backend via `ApiRequestHandler` using MessagePack binary serialization
- Sent on every significant game state change

**Their suggestion display:**
- `SuggestionManager` processes backend responses
- `SuggestionPanel` renders ONE suggestion with action + price + quantity
- Colored action indicators (blue = buy, red = sell)
- Widget highlighting via `HighlightController` to guide clicks

**Their transaction inference:**
- `GrandExchangeOfferEventHandler` monitors all GE slot changes
- `TransactionManager` infers completed buys/sells from state transitions
- Detected transactions uploaded to backend for profit tracking

**Their persistence:**
- JSONL format for local storage
- `Persistance` utility class handles file I/O
- Per-account files in `~/.runelite/flipping-copilot/`

**Their auth:**
- JWT-based via Basic Auth
- Token stored in RuneLite config (encrypted)
- Auto-refresh on 401

**Their key difference from us:**
- Their backend is a black box ML model -- no transparency
- Our backend uses 24 named signals with a composite scoring system
- Our suggestions include a human-readable reason explaining WHY
- We provide a full web dashboard for deeper analysis
- We will eventually show signal pills and confidence scores (Phase 2)
