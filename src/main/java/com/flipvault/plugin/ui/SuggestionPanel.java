package com.flipvault.plugin.ui;

import com.flipvault.plugin.controller.SuggestionController;
import com.flipvault.plugin.model.Suggestion;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
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
    private JLabel itemNameLabel;
    private JLabel actionBadge;
    private JLabel priceQtyLabel;
    private JLabel profitLabel;
    private JLabel gpHrLabel;
    private JTextArea reasonArea;

    // Collect / cancel labels
    private JLabel collectLabel;
    private JLabel cancelLabel;

    private Runnable refreshCallback;

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

    public void setRefreshCallback(Runnable callback) {
        this.refreshCallback = callback;
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

            gpHrLabel.setText("Est. GP/hr: " + formatGp(suggestion.getEstimatedGpPerHour()));

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

        // Header row: item name + action badge
        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        itemNameLabel = new JLabel();
        itemNameLabel.setFont(FontManager.getRunescapeBoldFont());
        itemNameLabel.setForeground(COLOR_BUY);
        headerRow.add(itemNameLabel, BorderLayout.WEST);

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
        panel.add(Box.createVerticalStrut(2));

        // GP/hr
        gpHrLabel = new JLabel();
        gpHrLabel.setForeground(Color.WHITE);
        gpHrLabel.setFont(FontManager.getRunescapeSmallFont());
        gpHrLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(gpHrLabel);
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

        // Bottom buttons
        panel.add(buildButtonBar());

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
        panel.add(Box.createVerticalStrut(16));

        JButton refreshBtn = createRefreshButton();
        refreshBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(refreshBtn);

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
        panel.add(Box.createVerticalStrut(16));

        JButton retryBtn = new JButton("Retry");
        retryBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        retryBtn.addActionListener(e -> {
            if (refreshCallback != null) {
                refreshCallback.run();
            }
        });
        panel.add(retryBtn);

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
        panel.add(Box.createVerticalStrut(16));

        JButton refreshBtn = createRefreshButton();
        refreshBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(refreshBtn);

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
        panel.add(Box.createVerticalStrut(16));

        JButton refreshBtn = createRefreshButton();
        refreshBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(refreshBtn);

        return panel;
    }

    private JPanel buildButtonBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Dashboard link
        JLabel dashLink = new JLabel("Open FlipVault \u2192");
        dashLink.setForeground(COLOR_CYAN);
        dashLink.setFont(FontManager.getRunescapeSmallFont());
        dashLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dashLink.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel linkWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        linkWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        linkWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        linkWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        linkWrapper.add(dashLink);
        bar.add(linkWrapper);
        bar.add(Box.createVerticalStrut(6));

        // Refresh button
        JButton refreshBtn = createRefreshButton();
        refreshBtn.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel btnWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        btnWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        btnWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        btnWrapper.add(refreshBtn);
        bar.add(btnWrapper);

        return bar;
    }

    private JButton createRefreshButton() {
        JButton btn = new JButton("Refresh");
        btn.addActionListener(e -> {
            if (refreshCallback != null) {
                refreshCallback.run();
            }
        });
        return btn;
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
