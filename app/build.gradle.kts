plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.origin"
    compileSdk = 36 // Recommended to keep this updated

    packagingOptions {
        exclude("META-INF/INDEX.LIST")
        excludes += "META-INF/io.netty.versions.properties" // Often needed for network libraries
    }

    defaultConfig {
        applicationId = "com.example.origin"
        minSdk = 26 // Keep your minimum SDK
        targetSdk = 36 // Match compileSdk
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // Use Java 11 for modern Android development
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11" // Match Java version
    }
}

// ALL YOUR DEPENDENCIES MUST BE INSIDE THIS SINGLE BLOCK
dependencies {
    // Kotlin standard library (recommended via BOM)
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.0")) // Using 1.9.0, or latest stable

    // AndroidX UI Components - using recent stable versions
    implementation("androidx.core:core-ktx:1.13.1") // Latest stable core-ktx
    implementation("androidx.appcompat:appcompat:1.7.0") // Latest stable appcompat
    implementation("com.google.android.material:material:1.12.0") // Latest stable Material Design
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Activity Result API (for picking files in MainActivity)
    implementation("androidx.activity:activity-ktx:1.9.0") // Latest stable activity-ktx

    // OkHttp for WebSocket client communication (used by WebSocketClientManager)
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Keeping 4.12.0 as you had it, or you can use 4.x or 5.x latest

    // LocalBroadcastManager (for communication between Service and Activity)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // REMOVED:
    // - Duplicate OkHttp and AndroidX versions
    // - okhttp-ws (functionality is in okhttp)
    // - Java-WebSocket, Ktor, NanoHTTPD (not used by Origin app logic)
    // - ExoPlayer/Media3 libraries (Origin app uses MediaExtractor directly)
    // - Gson, SLF4J (not used by current Origin app logic)
    // - Redundant libs.versions.toml aliases that conflict with direct declarations
}