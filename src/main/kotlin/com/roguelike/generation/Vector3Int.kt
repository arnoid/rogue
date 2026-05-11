package com.roguelike.generation

/**
 * Simple immutable 3D integer vector for grid coordinates and direction normals.
 */
data class Vector3Int(val x: Int, val y: Int, val z: Int) {
    operator fun plus(other: Vector3Int) = Vector3Int(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3Int) = Vector3Int(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Int) = Vector3Int(x * scalar, y * scalar, z * scalar)
    fun negate() = Vector3Int(-x, -y, -z)

    companion object {
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

