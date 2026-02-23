package com.flipvault.plugin.controller;

import com.flipvault.plugin.manager.FlipManager;
import com.flipvault.plugin.manager.OfferManager;
import com.flipvault.plugin.manager.SessionManager;
import com.flipvault.plugin.model.GESlotState;
import com.flipvault.plugin.model.SlotStatus;
import com.flipvault.plugin.model.Transaction;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;

@Slf4j
public class GEOfferHandler {
    private final OfferManager offerManager;
    private final FlipManager flipManager;
    private final SessionManager sessionManager;
    private final ApiClient apiClient;
    private final ExecutorService executor;

    // Listener for when state changes are detected (triggers suggestion refresh)
    private Runnable onStateChangedCallback;

    public GEOfferHandler(OfferManager offerManager, FlipManager flipManager,
                          SessionManager sessionManager, ApiClient apiClient,
                          ExecutorService executor) {
        this.offerManager = offerManager;
        this.flipManager = flipManager;
        this.sessionManager = sessionManager;
        this.apiClient = apiClient;
        this.executor = executor;
    }

    public void setOnStateChangedCallback(Runnable callback) {
        this.onStateChangedCallback = callback;
    }

    public void onOfferChanged(GrandExchangeOfferChanged event) {
        int slot = event.getSlot();
        GrandExchangeOffer offer = event.getOffer();
        GESlotState newState = mapOffer(slot, offer);
        GESlotState prevState = offerManager.getPreviousState(slot);

        if (prevState != null && newState != null) {
            processTransition(prevState, newState);
        }

        offerManager.updateSlot(slot, newState);

        // Notify that state changed (for suggestion refresh)
        if (onStateChangedCallback != null) {
            onStateChangedCallback.run();
        }
    }

    private void processTransition(GESlotState prev, GESlotState current) {
        SlotStatus prevStatus = prev.getStatus();
        SlotStatus currStatus = current.getStatus();

        // BUY completed - record pending buy
        if (prevStatus == SlotStatus.BUYING && currStatus == SlotStatus.BUY_COMPLETE) {
            log.debug("Buy completed: item={} qty={} price={}",
                current.getItemId(), current.getQuantityFilled(), current.getPrice());
            flipManager.recordBuy(
                current.getItemId(),
                "", // item name not available from GE offer, will be resolved elsewhere
                current.getPrice(),
                current.getQuantityFilled()
            );
        }

        // SELL completed - match against pending buy
        if (prevStatus == SlotStatus.SELLING && currStatus == SlotStatus.SELL_COMPLETE) {
            log.debug("Sell completed: item={} qty={} price={}",
                current.getItemId(), current.getQuantityFilled(), current.getPrice());
            Transaction tx = flipManager.matchSell(
                current.getItemId(),
                current.getPrice(),
                current.getQuantityFilled()
            );
            if (tx != null) {
                sessionManager.recordFlip(tx.getProfit());
                reportTransaction(tx);
            }
        }

        // Log other transitions for debugging
        if (prevStatus != currStatus) {
            log.debug("Slot {} transition: {} -> {} (item={})",
                current.getSlotIndex(), prevStatus, currStatus, current.getItemId());
        }
    }

    private void reportTransaction(Transaction tx) {
        executor.submit(() -> {
            try {
                apiClient.reportTransaction(tx);
                log.debug("Transaction reported to backend: item={} profit={}", tx.getItemId(), tx.getProfit());
            } catch (ApiException e) {
                log.warn("Failed to report transaction: {}", e.getMessage());
                // Could add to a retry queue here
            }
        });
    }

    private GESlotState mapOffer(int slot, GrandExchangeOffer offer) {
        if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) {
            return new GESlotState(slot, SlotStatus.EMPTY);
        }
        return new GESlotState(
            slot,
            mapOfferState(offer.getState()),
            offer.getItemId(),
            offer.getPrice(),
            offer.getTotalQuantity(),
            offer.getQuantitySold(),
            offer.getSpent()
        );
    }

    private SlotStatus mapOfferState(GrandExchangeOfferState state) {
        switch (state) {
            case BUYING:
                return SlotStatus.BUYING;
            case BOUGHT:
                return SlotStatus.BUY_COMPLETE;
            case SELLING:
                return SlotStatus.SELLING;
            case SOLD:
                return SlotStatus.SELL_COMPLETE;
            case CANCELLED_BUY:
            case CANCELLED_SELL:
                return SlotStatus.CANCELLED;
            case EMPTY:
            default:
                return SlotStatus.EMPTY;
        }
    }
}
