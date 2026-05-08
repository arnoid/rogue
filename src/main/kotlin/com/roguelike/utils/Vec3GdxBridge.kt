package com.roguelike.utils

import com.roguelike.core.math.Vec3

/** Bridge extensions for converting between core Vec3 and LibGDX Vector3.
 *  Lives in the infrastructure layer so core stays LibGDX-free. */

fun Vec3.toGdxVec(): com.badlogic.gdx.math.Vector3 =
    com.badlogic.gdx.math.Vector3(x, y, z)

fun com.badlogic.gdx.math.Vector3.toVec3(): Vec3 =
    Vec3(x, y, z)
