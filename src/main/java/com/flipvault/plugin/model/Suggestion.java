package com.flipvault.plugin.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

@Data
public class Suggestion {
    private String action;      // BUY, SELL, CANCEL, WAIT, COLLECT
    private int itemId;
    private String itemName;
    private int price;
    private int quantity;
    private int buyLimit;

    @SerializedName("targetSlot")
    private Integer slotIndex;  // Nullable, used for CANCEL action

    @SerializedName("isDump")
    private boolean dump;

    private int confidence;
    private String signal;
    private List<String> pills;
    private String reason;

    @SerializedName("estimatedProfitPer")
    private long estimatedProfit;

    @SerializedName("estimatedGpHr")
    private long estimatedGpPerHour;

    private Integer estimatedRecovery;
    private int estimatedSellPrice; // Expected sell price (for BUY suggestions)
    private long serverTimestamp;
    private List<Object> graphData;
}
