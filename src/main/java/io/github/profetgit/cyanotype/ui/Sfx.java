package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.Cyanotype;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * The mod's own interface sounds (assets/cyanotype/sounds, made by dev/sounds/make.py). Played as UI sounds at a quiet
 * level; the Sounds setting scales them, and 0 silences them. A sound plays on the state change, not when the animation
 * that goes with it ends (PRD 6b).
 */
public final class Sfx {
    /** The names in sounds.json. */
    public static final String HOVER = "ui_hover", PRESS = "ui_press", RELEASE = "ui_release", OPEN = "ui_open", CLOSE = "ui_close",
        WHEEL_TICK = "wheel_tick", LOCK = "lock", SNAP = "snap", COMPLETE = "complete", ERROR = "error";

    /** Our sounds are normalised to the same peak; this brings them to about the loudness of vanilla's button click. */
    private static final float BASE = 0.55f;
    private static final Map<String, SoundEvent> EVENTS = new HashMap<>();

    private Sfx() {
    }

    public static void play(String name) {
        play(name, 1f, 1f);
    }

    public static void play(String name, float pitch) {
        play(name, pitch, 1f);
    }

    public static void play(String name, float pitch, float volume) {
        float v = volume * BASE * Settings.get().sounds;
        if (v <= 0.001f) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() == null) return;
        // not registered in the game's sound registry: the sound manager finds it by its id in sounds.json
        SoundEvent e = EVENTS.computeIfAbsent(name, n -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, n)));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(e, pitch, v));
    }
}
