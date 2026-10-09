package io.github.profetgit.buildbuddy.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.buildbuddy.BuildBuddy;
import io.github.profetgit.buildbuddy.blueprint.Blueprint;
import io.github.profetgit.buildbuddy.blueprint.Capture;
import io.github.profetgit.buildbuddy.interaction.Interaction;
import io.github.profetgit.buildbuddy.interaction.LevelSource;
import io.github.profetgit.buildbuddy.interaction.Selecting;
import io.github.profetgit.buildbuddy.interaction.SelectionBox;
import io.github.profetgit.buildbuddy.pick.BoxFilter;
import io.github.profetgit.buildbuddy.placement.BlueprintLibrary;
import io.github.profetgit.buildbuddy.placement.BlueprintSaver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * The last step of the Save area tool (PRD 7.7): a name, an author and some tags for the box that was picked, two
 * choices (trim the empty space, keep the data blocks carry), and Save. The box is read a few milliseconds a tick while
 * a bar fills, then written on a worker thread; when it is done the tool ends and the blueprint is in the Library. Back
 * (or Esc) returns to the box.
 */
public final class SaveScreen extends Screen {
    private enum State {
        EDITING, READING, WRITING
    }

    private static final int READ_BUDGET_NS = 4_000_000;
    /** Dev demo: the file the last save wrote, and the blueprint it held. */
    public static volatile Path lastSaved;
    public static volatile Blueprint lastBlueprint;

    /**
     * What a Smart Pick hands over with its box: the cells of the parts it reached and did not pick (to leave out when asked),
     * how many blocks the build has, how many other parts there are, and whether part of it is in chunks that are not loaded.
     */
    public record Pick(it.unimi.dsi.fastutil.longs.LongOpenHashSet others, int blocks, int parts, boolean unloaded) {
    }

    private final SelectionBox box;
    private final Runnable onSaved;
    private final Pick pick;
    /** The three switches of what the box leaves out (see {@link BoxFilter}); a box from Smart Pick starts without the ground. */
    private boolean withGround, withNature = true, onlyBuild = true;
    private final long openedNs = System.nanoTime();
    private EditBox name, author, tags;
    private boolean trim = true, blockData = true;
    private State state = State.EDITING;
    private Capture.Job job;
    /** What the box holds with the current switches, read as soon as the screen opens and again whenever a switch changes: this is what is saved and what the preview shows. */
    private Blueprint captured;
    private boolean wantSave;
    private BuildPreview preview;
    /** On a screen too narrow for the preview beside the form, the preview takes the form's place when this is on. */
    private boolean previewOnly;
    private boolean draggingPreview, panningPreview;
    /** Why the last attempt to remove a block did nothing, shown under the picture until the next one. */
    private String removeNote = "";
    /** What was read from the world (before any block was taken out), where its min corner is, and the blocks taken out as world positions, with the ones undone for redo. */
    private Blueprint base;
    private int[] origin = {0, 0, 0};
    /** Each removal (a click on a tree, a stroke of the eraser, one block) is one step of undo: the world positions it took out. */
    private final java.util.ArrayList<long[]> removedStack = new java.util.ArrayList<>(), redoStack = new java.util.ArrayList<>();
    /** What the last removal took, for the line under the picture. */
    private String tookNote = "";
    /** Ctrl+press on the picture: a click (on release) takes a thing or a block, a drag erases what it passes over. */
    private boolean erasing, eraseMoved, eraseSingle;
    private int eraseUnder = -1;
    private double eraseX, eraseY, eraseLastX, eraseLastY;
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet eraseCells = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
    private final java.util.ArrayList<double[]> eraseTrail = new java.util.ArrayList<>();
    private static final double BRUSH = 6;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet removedSet = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private String error = "";
    private String down = "";
    private int loadedColumns, totalColumns, sinceCount;
    private Path writing;

    /** The Save area tool's box. */
    public SaveScreen(SelectionBox box) {
        this(box, Selecting::finish, null);
    }

    /**
     * @param onSaved what ends the tool that opened this once the file is written
     * @param pick    set when the box came from Smart Pick: it can leave the neighbouring buildings out
     */
    public SaveScreen(SelectionBox box, Runnable onSaved, Pick pick) {
        super(Component.literal(pick != null ? "Save this build" : "Save area"));
        this.box = box;
        this.onSaved = onSaved;
        this.pick = pick;
        this.withGround = pick == null;
    }

