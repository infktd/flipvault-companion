package com.flipvault.plugin.controller;

import com.flipvault.plugin.model.AccountState;
import com.flipvault.plugin.model.ActiveFlip;
import com.flipvault.plugin.model.GESlotState;
import com.flipvault.plugin.model.Suggestion;
import com.flipvault.plugin.model.Transaction;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {
    private static final String BASE_URL = "https://api.flipvault.app/api/plugin";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private volatile String apiKey;

    public ApiClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    /**
     * Authenticate with email/password to retrieve API keys.
     * Does NOT send X-API-Key header.
     *
     * @return parsed JSON with keys array, displayName, etc.
     */
    public JsonObject login(String email, String password) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        return post("/login", body);
    }

    /**
     * Activate (bind) an API key to a player name.
     * Does NOT send X-API-Key header.
     *
     * @return JSON with apiKey, boundTo, plan, expiresAt
     */
    public JsonObject activateKey(String keyId, String playerName) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("keyId", keyId);
        body.addProperty("playerName", playerName);
        return post("/activate-key", body);
    }

    /**
     * Validate the current API key for a given player.
     * Sends X-API-Key header.
     *
     * @return JSON with valid, plan, boundTo, expiresAt (or valid=false, reason)
     */
    public JsonObject validateKey(String playerName) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("playerName", playerName);
        return postAuthenticated("/validate-key", body);
    }

    /**
     * Send a heartbeat to keep the session alive.
     * Sends X-API-Key header.
     */
    public void heartbeat(String playerName, int world, String sessionStartedIso) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("playerName", playerName);
        body.addProperty("world", world);
        body.addProperty("sessionStarted", sessionStartedIso);
        postAuthenticated("/heartbeat", body);
    }

    /**
     * Request a trading suggestion from the server.
     * Sends X-API-Key header.
     * Builds the JSON body manually to match the API contract format.
     *
     * @return a Suggestion parsed from the response
     */
    public Suggestion requestSuggestion(AccountState state, List<ActiveFlip> activeFlips) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("playerName", state.getPlayerName());
        body.addProperty("cashStack", state.getCashStack());
        body.addProperty("isMembers", state.isMembers());
        body.addProperty("freeSlots", state.getFreeSlots());
        body.addProperty("timestamp", epochMillisToIso(state.getTimestamp()));

        // Build geSlots array
        JsonArray geSlotsArray = new JsonArray();
        if (state.getGeSlots() != null) {
            for (GESlotState slot : state.getGeSlots()) {
                JsonObject slotObj = new JsonObject();
                slotObj.addProperty("slotIndex", slot.getSlotIndex());
                slotObj.addProperty("status", slot.getStatus().name());
                slotObj.addProperty("itemId", slot.getItemId());
                slotObj.addProperty("price", slot.getPrice());
                slotObj.addProperty("totalQuantity", slot.getTotalQuantity());
                slotObj.addProperty("quantityFilled", slot.getQuantityFilled());
                slotObj.addProperty("spent", slot.getSpent());
                geSlotsArray.add(slotObj);
            }
        }
        body.add("geSlots", geSlotsArray);

        // Build activeFlips array
        JsonArray activeFlipsArray = new JsonArray();
        if (activeFlips != null) {
            for (ActiveFlip flip : activeFlips) {
                JsonObject flipObj = new JsonObject();
                flipObj.addProperty("itemId", flip.getItemId());
                flipObj.addProperty("itemName", flip.getItemName());
                flipObj.addProperty("buyPrice", flip.getBuyPrice());
                flipObj.addProperty("quantity", flip.getQuantity());
                flipObj.addProperty("boughtAt", epochMillisToIso(flip.getBoughtAt()));
                activeFlipsArray.add(flipObj);
            }
        }
        body.add("activeFlips", activeFlipsArray);

        JsonObject response = postAuthenticated("/suggest", body);
        return gson.fromJson(response, Suggestion.class);
    }

    /**
     * Report a completed transaction (flip) to the server.
     * Sends X-API-Key header.
     */
    public void reportTransaction(Transaction tx) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("itemId", tx.getItemId());
        body.addProperty("buyPrice", tx.getBuyPrice());
        body.addProperty("sellPrice", tx.getSellPrice());
        body.addProperty("quantity", tx.getQuantity());
        body.addProperty("profit", tx.getProfit());
        body.addProperty("buyTimestamp", epochMillisToIso(tx.getBuyTimestamp()));
        body.addProperty("sellTimestamp", epochMillisToIso(tx.getSellTimestamp()));
        postAuthenticated("/transaction", body);
    }

    // ---- Internal helpers ----

    private JsonObject post(String path, JsonObject body) throws ApiException {
        return postWithKey(path, body, false);
    }

    private JsonObject postAuthenticated(String path, JsonObject body) throws ApiException {
        return postWithKey(path, body, true);
    }

    @SuppressWarnings("deprecation")
    private JsonObject postWithKey(String path, JsonObject body, boolean includeApiKey) throws ApiException {
        Request.Builder builder = new Request.Builder()
                .url(BASE_URL + path)
                .post(RequestBody.create(JSON, body.toString()));

        if (includeApiKey && apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                String errorMsg = "API error";
                try {
                    JsonObject errorJson = new JsonParser().parse(responseBody).getAsJsonObject();
                    if (errorJson.has("error")) {
                        errorMsg = errorJson.get("error").getAsString();
                    } else if (errorJson.has("reason")) {
                        errorMsg = errorJson.get("reason").getAsString();
                    }
                } catch (Exception ignored) {
                    // Response body wasn't valid JSON or didn't have expected fields
                }
                throw new ApiException(errorMsg, response.code(), responseBody);
            }

            if (responseBody.isEmpty()) {
                return new JsonObject();
            }
            return new JsonParser().parse(responseBody).getAsJsonObject();
        } catch (IOException e) {
            throw new ApiException("Network error: " + e.getMessage(), e);
        }
    }

    private static String epochMillisToIso(long epochMillis) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
    }
}
