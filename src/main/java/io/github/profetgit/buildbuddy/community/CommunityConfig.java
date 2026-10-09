package io.github.profetgit.buildbuddy.community;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Where the community site is and whether the player has agreed to talk to it. The address is a setting; this class only
 * judges what was typed there. Plain http is accepted for this machine only (a local test site): everything else must be
 * https, so a downloaded schematic cannot be swapped on the way.
 */
public final class CommunityConfig {
    /** The public site. Empty until the site has a host: with no address the tab says so and sends nothing. */
    public static final String DEFAULT_BASE_URL = "";
    /** The dev server of the community project (`bun run dev` in ~/Projects/cyanotype-community). */
    public static final String LOCAL_TEST_URL = "http://localhost:3150";

    public static final String ASK = "ask", ON = "on", OFF = "off";

    private CommunityConfig() {
    }

    /**
     * The address in its plain form (no trailing slash), or null when it is empty or cannot be used.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost();
        if (host == null || host.isEmpty() || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) return null;
        if (!scheme.equals("https") && !(scheme.equals("http") && isLoopback(host))) return null;
        String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
        if (path.contains("..") || path.contains("//")) return null;
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + (u.getPort() >= 0 ? ":" + u.getPort() : "") + path;
    }

    public static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.endsWith(".localhost") || h.equals("127.0.0.1") || h.startsWith("127.") && h.matches("127(\\.\\d{1,3}){3}") || h.equals("[::1]") || h.equals("::1");
    }

    /** The address to talk to: the player's own when set, else the default; null when neither is usable. */
    public static String effective(String configured) {
        String own = configured == null ? "" : configured.trim();
        return normalize(own.isEmpty() ? DEFAULT_BASE_URL : own);
    }

    /** The User-Agent for a mod version: "BuildBuddy/0.0.14" (the build metadata after a plus sign is dropped, anything odd becomes a dash). */
    public static String userAgentFor(String version) {
        String v = version == null ? "" : version.split("\\+", 2)[0].replaceAll("[^0-9A-Za-z.\\-]", "-");
        return "BuildBuddy/" + (v.isEmpty() ? "dev" : v);
    }

    /** The part of the address a player recognises ("localhost:3150", "buildbuddy.example"). */
    public static String hostOf(String base) {
        try {
            URI u = new URI(base);
            return u.getHost() + (u.getPort() >= 0 ? ":" + u.getPort() : "");
        } catch (URISyntaxException e) {
            return base;
        }
    }
}
