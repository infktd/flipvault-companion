package com.flipvault.plugin.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AccountState {
    private String playerName;
    private long cashStack;
    private boolean isMembers;
    private GESlotState[] geSlots;
    private int freeSlots;
    private long totalWealth;
    private long timestamp;
}
