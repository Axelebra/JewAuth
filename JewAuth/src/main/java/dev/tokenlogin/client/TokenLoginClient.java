package dev.tokenlogin.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class TokenLoginClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("tokenlogin");

    @Override
    public void onInitializeClient() {
        LOGGER.info("TokenLogin initialized");

        ClientTickEvents.END_CLIENT_TICK.register(client -> SelfBan.tick());
    }
}