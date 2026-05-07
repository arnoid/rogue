plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.roguelike"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val gdxVersion = "1.12.1"

dependencies {
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
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
}
