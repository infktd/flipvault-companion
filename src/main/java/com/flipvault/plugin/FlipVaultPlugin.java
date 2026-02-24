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
import net.runelite.client.callback.ClientThread;
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
    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> heartbeatFuture;
    private boolean loggedIn;
    private String sessionStartedIso;
    private long lastPeriodicSaveTime;
    private volatile boolean forceNextSuggest;

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

        // Wire GE offer state changes to trigger a fresh snapshot (not stale lastAccountState)
        geOfferHandler.setOnStateChangedCallback(() -> {
            if (loggedIn) {
                clientThread.invokeLater(this::snapshotGameState);
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

        // 10. Schedule 1-second tick
        tickFuture = scheduledExecutor.scheduleAtFixedRate(
            this::tick, 1, 1, TimeUnit.SECONDS);

        // 11. Schedule heartbeat (60 seconds)
        heartbeatFuture = scheduledExecutor.scheduleAtFixedRate(
            this::heartbeat, 60, 60, TimeUnit.SECONDS);

        log.info("FlipVault plugin started");
    }

    @Override
    protected void shutDown() {
        log.info("FlipVault plugin shutting down");

        // Cancel scheduled tasks
        if (tickFuture != null) {
            tickFuture.cancel(true);
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

        // Unregister
        overlayManager.remove(highlightController);
        keyManager.unregisterKeyListener(this);
        clientToolbar.removeNavigation(navButton);

        log.info("FlipVault plugin stopped");
    }

    // ---- Tick (1 second) ----

    private static final long PERIODIC_SAVE_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes

    private void tick() {
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

        // Periodic save every 5 minutes
        long now = System.currentTimeMillis();
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

        clientThread.invokeLater(this::snapshotGameState);
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

            // Check if state changed or a forced suggest was requested
            boolean changed = offerManager.hasStateChanged(geSlots);
            boolean forced = forceNextSuggest;
            if (changed || forced) {
                forceNextSuggest = false;
                // Update all slot states in offer manager
                for (int i = 0; i < 8; i++) {
                    offerManager.updateSlot(i, geSlots[i]);
                }
                suggestionController.onStateChanged(newState);

                // Update UI
                SwingUtilities.invokeLater(() -> {
                    panel.updateSessionStats(sessionManager.getStats());
                    panel.getActiveFlipsPanel().update(flipManager.getActiveFlips());
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
                    suggestionController.clearSuggestion();
                    highlightController.setCurrentSuggestion(null);
                }
                SwingUtilities.invokeLater(() -> panel.setConnected(false));
                break;

            case HOPPING:
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
