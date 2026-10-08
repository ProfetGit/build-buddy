package io.github.profetgit.cyanotype.paste;

/** What {@link Paste} needs of a running paste: the server-side {@link PasteJob} (a world the player hosts) or the {@link CommandPasteJob} (a server, as an operator). */
public interface PasteRun {
    PasteJob.State state();

    long total();

    long placed();

    long same();

    long unloaded();

    String stopped();

    double progress();

    int undoable();

    void stop(String why);

    boolean undoPending();

    void beginUndo();
}
