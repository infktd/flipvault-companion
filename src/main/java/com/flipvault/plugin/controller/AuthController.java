package com.flipvault.plugin.controller;

import com.flipvault.plugin.FlipVaultConfig;
import com.flipvault.plugin.model.ApiKey;
import com.flipvault.plugin.model.AuthState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

@Slf4j
public class AuthController {
    private final ApiClient apiClient;
    private final FlipVaultConfig config;
    private final ConfigManager configManager;
    private final ExecutorService executor;

    @Getter
    private volatile AuthState state = AuthState.NO_KEY;
    @Getter
    private volatile List<ApiKey> availableKeys = new ArrayList<>();
    @Getter
    private volatile String errorMessage = "";
    @Getter
    private volatile String conflictPlayerName = "";
    @Getter @Setter
    private volatile String pendingPlayerName = "";
    @Getter
    private volatile String plan;

    private volatile String sessionToken;
    private volatile String browserAuthNonce;
    private volatile boolean browserAuthCancelled;
    private volatile boolean validationInFlight;

    private static final int BROWSER_AUTH_POLL_INTERVAL_MS = 3000;
    private static final int BROWSER_AUTH_TIMEOUT_MS = 5 * 60 * 1000;

    private final List<AuthStateListener> listeners = new CopyOnWriteArrayList<>();

    public interface AuthStateListener {
        void onAuthStateChanged(AuthState newState);
    }

    public AuthController(ApiClient apiClient, FlipVaultConfig config, ConfigManager configManager, ExecutorService executor) {
        this.apiClient = apiClient;
        this.config = config;
        this.configManager = configManager;
        this.executor = executor;
    }

    public void addListener(AuthStateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AuthStateListener listener) {
        listeners.remove(listener);
    }

    private void setState(AuthState newState) {
        this.state = newState;
        SwingUtilities.invokeLater(() -> {
            for (AuthStateListener l : listeners) {
                l.onAuthStateChanged(newState);
            }
        });
    }

    /**
     * Called at plugin startup. Checks if there is a stored API key
     * and sets state accordingly.
     */
    public void checkStoredKey() {
        String key = config.apiKey();
        if (key == null || key.isEmpty()) {
            setState(AuthState.NO_KEY);
        } else {
            apiClient.setApiKey(key);
            setState(AuthState.VALIDATING);
        }
    }

    /**
     * Called from LoginPanel button click. Authenticates with email/password
     * and transitions to SELECTING_KEY on success.
     */
    public void login(String email, String password) {
        setState(AuthState.LOGGING_IN);
        executor.submit(() -> {
            try {
                JsonObject response = apiClient.login(email, password);
                sessionToken = response.has("sessionToken") ? response.get("sessionToken").getAsString() : null;
                List<ApiKey> keys = parseKeys(response);
                availableKeys = keys;

                if (keys.size() == 1 && pendingPlayerName != null && !pendingPlayerName.isEmpty()) {
                    // Auto-activate single key — skip key selection UI
                    log.debug("Single key found, auto-activating for player: {}", pendingPlayerName);
                    activateKey(keys.get(0).getId(), pendingPlayerName);
                } else {
                    setState(AuthState.SELECTING_KEY);
                }
            } catch (ApiException e) {
                errorMessage = e.getMessage();
                if (e.getStatusCode() == 401 || e.getStatusCode() == 403) {
                    setState(AuthState.NO_KEY);
                } else {
                    setState(AuthState.ERROR);
                }
            }
        });
    }

