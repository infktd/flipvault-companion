package com.flipvault.plugin;

import java.awt.Color;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup("flipvault")
public interface FlipVaultConfig extends Config {

    // ---- Sections ----

    @ConfigSection(
        name = "FlipVault",
        description = "API key and account settings",
        position = 0
    )
    String flipvault = "flipvault";

    @ConfigSection(
        name = "Notifications",
        description = "Chat, tray, and Discord notification settings",
        position = 10
    )
    String notifications = "notifications";

    @ConfigSection(
        name = "Trading",
        description = "GE overlay and trading safety settings",
        position = 20
    )
    String trading = "trading";

    // ---- FlipVault section ----

    @ConfigItem(
        keyName = "apiKey",
        name = "API Key",
        description = "Your FlipVault API key. Use the login button in the plugin panel to set up.",
        secret = true,
        section = "flipvault",
        position = 1
    )
    default String apiKey() {
        return "";
    }

    @ConfigItem(
        keyName = "keyLabel",
        name = "Key Label",
        description = "Display label for the active API key",
        section = "flipvault",
        position = 2
    )
    default String keyLabel() {
        return "";
    }

    // ---- Hidden (used internally by AuthController) ----

    @ConfigItem(
        keyName = "boundTo",
        name = "Bound To",
        description = "OSRS player name this key is bound to",
        hidden = true
    )
    default String boundTo() {
        return "";
    }

    // ---- Notifications section ----

    @ConfigItem(
        keyName = "chatNotifications",
        name = "Chat Notifications",
        description = "Show suggestion notifications as in-game chat messages",
        section = "notifications",
        position = 11
    )
    default boolean chatNotifications() {
        return true;
    }

    @ConfigItem(
        keyName = "trayNotifications",
        name = "Tray Notifications",
        description = "Show suggestion notifications as OS tray popups",
        section = "notifications",
        position = 12
    )
    default boolean trayNotifications() {
        return true;
    }

    @ConfigItem(
        keyName = "chatTextColor",
        name = "Chat Text Color",
        description = "Color for FlipVault chat notification messages",
        section = "notifications",
        position = 13
    )
    default Color chatTextColor() {
        return new Color(0x0040FF);
    }

    @ConfigItem(
        keyName = "discordWebhookUrl",
        name = "Discord Webhook URL",
        description = "Discord webhook URL for session summary on plugin shutdown",
        section = "notifications",
        position = 14
    )
    default String discordWebhookUrl() {
        return "";
    }

    // ---- Trading section ----

    @ConfigItem(
        keyName = "autoFillHotkey",
        name = "Auto-Fill Hotkey",
        description = "Hotkey to fill suggested price and quantity into GE offer (default: F5)",
        section = "trading",
        position = 20
    )
    default Keybind autoFillHotkey() {
        return new Keybind(KeyEvent.VK_F5, 0);
    }

    @ConfigItem(
        keyName = "suggestionHighlights",
        name = "Suggestion Highlights",
        description = "Highlight GE buttons based on the current suggestion",
        section = "trading",
        position = 21
    )
    default boolean suggestionHighlights() {
        return true;
    }

    @ConfigItem(
        keyName = "misClickPrevention",
        name = "Mis-click Prevention",
        description = "Deprioritize Confirm button when offer does not match suggestion",
        section = "trading",
        position = 22
    )
    default boolean misClickPrevention() {
        return false;
    }
}
