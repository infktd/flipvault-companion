package com.flipvault.plugin.controller;

import com.flipvault.plugin.manager.FlipManager;
import com.flipvault.plugin.model.AccountState;
import com.flipvault.plugin.model.ActiveFlip;
import com.flipvault.plugin.model.Suggestion;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SuggestionController {
    private static final long MIN_REQUEST_INTERVAL_MS = 2000;
    private static final long WAIT_RETRY_MS = 30000;
    private static final long NETWORK_ERROR_RETRY_MS = 10000;
    private static final long SERVER_ERROR_RETRY_MS = 30000;
    private static final long TIMEOUT_RETRY_MS = 5000;

    private final ApiClient apiClient;
    private final AuthController authController;
    private final FlipManager flipManager;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduledExecutor;

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

    public SuggestionController(ApiClient apiClient, AuthController authController,
                                FlipManager flipManager, ExecutorService executor,
                                ScheduledExecutorService scheduledExecutor) {
        this.apiClient = apiClient;
        this.authController = authController;
        this.flipManager = flipManager;
        this.executor = executor;
        this.scheduledExecutor = scheduledExecutor;
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
                    scheduleRetry(state, WAIT_RETRY_MS);
                }
            } catch (ApiException e) {
                log.warn("Suggestion request failed: {} (status={})", e.getMessage(), e.getStatusCode());
                currentSuggestion = null;

                SwingUtilities.invokeLater(() -> {
                    if (listener != null) {
                        listener.onSuggestionError(e.getMessage());
                    }
                });

                // Error-specific retry logic
                int status = e.getStatusCode();
                if (status == 401) {
                    // Auth expired - don't retry, let auth controller handle it
                    authController.onUnauthorized();
                } else if (status >= 500) {
                    // Server error - retry after 30s
                    scheduleRetry(state, SERVER_ERROR_RETRY_MS);
                } else if (status == 0 && isTimeout(e)) {
                    // Timeout - retry after 5s
                    scheduleRetry(state, TIMEOUT_RETRY_MS);
                } else if (status == 0) {
                    // Other network error - retry after 10s
                    scheduleRetry(state, NETWORK_ERROR_RETRY_MS);
                }
                // For 4xx (other than 401), don't auto-retry
            } finally {
                requestInFlight = false;
            }
        });
    }

    private boolean isTimeout(ApiException e) {
        Throwable cause = e.getCause();
        return cause instanceof SocketTimeoutException
            || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout"));
    }

    private void scheduleRetry(AccountState state, long delayMs) {
        scheduledExecutor.schedule(() -> {
            // After waiting, try again if no other request happened
            if (System.currentTimeMillis() - lastRequestTime >= delayMs) {
                requestSuggestion(state);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public void clearSuggestion() {
        currentSuggestion = null;
        if (listener != null) {
            SwingUtilities.invokeLater(() -> listener.onSuggestionUpdated(null));
        }
    }
}
