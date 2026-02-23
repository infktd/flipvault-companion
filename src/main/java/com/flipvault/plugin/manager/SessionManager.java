package com.flipvault.plugin.manager;

import com.flipvault.plugin.model.SessionStats;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SessionManager {
    private long totalProfit;
    private int flipsDone;
    private int flipsWon;
    private long sessionStartTime;

    public synchronized void startSession() {
        this.totalProfit = 0;
        this.flipsDone = 0;
        this.flipsWon = 0;
        this.sessionStartTime = System.currentTimeMillis();
    }

    public synchronized void recordFlip(long profit) {
        this.totalProfit += profit;
        this.flipsDone++;
        if (profit > 0) {
            this.flipsWon++;
        }
        log.debug("Flip recorded: profit={}, total={}, count={}", profit, totalProfit, flipsDone);
    }

    public synchronized SessionStats getStats() {
        SessionStats stats = new SessionStats();
        stats.setTotalProfit(totalProfit);
        stats.setFlipsDone(flipsDone);
        stats.setFlipsWon(flipsWon);
        stats.setSessionStartTime(sessionStartTime);
        if (flipsDone > 0 && totalProfit != 0) {
            // Average margin is a rough calculation - could be refined
            stats.setAvgMarginPercent(0);
        }
        return stats;
    }

    public synchronized void reset() {
        startSession();
    }
}
