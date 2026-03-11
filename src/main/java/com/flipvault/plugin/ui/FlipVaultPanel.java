package com.flipvault.plugin.ui;

import com.flipvault.plugin.controller.AuthController;
import com.flipvault.plugin.model.AuthState;
import com.flipvault.plugin.model.SessionStats;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

public class FlipVaultPanel extends PluginPanel implements AuthController.AuthStateListener {
    private static final Color COLOR_BUY = new Color(56, 176, 0);
    private static final Color COLOR_SELL = new Color(255, 107, 107);
    private static final Color COLOR_MUTED = new Color(139, 139, 139);
    private static final Color COLOR_CYAN = new Color(34, 211, 238);

    private static final String CARD_LOGIN = "login";
    private static final String CARD_KEY_SELECT = "keySelect";
    private static final String CARD_MAIN = "main";
    private static final String CARD_VALIDATING = "validating";
    private static final String CARD_CONFLICT = "conflict";
    private static final String CARD_ERROR = "error";

    private static final String TAB_SUGGEST = "suggest";
    private static final String TAB_ACTIVE = "active";
    private static final String TAB_STATS = "stats";

    // Content cards (auth-aware)
    private final CardLayout mainCardLayout = new CardLayout();
    private final JPanel mainCardPanel;

    // Tab content cards
    private final CardLayout tabCardLayout = new CardLayout();
    private final JPanel tabCardPanel;

    // Tab buttons
    private JButton suggestTab;
    private JButton activeTab;
    private JButton statsTab;
    private String activeTabName = TAB_SUGGEST;

    // Sub-panels
    private final LoginPanel loginPanel;
    private final KeySelectionPanel keySelectionPanel;
    private final SuggestionPanel suggestionPanel;
    private final ActiveFlipsPanel activeFlipsPanel;
    private final StatsPanel statsPanel;

    // Header elements
    private JLabel sessionProfitLabel;
    private JLabel sessionFlipsLabel;
    private JLabel sessionTimeLabel;
    private JLabel connectionStatusLabel;
    private JLabel versionLabel;
    private JLabel logoutLabel;
    private JLabel premiumLabel;

    // Live session timer
    private Timer sessionTimer;
    private volatile SessionStats lastStats;

    // Auth controller reference for conflict panel
    private final AuthController authController;

    public FlipVaultPanel(AuthController authController,
                          LoginPanel loginPanel,
                          KeySelectionPanel keySelectionPanel,
                          SuggestionPanel suggestionPanel,
                          ActiveFlipsPanel activeFlipsPanel,
                          StatsPanel statsPanel) {
        super(false); // no wrapping
        this.authController = authController;
        this.loginPanel = loginPanel;
        this.keySelectionPanel = keySelectionPanel;
        this.suggestionPanel = suggestionPanel;
        this.activeFlipsPanel = activeFlipsPanel;
        this.statsPanel = statsPanel;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // NORTH: header + session bar + tab bar
        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        northPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        northPanel.add(buildHeader());
        northPanel.add(buildSessionBar());
        northPanel.add(buildTabBar());
        add(northPanel, BorderLayout.NORTH);

        // CENTER: main card panel (auth state switching)
        mainCardPanel = new JPanel(mainCardLayout);
        mainCardPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Login card
        JScrollPane loginScroll = wrapInScrollPane(loginPanel);
        mainCardPanel.add(loginScroll, CARD_LOGIN);

        // Key selection card
        JScrollPane keySelectScroll = wrapInScrollPane(keySelectionPanel);
        mainCardPanel.add(keySelectScroll, CARD_KEY_SELECT);

        // Validating card
        mainCardPanel.add(buildValidatingPanel(), CARD_VALIDATING);

        // Conflict card
        mainCardPanel.add(buildConflictPanel(), CARD_CONFLICT);

        // Error card
        mainCardPanel.add(buildErrorPanel(), CARD_ERROR);

        // Main content card (tabs)
        tabCardPanel = new JPanel(tabCardLayout);
        tabCardPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JScrollPane suggestScroll = wrapInScrollPane(suggestionPanel);
        tabCardPanel.add(suggestScroll, TAB_SUGGEST);

        JScrollPane activeScroll = wrapInScrollPane(activeFlipsPanel);
        tabCardPanel.add(activeScroll, TAB_ACTIVE);

        JScrollPane statsScroll = wrapInScrollPane(statsPanel);
        tabCardPanel.add(statsScroll, TAB_STATS);

        mainCardPanel.add(tabCardPanel, CARD_MAIN);

        add(mainCardPanel, BorderLayout.CENTER);

        // SOUTH: footer
        add(buildFooter(), BorderLayout.SOUTH);

        // Default state
        mainCardLayout.show(mainCardPanel, CARD_LOGIN);

        // Live session timer — updates time label every second
        sessionTimer = new Timer(1000, e -> {
            SessionStats stats = lastStats;
            if (stats != null && stats.getSessionStartTime() > 0) {
                sessionTimeLabel.setText(stats.getTimeActive());
            }
        });
        sessionTimer.start();
    }

    // ---- AuthStateListener implementation ----

