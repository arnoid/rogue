/**
 * Backward-compatibility alias. The real implementation has moved to core.systems.
 * This file will be removed in a follow-up cleanup.
 */
package com.roguelike.systems

@Deprecated("Use com.roguelike.core.systems.InteractionSystem", ReplaceWith("com.roguelike.core.systems.InteractionSystem"))
typealias InteractionSystem = com.roguelike.core.systems.InteractionSystem
