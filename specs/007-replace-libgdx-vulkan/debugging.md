# Vulkan Debugging Guide (LWJGL + Java/Kotlin on Windows)

Companion notes for the FPS-recovery work in this feature. Covers two
diagnostic tools you should reach for *first* whenever a frame looks
wrong or runs slow:

1. **Vulkan validation layers** — catch API misuse, sync hazards,
   wrong layouts, missing barriers, descriptor mismatches, etc.
2. **RenderDoc** — capture a single frame and inspect every draw call,
   bound pipeline, descriptor set, vertex buffer, and texture.

---

## 1. Validation layers

### Already wired up

`VulkanContext.create(window, debug)` already requests
`VK_LAYER_KHRONOS_validation` and installs a `VkDebugUtilsMessengerEXT`
when the `debug` flag is true. `Main.kt` passes `debug = true` iff the
`rogue.debug` system property is set:

```kotlin
val debug = System.getProperty("rogue.debug") != null
```

### Enable for a run

From Gradle:

```powershell
.\gradlew.bat run "-Drogue.debug=1"
```

Or, when launching the produced jar directly:

```powershell
java -Drogue.debug=1 -jar build\libs\rogue.jar
```

### Make the SDK's validation layer discoverable

The validation layer ships with the **Vulkan SDK** from LunarG. Install
it (https://vulkan.lunarg.com/sdk/home#windows). Two env-vars must point
at the SDK's `Bin` directory **before** the JVM starts, otherwise
`vkEnumerateInstanceLayerProperties` will not list the layer and our
runtime check (`hasValidationLayer()`) silently falls back to no
validation:

```powershell
$env:VK_LAYER_PATH = "C:\VulkanSDK\1.3.290.0\Bin"
$env:PATH = "C:\VulkanSDK\1.3.290.0\Bin;$env:PATH"
.\gradlew.bat run "-Drogue.debug=1"
```

(Replace `1.3.290.0` with your installed version.)

You'll know it worked when the console prints
`[Vulkan] Validation layer enabled` (or whatever your debug-callback
formatter emits) and you start seeing `[Validation]` warnings on every
real API mistake.

### What the messages look like

Output format from our messenger:

```
[Validation:ERROR] VUID-VkSubmitInfo-pCommandBuffers-00075:
  ...long descriptive message linking to the spec...
```

Treat *every* `ERROR` and *most* `WARNING` lines as bugs. The "best
practices" layer (also enabled with `-Drogue.debug.bestPractices`, if
you want to add a switch) is noisier but spotlights perf foot-guns like
`vkBeginCommandBuffer` with no usage flags, or
`VK_IMAGE_LAYOUT_GENERAL` where a more specific layout would let the
driver fast-path.

---

## 2. RenderDoc on an LWJGL/Java application