    @Override
    public void onAuthStateChanged(AuthState newState) {
        // Show logout when authenticated, validating, or selecting key
        logoutLabel.setVisible(newState == AuthState.VALID
            || newState == AuthState.VALIDATING
            || newState == AuthState.SELECTING_KEY
            || newState == AuthState.KEY_CONFLICT);

        switch (newState) {
            case NO_KEY:
            case EXPIRED:
                mainCardLayout.show(mainCardPanel, CARD_LOGIN);
                loginPanel.onAuthStateChanged(newState);
                break;
            case LOGGING_IN:
            case POLLING_BROWSER:
                loginPanel.onAuthStateChanged(newState);
                break;
            case SELECTING_KEY:
                mainCardLayout.show(mainCardPanel, CARD_KEY_SELECT);
                keySelectionPanel.onAuthStateChanged(newState);
                break;
            case VALIDATING:
                mainCardLayout.show(mainCardPanel, CARD_VALIDATING);
                break;
            case VALID:
                updatePlanBadge(authController.getPlan());
                mainCardLayout.show(mainCardPanel, CARD_MAIN);
                break;
            case KEY_CONFLICT:
                updateConflictPanel();
                mainCardLayout.show(mainCardPanel, CARD_CONFLICT);
                keySelectionPanel.onAuthStateChanged(newState);
                break;
            case ERROR:
                mainCardLayout.show(mainCardPanel, CARD_ERROR);
                loginPanel.onAuthStateChanged(newState);
                break;
        }
    }

    // ---- Public update methods ----

    public void updateSessionStats(SessionStats stats) {
        if (stats == null) {
            return;
        }
        lastStats = stats;
        sessionProfitLabel.setText("Session: " + stats.getFormattedProfit());
        sessionProfitLabel.setForeground(stats.getTotalProfit() >= 0 ? COLOR_BUY : COLOR_SELL);
        sessionFlipsLabel.setText("Flips: " + stats.getFlipsDone());
        sessionTimeLabel.setText(stats.getTimeActive());
    }

    public void updatePlanBadge(String plan) {
        if ("pro".equalsIgnoreCase(plan) || "premium".equalsIgnoreCase(plan)) {
            premiumLabel.setText("Pro");
            premiumLabel.setForeground(COLOR_CYAN);
        } else {
            premiumLabel.setText("Free");
            premiumLabel.setForeground(COLOR_MUTED);
        }
    }

    public void setConnected(boolean connected) {
        connectionStatusLabel.setText(connected ? "\u25CF Connected" : "\u25CF Disconnected");
        connectionStatusLabel.setForeground(connected ? COLOR_BUY : COLOR_SELL);
    }

    public SuggestionPanel getSuggestionPanel() {
        return suggestionPanel;
    }

    public ActiveFlipsPanel getActiveFlipsPanel() {
        return activeFlipsPanel;
    }

    public StatsPanel getStatsPanel() {
        return statsPanel;
    }

    public KeySelectionPanel getKeySelectionPanel() {
        return keySelectionPanel;
    }

    public boolean isCurrentlyActive() {
        return isShowing();
    }

