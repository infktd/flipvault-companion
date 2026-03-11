package com.flipvault.plugin;

import com.flipvault.plugin.controller.*;
import com.flipvault.plugin.manager.*;
import com.flipvault.plugin.model.*;
import com.flipvault.plugin.ui.*;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.concurrent.*;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.game.ItemManager;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.ImageUtil;
import java.awt.event.KeyEvent;

@Slf4j
@PluginDescriptor(
    name = "FlipVault",
    description = "AI-powered GE flipping assistant",
    tags = {"grand", "exchange", "flip", "trading", "flipvault"}
)
public class FlipVaultPlugin extends Plugin implements KeyListener {

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar clientToolbar;
    @Inject private FlipVaultConfig config;
    @Inject private ConfigManager configManager;
    @Inject private OverlayManager overlayManager;
    @Inject private KeyManager keyManager;
    @Inject private HighlightController highlightController;
    @Inject private ItemManager itemManager;
    @Inject private Notifier notifier;

    // Use RuneLite's injected executor for the scheduled tick
    @Inject private ScheduledExecutorService scheduledExecutor;

    // Our custom executor for API calls
    private ExecutorService flipvaultExecutor;

    // Navigation
    private NavigationButton navButton;

    // Managers
    private SessionManager sessionManager;
    private FlipManager flipManager;
    private OfferManager offerManager;
    private FlipLogger flipLogger;

    // Controllers
    private ApiClient apiClient;
    private AuthController authController;
    private SuggestionController suggestionController;
    private GEOfferHandler geOfferHandler;

    // UI
    private FlipVaultPanel panel;

    // State
    private AccountState lastAccountState;
    private ScheduledFuture<?> saveFuture;
    private ScheduledFuture<?> heartbeatFuture;
    private boolean loggedIn;
    private String sessionStartedIso;
    private long lastPeriodicSaveTime;
    private volatile boolean forceNextSuggest;
    private volatile Suggestion previousNotifiedSuggestion;

    // Login burst protection (like Flipping Copilot)
    private static final int LOGIN_BURST_TICKS = 2;
    private int lastLoginTick = -100;