RenderDoc (https://renderdoc.org/) attaches to a *process*, captures
the next presented frame, and lets you scrub through every draw call —
exactly what you need to verify "is the GPU actually drawing the
geometry I expect, or is the CPU just churning?".

### The Windows-specific gotcha

RenderDoc expects to launch your executable directly. With Java apps
you can NOT just point it at `gradlew.bat` — Gradle forks a JVM that
RenderDoc never sees, and the capture key (`F12`) does nothing.

**Solution:** point RenderDoc at the JVM's `java.exe` and pass the same
classpath + main-class Gradle would.

### Step-by-step

1. Install RenderDoc. Launch the Qt UI (`qrenderdoc.exe`).

2. In the **Launch Application** tab, fill in:

   | Field | Value |
   |---|---|
   | **Executable Path** | `C:\Path\To\Your\JDK\bin\java.exe` (the one in `gradle/gradle-daemon-jvm.properties` or whatever Gradle picks) |
   | **Working Directory** | `C:\Users\w196818\work\workspace\rogue` |
   | **Command-line Arguments** | see below |

3. Get the exact JVM args + classpath Gradle uses. The easiest way:

   ```powershell
   .\gradlew.bat run --dry-run | Out-Null   # populate caches
   .\gradlew.bat run "--debug" 2>&1 | Select-String -Pattern "Starting process 'Gradle Worker"
   ```

   Or, simpler: build a fat jar once and point RenderDoc at that.
   Recommended — add to `build.gradle.kts` if not already there:

   ```kotlin
   tasks.register<Jar>("fatJar") {
       archiveClassifier.set("all")
       manifest { attributes["Main-Class"] = "com.roguelike.MainKt" }
       from(sourceSets.main.get().output)
       dependsOn(configurations.runtimeClasspath)
       from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
       duplicatesStrategy = DuplicatesStrategy.EXCLUDE
   }
   ```

   Then build with `.\gradlew.bat fatJar` and use these RenderDoc fields:

   | Field | Value |
   |---|---|
   | **Command-line Arguments** | `-Drogue.debug=1 -jar build\libs\rogue-all.jar` |

4. **Capture Options** tab → enable:
   - ✅ *Capture Child Processes* (Gradle/JVM may spawn children)
   - ✅ *Allow Vsync* (leave on — we want realistic frame timing)
   - ✅ *Capture all cmd lists* (defensive)

5. Click **Launch**. When the game window appears, press **F12** to
   capture the next frame. The capture appears in the thumbnails strip;
   double-click to open.

### What to look for in a capture

In the **Event Browser** (left pane) you'll see every Vulkan call we
made for the captured frame. Health checklist after Steps 1-6:

| Symptom | What to inspect |
|---|---|
| 10 FPS but only a handful of draws | **Pipeline Statistics** (right pane). If `Vertices In` is in the millions for a single `vkCmdDraw`, your CPU vertex feed is the bottleneck (Step 1/5). |
| 10 FPS with thousands of draw calls | You regressed. We should be at 3 draws/frame (`gpu`, `lit`, `ui`). |
| Black screen mid-game | Find the first `vkCmdDraw` after the last `vkCmdBeginRenderPass`. Check **Mesh Viewer** → VS Input / VS Output. If VS Input has data but VS Output is empty, the VP matrix is wrong (push-constant overlap). |
| Lighting wrong | Pick a fragment in the **Pixel History** view; trace which lights contributed. SSBO contents are visible in **Resource Inspector** → click the shadow-tri SSBO. |
| Hidden voxel faces still drawn | Mesh Viewer → triangle count per draw call. With Step 5 it should drop 50-80% vs. pre-cull capture. |

### Quick performance sanity numbers (Step 1-3-5 should give)

On a mid-range GPU (e.g. GTX 1660 / RX 5600), 1080p, single submap:

| Metric | Before | Target after Steps 1+3+5 |
|---|---|---|
| FPS | ~10 | 60–144 (vsync-bound) |
| CPU frame time | 100 ms | 4–12 ms |
| `RoguelikeGame.renderWorld` | ~80 ms | <2 ms |
| `uploadLighting` | ~15 ms | 1–3 ms |
| Triangles submitted/frame | 800k–2M | 100k–300k (after voxel cull) |
| Heap allocations/sec | millions | thousands |

If you don't see at least a 4× FPS bump after this round, capture a
frame in RenderDoc and check the per-stage timings in the **Timeline**
view — the bottleneck has likely moved (good problem to have).

---

## 3. Next architectural steps (Step 2 in the FPS-recovery plan)

Steps 1+3+5 attacked the *symptoms* (per-vertex CPU allocations, full
grid iteration, unculled voxel meshes) without touching the rendering
architecture. The *cure* for that class of CPU-bound bottleneck is:

1. Upload each unique mesh to a `DEVICE_LOCAL` GPU vertex+index buffer
   exactly once at `AssetLoader.loadModel` time (use VMA, you already
   have it).
2. Per draw, push a 4×4 model matrix via push constants (you already
   reserve 64 B for the VP matrix — bump the range to 128 B and pack
   `mat4 model` after it), or use an instance-data SSBO indexed by
   `gl_InstanceIndex`.
3. Issue one `vkCmdDrawIndexed` per visible instance (or — better —
   group by mesh and issue one `vkCmdDrawIndexedIndirect` per mesh
   type with the instance count).
4. Once steps 1–3 are in, `SimpleUI`'s shared `gpuVertexBuffer` is no
   longer the hot path. At that point bump `FRAMES_IN_FLIGHT` to 2.

That's not a "fix the bottleneck" change — it's a "switch the engine
to a modern Vulkan renderer" change. Worth doing once the current
optimisations have given you the breathing room to architect it
properly.

