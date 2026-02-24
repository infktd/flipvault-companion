package com.flipvault.plugin.ui;

import com.flipvault.plugin.controller.AuthController;
import com.flipvault.plugin.model.AuthState;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

public class LoginPanel extends JPanel {
    private static final Color DISCORD_BLURPLE = new Color(88, 101, 242);

    private final AuthController authController;
    private final JTextField emailField;
    private final JPasswordField passwordField;
    private final JButton loginButton;
    private final JLabel errorLabel;
    private final JLabel statusLabel;

    // Browser auth components
    private final JButton browserLoginButton;
    private final JLabel browserStatusLabel;
    private final JButton cancelBrowserButton;
    private final JLabel separatorLabel;
    private final JPanel emailPasswordPanel;

    public LoginPanel(AuthController authController) {
        this.authController = authController;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(new EmptyBorder(20, 10, 20, 10));

        // Title
        JLabel title = new JLabel("Welcome to FlipVault");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(20));

        // Discord login button
        browserLoginButton = new JButton("Login with Discord");
        browserLoginButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        browserLoginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        browserLoginButton.setBackground(DISCORD_BLURPLE);
        browserLoginButton.setForeground(Color.WHITE);
        browserLoginButton.setFocusPainted(false);
        browserLoginButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        browserLoginButton.addActionListener(e -> authController.loginViaBrowser());
        add(browserLoginButton);
        add(Box.createVerticalStrut(8));

        // Browser auth status (hidden by default)
        browserStatusLabel = new JLabel("Waiting for browser login...");
        browserStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        browserStatusLabel.setFont(FontManager.getRunescapeSmallFont());
        browserStatusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        browserStatusLabel.setVisible(false);
        add(browserStatusLabel);
        add(Box.createVerticalStrut(4));

        // Cancel browser auth button (hidden by default)
        cancelBrowserButton = new JButton("Cancel");
        cancelBrowserButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        cancelBrowserButton.setVisible(false);
        cancelBrowserButton.addActionListener(e -> authController.cancelBrowserAuth());
        add(cancelBrowserButton);
        add(Box.createVerticalStrut(12));

        // Separator
        separatorLabel = new JLabel("--- or ---");
        separatorLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        separatorLabel.setFont(FontManager.getRunescapeSmallFont());
        separatorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(separatorLabel);
        add(Box.createVerticalStrut(12));

        // Email/password panel (can be hidden during browser auth)
        emailPasswordPanel = new JPanel();
        emailPasswordPanel.setLayout(new BoxLayout(emailPasswordPanel, BoxLayout.Y_AXIS));
        emailPasswordPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        emailPasswordPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Email
        JLabel emailLabel = new JLabel("Email:");
        emailLabel.setForeground(Color.WHITE);
        emailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        emailPasswordPanel.add(emailLabel);
        emailPasswordPanel.add(Box.createVerticalStrut(4));

        emailField = new JTextField();
        emailField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        emailField.setAlignmentX(Component.LEFT_ALIGNMENT);
        emailPasswordPanel.add(emailField);
        emailPasswordPanel.add(Box.createVerticalStrut(12));

        // Password
        JLabel passLabel = new JLabel("Password:");
        passLabel.setForeground(Color.WHITE);
        passLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        emailPasswordPanel.add(passLabel);
        emailPasswordPanel.add(Box.createVerticalStrut(4));

        passwordField = new JPasswordField();
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        passwordField.setAlignmentX(Component.LEFT_ALIGNMENT);
        passwordField.addActionListener(e -> doLogin());
        emailPasswordPanel.add(passwordField);
        emailPasswordPanel.add(Box.createVerticalStrut(16));

        // Error label (hidden by default)
        errorLabel = new JLabel();
        errorLabel.setForeground(new Color(255, 107, 107));
        errorLabel.setFont(FontManager.getRunescapeSmallFont());
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorLabel.setVisible(false);
        emailPasswordPanel.add(errorLabel);
        emailPasswordPanel.add(Box.createVerticalStrut(8));

        // Status label (for loading state)
        statusLabel = new JLabel();
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setVisible(false);
        emailPasswordPanel.add(statusLabel);

        // Login button
        loginButton = new JButton("Login");
        loginButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        loginButton.addActionListener(e -> doLogin());
        emailPasswordPanel.add(loginButton);
        emailPasswordPanel.add(Box.createVerticalStrut(20));

        // Signup link
        JLabel signupLabel = new JLabel("<html>Don't have an account?<br><font color='#22d3ee'>flipvault.app/signup</font></html>");
        signupLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        signupLabel.setFont(FontManager.getRunescapeSmallFont());
        signupLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        signupLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        emailPasswordPanel.add(signupLabel);

        add(emailPasswordPanel);
    }

    private void doLogin() {
        String email = emailField.getText().trim();
        String password = new String(passwordField.getPassword());
        if (email.isEmpty() || password.isEmpty()) {
            showError("Please enter email and password");
            return;
        }
        errorLabel.setVisible(false);
        loginButton.setEnabled(false);
        statusLabel.setText("Logging in...");
        statusLabel.setVisible(true);
        authController.login(email, password);
    }

    public void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        loginButton.setEnabled(true);
        statusLabel.setVisible(false);
        browserLoginButton.setEnabled(true);
        browserStatusLabel.setVisible(false);
        cancelBrowserButton.setVisible(false);
        separatorLabel.setVisible(true);
        emailPasswordPanel.setVisible(true);
    }

    public void reset() {
        emailField.setText("");
        passwordField.setText("");
        errorLabel.setVisible(false);
        loginButton.setEnabled(true);
        statusLabel.setVisible(false);
        browserLoginButton.setEnabled(true);
        browserStatusLabel.setVisible(false);
        cancelBrowserButton.setVisible(false);
        separatorLabel.setVisible(true);
        emailPasswordPanel.setVisible(true);
    }

    public void onAuthStateChanged(AuthState state) {
        switch (state) {
            case LOGGING_IN:
                loginButton.setEnabled(false);
                browserLoginButton.setEnabled(false);
                statusLabel.setText("Logging in...");
                statusLabel.setVisible(true);
                errorLabel.setVisible(false);
                break;
            case POLLING_BROWSER:
                browserLoginButton.setEnabled(false);
                browserStatusLabel.setVisible(true);
                cancelBrowserButton.setVisible(true);
                separatorLabel.setVisible(false);
                emailPasswordPanel.setVisible(false);
                errorLabel.setVisible(false);
                break;
            case ERROR:
                showError(authController.getErrorMessage());
                break;
            case NO_KEY:
                reset();
                break;
            case EXPIRED:
                showError(authController.getErrorMessage().isEmpty()
                    ? "Session expired. Please log in again."
                    : authController.getErrorMessage());
                break;
            default:
                break;
        }
    }
}