    /**
     * Called from KeySelectionPanel when user picks a key.
     * Activates the key and binds it to the player name.
     */
    public void activateKey(String keyId, String playerName) {
        setState(AuthState.VALIDATING);
        executor.submit(() -> {
            try {
                JsonObject response = apiClient.activateKey(keyId, playerName, sessionToken);
                String fullApiKey = response.get("apiKey").getAsString();
                String boundTo = response.get("boundTo").getAsString();
                plan = response.has("plan") ? response.get("plan").getAsString() : null;

                // Find the label for this key
                String label = "";
                for (ApiKey k : availableKeys) {
                    if (k.getId().equals(keyId)) {
                        label = k.getLabel();
                        break;
                    }
                }

                storeKey(fullApiKey, label, boundTo);
                setState(AuthState.VALID);
            } catch (ApiException e) {
                errorMessage = e.getMessage();
                if (e.getStatusCode() == 409) {
                    // Key bound to different player
                    try {
                        @SuppressWarnings("deprecation")
                        JsonObject errorJson = new JsonParser().parse(e.getResponseBody()).getAsJsonObject();
                        if (errorJson.has("boundTo")) {
                            conflictPlayerName = errorJson.get("boundTo").getAsString();
                        }
                    } catch (Exception ignored) {
                        // Response body wasn't valid JSON
                    }
                    setState(AuthState.KEY_CONFLICT);
                } else {
                    setState(AuthState.ERROR);
                }
            }
        });
    }

    /**
     * Called at startup after checkStoredKey sets VALIDATING.
     * Validates the stored API key with the backend.
     */
    public void validateKey(String playerName) {
        if (validationInFlight) return;
        validationInFlight = true;
        executor.submit(() -> {
            try {
                JsonObject response = apiClient.validateKey(playerName);
                boolean valid = response.has("valid") && response.get("valid").getAsBoolean();
                if (valid) {
                    plan = response.has("plan") ? response.get("plan").getAsString() : null;
                    setState(AuthState.VALID);
                } else {
                    String reason = response.has("reason") ? response.get("reason").getAsString() : "Key invalid";
                    errorMessage = reason;
                    if (response.has("boundTo")) {
                        conflictPlayerName = response.get("boundTo").getAsString();
                        setState(AuthState.KEY_CONFLICT);
                    } else {
                        clearStoredKey();
                        setState(AuthState.EXPIRED);
                    }
                }
            } catch (ApiException e) {
                errorMessage = e.getMessage();
                if (e.getStatusCode() == 401) {
                    clearStoredKey();
                    setState(AuthState.EXPIRED);
                } else if (e.getStatusCode() == 409) {
                    try {
                        @SuppressWarnings("deprecation")
                        JsonObject errorJson = new JsonParser().parse(e.getResponseBody()).getAsJsonObject();
                        if (errorJson.has("boundTo")) {
                            conflictPlayerName = errorJson.get("boundTo").getAsString();
                        }
                    } catch (Exception ignored) {
                        // Response body wasn't valid JSON
                    }
                    setState(AuthState.KEY_CONFLICT);
                } else {
                    setState(AuthState.ERROR);
                }
            } catch (Exception e) {
                // Safety net — ensure we never get stuck in VALIDATING
                log.error("Unexpected error during key validation", e);
                errorMessage = "Validation failed: " + e.getMessage();
                setState(AuthState.ERROR);
            } finally {
                validationInFlight = false;
            }
        });
    }

