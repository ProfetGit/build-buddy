plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom") version "1.18.2" apply false
}

stonecutter active "26.3"

tasks.register<Copy>("dist") {
    group = "build"
    description = "Builds every target and copies the jars into dist/"
    stonecutter.versions.forEach { v ->
        val p = project(":${v.project}")
        dependsOn(p.tasks.named("build"))
        from(p.layout.buildDirectory.dir("libs")) {
            include("buildbuddy-*.jar")
            exclude("*-sources.jar", "*-dev.jar")
        }
    }
    into(layout.projectDirectory.dir("dist"))
}
