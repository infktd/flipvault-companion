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
    private final AuthController authController;
    private final JTextField emailField;
    private final JPasswordField passwordField;
    private final JButton loginButton;
    private final JLabel errorLabel;
    private final JLabel statusLabel;

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

        // Email
        JLabel emailLabel = new JLabel("Email:");
        emailLabel.setForeground(Color.WHITE);
        emailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(emailLabel);
        add(Box.createVerticalStrut(4));

        emailField = new JTextField();
        emailField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        emailField.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(emailField);
        add(Box.createVerticalStrut(12));

        // Password
        JLabel passLabel = new JLabel("Password:");
        passLabel.setForeground(Color.WHITE);
        passLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(passLabel);
        add(Box.createVerticalStrut(4));

        passwordField = new JPasswordField();
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        passwordField.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Enter key triggers login
        passwordField.addActionListener(e -> doLogin());
        add(passwordField);
        add(Box.createVerticalStrut(16));

        // Error label (hidden by default)
        errorLabel = new JLabel();
        errorLabel.setForeground(new Color(255, 107, 107));
        errorLabel.setFont(FontManager.getRunescapeSmallFont());
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorLabel.setVisible(false);
        add(errorLabel);
        add(Box.createVerticalStrut(8));

        // Status label (for loading state)
        statusLabel = new JLabel();
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setVisible(false);
        add(statusLabel);

        // Login button
        loginButton = new JButton("Login");
        loginButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        loginButton.addActionListener(e -> doLogin());
        add(loginButton);
        add(Box.createVerticalStrut(20));

        // Signup link
        JLabel signupLabel = new JLabel("<html>Don't have an account?<br><font color='#22d3ee'>flipvault.app/signup</font></html>");
        signupLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        signupLabel.setFont(FontManager.getRunescapeSmallFont());
        signupLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        signupLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        add(signupLabel);
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
    }

    public void reset() {
        emailField.setText("");
        passwordField.setText("");
        errorLabel.setVisible(false);
        loginButton.setEnabled(true);
        statusLabel.setVisible(false);
    }

    public void onAuthStateChanged(AuthState state) {
        switch (state) {
            case LOGGING_IN:
                loginButton.setEnabled(false);
                statusLabel.setText("Logging in...");
                statusLabel.setVisible(true);
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
