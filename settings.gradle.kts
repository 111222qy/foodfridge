pluginManagement {
    repositories {
        // 【新增】阿里云镜像：Gradle 插件下载加速
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 【核心修改】阿里云镜像：依赖库下载加速（必须放在第一位！）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }

        // 备用：如果阿里云找不到，再回原厂找
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "FoodFridgeApp"
include(":app")
include(":face-sdk")

// SDK 已经在项目内部 (app/src/main/java/com/foodres/sdk/)
// 不再需要外部SDK模块配置