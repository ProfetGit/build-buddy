package io.github.profetgit.cyanotype.community;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.cyanotype.blueprint.LitematicReader;
import io.github.profetgit.cyanotype.placement.BlueprintLibrary;
import io.github.profetgit.cyanotype.ui.Settings;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;

/** The game side of the Community tab: the settings, the thread pool, and the pieces the model needs from the game. */
public final class Community {
    /** Network calls get their own few threads so a slow site never holds up the threads that read blueprints. */
    private static ExecutorService pool;
    private static CommunityClient client;
    private static String clientBase;

    private Community() {
    }

    public static synchronized ExecutorService pool() {
        if (pool == null) {
            int[] n = {0};
            pool = Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "Cyanotype-community-" + n[0]++);
                t.setDaemon(true);
                return t;
            });
        }
        return pool;
    }

    /** The address in use (the player's, else the default), or null when there is none or it cannot be used. */
    public static String base() {
        return CommunityConfig.effective(Settings.get().communityUrl);
    }

    /** ask / on / off, as the player last answered. */
    public static String consent() {
        String c = Settings.get().community;
        return CommunityConfig.ON.equals(c) || CommunityConfig.OFF.equals(c) ? c : CommunityConfig.ASK;
    }

    public static void setConsent(String value) {
        Settings.get().community = value;
        Settings.changed();
    }

    public static String userAgent() {
        String v = FabricLoader.getInstance().getModContainer("cyanotype").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        return CommunityConfig.userAgentFor(v);
    }

    /** One client per address, so the cache and the rate guard live as long as the address stays the same. */
    public static synchronized CommunityClient client(String base) {
        if (client == null || !base.equals(clientBase) || client.net().closed()) {
            if (client != null) client.net().close();
            client = new CommunityClient(new Net(base, userAgent()));
            clientBase = base;
        }
        return client;
    }

    /** Stops everything still queued for the site: nothing is sent after this until a model asks for a client again. */
    public static synchronized void stop() {
        if (client != null) client.net().close();
    }

    /** How many requests the current client has really sent (the demo checks the first-run promise with it). */
    public static synchronized int requestsSent() {
        return client == null ? 0 : client.net().requests.get();
    }

    public static CommunityModel newModel(String base) {
        return new CommunityModel(client(base), pool(), Community::decode, BlueprintLibrary.ownDir(), Community::validate, System::currentTimeMillis);
    }

    /** A downloaded file must open as a blueprint before it joins the library (shapes are not worked out: only the file is judged). */
    static void validate(java.nio.file.Path file) throws java.io.IOException {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            LitematicReader.read(in, file.getFileName().toString(), false);
        }
    }

    static CommunityModel.Pixels decode(byte[] png) throws java.io.IOException {
        try (NativeImage img = NativeImage.read(png)) {
            return new CommunityModel.Pixels(img.getWidth(), img.getHeight(), img.getPixels());
        }
    }
}
