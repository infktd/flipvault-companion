package com.flipvault.plugin.manager;

import com.flipvault.plugin.model.Transaction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Append-only JSONL logger for completed flips.
 * Writes one JSON object per line to ~/.runelite/flipvault/flips-{playerName}.jsonl
 */
@Slf4j
public class FlipLogger {
    private static final String FLIPVAULT_DIR = "flipvault";
    private final Gson gson = new Gson();
    private volatile String currentPlayerName;

    public void setPlayerName(String playerName) {
        this.currentPlayerName = playerName;
    }

    /**
     * Append a completed transaction to the JSONL flip log.
     * Thread-safe via synchronized file write.
     */
    public synchronized void logFlip(Transaction tx) {
        String playerName = currentPlayerName;
        if (playerName == null || playerName.isEmpty()) {
            log.debug("Cannot log flip: no player name set");
            return;
        }

        try {
            Path dir = getDataDir();
            Files.createDirectories(dir);
            Path file = dir.resolve("flips-" + playerName + ".jsonl");

            JsonObject entry = new JsonObject();
            entry.addProperty("itemId", tx.getItemId());
            entry.addProperty("buyPrice", tx.getBuyPrice());
            entry.addProperty("sellPrice", tx.getSellPrice());
            entry.addProperty("quantity", tx.getQuantity());
            entry.addProperty("profit", tx.getProfit());
            entry.addProperty("buyTimestamp", epochMillisToIso(tx.getBuyTimestamp()));
            entry.addProperty("sellTimestamp", epochMillisToIso(tx.getSellTimestamp()));

            try (FileWriter writer = new FileWriter(file.toFile(), true)) {
                writer.write(gson.toJson(entry));
                writer.write('\n');
            }

            log.debug("Logged flip to {}: item={} profit={}", file.getFileName(), tx.getItemId(), tx.getProfit());
        } catch (IOException e) {
            log.error("Failed to log flip to JSONL", e);
        }
    }

    private static String epochMillisToIso(long epochMillis) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
    }

    private Path getDataDir() {
        return Paths.get(RuneLite.RUNELITE_DIR.getPath(), FLIPVAULT_DIR);
    }
}
