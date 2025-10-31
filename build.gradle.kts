plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "ru.codeplugin"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation("org.yaml:snakeyaml:2.2")

    intellijPlatform {
        create("IC", "2025.1.4.1")                    // Платформа
        bundledPlugin("Git4Idea")                      // <-- чтобы работали git4idea.*
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // Если позже понадобятся Kotlin-инспекции, добавите:
        // bundledPlugin("org.jetbrains.kotlin")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"                         // IC 2025.1
        }
        changeNotes = "Initial version"
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    // patchPluginXml() не нужен в плагине v2.x — всё через pluginConfiguration
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}
