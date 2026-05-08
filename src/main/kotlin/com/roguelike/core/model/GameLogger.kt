package com.roguelike.core.model

/**
 * Simple logging abstraction so core systems stay LibGDX-free.
 * Implementations in the infrastructure layer can delegate to Gdx.app.log().
 */
fun interface GameLogger {
    fun log(tag: String, message: String)

    companion object {
        /** No-op logger for tests or when logging is not needed. */
        val NOOP = GameLogger { _, _ -> }
    }
}

