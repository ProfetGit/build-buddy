package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.Capture;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.LevelSource;
import io.github.profetgit.cyanotype.interaction.Selecting;
import io.github.profetgit.cyanotype.interaction.SelectionBox;
import io.github.profetgit.cyanotype.pick.BoxFilter;
import io.github.profetgit.cyanotype.placement.BlueprintLibrary;
import io.github.profetgit.cyanotype.placement.BlueprintSaver;
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
    private double dragDistance;
    /** Remove mode: pointing at a block of the preview lights it up and a click takes it out of the save. Ctrl+Z brings it back. */
    private boolean removeMode;
    /** What was read from the world (before any block was taken out), where its min corner is, and the blocks taken out as world positions, with the ones undone for redo. */
    private Blueprint base;
    private int[] origin = {0, 0, 0};
    private final it.unimi.dsi.fastutil.longs.LongArrayList removedStack = new it.unimi.dsi.fastutil.longs.LongArrayList(), redoStack = new it.unimi.dsi.fastutil.longs.LongArrayList();
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

    /** Where the picture itself is, inside its panel. */
    private int[] previewRect() {
        int[] p = previewPanel();
        return new int[]{p[0] + 8, p[1] + 22, p[2] - 16, p[3] - 22 - 24};
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
        captured = io.github.profetgit.cyanotype.blueprint.CellEdits.without(base, origin[0], origin[1], origin[2], removedSet);
        preview.setBlueprint(captured);
    }

    /** Takes the block of the preview under the pointer out of the save (remove mode). */
    private void removeHovered() {
        int cell = preview.hovered();
        int[] size = preview.boxSize();
        if (cell < 0 || size == null || base == null) return;
        int x = cell % size[0], z = (cell / size[0]) % size[2], y = cell / (size[0] * size[2]);
        long pos = net.minecraft.core.BlockPos.asLong(origin[0] + x, origin[1] + y, origin[2] + z);
        if (!removedSet.add(pos)) return;
        removedStack.add(pos);
        redoStack.clear();
        Sfx.play(Sfx.RELEASE, 1.1f);
        applyEdits();
    }

    /** Ctrl+Z: puts the last block taken out back. */
    private void undoRemoval() {
        if (removedStack.isEmpty()) {
            Sfx.play(Sfx.ERROR, 0.8f);
            return;
        }
        long pos = removedStack.removeLong(removedStack.size() - 1);
        removedSet.remove(pos);
        redoStack.add(pos);
        Sfx.play(Sfx.PRESS, 0.9f);
        applyEdits();
    }

    /** Ctrl+Y or Ctrl+Shift+Z: takes the block put back out again. */
    private void redoRemoval() {
        if (redoStack.isEmpty()) {
            Sfx.play(Sfx.ERROR, 0.8f);
            return;
        }
        long pos = redoStack.removeLong(redoStack.size() - 1);
        removedSet.add(pos);
        removedStack.add(pos);
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
        Blueprint bp = new Blueprint(meta, List.of(new io.github.profetgit.cyanotype.blueprint.Region(title.isBlank() ? "main" : title, r.x, r.y, r.z, r.sx, r.sy, r.sz, r.palette, r.blocks, r.blockEntities)));
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
                Cyanotype.LOG.error("Cannot save the blueprint", e);
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
        Ui.button(g, "sv#reset", resetX(pp), pp[1] + 5, RESET_W, 14, "Reset", null, mx, my, false, idle);
        Ui.button(g, "sv#remove", removeX(pp), pp[1] + 5, REMOVE_W, 14, "Remove blocks", null, mx, my, removeMode, idle && captured != null);
        int[] r = wide() ? previewRect() : new int[]{pp[0] + 8, pp[1] + 22, pp[2] - 16, pp[3] - 22 - 24};
        double progress = job != null && !job.done() ? job.progress() : -1;
        preview.draw(g, r[0], r[1], r[2], r[3], progress, mx, my);
        String hint = removedStack.isEmpty()
            ? (removeMode ? "Click a block to take it out" : "Drag to turn, right-drag to move, scroll to zoom")
            : "Took out " + removedStack.size() + (removedStack.size() == 1 ? " block" : " blocks") + ". Ctrl+Z puts it back";
        Ui.text(g, Ui.fit(hint, pp[2] - 16), pp[0] + 8, pp[1] + pp[3] - 17, Ui.withAlpha(removeMode ? Ui.CYAN : Ui.DIM, inner * 0.9f));
    }

    private static final int RESET_W = 44, REMOVE_W = 90;

    private int resetX(int[] pp) {
        return pp[0] + pp[2] - 10 - RESET_W;
    }

    private int removeX(int[] pp) {
        return resetX(pp) - 4 - REMOVE_W;
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
            if (captured != null && Ui.inside(mx, my, removeX(pp), pp[1] + 5, REMOVE_W, 14)) {
                removeMode = !removeMode;
                preview.setRemoveMode(removeMode);
                Sfx.play(Sfx.PRESS, removeMode ? 1.2f : 0.9f);
                return true;
            }
            int[] r = wide() ? previewRect() : new int[]{px() + 8, py() + 22, pw() - 16, ph() - 22 - 24};
            if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
                draggingPreview = true;
                dragDistance = 0;
                panningPreview = event.button() == 1 || event.button() == 2 || (event.modifiers() & 1) != 0;
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
            // a click that did not move is a removal, in remove mode; a drag is only the view turning
            if (removeMode && event.button() == 0 && dragDistance < 4) removeHovered();
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
            dragDistance += Math.abs(dx) + Math.abs(dy);
            preview.drag(dx, dy, panningPreview);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (previewShown()) {
            int[] r = wide() ? previewRect() : new int[]{px() + 8, py() + 22, pw() - 16, ph() - 22 - 24};
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
        return removedStack.size();
    }

    public boolean removing() {
        return removeMode;
    }

    /** Dev demo: undo and redo of the removals, as the keys do them. */
    public void undoRemovalForDemo() {
        undoRemoval();
    }

    public void redoRemovalForDemo() {
        redoRemoval();
    }

    public int[] anchor(String which) {
        return switch (which) {
            case "save" -> new int[]{saveX() + 45, buttonY() + 8};
            case "back" -> new int[]{backX() + 35, buttonY() + 8};
            case "trim", "ground" -> new int[]{px() + 20, py() + 44 + 86 + 5};
            case "reset" -> new int[]{resetX(previewPanel()) + RESET_W / 2, previewPanel()[1] + 12};
            case "remove" -> new int[]{removeX(previewPanel()) + REMOVE_W / 2, previewPanel()[1] + 12};
            case "preview" -> {
                int[] r = wide() ? previewRect() : new int[]{px() + 8, py() + 22, pw() - 16, ph() - 22 - 24};
                yield new int[]{r[0] + r[2] / 2, r[1] + r[3] / 2};
            }
            default -> new int[]{0, 0};
        };
    }
}
