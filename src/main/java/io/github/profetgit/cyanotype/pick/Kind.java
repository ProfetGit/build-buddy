package io.github.profetgit.cyanotype.pick;

/** What Smart Pick takes a block for. */
public enum Kind {
    /** Nothing there. */
    AIR,
    /** Made by a player, or at least not something the world grows: it can belong to a build. */
    BUILT,
    /** The ground and what is in it (dirt, stone, sand, ores). It joins a build only when it is a small separate lump. */
    TERRAIN,
    /** Wild plants and untouched leaves: never part of a build. */
    VEGETATION,
    /** A log or stem: a build's beam or a tree's trunk, told apart by the leaves around it. */
    LOG,
    /** Water and lava. */
    FLUID,
    /** The chunk is not loaded, so nothing is known. */
    UNLOADED
}
