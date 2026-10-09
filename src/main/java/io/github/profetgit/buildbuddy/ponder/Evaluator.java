package io.github.profetgit.buildbuddy.ponder;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link Scene} and a time into a {@link Snapshot}. A pure function of its two arguments (nothing is simulated or
 * remembered), which is what lets a lesson be scrubbed, stepped, looped, drawn as a still and recorded with the same call.
 */
public final class Evaluator {
    private Evaluator() {
    }

    public static final double DEFAULT_YAW = 0.785, DEFAULT_PITCH = 0.52;
    /** A block that is still falling in this much of the way does not count as built for the ghost it stands in. */
    private static final float LANDED = 0.85f;

    public static Snapshot at(Scene s, double t) {
        t = Math.max(0, Math.min(s.duration, t));
        List<Snapshot.GroupView> views = new ArrayList<>(s.groups.size());
        for (Scene.Group g : s.groups) views.add(group(g, t));
        // a ghost that matches another group: hide what is built, redden what is built wrong
        for (int i = 0; i < views.size(); i++) {
            Snapshot.GroupView v = views.get(i);
            if (v.group.match.isEmpty()) continue;
            List<Snapshot.GroupView> others = new ArrayList<>();
            for (String id : v.group.match) {
                for (Snapshot.GroupView other : views) {
                    if (other.group.id.equals(id)) others.add(other);
                }
            }
            match(v, others);
        }
        List<Snapshot.ItemView> overlays = new ArrayList<>();
        for (Scene.Item it : s.overlays) {
            double a = it.alphaAt(t);
            if (a > 0) overlays.add(new Snapshot.ItemView(it, t, a));
        }
        List<Snapshot.ItemView> panels = new ArrayList<>();
        for (Scene.Item it : s.panels) {
            double a = it.alphaAt(t);
            if (a > 0) panels.add(new Snapshot.ItemView(it, t, a));
        }
        Scene.Caption caption = null;
        for (Scene.Caption c : s.captions) {
            if (c.t0() <= t && t < c.t1()) {
                caption = c;
                break;
            }
        }
        List<Snapshot.ChipRow> chips = List.of();
        for (Scene.Chips c : s.chips) {
            if (c.t0() <= t && t < c.t1()) {
                List<Snapshot.ChipRow> rows = new ArrayList<>();
                for (int r = 0; r < c.rows().size(); r++) {
                    boolean lit = false;
                    for (double[] l : c.lit()) lit |= (int) l[0] == r && l[1] <= t && t < l[2];
                    rows.add(new Snapshot.ChipRow(c.rows().get(r).get(0), c.rows().get(r).get(1), lit));
                }
                chips = rows;
                break;
            }
        }
        double[] focus = s.camera.vec("focus", t, s.focus);
        return new Snapshot(t, s.camera.num("yaw", t, DEFAULT_YAW), s.camera.num("pitch", t, DEFAULT_PITCH), s.camera.num("zoom", t, 1.0), focus, views, overlays, panels, cursor(s, t), s.stepAt(t), s.steps(), caption, chips);
    }

    // ---- groups