    @Provides
    FlipVaultConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(FlipVaultConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("FlipVault plugin starting up");

        // 1. Create custom executor (2 daemon threads for API calls)
        flipvaultExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "flipvault");
            t.setDaemon(true);
            return t;
        });

        // 2. Instantiate managers
        sessionManager = new SessionManager();
        flipManager = new FlipManager();
        offerManager = new OfferManager();
        flipLogger = new FlipLogger();

        // 3. Instantiate controllers
        apiClient = new ApiClient();
        authController = new AuthController(apiClient, config, configManager, flipvaultExecutor);
        suggestionController = new SuggestionController(apiClient, authController, flipManager, flipvaultExecutor, scheduledExecutor);
        geOfferHandler = new GEOfferHandler(offerManager, flipManager, sessionManager, flipLogger, apiClient, authController, flipvaultExecutor);

        // Wire GE offer state changes to flag a suggestion refresh.
        // Only sets the flag — the 1-second tick picks it up with settled state.
        // Calling snapshotGameState directly from the event reads transitional
        // GE data (e.g. price=1 during margin checks) that the server rejects.
        geOfferHandler.setOnStateChangedCallback(() -> {
            if (loggedIn) {
                forceNextSuggest = true;
            }
        });

        // 4. Create sub-panels and main UI panel
        LoginPanel loginPanel = new LoginPanel(authController);
        KeySelectionPanel keySelectionPanel = new KeySelectionPanel(authController);
        SuggestionPanel suggestionPanel = new SuggestionPanel();
        ActiveFlipsPanel activeFlipsPanel = new ActiveFlipsPanel();
        StatsPanel statsPanel = new StatsPanel();

        panel = new FlipVaultPanel(authController, loginPanel, keySelectionPanel,
            suggestionPanel, activeFlipsPanel, statsPanel);

        // Wire suggestion updates to the panel — also load item icons
        suggestionController.setListener(new SuggestionController.SuggestionListener() {
            @Override
            public void onSuggestionUpdated(Suggestion suggestion) {
                suggestionPanel.onSuggestionUpdated(suggestion);
                handleSuggestionNotifications(suggestion);
                if (suggestion != null && suggestion.getItemId() > 0
                        && ("BUY".equals(suggestion.getAction()) || "SELL".equals(suggestion.getAction()))) {
                    itemManager.getImage(suggestion.getItemId()).addTo(suggestionPanel.getItemIconLabel());
                } else {
                    suggestionPanel.clearItemImage();
                }
            }

            @Override
            public void onSuggestionError(String error) {
                suggestionPanel.onSuggestionError(error);
            }

            @Override
            public void onSuggestionLoading() {
                suggestionPanel.onSuggestionLoading();
            }
        });

        // Wire auth state changes to panel
        authController.addListener(panel);
        // Propagate player name to key selection on auth state changes
        authController.addListener(state -> {
            if ((state == AuthState.VALID || state == AuthState.SELECTING_KEY) && loggedIn) {
                String playerName = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName() : null;
                if (playerName != null) {
                    panel.getKeySelectionPanel().setPlayerName(playerName);
                    authController.setPendingPlayerName(playerName);
                }
            }
        });

        // 5. Register sidebar navigation
        final BufferedImage icon = loadIcon();
        navButton = NavigationButton.builder()
            .tooltip("FlipVault")
            .icon(icon)
            .panel(panel)
            .priority(5)
            .build();
        clientToolbar.addNavigation(navButton);

        // 6. Register overlay + wire auto-fill feedback
        overlayManager.add(highlightController);
        highlightController.setOnAutoFillSuccess(() ->
            panel.getSuggestionPanel().showAutoFillFeedback(true));
        highlightController.setOnAutoFillFailure(() ->
            panel.getSuggestionPanel().showAutoFillFeedback(false));

        // 7. Register hotkey listener
        keyManager.registerKeyListener(this);

        // 8. Check auth on startup
        authController.checkStoredKey();

        // 9. Detect player name if already logged in, and validate key
        if (client.getGameState() == GameState.LOGGED_IN) {
            loggedIn = true;
            clientThread.invokeLater(() -> {
                Player localPlayer = client.getLocalPlayer();
                if (localPlayer != null && localPlayer.getName() != null) {
                    String playerName = localPlayer.getName();
                    panel.getKeySelectionPanel().setPlayerName(playerName);
                    authController.setPendingPlayerName(playerName);
                    offerManager.load(playerName);
                    sessionManager.load(playerName);
                    flipLogger.setPlayerName(playerName);

                    if (authController.getState() == AuthState.VALIDATING) {
                        authController.validateKey(playerName);
                    }
                }
            });
        }

        // 10. Schedule periodic save + auth retry (every 30 seconds)
        saveFuture = scheduledExecutor.scheduleAtFixedRate(
            this::periodicTick, 10, 30, TimeUnit.SECONDS);

        // 11. Schedule heartbeat (60 seconds)
        heartbeatFuture = scheduledExecutor.scheduleAtFixedRate(
            this::heartbeat, 60, 60, TimeUnit.SECONDS);

        log.info("FlipVault plugin started");
    }

    @Override
    protected void shutDown() {
        log.info("FlipVault plugin shutting down");

        // Cancel scheduled tasks
        if (saveFuture != null) {
            saveFuture.cancel(true);
        }
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
        }

        // Shutdown executor
        if (flipvaultExecutor != null) {
            flipvaultExecutor.shutdown();
            try {
                if (!flipvaultExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    flipvaultExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                flipvaultExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Save state
        if (lastAccountState != null && lastAccountState.getPlayerName() != null) {
            String playerName = lastAccountState.getPlayerName();
            offerManager.save(playerName);
            sessionManager.save(playerName);
        }

        // Send Discord webhook if configured
        String webhookUrl = config.discordWebhookUrl();
        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            String playerName = config.boundTo();
            if (playerName.isEmpty() && lastAccountState != null) {
                playerName = lastAccountState.getPlayerName();
            }
            new WebhookController().sendSessionSummary(webhookUrl, playerName, sessionManager.getStats());
        }

        // Unregister
        overlayManager.remove(highlightController);
        keyManager.unregisterKeyListener(this);
        clientToolbar.removeNavigation(navButton);

        log.info("FlipVault plugin stopped");
    }

    // ---- Game tick (0.6s, client thread) — drives suggestion requests ----

    @Subscribe
    public void onGameTick(GameTick event) {
        if (!loggedIn) {
            return;
        }

        // Skip during login burst — GE slots fire rapid EMPTY→real-state events
        if (client.getTickCount() <= lastLoginTick + LOGIN_BURST_TICKS) {
            return;
        }

        if (authController.getState() != AuthState.VALID) {
            return;
        }

        // Already on client thread — read fresh state directly
        snapshotGameState();
    }

    // ---- Periodic tick (30s, scheduled executor) — saves + auth retry ----

    private static final long PERIODIC_SAVE_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes
    private static final long SUGGESTION_REFRESH_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes
    private long lastSuggestionRefreshTime;

    private void periodicTick() {
        if (!loggedIn) {
            return;
        }

        // Retry validation if still waiting for player name
        if (authController.getState() == AuthState.VALIDATING) {
            clientThread.invokeLater(() -> {
                Player p = client.getLocalPlayer();
                if (p != null && p.getName() != null) {
                    authController.setPendingPlayerName(p.getName());
                    authController.validateKey(p.getName());
                }
            });
            return;
        }

        if (authController.getState() != AuthState.VALID) {
            return;
        }

        long now = System.currentTimeMillis();

        // Periodic suggestion refresh every 5 minutes (picks up fresh ML predictions)
        if (now - lastSuggestionRefreshTime >= SUGGESTION_REFRESH_INTERVAL_MS) {
            lastSuggestionRefreshTime = now;
            if (lastAccountState != null) {
                log.debug("Periodic suggestion refresh");
                clientThread.invokeLater(this::snapshotGameState);
                forceNextSuggest = true;
            }
        }

        // Periodic save every 5 minutes
        if (now - lastPeriodicSaveTime >= PERIODIC_SAVE_INTERVAL_MS) {
            lastPeriodicSaveTime = now;
            if (lastAccountState != null && lastAccountState.getPlayerName() != null) {
                String playerName = lastAccountState.getPlayerName();
                flipvaultExecutor.submit(() -> {
                    offerManager.save(playerName);
                    sessionManager.save(playerName);
                    log.debug("Periodic save completed for {}", playerName);
                });
            }
        }
    }

    private void snapshotGameState() {
        // Must be called on ClientThread
        try {
            Player localPlayer = client.getLocalPlayer();
            if (localPlayer == null) return;

            String playerName = localPlayer.getName();
            if (playerName == null) return;

            // Read cash stack from inventory
            long cashStack = 0;
            ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
            if (inventory != null) {
                for (Item item : inventory.getItems()) {
                    if (item.getId() == ItemID.COINS_995) {
                        cashStack = item.getQuantity();
                    }
                }
            }

            // Read GE slots
            GESlotState[] geSlots = new GESlotState[8];
            int freeSlots = 0;
            GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
            for (int i = 0; i < 8; i++) {
                if (offers == null || offers[i] == null
                        || offers[i].getState() == GrandExchangeOfferState.EMPTY
                        || offers[i].getState() == GrandExchangeOfferState.CANCELLED_BUY
                        || offers[i].getState() == GrandExchangeOfferState.CANCELLED_SELL) {
                    geSlots[i] = new GESlotState(i, SlotStatus.EMPTY);
                    freeSlots++;
                } else {
                    GrandExchangeOffer offer = offers[i];
                    geSlots[i] = new GESlotState(
                        i,
                        mapOfferState(offer.getState()),
                        offer.getItemId(),
                        offer.getPrice(),
                        offer.getTotalQuantity(),
                        offer.getQuantitySold(),
                        offer.getSpent()
                    );
                    // Carry over suggestion metadata from offerManager's tracked state
                    GESlotState tracked = offerManager.getPreviousState(i);
                    if (tracked != null && tracked.getItemId() == offer.getItemId()) {
                        geSlots[i].setWasFlipVaultSuggestion(tracked.isWasFlipVaultSuggestion());
                        geSlots[i].setFlipVaultPriceUsed(tracked.isFlipVaultPriceUsed());
                    }
                }
            }

            boolean isMembers = client.getWorldType().contains(WorldType.MEMBERS);

            AccountState newState = AccountState.builder()
                .playerName(playerName)
                .cashStack(cashStack)
                .isMembers(isMembers)
                .geSlots(geSlots)
                .freeSlots(freeSlots)
                .totalWealth(cashStack) // Simplified for Phase 1
                .timestamp(System.currentTimeMillis())
                .build();

            // Check if state changed, forced, or suggestion needed (e.g. 400 recovery)
            boolean changed = offerManager.hasStateChanged(geSlots);
            boolean forced = forceNextSuggest;
            boolean needed = suggestionController.isSuggestionNeeded();
            if (changed || forced || needed) {
                forceNextSuggest = false;
                suggestionController.setSuggestionNeeded(false);
                // Update all slot states in offer manager
                for (int i = 0; i < 8; i++) {
                    offerManager.updateSlot(i, geSlots[i]);
                }
                suggestionController.onStateChanged(newState);

                // Resolve item names for active GE slots (on client thread)
                final java.util.Map<Integer, String> itemNameMap = new java.util.HashMap<>();
                for (GESlotState slot : geSlots) {
                    if (slot.getStatus() != SlotStatus.EMPTY && slot.getStatus() != SlotStatus.CANCELLED
                            && slot.getItemId() > 0 && !itemNameMap.containsKey(slot.getItemId())) {
                        itemNameMap.put(slot.getItemId(), client.getItemDefinition(slot.getItemId()).getName());
                    }
                }
                final GESlotState[] slotsCopy = geSlots.clone();

                // Update UI
                SwingUtilities.invokeLater(() -> {
                    panel.updateSessionStats(sessionManager.getStats());
                    panel.getActiveFlipsPanel().update(slotsCopy, itemNameMap);
                    panel.getStatsPanel().update(sessionManager.getStats());
                });
            }

            lastAccountState = newState;

            // Update highlight controller with latest suggestion
            highlightController.setCurrentSuggestion(suggestionController.getCurrentSuggestion());

        } catch (Exception e) {
            log.error("Error snapshotting game state", e);
        }
    }

    private SlotStatus mapOfferState(GrandExchangeOfferState state) {
        switch (state) {
            case BUYING: return SlotStatus.BUYING;
            case BOUGHT: return SlotStatus.BUY_COMPLETE;
            case SELLING: return SlotStatus.SELLING;
            case SOLD: return SlotStatus.SELL_COMPLETE;
            case CANCELLED_BUY:
            case CANCELLED_SELL: return SlotStatus.CANCELLED;
            case EMPTY:
            default: return SlotStatus.EMPTY;
        }
    }

    // ---- Heartbeat (60 seconds) ----

    private void heartbeat() {
        if (!loggedIn || authController.getState() != AuthState.VALID) {
            return;
        }
        flipvaultExecutor.submit(() -> {
            try {
                String playerName = lastAccountState != null ? lastAccountState.getPlayerName() : "";
                int world = client.getWorld();
                apiClient.heartbeat(playerName, world,
                    sessionStartedIso != null ? sessionStartedIso : Instant.now().toString());
            } catch (ApiException e) {
                log.warn("Heartbeat failed: {}", e.getMessage());
                if (e.getStatusCode() == 401) {
                    authController.onUnauthorized();
                }
            }
        });
    }

    // ---- Event Subscriptions ----

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState gameState = event.getGameState();
        switch (gameState) {
            case LOGGED_IN:
                loggedIn = true;
                lastLoginTick = client.getTickCount();
                sessionStartedIso = Instant.now().toString();
                lastPeriodicSaveTime = System.currentTimeMillis();
                sessionManager.startSession();

                // Load persisted state and validate key
                clientThread.invokeLater(() -> {
                    Player localPlayer = client.getLocalPlayer();
                    if (localPlayer != null && localPlayer.getName() != null) {
                        String playerName = localPlayer.getName();
                        offerManager.load(playerName);
                        sessionManager.load(playerName);
                        flipLogger.setPlayerName(playerName);
                        panel.getKeySelectionPanel().setPlayerName(playerName);
                        authController.setPendingPlayerName(playerName);

                        // Validate stored key if we have one
                        if (authController.getState() == AuthState.VALIDATING) {
                            authController.validateKey(playerName);
                        }
                    }
                });

                SwingUtilities.invokeLater(() -> panel.setConnected(true));
                break;

            case LOGIN_SCREEN:
                if (loggedIn) {
                    // Save state on logout
                    if (lastAccountState != null && lastAccountState.getPlayerName() != null) {
                        String pn = lastAccountState.getPlayerName();
                        offerManager.save(pn);
                        sessionManager.save(pn);
                    }
                    loggedIn = false;
                    previousNotifiedSuggestion = null;
                    suggestionController.clearSuggestion();
                    highlightController.setCurrentSuggestion(null);
                }
                SwingUtilities.invokeLater(() -> panel.setConnected(false));
                break;

            case HOPPING:
                lastLoginTick = client.getTickCount();
                // Save state during world hop
                if (lastAccountState != null && lastAccountState.getPlayerName() != null) {
                    String hopName = lastAccountState.getPlayerName();
                    offerManager.save(hopName);
                    sessionManager.save(hopName);
                }
                break;

            default:
                break;
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        geOfferHandler.onOfferChanged(event);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() == InventoryID.INVENTORY.getId()) {
            // Inventory changed - the tick will pick up the new state
        }
    }

    @Subscribe
    public void onVarClientIntChanged(VarClientIntChanged event) {
        if (event.getIndex() == VarClientInt.INPUT_TYPE) {
            int inputType = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
            if (inputType == 14 && client.getWidget(162, 51) != null) {
                // GE item search opened and search results widget exists — inject suggested item
                clientThread.invokeLater(() -> highlightController.showSuggestedItemInSearch());
            }
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() == 465) {
            // GE interface opened - force a fresh snapshot + suggest
            if (loggedIn && authController.getState() == AuthState.VALID) {
                forceNextSuggest = true;
                clientThread.invokeLater(this::snapshotGameState);
            }
        }
    }

    // ---- Suggestion metadata: capture Confirm click ----

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!"Confirm".equals(event.getMenuOption())) {
            return;
        }
        // Only capture when GE offer setup is open
        Widget offerContainer = client.getWidget(465, 26);
        if (offerContainer == null || offerContainer.isHidden()) {
            return;
        }

        Suggestion suggestion = suggestionController.getCurrentSuggestion();
        if (suggestion != null && suggestion.getItemId() > 0
                && ("BUY".equals(suggestion.getAction()) || "SELL".equals(suggestion.getAction()))) {
            geOfferHandler.setConfirmedSuggestion(
                suggestion.getItemId(),
                suggestion.getAction(),
                suggestion.getPrice()
            );
            log.debug("Confirmed suggestion snapshot: item={} action={} price={}",
                suggestion.getItemId(), suggestion.getAction(), suggestion.getPrice());
        } else {
            geOfferHandler.clearConfirmedSuggestion();
        }
    }

    // ---- KeyListener for Auto-Fill Hotkey ----

    @Override
    public void keyPressed(KeyEvent e) {
        if (config.autoFillHotkey().matches(e)) {
            // Auto-fill on ClientThread
            clientThread.invokeLater(() -> highlightController.autoFill());
            e.consume();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // Not used
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // Not used
    }

    // ---- Notifications ----

    private void handleSuggestionNotifications(Suggestion suggestion) {
        if (suggestion == null || "WAIT".equals(suggestion.getAction())) {
            return;
        }

        // Skip if same suggestion already notified
        Suggestion prev = previousNotifiedSuggestion;
        if (prev != null
                && prev.getItemId() == suggestion.getItemId()
                && prev.getAction().equals(suggestion.getAction())) {
            return;
        }
        previousNotifiedSuggestion = suggestion;

        // Build message
        String msg;
        String action = suggestion.getAction();
        if ("BUY".equals(action) || "SELL".equals(action)) {
            msg = String.format("[FlipVault] %s %s x%d @ %,d gp",
                action, suggestion.getItemName(), suggestion.getQuantity(), suggestion.getPrice());
        } else {
            msg = String.format("[FlipVault] %s %s",
                action, suggestion.getItemName() != null ? suggestion.getItemName() : "");
        }

        // Chat notification — only if panel is not the active sidebar tab
        if (config.chatNotifications() && !panel.isCurrentlyActive()) {
            String chatMsg = new ChatMessageBuilder()
                .append(config.chatTextColor(), msg)
                .build();
            clientThread.invokeLater(() ->
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", chatMsg, ""));
        }

        // Tray notification — RuneLite handles focus-check internally
        if (config.trayNotifications()) {
            notifier.notify(msg);
        }
    }

    // ---- Mis-click Prevention ----

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (!config.misClickPrevention()) {
            return;
        }

        Suggestion suggestion = suggestionController.getCurrentSuggestion();
        if (suggestion == null) {
            return;
        }

        String action = suggestion.getAction();
        if (!"BUY".equals(action) && !"SELL".equals(action)) {
            return;
        }

        // Only act when GE offer setup is open
        Widget offerContainer = client.getWidget(465, 26);
        if (offerContainer == null || offerContainer.isHidden()) {
            return;
        }

        if (!"Confirm".equals(event.getOption())) {
            return;
        }

        // Check if offer details match the suggestion
        int offerPrice = client.getVarbitValue(4398);
        int offerQty = client.getVarbitValue(4396);
        if (offerPrice != suggestion.getPrice() || offerQty != suggestion.getQuantity()) {
            event.getMenuEntry().setDeprioritized(true);
        }
    }

    // ---- Helpers ----

    private BufferedImage loadIcon() {
        try {
            return ImageUtil.loadImageResource(getClass(), "icon.png");
        } catch (Exception e) {
            log.warn("Could not load plugin icon, using fallback");
            // Return a green rectangle with "FV" text as fallback
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Green background with rounded corners
            g.setColor(new java.awt.Color(56, 176, 0));
            g.fillRoundRect(0, 0, 16, 16, 4, 4);
            // White "FV" text
            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 10));
            java.awt.FontMetrics fm = g.getFontMetrics();
            String text = "FV";
            int textWidth = fm.stringWidth(text);
            int x = (16 - textWidth) / 2;
            int y = (16 + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(text, x, y);
            g.dispose();
            return img;
        }
    }
}
