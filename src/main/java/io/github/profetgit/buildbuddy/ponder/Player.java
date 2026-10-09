package io.github.profetgit.buildbuddy.ponder;

/**
 * Where a lesson is in time: play, pause, loop, seek, step. With Reduce motion on, a lesson opens paused on the finished picture
 * of its first step and the step buttons jump from still to still (a still is the last moment of a step, when every drop, pop and
 * camera move of it is over); pressing play still plays it.
 */
public final class Player {
    private final Scene scene;
    private final boolean reduced;
    private double t;
    private boolean playing;
    private double speed = 1.0;
    private int loops;

    public Player(Scene scene, boolean reduced) {
        this.scene = scene;
        this.reduced = reduced;
        restart();
    }

    public Scene scene() {
        return scene;
    }

    public double time() {
        return t;
    }

    public boolean playing() {
        return playing;
    }

    public double speed() {
        return speed;
    }

    /** How many times the lesson has gone round since it was last restarted. */
    public int loops() {
        return loops;
    }

    public int step() {
        return scene.stepAt(t);
    }

    public int steps() {
        return scene.steps();
    }

    public Snapshot snapshot() {
        return Evaluator.at(scene, t);
    }

    public void play() {
        playing = true;
    }

    public void pause() {
        playing = false;
    }

    public void toggle() {
        playing = !playing;
    }

    public void cycleSpeed() {
        speed = speed > 0.75 ? 0.5 : 1.0;
    }

    /** Moves on by a frame's time; goes back to the start at the end. */
    public void update(double dt) {
        if (!playing) return;
        double before = t;
        t += Math.max(0, Math.min(dt, 0.25)) * speed;
        boolean wrapped = false;
        if (t >= scene.duration) {
            t %= scene.duration;
            loops++;
            wrapped = true;
        }
        // the sounds the lesson passed over in this frame, once each; a seek or a step jumps over them
        for (Scene.SoundCue c : scene.sounds) {
            boolean passed = wrapped ? c.t() > before || c.t() <= t : c.t() > before && c.t() <= t;
            if (passed) due.add(c);
        }
    }

    private final java.util.List<Scene.SoundCue> due = new java.util.ArrayList<>();

    /** The sounds that came due since the last call (the screen plays them; a lesson on its own makes no noise). */
    public java.util.List<Scene.SoundCue> drainSounds() {
        if (due.isEmpty()) return java.util.List.of();
        java.util.List<Scene.SoundCue> out = new java.util.ArrayList<>(due);
        due.clear();
        return out;
    }

    public void seek(double to) {
        t = Math.max(0, Math.min(scene.duration - 1e-6, to));
    }

    /** From the start: playing, or, with Reduce motion, the still of step 1. */
    public void restart() {
        loops = 0;
        if (reduced) {
            t = stillTime(0);
            playing = false;
        } else {
            t = 0;
            playing = true;
        }
    }

    /** The last moment of a step, a little before its caption goes. */
    public double stillTime(int step) {
        double s = scene.stepStart(step), e = scene.stepEnd(step);
        return Math.max(s, e - 0.05);
    }

    public void next() {
        int n = step() + 1;
        if (n >= steps()) n = 0;
        goTo(n);
    }

    public void previous() {
        int cur = step();
        // a step that has been going for a moment starts over; at its start the step before it plays
        if (t - scene.stepStart(cur) > 1.0 && !reduced) {
            goTo(cur);
            return;
        }
        goTo(cur == 0 ? steps() - 1 : cur - 1);
    }

    /** Plays a step from its start (or shows its still, with Reduce motion). */
    public void goTo(int step) {
        int s = Math.max(0, Math.min(step, steps() - 1));
        if (reduced) {
            seek(stillTime(s));
            playing = false;
        } else {
            seek(scene.stepStart(s));
        }
    }
}
