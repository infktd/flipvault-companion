package com.flipvault.plugin.model;

/**
 * OSRS Grand Exchange tax calculation.
 * Tax is 2% of the total sell value, capped at 5,000,000 gp per transaction.
 * Items under 50 gp are not taxed (2% rounds down to less than 1 coin).
 */
public final class GETax {
    private static final long MAX_TAX = 5_000_000L;
    private static final double TAX_RATE = 0.02;
    private static final int MIN_TAXABLE_PRICE = 50;

    private GETax() {
    }

    /**
     * Calculate the GE tax for a sell transaction.
     *
     * @param sellPrice price per item
     * @param quantity  number of items sold
     * @return the tax amount in gp
     */
    public static long calculateTax(int sellPrice, int quantity) {
        if (sellPrice < MIN_TAXABLE_PRICE) {
            return 0;
        }
        long totalValue = (long) sellPrice * quantity;
        long tax = (long) Math.floor(totalValue * TAX_RATE);
        return Math.min(tax, MAX_TAX);
    }

    /**
     * Calculate profit for a flip after GE tax.
     *
     * @param buyPrice  price per item bought at
     * @param sellPrice price per item sold at
     * @param quantity  number of items
     * @return net profit after tax
     */
    public static long calculateProfit(int buyPrice, int sellPrice, int quantity) {
        long gross = ((long) sellPrice - buyPrice) * quantity;
        long tax = calculateTax(sellPrice, quantity);
        return gross - tax;
    }
}
