package com.flipvault.plugin.controller;

import com.flipvault.plugin.manager.FlipManager;
import com.flipvault.plugin.model.AccountState;
import com.flipvault.plugin.model.ActiveFlip;
import com.flipvault.plugin.model.Suggestion;
import java.util.List;
import java.util.concurrent.ExecutorService;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SuggestionController {
    private static final long MIN_REQUEST_INTERVAL_MS = 2000;
    private static final long WAIT_RETRY_MS = 30000;

    private final ApiClient apiClient;
    private final FlipManager flipManager;
    private final ExecutorService executor;

    @Getter
    private volatile Suggestion currentSuggestion;
    private volatile long lastRequestTime;
    private volatile boolean requestInFlight;

    private SuggestionListener listener;

    public interface SuggestionListener {
        void onSuggestionUpdated(Suggestion suggestion);
        void onSuggestionError(String error);
        void onSuggestionLoading();
    }

    public SuggestionController(ApiClient apiClient, FlipManager flipManager, ExecutorService executor) {
        this.apiClient = apiClient;
        this.flipManager = flipManager;
        this.executor = executor;
    }

    public void setListener(SuggestionListener listener) {
        this.listener = listener;
    }

    /**
     * Called when game state changes. Decides whether to request a new suggestion.
     */
    public void onStateChanged(AccountState state) {
        if (!shouldRequest(state)) return;
        requestSuggestion(state);
    }

    /**
     * Manual refresh from user clicking refresh button. Bypasses rate limit.
     */
    public void refresh(AccountState state) {
        lastRequestTime = 0;
        requestSuggestion(state);
    }

    private boolean shouldRequest(AccountState state) {
        if (state == null || state.getPlayerName() == null) {
            return false;
        }
        if (requestInFlight) {
            return false;
        }
        if (System.currentTimeMillis() - lastRequestTime < MIN_REQUEST_INTERVAL_MS) {
            return false;
        }
        if (state.getFreeSlots() == 0) {
            // All slots occupied - update UI to say so
            if (listener != null) {
                SwingUtilities.invokeLater(() ->
                    listener.onSuggestionError("All GE slots in use"));
            }
            return false;
        }
        return true;
    }

    private void requestSuggestion(AccountState state) {
        if (state == null) return;

        requestInFlight = true;
        lastRequestTime = System.currentTimeMillis();

        // Notify UI that we're loading
        if (listener != null) {
            SwingUtilities.invokeLater(() -> listener.onSuggestionLoading());
        }

        executor.submit(() -> {
            try {
                List<ActiveFlip> activeFlips = flipManager.getActiveFlips();
                Suggestion suggestion = apiClient.requestSuggestion(state, activeFlips);
                currentSuggestion = suggestion;

                log.debug("Suggestion received: {} {} x{} @ {}",
                    suggestion.getAction(), suggestion.getItemName(),
                    suggestion.getQuantity(), suggestion.getPrice());

                SwingUtilities.invokeLater(() -> {
                    if (listener != null) {
                        listener.onSuggestionUpdated(suggestion);
                    }
                });

                // If WAIT, schedule a retry after WAIT_RETRY_MS
                if ("WAIT".equals(suggestion.getAction())) {
                    scheduleWaitRetry(state);
                }
            } catch (ApiException e) {
                log.warn("Suggestion request failed: {} (status={})", e.getMessage(), e.getStatusCode());
                currentSuggestion = null;

                SwingUtilities.invokeLater(() -> {
                    if (listener != null) {
                        listener.onSuggestionError(e.getMessage());
                    }
                });
            } finally {
                requestInFlight = false;
            }
        });
    }

    private void scheduleWaitRetry(AccountState state) {
        executor.submit(() -> {
            try {
                Thread.sleep(WAIT_RETRY_MS);
                // After waiting, try again if no other request happened
                if (System.currentTimeMillis() - lastRequestTime >= WAIT_RETRY_MS) {
                    requestSuggestion(state);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public void clearSuggestion() {
        currentSuggestion = null;
        if (listener != null) {
            SwingUtilities.invokeLater(() -> listener.onSuggestionUpdated(null));
        }
    }
}
