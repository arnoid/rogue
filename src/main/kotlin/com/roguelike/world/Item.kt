package com.roguelike.world

import com.badlogic.gdx.graphics.Color
import java.util.UUID

interface Item {
    val id: String
    val type: String
    val color: Color
    val name: String
}

data class KeyItem(
    override val id: String = UUID.randomUUID().toString(),
    override val type: String = "Key",
    override val color: Color = Color.WHITE,
    override val name: String = "Key"
) : Item
