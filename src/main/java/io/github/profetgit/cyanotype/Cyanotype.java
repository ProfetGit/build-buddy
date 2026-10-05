package io.github.profetgit.cyanotype;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Cyanotype implements ClientModInitializer {
    public static final String MOD_ID = "cyanotype";
    public static final Logger LOG = LoggerFactory.getLogger("Cyanotype");

    @Override
    public void onInitializeClient() {
        LOG.info("Cyanotype ready");
    }
}
