package com.flipvault.plugin.ui;

import com.flipvault.plugin.controller.SuggestionController;
import com.flipvault.plugin.model.Suggestion;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

public class SuggestionPanel extends JPanel implements SuggestionController.SuggestionListener {
    private static final Color COLOR_BUY = new Color(56, 176, 0);
    private static final Color COLOR_SELL = new Color(255, 107, 107);
    private static final Color COLOR_MUTED = new Color(139, 139, 139);
    private static final Color COLOR_CYAN = new Color(34, 211, 238);

    private static final String CARD_LOADING = "loading";
    private static final String CARD_SUGGESTION = "suggestion";
    private static final String CARD_WAIT = "wait";
    private static final String CARD_ERROR = "error";
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_COLLECT = "collect";
    private static final String CARD_CANCEL = "cancel";

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel;

    // Suggestion display labels
    private JLabel itemIconLabel;
    private JLabel itemNameLabel;
    private JLabel actionBadge;
    private JLabel priceQtyLabel;
    private JLabel profitLabel;
    private JTextArea reasonArea;

    // Collect / cancel labels
    private JLabel collectLabel;
    private JLabel cancelLabel;

    // Auto-fill feedback
    private JLabel filledLabel;

    public SuggestionPanel() {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARKER_GRAY_COLOR);

        cardPanel = new JPanel(cardLayout);
        cardPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        cardPanel.add(buildLoadingPanel(), CARD_LOADING);
        cardPanel.add(buildSuggestionPanel(), CARD_SUGGESTION);
        cardPanel.add(buildWaitPanel(), CARD_WAIT);
        cardPanel.add(buildErrorPanel(), CARD_ERROR);
        cardPanel.add(buildEmptyPanel(), CARD_EMPTY);
        cardPanel.add(buildCollectPanel(), CARD_COLLECT);
        cardPanel.add(buildCancelPanel(), CARD_CANCEL);

        add(cardPanel, BorderLayout.CENTER);

