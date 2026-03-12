package com.flippingcopilot;

import com.flippingcopilot.controller.FlipVaultPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class FlipVaultPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FlipVaultPlugin.class);
		RuneLite.main(args);
	}
}