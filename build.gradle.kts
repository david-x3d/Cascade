buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9.3 ships Kotlin 2.2.10; pin the built-in Kotlin support to 2.4.10.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.0" apply false
}
