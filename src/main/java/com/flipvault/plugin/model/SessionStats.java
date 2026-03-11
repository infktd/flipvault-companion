package com.flipvault.plugin.model;

import lombok.Data;

@Data
public class SessionStats {
    private long totalProfit;
    private int flipsDone;
    private int flipsWon;
    private double avgMarginPercent;
    private long sessionStartTime;

    public double getWinRate() {
        return flipsDone == 0 ? 0.0 : (double) flipsWon / flipsDone * 100.0;
    }

    public String getFormattedProfit() {
        if (Math.abs(totalProfit) >= 1_000_000) {
            return String.format("%+.1fM", totalProfit / 1_000_000.0);
        } else if (Math.abs(totalProfit) >= 1_000) {
            return String.format("%+.0fK", totalProfit / 1_000.0);
        }
        return String.format("%+d", totalProfit);
    }

    public long getGpPerHour() {
        long elapsed = System.currentTimeMillis() - sessionStartTime;
        if (elapsed <= 0) return 0;
        return (long) (totalProfit / (elapsed / 3_600_000.0));
    }

    public String getTimeActive() {
        long elapsed = System.currentTimeMillis() - sessionStartTime;
        long hours = elapsed / 3_600_000;
        long minutes = (elapsed % 3_600_000) / 60_000;
        long seconds = (elapsed % 60_000) / 1_000;
        return String.format("%d:%02d:%02d", hours, minutes, seconds);
    }
}
