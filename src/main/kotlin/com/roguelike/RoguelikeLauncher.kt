package com.roguelike

import com.roguelike.input.InputSystem
import com.roguelike.generation.BiomeDefinition
import com.roguelike.generation.BiomeIndex
import com.roguelike.rendering.Camera
import com.roguelike.rendering.vulkan.SwapChain
import com.roguelike.rendering.vulkan.VulkanContext
import com.roguelike.ui.SimpleUI
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * Application state machine replacing libGDX's Game/ApplicationAdapter pattern.
 * States: INIT → MENU → BIOME_PICKER → GAME | EDITOR → SHUTDOWN
 */
enum class AppState {
    INIT, MENU, BIOME_PICKER, GAME, EDITOR, SHUTDOWN
}

class RoguelikeLauncher(
    private val vulkanContext: VulkanContext,
    private val swapChain: SwapChain,
    private val inputSystem: InputSystem,
    private val camera: Camera
) {
    var state: AppState = AppState.INIT
        private set

    private var ui: SimpleUI? = null
    private var mainMenu: MainMenuScreen? = null
    private var biomePicker: BiomePickerScreen? = null
    private var game: RoguelikeGame? = null
    private var editor: MapEditor? = null

    /** Biome chosen in the picker; consumed when transitioning into GAME. */
    private var pendingBiome: BiomeDefinition? = null

    fun init() {
        ui = SimpleUI(vulkanContext, swapChain.renderPass)
        ui!!.screenWidth = swapChain.width.toFloat()
        ui!!.screenHeight = swapChain.height.toFloat()
        mainMenu = MainMenuScreen(ui!!, inputSystem)
        biomePicker = BiomePickerScreen(ui!!, inputSystem)
        state = AppState.MENU
    }

    /**
     * Called each frame to record rendering commands into the command buffer.
     * The render pass is already begun by Main.kt.
     */
    fun render(commandBuffer: VkCommandBuffer) {
        // Update screen dimensions in case of resize
        ui?.screenWidth = swapChain.width.toFloat()
        ui?.screenHeight = swapChain.height.toFloat()

        when (state) {
            AppState.MENU -> {
                ui?.beginFrame()
                val action = mainMenu?.render()
                ui?.render(commandBuffer)

                when (action) {
                    MenuAction.ARENA -> transitionTo(AppState.BIOME_PICKER)
                    MenuAction.EDITOR -> transitionTo(AppState.EDITOR)
                    MenuAction.QUIT -> {
                        state = AppState.SHUTDOWN
                    }
                    null -> {}
                }
            }
            AppState.BIOME_PICKER -> {
                ui?.beginFrame()
                val result = biomePicker?.render()
                ui?.render(commandBuffer)

                val backHit = result?.backPressed == true ||
                        inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
                val picked = result?.selected
                if (picked != null) {
                    // Resolve the picked biome's full definition (submap lists)
                    // and stash it for the GAME transition to consume.
                    pendingBiome = BiomeIndex.loadBiome(picked)
                    if (pendingBiome == null) {
                        println("[Launcher] biome '${picked.name}' failed to load; returning to menu")
                        transitionTo(AppState.MENU)
                    } else {
                        transitionTo(AppState.GAME)
                    }
                } else if (backHit) {
                    transitionTo(AppState.MENU)
                }
            }
            AppState.GAME -> {
                ui?.beginFrame()
                game?.render()
                ui?.render(commandBuffer)

                if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)) {
                    transitionTo(AppState.MENU)
                }
            }
            AppState.EDITOR -> {
                ui?.beginFrame()
                editor?.render()
                ui?.render(commandBuffer)

                if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) ||
                    editor?.exitRequested == true) {
                    transitionTo(AppState.MENU)
                }
            }
            else -> {}
        }
    }

    fun transitionTo(newState: AppState) {
        // Cleanup old state
        when (state) {
            AppState.GAME -> {
                // Release the captured cursor before tearing the game down
                // so the user can interact with the menu / window controls.
                inputSystem.setCursorCaptured(false)
                game?.dispose()
                game = null
            }
            AppState.EDITOR -> {
                editor?.dispose()
                editor = null
            }
            else -> {}
        }

        // Initialize new state
        when (newState) {
            AppState.BIOME_PICKER -> {
                // Force a fresh re-read of biomes.json each time we enter
                // the picker so manual edits show up without restarting.
                biomePicker?.reset()
            }
            AppState.GAME -> {
                game = RoguelikeGame(inputSystem, camera, ui!!)
                game!!.biome = pendingBiome
                pendingBiome = null
                game!!.show()
            }
            AppState.EDITOR -> {
                editor = MapEditor(inputSystem, camera, ui!!)
                editor!!.show()
            }
            else -> {}
        }

        state = newState
    }

    fun cleanup() {
        inputSystem.setCursorCaptured(false)
        game?.dispose()
        editor?.dispose()
        state = AppState.SHUTDOWN
        ui?.close()
        ui = null
    }
}