        // Start on empty state
        cardLayout.show(cardPanel, CARD_EMPTY);
    }

    /**
     * Returns the item icon label so callers can use AsyncBufferedImage.addTo().
     */
    public JLabel getItemIconLabel() {
        return itemIconLabel;
    }

    /**
     * Clear the item icon (e.g. when switching away from BUY/SELL).
     */
    public void clearItemImage() {
        SwingUtilities.invokeLater(() -> {
            itemIconLabel.setIcon(null);
            revalidate();
            repaint();
        });
    }

    // ---- SuggestionListener implementation ----

    @Override
    public void onSuggestionUpdated(Suggestion suggestion) {
        if (suggestion == null) {
            cardLayout.show(cardPanel, CARD_EMPTY);
            return;
        }

        String action = suggestion.getAction();
        if ("WAIT".equals(action)) {
            cardLayout.show(cardPanel, CARD_WAIT);
        } else if ("COLLECT".equals(action)) {
            collectLabel.setText("<html>Collect your completed offers"
                + (suggestion.getSlotIndex() != null ? " (slot " + suggestion.getSlotIndex() + ")" : "")
                + "</html>");
            cardLayout.show(cardPanel, CARD_COLLECT);
        } else if ("CANCEL".equals(action)) {
            cancelLabel.setText("Cancel offer in slot " + (suggestion.getSlotIndex() != null ? suggestion.getSlotIndex() : "?"));
            cardLayout.show(cardPanel, CARD_CANCEL);
        } else {
            // BUY or SELL
            boolean isBuy = "BUY".equals(action);
            Color actionColor = isBuy ? COLOR_BUY : COLOR_SELL;

            itemNameLabel.setText(suggestion.getItemName());
            itemNameLabel.setForeground(actionColor);

            actionBadge.setText(action);
            actionBadge.setForeground(actionColor);

            priceQtyLabel.setText(formatNumber(suggestion.getPrice()) + " gp x " + formatNumber(suggestion.getQuantity()));

            profitLabel.setText("Est. profit: " + formatGp(suggestion.getEstimatedProfit()));
            profitLabel.setForeground(suggestion.getEstimatedProfit() >= 0 ? COLOR_BUY : COLOR_SELL);

            String reason = suggestion.getReason();
            if (reason != null && !reason.isEmpty()) {
                reasonArea.setText(reason);
                reasonArea.setVisible(true);
            } else {
                reasonArea.setVisible(false);
            }

            cardLayout.show(cardPanel, CARD_SUGGESTION);
        }
    }

    @Override
    public void onSuggestionError(String error) {
        // Find the error label inside the error panel and update it
        JPanel errorPanel = (JPanel) cardPanel.getComponent(3); // CARD_ERROR index
        for (java.awt.Component c : errorPanel.getComponents()) {
            if (c instanceof JLabel && "errorMessage".equals(c.getName())) {
                ((JLabel) c).setText("<html><center>" + error + "</center></html>");
                break;
            }
        }
        cardLayout.show(cardPanel, CARD_ERROR);
    }

    @Override
    public void onSuggestionLoading() {
        cardLayout.show(cardPanel, CARD_LOADING);
    }

    // ---- Panel builders ----

    private JPanel buildLoadingPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(40, 10, 40, 10));

        JLabel label = new JLabel("Loading suggestion...");
        label.setForeground(COLOR_MUTED);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(label);

        return panel;
    }

    private JPanel buildSuggestionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Header row: [icon] [itemName] ... [actionBadge]
        JPanel headerRow = new JPanel(new BorderLayout(4, 0));
        headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        itemIconLabel = new JLabel();
        itemIconLabel.setPreferredSize(new Dimension(36, 32));
        headerRow.add(itemIconLabel, BorderLayout.WEST);

        itemNameLabel = new JLabel();
        itemNameLabel.setFont(FontManager.getRunescapeBoldFont());
        itemNameLabel.setForeground(COLOR_BUY);
        headerRow.add(itemNameLabel, BorderLayout.CENTER);

        actionBadge = new JLabel();
        actionBadge.setFont(FontManager.getRunescapeBoldFont());
        actionBadge.setForeground(COLOR_BUY);
        actionBadge.setHorizontalAlignment(SwingConstants.RIGHT);
        headerRow.add(actionBadge, BorderLayout.EAST);

        panel.add(headerRow);
        panel.add(Box.createVerticalStrut(4));

        // Price x Quantity
        priceQtyLabel = new JLabel();
        priceQtyLabel.setForeground(Color.WHITE);
        priceQtyLabel.setFont(FontManager.getRunescapeSmallFont());
        priceQtyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(priceQtyLabel);
        panel.add(Box.createVerticalStrut(6));

        // Estimated profit
        profitLabel = new JLabel();
        profitLabel.setForeground(COLOR_BUY);
        profitLabel.setFont(FontManager.getRunescapeSmallFont());
        profitLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(profitLabel);
        panel.add(Box.createVerticalStrut(8));

        // Reason area (muted, smaller font, wrapping)
        reasonArea = new JTextArea();
        reasonArea.setFont(FontManager.getRunescapeSmallFont());
        reasonArea.setForeground(COLOR_MUTED);
        reasonArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        reasonArea.setWrapStyleWord(true);
        reasonArea.setLineWrap(true);
        reasonArea.setEditable(false);
        reasonArea.setFocusable(false);
        reasonArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        reasonArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        panel.add(reasonArea);
        panel.add(Box.createVerticalStrut(10));

        // Auto-fill feedback label (hidden by default)
        filledLabel = new JLabel("Filled!");
        filledLabel.setForeground(COLOR_CYAN);
        filledLabel.setFont(FontManager.getRunescapeBoldFont());
        filledLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        filledLabel.setVisible(false);
        panel.add(filledLabel);

        return panel;
    }

    private JPanel buildWaitPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(30, 10, 30, 10));

        JLabel label = new JLabel("Waiting for opportunities...");
        label.setForeground(COLOR_MUTED);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(label);

        return panel;
    }

    private JPanel buildErrorPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(30, 10, 30, 10));

        JLabel errorMsg = new JLabel("Something went wrong");
        errorMsg.setName("errorMessage");
        errorMsg.setForeground(COLOR_SELL);
        errorMsg.setFont(FontManager.getRunescapeSmallFont());
        errorMsg.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(errorMsg);

        return panel;
    }

    private JPanel buildEmptyPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(40, 10, 40, 10));

        JLabel label = new JLabel("<html><center>Log in and open the<br>Grand Exchange to get started</center></html>");
        label.setForeground(COLOR_MUTED);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(label);

        return panel;
    }

    private JPanel buildCollectPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(30, 10, 30, 10));

        collectLabel = new JLabel("Collect your completed offers");
        collectLabel.setForeground(COLOR_CYAN);
        collectLabel.setFont(FontManager.getRunescapeBoldFont());
        collectLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(collectLabel);

        return panel;
    }

    private JPanel buildCancelPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(30, 10, 30, 10));

        cancelLabel = new JLabel("Cancel offer in slot ?");
        cancelLabel.setForeground(COLOR_SELL);
        cancelLabel.setFont(FontManager.getRunescapeBoldFont());
        cancelLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(cancelLabel);

        return panel;
    }

    // ---- Auto-fill feedback ----

    /**
     * Show "Filled!" text that fades after 2 seconds.
     * Called from the plugin when auto-fill succeeds.
     */
    public void showAutoFillFeedback(boolean success) {
        SwingUtilities.invokeLater(() -> {
            if (success) {
                filledLabel.setText("Filled!");
                filledLabel.setForeground(COLOR_CYAN);
            } else {
                filledLabel.setText("Can't fill \u2014 open a GE offer first");
                filledLabel.setForeground(COLOR_SELL);
            }
            filledLabel.setVisible(true);
            revalidate();
            repaint();

            // Hide after 2 seconds
            Timer timer = new Timer(2000, e -> {
                filledLabel.setVisible(false);
                revalidate();
                repaint();
            });
            timer.setRepeats(false);
            timer.start();
        });
    }

    // ---- Formatting helpers ----

    private String formatGp(long amount) {
        if (Math.abs(amount) >= 1_000_000) {
            return String.format("%+.1fM", amount / 1_000_000.0);
        } else if (Math.abs(amount) >= 1_000) {
            return String.format("%+.0fK", amount / 1_000.0);
        }
        return String.format("%+d", amount);
    }

    private String formatNumber(long value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        } else if (value >= 1_000) {
            return String.format("%,.0f", (double) value);
        }
        return String.valueOf(value);
    }
}
