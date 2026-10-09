package io.github.profetgit.buildbuddy.auto;

import java.util.Locale;

/** One name for a server however the player typed its address, so a remembered choice or a block is found again. */
public final class ServerKey {
    private ServerKey() {
    }

    /**
     * Lower case, no spaces, no trailing dot, and the default port left out: "Play.Example.com.:25565" and
     * "play.example.com" are the same server. Empty for nothing.
     */
    public static String normalize(String address) {
        if (address == null) return "";
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) return "";
        String host = a, port = "";
        // an IPv6 address in brackets keeps its colons; the port is what follows the closing bracket
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end > 0) {
                host = a.substring(0, end + 1);
                if (a.length() > end + 2 && a.charAt(end + 1) == ':') port = a.substring(end + 2);
            }
        } else {
            int colon = a.lastIndexOf(':');
            if (colon > 0 && a.indexOf(':') == colon) {
                host = a.substring(0, colon);
                port = a.substring(colon + 1);
            }
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (port.equals("25565") || port.isEmpty()) return host;
        return host + ":" + port;
    }
}
