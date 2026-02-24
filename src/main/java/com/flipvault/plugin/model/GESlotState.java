package com.flipvault.plugin.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GESlotState {
    private int slotIndex;
    private SlotStatus status;
    private int itemId;
    private int price;
    private int totalQuantity;
    private int quantityFilled;
    private int spent;

    // Suggestion metadata — set by GEOfferHandler after construction
    private boolean wasFlipVaultSuggestion;
    private boolean flipVaultPriceUsed;

    // Constructor for full offer state (without metadata flags)
    public GESlotState(int slotIndex, SlotStatus status, int itemId, int price,
                       int totalQuantity, int quantityFilled, int spent) {
        this(slotIndex, status, itemId, price, totalQuantity, quantityFilled, spent, false, false);
    }

    // Constructor for empty slots
    public GESlotState(int slotIndex, SlotStatus status) {
        this(slotIndex, status, 0, 0, 0, 0, 0, false, false);
    }
}
