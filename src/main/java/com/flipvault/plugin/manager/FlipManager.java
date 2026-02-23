package com.flipvault.plugin.manager;

import com.flipvault.plugin.model.ActiveFlip;
import com.flipvault.plugin.model.Transaction;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FlipManager {
    // Pending buys keyed by itemId, FIFO queue per item
    private final Map<Integer, Queue<ActiveFlip>> pendingBuys = new ConcurrentHashMap<>();
    // All active flips (items we bought, waiting to sell)
    private final List<ActiveFlip> activeFlips = new CopyOnWriteArrayList<>();

    public synchronized void recordBuy(int itemId, String itemName, int buyPrice, int quantity) {
        ActiveFlip flip = new ActiveFlip(itemId, itemName, buyPrice, quantity, System.currentTimeMillis());
        pendingBuys.computeIfAbsent(itemId, k -> new LinkedList<>()).add(flip);
        activeFlips.add(flip);
        log.debug("Buy recorded: {} x{} @ {}", itemName, quantity, buyPrice);
    }

    /**
     * Match a sell against pending buys for the same item (FIFO).
     * Returns the completed Transaction, or null if no matching buy found.
     */
    public synchronized Transaction matchSell(int itemId, int sellPrice, int quantity) {
        Queue<ActiveFlip> buys = pendingBuys.get(itemId);
        if (buys == null || buys.isEmpty()) {
            log.debug("No pending buy found for item {}", itemId);
            return null;
        }

        ActiveFlip buy = buys.poll();
        activeFlips.remove(buy);

        // If queue is now empty, remove the key
        if (buys.isEmpty()) {
            pendingBuys.remove(itemId);
        }

        long profit = ((long) sellPrice - buy.getBuyPrice()) * quantity;

        Transaction tx = Transaction.builder()
            .itemId(itemId)
            .buyPrice(buy.getBuyPrice())
            .sellPrice(sellPrice)
            .quantity(quantity)
            .profit(profit)
            .buyTimestamp(buy.getBoughtAt())
            .sellTimestamp(System.currentTimeMillis())
            .build();

        log.debug("Sell matched: item={}, profit={}", itemId, profit);
        return tx;
    }

    public List<ActiveFlip> getActiveFlips() {
        return Collections.unmodifiableList(new ArrayList<>(activeFlips));
    }

    public synchronized void clear() {
        pendingBuys.clear();
        activeFlips.clear();
    }
}
