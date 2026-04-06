package com.flippingcopilot.model;

import com.flippingcopilot.controller.ApiRequestHandler;
import com.flippingcopilot.controller.Persistance;
import com.flippingcopilot.rs.FVLoginRS;
import com.flippingcopilot.util.MutableReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TransactionManager {

    // dependencies
    private final FlipManager flipManager;
    private final ScheduledExecutorService executorService;
    private final ApiRequestHandler api;
    private final FVLoginRS fvLoginRS;
    private final OsrsLoginManager osrsLoginManager;
    private final SessionManager sessionManager;

    // state
    private final ConcurrentMap<String, List<Transaction>> cachedUnAckedTransactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicBoolean> transactionSyncScheduled = new ConcurrentHashMap<>();

    public void syncUnAckedTransactions(String displayName) {

        long s = System.nanoTime();
        List<Transaction> toSend;
        synchronized (this) {
            toSend = new ArrayList<>(getUnAckedTransactions(displayName));
            if(toSend.isEmpty()) {
                transactionSyncScheduled.get(displayName).set(false);
                return;
            }
        }

        BiConsumer<Integer, List<FlipV2>> onSuccess = (userId, flips) -> {
            if(!flips.isEmpty()) {
                fvLoginRS.addAccountIfMissing(flips.get(0).getAccountId(), displayName, userId);
            }
            flipManager.mergeFlips(flips, userId);
            log.info("sending {} transactions took {}ms", toSend.size(), (System.nanoTime() - s) / 1000_000);
            synchronized (this) {
                List<Transaction> unAckedTransactions  = getUnAckedTransactions(displayName);
                transactionSyncScheduled.get(displayName).set(false);
                toSend.forEach(unAckedTransactions::remove);
                if(!unAckedTransactions.isEmpty()) {
                    scheduleSyncIn(0, displayName);
                }
            }
        };

        Consumer<HttpResponseException> onFailure = (e) -> {
            synchronized (this) {
                transactionSyncScheduled.get(displayName).set(false);
            }
            String currentDisplayName = osrsLoginManager.getPlayerDisplayName();
            if (fvLoginRS.get().isLoggedIn() && (currentDisplayName == null || currentDisplayName.equals(displayName))) {
                log.warn("failed to send transactions to FV server {}", e.getMessage(), e);
                scheduleSyncIn(10, displayName);
            }
        };
        api.sendTransactionsAsync(toSend, displayName, onSuccess, onFailure);
    }

    public long addTransaction(Transaction transaction, String displayName) {
        if (osrsLoginManager.isUnsupportedWorldType()) {
            log.debug("ignoring transaction for {} on unsupported world type(s)", displayName);
            return 0;
        }
        synchronized (this) {
            List<Transaction> unAckedTransactions = getUnAckedTransactions(displayName);
            unAckedTransactions.add(transaction);
            Persistance.storeUnAckedTransactions(unAckedTransactions, displayName);
        }

        long profit = 0;
        if (OfferStatus.BUY.equals(transaction.getType())) {
            // Track buy locally so we can estimate profit when the paired sell comes in
            flipManager.trackLocalBuy(displayName, transaction.getItemId(), transaction.getAmountSpent(), transaction.getQuantity());
        } else if (OfferStatus.SELL.equals(transaction.getType())) {
            // Try server-side estimate first (requires account data from server)
            Integer accountId = fvLoginRS.get().getAccountId(displayName);
            if (accountId != null && accountId != -1) {
                Long p = flipManager.estimateTransactionProfit(accountId, transaction);
                if (p != null) profit = p;
            }
            // Fall back to local estimate if server data is unavailable
            if (profit == 0) {
                Long p = flipManager.estimateLocalProfit(displayName, transaction);
                if (p != null) profit = p;
            }
            if (profit != 0) {
                sessionManager.addSessionProfit(profit, displayName);
            }
        }

        if (fvLoginRS.get().isLoggedIn()) {
            scheduleSyncIn(0, displayName);
        }
        return profit;
    }

    public List<Transaction> getUnAckedTransactions(String displayName) {
        return cachedUnAckedTransactions.computeIfAbsent(displayName, (k) -> Persistance.loadUnAckedTransactions(displayName));
    }

    public synchronized void scheduleSyncIn(int seconds, String displayName) {
        AtomicBoolean scheduled = transactionSyncScheduled.computeIfAbsent(displayName, k -> new AtomicBoolean(false));
        if(scheduled.compareAndSet(false, true)) {
            log.info("scheduling {} attempt to sync {} transactions in {}s", displayName, getUnAckedTransactions(displayName).size(), seconds);
            executorService.schedule(() ->  {
                this.syncUnAckedTransactions(displayName);
            }, seconds, TimeUnit.SECONDS);
        } else {
            log.debug("skipping scheduling sync as already scheduled");
        }
    }
}
