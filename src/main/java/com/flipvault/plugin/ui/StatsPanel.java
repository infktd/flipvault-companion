package com.flipvault.plugin.ui;

import com.flipvault.plugin.model.SessionStats;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

public class StatsPanel extends JPanel {
    private static final Color COLOR_BUY = new Color(56, 176, 0);
    private static final Color COLOR_SELL = new Color(255, 107, 107);
    private static final Color COLOR_MUTED = new Color(139, 139, 139);

    private final JLabel profitValue;
    private final JLabel flipsDoneValue;
    private final JLabel winRateValue;
    private final JLabel avgMarginValue;
    private final JLabel gpHrValue;
    private final JLabel timeActiveValue;

    public StatsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Title
        JLabel title = new JLabel("Session Stats");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(12));

        // Stats grid
        JPanel grid = new JPanel(new GridLayout(6, 2, 4, 8));
        grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        grid.setBorder(new EmptyBorder(10, 12, 10, 12));
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));

        // Total Profit
        grid.add(createStatLabel("Total Profit"));
        profitValue = createStatValue("+0");
        profitValue.setForeground(COLOR_BUY);
        grid.add(profitValue);

        // Flips Done
        grid.add(createStatLabel("Flips Done"));
        flipsDoneValue = createStatValue("0");
        grid.add(flipsDoneValue);

        // Win Rate
        grid.add(createStatLabel("Win Rate"));
        winRateValue = createStatValue("0%");
        grid.add(winRateValue);

        // Avg Margin
        grid.add(createStatLabel("Avg Margin"));
        avgMarginValue = createStatValue("0%");
        grid.add(avgMarginValue);

        // GP/hr Avg
        grid.add(createStatLabel("GP/hr Avg"));
        gpHrValue = createStatValue("0");
        grid.add(gpHrValue);

        // Time Active
        grid.add(createStatLabel("Time Active"));
        timeActiveValue = createStatValue("0h 00m");
        grid.add(timeActiveValue);

        add(grid);

        // Spacer to push content to top
        add(Box.createVerticalGlue());
    }

    public void update(SessionStats stats) {
        if (stats == null) {
            return;
        }

        profitValue.setText(stats.getFormattedProfit());
        profitValue.setForeground(stats.getTotalProfit() >= 0 ? COLOR_BUY : COLOR_SELL);

        flipsDoneValue.setText(String.valueOf(stats.getFlipsDone()));

        winRateValue.setText(String.format("%.0f%%", stats.getWinRate()));

        avgMarginValue.setText(String.format("%.1f%%", stats.getAvgMarginPercent()));

        gpHrValue.setText(formatGp(stats.getGpPerHour()));

        timeActiveValue.setText(stats.getTimeActive());
    }

    private JLabel createStatLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(COLOR_MUTED);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    private JLabel createStatValue(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(FontManager.getRunescapeBoldFont());
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        return label;
    }

    private String formatGp(long amount) {
        if (Math.abs(amount) >= 1_000_000) {
            return String.format("%.1fM", amount / 1_000_000.0);
        } else if (Math.abs(amount) >= 1_000) {
            return String.format("%.0fK", amount / 1_000.0);
        }
        return String.valueOf(amount);
    }
}
