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
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.VarPlayer;
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

    // GE offer container (465, 26) child indices — verified from Flipping Copilot
    private static final int CHILD_QTY_BUTTON = 51;
    private static final int CHILD_PRICE_BUTTON = 54;
    private static final int CHILD_CONFIRM_BUTTON = 58;

    // Varbit IDs for reading offer state
    private static final int VARBIT_OFFER_QUANTITY = 4396;
    private static final int VARBIT_OFFER_PRICE = 4398;

    @Inject
    private Client client;

    @Setter
    private Suggestion currentSuggestion;

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
        if (currentSuggestion == null || !isGEOpen()) {
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
            default:
                break;
        }

        return null;
    }

    // ---- BUY flow ----

    private void highlightForBuy(Graphics2D g) {
        if (isOnHomeScreen()) {
            highlightFirstEmptyBuyButton(g);
        } else if (isGEOfferSetupOpen()) {
            highlightOfferScreen(g, HIGHLIGHT_BUY);
        }
    }

    private void highlightFirstEmptyBuyButton(Graphics2D g) {
        int firstEmpty = findFirstEmptySlot();
        if (firstEmpty < 0) {
            return;
        }

        Widget slotWidget = getGESlotWidget(firstEmpty);
        if (slotWidget == null) {
            return;
        }

        Widget buyButton = slotWidget.getChild(0);
        if (buyButton != null && !buyButton.isHidden()) {
            highlightWidget(g, buyButton, HIGHLIGHT_BUY);
        }
    }

    // ---- SELL flow ----

    private void highlightForSell(Graphics2D g) {
        if (isGEOfferSetupOpen()) {
            highlightOfferScreen(g, HIGHLIGHT_SELL);
        }
        // Inventory item highlighting deferred — needs inventory overlay
    }

    // ---- Shared offer screen highlighting (BUY + SELL) ----

    /**
     * Highlight the appropriate buttons on the GE offer setup screen based on
     * whether the current price/quantity match the suggestion. Uses varbits
     * (not widget text) for reliable real-time state reading.
     */
    private void highlightOfferScreen(Graphics2D g, Color color) {
        int currentItem = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);

        if (currentItem == -1) {
            // No item selected yet — highlight search result if search is open
            if ("BUY".equals(currentSuggestion.getAction())) {
                int inputType = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
                if (inputType == 14) {
                    highlightSearchResult(g);
                }
            }
            return;
        }

        if (currentItem != currentSuggestion.getItemId()) {
            return; // Wrong item selected — don't highlight
        }

        int offerPrice = client.getVarbitValue(VARBIT_OFFER_PRICE);
        int offerQty = client.getVarbitValue(VARBIT_OFFER_QUANTITY);
        boolean priceCorrect = offerPrice == currentSuggestion.getPrice();
        boolean qtyCorrect = offerQty == currentSuggestion.getQuantity();

        if (priceCorrect && qtyCorrect) {
            // Both match — highlight confirm
            highlightButton(g, CHILD_CONFIRM_BUTTON, color);
        } else {
            // Highlight whichever doesn't match
            if (!priceCorrect) {
                highlightButton(g, CHILD_PRICE_BUTTON, color);
            }
            if (!qtyCorrect) {
                highlightButton(g, CHILD_QTY_BUTTON, color);
            }
        }
    }

    private void highlightButton(Graphics2D g, int childIndex, Color color) {
        Widget offerContainer = client.getWidget(465, 26);
        if (offerContainer == null) {
            return;
        }
        Widget btn = offerContainer.getChild(childIndex);
        if (btn != null && !btn.isHidden()) {
            highlightWidget(g, btn, color);
        }
    }

    private void highlightSearchResult(Graphics2D g) {
        if (currentSuggestion == null) {
            return;
        }

        String inputText = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
        if (inputText != null && !inputText.isEmpty()) {
            return; // user is typing — don't highlight our injected result
        }

        Widget searchResults = client.getWidget(162, 51);
        if (searchResults == null || searchResults.isHidden()) {
            return;
        }

        String expectedName = "<col=ff9040>" + currentSuggestion.getItemName() + "</col>";

        // Check dynamic children first (created from scratch when no previous search)
        Widget[] dynamicChildren = searchResults.getChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                if (child != null && expectedName.equals(child.getName())) {
                    highlightWidget(g, child, HIGHLIGHT_BUY);
                    return;
                }
            }
        }

        // Fallback: check static child(0) (mutated previous search)
        Widget clickable = searchResults.getChild(0);
        if (clickable != null && expectedName.equals(clickable.getName())) {
            highlightWidget(g, clickable, HIGHLIGHT_BUY);
        }
    }

    // ---- COLLECT flow ----

    private void highlightForCollect(Graphics2D g) {
        highlightCompletedSlots(g);

        Widget collectContainer = client.getWidget(465, 6);
        if (collectContainer != null) {
            Widget collectButton = collectContainer.getChild(2);
            if (collectButton != null && !collectButton.isHidden()) {
                highlightWidget(g, collectButton, HIGHLIGHT_ACT);
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

    // ---- CANCEL flow ----

    private void highlightForCancel(Graphics2D g) {
        if (currentSuggestion.getSlotIndex() != null) {
            highlightSpecificSlot(g, currentSuggestion.getSlotIndex(), HIGHLIGHT_ACT);
        }
    }

    private void highlightSpecificSlot(Graphics2D g, int slotIndex, Color color) {
        Widget slotWidget = getGESlotWidget(slotIndex);
        if (slotWidget != null) {
            highlightWidget(g, slotWidget, color);
        }
    }

    // ---- Chatbox item injection ----

    /**
     * Inject the suggested item into the GE search chatbox as a clickable result.
     * Branches based on whether the player has a previous search set:
     * - If yes: mutate the existing static children of the search results widget
     * - If no: create new dynamic children from scratch
     * Must be called on the ClientThread.
     */
    public void showSuggestedItemInSearch() {
        if (currentSuggestion == null || !"BUY".equals(currentSuggestion.getAction())) {
            return;
        }

        String inputText = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
        if (inputText != null && !inputText.isEmpty()) {
            return; // user typed something — don't override
        }

        Widget searchResults = client.getWidget(162, 51);
        if (searchResults == null) {
            return;
        }

        int itemId = currentSuggestion.getItemId();
        String itemName = currentSuggestion.getItemName();

        if (isPreviousSearchSet() && isShowLastSearchEnabled()) {
            // Previous search exists — mutate the static children
            Widget previousSearch = searchResults.getChild(0);
            if (previousSearch == null) {
                return;
            }
            previousSearch.setOnOpListener(754, itemId, 84);
            previousSearch.setOnKeyListener(754, itemId, -2147483640);
            previousSearch.setName("<col=ff9040>" + itemName + "</col>");

            Widget labelWidget = searchResults.getChild(1);
            if (labelWidget != null) {
                labelWidget.setText("FlipVault item:");
            }

            Widget nameWidget = searchResults.getChild(2);
            if (nameWidget != null) {
                nameWidget.setText(itemName);
            }

            Widget itemWidget = searchResults.getChild(3);
            if (itemWidget != null) {
                itemWidget.setItemId(itemId);
            }

            log.debug("Mutated previous search for: {} (id={})", itemName, itemId);
        } else {
            // No previous search — create new dynamic children
            createSearchResultWidgets(searchResults, itemId, itemName);
            log.debug("Created search result widgets for: {} (id={})", itemName, itemId);
        }
    }

    private void createSearchResultWidgets(Widget parent, int itemId, String itemName) {
        // Clickable rectangle (matches Flipping Copilot's widget setup)
        Widget rect = parent.createChild(-1, 3); // WidgetType.RECTANGLE
        rect.setTextColor(0xFFFFFF);
        rect.setOpacity(255);
        rect.setName("<col=ff9040>" + itemName + "</col>");
        rect.setFilled(true);
        rect.setOriginalX(114);
        rect.setOriginalY(0);
        rect.setOriginalWidth(256);
        rect.setOriginalHeight(32);
        rect.setOnOpListener(754, itemId, 84);
        rect.setOnKeyListener(754, itemId, -2147483640);
        rect.setAction(0, "Select");
        rect.setHasListener(true);
        rect.revalidate();

        // Item name text
        Widget nameWidget = parent.createChild(-1, 4); // WidgetType.TEXT
        nameWidget.setText(itemName);
        nameWidget.setFontId(495);
        nameWidget.setOriginalX(254);
        nameWidget.setOriginalY(0);
        nameWidget.setOriginalWidth(116);
        nameWidget.setOriginalHeight(32);
        nameWidget.setYTextAlignment(1);
        nameWidget.revalidate();

        // Item graphic/sprite
        Widget itemWidget = parent.createChild(-1, 5); // WidgetType.GRAPHIC
        itemWidget.setItemId(itemId);
        itemWidget.setItemQuantity(1);
        itemWidget.setItemQuantityMode(0);
        itemWidget.setRotationX(550);
        itemWidget.setModelZoom(1031);
        itemWidget.setBorderType(1);
        itemWidget.setOriginalX(214);
        itemWidget.setOriginalY(0);
        itemWidget.setOriginalWidth(36);
        itemWidget.setOriginalHeight(32);
        itemWidget.revalidate();

        // "FlipVault item:" label
        Widget label = parent.createChild(-1, 4); // WidgetType.TEXT
        label.setText("FlipVault item:");
        label.setFontId(495);
        label.setOriginalX(114);
        label.setOriginalY(0);
        label.setOriginalWidth(95);
        label.setOriginalHeight(32);
        label.setYTextAlignment(1);
        label.revalidate();
    }

    private boolean isPreviousSearchSet() {
        return client.getVarpValue(2674) != -1;
    }

    private boolean isShowLastSearchEnabled() {
        return client.getVarbitValue(10295) == 0;
    }

    // ---- Auto-fill via chatbox input ----

    /**
     * Auto-fill the suggested price or quantity into the GE chatbox input.
     * Reads the chatbox title widget (162, 42) to determine which input is open:
     * - "How many do you wish to buy/sell?" → fills quantity
     * - "Set a price for each item:" → fills price
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

        int inputType = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
        if (inputType != 7) {
            log.debug("No numeric input chatbox open (inputType={})", inputType);
            if (onAutoFillFailure != null) {
                onAutoFillFailure.run();
            }
            return;
        }

        // CHATBOX_TITLE is widget 162, child 42 (confirmed from ComponentID)
        Widget chatboxTitle = client.getWidget(162, 42);
        if (chatboxTitle == null) {
            log.debug("Chatbox title widget not found");
            if (onAutoFillFailure != null) {
                onAutoFillFailure.run();
            }
            return;
        }

        String titleText = chatboxTitle.getText();
        int value;
        if ("How many do you wish to buy?".equals(titleText)
                || "How many do you wish to sell?".equals(titleText)) {
            value = currentSuggestion.getQuantity();
            log.debug("Auto-filling quantity: {}", value);
        } else if ("Set a price for each item:".equals(titleText)) {
            value = currentSuggestion.getPrice();
            log.debug("Auto-filling price: {}", value);
        } else {
            log.debug("Unknown chatbox title: '{}'", titleText);
            if (onAutoFillFailure != null) {
                onAutoFillFailure.run();
            }
            return;
        }

        // CHATBOX_FULL_INPUT is widget 162, child 43
        Widget chatboxInput = client.getWidget(162, 43);
        if (chatboxInput != null) {
            chatboxInput.setText(value + "*");
            client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(value));
            log.info("Auto-filled value: {}", value);
            if (onAutoFillSuccess != null) {
                onAutoFillSuccess.run();
            }
        } else {
            if (onAutoFillFailure != null) {
                onAutoFillFailure.run();
            }
        }
    }

    // ---- Helpers ----

    private int findFirstEmptySlot() {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        for (int i = 0; i < 8; i++) {
            if (offers == null || offers[i] == null
                    || offers[i].getState() == GrandExchangeOfferState.EMPTY) {
                return i;
            }
        }
        return -1;
    }

    private boolean isOnHomeScreen() {
        return client.getVarbitValue(4439) == 0;
    }

    private Widget getGESlotWidget(int slot) {
        return client.getWidget(465, 7 + slot);
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

    private boolean isGEOfferSetupOpen() {
        Widget offerContainer = client.getWidget(465, 26);
        return offerContainer != null && !offerContainer.isHidden();
    }

    private void highlightWidget(Graphics2D g, Widget widget, Color color) {
        if (widget == null || widget.isHidden()) {
            return;
        }
        Rectangle bounds = widget.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            return;
        }

        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80));
        g.fill(bounds);

        g.setColor(color);
        g.setStroke(new BasicStroke(2));
        g.draw(bounds);
    }
}
