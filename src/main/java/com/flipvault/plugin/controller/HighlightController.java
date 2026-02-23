package com.flipvault.plugin.controller;

import com.flipvault.plugin.model.Suggestion;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

@Slf4j
@Singleton
public class HighlightController extends Overlay {
    private static final Color HIGHLIGHT_BUY = new Color(56, 176, 0, 180);
    private static final Color HIGHLIGHT_SELL = new Color(255, 107, 107, 180);
    private static final Color HIGHLIGHT_ACT = new Color(34, 211, 238, 180);

    @Inject
    private Client client;

    @Setter
    private Suggestion currentSuggestion;

    // Callbacks for auto-fill visual feedback
    @Setter
    private Runnable onAutoFillSuccess;
    @Setter
    private Runnable onAutoFillFailure;

    public HighlightController() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (currentSuggestion == null) {
            return null;
        }

        if (!isGEOpen()) {
            return null;
        }

        String action = currentSuggestion.getAction();
        if (action == null) {
            return null;
        }

        switch (action) {
            case "BUY":
                highlightForBuy(graphics);
                break;
            case "SELL":
                highlightForSell(graphics);
                break;
            case "COLLECT":
                highlightForCollect(graphics);
                break;
            case "CANCEL":
                highlightForCancel(graphics);
                break;
            // WAIT - no highlighting
            default:
                break;
        }

