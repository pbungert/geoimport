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

// --- Packaging -----------------------------------------------------------
//
// jpackage is invoked directly rather than through a wrapper plugin: it runs
// jlink itself, and the obvious plugin (org.beryx.runtime) cannot serialise
// for the configuration cache this project enables.
//
// jpackage only ever targets the OS it runs on, so producing all three
// platforms means a CI matrix, not a flag.

/**
 * A bundled exiftool, if one has been unpacked into src/dist/exiftool. It then
 * lands beside the installed app, which is where ExifToolGpsWriter.discover
 * looks first. Without it the CLI falls back to PATH and then to the built-in
 * writer, so the build works either way.
 */
val exifToolDir = layout.projectDirectory.dir("src/dist/exiftool")

/** JDK modules the app actually needs; keeps the bundled runtime small. */
val requiredModules = listOf(
    "java.base",
    "java.desktop", // ImageIO, reached through Commons Imaging
    "java.logging",
    "java.xml", // SAX, used by TrackParser
)

val jpackageTool: Provider<String> =
    extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.map {
        val bin = File(it.metadata.installationPath.asFile, "bin")
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) {
            "jpackage.exe"
        } else {
            "jpackage"
        }
        File(bin, exe).absolutePath
    }

/**
 * Self-contained app image: the CLI plus a trimmed JRE, so the target machine
 * needs no Java installed.
 */
val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a self-contained app image for the current OS"

    dependsOn(tasks.named("installDist"))

    val libDir = layout.buildDirectory.dir("install/geoimport/lib")
    val outDir = layout.buildDirectory.dir("jpackage")
    val mainClassName = application.mainClass
    val appName = application.applicationName
    val tool = jpackageTool
    val exifTool = exifToolDir.asFile
    val modules = requiredModules.joinToString(",")

    inputs.dir(libDir)
    outputs.dir(outDir)

    doFirst {
        // jpackage refuses to write into an existing image directory.
        outDir.get().asFile.deleteRecursively()

        val args = mutableListOf(
            tool.get(),
            "--type", "app-image",
            "--name", appName,
            "--app-version", "1.0",
            "--input", libDir.get().asFile.absolutePath,
            "--main-jar", "desktop.jar",
            "--main-class", mainClassName.get(),
            "--dest", outDir.get().asFile.absolutePath,
            "--add-modules", modules,
            "--java-options", "-Dfile.encoding=UTF-8",
        )
        if (exifTool.isDirectory) {
            args += listOf("--app-content", exifTool.absolutePath)
            logger.lifecycle("Bundling exiftool from ${exifTool.absolutePath}")
        } else {
            logger.lifecycle(
                "No bundled exiftool (see :desktop:fetchExifTool). " +
                    "The CLI will use PATH, then the built-in writer."
            )
        }
        commandLine(args)
    }
}

/**
 * Deliberately downloads nothing: the build must not require network access,
 * so this only explains where to put exiftool.
 */
tasks.register("fetchExifTool") {
    group = "distribution"
    description = "How to place exiftool so jpackageImage bundles it"
    val target = exifToolDir.asFile
    doLast {
        logger.lifecycle(
            """
            Unpack exiftool from https://exiftool.org into:
                ${target.absolutePath}

            Windows: the 64-bit "Windows Executable" zip. Keep exiftool.exe next
                     to its exiftool_files directory, and rename
                     exiftool(-k).exe to exiftool.exe.
            macOS/Linux: the Unix tar.gz - the 'exiftool' script plus lib/.
                     Relies on the system perl, which both platforms ship.

            Without it the CLI falls back to PATH, then to the built-in writer,
            which still covers RAF, JPEG and MOV.
            """.trimIndent()
        )
    }
}
