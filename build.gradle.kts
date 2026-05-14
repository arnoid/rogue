plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.roguelike"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val lwjglVersion = "3.4.1"
val jomlVersion = "1.10.8"
val imguiVersion = "1.87.6"

dependencies {
    // LWJGL BOM
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    // LWJGL modules (API)
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-vulkan")
    implementation("org.lwjgl:lwjgl-vma")
    implementation("org.lwjgl:lwjgl-stb")
    implementation("org.lwjgl:lwjgl-shaderc")
    implementation("org.lwjgl:lwjgl-openal")
    implementation("org.lwjgl:lwjgl-assimp")

    // LWJGL natives (Windows)
    runtimeOnly("org.lwjgl:lwjgl::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-vma::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-stb::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-shaderc::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-openal::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-assimp::natives-windows")

    // LWJGL natives (Linux)
    runtimeOnly("org.lwjgl:lwjgl::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-vma::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-stb::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-shaderc::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-openal::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-assimp::natives-linux")

    // LWJGL natives (macOS x86_64)
    runtimeOnly("org.lwjgl:lwjgl::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-vma::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-stb::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-shaderc::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-openal::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-assimp::natives-macos")

    // LWJGL natives (macOS ARM64)
    runtimeOnly("org.lwjgl:lwjgl::natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-vma::natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-stb::natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-shaderc::natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-openal::natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-assimp::natives-macos-arm64")

    // Math
    implementation("org.joml:joml:$jomlVersion")

    // Dear ImGui
    implementation("io.github.spair:imgui-java-binding:$imguiVersion")
    implementation("io.github.spair:imgui-java-lwjgl3:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-windows:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-linux:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-macos:$imguiVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// T002: Compile GLSL shaders to SPIR-V via shaderc
tasks.register("compileShaders") {
    group = "build"
    description = "Compile Vulkan GLSL shaders to SPIR-V using shaderc"

    val shaderDir = file("src/main/resources/shaders")
    val outputDir = file("build/resources/main/shaders")

    inputs.dir(shaderDir)
    outputs.dir(outputDir)

    doLast {
        outputDir.mkdirs()
        val shaderFiles = shaderDir.listFiles()?.filter {
            it.name.endsWith(".vert.glsl") || it.name.endsWith(".frag.glsl")
        } ?: emptyList()

        if (shaderFiles.isEmpty()) {
            logger.warn("No shader files found in $shaderDir")
            return@doLast
        }

        shaderFiles.forEach { shaderFile ->
            val outputName = shaderFile.name.removeSuffix(".glsl") + ".spv"
            val outputFile = File(outputDir, outputName)
            logger.lifecycle("Compiling shader: ${shaderFile.name} -> $outputName")

            // Use shaderc via LWJGL at build time by invoking a helper class
            // For now, we use the Gradle JavaExec approach
            val shaderType = if (shaderFile.name.endsWith(".vert.glsl")) "vertex" else "fragment"
            val proc = ProcessBuilder("glslc", "-fshader-stage=$shaderType", shaderFile.absolutePath, "-o", outputFile.absolutePath)
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw GradleException("Shader compilation failed for ${shaderFile.name}")
            }
        }
    }
}

// T003: Test configuration for Vulkan
tasks.test {
    useJUnitPlatform()
    workingDir = file("src/main/resources")
    jvmArgs(
        "-Djava.awt.headless=true"
    )
    // Run visual tests in their own fork to avoid thread issues
    forkEvery = 1
    maxParallelForks = 1
}

// T004: Application configuration
application {
    mainClass.set("com.roguelike.MainKt")
}

tasks.withType<JavaExec> {
    workingDir = file("src/main/resources")

    // Forward selected -D system properties from the Gradle invocation to the
    // launched JVM so e.g. `gradle run -Drogue.lightlog=1` actually enables
    // lighting diagnostics inside the running game.
    listOf("rogue.lightlog", "rogue.debug").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