        return null;
    }

    private void highlightForBuy(Graphics2D g) {
        // Highlight empty GE slots
        highlightEmptySlots(g, HIGHLIGHT_BUY);
        // Highlight buy button if on offer screen
        highlightBuyButton(g);
        // Highlight confirm button if setting up offer
        highlightConfirmButton(g, HIGHLIGHT_BUY);
    }

    private void highlightForSell(Graphics2D g) {
        // Highlight sell button
        highlightSellButton(g);
        // Highlight confirm button
        highlightConfirmButton(g, HIGHLIGHT_SELL);
    }

    private void highlightForCollect(Graphics2D g) {
        // Highlight completed offer slots
        highlightCompletedSlots(g);
    }

    private void highlightForCancel(Graphics2D g) {
        if (currentSuggestion.getSlotIndex() != null) {
            highlightSpecificSlot(g, currentSuggestion.getSlotIndex(), HIGHLIGHT_ACT);
        }
    }

    private void highlightEmptySlots(Graphics2D g, Color color) {
        // GE offer containers - widget group 465
        // Each slot is a child widget within the offer container
        for (int slot = 0; slot < 8; slot++) {
            Widget slotWidget = getGESlotWidget(slot);
            if (slotWidget != null && isSlotEmpty(slot)) {
                highlightWidget(g, slotWidget, color);
            }
        }
    }

    private void highlightCompletedSlots(Graphics2D g) {
        for (int slot = 0; slot < 8; slot++) {
            Widget slotWidget = getGESlotWidget(slot);
            if (slotWidget != null && isSlotCompleted(slot)) {
                highlightWidget(g, slotWidget, HIGHLIGHT_ACT);
            }
        }
    }

    private void highlightSpecificSlot(Graphics2D g, int slotIndex, Color color) {
        Widget slotWidget = getGESlotWidget(slotIndex);
        if (slotWidget != null) {
            highlightWidget(g, slotWidget, color);
        }
    }

    private void highlightBuyButton(Graphics2D g) {
        // The buy button widget in the GE interface
        // This is within group 465, look for the "Buy" button child
        Widget buyButton = client.getWidget(465, 2); // Buy button approximate child
        if (buyButton != null && !buyButton.isHidden()) {
            highlightWidget(g, buyButton, HIGHLIGHT_BUY);
        }
    }

    private void highlightSellButton(Graphics2D g) {
        Widget sellButton = client.getWidget(465, 3); // Sell button approximate child
        if (sellButton != null && !sellButton.isHidden()) {
            highlightWidget(g, sellButton, HIGHLIGHT_SELL);
        }
    }

    private void highlightConfirmButton(Graphics2D g, Color color) {
        // Confirm button in offer setup
        Widget confirmButton = client.getWidget(465, 27); // Approximate child ID
        if (confirmButton != null && !confirmButton.isHidden()) {
            highlightWidget(g, confirmButton, color);
        }
    }

    private Widget getGESlotWidget(int slot) {
        // GE offer slots are children of the offer container
        // The exact child IDs should be verified with widget inspector
        // Approximate: group 465, children 7-14 for slots 0-7
        return client.getWidget(465, 7 + slot);
    }

    private boolean isSlotEmpty(int slot) {
        // Check if the GE slot has no active offer
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers != null && slot < offers.length && offers[slot] != null) {
            return offers[slot].getState() == GrandExchangeOfferState.EMPTY;
        }
        return true;
    }

    private boolean isSlotCompleted(int slot) {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers != null && slot < offers.length && offers[slot] != null) {
            GrandExchangeOfferState state = offers[slot].getState();
            return state == GrandExchangeOfferState.BOUGHT
                || state == GrandExchangeOfferState.SOLD;
        }
        return false;
    }

    private boolean isGEOpen() {
        Widget geWindow = client.getWidget(WidgetInfo.GRAND_EXCHANGE_WINDOW_CONTAINER);
        return geWindow != null && !geWindow.isHidden();
    }

    private void highlightWidget(Graphics2D g, Widget widget, Color color) {
        if (widget == null || widget.isHidden()) {
            return;
        }
        Rectangle bounds = widget.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            return;
        }

        // Semi-transparent fill
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80));
        g.fill(bounds);

        // Solid border
        g.setColor(color);
        g.setStroke(new BasicStroke(2));
        g.draw(bounds);
    }

    /**
     * Auto-fill the suggested price and quantity into the GE offer setup screen.
     * Must be called on the ClientThread.
     */
    public void autoFill() {
        if (currentSuggestion == null) {
            log.debug("No suggestion to auto-fill");
            return;
        }

        String action = currentSuggestion.getAction();
        if (!"BUY".equals(action) && !"SELL".equals(action)) {
            log.debug("Cannot auto-fill for action: {}", action);
            return;
        }

        if (!isGEOfferSetupOpen()) {
            log.debug("GE offer setup not open");
            if (onAutoFillFailure != null) {
                onAutoFillFailure.run();
            }
            return;
        }

        try {
            // Set price via widget text manipulation
            // The exact approach depends on RuneLite's widget API
            // These widget IDs are approximate and need verification with widget inspector
            int price = currentSuggestion.getPrice();
            int quantity = currentSuggestion.getQuantity();

            log.debug("Auto-filling: price={}, quantity={}", price, quantity);

            // Approach: Use client script to set price/quantity
            // RuneLite's GE plugin uses similar patterns
            // ScriptID values for GE offer setup may vary
            // For now, attempt widget text manipulation as a fallback

            Widget priceInput = client.getWidget(465, 24); // Price input widget (approximate)
            if (priceInput != null) {
                priceInput.setText(String.valueOf(price));
            }

            Widget qtyInput = client.getWidget(465, 25); // Quantity input widget (approximate)
            if (qtyInput != null) {
                qtyInput.setText(String.valueOf(quantity));
            }

            log.info("Auto-filled price={} qty={}", price, quantity);

            if (onAutoFillSuccess != null) {
                onAutoFillSuccess.run();
            }
        } catch (Exception e) {
            log.warn("Auto-fill failed: {}", e.getMessage());
        }
    }

    private boolean isGEOfferSetupOpen() {
        // Check if the offer setup screen is visible (not just the GE list)
        Widget offerSetup = client.getWidget(465, 20); // Offer setup container (approximate)
        return offerSetup != null && !offerSetup.isHidden();
    }
}
