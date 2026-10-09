package io.github.profetgit.buildbuddy.ponder;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Everything on screen at one moment of a lesson, worked out from the {@link Scene} and a time alone. Immutable once built, so the
 * worker that draws it and the screen that overlays text on it can share it without locks.
 */
public final class Snapshot {
    /** Cell states of a group: not there, there, or there and wrong (a ghost cell where the real world holds another block). */
    public static final byte ABSENT = 0, PRESENT = 1, WRONG = 2;
    /** How a cell is arriving or leaving: none, falling in, swelling in, fading in, fading out. */
    public static final byte NO_ANIM = 0, DROP_IN = 1, POP_IN = 2, FADE_IN = 3, FADE_OUT = 4;

    public final double t;
    public final double yaw, pitch, zoom;
    public final double[] focus;
    public final List<GroupView> groups;
    public final List<ItemView> overlays, panels;
    public final @Nullable CursorView cursor;
    public final int step, steps;
    public final Scene.@Nullable Caption caption;
    public final List<ChipRow> chips;

    Snapshot(double t, double yaw, double pitch, double zoom, double[] focus, List<GroupView> groups, List<ItemView> overlays, List<ItemView> panels, @Nullable CursorView cursor, int step, int steps,
             Scene.@Nullable Caption caption, List<ChipRow> chips) {
        this.t = t;
        this.yaw = yaw;
        this.pitch = pitch;
        this.zoom = zoom;
        this.focus = focus;
        this.groups = groups;
        this.overlays = overlays;
        this.panels = panels;
        this.cursor = cursor;
        this.step = step;
        this.steps = steps;
        this.caption = caption;
        this.chips = chips;
    }

    /** One chip row on screen: the key text and action, and whether it is lit just now. */
    public record ChipRow(String key, String action, boolean lit) {
    }

    /**
     * The mock cursor: gliding from one spot to the next ({@code k} of the way; the screen turns both into places on the picture),
     * pressed or not, how long since it last went down (for the ripple), its shape and opacity.
     */
    public record CursorView(Scene.Pt from, Scene.Pt to, double k, boolean down, double clickAge, String shape, double alpha) {
    }

    /** A group at the moment: its transform and which cells are there. The transform takes a local point to the world: mirror in x, turn about the footprint's middle, scale, move. */
    public static final class GroupView {
        public final Scene.Group group;
        public final double[] pos;
        /** Quarter turns, positive clockwise seen from above. */
        public final double turn;
        public final double scale, alpha;
        public final boolean mirror;
        /** Per cell: {@link #ABSENT}, {@link #PRESENT} or {@link #WRONG}. */
        public final byte[] state;
        /** Per cell: how it is arriving or leaving, and how far (0 to 1). */
        public final byte[] anim;
        public final float[] progress;

        GroupView(Scene.Group group, double[] pos, double turn, double scale, double alpha, boolean mirror, byte[] state, byte[] anim, float[] progress) {
            this.group = group;
            this.pos = pos;
            this.turn = turn;
            this.scale = scale;
            this.alpha = alpha;
            this.mirror = mirror;
            this.state = state;
            this.anim = anim;
            this.progress = progress;
        }

        /** The radians of the turn (clockwise seen from above). */
        public double angle() {
            return turn * Math.PI / 2;
        }

        /** A local point (in blocks, the group's own corner at 0) in the world. */
        public void toWorld(double lx, double ly, double lz, double[] out) {
            double px = group.sx / 2.0, pz = group.sz / 2.0;
            double rx = lx - px, rz = lz - pz;
            if (mirror) rx = -rx;
            double a = angle(), c = Math.cos(a), s = Math.sin(a);
            out[0] = pos[0] + px + (rx * c - rz * s) * scale;
            out[1] = pos[1] + ly * scale;
            out[2] = pos[2] + pz + (rx * s + rz * c) * scale;
        }

        /** The cell of this group a world point is in, as an index, or -1 when it is outside. */
        public int cellAt(double wx, double wy, double wz) {
            double px = group.sx / 2.0, pz = group.sz / 2.0;
            double rx = (wx - pos[0] - px) / scale, rz = (wz - pos[2] - pz) / scale, ly = (wy - pos[1]) / scale;
            double a = angle(), c = Math.cos(a), s = Math.sin(a);
            double ux = rx * c + rz * s, uz = -rx * s + rz * c;
            if (mirror) ux = -ux;
            int x = (int) Math.floor(ux + px + 1e-6), y = (int) Math.floor(ly + 1e-6), z = (int) Math.floor(uz + pz + 1e-6);
            if (x < 0 || y < 0 || z < 0 || x >= group.sx || y >= group.sy || z >= group.sz) return -1;
            return group.index(x, y, z);
        }

        /** How many cells are there (present or wrong). */
        public int count() {
            int n = 0;
            for (byte b : state) if (b != ABSENT) n++;
            return n;
        }
    }

    /** An overlay or panel at the moment: its item, how visible it is, and reads of its properties that follow its keyframes and states. */
    public static final class ItemView {
        public final Scene.Item item;
        public final double t, alpha;
        private final Scene.Props props;

        ItemView(Scene.Item item, double t, double alpha) {
            this.item = item;
            this.t = t;
            this.alpha = alpha;
            this.props = item.propsAt(t);
        }

        public String type() {
            return item.type();
        }

        public double num(String f, double def) {
            return item.keys().has(f) ? item.keys().num(f, t, def) : props.num(f, def);
        }

        public double[] vec(String f, double[] def) {
            if (item.keys().has(f)) return item.keys().vec(f, t, def);
            double[] v = props.vec(f);
            return v != null ? v : def;
        }

        public String str(String f, String def) {
            return props.str(f, def);
        }

        public boolean bool(String f, boolean def) {
            return props.bool(f, def);
        }

        public Scene.Props props() {
            return props;
        }

        /** Seconds since the item appeared. */
        public double age() {
            return t - item.t0();
        }
    }
}
