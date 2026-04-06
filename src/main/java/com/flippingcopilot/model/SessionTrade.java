package com.flippingcopilot.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SessionTrade {
    private final int itemId;
    private final String itemName;
    private final int quantity;
    private final long profit;
    private final long timestamp; // epoch millis
}
