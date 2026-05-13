package com.roguelike.core.systems

/**
 * Returns the angular attenuation factor for a direction relative to a cone axis.
 *
 *   dot         — cosine of the angle between the direction and the cone axis
 *   cosHardEdge — cos(halfConeDeg)              = inner hard boundary
 *   cosSoftEdge — cos(halfConeDeg + featherDeg) = outer soft boundary
 *
 * Returns 1f when fully inside the hard cone (dot >= cosHardEdge),
 * 0f when outside the soft boundary (dot < cosSoftEdge),
 * and a linear interpolation in the penumbra zone between them.
 *
 * Guard: callers must ensure cosHardEdge > cosSoftEdge. When featherDeg == 0
 * pass cosSoftEdge = cosHardEdge - 1e-6f to avoid division by zero.
 */
internal fun softConeFactor(dot: Float, cosHardEdge: Float, cosSoftEdge: Float): Float =
    ((dot - cosSoftEdge) / (cosHardEdge - cosSoftEdge)).coerceIn(0f, 1f)
