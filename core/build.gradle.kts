plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Consumed by the Android app (minSdk 36) as well as the desktop CLI, so the
// bytecode level has to match app's compileOptions and nothing here may touch
// android.* / androidx.* or APIs missing from the Android class library
// (notably javax.xml.stream — SAX and DOM are available, StAX is not).
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(libs.junit)
}
