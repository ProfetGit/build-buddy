package io.github.profetgit.buildbuddy.auto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.github.profetgit.buildbuddy.BuildBuddy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * What the player has decided about auto-placing on each multiplayer server (PRD 7.8), kept in
 * {@code config/buildbuddy/servers.json}: servers where it is allowed or declined for good, and the blocked list where it
 * can never be switched on. A block always wins. Addresses are {@linkplain ServerKey#normalize normalised}.
 */
public final class ServerRules {
    public enum Decision {
        /** On the blocked list: auto-placing cannot be turned on here. */
        BLOCKED,
        /** The player allowed it on this server and asked not to be asked again. */
        ALLOWED,
        /** The player kept it off here and asked not to be asked again. */
        DECLINED,
        /** Nothing decided yet: the disclaimer comes first. */
        ASK
    }

    /** The file's shape. */
    private static final class Data {
        Map<String, String> remembered = new LinkedHashMap<>();
        List<String> blocked = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ServerRules instance;

    private final Path file;
    private Data data = new Data();

    public ServerRules(Path file) {
        this.file = file;
        load();
    }

    /** The rules of this installation. */
    public static synchronized ServerRules get() {
        if (instance == null) instance = new ServerRules(FabricLoader.getInstance().getConfigDir().resolve("buildbuddy").resolve("servers.json"));
        return instance;
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Data read = GSON.fromJson(Files.readString(file), Data.class);
            if (read != null) {
                data = read;
                if (data.remembered == null) data.remembered = new LinkedHashMap<>();
                if (data.blocked == null) data.blocked = new ArrayList<>();
                // a hand-edited file may hold addresses in any spelling
                Set<String> blocked = new LinkedHashSet<>();
                for (String b : data.blocked) {
                    String k = ServerKey.normalize(b);
                    if (!k.isEmpty()) blocked.add(k);
                }
                data.blocked = new ArrayList<>(blocked);
                Map<String, String> remembered = new LinkedHashMap<>();
                for (var e : data.remembered.entrySet()) {
                    String k = ServerKey.normalize(e.getKey());
                    if (!k.isEmpty() && ("allowed".equals(e.getValue()) || "declined".equals(e.getValue()))) remembered.put(k, e.getValue());
                }
                data.remembered = remembered;
            }
        } catch (IOException | JsonSyntaxException e) {
            // an unreadable file is put aside rather than overwritten by the next save, so a hand-made blocked list is not lost
            BuildBuddy.LOG.warn("Cannot read {} (no server choices are remembered this time; the file is kept as .bad): {}", file, e.getMessage());
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // nothing more to do
            }
        }
    }

    public synchronized Decision decision(String address) {
        String k = ServerKey.normalize(address);
        if (k.isEmpty()) return Decision.ASK;
        if (data.blocked.contains(k)) return Decision.BLOCKED;
        String r = data.remembered.get(k);
        if ("allowed".equals(r)) return Decision.ALLOWED;
        if ("declined".equals(r)) return Decision.DECLINED;
        return Decision.ASK;
    }

    public synchronized void allow(String address) {
        remember(address, "allowed");
    }

    public synchronized void decline(String address) {
        remember(address, "declined");
    }

    private void remember(String address, String what) {
        String k = ServerKey.normalize(address);
        if (k.isEmpty()) return;
        data.remembered.put(k, what);
        save();
    }

    /** Puts a server on the blocked list (and forgets any choice made for it). */
    public synchronized void block(String address) {
        String k = ServerKey.normalize(address);
        if (k.isEmpty()) return;
        data.remembered.remove(k);
        if (!data.blocked.contains(k)) data.blocked.add(k);
        save();
    }

    public synchronized void unblock(String address) {
        if (data.blocked.remove(ServerKey.normalize(address))) save();
    }

    /** Forgets what was chosen for a server, so the disclaimer is shown again. */
    public synchronized void forget(String address) {
        if (data.remembered.remove(ServerKey.normalize(address)) != null) save();
    }

    public synchronized List<String> blocked() {
        return List.copyOf(data.blocked);
    }

    /** Servers with a remembered choice, in the order they were made: key, then "allowed" or "declined". */
    public synchronized Map<String, String> remembered() {
        return Map.copyOf(data.remembered);
    }

    public synchronized List<String> rememberedKeys() {
        return new ArrayList<>(data.remembered.keySet());
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            BuildBuddy.LOG.error("Cannot save {}", file, e);
        }
    }
}