    @Override
    protected void init() {
        String who = Settings.get().author.isBlank() ? minecraft.getUser().getName() : Settings.get().author;
        String keepName = name == null ? "" : name.getValue(), keepTags = tags == null ? "" : tags.getValue();
        name = field("Name", 48, keepName.isEmpty() ? "My build" : keepName);
        author = field("Author", 32, author == null ? who : author.getValue());
        tags = field("Tags", 80, keepTags);
        name.setFocused(true);
        setFocused(name);
        countChunks();
        for (EditBox f : List.of(name, author, tags)) f.setVisible(!previewOnly || wide());
        if (preview == null) {
            preview = new BuildPreview(minecraft);
            restartCapture();
            Sfx.play(Sfx.OPEN);
        }
    }

    @Override
    public void removed() {
        if (preview != null) preview.close();
        super.removed();
    }

    private EditBox field(String label, int max, String value) {
        EditBox f = new EditBox(font, 0, 0, 100, 12, Component.literal(label));
        f.setBordered(false);
        f.setMaxLength(max);
        f.setTextColor(Ui.WHITE);
        f.setValue(value);
        addRenderableWidget(f);
        return f;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
        g.fillGradient(0, 0, width, height, 0x30000000, 0x70000000);
    }

    private int pw() {
        return Math.min(width - 24, 292);
    }

    private int ph() {
        return 204 + (rows().size() - 2) * 13;
    }

    /** The switches shown, top to bottom: what the box keeps, then the file's own two. */
    private List<String> rows() {
        return pick != null ? List.of("ground", "nature", "only", "trim", "data") : List.of("ground", "nature", "trim", "data");
    }

    private static String labelOf(String row) {
        return switch (row) {
            case "ground" -> GROUND;
            case "nature" -> NATURE;
            case "only" -> ONLY;
            case "trim" -> TRIM;
            default -> DATA;
        };
    }

    private boolean isOn(String row) {
        return switch (row) {
            case "ground" -> withGround;
            case "nature" -> withNature;
            case "only" -> onlyBuild;
            case "trim" -> trim;
            default -> blockData;
        };
    }

    private void flip(String row) {
        switch (row) {
            case "ground" -> withGround = !withGround;
            case "nature" -> withNature = !withNature;
            case "only" -> onlyBuild = !onlyBuild;
            case "trim" -> trim = !trim;
            default -> blockData = !blockData;
        }
        Sfx.play(Sfx.PRESS, 1.1f);
        restartCapture();
    }

    private static final int GAP = 8, PREVIEW_MIN = 200;

    /** Whether there is room for the preview beside the form. */
    private boolean wide() {
        return width >= 292 + GAP + PREVIEW_MIN + 24;
    }

    private int pvW() {
        return Math.min(300, width - 24 - pw() - GAP);
    }

    private int px() {
        return (width - (wide() ? pw() + GAP + pvW() : pw())) / 2;
    }

    /** The preview's panel: beside the form, or on top of it when the screen is narrow and the preview is asked for. */
    private int[] previewPanel() {
        return wide() ? new int[]{px() + pw() + GAP, py(), pvW(), ph()} : new int[]{px(), py(), pw(), ph()};
    }

    private boolean previewShown() {
        return wide() || previewOnly;
    }

    private boolean formShown() {
        return wide() || !previewOnly;
    }

    private int py() {
        return (height - ph()) / 2;
    }

    private int fieldW() {
        return pw() - 20;
    }

    private int buttonY() {
        return py() + ph() - 26;
    }

    private int backX() {
        return px() + 10;
    }

    private int saveX() {
        return px() + pw() - 10 - 90;
    }

    private void countChunks() {
        var level = minecraft.level;
        totalColumns = box.chunkColumns();
        loadedColumns = 0;
        if (level == null) return;
        for (int cx = box.x0() >> 4; cx <= box.x1() >> 4; cx++) {
            for (int cz = box.z0() >> 4; cz <= box.z1() >> 4; cz++) if (level.getChunkSource().hasChunk(cx, cz)) loadedColumns++;
        }
    }

    private boolean nameOk() {
        return !name.getValue().isBlank();
    }

    private Path target() {
        return BlueprintSaver.freePath(BlueprintLibrary.saveDir(), BlueprintSaver.stem(name.getValue()));
    }

    @Override
    public void tick() {
        if (state == State.EDITING && ++sinceCount >= 20) {
            sinceCount = 0;
            countChunks();
        }
        if (job != null && !job.done() && state != State.WRITING) {
            if (job.step(READ_BUDGET_NS)) finishCapture();
        }
    }

    // ---- reading the box, then saving

