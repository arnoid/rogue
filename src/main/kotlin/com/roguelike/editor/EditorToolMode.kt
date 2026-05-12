package com.roguelike.editor

/** Active building tool in the map editor. */
enum class EditorToolMode {
    /** No special tool — normal paint/select behaviour. */
    NONE,
    /** Flood-fill floors bounded by walls. */
    FILL,
    /** Click-drag a rectangle to outline with walls. */
    ROOM
}

