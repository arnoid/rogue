plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.roguelike"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val gdxVersion = "1.14.0"

dependencies {
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    implementation("com.kotcrab.vis:vis-ui:1.5.3")
    implementation("io.github.libktx:ktx-scene2d:1.12.1-rc1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.roguelike.MainKt")
}

tasks.withType<JavaExec> {
    // Required for macOS to run LibGDX apps properly
    jvmArgs("-XstartOnFirstThread")
    workingDir = file("src/main/resources")

    // Forward selected -D system properties from the Gradle invocation to the
    // launched JVM so e.g. `gradle run -Drogue.lightlog=1` actually enables
    // lighting diagnostics inside the running game.
    listOf("rogue.lightlog").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
