<<<<<<< HEAD
buildscript {
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        // Update the Android Gradle Plugin to a version that supports desugaring
        classpath("com.android.tools.build:gradle:8.1.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
=======
plugins {
    // ✅ Android Gradle Plugin 8.7.0 — supports compileSdk 36 & JDK 17
    id("com.android.application") version "8.11.1" apply false
    // ✅ Kotlin 2.0.21 — required for compileSdk 36 & Flutter 3.10+
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // ✅ Google Services plugin — REQUIRED for Firebase
    id("com.google.gms.google-services") version "4.3.15" apply false
>>>>>>> workspace
}

allprojects {
    repositories {
        google()
        mavenCentral()
<<<<<<< HEAD
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
=======
        // ⚠️ Some Flutter plugins still need jitpack
        maven { url = uri("https://jitpack.io") }
    }
}

val newBuildDir: Directory = rootProject.layout.buildDirectory
    .dir("../../build")
    .get()
>>>>>>> workspace
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}

subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}