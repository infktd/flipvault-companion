package com.flipvault.plugin.model;

import lombok.Data;

@Data
public class Suggestion {
    private String action;      // BUY, SELL, CANCEL, WAIT, COLLECT
    private int itemId;
    private String itemName;
    private int price;
    private int quantity;
    private long estimatedProfit;
    private long estimatedGpPerHour;
    private String reason;
    private int confidence;
    private String signal;
    private Integer slotIndex;  // Nullable, used for CANCEL action
}
