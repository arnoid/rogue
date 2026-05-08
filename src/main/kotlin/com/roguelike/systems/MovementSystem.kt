/**
 * Backward-compatibility alias. The real implementation lives in core.systems.
 * This file will be removed in a follow-up cleanup.
 */
package com.roguelike.systems

@Deprecated("Use com.roguelike.core.systems.MovementSystem", ReplaceWith("com.roguelike.core.systems.MovementSystem"))
typealias MovementSystem = com.roguelike.core.systems.MovementSystem
