package com.yourname;

import net.fabricmc.api.ModInitializer;
import net.wurstclient.WurstInitializer;

public class AuthEntrypoint implements ModInitializer
{
	// Change this to true or false for testing
	private static final boolean FAIL_AUTH = false;

	@Override
	public void onInitialize()
	{
		System.out.println("AuthEntrypoint loaded.");

		if(FAIL_AUTH)
			throw new RuntimeException("Authentication failed");

		System.out.println("Authentication successful. Mod loading normally.");

		new WurstInitializer().onInitialize();
	}
}
