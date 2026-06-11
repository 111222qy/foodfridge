plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.foodfridge"
    compileSdk = 34
    ndkVersion = "25.1.8937393"

    defaultConfig {
        applicationId = "com.foodfridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "API_BASE_URL", "\"https://api-v4.debug.packertec.com/\"")
        buildConfigField("String", "SM4_KEY", "\"Z5S0FQBZHTHCHHOW\"")
        buildConfigField("String", "SM4_IV", "\"VGSHUMIOL409QQCF\"")
        buildConfigField("String", "APISIX_HEADER", "\"djdhJSJSHDHQK2,66556sskakjdajdj@@curls\"")
        buildConfigField("String", "ACTIVATION_CODE", "\"TEST_ACTIVATION_CODE_001\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            // RK3568 是 arm64-v8a 架构
            abiFilters.add("arm64-v8a")
        }
        
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kapt 配置
kapt {
    correctErrorTypes = true
    useBuildCache = true
    javacOptions {
        option("--add-modules=jdk.compiler")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED")
        option("--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED")
    }
}

dependencies {
    implementation(project(":face-sdk"))

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    implementation(files("libs\\api.jar"))
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Crypto
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Compatibility alias for legacy CI/local commands that still call :app:testClasses.
tasks.register("testClasses") {
    group = "verification"
    description = "Compatibility task. Assembles unit-test classes for debug and release variants."
    dependsOn("assembleDebugUnitTest", "assembleReleaseUnitTest")
}