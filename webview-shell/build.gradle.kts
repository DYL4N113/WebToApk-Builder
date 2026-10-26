import org.gradle.jvm.tasks.Jar

plugins {
    id("com.android.library")
}

android {
    namespace = "com.dyl4n.webtoapk.shell"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

val shellClassesJar = tasks.register<Jar>("shellClassesJar") {
    dependsOn("compileDebugJavaWithJavac")
    from(layout.buildDirectory.dir("intermediates/javac/debug/classes"))
    archiveFileName.set("webview-shell-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("template-dex/input"))
}

tasks.register("exportTemplateDex") {
    group = "build"
    description = "Dexes the WebView shell library and exports it as app/src/main/assets/template.dex."
    dependsOn(shellClassesJar)

    doLast {
        val classesJar = shellClassesJar.get().archiveFile.get().asFile
        check(classesJar.isFile) { "Shell classes JAR not found: ${classesJar.absolutePath}" }
        val d8Jar = File(
            android.sdkDirectory,
            "build-tools/${android.buildToolsVersion}/lib/d8.jar",
        )
        check(d8Jar.isFile) { "D8 not found: ${d8Jar.absolutePath}" }

        val dexOutputDir = layout.buildDirectory.dir("template-dex/output").get().asFile
        check(dexOutputDir.isDirectory || dexOutputDir.mkdirs()) {
            "Unable to create D8 output directory: ${dexOutputDir.absolutePath}"
        }
        val classesDex = File(dexOutputDir, "classes.dex")
        check(!classesDex.exists() || classesDex.delete()) {
            "Unable to remove stale DEX output: ${classesDex.absolutePath}"
        }
        project.exec {
            commandLine(
                "java",
                "-cp",
                d8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--min-api",
                "26",
                "--output",
                dexOutputDir.absolutePath,
                classesJar.absolutePath,
            )
        }

        check(classesDex.isFile) { "D8 did not produce classes.dex." }

        val templateDex = rootProject.file("app/src/main/assets/template.dex")
        check(templateDex.parentFile.isDirectory || templateDex.parentFile.mkdirs()) {
            "Unable to create template output directory: ${templateDex.parentFile.absolutePath}"
        }
        classesDex.copyTo(templateDex, overwrite = true)
    }
}
