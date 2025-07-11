plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.origin"
    compileSdk = 36

    packagingOptions {
        exclude("META-INF/INDEX.LIST")
        excludes += "META-INF/io.netty.versions.properties"
    }

    defaultConfig {
        applicationId = "com.example.origin"
        minSdk = 26
        targetSdk = 36
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.androidx.media3.exoplayer)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    dependencies {
        // NanoHTTPD for HTTP server
        implementation ("org.nanohttpd:nanohttpd:2.3.1")
        implementation("com.google.android.exoplayer:exoplayer-ui:2.18.5")
        implementation("com.google.android.exoplayer:exoplayer:2.18.5")
        implementation("com.google.android.exoplayer:exoplayer-ui:2.18.5")
            implementation ("androidx.media3:media3-exoplayer:1.1.1")
            implementation ("androidx.media3:media3-ui:1.1.1")
        implementation("org.java-websocket:Java-WebSocket:1.5.3")
        implementation("androidx.appcompat:appcompat:1.5.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
                implementation("org.java-websocket:Java-WebSocket:1.5.2")
        implementation("io.ktor:ktor-server-websockets:3.2.0")
        implementation("io.ktor:ktor-server-core:3.2.0")
        implementation("io.ktor:ktor-server-websockets:3.2.0")
        implementation("io.ktor:ktor-server-netty:3.2.0")
            implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
            implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
            implementation ("com.google.android.exoplayer:exoplayer:2.18.7")
            implementation ("com.google.code.gson:gson:2.9.1")

            implementation ("androidx.media3:media3-exoplayer:1.1.1")
            implementation ("androidx.media3:media3-ui:1.1.1")
            implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
        implementation("org.java-websocket:Java-WebSocket:1.5.2")
        implementation("com.squareup.okhttp3:okhttp:4.9.3")
        implementation("org.slf4j:slf4j-android:1.7.36")


        }

        // Your other dependencies



        // Java-WebSocket for WebSocket server
        implementation ("org.java-websocket:Java-WebSocket:1.5.3")

        // Your existing dependencies ...

            implementation ("org.slf4j:slf4j-api:1.7.36") // or your SLF4J version
              // Simple logger implementation


    }

