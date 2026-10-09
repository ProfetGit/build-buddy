package io.github.profetgit.buildbuddy;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Registers vanilla's blocks and detects the game version once, the way the game does before it loads anything. */
public final class TestBootstrap {
    private static boolean done;

    private TestBootstrap() {
    }

    public static synchronized void init() {
        if (done) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        done = true;
    }
}
