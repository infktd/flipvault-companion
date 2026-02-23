package com.flipvault.plugin.ui;

import com.flipvault.plugin.controller.AuthController;
import com.flipvault.plugin.model.ApiKey;
import com.flipvault.plugin.model.AuthState;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

public class KeySelectionPanel extends JPanel {
    private final AuthController authController;
    private final JPanel keysContainer;
    private final JButton activateButton;
    private final JLabel errorLabel;
    private String selectedKeyId;
    // playerName is set by the main plugin when the player logs in
    private String playerName;

    public KeySelectionPanel(AuthController authController) {
        this.authController = authController;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(new EmptyBorder(20, 10, 20, 10));

        // Title
        JLabel title = new JLabel("Select API Key:");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(12));

        // Keys container
        keysContainer = new JPanel();
        keysContainer.setLayout(new BoxLayout(keysContainer, BoxLayout.Y_AXIS));
        keysContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        keysContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(keysContainer);
        add(Box.createVerticalStrut(12));

        // Error label
        errorLabel = new JLabel();
        errorLabel.setForeground(new Color(255, 107, 107));
        errorLabel.setFont(FontManager.getRunescapeSmallFont());
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorLabel.setVisible(false);
        add(errorLabel);
        add(Box.createVerticalStrut(8));

        // Activate button
        activateButton = new JButton("Activate Key");
        activateButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        activateButton.addActionListener(e -> doActivate());
        add(activateButton);
        add(Box.createVerticalStrut(16));

        // More keys link
        JLabel moreKeysLabel = new JLabel("<html>Need more keys?<br><font color='#22d3ee'>flipvault.app/keys</font></html>");
        moreKeysLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        moreKeysLabel.setFont(FontManager.getRunescapeSmallFont());
        moreKeysLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        moreKeysLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        add(moreKeysLabel);
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public void populateKeys(List<ApiKey> keys) {
        keysContainer.removeAll();
        selectedKeyId = null;

        for (ApiKey key : keys) {
            JPanel keyCard = createKeyCard(key);
            keysContainer.add(keyCard);
            keysContainer.add(Box.createVerticalStrut(6));
        }

        // Auto-select first key
        if (!keys.isEmpty()) {
            selectedKeyId = keys.get(0).getId();
            refreshKeyCards(keys);
        }

        revalidate();
        repaint();
    }

    private JPanel createKeyCard(ApiKey key) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARK_GRAY_COLOR);
        card.setBorder(new CompoundBorder(
            new LineBorder(key.getId().equals(selectedKeyId) ? new Color(34, 211, 238) : ColorScheme.MEDIUM_GRAY_COLOR, 1),
            new EmptyBorder(8, 10, 8, 10)
        ));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel nameLabel = new JLabel(key.getLabel().isEmpty() ? "API Key" : key.getLabel());
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        card.add(nameLabel);

        JLabel maskedLabel = new JLabel(key.getMaskedKey());
        maskedLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        maskedLabel.setFont(FontManager.getRunescapeSmallFont());
        card.add(maskedLabel);

        String boundText = key.getBoundTo() != null ? "Bound: " + key.getBoundTo() : "Not bound";
        JLabel boundLabel = new JLabel(boundText);
        boundLabel.setForeground(key.getBoundTo() != null ? new Color(56, 176, 0) : ColorScheme.LIGHT_GRAY_COLOR);
        boundLabel.setFont(FontManager.getRunescapeSmallFont());
        card.add(boundLabel);

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                selectedKeyId = key.getId();
                refreshKeyCards(authController.getAvailableKeys());
            }
        });

        return card;
    }

    private void refreshKeyCards(List<ApiKey> keys) {
        keysContainer.removeAll();
        for (ApiKey key : keys) {
            keysContainer.add(createKeyCard(key));
            keysContainer.add(Box.createVerticalStrut(6));
        }
        revalidate();
        repaint();
    }

    private void doActivate() {
        if (selectedKeyId == null) {
            showError("Please select a key");
            return;
        }
        if (playerName == null || playerName.isEmpty()) {
            showError("Log into OSRS first");
            return;
        }
        errorLabel.setVisible(false);
        activateButton.setEnabled(false);
        authController.activateKey(selectedKeyId, playerName);
    }

    public void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        activateButton.setEnabled(true);
    }

    public void onAuthStateChanged(AuthState state) {
        switch (state) {
            case SELECTING_KEY:
                activateButton.setEnabled(true);
                errorLabel.setVisible(false);
                populateKeys(authController.getAvailableKeys());
                break;
            case KEY_CONFLICT:
                showError("Key bound to " + authController.getConflictPlayerName() + ". Unbind at flipvault.app/keys");
                break;
            case ERROR:
                showError(authController.getErrorMessage());
                break;
            default:
                break;
        }
    }
}