    // ---- Panel builders ----

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.setBorder(new EmptyBorder(8, 10, 8, 10));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel("FlipVault");
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(Color.WHITE);
        header.add(titleLabel, BorderLayout.WEST);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        logoutLabel = new JLabel("Logout");
        logoutLabel.setFont(FontManager.getRunescapeSmallFont());
        logoutLabel.setForeground(COLOR_MUTED);
        logoutLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        logoutLabel.setVisible(false);
        logoutLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                authController.resetToLogin();
            }
        });
        rightPanel.add(logoutLabel);

        premiumLabel = new JLabel("Free");
        premiumLabel.setFont(FontManager.getRunescapeSmallFont());
        premiumLabel.setForeground(COLOR_MUTED);
        rightPanel.add(premiumLabel);

        header.add(rightPanel, BorderLayout.EAST);

        return header;
    }

    private JPanel buildSessionBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bar.setBorder(new EmptyBorder(6, 10, 6, 10));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);

        sessionProfitLabel = new JLabel("Session: +0");
        sessionProfitLabel.setFont(FontManager.getRunescapeSmallFont());
        sessionProfitLabel.setForeground(COLOR_BUY);
        bar.add(sessionProfitLabel, BorderLayout.WEST);

        // Center: live session time
        sessionTimeLabel = new JLabel("0:00:00");
        sessionTimeLabel.setFont(FontManager.getRunescapeSmallFont());
        sessionTimeLabel.setForeground(COLOR_MUTED);
        sessionTimeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        bar.add(sessionTimeLabel, BorderLayout.CENTER);

        sessionFlipsLabel = new JLabel("Flips: 0");
        sessionFlipsLabel.setFont(FontManager.getRunescapeSmallFont());
        sessionFlipsLabel.setForeground(Color.WHITE);
        sessionFlipsLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        bar.add(sessionFlipsLabel, BorderLayout.EAST);

        return bar;
    }

    private JPanel buildTabBar() {
        JPanel bar = new JPanel(new GridLayout(1, 3, 1, 0));
        bar.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);

        suggestTab = createTabButton("Suggest", TAB_SUGGEST);
        activeTab = createTabButton("Active", TAB_ACTIVE);
        statsTab = createTabButton("Stats", TAB_STATS);

        bar.add(suggestTab);
        bar.add(activeTab);
        bar.add(statsTab);

        // Set initial active tab
        updateTabStyles();

        return bar;
    }

    private JButton createTabButton(String label, String tabName) {
        JButton btn = new JButton(label);
        btn.setFont(FontManager.getRunescapeSmallFont());
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(0, 28));

        btn.addActionListener(e -> {
            activeTabName = tabName;
            tabCardLayout.show(tabCardPanel, tabName);
            updateTabStyles();
        });

        return btn;
    }

    private void updateTabStyles() {
        styleTab(suggestTab, TAB_SUGGEST.equals(activeTabName));
        styleTab(activeTab, TAB_ACTIVE.equals(activeTabName));
        styleTab(statsTab, TAB_STATS.equals(activeTabName));
    }

    private void styleTab(JButton tab, boolean isActive) {
        if (isActive) {
            tab.setBackground(ColorScheme.DARK_GRAY_COLOR);
            tab.setForeground(Color.WHITE);
            tab.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, COLOR_CYAN));
        } else {
            tab.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            tab.setForeground(COLOR_MUTED);
            tab.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        }
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new GridLayout(1, 2));
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.setBorder(new EmptyBorder(6, 10, 6, 10));

        connectionStatusLabel = new JLabel("\u25CF Disconnected");
        connectionStatusLabel.setFont(FontManager.getRunescapeSmallFont());
        connectionStatusLabel.setForeground(COLOR_SELL);
        connectionStatusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        footer.add(connectionStatusLabel);

        versionLabel = new JLabel("v0.1.0");
        versionLabel.setFont(FontManager.getRunescapeSmallFont());
        versionLabel.setForeground(COLOR_MUTED);
        versionLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        footer.add(versionLabel);

        return footer;
    }

    private JPanel buildValidatingPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(40, 10, 40, 10));

        JLabel label = new JLabel("<html><center>Log in to a game world<br>to get started</center></html>");
        label.setForeground(COLOR_MUTED);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(label, BorderLayout.CENTER);

        return panel;
    }

    private JLabel conflictMessageLabel;
    private JPanel conflictPanel;

    private JPanel buildConflictPanel() {
        conflictPanel = new JPanel();
        conflictPanel.setLayout(new BoxLayout(conflictPanel, BoxLayout.Y_AXIS));
        conflictPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        conflictPanel.setBorder(new EmptyBorder(30, 10, 30, 10));

        JLabel title = new JLabel("Key Conflict");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(COLOR_SELL);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        conflictPanel.add(title);
        conflictPanel.add(Box.createVerticalStrut(12));

        conflictMessageLabel = new JLabel("<html><center>This key is bound to another player.</center></html>");
        conflictMessageLabel.setForeground(Color.WHITE);
        conflictMessageLabel.setFont(FontManager.getRunescapeSmallFont());
        conflictMessageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        conflictPanel.add(conflictMessageLabel);
        conflictPanel.add(Box.createVerticalStrut(16));

        // Link to key management
        JLabel manageLink = new JLabel("<html><font color='#22d3ee'>Manage keys at flipvault.app/keys</font></html>");
        manageLink.setFont(FontManager.getRunescapeSmallFont());
        manageLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        manageLink.setAlignmentX(Component.CENTER_ALIGNMENT);
        conflictPanel.add(manageLink);
        conflictPanel.add(Box.createVerticalStrut(16));

        // Switch key button
        JButton switchKeyBtn = new JButton("Switch Key");
        switchKeyBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        switchKeyBtn.addActionListener(e -> authController.resetToLogin());
        conflictPanel.add(switchKeyBtn);

        return conflictPanel;
    }

    private void updateConflictPanel() {
        String player = authController.getConflictPlayerName();
        if (player != null && !player.isEmpty()) {
            conflictMessageLabel.setText("<html><center>This key is bound to<br><b>" + player + "</b></center></html>");
        } else {
            conflictMessageLabel.setText("<html><center>This key is bound to another player.</center></html>");
        }
    }

    private JPanel buildErrorPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(30, 10, 30, 10));

        JLabel title = new JLabel("Connection Error");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(COLOR_SELL);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(12));

        JLabel msg = new JLabel("<html><center>Could not connect to FlipVault.<br>Please try again.</center></html>");
        msg.setForeground(COLOR_MUTED);
        msg.setFont(FontManager.getRunescapeSmallFont());
        msg.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(msg);
        panel.add(Box.createVerticalStrut(16));

        JButton retryBtn = new JButton("Retry");
        retryBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        retryBtn.addActionListener(e -> authController.retryValidation());
        panel.add(retryBtn);

        return panel;
    }

    private JScrollPane wrapInScrollPane(JPanel panel) {
        JScrollPane scrollPane = new JScrollPane(panel);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        return scrollPane;
    }
}
