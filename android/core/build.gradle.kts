plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure Kotlin/JVM, no Android dependency — this is the wire-format contract
// with the web app, and it needs to build and run its golden-vector tests on
// a plain JVM in CI without an Android emulator.
//
// Targets JVM 17 bytecode (what D8/AGP expects when :app later depends on
// this) without requiring an actual JDK 17 *installation* — jvmToolchain()
// does the latter and fails on a machine that only has JDK 21, whereas
// compilerOptions.jvmTarget just tells the JDK 21 compiler what bytecode
// version to emit.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
