package com.roguelike.core

import com.roguelike.core.model.CandleItem
import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.ItemFactory
import com.roguelike.core.model.ItemTags
import com.roguelike.core.model.Player
import com.roguelike.core.model.World
import com.roguelike.core.model.isLit
import com.roguelike.core.model.setLit
import com.roguelike.core.systems.InteractionSystem
import com.roguelike.utils.ItemCatalogLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropPickupTest {

    @BeforeAll
    fun setup() {
        ItemCatalog.clear()
        val defs = ItemCatalogLoader.loadFromInternal("items/items.json")
        check(defs.isNotEmpty())
    }

    @Test
    fun dropPlacesItemOnActorCell_andSetsFacingFromActor() {
        val world = World(9, 9, 3)
        val sys = InteractionSystem(world)
        val player = Player().also {
            it.position.set(4f, 4f, 1f)
            it.facingDirection.set(1f, 0f, 0f) // facing east
        }
        val candle = ItemFactory.create("Candle")!!
        player.inventory.add(candle)

        val ok = sys.drop(player, candle)
        assertTrue(ok)
        assertFalse(candle in player.inventory)

        val node = world.getNode(4, 4, 1)!!
        assertTrue(candle in node.items)
        assertEquals(1f, candle.facingX, 0.0001f)
        assertEquals(0f, candle.facingY, 0.0001f)
    }

    @Test
    fun pickupFromAdjacentCell_movesItemToInventory() {
        val world = World(9, 9, 3)
        val sys = InteractionSystem(world)
        val player = Player().also { it.position.set(4f, 4f, 1f) }
        val candle = ItemFactory.create("Candle")!!
        world.getNode(5, 4, 1)!!.items.add(candle)

        sys.interact(player, player.facingDirection)
        assertTrue(candle in player.inventory)
        assertFalse(candle in world.getNode(5, 4, 1)!!.items)
    }

    @Test
    fun pickupFromCurrentCell_takesPrecedenceOverAdjacent() {
        val world = World(9, 9, 3)
        val sys = InteractionSystem(world)
        val player = Player().also { it.position.set(4f, 4f, 1f) }
        val onCell = ItemFactory.create("Key")!!
        val nearby = ItemFactory.create("Candle")!!
        world.getNode(4, 4, 1)!!.items.add(onCell)
        world.getNode(5, 4, 1)!!.items.add(nearby)

        sys.interact(player, player.facingDirection)
        assertTrue(onCell in player.inventory)
        assertTrue(nearby in world.getNode(5, 4, 1)!!.items)
    }

    @Test
    fun droppedLitCandleIsOnWorldNode() {
        val world = World(15, 15, 3)
        val candle = ItemFactory.create("Candle")!!.also {
            it.setLit(true)
            it.facingX = 1f
            it.facingY = 0f
        }
        world.getNode(7, 7, 1)!!.items.add(candle)

        // Candle is present on the world node and marked lit.
        val node = world.getNode(7, 7, 1)!!
        assertTrue(candle in node.items)
        assertTrue(candle.isLit())
    }

    @Test
    fun unlitDroppedItem_isNotLit() {
        val world = World(9, 9, 3)
        val candle = ItemFactory.create("Candle")!! // unlit by default
        world.getNode(4, 4, 1)!!.items.add(candle)

        assertFalse(candle.isLit(), "dropped candle should be unlit by default")
    }
}

