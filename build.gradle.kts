// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.50" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}

// Keep Gradle outputs outside the workspace and isolate each invocation to avoid stale file locks.
// Use D drive by default to save C drive space
val baseExternalBuildRoot = java.io.File(
    System.getenv("SMARTSCALE_BUILD_ROOT") ?: "D:\\SmartScaleBuild",
    "SmartScaleBuild"
)
// Keep IDE builds stable for artifact redirect resolution, while CLI builds use unique paths
// to avoid Windows file-lock collisions (e.g. R.jar cannot be deleted).
val invokedFromIde = (
    gradle.startParameter.projectProperties["android.injected.invoked.from.ide"]
        ?: System.getProperty("android.injected.invoked.from.ide")
) == "true"

val buildRunId = System.getenv("SMARTSCALE_BUILD_RUN_ID")
    ?.takeIf { it.isNotBlank() }
    ?: if (invokedFromIde) {
        "stable"
    } else {
        "run-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
    }
val externalBuildRoot = baseExternalBuildRoot.resolve(buildRunId)

allprojects {
    val relativeProjectPath = project.path.removePrefix(":").replace(":", "/")
    layout.buildDirectory.set(externalBuildRoot.resolve(relativeProjectPath))
}

tasks.register("clean", org.gradle.api.tasks.Delete::class) {
    delete(externalBuildRoot)
}
