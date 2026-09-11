plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.clikt)
    implementation(libs.commons.imaging)
    implementation(libs.metadata.extractor)
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.pbungert.geoimport.desktop.MainKt")
    applicationName = "geoimport"
}