    /**
     * Called from LoginPanel Discord button. Opens the FlipVault login page
     * in the browser with a session nonce, then polls until auth completes.
     */
    public void loginViaBrowser() {
        browserAuthCancelled = false;
        browserAuthNonce = UUID.randomUUID().toString();

        final String nonce = browserAuthNonce;
        final String loginUrl = "https://flipvault.app/login?plugin_session=" + nonce;

        if (!openBrowser(loginUrl)) {
            errorMessage = "Could not open browser. Visit: " + loginUrl;
            setState(AuthState.ERROR);
            return;
        }

        setState(AuthState.POLLING_BROWSER);
        executor.submit(() -> {
            try {
                long startTime = System.currentTimeMillis();
                while (!browserAuthCancelled) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (elapsed >= BROWSER_AUTH_TIMEOUT_MS) {
                        errorMessage = "Browser login timed out. Please try again.";
                        setState(AuthState.NO_KEY);
                        return;
                    }

                    Thread.sleep(BROWSER_AUTH_POLL_INTERVAL_MS);

                    if (browserAuthCancelled) {
                        break;
                    }

                    JsonObject pollResponse = apiClient.pollBrowserAuth(nonce);
                    String status = pollResponse.get("status").getAsString();

                    if ("completed".equals(status)) {
                        sessionToken = pollResponse.has("sessionToken") ? pollResponse.get("sessionToken").getAsString() : null;
                        List<ApiKey> keys = parseKeys(pollResponse);
                        availableKeys = keys;

                        if (keys.size() == 1 && pendingPlayerName != null && !pendingPlayerName.isEmpty()) {
                            log.debug("Single key found via browser auth, auto-activating for player: {}", pendingPlayerName);
                            activateKey(keys.get(0).getId(), pendingPlayerName);
                        } else {
                            setState(AuthState.SELECTING_KEY);
                        }
                        return;
                    } else if ("expired".equals(status)) {
                        errorMessage = "Browser session expired. Please try again.";
                        setState(AuthState.NO_KEY);
                        return;
                    }
                    // "pending" — continue polling
                }

                // Cancelled
                setState(AuthState.NO_KEY);
            } catch (ApiException e) {
                errorMessage = e.getMessage();
                setState(AuthState.ERROR);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                setState(AuthState.NO_KEY);
            }
        });
    }

    /**
     * Cancel an in-progress browser auth polling loop.
     */
    public void cancelBrowserAuth() {
        browserAuthCancelled = true;
    }

    /**
     * Open a URL in the user's default browser.
     */
    private boolean openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to open browser: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Called when any API request returns 401.
     * Clears stored key and transitions to EXPIRED.
     */
    public void onUnauthorized() {
        clearStoredKey();
        setState(AuthState.EXPIRED);
    }

    /**
     * Retry validation using the existing stored key.
     * If no stored key exists, falls back to resetToLogin.
     */
    public void retryValidation() {
        String key = config.apiKey();
        if (key == null || key.isEmpty()) {
            resetToLogin();
            return;
        }
        String playerName = pendingPlayerName;
        if (playerName == null || playerName.isEmpty()) {
            // Can't validate without a player name — stay in VALIDATING
            // until onGameStateChanged(LOGGED_IN) provides one
            apiClient.setApiKey(key);
            setState(AuthState.VALIDATING);
            return;
        }
        apiClient.setApiKey(key);
        setState(AuthState.VALIDATING);
        validateKey(playerName);
    }

    /**
     * Re-show login panel. Used by conflict/expired states
     * when user wants to start over.
     */
    public void resetToLogin() {
        clearStoredKey();
        setState(AuthState.NO_KEY);
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
        this.apiClient.setApiKey(apiKey);
    }

    @SuppressWarnings("deprecation")
    private List<ApiKey> parseKeys(JsonObject loginResponse) {
        List<ApiKey> keys = new ArrayList<>();
        if (loginResponse.has("keys")) {
            JsonArray keysArray = loginResponse.getAsJsonArray("keys");
            for (JsonElement elem : keysArray) {
                JsonObject keyObj = elem.getAsJsonObject();
                ApiKey key = new ApiKey();
                key.setId(keyObj.get("id").getAsString());
                key.setLabel(keyObj.has("label") ? keyObj.get("label").getAsString() : "");
                key.setMaskedKey(keyObj.has("maskedKey") ? keyObj.get("maskedKey").getAsString() : "");
                key.setBoundTo(keyObj.has("boundTo") && !keyObj.get("boundTo").isJsonNull() ? keyObj.get("boundTo").getAsString() : null);
                key.setCreatedAt(keyObj.has("createdAt") ? keyObj.get("createdAt").getAsString() : "");
                keys.add(key);
            }
        }
        return keys;
    }
}
