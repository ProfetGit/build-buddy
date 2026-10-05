package io.github.profetgit.cyanotype.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The art the code asks for by name is in the atlas (a typo would otherwise draw nothing, silently). */
class AtlasTest {
    private static final Path ART = Path.of("src/main/resources/assets/cyanotype/ui");

    private static JsonObject sprites() throws IOException {
        return JsonParser.parseString(Files.readString(ART.resolve("atlas.json"))).getAsJsonObject().getAsJsonObject("sprites");
    }

    @Test
    void everyIconOfTheWheelHasBothLooks() throws IOException {
        JsonObject s = sprites();
        for (Tool t : Tool.values()) {
            assertTrue(s.has("icon_" + t.icon), t + " needs icon_" + t.icon);
            assertTrue(s.has("icon_" + t.icon + "_dark"), t + " needs icon_" + t.icon + "_dark");
        }
    }

    @Test
    void theControlsTheScreensDrawAreThere() throws IOException {
        JsonObject s = sprites();
        for (String name : List.of("panel", "inset", "button_0", "button_1", "button_2", "tab_on", "tab_off", "tooltip", "bar_track", "checkbox_on", "checkbox_off", "checkbox_hover", "knob",
            "icon_check", "icon_cross", "icon_list", "icon_folder")) {
            assertTrue(s.has(name), "missing " + name);
        }
    }

    @Test
    void spritesSitInsideTheAtlasAndAreBigEnoughForTheirSlices() throws IOException {
        JsonObject meta = JsonParser.parseString(Files.readString(ART.resolve("atlas.json"))).getAsJsonObject();
        int r = meta.get("r").getAsInt(), w = meta.getAsJsonArray("size").get(0).getAsInt(), h = meta.getAsJsonArray("size").get(1).getAsInt();
        for (String name : meta.getAsJsonObject("sprites").keySet()) {
            JsonObject s = meta.getAsJsonObject("sprites").getAsJsonObject(name);
            assertTrue(s.get("x").getAsInt() >= 0 && s.get("x").getAsInt() + s.get("w").getAsInt() <= w, name + " x");
            assertTrue(s.get("y").getAsInt() >= 0 && s.get("y").getAsInt() + s.get("h").getAsInt() <= h, name + " y");
            assertEquals(s.get("lw").getAsInt() * r, s.get("w").getAsInt(), name + " width in texels");
            assertEquals(s.get("lh").getAsInt() * r, s.get("h").getAsInt(), name + " height in texels");
            int slice = s.get("slice").getAsInt();
            assertTrue(slice * 2 < s.get("lw").getAsInt() && slice * 2 < s.get("lh").getAsInt(), name + " slice");
        }
    }
}
