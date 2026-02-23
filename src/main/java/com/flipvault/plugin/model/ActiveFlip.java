package com.flipvault.plugin.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ActiveFlip {
    private int itemId;
    private String itemName;
    private int buyPrice;
    private int quantity;
    private long boughtAt;  // epoch millis
}