    /**
     * Reads the box with the current switches, a few milliseconds a tick, from the moment the screen opens and again whenever a
     * switch changes: the result is both what the preview shows and what Save writes, so the picture cannot differ from the file.
     */
    private void restartCapture() {
        captured = null;
        base = null;
        error = "";
        job = null;
        preview.setBlueprint(null);
        if (minecraft.level == null) return;
        try {
            var level = new LevelSource(minecraft.level);
            BoxFilter filter = new BoxFilter(level, withGround, withNature, pick != null && onlyBuild ? pick.others() : null);
            job = new Capture.Job(level, box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1(), new Capture.Options(trim, blockData), Blueprint.Metadata.of("main"), filter.filters() ? filter : null);
        } catch (IllegalArgumentException e) {
            error = "This box is too big to save in one piece.";
            Sfx.play(Sfx.ERROR);
        }
    }

    private void finishCapture() {
        if (job.failure() != null) {
            if (state == State.READING) fail(job.failure());
            else error = job.failure();
            job = null;
            return;
        }
        base = job.result();
        origin = job.origin();
        applyEdits();
        if (wantSave) {
            wantSave = false;
            write();
        }
    }

    /** Makes what is saved: what was read, minus the blocks taken out. */
    private void applyEdits() {
        if (base == null) return;
        captured = io.github.profetgit.buildbuddy.blueprint.CellEdits.without(base, origin[0], origin[1], origin[2], removedSet);
        preview.setBlueprint(captured);
    }

    /** Takes cells of the preview (indexes into the box) out of the save as one step of undo. */
    private void removeCells(int[] cells, String what) {
        int[] size = preview.boxSize();
        if (size == null || base == null || cells.length == 0) return;
        long[] step = new long[cells.length];
        int n = 0;
        for (int cell : cells) {
            int x = cell % size[0], z = (cell / size[0]) % size[2], y = cell / (size[0] * size[2]);
            long pos = net.minecraft.core.BlockPos.asLong(origin[0] + x, origin[1] + y, origin[2] + z);
            if (removedSet.add(pos)) step[n++] = pos;
        }
        if (n == 0) return;
        removedStack.add(java.util.Arrays.copyOf(step, n));
        redoStack.clear();
        removeNote = "";
        tookNote = what.isEmpty() ? "Took out " + n + (n == 1 ? " block" : " blocks") : "Took out " + what;
        Sfx.play(Sfx.RELEASE, n > 1 ? 0.9f : 1.1f);
        applyEdits();
    }

    /** The words for taking a group out: "a tree (312 blocks)", or nothing for one block. */
    private String describe(Groups.Type type, int count) {
        return type == Groups.Type.BLOCK ? "" : type.said + " (" + count + " blocks)";
    }

    /** Ctrl+Z: puts the last removal back. */
    private void undoRemoval() {
        if (removedStack.isEmpty()) {
            Sfx.play(Sfx.ERROR, 0.8f);
            return;
        }
        long[] step = removedStack.remove(removedStack.size() - 1);
        for (long pos : step) removedSet.remove(pos);
        redoStack.add(step);
        tookNote = "";
        Sfx.play(Sfx.PRESS, 0.9f);
        applyEdits();
    }

    /** Ctrl+Y or Ctrl+Shift+Z: takes the removal put back out again. */
    private void redoRemoval() {
        if (redoStack.isEmpty()) {
            Sfx.play(Sfx.ERROR, 0.8f);
            return;
        }
        long[] step = redoStack.remove(redoStack.size() - 1);
        for (long pos : step) removedSet.add(pos);
        removedStack.add(step);
        tookNote = "";
        Sfx.play(Sfx.RELEASE, 1.1f);
        applyEdits();
    }

    /** Saves: writes what was read (waiting for the reading to end first when it is still running). Does nothing without a name or while a save is running. */
    public void save() {
        if (state == State.WRITING || !nameOk() || minecraft.level == null) return;
        if (state == State.EDITING) {
            error = "";
            for (EditBox f : List.of(name, author, tags)) f.setEditable(false);
            Sfx.play(Sfx.PRESS, 1.2f);
        }
        if (captured == null) {
            if (job == null) {
                for (EditBox f : List.of(name, author, tags)) f.setEditable(true);
                return;
            }
            state = State.READING;
            wantSave = true;
            return;
        }
        write();
    }

