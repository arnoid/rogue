/**
 * Backward-compatibility type aliases so existing code that imports
 * com.roguelike.world.* continues to compile while we migrate call
 * sites to com.roguelike.core.model.* incrementally.
 *
 * These aliases will be removed in a follow-up cleanup commit once all
 * call sites have been updated.
 */
package com.roguelike.world

typealias World     = com.roguelike.core.model.World
typealias WorldNode = com.roguelike.core.model.WorldNode
typealias Actor     = com.roguelike.core.model.Actor
typealias Player    = com.roguelike.core.model.Player
typealias Item      = com.roguelike.core.model.Item
typealias KeyItem   = com.roguelike.core.model.KeyItem
