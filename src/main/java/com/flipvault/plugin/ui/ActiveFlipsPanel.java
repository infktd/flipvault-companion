package com.flipvault.plugin.ui;

import com.flipvault.plugin.model.GESlotState;
import com.flipvault.plugin.model.SlotStatus;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.Map;
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
    private static final Color COLOR_SELL = new Color(255, 107, 107);
    private static final Color COLOR_MUTED = new Color(139, 139, 139);
    private static final Color COLOR_CYAN = new Color(34, 211, 238);

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
        emptyPanel = new JPanel(new BorderLayout());
        emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        emptyPanel.setBorder(new EmptyBorder(40, 10, 40, 10));
        JLabel emptyLabel = new JLabel("No active offers");
        emptyLabel.setForeground(COLOR_MUTED);
        emptyLabel.setFont(FontManager.getRunescapeSmallFont());
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyPanel.add(emptyLabel, BorderLayout.CENTER);

        // Wrapper that holds flips + total
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        wrapper.add(flipsContainer, BorderLayout.NORTH);

        // Total P&L footer
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.setBorder(new EmptyBorder(8, 10, 8, 10));

        JLabel totalLabel = new JLabel("Invested");
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

    /**
     * Update with current GE slot states and item name lookup.
     */
    public void update(GESlotState[] slots, Map<Integer, String> itemNames) {
        flipsContainer.removeAll();

        if (slots == null) {
            showEmpty();
            return;
        }

        int activeCount = 0;
        long totalInvested = 0;

        for (GESlotState slot : slots) {
            if (slot == null || slot.getStatus() == SlotStatus.EMPTY || slot.getStatus() == SlotStatus.CANCELLED) {
                continue;
            }
            activeCount++;
            String name = itemNames != null ? itemNames.getOrDefault(slot.getItemId(), "Item " + slot.getItemId()) : "Item " + slot.getItemId();
            flipsContainer.add(buildSlotCard(slot, name));
            totalInvested += (long) slot.getPrice() * slot.getTotalQuantity();
        }

        if (activeCount == 0) {
            showEmpty();
            return;
        }

        emptyPanel.setVisible(false);
        totalPnlLabel.setText(formatGp(totalInvested));
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

    private JPanel buildSlotCard(GESlotState slot, String itemName) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARK_GRAY_COLOR);
        card.setBorder(new EmptyBorder(8, 10, 8, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        boolean isBuy = slot.getStatus() == SlotStatus.BUYING || slot.getStatus() == SlotStatus.BUY_COMPLETE;
        Color statusColor = isBuy ? COLOR_BUY : COLOR_SELL;

        // Top row: item name + slot index
        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel nameLabel = new JLabel(itemName);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(FontManager.getRunescapeBoldFont());
        topRow.add(nameLabel, BorderLayout.WEST);

        JLabel slotLabel = new JLabel("Slot " + slot.getSlotIndex());
        slotLabel.setForeground(COLOR_MUTED);
        slotLabel.setFont(FontManager.getRunescapeSmallFont());
        slotLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        topRow.add(slotLabel, BorderLayout.EAST);

        card.add(topRow);
        card.add(Box.createVerticalStrut(2));

        // Bottom row: status + price x qty / filled
        JPanel bottomRow = new JPanel(new BorderLayout());
        bottomRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        bottomRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
        bottomRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        String statusText = formatStatus(slot.getStatus());
        String detail = formatNumber(slot.getPrice()) + " gp x " + formatNumber(slot.getTotalQuantity());
        if (slot.getQuantityFilled() > 0 && slot.getQuantityFilled() < slot.getTotalQuantity()) {
            detail += " (" + slot.getQuantityFilled() + "/" + slot.getTotalQuantity() + ")";
        }

        JLabel statusLabel = new JLabel(statusText + "  " + detail);
        statusLabel.setForeground(statusColor);
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

    private String formatStatus(SlotStatus status) {
        switch (status) {
            case BUYING: return "BUYING";
            case BUY_COMPLETE: return "BOUGHT";
            case SELLING: return "SELLING";
            case SELL_COMPLETE: return "SOLD";
            default: return status.name();
        }
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
}
