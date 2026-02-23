package com.flipvault.plugin;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("flipvault")
public interface FlipVaultConfig extends Config {

    @ConfigItem(
        keyName = "apiKey",
        name = "API Key",
        description = "Your FlipVault API key. Use the login button in the plugin panel to set up.",
        secret = true
    )
    default String apiKey() {
        return "";
    }

    @ConfigItem(
        keyName = "keyLabel",
        name = "Key Label",
        description = "Display label for the active API key"
    )
    default String keyLabel() {
        return "";
    }

    @ConfigItem(
        keyName = "boundTo",
        name = "Bound To",
        description = "OSRS player name this key is bound to"
    )
    default String boundTo() {
        return "";
    }

    @ConfigItem(
        keyName = "autoFillHotkey",
        name = "Auto-Fill Hotkey",
        description = "Hotkey to fill suggested price and quantity into GE offer (default: F5)"
    )
    default Keybind autoFillHotkey() {
        return new Keybind(KeyEvent.VK_F5, 0);
    }
}
