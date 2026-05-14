package com.roguelike

import com.roguelike.input.InputSystem
import com.roguelike.rendering.Camera
import com.roguelike.rendering.vulkan.SwapChain
import com.roguelike.rendering.vulkan.VulkanContext
import com.roguelike.ui.SimpleUI
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * Application state machine replacing libGDX's Game/ApplicationAdapter pattern.
 * States: INIT → MENU → GAME | EDITOR → SHUTDOWN
 */
enum class AppState {
    INIT, MENU, GAME, EDITOR, SHUTDOWN
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
    private var game: RoguelikeGame? = null
    private var editor: MapEditor? = null

    fun init() {
        ui = SimpleUI(vulkanContext, swapChain.renderPass)
        ui!!.screenWidth = swapChain.width.toFloat()
        ui!!.screenHeight = swapChain.height.toFloat()
        mainMenu = MainMenuScreen(ui!!, inputSystem)
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
                    MenuAction.ARENA -> transitionTo(AppState.GAME)
                    MenuAction.EDITOR -> transitionTo(AppState.EDITOR)
                    MenuAction.QUIT -> {
                        state = AppState.SHUTDOWN
                    }
                    null -> {}
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
            AppState.GAME -> {
                game = RoguelikeGame(inputSystem, camera, ui!!)
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
        game?.dispose()
        editor?.dispose()
        state = AppState.SHUTDOWN
        ui?.close()
        ui = null
    }
}
