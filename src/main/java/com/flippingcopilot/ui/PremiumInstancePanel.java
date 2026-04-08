package com.flippingcopilot.ui;

import com.flippingcopilot.controller.ApiRequestHandler;
import com.flippingcopilot.config.FlipVaultConfig;
import com.flippingcopilot.model.PremiumInstanceStatus;
import com.flippingcopilot.model.SuggestionManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class PremiumInstancePanel extends JPanel {

    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private final List<JComboBox<String>> instanceDropdowns;
    private final FlipVaultConfig config;
    private final ApiRequestHandler apiRequestHandler;
    private final SuggestionManager suggestionManager;

    public PremiumInstancePanel(FlipVaultConfig config, ApiRequestHandler apiRequestHandler, SuggestionManager suggestionManager) {
        this.config = config;
        this.apiRequestHandler = apiRequestHandler;
        this.suggestionManager = suggestionManager;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARKER_GRAY_COLOR);

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Create loading panel
        JPanel loadingPanel = createLoadingPanel();
        cardPanel.add(loadingPanel, "loading");

        // Create error panel container (will be populated when error occurs)
        JPanel errorPanel = new JPanel(new BorderLayout());
        errorPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        cardPanel.add(errorPanel, "error");

        // Create management panel container (will be populated when data loads)
        JPanel managementPanel = new JPanel(new BorderLayout());
        managementPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        cardPanel.add(managementPanel, "management");

        add(cardPanel, BorderLayout.CENTER);

        instanceDropdowns = new ArrayList<>();
    }

    private JPanel createLoadingPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 10, 0);

        Spinner spinner = new Spinner();
        spinner.show();
        panel.add(spinner, gbc);

        gbc.gridy = 1;
        JLabel loadingLabel = new JLabel("Loading API key data...");
        loadingLabel.setForeground(Color.WHITE);
        panel.add(loadingLabel, gbc);

        return panel;
    }

    public void showLoading() {
        cardLayout.show(cardPanel, "loading");
    }

    public void showError(String errorMessage) {
        JPanel errorPanel = (JPanel) cardPanel.getComponent(1); // error panel
        errorPanel.removeAll();
        errorPanel.setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(10, 10, 10, 10);

        JLabel errorLabel = new JLabel("<html><center>" + errorMessage + "</center></html>");
        errorLabel.setForeground(Color.RED);
        errorLabel.setHorizontalAlignment(SwingConstants.CENTER);
        errorPanel.add(errorLabel, gbc);

        cardLayout.show(cardPanel, "error");
    }

    public void showManagementView(PremiumInstanceStatus status) {
        JPanel managementPanel = (JPanel) cardPanel.getComponent(2); // management panel
        managementPanel.removeAll();
        managementPanel.setLayout(new BorderLayout());
        managementPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        managementPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Header
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JLabel countLabel = new JLabel(status.getPremiumInstancesCount() + " active API key" + (status.getPremiumInstancesCount() != 1 ? "s" : ""));
        countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD));
        countLabel.setForeground(Color.WHITE);
        countLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        headerPanel.add(countLabel);
        headerPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        managementPanel.add(headerPanel, BorderLayout.NORTH);

        // Key list
        JPanel scrollContent = new JPanel();
        scrollContent.setLayout(new BoxLayout(scrollContent, BoxLayout.Y_AXIS));
        scrollContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        List<String> keyEntries = status.getCurrentlyAssignedDisplayNames();
        if (keyEntries != null) {
            for (int i = 0; i < keyEntries.size(); i++) {
                JPanel row = new JPanel(new BorderLayout());
                row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                row.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
                        BorderFactory.createEmptyBorder(8, 4, 8, 4)));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

                JLabel keyLabel = new JLabel(keyEntries.get(i));
                keyLabel.setForeground(Color.WHITE);
                row.add(keyLabel, BorderLayout.CENTER);

                scrollContent.add(row);
            }
        }

        scrollContent.add(Box.createVerticalGlue());

        JScrollPane scrollPane = new JScrollPane(scrollContent);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        managementPanel.add(scrollPane, BorderLayout.CENTER);

        // Footer
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JLabel footerLabel = new JLabel("Manage keys at flipvault.app/settings");
        footerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        bottomPanel.add(footerLabel, BorderLayout.WEST);

        managementPanel.add(bottomPanel, BorderLayout.SOUTH);

        cardLayout.show(cardPanel, "management");
    }
}