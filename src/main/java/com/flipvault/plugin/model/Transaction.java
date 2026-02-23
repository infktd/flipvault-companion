package com.flipvault.plugin.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Transaction {
    private int itemId;
    private int buyPrice;
    private int sellPrice;
    private int quantity;
    private long profit;
    private long buyTimestamp;
    private long sellTimestamp;
}
