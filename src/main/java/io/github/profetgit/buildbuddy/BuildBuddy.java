package io.github.profetgit.buildbuddy;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BuildBuddy implements ClientModInitializer {
    public static final String MOD_ID = "buildbuddy";
    public static final Logger LOG = LoggerFactory.getLogger("Build Buddy");

    @Override
    public void onInitializeClient() {
        io.github.profetgit.buildbuddy.ui.Settings.load();
        LOG.info("Build Buddy ready");
    }
}
