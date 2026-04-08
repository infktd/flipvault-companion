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

        cardPanel.add(createLoadingPanel(), "loading");

        JPanel errorPanel = new JPanel(new BorderLayout());
        errorPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        cardPanel.add(errorPanel, "error");

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
        JPanel errorPanel = (JPanel) cardPanel.getComponent(1);
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
        JPanel managementPanel = (JPanel) cardPanel.getComponent(2);
        managementPanel.removeAll();
        managementPanel.setLayout(new BorderLayout());
        managementPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        managementPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Header
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        int slots = status.getPremiumInstancesCount();
        JLabel title = new JLabel("You have " + slots + " account slot" + (slots != 1 ? "s" : ""));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        headerPanel.add(title);
        headerPanel.add(Box.createRigidArea(new Dimension(0, 15)));
        managementPanel.add(headerPanel, BorderLayout.NORTH);

        // Key rows with dropdowns
        instanceDropdowns.clear();

        JPanel scrollContent = new JPanel();
        scrollContent.setLayout(new BoxLayout(scrollContent, BoxLayout.Y_AXIS));
        scrollContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        List<String> rsnOptions = status.getAvailableDisplayNames();

        for (int i = 0; i < status.getPremiumInstancesCount(); i++) {
            JPanel row = new JPanel();
            row.setLayout(new FlowLayout(FlowLayout.LEFT));
            row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));

            JLabel label = new JLabel("Account " + (i + 1) + ":");
            label.setForeground(Color.WHITE);
            label.setPreferredSize(new Dimension(130, 25));
            row.add(label);

            JComboBox<String> dropdown = new JComboBox<>();
            dropdown.setPreferredSize(new Dimension(150, 25));
            dropdown.addItem("Unassigned");

            String currentRsn = null;
            if (i < status.getCurrentlyAssignedDisplayNames().size()) {
                currentRsn = status.getCurrentlyAssignedDisplayNames().get(i);
            }

            if (currentRsn != null && !currentRsn.isEmpty()) {
                dropdown.addItem(currentRsn);
                dropdown.setSelectedIndex(1);
            }

            if (rsnOptions != null) {
                for (String rsn : rsnOptions) {
                    if (!rsn.equals(currentRsn)) {
                        dropdown.addItem(rsn);
                    }
                }
            }

            row.add(dropdown);
            instanceDropdowns.add(dropdown);
            scrollContent.add(row);
            scrollContent.add(Box.createRigidArea(new Dimension(0, 5)));
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

        // Bottom: save button
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> {
            this.showLoading();
            Consumer<PremiumInstanceStatus> c = (s) -> {
                SwingUtilities.invokeLater(() -> {
                    if (s.getLoadingError() != null && !s.getLoadingError().isEmpty()) {
                        this.showError(s.getLoadingError());
                    } else {
                        this.showManagementView(s);
                        suggestionManager.setSuggestionNeeded(true);
                    }
                });
            };
            List<String> assignments = new ArrayList<>();
            for (JComboBox<String> dd : instanceDropdowns) {
                String selected = (String) dd.getSelectedItem();
                if (selected != null && !selected.equals("Unassigned") && !assignments.contains(selected)) {
                    assignments.add(selected);
                } else {
                    assignments.add("Unassigned");
                }
            }
            apiRequestHandler.asyncUpdatePremiumInstances(c, assignments);
        });
        bottomPanel.add(saveButton, BorderLayout.EAST);

        managementPanel.add(bottomPanel, BorderLayout.SOUTH);
        cardLayout.show(cardPanel, "management");
    }
}
