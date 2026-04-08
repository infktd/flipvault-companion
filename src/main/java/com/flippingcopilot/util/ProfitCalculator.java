package com.flippingcopilot.util;

import com.flippingcopilot.controller.ItemController;
import com.flippingcopilot.model.*;
import com.flippingcopilot.rs.FVLoginRS;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.HashSet;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ProfitCalculator {
    private static final int GE_SLOT_COUNT = 8;
    private final static int MAX_PRICE_FOR_GE_TAX = 250000000;
    private final static int GE_TAX_CAP = 5000000;
    private final static double GE_TAX = 0.02;
    private final static int GE_TAX_FREE_THRESHOLD = 50; // Items < 50 GP have 0 tax (rounds down to 0)
    private final static HashSet<Integer> GE_TAX_EXEMPT_ITEMS = new HashSet<>(
            Arrays.asList(
                    // Low-level food
                    233, 315, 329, 347, 351, 355, 361, 365, 379, 1891, 2140, 2142, 2309, 2327,
                    // Low-level ammo + Mind rune
                    558, 806, 807, 808, 882, 884, 886,
                    // Tools
                    952, 1733, 1735, 1755, 1785, 2347, 5325, 5329, 5331, 5341, 5343, 8794,
                    // Teleport tablets
                    8007, 8008, 8009, 8010, 8011, 8013, 28790, 28824,
                    // Jewelry + potions
                    2552, 3853,  // Ring of dueling(8), Games necklace(8)
                    3008, 3010, 3012, 3014,  // Energy potions
                    // Bonds
                    13190));

    private final Client client;
    private final OfferManager offerManager;
    private final FlipManager flipManager;
    private final OsrsLoginManager osrsLoginManager;
    private final FVLoginRS fvLoginRS;
    private final ItemController itemController;

    /**
     * Calculates the post-tax price for an item.
     */
    public static int getPostTaxPrice(int itemId, int price) {
        return price - getTaxAmount(itemId, price);
    }

    /**
     * Calculates the GE tax amount for an item.
     */
    public static int getTaxAmount(int itemId, int price) {
        if (GE_TAX_EXEMPT_ITEMS.contains(itemId)) {
            return 0;
        }
        if (price < GE_TAX_FREE_THRESHOLD) {
            return 0; // 2% of <50 rounds down to 0
        }
        if (price >= MAX_PRICE_FOR_GE_TAX) {
            return GE_TAX_CAP;
        }
        return (int)Math.floor(price * GE_TAX);
    }

    /**
     * Calculates the profit per item for a sell at the given price.
     * 
     * @param itemId The item ID
     * @param sellPrice The price to sell at
     * @param avgBuyPrice The average buy price
     * @return The profit per item in GP (post-tax)
     */
    public static long calculateProfitPerItem(int itemId, int sellPrice, long avgBuyPrice) {
        return getPostTaxPrice(itemId, sellPrice) - avgBuyPrice;
    }

    /**
     * Calculates the profit for a sell offer based on the buy price from a flip.
     * 
     * @param offer The sell offer
     * @param flip The flip containing the average buy price
     * @return The profit in GP (post-tax)
     */
    public static long calculateOfferProfit(SavedOffer offer, FlipV2 flip) {
        long profitPerItem = calculateProfitPerItem(offer.getItemId(), offer.getPrice(), flip.getAvgBuyPrice());
        return profitPerItem * offer.getTotalQuantity();
    }

    /**
     * Calculates the profit for a sell offer in a specific GE slot.
     * Only calculates profit for SELL offers.
     * 
     * @param slotIndex The GE slot index (0-7)
     * @return The profit in GP, or null if cannot be calculated
     */
    public Long calculateSlotProfit(int slotIndex) {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            return null;
        }

        long accountHash = client.getAccountHash();
        SavedOffer offer = offerManager.loadOffer(accountHash, slotIndex);
        
        if (offer == null || !offer.getOfferStatus().equals(OfferStatus.SELL)) {
            return null;
        }

        Integer accountId = fvLoginRS.get().getAccountId(displayName);
        if (accountId == null || accountId == -1) {
            return null;
        }

        FlipV2 flip = flipManager.getLastFlipByItemId(accountId, offer.getItemId());
        if (flip == null || FlipStatus.FINISHED.equals(flip.getStatus())) {
            return null;
        }

        return calculateOfferProfit(offer, flip);
    }

    /**
     * Calculates the profit for a suggested sell offer.
     * Used for sell suggestions before the offer is actually placed.
     * 
     * @param suggestion The sell suggestion
     * @return The profit in GP, or null if cannot be calculated
     */
    public Long calculateSuggestionProfit(Suggestion suggestion) {
        if (!suggestion.isSellSuggestion()) {
            return null;
        }

        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            return null;
        }

        Integer accountId = fvLoginRS.get().getAccountId(displayName);
        if (accountId == null || accountId == -1) {
            return null;
        }

        // Create a transaction from the suggestion to estimate profit
        Transaction t = new Transaction();
        t.setItemId(suggestion.getItemId());
        t.setPrice(suggestion.getPrice());
        t.setQuantity(suggestion.getQuantity());
        t.setAmountSpent(suggestion.getPrice() * suggestion.getQuantity());
        t.setType(OfferStatus.SELL);

        return flipManager.estimateTransactionProfit(accountId, t);
    }

    /**
     * Calculates the profit per item for a given item and price for the current player.
     * This is useful for determining profitability before placing an offer.
     * Only calculates for flips that are not yet finished.
     * 
     * @param itemId The item ID
     * @param sellPrice The price to sell at
     * @return Profit per item in GP (post-tax revenue minus buy price), or null if cannot be calculated
     */
    public Long calculateProfitPerItem(int itemId, int sellPrice) {
        if (sellPrice <= 0) {
            return null;
        }

        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            return null;
        }

        // Try server-side flip data first
        Integer accountId = fvLoginRS.get().getAccountId(displayName);
        if (accountId != null && accountId != -1) {
            FlipV2 flip = flipManager.getLastFlipByItemId(accountId, itemId);
            if (flip != null && !FlipStatus.FINISHED.equals(flip.getStatus())) {
                long avgBuyPrice = flip.getAvgBuyPrice();
                if (avgBuyPrice > 0) {
                    return calculateProfitPerItem(itemId, sellPrice, avgBuyPrice);
                }
            }
        }

        // Fall back to local buy tracking
        Long localAvgBuy = flipManager.getLocalAvgBuyPrice(displayName, itemId);
        if (localAvgBuy != null && localAvgBuy > 0) {
            return calculateProfitPerItem(itemId, sellPrice, localAvgBuy);
        }

        return null;
    }

    /**
     * Finds the profit for a sell offer by item name.
     * Searches through all GE slots to find a matching sell offer.
     * 
     * @param itemName The item name to search for
     * @return The profit in GP, or 0 if not found
     */
    public long getProfitByItemName(String itemName) {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            return 0;
        }

        Integer accountId = fvLoginRS.get().getAccountId(displayName);
        long accountHash = client.getAccountHash();

        for (int slotIndex = 0; slotIndex < GE_SLOT_COUNT; slotIndex++) {
            SavedOffer offer = offerManager.loadOffer(accountHash, slotIndex);
            if (offer == null || !offer.getOfferStatus().equals(OfferStatus.SELL)) {
                continue;
            }

            // Try server flip data
            if (accountId != null && accountId != -1) {
                FlipV2 flip = flipManager.getLastFlipByItemId(accountId, offer.getItemId());
                if (flip != null && !FlipStatus.FINISHED.equals(flip.getStatus())
                        && flip.getCachedItemName().equals(itemName)) {
                    return calculateOfferProfit(offer, flip);
                }
            }

            // Fall back to local buy tracking for this item
            String offerItemName = itemController.getItemName(offer.getItemId());
            if (!itemName.equals(offerItemName)) {
                continue;
            }
            Long localAvgBuy = flipManager.getLocalAvgBuyPrice(displayName, offer.getItemId());
            if (localAvgBuy != null && localAvgBuy > 0) {
                int postTaxSellPrice = getPostTaxPrice(offer.getItemId(), offer.getPrice());
                long profitPerItem = postTaxSellPrice - localAvgBuy;
                return profitPerItem * offer.getTotalQuantity();
            }
        }

        return 0;
    }
}
