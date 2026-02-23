package com.flipvault.plugin.ui;

import com.flipvault.plugin.model.ActiveFlip;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

public class ActiveFlipsPanel extends JPanel {
    private static final Color COLOR_BUY = new Color(56, 176, 0);
    private static final Color COLOR_MUTED = new Color(139, 139, 139);

    private final JPanel flipsContainer;
    private final JLabel totalPnlLabel;
    private final JPanel emptyPanel;

    public ActiveFlipsPanel() {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Scrollable flips area
        flipsContainer = new JPanel();
        flipsContainer.setLayout(new BoxLayout(flipsContainer, BoxLayout.Y_AXIS));
        flipsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Empty state
        emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        emptyPanel.setBorder(new EmptyBorder(40, 10, 40, 10));
        JLabel emptyLabel = new JLabel("No active flips");
        emptyLabel.setForeground(COLOR_MUTED);
        emptyLabel.setFont(FontManager.getRunescapeSmallFont());
        emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyPanel.add(emptyLabel);

        // Wrapper that holds flips + total
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        wrapper.add(flipsContainer, BorderLayout.NORTH);

        // Total P&L footer
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.setBorder(new EmptyBorder(8, 10, 8, 10));

        JLabel totalLabel = new JLabel("Unrealized P&L");
        totalLabel.setForeground(COLOR_MUTED);
        totalLabel.setFont(FontManager.getRunescapeSmallFont());
        footer.add(totalLabel, BorderLayout.WEST);

        totalPnlLabel = new JLabel("--");
        totalPnlLabel.setForeground(Color.WHITE);
        totalPnlLabel.setFont(FontManager.getRunescapeBoldFont());
        totalPnlLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        footer.add(totalPnlLabel, BorderLayout.EAST);

        add(wrapper, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        // Start empty
        showEmpty();
    }

    public void update(List<ActiveFlip> flips) {
        flipsContainer.removeAll();

        if (flips == null || flips.isEmpty()) {
            showEmpty();
            return;
        }

        emptyPanel.setVisible(false);

        for (ActiveFlip flip : flips) {
            flipsContainer.add(buildFlipCard(flip));
        }

        // We don't have real-time sell prices in Phase 1, so just show buy totals
        long totalBuyValue = 0;
        for (ActiveFlip flip : flips) {
            totalBuyValue += (long) flip.getBuyPrice() * flip.getQuantity();
        }
        totalPnlLabel.setText("Invested: " + formatGp(totalBuyValue));
        totalPnlLabel.setForeground(Color.WHITE);

        revalidate();
        repaint();
    }

    private void showEmpty() {
        flipsContainer.removeAll();
        flipsContainer.add(emptyPanel);
        emptyPanel.setVisible(true);
        totalPnlLabel.setText("--");
        totalPnlLabel.setForeground(Color.WHITE);
        revalidate();
        repaint();
    }

    private JPanel buildFlipCard(ActiveFlip flip) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARK_GRAY_COLOR);
        card.setBorder(new EmptyBorder(8, 10, 8, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        // Top row: item name + time since buy
        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel nameLabel = new JLabel(flip.getItemName());
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(FontManager.getRunescapeBoldFont());
        topRow.add(nameLabel, BorderLayout.WEST);

        String elapsed = formatElapsed(System.currentTimeMillis() - flip.getBoughtAt());
        JLabel timeLabel = new JLabel(elapsed);
        timeLabel.setForeground(COLOR_MUTED);
        timeLabel.setFont(FontManager.getRunescapeSmallFont());
        timeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        topRow.add(timeLabel, BorderLayout.EAST);

        card.add(topRow);
        card.add(Box.createVerticalStrut(2));

        // Bottom row: BUYING x qty @ price
        JPanel bottomRow = new JPanel(new BorderLayout());
        bottomRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        bottomRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
        bottomRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel statusLabel = new JLabel("BOUGHT x" + formatNumber(flip.getQuantity()) + "  " + formatNumber(flip.getBuyPrice()) + " gp");
        statusLabel.setForeground(COLOR_BUY);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        bottomRow.add(statusLabel, BorderLayout.WEST);

        card.add(bottomRow);

        // Separator space
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        wrapper.add(card, BorderLayout.CENTER);
        wrapper.setBorder(new EmptyBorder(0, 0, 2, 0));
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);

        return wrapper;
    }

    private String formatGp(long amount) {
        if (Math.abs(amount) >= 1_000_000) {
            return String.format("%.1fM", amount / 1_000_000.0);
        } else if (Math.abs(amount) >= 1_000) {
            return String.format("%.0fK", amount / 1_000.0);
        }
        return String.valueOf(amount);
    }

    private String formatNumber(long value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        } else if (value >= 10_000) {
            return String.format("%.1fK", value / 1_000.0);
        } else if (value >= 1_000) {
            return String.format("%,d", value);
        }
        return String.valueOf(value);
    }

    private String formatElapsed(long millis) {
        long minutes = millis / 60_000;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + (minutes % 60) + "m ago";
        }
        long days = hours / 24;
        return days + "d ago";
    }
}
