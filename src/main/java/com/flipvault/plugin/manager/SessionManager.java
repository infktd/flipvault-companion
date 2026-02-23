package com.flipvault.plugin.manager;

import com.flipvault.plugin.model.SessionStats;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

@Slf4j
public class SessionManager {
    private static final String FLIPVAULT_DIR = "flipvault";
    private final Gson gson = new Gson();

    private long totalProfit;
    private int flipsDone;
    private int flipsWon;
    private long sessionStartTime;

    public synchronized void startSession() {
        this.totalProfit = 0;
        this.flipsDone = 0;
        this.flipsWon = 0;
        this.sessionStartTime = System.currentTimeMillis();
    }

    public synchronized void recordFlip(long profit) {
        this.totalProfit += profit;
        this.flipsDone++;
        if (profit > 0) {
            this.flipsWon++;
        }
        log.debug("Flip recorded: profit={}, total={}, count={}", profit, totalProfit, flipsDone);
    }

    public synchronized SessionStats getStats() {
        SessionStats stats = new SessionStats();
        stats.setTotalProfit(totalProfit);
        stats.setFlipsDone(flipsDone);
        stats.setFlipsWon(flipsWon);
        stats.setSessionStartTime(sessionStartTime);
        if (flipsDone > 0 && totalProfit != 0) {
            // Average margin is a rough calculation - could be refined
            stats.setAvgMarginPercent(0);
        }
        return stats;
    }

    public synchronized void reset() {
        startSession();
    }

    /**
     * Save session stats to ~/.runelite/flipvault/session-{playerName}.json
     */
    public synchronized void save(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        try {
            Path dir = getDataDir();
            Files.createDirectories(dir);
            Path file = dir.resolve("session-" + playerName + ".json");

            JsonObject obj = new JsonObject();
            obj.addProperty("totalProfit", totalProfit);
            obj.addProperty("flipsDone", flipsDone);
            obj.addProperty("flipsWon", flipsWon);
            obj.addProperty("sessionStartTime", sessionStartTime);
            obj.addProperty("savedAt", System.currentTimeMillis());

            try (Writer writer = new FileWriter(file.toFile())) {
                gson.toJson(obj, writer);
            }
            log.debug("Saved session stats for {}", playerName);
        } catch (IOException e) {
            log.error("Failed to save session stats", e);
        }
    }

    /**
     * Load session stats from ~/.runelite/flipvault/session-{playerName}.json
     * Only restores if the saved session is less than 6 hours old.
     */
    @SuppressWarnings("deprecation")
    public synchronized void load(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        Path file = getDataDir().resolve("session-" + playerName + ".json");
        if (!Files.exists(file)) {
            log.debug("No saved session for {}", playerName);
            return;
        }
        try (Reader reader = new FileReader(file.toFile())) {
            JsonObject obj = new JsonParser().parse(reader).getAsJsonObject();
            long savedAt = obj.has("savedAt") ? obj.get("savedAt").getAsLong() : 0;

            // Only restore if session was saved less than 6 hours ago
            long sixHoursMs = 6 * 60 * 60 * 1000L;
            if (System.currentTimeMillis() - savedAt > sixHoursMs) {
                log.debug("Saved session for {} is too old, starting fresh", playerName);
                return;
            }

            this.totalProfit = obj.has("totalProfit") ? obj.get("totalProfit").getAsLong() : 0;
            this.flipsDone = obj.has("flipsDone") ? obj.get("flipsDone").getAsInt() : 0;
            this.flipsWon = obj.has("flipsWon") ? obj.get("flipsWon").getAsInt() : 0;
            this.sessionStartTime = obj.has("sessionStartTime") ? obj.get("sessionStartTime").getAsLong() : System.currentTimeMillis();

            log.debug("Loaded session stats for {}: profit={}, flips={}", playerName, totalProfit, flipsDone);
        } catch (Exception e) {
            log.error("Failed to load session stats", e);
        }
    }

    private Path getDataDir() {
        return Paths.get(RuneLite.RUNELITE_DIR.getPath(), FLIPVAULT_DIR);
    }
}
