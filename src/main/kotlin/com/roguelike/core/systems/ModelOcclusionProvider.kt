package com.roguelike.core.systems

fun interface ModelOcclusionProvider {
    fun isOccluded(
        ox: Float, oy: Float, oz: Float,
        tx: Float, ty: Float, tz: Float
    ): Boolean
}
