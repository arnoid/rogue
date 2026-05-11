package com.roguelike.generation

/**
 * Represents a connection point on a submap face.
 */
data class Socket(
    val localPosition: Vector3Int,
    val direction: Vector3Int,
    val tag: String,
    var state: SocketState = SocketState.OPEN
)

enum class SocketState {
    OPEN,
    CONNECTED,
    SEALED
}

