package com.roguelike.generation

/**
 * Debug UI for procedural generation.
 * TODO: Rewrite with Dear ImGui (Phase 6)
 */
class GenerationDebugUI : DebugUICallback {
    override fun showCandidate(candidate: DebugCandidate, onConfirm: () -> Unit, onReject: () -> Unit) {
        println("[GenerationDebug] Candidate: $candidate")
        onConfirm() // Auto-confirm for now
    }
    override fun hideDebugUI() {}
}