    static Snapshot.GroupView group(Scene.Group g, double t) {
        double[] pos = g.keys.vec("pos", t, g.pos);
        double turn = g.keys.num("turn", t, 0), scale = g.keys.num("scale", t, 1), alpha = g.keys.num("alpha", t, 1);
        boolean mirror = g.keys.num("mirror", t, 0) >= 0.5, visible = g.keys.num("visible", t, 1) >= 0.5;
        int lo = Integer.MIN_VALUE, hi = Integer.MAX_VALUE;
        if (g.keys.has("window")) {
            double[] w = g.keys.vec("window", t, new double[]{0, g.sy});
            lo = (int) Math.round(w[0]);
            hi = (int) Math.round(w[1]);
        }
        int n = g.volume();
        byte[] state = new byte[n], anim = new byte[n];
        float[] progress = new float[n];
        java.util.Arrays.fill(progress, 1f);
        if (visible) {
            int layer = g.sx * g.sz;
            for (int c = 0; c < n; c++) {
                if (g.cell[c] == 0) continue;
                int y = c / layer;
                if (y < lo || y > hi) continue;
                Scene.Ev[] ev = g.events[c];
                boolean present = g.startFull;
                Scene.Ev last = null;
                if (ev != null) {
                    for (Scene.Ev e : ev) {
                        if (e.t() > t) break;
                        last = e;
                        present = e.reveal();
                    }
                }
                if (last != null && last.anim() != Scene.Anim.NONE && last.dur() > 0) {
                    double age = t - last.t();
                    if (age < last.dur()) {
                        float p = (float) (age / last.dur());
                        if (last.reveal()) {
                            anim[c] = switch (last.anim()) {
                                case DROP -> Snapshot.DROP_IN;
                                case POP -> Snapshot.POP_IN;
                                default -> Snapshot.FADE_IN;
                            };
                        } else {
                            // a cell that is going stays until it has faded
                            present = true;
                            anim[c] = Snapshot.FADE_OUT;
                        }
                        progress[c] = p;
                    }
                }
                if (present) state[c] = Snapshot.PRESENT;
            }
        }
        return new Snapshot.GroupView(g, pos, turn, scale, alpha, mirror, state, anim, progress);
    }

    /**
     * Ghost cells where one of the other groups holds the same block are hidden; where they hold a different one they are wrong.
     * Only while the ghost sits square on the grid.
     */
    static void match(Snapshot.GroupView ghost, List<Snapshot.GroupView> reals) {
        if (Math.abs(ghost.turn - Math.rint(ghost.turn)) > 1e-3 || ghost.scale != 1.0) return;
        Scene.Group g = ghost.group;
        double[] w = new double[3];
        int layer = g.sx * g.sz;
        for (int c = 0; c < g.volume(); c++) {
            if (ghost.state[c] == Snapshot.ABSENT) continue;
            int x = c % g.sx, z = (c / g.sx) % g.sz, y = c / layer;
            ghost.toWorld(x + 0.5, y + 0.5, z + 0.5, w);
            boolean same = false, other = false;
            for (Snapshot.GroupView real : reals) {
                int rc = real.cellAt(w[0], w[1], w[2]);
                if (rc < 0 || real.state[rc] == Snapshot.ABSENT) continue;
                if (real.anim[rc] == Snapshot.DROP_IN && real.progress[rc] < LANDED) continue;
                if (real.anim[rc] == Snapshot.FADE_OUT) continue;
                if (real.group.cell[rc] == g.cell[c]) same = true;
                else other = true;
            }
            if (same) ghost.state[c] = Snapshot.ABSENT;
            else if (other) ghost.state[c] = Snapshot.WRONG;
        }
    }

    // ---- cursor

    static Snapshot.@Nullable CursorView cursor(Scene s, double t) {
        List<Scene.CursorKey> keys = s.cursor;
        if (keys.isEmpty()) return null;
        int i = -1;
        for (int k = 0; k < keys.size(); k++) {
            if (keys.get(k).t() <= t) i = k;
            else break;
        }
        Scene.CursorKey a = keys.get(Math.max(0, i));
        Scene.CursorKey b = i >= 0 && i + 1 < keys.size() ? keys.get(i + 1) : a;
        double span = b.t() - a.t();
        double raw = b == a || span <= 0 ? 1 : Math.max(0, Math.min(1, (t - a.t()) / span));
        double k = b == a ? 1 : b.ease().apply(raw);
        double alpha = a.alpha() + (b.alpha() - a.alpha()) * raw;
        // how long since the button last went down (the ripple of a click goes on after the release)
        double age = 1e9;
        boolean down = i >= 0 && keys.get(i).down();
        for (int c = i; c >= 0; c--) {
            if (keys.get(c).down() && (c == 0 || !keys.get(c - 1).down())) {
                age = t - keys.get(c).t();
                break;
            }
        }
        return new Snapshot.CursorView(a.pt(), b.pt(), k, down, age, a.shape(), alpha);
    }
}
