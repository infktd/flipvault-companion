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

    // Constructor for empty slots
    public GESlotState(int slotIndex, SlotStatus status) {
        this(slotIndex, status, 0, 0, 0, 0, 0);
    }
}
