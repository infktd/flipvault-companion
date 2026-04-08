package com.flippingcopilot.controller;

import com.flippingcopilot.config.FlipVaultConfig;
import com.flippingcopilot.model.OsrsLoginManager;
import com.flippingcopilot.model.PremiumInstanceStatus;
import com.flippingcopilot.model.SuggestionManager;
import com.flippingcopilot.ui.PremiumInstancePanel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.util.function.Consumer;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class PremiumInstanceController {

    private final ApiRequestHandler apiRequestHandler;
    private final FlipVaultConfig fvConfig;
    private final SuggestionManager suggestionManager;
    private final OsrsLoginManager osrsLoginManager;
    private JDialog dialog;

    public void loadAndOpenPremiumInstanceDialog() {
        // Create the dialog
        if(dialog != null) {
            dialog.dispose();
        }
        dialog = new JDialog();
        dialog.setTitle("Account Management");
        dialog.setModal(false);
        dialog.setSize(450, 300);
        dialog.setLocationRelativeTo(null);

        // Create the panel
        PremiumInstancePanel panel = new PremiumInstancePanel(fvConfig, apiRequestHandler, suggestionManager);
        dialog.setContentPane(panel);

        // Show loading state
        panel.showLoading();
        Consumer<PremiumInstanceStatus> c = (status) -> {
            SwingUtilities.invokeLater(() -> {  // Make sure UI updates happen on EDT
                if (status.getLoadingError() != null && !status.getLoadingError().isEmpty()) {
                    panel.showError(status.getLoadingError());
                } else {
                    panel.showManagementView(status);
                }
            });
        };
        apiRequestHandler.asyncGetPremiumInstanceStatus(c, osrsLoginManager.getPlayerDisplayName());
        dialog.setVisible(true);
    }
}