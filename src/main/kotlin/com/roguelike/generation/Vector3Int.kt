package com.roguelike.generation

/**
 * Simple immutable 3D integer vector for grid coordinates and direction normals.
 */
data class Vector3Int(val x: Int, val y: Int, val z: Int) {
    operator fun plus(other: Vector3Int) = Vector3Int(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3Int) = Vector3Int(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Int) = Vector3Int(x * scalar, y * scalar, z * scalar)
    fun negate() = Vector3Int(-x, -y, -z)

    /**
     * Rotate this direction vector 90° clockwise around Z axis.
     * (x,y) -> (y, -x)
     */
    fun rotateCW90() = Vector3Int(y, -x, z)

    /**
     * Rotate this direction vector N * 90° clockwise around Z.
     * @param steps number of 90° CW rotations (0..3)
     */
    fun rotateCW(steps: Int): Vector3Int {
        var v = this
        repeat(steps and 3) { v = v.rotateCW90() }
        return v
    }

    companion object {
        // ...existing code...
        val ZERO = Vector3Int(0, 0, 0)
        val UP = Vector3Int(0, 0, 1)
        val DOWN = Vector3Int(0, 0, -1)
        val NORTH = Vector3Int(0, 1, 0)
        val SOUTH = Vector3Int(0, -1, 0)
        val EAST = Vector3Int(1, 0, 0)
        val WEST = Vector3Int(-1, 0, 0)

        val ALL_DIRECTIONS = listOf(UP, DOWN, NORTH, SOUTH, EAST, WEST)
    }
}