    private void write() {
        String title = name.getValue().trim();
        long now = System.currentTimeMillis();
        Blueprint.Metadata meta = new Blueprint.Metadata(title, author.getValue().trim(), "", now, now, captured.meta.dataVersion());
        var r = captured.regions.get(0);
        Blueprint bp = new Blueprint(meta, List.of(new io.github.profetgit.buildbuddy.blueprint.Region(title.isBlank() ? "main" : title, r.x, r.y, r.z, r.sx, r.sy, r.sz, r.palette, r.blocks, r.blockEntities)));
        List<String> tagList = BlueprintSaver.parseTags(tags.getValue());
        String stem = BlueprintSaver.stem(title);
        state = State.WRITING;
        Settings.get().author = author.getValue().trim();
        Settings.changed();
        Util.backgroundExecutor().execute(() -> {
            try {
                Path file = BlueprintSaver.save(bp, BlueprintLibrary.saveDir(), stem, tagList);
                minecraft.execute(() -> saved(file, bp));
            } catch (IOException | RuntimeException e) {
                BuildBuddy.LOG.error("Cannot save the blueprint", e);
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                minecraft.execute(() -> fail("Could not write the file: " + why));
            }
        });
    }

    private void saved(Path file, Blueprint bp) {
        lastSaved = file;
        lastBlueprint = bp;
        long unloaded = job.unloadedCells();
        onSaved.run();
        Sfx.play(Sfx.LOCK);
        minecraft.gui.setScreen(null);
        Interaction.say(minecraft, "Saved " + file.getFileName().toString().replaceFirst("(?i)\\.litematic$", "") + ": " + String.format(Locale.ROOT, "%,d", bp.totalBlocks())
            + " blocks, " + bp.sizeX + " x " + bp.sizeY + " x " + bp.sizeZ + (unloaded > 0 ? " (part of the box was not loaded)" : "") + ". It is in your Library.");
    }

    private void fail(String why) {
        error = why;
        state = State.EDITING;
        wantSave = false;
        for (EditBox f : List.of(name, author, tags)) f.setEditable(true);
        Sfx.play(Sfx.ERROR);
    }

    // ---- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int rawX, int rawY, float partial) {
        Motion.frame();
        int mx = Ui.mx(rawX), my = Ui.my(rawY);
        double open = Motion.reduced() ? 1 : Math.min(1, (System.nanoTime() - openedNs) / 1e9 / 0.22);
        int px = px(), py = py(), pw = pw(), ph = ph();
        float inner = Ui.panelOpening(g, px, py, pw, ph, open);
        if (inner < 0.05f) return;
        double t = System.nanoTime() / 1e9;
        boolean busy = state != State.EDITING;

