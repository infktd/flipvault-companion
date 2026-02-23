package com.flipvault.plugin.manager;

import com.flipvault.plugin.model.GESlotState;
import com.flipvault.plugin.model.SlotStatus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

@Slf4j
public class OfferManager {
    private static final String FLIPVAULT_DIR = "flipvault";
    private final GESlotState[] previousStates = new GESlotState[8];
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public OfferManager() {
        // Initialize all slots to EMPTY
        for (int i = 0; i < 8; i++) {
            previousStates[i] = new GESlotState(i, SlotStatus.EMPTY);
        }
    }

    public synchronized void updateSlot(int slot, GESlotState newState) {
        if (slot >= 0 && slot < 8) {
            previousStates[slot] = newState;
        }
    }

    public synchronized GESlotState getPreviousState(int slot) {
        if (slot >= 0 && slot < 8) {
            return previousStates[slot];
        }
        return null;
    }

    public synchronized GESlotState[] getAllStates() {
        return Arrays.copyOf(previousStates, 8);
    }

    /**
     * Check if the new states differ from previous states.
     * Compares status, quantityFilled, spent, and itemId for each slot.
     */
    public synchronized boolean hasStateChanged(GESlotState[] newStates) {
        if (newStates == null) return false;
        for (int i = 0; i < Math.min(8, newStates.length); i++) {
            GESlotState prev = previousStates[i];
            GESlotState curr = newStates[i];
            if (prev == null && curr != null) return true;
            if (prev != null && curr == null) return true;
            if (prev == null) continue;
            if (prev.getStatus() != curr.getStatus()) return true;
            if (prev.getQuantityFilled() != curr.getQuantityFilled()) return true;
            if (prev.getSpent() != curr.getSpent()) return true;
            if (prev.getItemId() != curr.getItemId()) return true;
        }
        return false;
    }

    /**
     * Save offer states to ~/.runelite/flipvault/offers-{playerName}.json
     */
    public void save(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        try {
            Path dir = getDataDir();
            Files.createDirectories(dir);
            Path file = dir.resolve("offers-" + playerName + ".json");
            try (Writer writer = new FileWriter(file.toFile())) {
                gson.toJson(previousStates, writer);
            }
            log.debug("Saved offer states for {}", playerName);
        } catch (IOException e) {
            log.error("Failed to save offer states", e);
        }
    }

    /**
     * Load offer states from ~/.runelite/flipvault/offers-{playerName}.json
     */
    public void load(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        Path file = getDataDir().resolve("offers-" + playerName + ".json");
        if (!Files.exists(file)) {
            log.debug("No saved offer states for {}", playerName);
            return;
        }
        try (Reader reader = new FileReader(file.toFile())) {
            GESlotState[] loaded = gson.fromJson(reader, GESlotState[].class);
            if (loaded != null) {
                synchronized (this) {
                    for (int i = 0; i < Math.min(8, loaded.length); i++) {
                        if (loaded[i] != null) {
                            previousStates[i] = loaded[i];
                        }
                    }
                }
                log.debug("Loaded offer states for {}", playerName);
            }
        } catch (Exception e) {
            log.error("Failed to load offer states", e);
        }
    }

    private Path getDataDir() {
        return Paths.get(RuneLite.RUNELITE_DIR.getPath(), FLIPVAULT_DIR);
    }
}