        if (formShown()) {
        Ui.text(g, getTitle().getString(), px + 10, py + 8, Ui.withAlpha(Ui.LINE, inner));
        Ui.right(g, box.sizeText(), px + pw - 10, py + 8, Ui.withAlpha(Ui.CYAN, inner));
        String info = pick != null ? "Fitted round a build of " + String.format(Locale.ROOT, "%,d", pick.blocks()) + " blocks." : String.format(Locale.ROOT, "%,d", box.volume()) + " cells in the box.";
        int y = py + 21;
        Ui.text(g, info, px + 10, y, Ui.withAlpha(Ui.DIM, inner));
        if (pick != null ? pick.unloaded() : loadedColumns < totalColumns) {
            String warn = pick != null ? "Part of this build is in chunks that are not loaded: walk closer and pick again, or the save will have holes."
                : (totalColumns - loadedColumns) + " of " + totalColumns + " chunks are not loaded: walk closer, or the save will have holes.";
            for (String line : Ui.wrap(warn, pw - 20, 2)) {
                y += 10;
                Ui.text(g, line, px + 10, y, Ui.withAlpha(Ui.WARN, inner));
            }
        }

        int fx = px + 10;
        int fy = py + 44;
        fieldRow(g, "Name", name, fx, fy, inner, partial, mx, my, null);
        fieldRow(g, "Author", author, fx, fy + 28, inner, partial, mx, my, null);
        fieldRow(g, "Tags, separated by commas", tags, fx, fy + 56, inner, partial, mx, my, "house, medieval");

        int cy = fy + 86;
        List<String> rows = rows();
        for (int i = 0; i < rows.size(); i++) check(g, "sv#" + rows.get(i), fx, cy + i * 13, labelOf(rows.get(i)), isOn(rows.get(i)), mx, my, inner, busy);

        int ly = py + ph - 44;
        if (busy) {
            double v = state == State.READING ? job.progress() * 0.9 : 0.95;
            Ui.bar(g, "sv#bar", px + 10, ly, pw - 20, 10, v, t);
            Ui.centered(g, state == State.READING ? "Reading the box..." : "Writing the file...", px + pw / 2, ly + 14, Ui.withAlpha(Ui.DIM, inner));
        } else if (!error.isEmpty()) {
            List<String> lines = Ui.wrap(error, pw - 20, 2);
            for (int i = 0; i < lines.size(); i++) Ui.text(g, lines.get(i), px + 10, ly + i * 10, Ui.withAlpha(Ui.BAD, inner));
        } else if (nameOk()) {
            Ui.text(g, Ui.fit("Saves as " + target().getFileName(), pw - 20), px + 10, ly + 6, Ui.withAlpha(Ui.DIM, inner));
        } else {
            Ui.text(g, "Give it a name first.", px + 10, ly + 6, Ui.withAlpha(Ui.WARN, inner));
        }

        } else {
            drawPreviewPanel(g, previewPanel(), inner, mx, my);
        }
        if (wide()) {
            int[] pp = previewPanel();
            float inner2 = Ui.panelOpening(g, pp[0], pp[1], pp[2], pp[3], open);
            if (inner2 >= 0.05f) drawPreviewPanel(g, pp, inner2, mx, my);
        }
        Ui.button(g, "sv#back", backX(), buttonY(), 70, 16, "Back", null, mx, my, down.equals("back"), !busy);
        if (!wide()) Ui.button(g, "sv#view", viewX(), buttonY(), 80, 16, previewOnly ? "Details" : "Preview", null, mx, my, down.equals("view"), !busy);
        boolean saveOn = !busy && nameOk() && (captured != null || job != null);
        Ui.button(g, "sv#save", saveX(), buttonY(), 90, 16, "Save", "save", mx, my, down.equals("save"), saveOn);
        if (saveOn) Ui.marching(g, saveX() - 2, buttonY() - 2, 94, 20, Ui.withAlpha(Ui.CYAN, inner * 0.8f), t);
    }

    private int viewX() {
        return px() + pw() / 2 - 40;
    }

    /** The preview's panel: a title, the picture, a line of hints under it, and what is being read while it is. */
    private void drawPreviewPanel(GuiGraphicsExtractor g, int[] pp, float inner, int mx, int my) {
        Ui.text(g, "Preview", pp[0] + 10, pp[1] + 8, Ui.withAlpha(Ui.LINE, inner));
        boolean idle = state == State.EDITING;
        boolean ctrl = idle && Interaction.ctrlDown(minecraft);
        // pointing at a block lights it up while Ctrl is held (Ctrl+click takes it out)
        preview.setRemoveMode(ctrl && !(erasing && eraseMoved));
        // Shift with Ctrl: just the one block, so the whole tree does not light up
        preview.setWhole(!Interaction.shiftDown(minecraft));
        Ui.button(g, "sv#reset", resetX(pp), pp[1] + 5, RESET_W, 14, "Reset view", null, mx, my, false, idle);
        int[] r = pictureRect();
        double progress = job != null && !job.done() ? job.progress() : -1;
        preview.draw(g, r[0], r[1], r[2], r[3], progress, mx, my);
        if (ctrl) {
            // with Ctrl held it is unmistakable what a click does: a red frame, a red banner, the pointed block in red
            int red = Ui.withAlpha(0xFFFF6B6B, inner * 0.95f);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + 2, red);
            g.fill(r[0], r[1] + r[3] - 2, r[0] + r[2], r[1] + r[3], red);
            g.fill(r[0], r[1], r[0] + 2, r[1] + r[3], red);
            g.fill(r[0] + r[2] - 2, r[1], r[0] + r[2], r[1] + r[3], red);
            String banner = banner();
            int bw = Ui.font().width(banner) + 10;
            g.fill(r[0] + 2, r[1] + 2, r[0] + 2 + bw, r[1] + 15, Ui.withAlpha(0xFFB02A2A, inner * 0.92f));
            Ui.text(g, banner, r[0] + 7, r[1] + 5, Ui.withAlpha(Ui.WHITE, inner));
        }
        if (erasing && eraseMoved) {
            // the brush's path: where the eraser has been, in red, until the button goes up
            int trail = Ui.withAlpha(0xFFFF6B6B, inner * 0.28f);
            int rad = (int) BRUSH;
            for (double[] pt : eraseTrail) {
                int cx = (int) pt[0], cy = (int) pt[1];
                if (cx < r[0] || cy < r[1] || cx >= r[0] + r[2] || cy >= r[1] + r[3]) continue;
                for (int dy = -rad; dy <= rad; dy++) {
                    int half = (int) Math.sqrt(rad * rad - dy * dy);
                    g.fill(Math.max(r[0], cx - half), Math.max(r[1], cy + dy), Math.min(r[0] + r[2], cx + half), Math.min(r[1] + r[3], cy + dy + 1), trail);
                }
            }
        }
        // what was done, or why a click did nothing, in the corner under the picture; then the shortcuts
        String note = !removeNote.isEmpty() ? removeNote
            : removedStack.isEmpty() || tookNote.isEmpty() ? ""
            : tookNote + ". Ctrl+Z puts it back";
        int ly = r[1] + r[3] + 4;
        if (!note.isEmpty()) Ui.text(g, Ui.fit(note, pp[2] - 16), pp[0] + 8, ly, Ui.withAlpha(removeNote.isEmpty() ? Ui.CYAN : 0xFFFF8A8A, inner));
        drawShortcuts(g, pp[0] + 8, ly + (note.isEmpty() ? 0 : 11), pp[2] - 16, inner, ctrl);
    }

    /** What a click would do right now with Ctrl held, in the red banner over the picture. */
    private String banner() {
        if (erasing && eraseMoved) return "Erasing: let go to take these blocks out";
        int cell = preview.hovered();
        if (cell < 0) return "CTRL: click a block to take it out";
        boolean single = Interaction.shiftDown(minecraft);
        Groups.Type type = single ? Groups.Type.BLOCK : preview.groupType(cell);
        if (type == null || type == Groups.Type.BLOCK) return "Click: take out this block";
        return "Click: take out " + type.pointing + " (" + preview.groupSize(cell) + " blocks)";
    }

    /** What the mouse and keys do in the preview, as the same key and mouse pictures the cursor hints use. */
    private static final String[][] SHORTCUTS = {
        {"Drag", "Turn"}, {"Right drag", "Move"}, {"Scroll", "Zoom"},
        {"Ctrl+Click", "Remove a tree, the ground or a block"}, {"Ctrl+Shift+Click", "One block only"}, {"Ctrl+Drag", "Erase"},
        {"Ctrl+Z / Y", "Undo / Redo"}};

    private void drawShortcuts(GuiGraphicsExtractor g, int x, int y, int w, float inner, boolean ctrl) {
        int cx = x, cy = y;
        for (String[] sc : SHORTCUTS) {
            int need = ChipIcons.width(sc[0]) + 4 + Ui.font().width(sc[1]);
            if (cx > x && cx + need > x + w) {
                cx = x;
                cy += 15;
            }
            boolean hot = ctrl && sc[0].startsWith("Ctrl+Click");
            ChipIcons.draw(g, sc[0], cx, cy - 2, inner * (hot ? 1f : 0.9f));
            Ui.text(g, sc[1], cx + ChipIcons.width(sc[0]) + 4, cy + 2, Ui.withAlpha(hot ? 0xFFFF8A8A : Ui.DIM, inner));
            cx += need + 12;
        }
    }

    /** The picture inside the preview panel: below the title, above the note and the shortcuts. */
    private int[] pictureRect() {
        int[] pp = previewPanel();
        return new int[]{pp[0] + 8, pp[1] + 22, pp[2] - 16, pp[3] - 22 - SHORTCUT_H};
    }

    private static final int SHORTCUT_H = 80;

    private static final int RESET_W = 70;

    private int resetX(int[] pp) {
        return pp[0] + pp[2] - 10 - RESET_W;
    }

    private void fieldRow(GuiGraphicsExtractor g, String label, EditBox f, int x, int y, float inner, float partial, int mx, int my, String hint) {
        Ui.text(g, label, x, y, Ui.withAlpha(Ui.DIM, inner));
        int w = fieldW();
        Ui.inset(g, x, y + 10, w, 14);
        f.setX(x + 4);
        f.setY(y + 13);
        f.setWidth(w - 8);
        f.extractRenderState(g, mx, my, partial);
        if (hint != null && f.getValue().isEmpty() && !f.isFocused()) Ui.text(g, hint, x + 6, y + 13, Ui.withAlpha(Ui.DIM, inner * 0.55f));
    }

    private void check(GuiGraphicsExtractor g, String key, int x, int y, String label, boolean on, int mx, int my, float inner, boolean busy) {
        boolean over = !busy && Ui.inside(mx, my, x, y, 12 + 6 + font.width(label), 11);
        Ui.checkbox(g, key, x, y, on, over);
        Ui.text(g, label, x + 16, y + 1, Ui.withAlpha(busy ? 0xFF5E7C99 : Ui.LINE, inner));
    }

    // ---- input

    private boolean onCheck(int mx, int my, int x, int y, String label) {
        return Ui.inside(mx, my, x, y, 12 + 6 + font.width(label), 11);
    }

    private static final String TRIM = "Trim the empty space around the build", DATA = "Keep sign text, banners and heads", GROUND = "Include the ground (dirt, stone, sand)",
        NATURE = "Include trees and plants", ONLY = "Leave out the other buildings in the box";

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        if (previewShown() && state == State.EDITING) {
            int[] pp = previewPanel();
            if (Ui.inside(mx, my, resetX(pp), pp[1] + 5, RESET_W, 14)) {
                preview.reset();
                Sfx.play(Sfx.PRESS, 1.1f);
                return true;
            }
            int[] r = pictureRect();
            if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
                // the game numbers the mouse buttons left 1, middle 2, right 3
                boolean left = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
                // Ctrl+click takes the block under the pointer out, on the press: no mode, and nothing to tell from a drag
                if (left && (event.hasControlDown() || Interaction.ctrlDown(minecraft))) {
                    // taken on release: a click takes the thing under it, a drag erases what it passes over
                    erasing = true;
                    eraseMoved = false;
                    eraseSingle = event.hasShiftDown() || Interaction.shiftDown(minecraft);
                    eraseX = eraseLastX = event.x();
                    eraseY = eraseLastY = event.y();
                    eraseUnder = preview.cellAt(event.x() - r[0], event.y() - r[1]);
                    eraseCells.clear();
                    eraseTrail.clear();
                    removeNote = "";
                    return true;
                }
                draggingPreview = true;
                panningPreview = event.button() == InputConstants.MOUSE_BUTTON_RIGHT || event.button() == InputConstants.MOUSE_BUTTON_MIDDLE || event.hasShiftDown();
                return true;
            }
        }
        if (state == State.EDITING) {
            int cy = py() + 44 + 86, fx = px() + 10;
            List<String> rows = rows();
            for (int i = 0; formShown() && i < rows.size(); i++) {
                if (onCheck(mx, my, fx, cy + i * 13, labelOf(rows.get(i)))) {
                    flip(rows.get(i));
                    return true;
                }
            }
            if (!wide() && Ui.inside(mx, my, viewX(), buttonY(), 80, 16)) {
                down = "view";
                Sfx.play(Sfx.PRESS);
                return true;
            }
            if (Ui.inside(mx, my, backX(), buttonY(), 70, 16)) {
                down = "back";
                Sfx.play(Sfx.PRESS);
                return true;
            }
            if (nameOk() && Ui.inside(mx, my, saveX(), buttonY(), 90, 16)) {
                down = "save";
                Sfx.play(Sfx.PRESS);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int mx = (int) event.x(), my = (int) event.y();
        if (draggingPreview) {
            draggingPreview = false;
            return true;
        }
        if (erasing && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            finishErasing();
            return true;
        }
        String was = down;
        down = "";
        if (was.equals("back") && Ui.inside(mx, my, backX(), buttonY(), 70, 16)) {
            back();
            return true;
        }
        if (was.equals("view") && Ui.inside(mx, my, viewX(), buttonY(), 80, 16)) {
            previewOnly = !previewOnly;
            for (EditBox f : List.of(name, author, tags)) f.setVisible(!previewOnly);
            return true;
        }
        if (was.equals("save") && Ui.inside(mx, my, saveX(), buttonY(), 90, 16)) {
            save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingPreview) {
            preview.drag(dx, dy, panningPreview);
            return true;
        }
        if (erasing) {
            // a few units of movement make it a stroke; the points in between are filled in so a quick sweep leaves no gaps
            if (!eraseMoved && Math.hypot(event.x() - eraseX, event.y() - eraseY) < 5) return true;
            if (!eraseMoved) {
                eraseMoved = true;
                brush(eraseX, eraseY);
            }
            double len = Math.hypot(event.x() - eraseLastX, event.y() - eraseLastY);
            int steps = Math.max(1, (int) (len / 3));
            for (int i = 1; i <= steps; i++) {
                brush(eraseLastX + (event.x() - eraseLastX) * i / steps, eraseLastY + (event.y() - eraseLastY) * i / steps);
            }
            eraseLastX = event.x();
            eraseLastY = event.y();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    /** The eraser touches the blocks under a round brush at a point of the screen. */
    private void brush(double x, double y) {
        int[] r = pictureRect();
        eraseTrail.add(new double[]{x, y});
        for (int cell : preview.cellsNear(x - r[0], y - r[1], BRUSH)) eraseCells.add(cell);
    }

    /** The mouse button went up after a Ctrl press: take a thing or a block (a click), or everything the brush passed over (a stroke). */
    private void finishErasing() {
        erasing = false;
        if (eraseMoved) {
            int[] cells = eraseCells.toIntArray();
            removeCells(cells, "");
            if (cells.length == 0) removeNote = "The eraser did not touch a block";
        } else if (eraseUnder >= 0) {
            Groups.Type type = eraseSingle ? Groups.Type.BLOCK : preview.groupType(eraseUnder);
            int[] cells = preview.cellsOf(eraseUnder, !eraseSingle);
            removeCells(cells, describe(type == null ? Groups.Type.BLOCK : type, cells.length));
        } else {
            removeNote = "No block there. Ctrl+click on a block of the picture";
        }
        eraseCells.clear();
        eraseTrail.clear();
        eraseMoved = false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (previewShown()) {
            int[] r = pictureRect();
            if (Ui.inside((int) x, (int) y, r[0], r[1], r[2], r[3])) {
                preview.zoom(scrollY, x - (r[0] + r[2] / 2.0), y - (r[1] + r[3] / 2.0));
                return true;
            }
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (state != State.EDITING) return true;
        if (event.hasControlDown() && event.input() == com.mojang.blaze3d.platform.InputConstants.KEY_Z) {
            if (event.hasShiftDown()) redoRemoval();
            else undoRemoval();
            return true;
        }
        if (event.hasControlDown() && event.input() == com.mojang.blaze3d.platform.InputConstants.KEY_Y) {
            redoRemoval();
            return true;
        }
        if (super.keyPressed(event)) return true;
        if (event.isConfirmation()) {
            save();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        return state == State.EDITING && super.charTyped(event);
    }

    @Override
    public void onClose() {
        if (state != State.EDITING) return;
        back();
    }

    private void back() {
        Sfx.play(Sfx.CLOSE);
        minecraft.gui.setScreen(null);
    }

    // ---- for the demo

    public void setName(String s) {
        name.setValue(s);
    }

    public void setTags(String s) {
        tags.setValue(s);
    }

    public void setAuthor(String s) {
        author.setValue(s);
    }

    public boolean busy() {
        return state != State.EDITING;
    }

    public String error() {
        return error;
    }

    public boolean groundOn() {
        return withGround;
    }

    public boolean natureOn() {
        return withNature;
    }

    public boolean onlyBuildOn() {
        return onlyBuild;
    }

    /** Dev demo: sets the three switches of what the box keeps. */
    public void setKeeps(boolean ground, boolean nature, boolean onlyBuild) {
        this.withGround = ground;
        this.withNature = nature;
        this.onlyBuild = onlyBuild;
        restartCapture();
    }

    public boolean trimming() {
        return trim;
    }

    public void setOptions(boolean trim, boolean blockData) {
        this.trim = trim;
        this.blockData = blockData;
        restartCapture();
    }

    /** Dev demo: the preview, and whether the box has been read. */
    public BuildPreview preview() {
        return preview;
    }

    public boolean readyToSave() {
        return captured != null;
    }

    public Blueprint captured() {
        return captured;
    }

    public int removedCount() {
        int n = 0;
        for (long[] step : removedStack) n += step.length;
        return n;
    }

    /** Dev demo: how many separate removals (steps of undo) there are. */
    public int removalSteps() {
        return removedStack.size();
    }

    /** Dev demo: undo and redo of the removals, as the keys do them. */
    public void undoRemovalForDemo() {
        undoRemoval();
    }

    public void redoRemovalForDemo() {
        redoRemoval();
    }

    /** Dev demo: a screen point over a block of a kind of group in the picture, or null. */
    public int @org.jspecify.annotations.Nullable [] anchorOfGroup(Groups.Type type) {
        double[] p = preview.pointOfGroup(type);
        if (p == null) return null;
        int[] r = pictureRect();
        return new int[]{(int) (r[0] + p[0]), (int) (r[1] + p[1])};
    }

    /** Dev demo: the words in the red banner now. */
    public String bannerText() {
        return banner();
    }

    public int[] anchor(String which) {
        return switch (which) {
            case "save" -> new int[]{saveX() + 45, buttonY() + 8};
            case "back" -> new int[]{backX() + 35, buttonY() + 8};
            case "trim", "ground" -> new int[]{px() + 20, py() + 44 + 86 + 5};
            case "reset" -> new int[]{resetX(previewPanel()) + RESET_W / 2, previewPanel()[1] + 12};
            case "preview" -> {
                int[] r = pictureRect();
                yield new int[]{r[0] + r[2] / 2, r[1] + r[3] / 2};
            }
            default -> new int[]{0, 0};
        };
    }
}
